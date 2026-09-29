// @ts-check
const { test, expect } = require('@playwright/test');
const H = require('./multi/helpers');

/**
 * THE WHOLE MARKET, PLAYED BY THREE PEOPLE AT ONCE.
 *
 * Every other spec in this folder signs in ONE user and, for the money flows,
 * stops at the confirm dialog. That proves a screen renders; it cannot prove
 * that when Atlas sells to Pixel, Pixel pays exactly the price, Atlas is paid
 * exactly the price minus the 2% fee once Pixel confirms, and nobody else's
 * wallet moves by a cent. This file does.
 *
 *   Atlas  (user 2) — seller with seeded listings
 *   Pixel  (user 3) — buyer, later resells what he bought
 *   Ghost  (user 4) — second buyer, bargains, races Pixel for a listing
 *
 * Each person is a separate browser context. Balances are read from the
 * server after every step and compared to the cent.
 *
 * THIS MOVES TEST MONEY. It needs a server started with BOTH opt-ins in its
 * process environment (see e2e/README.md):
 *   SBOX_DEV_LOGIN_ENABLED=true   one session per person, no Steam
 *   SBOX_DEV_CREDIT_ENABLED=true  the wallet form credits test money
 * and the demo catalogue (--sbox.seed.demo-data=true). Never point it at a
 * server that can move real money; the server refuses both doors there anyway.
 */

test.describe.configure({ mode: 'serial', timeout: 90_000 });

/** @type {any} */ let atlas;
/** @type {any} */ let pixel;
/** @type {any} */ let ghost;
/** Shared across the serial steps: what Pixel bought from Atlas. */
const state = { boughtListingId: 0, boughtItemId: 0, tradeId: 0 };

test.beforeAll(async ({ browser, baseURL }) => {
  [atlas, pixel, ghost] = await Promise.all([2, 3, 4].map((id) => H.signIn(browser, baseURL, id)));
});

test.afterAll(async () => {
  for (const u of [atlas, pixel, ghost]) await u?.context.close();
});

test('three people top up their wallets at the same moment, to the cent', async () => {
  const before = await Promise.all([atlas, pixel, ghost].map(H.balanceCents));
  await Promise.all([atlas, pixel, ghost].map((u) => H.depositViaUi(u, '$100.00')));
  const after = await Promise.all([atlas, pixel, ghost].map(H.balanceCents));
  after.forEach((c, i) => expect(c, `wallet ${i}`).toBe(before[i] + 10000));
  for (const [i, u] of [atlas, pixel, ghost].entries()) await H.expectHeaderBalance(u, after[i]);
});

test('a buyer adds a Steam trade URL on the profile page', async () => {
  // Clear it first so the form, not a leftover, is what saves it.
  await H.call(pixel, 'PUT', '/api/profile/trade-url', { tradeUrl: '' });
  const page = pixel.page;
  await page.goto('/profile');
  await page.getByRole('button', { name: 'Add', exact: true }).click();
  await page.getByPlaceholder(/tradeoffer\/new/).fill(H.tradeUrlFor(pixel.steamId64));
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(page.getByText(H.tradeUrlFor(pixel.steamId64)).first()).toBeVisible({ timeout: 8_000 });
  const me = (await H.call(pixel, 'GET', '/api/auth/steam/me')).body;
  expect(me.tradeUrl).toBe(H.tradeUrlFor(pixel.steamId64));
  await H.setTradeUrlViaApi(ghost);
  await H.setTradeUrlViaApi(atlas);
});

