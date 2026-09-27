// @ts-check
const { test, expect } = require('@playwright/test');

// Global nav chrome a customer relies on every page (signed out).
test.describe('nav chrome (signed out)', () => {
  test('nav links present and Market navigates', async ({ page }) => {
    await page.goto('/');
    const nav = page.locator('nav').first();
    await expect(nav.getByRole('link', { name: /^Market$/i }).or(nav.getByText(/^Market$/i)).first()).toBeVisible();
    await expect(nav.getByText(/Database/i).first()).toBeVisible();
    await expect(nav.getByText(/Loadout Lab/i).first()).toBeVisible();
    await expect(nav.getByText(/Watchlist/i).first()).toBeVisible();
    // Clicking Market routes to /market
    await nav.getByText(/^Market$/i).first().click();
    await expect(page).toHaveURL(/\/market/);
    await expect(page.locator('.grid-card').first()).toBeVisible({ timeout: 15_000 });
  });

  test('currency selector + cart icon present in nav', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('text=/USD/i').first()).toBeVisible();
    // Cart affordance in nav (icon button)
    await expect(page.locator('nav').first().locator('[class*="cart"]').first()).toBeVisible();
  });

  test('a deep route (item) renders standalone (SSR/OG shell ok)', async ({ page }) => {
    const resp = await page.goto('/item/7');
    expect(resp?.status()).toBeLessThan(400);
    await expect(page.locator('text=/Listing price/i').or(page.locator('text=/Not listed/i')).first())
      .toBeVisible({ timeout: 15_000 });
  });
});
