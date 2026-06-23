// @ts-check
const { test, expect } = require('@playwright/test');

// TRUE end-to-end money round-trip (the rest of the suite only asserts the
// UI renders). Buys the cheapest available *system* listing (sellerUserId
// null → no Steam trade-url gate, delivered in-platform) and proves the
// money invariant a real customer depends on:
//   * the wallet is debited by EXACTLY the listing price (0% buyer fee),
//   * the listing transitions out of ACTIVE (it's consumed, not re-buyable),
//   * the API returns a transactionId.
// Runs in the authenticated (dev-login) project. It permanently consumes one
// cheap listing + debits ~$0.60 per run, which the seeded catalogue absorbs.
test('buying a listing debits the wallet by exactly the price and marks it sold', async ({ page }) => {
  const api = page.request;

  // 1. Current wallet balance.
  const walletRes = await api.get('/api/wallet');
  expect(walletRes.ok()).toBeTruthy();
  const wallet = await walletRes.json();
  const oldBalance = Number(wallet.balance);
  expect(Number.isFinite(oldBalance)).toBeTruthy();

  // 2. Pick the cheapest ACTIVE, non-auction, *system* listing (sellerUserId
  //    null) so there's no trade-url requirement and no "can't buy your own".
  const listRes = await api.get('/api/listings?sort=price_asc');
  expect(listRes.ok()).toBeTruthy();
  const listings = await listRes.json();
  const target = listings.find(
    (l) => l.sellerUserId == null && l.listingType !== 'AUCTION' && l.status === 'ACTIVE'
  );
  expect(target, 'a buyable system listing should exist in the seeded catalogue').toBeTruthy();
  const price = Number(target.price);
  expect(oldBalance, 'dev wallet must have enough to buy the cheapest listing').toBeGreaterThanOrEqual(price);

  // 3. CSRF: the app uses a double-submit sbox_csrf cookie echoed as a header.
  const cookies = await page.context().cookies();
  const csrf = cookies.find((c) => c.name === 'sbox_csrf')?.value;
  expect(csrf, 'sbox_csrf cookie should be present after dev-login').toBeTruthy();

  // 4. Buy it.
  const buyRes = await api.post(`/api/listings/${target.id}/buy`, {
    headers: { 'X-CSRF-Token': csrf, 'Content-Type': 'application/json' },
    data: {},
  });
  expect(buyRes.ok(), `buy should succeed (got ${buyRes.status()})`).toBeTruthy();
  const buy = await buyRes.json();
  expect(buy.transactionId, 'a transaction id is returned').toBeTruthy();

  // 5. The wallet debit is EXACTLY the price (cent-precise), both from the
  //    buy response and a fresh wallet read.
  const expectedCents = Math.round(oldBalance * 100) - Math.round(price * 100);
  expect(Math.round(Number(buy.newBalance) * 100)).toBe(expectedCents);

  const wallet2 = await (await api.get('/api/wallet')).json();
  expect(Math.round(Number(wallet2.balance) * 100)).toBe(expectedCents);

  // 6. The listing is consumed — no longer ACTIVE, so it can't be re-bought.
  const afterRes = await api.get(`/api/listings/${target.id}`);
  if (afterRes.ok()) {
    const after = await afterRes.json();
    expect(after.status, 'listing should leave ACTIVE after purchase').not.toBe('ACTIVE');
  }

  // 7. A replay of the same buy must NOT double-charge — the SOLD state
  //    machine rejects it and the balance is unchanged.
  const replay = await api.post(`/api/listings/${target.id}/buy`, {
    headers: { 'X-CSRF-Token': csrf, 'Content-Type': 'application/json' },
    data: {},
  });
  expect(replay.ok(), 'a replayed buy on a sold listing must be rejected').toBeFalsy();
  const wallet3 = await (await api.get('/api/wallet')).json();
  expect(Math.round(Number(wallet3.balance) * 100)).toBe(expectedCents);
});