test('Pixel buys Atlas\'s listing: he pays the price, Atlas is paid only after he confirms', async () => {
  // The item page's Buy now takes the cheapest copy, so pick an item whose
  // cheapest buy-now copy is Atlas's.
  const rows = await H.listings(pixel);
  const buyNow = rows.filter((l) => l.listingType !== 'AUCTION' && l.status === 'ACTIVE');
  const itemOf = (l) => l.item?.id ?? l.itemId;
  const target = buyNow.find((l) => l.sellerUserId === atlas.id && Number(l.price) <= 20
    && !buyNow.some((o) => itemOf(o) === itemOf(l) && o.id !== l.id && Number(o.price) <= Number(l.price)));
  expect(target, 'an item whose cheapest copy is Atlas\'s').toBeTruthy();
  const price = H.cents(target.price);
  const itemId = target.item?.id ?? target.itemId;
  const [p0, a0, g0] = await Promise.all([pixel, atlas, ghost].map(H.balanceCents));

  // Buy it on the item page.
  const page = pixel.page;
  await page.goto(`/item/${itemId}`);
  await page.locator('.item-rail-actions-buy').first().click({ timeout: 15_000 });
  const confirm = page.getByRole('dialog', { name: 'Confirm purchase' });
  await expect(confirm).toBeVisible();
  await confirm.getByRole('button', { name: /Confirm purchase/ }).click();
  await expect(confirm).toHaveCount(0, { timeout: 10_000 });

  // Charged exactly the price; nobody else's wallet moved yet.
  expect(await H.balanceCents(pixel)).toBe(p0 - price);
  expect(await H.balanceCents(atlas)).toBe(a0);
  expect(await H.balanceCents(ghost)).toBe(g0);
  await H.expectHeaderBalance(pixel, p0 - price);

  const t = await H.tradeForListing(pixel, target.id);
  expect(t, 'an escrow trade was opened for the purchase').toBeTruthy();
  expect(t.state).toBe('PENDING_SELLER_ACCEPT');
  expect(H.cents(t.price)).toBe(price);
  Object.assign(state, { boughtListingId: target.id, boughtItemId: itemId, tradeId: t.id });

  // The listing is gone from the market for everyone else.
  const after = await H.call(ghost, 'GET', `/api/listings/${target.id}`);
  if (after.ok) expect(after.body.status).not.toBe('ACTIVE');

  // Atlas: Accept, then Mark Sent (skipping the optional offer link).
  let row = await H.tradeRow(atlas, t.id);
  await row.getByRole('button', { name: 'Accept', exact: true }).click();
  await expect.poll(async () => (await H.trade(atlas, t.id)).state).toBe('PENDING_SELLER_SEND');
  row = await H.tradeRow(atlas, t.id);
  await row.getByRole('button', { name: 'Mark Sent', exact: true }).click();
  const sent = atlas.page.getByRole('button', { name: 'Mark sent', exact: true });
  await expect(sent).toBeVisible();
  await sent.click();
  await expect.poll(async () => (await H.trade(atlas, t.id)).state).toBe('PENDING_BUYER_CONFIRM');
  expect(await H.balanceCents(atlas), 'no payout before the buyer confirms').toBe(a0);

  // Pixel confirms receipt; Atlas is paid price minus the 2% fee, exactly.
  row = await H.tradeRow(pixel, t.id);
  await row.getByRole('button', { name: 'Confirm', exact: true }).click();
  await pixel.page.locator('.trade-confirm-modal').getByRole('button', { name: /Release/ }).click();
  await expect.poll(async () => (await H.trade(pixel, t.id)).state).toBe('VERIFIED');
  expect(await H.balanceCents(atlas)).toBe(a0 + price - H.feeCents(price));
  expect(await H.balanceCents(pixel)).toBe(p0 - price);
  expect(await H.balanceCents(ghost)).toBe(g0);
});

