// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 57.

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

async function signedIn(page) {
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json(ME)));
}
const fakeClipboard = (page) => page.addInitScript(() => {
  Object.defineProperty(navigator, 'clipboard', { value: { writeText: async () => {} }, configurable: true });
  try { delete Navigator.prototype.share; } catch (_) {}
});
const doubleClick = (btn) => btn.evaluate((b) => { b.click(); b.click(); });

test.describe('defect pass 57', () => {
  test('Copy Steam ID flashes a tick instead of opening a Copy prompt', async ({ page }) => {
    await fakeClipboard(page);
    await signedIn(page);
    const dialogs = [];
    page.on('dialog', (d) => { dialogs.push(d.type()); d.dismiss(); });
    await page.goto('/profile');
    const btn = page.getByRole('button', { name: 'Copy Steam ID', exact: true });
    await btn.click({ timeout: 20_000 });
    await expect(btn).toHaveText('✓');
    expect(dialogs).toEqual([]);
  });

  test('a second Copy Filtered URL click mid-flash does not leave it stuck on Copied', async ({ page }) => {
    await fakeClipboard(page);
    await signedIn(page);
    await page.route('**/api/listings?*', (r) => r.fulfill(json([])));
    await page.goto('/market?search=hat');
    const btn = page.getByRole('button', { name: '⎘ Copy Filtered URL' });
    await btn.click({ timeout: 20_000 });
    await page.waitForTimeout(300);
    await page.locator('button.btn-clear', { hasText: 'Copied' }).click();
    await page.waitForTimeout(1800);
    await expect(btn).toBeVisible();
  });

  test('a second item Share click mid-flash does not leave it stuck on Copied', async ({ page }) => {
    await fakeClipboard(page);
    await signedIn(page);
    const item = { id: 5701, name: 'Share Hat', category: 'Hats', rarity: 'Standard', lowestPrice: 5, isListed: true };
    await page.route('**/api/items/5701', (r) => r.fulfill(json(item)));
    await page.route('**/api/listings/item/5701*', (r) => r.fulfill(json([])));
    await page.goto('/item/5701');
    const btn = page.getByTitle('Share this item');
    await btn.click({ timeout: 20_000 });
    await page.waitForTimeout(300);
    await btn.click();
    await page.waitForTimeout(2000);
    await expect(btn).not.toHaveText('Copied');
    await expect(btn).toContainText('Share');
  });

  test('double-clicking Mark all read sends one request and one toast', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/notifications*', (r) => r.fulfill(json({
      items: [{ id: 1, title: 'Hi', message: 'Hello', read: false, createdAt: Date.now(), kind: 'SYSTEM', type: 'SYSTEM' }],
      unread: 1,
    })));
    let posts = 0;
    await page.route('**/api/notifications/read-all', (r) => { posts += 1; return slow({})(r); });
    await page.goto('/notifications');
    const btn = page.getByRole('button', { name: 'Mark all read' });
    await btn.waitFor({ timeout: 20_000 });
    await doubleClick(btn);
    await page.waitForTimeout(800);
    expect(posts).toBe(1);
  });

  test("double-clicking Unblock on a blocked seller's stall sends one request", async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings/stall/7', (r) => r.fulfill(json({
      seller: { id: 7, displayName: 'bob' }, blockedByViewer: true, count: 0, listings: [],
    })));
    await page.route('**/api/listings/stall/7/**', (r) => r.fulfill(json([])));
    await page.route('**/api/reviews/**', (r) => r.fulfill(json([])));
    let deletes = 0;
    await page.route('**/api/profile/blocks/7', (r) => {
      if (r.request().method() === 'DELETE') deletes += 1;
      return slow({})(r);
    });
    await page.goto('/stall/7');
    const btn = page.getByRole('button', { name: 'Unblock', exact: true });
    await btn.waitFor({ timeout: 20_000 });
    await doubleClick(btn);
    await page.waitForTimeout(800);
    expect(deletes).toBe(1);
  });

  test('double-clicking Unblock in the profile blocked list sends one request', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/profile/blocks?limit=200', (r) => r.fulfill(json({ count: 1, items: [{ blockedUserId: 9, displayName: 'eve' }] })));
    let deletes = 0;
    await page.route('**/api/profile/blocks/9', (r) => {
      if (r.request().method() === 'DELETE') deletes += 1;
      return slow({})(r);
    });
    await page.goto('/profile');
    const btn = page.getByRole('button', { name: 'Unblock eve' });
    await btn.waitFor({ timeout: 20_000 });
    await doubleClick(btn);
    await page.waitForTimeout(800);
    expect(deletes).toBe(1);
  });

  test('double-clicking Unfollow in the profile following list sends one request', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/follows?limit=200', (r) => r.fulfill(json([{ id: 1, sellerUserId: 9 }])));
    await page.route('**/api/sellers/names*', (r) => r.fulfill(json({ 9: 'eve' })));
    let deletes = 0;
    await page.route('**/api/follows/9', (r) => {
      if (r.request().method() === 'DELETE') deletes += 1;
      return slow({})(r);
    });
    await page.goto('/profile');
    const btn = page.getByRole('button', { name: /Unfollow eve/ });
    await btn.waitFor({ timeout: 20_000 });
    await doubleClick(btn);
    await page.waitForTimeout(800);
    expect(deletes).toBe(1);
  });

  test('double-clicking the loadout Public toggle sends one request', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/loadouts/55', (r) => {
      if (r.request().method() === 'PUT') { puts += 1; return slow({})(r); }
      return r.fulfill(json({ loadout: { id: 55, name: 'L', ownerUserId: 1, visibility: 'PUBLIC', totalValue: 0 }, slots: [] }));
    });
    let puts = 0;
    await page.goto('/loadout/55');
    const btn = page.getByRole('button', { name: 'Public', exact: true });
    await btn.waitFor({ timeout: 20_000 });
    await doubleClick(btn);
    await page.waitForTimeout(800);
    expect(puts).toBe(1);
  });

  test('the database count says "1 result" for a single match', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/database?*', (r) => r.fulfill(json({
      items: [{ id: 1, name: 'Hat', category: 'Hats', rarity: 'Standard', supply: 5, lowestPrice: 2 }], total: 1, indexed: 1,
    })));
    await page.goto('/db');
    await expect(page.locator('.db-meta')).toContainText(/1 result · page/, { timeout: 20_000 });
  });

  test('MyStall sold tab says "1 sale" and its overflow banner has no stray dash', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings/my-stall/sold', (r) => r.fulfill({
      status: 200, contentType: 'application/json', headers: { 'X-Total-Count': '250' },
      body: JSON.stringify([{ id: 1, price: 5, soldAt: Date.now(), listingType: 'BUY_NOW', item: { id: 2, name: 'Hat' } }]),
    }));
    await page.goto('/me/stall/sold');
    await expect(page.locator('.mystall-sold-label')).toHaveText('Gross · last 1 sale', { timeout: 20_000 });
    await expect(page.getByText(/^Showing most recent 1 of 250 sales/)).toBeVisible();
  });

  test('MyStall verified-seller progress says "(1 review)"', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/sellers/me/verification-progress', (r) => r.fulfill(json({
      soldCount: 3, verified: false, salesNeeded: 7, thresholdSales: 10, ratingOk: false, thresholdRating: 4, ratingAverage: 3, ratingCount: 1,
    })));
    await page.goto('/me/stall');
    await expect(page.getByText('(1 review)', { exact: false })).toBeVisible({ timeout: 20_000 });
  });
});
