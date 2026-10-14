// @ts-check
const { test, expect } = require('@playwright/test');

// Discovery labels (look-learning pass 6, 2026-10-14): rails and sorts say
// what actually fills or orders them.

test('the discount sort says what the discount is measured against', async ({ page }) => {
  await page.goto('/market');
  await page.locator('.sort-picker-chip').first().click();
  await expect(page.getByText('Biggest discount vs Steam', { exact: true })).toBeVisible({ timeout: 15_000 });
});

test('the market heading names the chosen category', async ({ page }) => {
  await page.goto('/market?category=Hats');
  await expect(page.locator('h1.visually-hidden')).toHaveText('Marketplace: Hats', { timeout: 15_000 });
  await page.goto('/market');
  await expect(page.locator('h1.visually-hidden')).toHaveText('Marketplace', { timeout: 15_000 });
});

test('the empty cart rail is titled by what it shows', async ({ page }) => {
  await page.goto('/cart');
  const title = page.locator('.csfloat-empty-cart-rail-title');
  await expect(title).toHaveText('On the market now', { timeout: 15_000 });
  await expect(page.locator('body')).not.toContainText('Trending right now');
});