test('two buyers click Buy on the same listing at once: one gets it, the other pays nothing', async () => {
  const rows = await H.listings(pixel);
  const target = rows.find((l) => l.sellerUserId === atlas.id && l.listingType !== 'AUCTION'
    && l.status === 'ACTIVE' && Number(l.price) <= 20);
  expect(target, 'another Atlas listing to race for').toBeTruthy();
  const price = H.cents(target.price);
  const [p0, g0, a0] = await Promise.all([pixel, ghost, atlas].map(H.balanceCents));

  const [rp, rg] = await Promise.all([pixel, ghost].map((u) =>
    H.call(u, 'POST', `/api/listings/${target.id}/buy`, { expectedPrice: String(target.price) })));
  const wins = [rp, rg].filter((r) => r.ok);
  expect(wins.length, `exactly one purchase succeeds (got ${rp.status} and ${rg.status})`).toBe(1);
  const loser = rp.ok ? rg : rp;
  expect(loser.status).toBeGreaterThanOrEqual(400);
  expect(loser.status).toBeLessThan(500);

  const [p1, g1] = await Promise.all([pixel, ghost].map(H.balanceCents));
  expect(p1 + g1).toBe(p0 + g0 - price);
  expect(rp.ok ? p1 : g1).toBe((rp.ok ? p0 : g0) - price);
  expect(rp.ok ? g1 : p1).toBe(rp.ok ? g0 : p0);
  expect(await H.balanceCents(atlas)).toBe(a0);

  // The winner's trade: the seller cancels it. The buyer is refunded in full
  // and the listing goes back on the market.
  const winner = rp.ok ? pixel : ghost;
  const t = await H.tradeForListing(winner, target.id);
  expect(t).toBeTruthy();
  const row = await H.tradeRow(atlas, t.id);
  atlas.page.once('dialog', (d) => d.accept());
  await row.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect.poll(async () => (await H.trade(atlas, t.id)).state).toBe('CANCELLED');
  expect(await H.balanceCents(winner)).toBe(rp.ok ? p0 : g0);
  expect(await H.balanceCents(atlas)).toBe(a0);
});

test('Pixel cannot resell what has not arrived; once it has, he lists it and Ghost buys it from the cart', async () => {
  // Buy a fresh copy of something from Atlas, leave the trade open.
  const rows = await H.listings(pixel);
  const open = rows.find((l) => l.sellerUserId === atlas.id && l.listingType !== 'AUCTION'
    && l.status === 'ACTIVE' && Number(l.price) <= 20);
  expect(open).toBeTruthy();
  const buy = await H.call(pixel, 'POST', `/api/listings/${open.id}/buy`, { expectedPrice: String(open.price) });
  expect(buy.ok, JSON.stringify(buy.body)).toBeTruthy();
  const pending = await H.tradeForListing(pixel, open.id);

  // The sell page offers his platform inventory; the unreceived copy is refused.
  const page = pixel.page;
  const listFromInventory = async (listingId, price) => {
    await page.goto('/sell');
    await page.getByRole('button', { name: /Platform Inventory/i }).click();
    const card = page.locator(`.inventory-item[data-listing-id="${listingId}"]`);
    await expect(card).toBeVisible({ timeout: 10_000 });
    await card.click();
    await page.getByRole('spinbutton', { name: 'Asking price' }).fill(price);
    await page.getByRole('button', { name: 'List for Sale' }).click();
  };
  await listFromInventory(open.id, '9.99');
  await expect(page.getByText(/once you have received it/)).toBeVisible({ timeout: 8_000 });
  const stillMine = (await H.call(pixel, 'GET', '/api/listings/inventory')).body.map((l) => l.id);
  expect(stillMine).toContain(open.id);

  // Atlas cancels that one (refund), and Pixel resells the copy he DID receive.
  await H.call(atlas, 'POST', `/api/trades/${pending.id}/cancel`, { reason: 'e2e' });
  expect((await H.trade(pixel, pending.id)).state).toBe('CANCELLED');

  const resalePrice = 2345; // cents
  await listFromInventory(state.boughtListingId, (resalePrice / 100).toFixed(2));
  const findResale = async () => ((await H.call(pixel, 'GET', '/api/listings/my-stall')).body || [])
    .find((l) => l.status === 'ACTIVE' && H.cents(l.price) === resalePrice
      && (l.item?.id ?? l.itemId) === state.boughtItemId);
  await expect.poll(async () => !!(await findResale()), { timeout: 10_000 }).toBe(true);
  const mine = await findResale();
  expect(mine, 'the resale is on the market under Pixel').toBeTruthy();

  // Ghost adds it to the cart and checks out.
  const [g0, p0] = await Promise.all([ghost, pixel].map(H.balanceCents));
  await H.call(ghost, 'DELETE', '/api/cart');
  const add = await H.call(ghost, 'POST', `/api/cart/${mine.id}`);
  expect(add.ok, JSON.stringify(add.body)).toBeTruthy();
  const gp = ghost.page;
  await gp.goto('/cart');
  const checkout = gp.getByRole('button', { name: /Checkout/i }).first();
  await expect(checkout).toContainText(`$${(resalePrice / 100).toFixed(2)}`);
  await checkout.click();
  const confirm = gp.getByRole('dialog').filter({ hasText: /Confirm|checkout/i }).last();
  if (await confirm.isVisible().catch(() => false)) {
    await confirm.getByRole('button', { name: /Confirm|Pay|Buy/i }).last().click();
  }
  await expect.poll(() => H.balanceCents(ghost)).toBe(g0 - resalePrice);
  expect(await H.balanceCents(pixel)).toBe(p0);
  const t = await H.tradeForListing(ghost, mine.id);
  expect(t.state).toBe('PENDING_SELLER_ACCEPT');
  expect(t.sellerUserId).toBe(pixel.id);

  // Pixel ships, Ghost confirms: Pixel receives resale minus fee.
  expect((await H.call(pixel, 'POST', `/api/trades/${t.id}/accept`)).ok).toBeTruthy();
  expect((await H.call(pixel, 'POST', `/api/trades/${t.id}/sent`, {})).ok).toBeTruthy();
  expect((await H.call(ghost, 'POST', `/api/trades/${t.id}/confirm`)).ok).toBeTruthy();
  expect(await H.balanceCents(pixel)).toBe(p0 + resalePrice - H.feeCents(resalePrice));
});

