// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 68.

const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });

async function as(page, me) {
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  if (me) await page.route('**/api/auth/steam/me', (r) => r.fulfill(json({ role: 'USER', signedIn: true, sessionEpoch: 0, ...me })));
}
const hourAgo = () => Date.now() - 3_600_000;
async function stall(page, eligible) {
  await page.route('**/api/listings/stall/2*', (r) => r.fulfill(json({ seller: { id: 2, displayName: 'bob', soldCount: 1 }, listings: [], count: 0 })));
  await page.route('**/api/listings/stall/2/recent-sales*', (r) => r.fulfill(json([])));
  await page.route('**/api/reviews/user/2', (r) => r.fulfill(json([
    { id: 11, rating: 4, comment: 'fine trade', fromDisplayName: 'alice', createdAt: hourAgo(), helpfulCount: 0 },
  ])));
  await page.route('**/api/reviews/user/2/summary*', (r) => r.fulfill(json({ count: 1, average: 4 })));
  await page.route('**/api/reviews/eligible/2*', (r) => r.fulfill(json(eligible)));
}

test.describe('defect pass 68', () => {
  test('Back from Support returns to the staff-only page, not the market', async ({ page }) => {
    await as(page, { id: 1, displayName: 'Pass Tester' });
    await page.route('**/api/support/tickets*', (r) => r.fulfill(json([])));
    await page.goto('/admin');
    await page.locator('.modal', { hasText: 'Staff access only' }).getByRole('link', { name: 'Support', exact: true }).click({ timeout: 20_000 });
    await expect(page).toHaveURL(/\/support$/);
    await page.goBack();
    await expect(page).toHaveURL(/\/admin$/);
  });

  test('a failed buy-orders load on an item says so instead of "no active buy orders"', async ({ page }) => {
    await as(page, null);
    const item = { id: 7, name: 'Blue Hat', rarity: 'Standard', category: 'Hats', lowestPrice: 5, totalSold: 0 };
    await page.route('**/api/items/7', (r) => r.fulfill(json(item)));
    await page.route('**/api/items/7/history*', (r) => r.fulfill(json([])));
    await page.route('**/api/listings/item/7*', (r) => r.fulfill(json([
      { id: 70, price: 5, listingType: 'BUY_NOW', status: 'ACTIVE', sellerUserId: 2, sellerName: 'bob', item },
    ])));
    await page.route('**/api/buy-orders/count/item/7*', (r) => r.fulfill(json({ count: 3, bestBid: 4 })));
    let fail = true;
    await page.route('**/api/buy-orders/for-item/7*', (r) => r.fulfill(fail ? json({ error: 'no' }, 403) : json([])));
    await page.goto('/item/7');
    await expect(page.getByText("Couldn't load buy orders.")).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText('There are no active buy orders for this item.')).toHaveCount(0);
    fail = false;
    await page.getByRole('button', { name: 'Retry', exact: true }).click();
    await expect(page.getByText('There are no active buy orders for this item.')).toBeVisible();
  });

  test('Escape closes the stall owner\'s review reply editor', async ({ page }) => {
    await as(page, { id: 2, displayName: 'bob' });
    await stall(page, []);
    await page.goto('/stall/2');
    await page.getByRole('button', { name: /Reply/ }).first().click({ timeout: 20_000 });
    const box = page.getByLabel('Public reply to this review');
    await expect(box).toBeFocused();
    await box.press('Escape');
    await expect(box).toHaveCount(0);
  });

  test('"Write review" on a stall puts the caret in the comment box and Escape closes it', async ({ page }) => {
    await as(page, { id: 3, displayName: 'carol' });
    await stall(page, [{ tradeId: 5, itemName: 'Blue Hat', settledAt: Date.now(), reviewed: false, price: 5 }]);
    await page.goto('/stall/2');
    await page.getByRole('button', { name: /Write review/ }).first().click({ timeout: 20_000 });
    const box = page.getByLabel('Review comment (optional)');
    await expect(box).toBeFocused();
    await box.press('Escape');
    await expect(box).toHaveCount(0);
  });

  test('editing a given review puts the caret in the comment box', async ({ page }) => {
    await as(page, { id: 3, displayName: 'carol' });
    await page.route('**/api/reviews/mine*', (r) => r.fulfill(json([
      { id: 21, tradeId: 9, rating: 4, comment: 'ok', sellerUserId: 2, sellerName: 'bob', createdAt: hourAgo(), mine: true },
    ])));
    await page.route('**/api/reviews/pending*', (r) => r.fulfill(json({ count: 0, items: [] })));
    await page.route('**/api/reviews/user/3', (r) => r.fulfill(json([])));
    await page.route('**/api/reviews/user/3/summary*', (r) => r.fulfill(json({ count: 0, average: null })));
    await page.goto('/profile/reviews');
    await page.getByRole('tab', { name: /^Given/ }).click({ timeout: 20_000 });
    await page.getByRole('button', { name: /Edit/ }).first().click();
    await expect(page.getByLabel('Edit your review comment')).toBeFocused();
  });
});
