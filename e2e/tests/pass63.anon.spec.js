// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 63.

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
async function emailProfile(page) {
  await signedIn(page);
  await page.route('**/api/profile/me', (r) => r.fulfill(json({ user: { id: 1, email: 'a@example.com', emailVerified: true, emailNotificationsEnabled: true } })));
  await page.route('**/api/profile/email-mutes', (r) => (r.request().method() === 'GET'
    ? r.fulfill(json({ available: ['TRADES', 'AUCTIONS'], muted: [] }))
    : r.fallback()));
}
const stall = (id) => ({ seller: { id, displayName: id === 1 ? 'Me' : 'Sel' }, listings: [], count: 0, blockedByViewer: false });

test.describe('defect pass 63', () => {
  test('double-clicking the Email notifications switch sends one request', async ({ page }) => {
    await emailProfile(page);
    const puts = await countSlow(page, '**/api/profile/email-notifications', 'PUT');
    const posts = await countSlow(page, '**/api/profile/email-notifications', 'POST');
    await page.goto('/profile/personal');
    const sw = page.getByRole('switch', { name: 'Email notifications' });
    await sw.waitFor({ timeout: 20_000 });
    await doubleClick(sw);
    await page.waitForTimeout(800);
    expect(puts.n + posts.n).toBe(1);
  });

  test('double-clicking Refresh listings fetches once', async ({ page }) => {
    await signedIn(page);
    let gets = 0;
    await page.goto('/market');
    const btn = page.getByRole('button', { name: 'Refresh listings' });
    await btn.waitFor({ timeout: 20_000 });
    await page.waitForTimeout(500);
    await page.route('**/api/listings?*', (r) => { gets += 1; return slow([])(r); });
    await doubleClick(btn);
    await page.waitForTimeout(1000);
    expect(gets).toBe(1);
  });

  test('double-clicking Revoke all active keys sends one request', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/api-keys', (r) => (r.request().method() === 'GET'
      ? r.fulfill(json([1, 2].map((id) => ({ id, label: `k${id}`, scope: 'RW', prefix: 'sbx_live_a' + id, createdAt: Date.now(), revoked: false }))))
      : r.fallback()));
    const deletes = await countSlow(page, '**/api/api-keys', 'DELETE', { revoked: 2 });
    await page.goto('/profile/developers');
    const btn = page.getByRole('button', { name: 'Revoke all active keys' });
    await btn.waitFor({ timeout: 20_000 });
    await doubleClick(btn);
    await page.waitForTimeout(800);
    expect(deletes.n).toBe(1);
  });

  test('double-clicking Post reply on a stall review sends one reply', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings/stall/1*', (r) => r.fulfill(json(stall(1))));
    await page.route('**/api/reviews/user/1*', (r) => r.fulfill(json([{ id: 21, rating: 5, comment: 'great', fromUserId: 2, fromDisplayName: 'B', helpfulCount: 0, createdAt: Date.now() }])));
    const posts = await countSlow(page, '**/api/reviews/21/reply', 'POST');
    await page.goto('/stall/1');
    await page.getByRole('button', { name: /Reply/ }).first().click({ timeout: 20_000 });
    await page.getByLabel('Public reply to this review').fill('thanks');
    await doubleClick(page.getByRole('button', { name: 'Post reply' }));
    await page.waitForTimeout(800);
    expect(posts.n).toBe(1);
  });

  test('double-clicking Delete my review on a stall sends one delete', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings/stall/80*', (r) => r.fulfill(json(stall(80))));
    await page.route('**/api/reviews/user/80*', (r) => r.fulfill(json([{ id: 22, rating: 4, comment: 'ok', mine: true, fromUserId: 1, fromDisplayName: 'Pass Tester', helpfulCount: 0, createdAt: Date.now() }])));
    const deletes = await countSlow(page, '**/api/reviews/22', 'DELETE');
    await page.goto('/stall/80');
    const btn = page.getByRole('button', { name: 'Delete my review' });
    await btn.waitFor({ timeout: 20_000 });
    await doubleClick(btn);
    await page.waitForTimeout(800);
    expect(deletes.n).toBe(1);
  });

  test('the stall share button announces "Stall link copied" once it shows Copied', async ({ page }) => {
    await page.addInitScript(() => {
      Object.defineProperty(navigator, 'clipboard', { value: { writeText: async () => {} }, configurable: true });
      try { delete Navigator.prototype.share; } catch (_) {}
    });
    await signedIn(page);
    await page.route('**/api/listings/stall/7*', (r) => r.fulfill(json(stall(7))));
    await page.goto('/stall/7');
    await page.getByRole('button', { name: 'Share stall' }).click({ timeout: 20_000 });
    await expect(page.locator('.stall-share-btn', { hasText: 'Copied' })).toHaveAttribute('aria-label', 'Stall link copied');
  });

  test('each loadout card names the loadout its copy button copies', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/loadouts/discover*', (r) => r.fulfill(json([
      { id: 1, name: 'Wizard', ownerName: 'A', favorites: 0, totalValue: 10, filledSlots: 1, visibility: 'PUBLIC' },
      { id: 2, name: 'Pirate', ownerName: 'B', favorites: 1, totalValue: 5, filledSlots: 1, visibility: 'PUBLIC' },
    ])));
    await page.goto('/loadout');
    await expect(page.getByRole('button', { name: 'Copy link to Wizard' })).toBeVisible({ timeout: 20_000 });
    await expect(page.getByRole('button', { name: 'Copy link to Pirate' })).toBeVisible();
  });

  test('the email and trade URL Edit buttons on Personal are told apart', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/profile/me', (r) => r.fulfill(json({ user: { id: 1, email: 'a@example.com', emailVerified: true, tradeUrl: ME.tradeUrl } })));
    await page.goto('/profile/personal');
    await expect(page.getByRole('button', { name: 'Edit email address' })).toBeVisible({ timeout: 20_000 });
    await expect(page.getByRole('button', { name: 'Edit Steam trade URL' })).toBeVisible();
  });

  test('the live-sales ticker writes "Just now" the same way for every row', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings/recent-sales*', (r) => r.fulfill(json([
      { listingId: 1, itemId: 5, itemName: 'Hat', price: 2, soldAt: null },
      { listingId: 2, itemId: 6, itemName: 'Cap', price: 3, soldAt: Date.now() - 5000 },
    ])));
    await page.goto('/market');
    await expect(page.locator('.ticker-time').first()).toBeVisible({ timeout: 20_000 });
    const times = await page.locator('.ticker-time').allTextContents();
    expect(times.filter((t) => /just now/i.test(t)).length).toBeGreaterThan(0);
    expect(times.filter((t) => t === 'just now')).toEqual([]);
  });
});