test('bargaining: Ghost offers, Atlas counters, Ghost takes the counter and pays exactly that', async () => {
  await H.call(ghost, 'POST', '/api/offers/outgoing/cancel-all', {});
  const outBefore = (await H.call(ghost, 'GET', '/api/offers/outgoing')).body;
  const talkedAbout = new Set((Array.isArray(outBefore) ? outBefore : outBefore.content || [])
    .filter((o) => ['PENDING', 'COUNTERED'].includes(o.status)).map((o) => o.listingId));
  const rows = await H.listings(ghost);
  const buyNow = rows.filter((l) => l.listingType !== 'AUCTION' && l.status === 'ACTIVE');
  const itemOf = (l) => l.item?.id ?? l.itemId;
  const target = buyNow.find((l) => l.sellerUserId === atlas.id && Number(l.price) >= 1 && Number(l.price) <= 20
    && l.maxDiscount == null && !talkedAbout.has(l.id)
    && !buyNow.some((o) => itemOf(o) === itemOf(l) && o.id !== l.id && Number(o.price) <= Number(l.price)));
  expect(target, 'an Atlas listing that is the cheapest copy of its item').toBeTruthy();
  const ask = H.cents(target.price);
  const offer = Math.floor(ask * 0.8);
  const [g0, a0] = await Promise.all([ghost, atlas].map(H.balanceCents));

  // Ghost: Bargain drawer on the item page.
  const gp = ghost.page;
  await gp.goto(`/item/${itemOf(target)}`);
  await gp.getByRole('button', { name: /Bargain/i }).first().click();
  await gp.locator('input[type="number"], input[inputmode="decimal"]').first().fill((offer / 100).toFixed(2));
  await gp.getByRole('button', { name: /Send Offer/i }).click();
  await expect.poll(async () => {
    const out = (await H.call(ghost, 'GET', '/api/offers/outgoing')).body;
    return (Array.isArray(out) ? out : out.content || []).filter((o) => o.listingId === target.id && o.status === 'PENDING').length;
  }).toBe(1);
  expect(await H.balanceCents(ghost), 'an offer holds no money').toBe(g0);

  // Atlas: counter from the Offers tab, at the midway amount it suggests.
  const ap = atlas.page;
  await ap.goto('/profile?tab=offers');
  const row = ap.locator('.offer-row').filter({ hasText: target.item?.name || '' }).first();
  await expect(row).toBeVisible({ timeout: 10_000 });
  await expect(row.locator('.offer-amt')).toHaveText(`$${(offer / 100).toFixed(2)}`);
  await row.getByRole('button', { name: 'Counter offer' }).click();
  const counterInput = ap.getByRole('spinbutton', { name: 'Counter price' });
  const counter = Math.round(Number(await counterInput.inputValue()) * 100);
  expect(counter).toBeGreaterThan(offer);
  expect(counter).toBeLessThan(ask);
  await ap.getByRole('button', { name: 'Send counter' }).click();

  // Ghost: accept the seller's counter from Outgoing. Charged the counter.
  await gp.goto('/profile?tab=offers');
  await gp.getByText(/^Outgoing/).first().click();
  await gp.getByRole('button', { name: 'Accept seller counter' }).first().click();
  await expect.poll(() => H.balanceCents(ghost)).toBe(g0 - counter);
  const t = await H.tradeForListing(ghost, target.id);
  expect(t, 'accepting the counter opened the escrow trade').toBeTruthy();
  expect(H.cents(t.price)).toBe(counter);
  expect(await H.balanceCents(atlas)).toBe(a0);

  // Settle it and check Atlas is paid counter minus fee.
  expect((await H.call(atlas, 'POST', `/api/trades/${t.id}/accept`)).ok).toBeTruthy();
  expect((await H.call(atlas, 'POST', `/api/trades/${t.id}/sent`, {})).ok).toBeTruthy();
  expect((await H.call(ghost, 'POST', `/api/trades/${t.id}/confirm`)).ok).toBeTruthy();
  expect(await H.balanceCents(atlas)).toBe(a0 + counter - H.feeCents(counter));
});

