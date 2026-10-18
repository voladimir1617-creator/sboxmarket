// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 55.

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

async function buyOrderPicker(page, lowestPrice) {
  await signedIn(page);
  await page.route('**/api/buy-orders*', (r) => r.fulfill(json([])));
  await page.route('**/api/database**', (r) => r.fulfill(json({
    items: [{ id: 551, name: 'Red Hat', category: 'Hats', rarity: 'Rare', lowestPrice }], total: 1,
  })));
  await page.goto('/buy-orders');
  await page.getByRole('button', { name: '+ Create Buy Order' }).click({ timeout: 20_000 });
  await page.getByRole('button', { name: /^Pick Red Hat/ }).click();
}

test.describe('defect pass 55', () => {
  test('the buy-order floor is in dollars beside the USD max price', async ({ page }) => {
    await page.addInitScript(() => localStorage.setItem('sb_currency', 'EUR'));
    await buyOrderPicker(page, '10.00');
    await expect(page.locator('.buyorder-picked')).toContainText('Floor · $10.00');
    await expect(page.getByRole('spinbutton', { name: 'Maximum price per item' })).toHaveValue('9.00');
  });

  test('picking an unlisted item leaves the max price empty instead of 0.00', async ({ page }) => {
    await buyOrderPicker(page, '0');
    await expect(page.locator('.buyorder-picked')).toContainText('no active listings');
    await expect(page.getByRole('spinbutton', { name: 'Maximum price per item' })).toHaveValue('');
  });

  test('double-clicking Reopen on a resolved ticket sends one request', async ({ page }) => {
    await signedIn(page);
    const ticket = { id: 7, subject: 'Help', category: 'OTHER', status: 'RESOLVED', updatedAt: Date.now() };
    await page.route('**/api/support/tickets', (r) => r.fulfill(json([ticket])));
    await page.route('**/api/support/tickets/7', (r) => r.fulfill(json({ ticket, messages: [] })));
    let reopens = 0;
    await page.route('**/api/support/tickets/7/reopen', (r) => { reopens += 1; return slow({ ok: true })(r); });
    await page.goto('/profile/support');
    await page.getByRole('button', { name: /^Resolved/ }).click({ timeout: 20_000 });
    await page.getByRole('button', { name: /Open ticket #7/ }).click();
    const btn = page.getByRole('button', { name: '↻ Reopen' });
    await btn.evaluate((b) => { b.click(); b.click(); });
    await page.waitForTimeout(800);
    expect(reopens).toBe(1);
  });

  test('double-clicking Clear fired sends one request', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/watchlist/alerts', (r) => r.fulfill(json([
      { id: 1, itemId: 5, itemName: 'Red Hat', targetPrice: '8.00', status: 'FIRED' },
      { id: 2, itemId: 6, itemName: 'Blue Hat', targetPrice: '4.00', status: 'ACTIVE' },
    ])));
    let clears = 0;
    await page.route('**/api/watchlist/alerts/clear-fired', (r) => { clears += 1; return slow({ cleared: 1 })(r); });
    await page.goto('/watchlist');
    const btn = page.getByRole('button', { name: 'Clear fired' });
    await btn.waitFor({ timeout: 20_000 });
    await btn.evaluate((b) => { b.click(); b.click(); });
    await page.waitForTimeout(800);
    expect(clears).toBe(1);
  });

  test('editing a restock alert starts with an empty target, not 99999', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/watchlist/alerts', (r) => r.fulfill(json([
      { id: 1, itemId: 5, itemName: 'Red Hat', targetPrice: '99999.00', status: 'ACTIVE' },
    ])));
    await page.goto('/watchlist');
    await page.getByRole('button', { name: 'Change the target price for this alert' }).click({ timeout: 20_000 });
    await expect(page.getByLabel('New alert target price')).toHaveValue('');
  });

  test('the sell form quotes the seller floor and the Steam price in dollars', async ({ page }) => {
    await page.addInitScript(() => localStorage.setItem('sb_currency', 'EUR'));
    await signedIn(page);
    await page.route('**/api/steam/inventory', (r) => r.fulfill(json({
      items: [{
        assetId: '910055', assetIds: ['910055'], quantity: 1, name: 'Terms Test Hat', type: 'Hat',
        iconUrl: 'https://example.com/x.png', tradable: true, marketable: true, category: 'Hats',
        rarity: 'Standard', catalogueId: 4255, suggestedPrice: 0, steamPrice: 9,
        listedCount: 0, listableQuantity: 1, unlistableReason: null,
      }],
      count: 1, assetCount: 1, lastSyncedAt: Date.now(), steamId64: '76561199000000001',
    })));
    await page.route('**/api/listings/item/*', (r) => r.fulfill(json([
      { id: 5520, status: 'ACTIVE', listingType: 'BUY_NOW', price: 10, sellerUserId: 99, sellerName: 'Other' },
    ])));
    await page.goto('/sell');
    await page.locator('.inventory-item').first().click({ timeout: 20_000 });
    await page.getByRole('spinbutton', { name: 'Asking price' }).fill('12.00');
    await expect(page.getByText(/other seller · floor \$10\.00/)).toBeVisible();
    await expect(page.getByText('above the Steam market price ($9.00)', { exact: false })).toBeVisible();
  });

  test('Enter in the loadout budget runs Generate', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/loadouts/5', (r) => r.fulfill(json({
      loadout: { id: 5, name: 'Set', ownerUserId: 1, ownerName: 'Pass Tester', favorites: 0, visibility: 'PUBLIC', totalValue: 0 },
      slots: [], favorited: false,
    })));
    let gens = 0;
    await page.route('**/api/loadouts/5/generate', (r) => { gens += 1; return r.fulfill(json({ ok: true })); });
    const box = page.getByRole('textbox', { name: 'Max budget for Generate' });
    await page.goto('/loadout/5');
    await box.fill('50', { timeout: 20_000 });
    await box.press('Enter');
    await expect.poll(() => gens).toBe(1);
  });

  test('double-clicking Favorite on a loadout toggles it once', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/loadouts/5', (r) => r.fulfill(json({
      loadout: { id: 5, name: 'Set', ownerUserId: 2, ownerName: 'Ann', favorites: 0, visibility: 'PUBLIC', totalValue: 0 },
      slots: [], favorited: false,
    })));
    let favs = 0;
    await page.route('**/api/loadouts/5/favorite', (r) => { favs += 1; return slow({ favorited: true, favorites: 1 })(r); });
    await page.goto('/loadout/5');
    const btn = page.getByRole('button', { name: '♡ Favorite' });
    await btn.waitFor({ timeout: 20_000 });
    await btn.evaluate((b) => { b.click(); b.click(); });
    await page.waitForTimeout(800);
    expect(favs).toBe(1);
    await expect(page.getByRole('button', { name: '♥ Favorited' })).toHaveAttribute('aria-pressed', 'true');
  });

  test('the Copy Steam ID button is named', async ({ page }) => {
    await signedIn(page);
    await page.goto('/profile');
    await expect(page.getByRole('button', { name: 'Copy Steam ID', exact: true })).toBeVisible({ timeout: 20_000 });
  });
});
