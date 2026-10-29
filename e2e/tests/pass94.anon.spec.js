// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 94.

const ME = { id: 3, displayName: 'carol', role: 'USER', signedIn: true, sessionEpoch: 0, tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc' };
const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });
const slow = (body, ms = 800) => (r) => new Promise((ok) => setTimeout(ok, ms)).then(() => r.fulfill(json(body)));

async function signedIn(page) {
  await page.addInitScript(() => { window.confirm = () => true; });
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json(ME)));
}
async function asAdmin(page) {
  await signedIn(page);
  await page.route('**/api/admin/check', (r) => r.fulfill(json({ admin: true })));
}

test.describe('defect pass 94 · fee-path', () => {
  // Pass 94: the first-fee path (list -> deposit -> buy -> bot delivery -> review).

  const inventoryRow = { assetId: '1000000002', assetIds: ['1000000002'], quantity: 1, listedCount: 0, listableQuantity: 1,
    name: 'Grand Ball Gown', category: 'Workshop', rarity: 'Standard', tradable: true, marketable: true, catalogueId: 38, suggestedPrice: 230 };
  const stall = (id) => ({ seller: { id, displayName: 'AtlasTrades' }, listings: [], count: 0, blockedByViewer: false });

  test.describe('pass 94: first-fee path', () => {
    test('a listing held for the bot deposit lands the seller on My Stall, not the Market', async ({ page }) => {
      await signedIn(page);
      await page.route('**/api/steam/inventory*', (r) => r.fulfill(json({ count: 1, items: [inventoryRow] })));
      await page.route('**/api/steam/list', (r) => r.fulfill(json({ listingId: 252, itemId: 38, price: 230, status: 'PENDING_ESCROW', escrowPending: true, listingType: 'BUY_NOW' })));
      await page.goto('/sell');
      await page.locator('.inventory-item').first().click({ timeout: 20_000 });
      await page.locator('.wallet-amount-input, input[type="number"]').first().fill('230.00');
      await page.getByRole('button', { name: /List for Sale/ }).click();
      await expect(page.getByText(/is reserved at \$230\.00/)).toBeVisible();
      await expect(page).toHaveURL(/\/me\/stall/);
    });

    test('My Stall Sold rows show the whole item name', async ({ page }) => {
      await signedIn(page);
      await page.route('**/api/listings/my-stall', (r) => r.fulfill(json([])));
      await page.route('**/api/listings/my-stall/sold', (r) => r.fulfill(json([
        { id: 253, price: 3.21, soldAt: Date.now() - 60000, item: { id: 32, name: 'Scientist Respirator' } },
      ])));
      await page.goto('/me/stall/sold');
      const name = page.locator('.recent-sales-row').getByText(/^Scientist Respirator$/);
      await name.scrollIntoViewIfNeeded({ timeout: 20_000 });
      await expect(name).toBeVisible();
      expect(await name.evaluate((el) => el.scrollWidth <= el.clientWidth)).toBe(true);
    });

    test('a stall\'s Recent sales row keeps its time on the same line as the sale', async ({ page }) => {
      await signedIn(page);
      await page.route('**/api/listings/stall/2', (r) => r.fulfill(json(stall(2))));
      await page.route('**/api/listings/stall/2/recent-sales*', (r) => r.fulfill(json([
        { listingId: 252, price: 230, soldAt: Date.now() - 5 * 60000, listingType: 'BUY_NOW', item: { id: 38, name: 'Grand Ball Gown' } },
        { listingId: 251, price: 10.37, soldAt: Date.now() - 12 * 60000, listingType: 'BUY_NOW', item: { id: 32, name: 'Scientist Respirator' } },
      ])));
      await page.goto('/stall/2');
      const row = page.locator('.stall-recent-sales .recent-sales-row').first();
      await row.scrollIntoViewIfNeeded({ timeout: 20_000 });
      const rowBox = await row.boundingBox();
      const timeBox = await row.locator('.recent-sales-time').boundingBox();
      expect(timeBox.y + timeBox.height).toBeLessThanOrEqual(rowBox.y + rowBox.height + 1);
    });

    test('the stall\'s Leave a review row gives the item name room and keeps Write review compact', async ({ page }) => {
      await signedIn(page);
      await page.route('**/api/listings/stall/2', (r) => r.fulfill(json(stall(2))));
      await page.route('**/api/reviews/eligible/2', (r) => r.fulfill(json([
        { tradeId: 2, itemName: 'Grand Ball Gown', price: 230, settledAt: Date.now(), reviewed: false },
      ])));
      await page.goto('/stall/2');
      const write = page.getByRole('button', { name: /^Write review$/ });
      await write.scrollIntoViewIfNeeded({ timeout: 20_000 });
      const name = page.locator('.stall-review-cta-item').filter({ hasText: /^Grand Ball Gown$/ });
      const nameBox = await name.boundingBox();
      const btnBox = await write.boundingBox();
      // One line of text, and a button sized to its label rather than the row.
      expect(nameBox.height).toBeLessThan(24);
      expect(btnBox.width).toBeLessThan(300);
    });
  });

  test.describe('pass 94: review after the first sale', () => {
    test('the stall review form\'s comment box spans the form, not 20 columns', async ({ page }) => {
      await signedIn(page);
      await page.route('**/api/listings/stall/2', (r) => r.fulfill(json(stall(2))));
      await page.route('**/api/reviews/eligible/2', (r) => r.fulfill(json([
        { tradeId: 2, itemName: 'Grand Ball Gown', price: 230, settledAt: Date.now(), reviewed: false },
      ])));
      await page.goto('/stall/2?leaveReview=2');
      const box = page.getByRole('textbox', { name: 'Review comment (optional)' });
      await box.scrollIntoViewIfNeeded({ timeout: 20_000 });
      await expect(box).toBeInViewport();
      const formWidth = await page.locator('.stall-review-form').evaluate((el) => el.clientWidth);
      const boxWidth = (await box.boundingBox()).width;
      expect(boxWidth).toBeGreaterThan(formWidth * 0.8);
    });
  });
});