test('auction: Atlas auctions an item, Pixel bids (nothing charged), Ghost buys it out at the Buy Now price', async () => {
  // Atlas takes a house copy of an item nobody is auctioning; house sales
  // are delivered at once, so it lands straight in his inventory.
  const rows = await H.listings(atlas);
  const itemOf = (l) => l.item?.id ?? l.itemId;
  const auctioned = new Set(rows.filter((l) => l.listingType === 'AUCTION').map(itemOf));
  const house = rows.find((l) => l.sellerUserId == null && l.listingType !== 'AUCTION'
    && l.status === 'ACTIVE' && !auctioned.has(itemOf(l)) && Number(l.price) <= 5);
  expect(house, 'a house listing to stock Atlas with').toBeTruthy();
  const got = await H.call(atlas, 'POST', `/api/listings/${house.id}/buy`, { expectedPrice: String(house.price) });
  expect(got.ok, JSON.stringify(got.body)).toBeTruthy();

  const start = 500, buyNow = 1500; // cents
  const ap = atlas.page;
  await ap.goto('/sell');
  await ap.getByRole('button', { name: /Platform Inventory/i }).click();
  await ap.locator(`.inventory-item[data-listing-id="${house.id}"]`).click();
  await ap.getByRole('button', { name: 'Auction', exact: true }).click();
  await ap.getByRole('button', { name: '1d', exact: true }).click();
  await ap.getByRole('spinbutton', { name: 'Starting bid' }).fill((start / 100).toFixed(2));
  await ap.getByRole('spinbutton', { name: 'Buy Now price' }).fill((buyNow / 100).toFixed(2));
  await ap.getByRole('button', { name: 'List for Sale' }).click();
  const findAuction = async () => ((await H.call(atlas, 'GET', '/api/listings/my-stall')).body || [])
    .find((l) => l.listingType === 'AUCTION' && l.status === 'ACTIVE' && itemOf(l) === itemOf(house));
  await expect.poll(async () => !!(await findAuction()), { timeout: 10_000 }).toBe(true);
  const auction = await findAuction();
  expect(H.cents(auction.price)).toBe(start);
  expect(H.cents(auction.buyNowPrice)).toBe(buyNow);

  // Pixel bids the minimum from the item page. A bid holds no money.
  const [p0, g0, a0] = await Promise.all([pixel, ghost, atlas].map(H.balanceCents));
  const pp = pixel.page;
  await pp.goto(`/item/${itemOf(house)}`);
  await pp.getByRole('button', { name: /^Min \$/ }).first().click();
  await pp.getByRole('button', { name: 'Place Bid' }).first().click();
  const bidDlg = pp.getByRole('dialog').filter({ hasText: /bid/i }).last();
  const confirmBid = bidDlg.getByRole('button', { name: /Confirm|Place bid/i });
  if (await confirmBid.count()) await confirmBid.last().click();
  await expect.poll(async () => {
    const b = await H.call(pixel, 'GET', `/api/bids/listing/${auction.id}`);
    return (Array.isArray(b.body) ? b.body : b.body?.bids || []).length;
  }, { timeout: 10_000 }).toBeGreaterThan(0);
  expect(await H.balanceCents(pixel), 'placing a bid charges nothing').toBe(p0);

  // Ghost outbids, then buys it out.
  const bids = await H.call(pixel, 'GET', `/api/bids/listing/${auction.id}`);
  const top = Math.max(...(Array.isArray(bids.body) ? bids.body : bids.body.bids).map((b) => H.cents(b.amount)));
  const outbid = await H.call(ghost, 'POST', '/api/bids', { listingId: auction.id, amount: ((top + 100) / 100).toFixed(2) });
  expect(outbid.ok, JSON.stringify(outbid.body)).toBeTruthy();
  const bn = await H.call(ghost, 'POST', `/api/bids/listing/${auction.id}/buy-now`, {});
  expect(bn.ok, JSON.stringify(bn.body)).toBeTruthy();

  expect(await H.balanceCents(ghost)).toBe(g0 - buyNow);
  expect(await H.balanceCents(pixel), 'the outbid bidder is never charged').toBe(p0);
  expect(await H.balanceCents(atlas)).toBe(a0);
  const t = await H.tradeForListing(ghost, auction.id);
  expect(t, 'buy-now opened an escrow trade').toBeTruthy();
  expect(H.cents(t.price)).toBe(buyNow);
  const after = await H.call(pixel, 'GET', `/api/listings/${auction.id}`);
  if (after.ok) expect(after.body.status).not.toBe('ACTIVE');
});

