// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 60.

const ME = {
  id: 1,
  steamId64: '76561199000000001',
  displayName: 'Pass Tester',
  signedIn: true,
  sessionEpoch: 0,
  tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc',
};

const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });

async function signedIn(page) {
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json(ME)));
}
const slow = (body, ms = 400) => (r) => new Promise((ok) => setTimeout(ok, ms)).then(() => r.fulfill(json(body)));
const doubleClick = (btn) => btn.evaluate((b) => { b.click(); b.click(); });
async function countSlow(page, url, method, body = {}) {
  const hits = { n: 0 };
  await page.route(url, (r) => {
    if (r.request().method() === method) { hits.n += 1; return slow(body)(r); }
    return r.fallback();
  });
  return hits;
}
const order = (extra = {}) => ({ id: 3, itemId: 13, itemName: 'Edit Hat', status: 'ACTIVE', maxPrice: 2, quantity: 1, originalQuantity: 1, createdAt: Date.now(), updatedAt: Date.now(), ...extra });
async function buyOrders(page, o) {
  await page.addInitScript(() => { window.confirm = () => true; });
  await signedIn(page);
  await page.route('**/api/buy-orders', (r) => r.fulfill(json([o])));
}
async function admin(page) {
  await signedIn(page);
  await page.route('**/api/admin/check', (r) => r.fulfill(json({ admin: true })));
}

test.describe('defect pass 60', () => {
  test('the loadout slot picker announces an unlisted item as "no listings", not $0.00', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/loadouts/55', (r) => r.fulfill(json({ loadout: { id: 55, name: 'L', ownerUserId: 1, visibility: 'PUBLIC', totalValue: 0 }, slots: [] })));
    await page.route('**/api/database?*', (r) => r.fulfill(json({
      items: [{ id: 3, name: 'Plain Cap', category: 'Hats', rarity: 'Standard', lowestPrice: null }], total: 1, indexed: 1,
    })));
    await page.goto('/loadout/55');
    await page.locator('.loadout-slot', { hasText: 'Hats' }).getByRole('button', { name: '+ Add' }).click({ timeout: 20_000 });
    await expect(page.locator('.loadout-picker-item').first()).toHaveAttribute('aria-label', 'Add Plain Cap to the Hats slot (no listings)');
  });

  test('Escape closes the new-loadout name box, like the rename box', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/loadouts/discover*', (r) => r.fulfill(json([])));
    await page.goto('/loadout');
    await page.getByRole('button', { name: '+ Create' }).click({ timeout: 20_000 });
    const box = page.getByLabel('New loadout name');
    await box.fill('Draft');
    await box.press('Escape');
    await expect(box).toHaveCount(0);
    await expect(page.getByRole('button', { name: '+ Create' })).toBeVisible();
  });

  test('the admin simulator says "Spawn 1 listing" and names its count box', async ({ page }) => {
    await admin(page);
    await page.route('**/api/admin/simulate/count', (r) => r.fulfill(json({ count: 0 })));
    await page.goto('/admin?tab=simulator');
    const count = page.getByRole('spinbutton', { name: 'Count' });
    await count.fill('1', { timeout: 20_000 });
    await expect(page.getByRole('button', { name: 'Spawn 1 listing', exact: true })).toBeVisible();
  });

  test('an admin report row with one report says "(1 report)"', async ({ page }) => {
    await admin(page);
    await page.route('**/api/admin/listings/reported', (r) => r.fulfill(json([
      { id: 77, itemName: 'Red Hat', reportCount: 1, reports: [], price: 5, status: 'ACTIVE' },
    ])));
    await page.goto('/admin?tab=reported');
    await expect(page.locator('[aria-label*="reported listing #77"]').first())
      .toHaveAttribute('aria-label', /\(1 report\)$/, { timeout: 20_000 });
  });

  test('the admin users detail button is named', async ({ page }) => {
    await admin(page);
    await page.route('**/api/admin/users*', (r) => r.fulfill(json([
      { id: 9, displayName: 'eve', role: 'USER', banned: false, balance: 0, createdAt: Date.now() },
    ])));
    await page.goto('/admin?tab=users');
    await expect(page.getByRole('button', { name: 'View user detail for eve' })).toBeVisible({ timeout: 20_000 });
  });

  test('double-clicking Cancel on the buy orders page sends one cancel', async ({ page }) => {
    await buyOrders(page, order());
    const deletes = await countSlow(page, '**/api/buy-orders/3', 'DELETE');
    await page.goto('/buy-orders');
    const btn = page.getByRole('button', { name: /Cancel buy order for Edit Hat|^Cancel$|✕ Cancel/ }).first();
    await btn.waitFor({ timeout: 20_000 });
    await doubleClick(btn);
    await page.waitForTimeout(800);
    expect(deletes.n).toBe(1);
  });

  test('double-clicking ✕ Cancel in the profile buy orders tab sends one cancel', async ({ page }) => {
    await buyOrders(page, order());
    const deletes = await countSlow(page, '**/api/buy-orders/3', 'DELETE');
    await page.goto('/profile/buyorders');
    const btn = page.getByRole('button', { name: /Cancel buy order for/ });
    await btn.waitFor({ timeout: 20_000 });
    await doubleClick(btn);
    await page.waitForTimeout(800);
    expect(deletes.n).toBe(1);
  });

  test('double-clicking Bump in the profile buy orders tab sends one request', async ({ page }) => {
    await buyOrders(page, order({ updatedAt: Date.now() - 25 * 86_400_000 }));
    const puts = await countSlow(page, '**/api/buy-orders/3', 'PUT');
    await page.goto('/profile/buyorders');
    const btn = page.getByRole('button', { name: 'Bump', exact: true });
    await btn.waitFor({ timeout: 20_000 });
    await doubleClick(btn);
    await page.waitForTimeout(800);
    expect(puts.n).toBe(1);
  });
});
