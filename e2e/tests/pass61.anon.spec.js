// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 61.

const ME = {
  id: 1,
  steamId64: '76561202255232729',
  displayName: 'Pass Tester',
  signedIn: true,
  sessionEpoch: 0,
  tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc',
};

const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });
const slow = (body, ms = 400) => (r) => new Promise((ok) => setTimeout(ok, ms)).then(() => r.fulfill(json(body)));
const doubleClick = (btn) => btn.evaluate((b) => { b.click(); b.click(); });

async function signedIn(page) {
  await page.addInitScript(() => { window.confirm = () => true; });
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json(ME)));
}
// Counts requests of one method on a route, answering slowly so a second
// click lands while the first is still in flight.
async function countSlow(page, url, method, body = {}) {
  const hits = { n: 0 };
  await page.route(url, (r) => {
    if (r.request().method() === method) { hits.n += 1; return slow(body)(r); }
    return r.fallback();
  });
  return hits;
}
const offer = (id, extra = {}) => ({
  id, listingId: 100 + id, itemId: 200 + id, itemName: `Hat ${id}`, amount: 4, askingPrice: 10,
  status: 'PENDING', author: 'BUYER', buyerName: 'bob', createdAt: Date.now(), ...extra,
});
async function offers(page, incoming, outgoing) {
  await page.route('**/api/offers/incoming', (r) => r.fulfill(json(incoming)));
  await page.route('**/api/offers/outgoing', (r) => r.fulfill(json(outgoing)));
}
const order = (id) => ({ id, itemId: 10 + id, itemName: `Order Hat ${id}`, status: 'ACTIVE', maxPrice: 2, quantity: 1, originalQuantity: 1, createdAt: Date.now(), updatedAt: Date.now() });