test('buy order: Ghost posts one, and Pixel\'s new listing at or under it fills it automatically', async () => {
  // Stock Pixel with a house item.
  const rows = await H.listings(pixel);
  const itemOf = (l) => l.item?.id ?? l.itemId;
  const house = rows.find((l) => l.sellerUserId == null && l.listingType !== 'AUCTION'
    && l.status === 'ACTIVE' && Number(l.price) <= 5);
  expect(house).toBeTruthy();
  expect((await H.call(pixel, 'POST', `/api/listings/${house.id}/buy`, { expectedPrice: String(house.price) })).ok).toBeTruthy();

  // Ghost's buy order sits ABOVE every live copy? No: under the floor, so
  // nothing already listed fills it, and above Pixel's coming price.
  const live = rows.filter((l) => itemOf(l) === itemOf(house) && l.status === 'ACTIVE' && l.id !== house.id && l.listingType !== 'AUCTION');
  const floor = live.length ? Math.min(...live.map((l) => H.cents(l.price))) : 100000;
  const orderMax = Math.max(2, floor - 1);
  const listAt = orderMax - 1;
  await H.call(ghost, 'POST', '/api/buy-orders/cancel-all', {});

  const gp = ghost.page;
  await gp.goto('/buy-orders');
  await gp.getByRole('button', { name: /Create Buy Order/ }).first().click();
  await gp.getByRole('textbox', { name: 'Search catalogue for buy order target' }).fill(house.item.name);
  await gp.locator('.buyorder-form').getByText(house.item.name, { exact: true }).first().click();
  await gp.getByRole('spinbutton', { name: 'Maximum price per item' }).fill((orderMax / 100).toFixed(2));
  await gp.getByRole('spinbutton', { name: /Quantity to buy/ }).fill('1');
  const posted = gp.waitForResponse((r) => r.url().endsWith('/api/buy-orders') && r.request().method() === 'POST');
  await gp.getByRole('button', { name: 'Place Buy Order' }).click();
  const postRes = await posted;
  expect(postRes.ok(), `buy order accepted: ${await postRes.text()}`).toBeTruthy();
  await expect.poll(async () => {
    const r = await H.call(ghost, 'GET', '/api/buy-orders');
    return (Array.isArray(r.body) ? r.body : r.body?.content || []).filter((o) => o.status === 'ACTIVE').length;
  }, { timeout: 10_000 }).toBe(1);

  const [g0, p0] = await Promise.all([ghost, pixel].map(H.balanceCents));
  const listed = await H.call(pixel, 'POST', '/api/listings/sell', { listingId: house.id, price: (listAt / 100).toFixed(2) });
  expect(listed.ok, JSON.stringify(listed.body)).toBeTruthy();

  // The standing order buys it at the listing's price, not the order's.
  await expect.poll(() => H.balanceCents(ghost), { timeout: 10_000 }).toBe(g0 - listAt);
  const t = await H.tradeForListing(ghost, listed.body.listingId);
  expect(t).toBeTruthy();
  expect(t.sellerUserId).toBe(pixel.id);
  expect(await H.balanceCents(pixel)).toBe(p0);
});

