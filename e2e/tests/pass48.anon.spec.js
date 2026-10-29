// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 48.

const ME = {
  id: 1,
  steamId64: '76561202255232729',
  displayName: 'Pass Tester',
  signedIn: true,
  sessionEpoch: 0,
};

const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });

async function signedIn(page) {
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json(ME)));
}

test.describe('defect pass 48', () => {
  test('an Auto-accept of 0 lists with auto-accept off, as Help says', async ({ page }) => {
    await signedIn(page);
    const row = {
      assetId: '948001', assetIds: ['948001'], quantity: 1, name: 'Zero Pct Hat', type: 'Hat', iconUrl: null,
      tradable: true, marketable: true, category: 'Hats', rarity: 'Standard', catalogueId: 4848,
      suggestedPrice: 6, steamPrice: null, listedCount: 0, listableQuantity: 1, unlistableReason: null,
    };
    await page.route('**/api/steam/inventory', (r) => r.fulfill(json({
      items: [row], count: 1, assetCount: 1, lastSyncedAt: Date.now(), steamId64: ME.steamId64,
    })));
    await page.route('**/api/items/*/recent-sales*', (r) => r.fulfill(json([])));
    await page.route('**/api/listings/item/*', (r) => r.fulfill(json([])));
    let posted = null;
    await page.route('**/api/steam/list', (r) => {
      posted = r.request().postDataJSON();
      return r.fulfill(json({ id: 48, status: 'ACTIVE' }));
    });
    page.on('dialog', (d) => d.accept());
    await page.goto('/sell');
    await page.locator('.inventory-item').first().click({ timeout: 20_000 });
    await page.getByRole('spinbutton', { name: 'Auto-accept discount percent' })
      .or(page.getByRole('textbox', { name: 'Auto-accept discount percent' })).fill('0');
    await page.getByRole('button', { name: /List for Sale/i }).click();
    await expect.poll(() => posted).not.toBeNull();
    expect(posted.maxDiscount).toBeUndefined();
    await expect(page.getByText('must be a whole number from 1% to 50%')).toHaveCount(0);
  });

  test('the Trades filter chips use the same state names as the rows', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/trades', (r) => r.fulfill(json([
      { id: 9, state: 'PENDING_SELLER_SEND', itemName: 'Chip Hat', buyerUserId: 1, sellerUserId: 2,
        counterpartyName: 'Seller', price: 5, createdAt: Date.now() },
    ])));
    await page.goto('/profile/trades');
    const bar = page.locator('.trade-filter-bar');
    await expect(bar.getByRole('button', { name: 'Awaiting Steam offer', exact: true })).toBeVisible({ timeout: 20_000 });
    await expect(bar.getByRole('button', { name: 'pending seller send' })).toHaveCount(0);
  });

  test('Support rows say "Waiting you" like the filter chip, not WAITING_USER', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/support/tickets', (r) => r.fulfill(json([
      { id: 48, subject: 'Where is my hat', category: 'TRADE', status: 'WAITING_USER',
        createdAt: Date.now(), updatedAt: Date.now() },
    ])));
    await page.goto('/profile/support');
    await expect(page.getByRole('cell', { name: 'Waiting you', exact: true })).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText('WAITING_USER', { exact: true })).toHaveCount(0);
  });

  test('Submit Ticket with an empty form says what is missing', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/support/tickets', (r) => r.fulfill(json([])));
    await page.goto('/profile/support');
    await page.getByRole('button', { name: '+ New Ticket' }).first().click({ timeout: 20_000 });
    await page.getByRole('button', { name: 'Submit Ticket' }).click();
    await expect(page.getByText('Add a subject and a message first.')).toBeVisible();
  });
  test('the Buy Orders page chips say which status filter is on', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/buy-orders', (r) => r.fulfill(json([
      { id: 1, status: 'ACTIVE', maxPrice: 2, quantity: 1, itemName: 'Order Hat', createdAt: Date.now() },
      { id: 2, status: 'FILLED', maxPrice: 3, quantity: 1, itemName: 'Filled Hat', createdAt: Date.now() },
    ])));
    await page.goto('/buy-orders');
    const active = page.locator('.wallet-tx-filter-chip', { hasText: 'Active' });
    await expect(active).toHaveAttribute('aria-pressed', 'true', { timeout: 20_000 });
    await expect(page.locator('.wallet-tx-filter-chip', { hasText: 'All' })).toHaveAttribute('aria-pressed', 'false');
  });

  test('a new loadout name is capped at 80, Enter creates it, and a double click creates one', async ({ page }) => {
    await signedIn(page);
    let posts = 0;
    await page.route('**/api/loadouts', (r) => {
      if (r.request().method() !== 'POST') return r.fulfill(json([]));
      posts += 1;
      return new Promise((res) => setTimeout(res, 300)).then(() => r.fulfill(json({ id: 4800 + posts, name: 'x' })));
    });
    await page.goto('/loadout');
    await page.getByRole('button', { name: '+ Create' }).click({ timeout: 20_000 });
    const input = page.getByRole('textbox', { name: 'New loadout name' });
    await expect(input).toHaveAttribute('maxlength', '80');
    await input.fill('Weekend fit');
    await input.press('Enter');
    await input.press('Enter');
    await expect.poll(() => posts).toBe(1);
    await page.waitForTimeout(600);
    expect(posts).toBe(1);
  });

  test('verified-seller progress names a low rating while sales are still short', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/sellers/me/verification-progress', (r) => r.fulfill(json({
      verified: false, soldCount: 3, thresholdSales: 10, salesNeeded: 7,
      ratingOk: false, ratingAverage: 3.996, ratingCount: 2, thresholdRating: 4,
    })));
    await page.goto('/me/stall');
    await expect(page.getByText('lift your rating to', { exact: false })).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText('3.99★')).toBeVisible();
  });
});