test.describe('defect pass 61', () => {
  test('Escape closes the offer Reject note box and keeps the offers page open', async ({ page }) => {
    await signedIn(page);
    await offers(page, [offer(1)], []);
    await page.goto('/offers');
    await page.getByRole('button', { name: 'Reject', exact: true }).click({ timeout: 20_000 });
    const box = page.getByLabel('Optional reason shown to the buyer');
    await box.fill('sorry');
    await box.press('Escape');
    await expect(box).toHaveCount(0);
    await expect(page).toHaveURL(/\/offers/);
    await expect(page.getByRole('button', { name: 'Reject', exact: true })).toBeVisible();
  });

  test('double-clicking Reject on a profile offer sends one reject', async ({ page }) => {
    await signedIn(page);
    await offers(page, [offer(1)], []);
    const posts = await countSlow(page, '**/api/offers/1/reject', 'POST');
    await page.goto('/profile/offers');
    const btn = page.getByRole('button', { name: 'Reject offer' });
    await btn.waitFor({ timeout: 20_000 });
    await doubleClick(btn);
    await page.waitForTimeout(800);
    expect(posts.n).toBe(1);
  });

  test('double-clicking Cancel on a profile outgoing offer sends one cancel', async ({ page }) => {
    await signedIn(page);
    await offers(page, [], [offer(2)]);
    const deletes = await countSlow(page, '**/api/offers/2', 'DELETE');
    await page.goto('/profile/offers');
    await page.getByRole('tab', { name: /Outgoing/ }).click({ timeout: 20_000 });
    const btn = page.getByRole('button', { name: 'Cancel', exact: true });
    await btn.waitFor();
    await doubleClick(btn);
    await page.waitForTimeout(800);
    expect(deletes.n).toBe(1);
  });

  test('double-clicking Cancel all pending sends one bulk cancel', async ({ page }) => {
    await signedIn(page);
    await offers(page, [], [offer(2), offer(3)]);
    const posts = await countSlow(page, '**/api/offers/outgoing/cancel-all', 'POST', { cancelled: 2 });
    await page.goto('/profile/offers');
    await page.getByRole('tab', { name: /Outgoing/ }).click({ timeout: 20_000 });
    const btn = page.getByRole('button', { name: '✕ Cancel all pending' });
    await btn.waitFor();
    await doubleClick(btn);
    await page.waitForTimeout(800);
    expect(posts.n).toBe(1);
  });

  test('a saved reputation sort lists outgoing offers newest first, as the dropdown says', async ({ page }) => {
    await signedIn(page);
    await page.addInitScript(() => localStorage.setItem('sb_offers_sort', 'reputation'));
    await offers(page, [], [
      offer(4, { itemName: 'Older Hat', amount: 9, createdAt: Date.now() - 86_400_000 }),
      offer(5, { itemName: 'Newer Hat', amount: 1 }),
    ]);
    await page.goto('/profile/offers');
    await page.getByRole('tab', { name: /Outgoing/ }).click({ timeout: 20_000 });
    await expect(page.getByText('Newer Hat').first()).toBeVisible();
    await expect(page.getByLabel('Sort offers')).toHaveValue('newest');
    const text = await page.locator('body').innerText();
    expect(text.indexOf('Newer Hat')).toBeLessThan(text.indexOf('Older Hat'));
  });

  test('double-clicking Cancel all active buy orders sends one bulk cancel', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/buy-orders', (r) => r.fulfill(json([order(3), order(4)])));
    const posts = await countSlow(page, '**/api/buy-orders/cancel-all', 'POST', { cancelled: 2 });
    await page.goto('/profile/buyorders');
    const btn = page.getByRole('button', { name: '✕ Cancel all active' });
    await btn.waitFor({ timeout: 20_000 });
    await doubleClick(btn);
    await page.waitForTimeout(800);
    expect(posts.n).toBe(1);
  });

  for (const [btnName, label] of [['Disable', '6-digit authenticator code or backup code'], ['Regenerate codes', '6-digit authenticator code']]) {
    test(`Escape closes the 2FA ${btnName} drawer like the boxes beside it`, async ({ page }) => {
      await signedIn(page);
      await page.route('**/api/profile/me', (r) => r.fulfill(json({ twoFactorEnabled: true, user: { id: 1, email: 'a@example.com', emailVerified: true, twoFactorEnabled: true } })));
      await page.goto('/profile/personal');
      await page.getByRole('button', { name: btnName, exact: true }).click({ timeout: 20_000 });
      const box = page.getByLabel(label, { exact: true });
      await box.fill('12');
      await box.press('Escape');
      await expect(box).toHaveCount(0);
    });
  }

  test('double-clicking Revoke on an API key sends one revoke', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/api-keys', (r) => r.fulfill(json([{ id: 7, label: 'bot', scope: 'RW', prefix: 'sbx_live_ab', createdAt: Date.now(), revoked: false }])));
    const deletes = await countSlow(page, '**/api/api-keys/7', 'DELETE');
    await page.goto('/profile/developers');
    const btn = page.getByRole('button', { name: 'Revoke', exact: true });
    await btn.waitFor({ timeout: 20_000 });
    await doubleClick(btn);
    await page.waitForTimeout(800);
    expect(deletes.n).toBe(1);
  });

  test('double-clicking a watchlist alert ✕ sends one cancel', async ({ page }) => {
    await signedIn(page);
    await page.addInitScript(() => localStorage.setItem('sb_watchlist', JSON.stringify([999401])));
    const item = { id: 999401, name: 'Alert Hat', lowestPrice: 5, rarity: 'Standard', category: 'Hats', isListed: true };
    await page.route('**/api/items/batch**', (r) => r.fulfill(json([item])));
    await page.route('**/api/listings?**', (r) => r.fulfill(json([{ id: 81, price: 5, listingType: 'BUY_NOW', status: 'ACTIVE', sellerName: 's', item }])));
    await page.route('**/api/watchlist/alerts', (r) => r.fulfill(json([{ id: 12, itemId: 999401, targetPrice: 3, status: 'ACTIVE', createdAt: Date.now() }])));
    const deletes = await countSlow(page, '**/api/watchlist/alerts/12', 'DELETE');
    await page.goto('/watchlist');
    const btn = page.getByRole('button', { name: 'Cancel this price alert' }).first();
    await btn.waitFor({ timeout: 20_000 });
    await doubleClick(btn);
    await page.waitForTimeout(800);
    expect(deletes.n).toBe(1);
  });

  const myLoadout = { loadout: { id: 56, name: 'Mine', ownerUserId: 1, visibility: 'PUBLIC', totalValue: 5 }, slots: [{ slot: 'Hats', itemId: 3, itemName: 'Cap', snapshotPrice: 5, locked: false }] };

  test('double-clicking a loadout slot lock sends one toggle', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/loadouts/56', (r) => r.fulfill(json(myLoadout)));
    const posts = await countSlow(page, '**/api/loadouts/56/slot/Hats/lock', 'POST', { locked: true });
    await page.goto('/loadout/56');
    const btn = page.getByRole('button', { name: 'Lock Hats slot' });
    await btn.waitFor({ timeout: 20_000 });
    await doubleClick(btn);
    await page.waitForTimeout(800);
    expect(posts.n).toBe(1);
  });

  test('double-clicking Delete on a loadout sends one delete', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/loadouts/56', (r) => (r.request().method() === 'GET' ? r.fulfill(json(myLoadout)) : r.fallback()));
    const deletes = await countSlow(page, '**/api/loadouts/56', 'DELETE');
    await page.goto('/loadout/56');
    const btn = page.getByRole('button', { name: 'Delete', exact: true });
    await btn.waitFor({ timeout: 20_000 });
    await doubleClick(btn);
    await page.waitForTimeout(800);
    expect(deletes.n).toBe(1);
  });
});