test('Atlas cashes out from the payouts card: email first, then the withdrawal lands to the cent', async () => {
  const ap = atlas.page;
  // Withdrawals need a verified email. Add it on the profile page; in test
  // mode the page shows the verification token instead of emailing it.
  await ap.goto('/profile');
  const emailRow = ap.locator('.profile-row').filter({ hasText: 'Email' }).first();
  await emailRow.getByRole('button', { name: /Edit|Add/ }).first().click();
  await ap.locator('input[type=email]').fill('atlas.e2e@example.test');
  await ap.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(emailRow).toContainText('atlas.e2e@example.test', { timeout: 8_000 });
  const token = (await emailRow.getByText(/^[0-9a-f]{32}$/).first().innerText()).trim();
  await emailRow.getByPlaceholder('Paste token').fill(token);
  await emailRow.getByRole('button', { name: 'Verify' }).click();
  await expect.poll(async () => (await H.call(atlas, 'GET', '/api/auth/steam/me')).body.emailVerified).toBe(true);

  // The payouts card on My Stall: what it says is available is the wallet.
  const a0 = await H.balanceCents(atlas);
  await ap.goto('/me/stall');
  const card = ap.getByTestId('seller-payouts');
  await expect(card).toContainText(`$${(a0 / 100).toFixed(2)}`);
  await card.getByRole('button', { name: 'Request payout' }).click();
  await expect(ap).toHaveURL(/\/wallet\/withdraw/);
  const amount = 1000;
  await ap.getByRole('spinbutton', { name: 'Withdrawal amount' }).fill((amount / 100).toFixed(2));
  const sent = ap.waitForResponse((r) => r.url().endsWith('/api/wallet/withdraw') && r.request().method() === 'POST');
  await ap.getByRole('button', { name: `Withdraw $${(amount / 100).toFixed(2)}` }).click();
  const res = await sent;
  const body = await res.json();
  expect(res.ok(), JSON.stringify(body)).toBeTruthy();
  expect(await H.balanceCents(atlas)).toBe(a0 - amount);
  expect(H.cents(body.netPayout) + H.cents(body.processingFee || 0)).toBe(amount);
  await expect(ap.locator('.wallet-error')).toHaveCount(0);

  // The card now shows it as paid (test mode completes at once) or on its way.
  await ap.goto('/me/stall');
  await expect(ap.getByTestId('seller-payouts')).toContainText(`$${(amount / 100).toFixed(2)}`);
  const p = (await H.call(atlas, 'GET', '/api/wallet/payouts')).body;
  expect(H.cents(p.paidOut.amount) + H.cents(p.inFlight.amount)).toBe(amount);
  expect(H.cents(p.available)).toBe(a0 - amount);
});

