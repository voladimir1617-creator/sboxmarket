const { test, expect } = require('@playwright/test');

// The item page's sales figures say whose sales they are, and a quiet item
// says it is quiet instead of looking like a page still loading.

const ZERO = { soldLast24h: 0, soldLast7d: 0, soldLast30d: 0, volumeLast30d: 0, lastSoldPrice: null, lastSoldAt: null };

test('an item with no sales in 30 days says so', async ({ page }) => {
  await page.route('**/api/items/12/velocity', (r) => r.fulfill({ json: ZERO }));
  await page.goto('/item/12');
  await expect(page.getByText('No sales on SkinBox in 30 days')).toBeVisible({ timeout: 15_000 });
});

test('a failed sales lookup never claims there were no sales', async ({ page }) => {
  let answered = false;
  await page.route('**/api/items/12/velocity', (r) => { answered = true; return r.fulfill({ status: 500, body: '{}' }); });
  await page.goto('/item/12');
  await expect(page.locator('h1')).toContainText('Vintage Design Crop Top', { timeout: 15_000 });
  await expect.poll(() => answered).toBe(true);
  await page.waitForTimeout(500);
  await expect(page.getByText('No sales on SkinBox in 30 days')).toHaveCount(0);
});

test('an item with sales shows no "no sales" chip', async ({ page }) => {
  await page.route('**/api/items/12/velocity', (r) => r.fulfill({ json: { ...ZERO, soldLast7d: 2, soldLast30d: 5 } }));
  await page.goto('/item/12');
  await expect(page.getByText('sold this week')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText('No sales on SkinBox in 30 days')).toHaveCount(0);
});

test('the recent-sales heading says the sales are on SkinBox', async ({ page }) => {
  await page.goto('/item/12');
  await expect(page.getByRole('heading', { name: /^Recent sales on SkinBox \(\d+\)$/ })).toBeVisible({ timeout: 15_000 });
});

test('once the Steam price sync has quoted an item, the chart names both sources', async ({ page }) => {
  // The item page builds the item from its listings.
  await page.route('**/api/listings/item/12', async (r) => {
    const resp = await r.fetch();
    const rows = await resp.json();
    await r.fulfill({ response: resp, json: rows.map((l) => ({ ...l, item: { ...l.item, steamPrice: 0.52 } })) });
  });
  await page.goto('/item/12');
  await expect(page.getByText('Prices: sales on SkinBox, and the Steam Market price on days nobody listed this item here.')).toBeVisible({ timeout: 15_000 });
});

test('with no Steam quote the chart carries no Steam source line', async ({ page }) => {
  await page.goto('/item/12');
  await expect(page.locator('.chart-wrap')).toBeVisible({ timeout: 15_000 });
  await expect(page.locator('.chart-source')).toHaveCount(0);
});
