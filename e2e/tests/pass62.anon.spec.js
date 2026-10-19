// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 62.

const ME = {
  id: 1,
  steamId64: '76561199000000001',
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
const listing = (id) => ({ id, price: 5, listingType: 'BUY_NOW', status: 'ACTIVE', hidden: false, item: { id: 100 + id, name: `Stall Hat ${id}`, category: 'Hats', rarity: 'Standard' } });
const review = (id, rating) => ({ id, rating, comment: 'ok', fromUserId: 2, fromDisplayName: 'B', helpfulCount: 0, createdAt: Date.now() });

test.describe('defect pass 62', () => {
  test('double-clicking Hide on a My Stall listing sends one request', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings/my-stall', (r) => (r.request().method() === 'GET' ? r.fulfill(json([listing(1)])) : r.fallback()));
    const puts = await countSlow(page, '**/api/listings/1/stall', 'PUT');
    await page.goto('/me/stall');
    const btn = page.getByRole('button', { name: 'Hide', exact: true }).first();
    await btn.waitFor({ timeout: 20_000 });
    await doubleClick(btn);
    await page.waitForTimeout(800);
    expect(puts.n).toBe(1);
  });

  test('double-clicking ✕ Cancel all on My Stall sends one bulk cancel', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings/my-stall', (r) => (r.request().method() === 'GET' ? r.fulfill(json([listing(1), listing(2)])) : r.fallback()));
    const deletes = await countSlow(page, '**/api/listings/my-stall', 'DELETE', { cancelled: 2 });
    await page.goto('/me/stall');
    const btn = page.getByRole('button', { name: '✕ Cancel all', exact: true });
    await btn.waitFor({ timeout: 20_000 });
    await doubleClick(btn);
    await page.waitForTimeout(800);
    expect(deletes.n).toBe(1);
  });

  test('the Following "✕ all" button is named and sends one unfollow-all on a double click', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/follows?limit=200', (r) => r.fulfill(json([{ id: 1, sellerUserId: 8 }, { id: 2, sellerUserId: 9 }])));
    await page.route('**/api/sellers/names*', (r) => r.fulfill(json({ 8: 'dan', 9: 'eve' })));
    const deletes = await countSlow(page, '**/api/follows', 'DELETE', { unfollowed: 2 });
    await page.goto('/profile');
    const btn = page.getByRole('button', { name: 'Unfollow all sellers' });
    await btn.waitFor({ timeout: 20_000 });
    await doubleClick(btn);
    await page.waitForTimeout(800);
    expect(deletes.n).toBe(1);
  });

  test('the Blocked "✕ all" button is named and sends one unblock-all on a double click', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/profile/blocks?limit=200', (r) => r.fulfill(json({ count: 2, items: [{ blockedUserId: 8, displayName: 'dan' }, { blockedUserId: 9, displayName: 'eve' }] })));
    const deletes = await countSlow(page, '**/api/profile/blocks', 'DELETE', { removed: 2 });
    await page.goto('/profile');
    const btn = page.getByRole('button', { name: 'Unblock all users' });
    await btn.waitFor({ timeout: 20_000 });
    await doubleClick(btn);
    await page.waitForTimeout(800);
    expect(deletes.n).toBe(1);
  });

  test('the profile review star filters are named "Show 5-star reviews" and "Show all reviews"', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/reviews/user/1*', (r) => r.fulfill(json([review(1, 5), review(2, 1)])));
    await page.goto('/profile/reviews');
    await expect(page.getByRole('button', { name: 'Show 5-star reviews', exact: true })).toBeVisible({ timeout: 20_000 });
    await expect(page.getByRole('button', { name: 'Show all reviews', exact: true })).toBeVisible();
  });

  test('the stall review star filters are named "Show 5-star reviews"', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings/stall/80*', (r) => r.fulfill(json({ seller: { id: 80, displayName: 'Sel' }, listings: [], count: 0, blockedByViewer: false })));
    await page.route('**/api/reviews/user/80*', (r) => r.fulfill(json([review(1, 5), review(2, 4), review(3, 1)])));
    await page.goto('/stall/80');
    await expect(page.getByRole('button', { name: 'Show 5-star reviews', exact: true })).toBeVisible({ timeout: 20_000 });
  });

  test('double-clicking Save on an item price alert sends one request', async ({ page }) => {
    await signedIn(page);
    const item = { id: 5702, name: 'Alert Cap', category: 'Hats', rarity: 'Standard', lowestPrice: 10, isListed: true };
    await page.route('**/api/items/5702', (r) => r.fulfill(json(item)));
    await page.route('**/api/listings/item/5702*', (r) => r.fulfill(json([{ id: 91, price: 10, listingType: 'BUY_NOW', status: 'ACTIVE', sellerName: 's', item }])));
    await page.route('**/api/watchlist/alerts', (r) => (r.request().method() === 'GET' ? r.fulfill(json([])) : r.fallback()));
    const posts = await countSlow(page, '**/api/watchlist/alerts', 'POST', { id: 1 });
    await page.goto('/item/5702');
    await page.getByRole('button', { name: /Set Price Alert/ }).first().click({ timeout: 20_000 });
    await page.getByLabel('Price alert target (USD)').fill('8');
    await doubleClick(page.locator('[data-alert-save]'));
    await page.waitForTimeout(800);
    expect(posts.n).toBe(1);
  });

  test('double-clicking Save new target on a watchlist alert sends one request', async ({ page }) => {
    await signedIn(page);
    await page.addInitScript(() => localStorage.setItem('sb_watchlist', JSON.stringify([999402])));
    const item = { id: 999402, name: 'Edit Alert Hat', lowestPrice: 5, rarity: 'Standard', category: 'Hats', isListed: true };
    await page.route('**/api/items/batch**', (r) => r.fulfill(json([item])));
    await page.route('**/api/listings?**', (r) => r.fulfill(json([{ id: 82, price: 5, listingType: 'BUY_NOW', status: 'ACTIVE', sellerName: 's', item }])));
    await page.route('**/api/watchlist/alerts', (r) => (r.request().method() === 'GET'
      ? r.fulfill(json([{ id: 13, itemId: 999402, targetPrice: 3, status: 'ACTIVE', createdAt: Date.now() }]))
      : r.fallback()));
    const posts = await countSlow(page, '**/api/watchlist/alerts', 'POST', { id: 13 });
    await page.goto('/watchlist');
    await page.getByRole('button', { name: 'Change the target price for this alert' }).first().click({ timeout: 20_000 });
    await page.getByLabel('New alert target price').fill('4');
    await doubleClick(page.getByRole('button', { name: 'Save new target' }));
    await page.waitForTimeout(800);
    expect(posts.n).toBe(1);
  });

  test('double-clicking Delete on a given review sends one delete', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/reviews/mine', (r) => r.fulfill(json([{ id: 3, tradeId: 9, rating: 4, comment: 'ok', toUserName: 'bob', createdAt: Date.now() }])));
    const deletes = await countSlow(page, '**/api/reviews/3', 'DELETE');
    await page.goto('/profile/reviews');
    await page.locator('.offer-tab', { hasText: 'Given' }).click({ timeout: 20_000 });
    const btn = page.getByTitle('Permanently delete your review').first();
    await btn.waitFor();
    await doubleClick(btn);
    await page.waitForTimeout(800);
    expect(deletes.n).toBe(1);
  });

  test('double-clicking Send on Contact seller opens one ticket', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings/stall/2*', (r) => r.fulfill(json({ seller: { id: 2, displayName: 'bob' }, listings: [], count: 0, blockedByViewer: false })));
    const posts = await countSlow(page, '**/api/support/tickets', 'POST', { id: 1 });
    await page.goto('/stall/2');
    await page.getByRole('button', { name: /Contact/ }).first().click({ timeout: 20_000 });
    await page.getByLabel('Message to support about this seller').fill('hello');
    await doubleClick(page.getByRole('button', { name: 'Send', exact: true }));
    await page.waitForTimeout(800);
    expect(posts.n).toBe(1);
  });

  test('double-clicking ✕ Clear all on the watchlist sends one clear', async ({ page }) => {
    await signedIn(page);
    await page.addInitScript(() => localStorage.setItem('sb_watchlist', JSON.stringify([999403])));
    const item = { id: 999403, name: 'Clear Hat', lowestPrice: 5, rarity: 'Standard', category: 'Hats', isListed: true };
    await page.route('**/api/items/batch**', (r) => r.fulfill(json([item])));
    await page.route('**/api/listings?**', (r) => r.fulfill(json([{ id: 83, price: 5, listingType: 'BUY_NOW', status: 'ACTIVE', sellerName: 's', item }])));
    const deletes = await countSlow(page, '**/api/watchlist', 'DELETE', { removed: 1 });
    await page.goto('/watchlist');
    const btn = page.getByRole('button', { name: '✕ Clear all' });
    await btn.waitFor({ timeout: 20_000 });
    await doubleClick(btn);
    await page.waitForTimeout(800);
    expect(deletes.n).toBe(1);
  });
});