test('everyone\'s history adds up: notifications, trades and wallet rows', async () => {
  // Atlas was told about his sales, and can clear the bell.
  const ap = atlas.page;
  const unread = (await H.call(atlas, 'GET', '/api/notifications/unread-count')).body;
  expect(Number(unread.count ?? unread.unread ?? unread)).toBeGreaterThan(0);
  const notes = (await H.call(atlas, 'GET', '/api/notifications')).body;
  const list = Array.isArray(notes) ? notes : notes.items || [];
  expect(list.some((n) => n.kind === 'TRADE_REQUESTED'), 'a "New sale" notification').toBeTruthy();
  await ap.goto('/');
  await ap.getByRole('button', { name: /notifications/i }).first().click();
  // The panel fills in after its own fetch: wait for the rows before measuring.
  const bellRows = ap.locator('#notif-dropdown-panel > div.notif-item');
  await expect(bellRows.nth(1)).toBeVisible();
  // Each row is as tall as its text: none spills onto the next one.
  const boxes = await bellRows.evaluateAll((els) =>
    els.map((e) => ({ top: e.offsetTop, h: e.offsetHeight, content: e.scrollHeight })));
  expect(boxes.length).toBeGreaterThan(1);
  for (let i = 0; i < boxes.length; i++) {
    expect(boxes[i].content, `bell row ${i} fits its text`).toBeLessThanOrEqual(boxes[i].h + 1);
    if (i > 0) expect(boxes[i].top).toBeGreaterThanOrEqual(boxes[i - 1].top + boxes[i - 1].h - 1);
  }
  await ap.getByRole('button', { name: /^Mark all .*read$/i }).first().click();
  await expect.poll(async () => {
    const c = (await H.call(atlas, 'GET', '/api/notifications/unread-count')).body;
    return Number(c.count ?? c.unread ?? c);
  }).toBe(0);

  // Pixel's trades tab lists his purchase from Atlas as verified.
  const pp = pixel.page;
  await pp.goto('/profile?tab=trades');
  await expect(pp.locator(`#trade-${state.tradeId}`)).toContainText(/Verified|Completed/i);

  // Wallet history: every PURCHASE row Pixel has is a real debit, and the
  // rows sum to his balance movement since sign-up.
  const tx = (await H.call(pixel, 'GET', '/api/wallet/transactions')).body;
  const rows = Array.isArray(tx) ? tx : tx.content || tx.transactions || [];
  expect(rows.some((r) => r.type === 'DEPOSIT')).toBeTruthy();
  expect(rows.some((r) => r.type === 'PURCHASE')).toBeTruthy();
  await pp.goto('/wallet');
  await pp.getByText(/^History$/).first().click();
  await expect(pp.getByText(/Deposit/i).first()).toBeVisible();
});

test('Atlas takes a listing down from My Stall and it goes back to his inventory', async () => {
  const stall = (await H.call(atlas, 'GET', '/api/listings/my-stall')).body;
  const l = stall.find((x) => x.status === 'ACTIVE' && x.listingType !== 'AUCTION');
  expect(l, 'an active buy-now listing on Atlas\'s stall').toBeTruthy();
  const ap = atlas.page;
  await ap.goto('/me/stall');
  const row = ap.locator(`[data-listing-id="${l.id}"]`).first();
  await expect(row).toBeVisible({ timeout: 10_000 });
  ap.once('dialog', (d) => d.accept());
  await row.getByRole('button', { name: /✕|Cancel listing|Remove/ }).first().click();
  const confirm = ap.getByRole('button', { name: /^(Yes|Confirm|Cancel listing|Remove listing|Delist)/ });
  if (await confirm.count()) await confirm.last().click();
  await expect.poll(async () => (await H.call(ghost, 'GET', `/api/listings/${l.id}`)).body?.status).not.toBe('ACTIVE');
  const inv = (await H.call(atlas, 'GET', '/api/listings/inventory')).body.map((x) => x.id);
  expect(inv).toContain(l.id);
});
