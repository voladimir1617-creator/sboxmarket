// @ts-check
const { test, expect } = require('@playwright/test');

// Mobile smoke (iPhone 13 viewport, signed in). Real customers browse on
// phones — guard against horizontal overflow and missing mobile chrome.
test.describe('mobile smoke', () => {
  test('home has no horizontal overflow + renders content', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('text=/Buy & Sell s&box Skins/i')).toBeVisible({ timeout: 15_000 });
    const overflow = await page.evaluate(() =>
      document.documentElement.scrollWidth - document.documentElement.clientWidth);
    // Allow a 2px rounding fudge; anything more is a real horizontal-scroll bug.
    expect(overflow).toBeLessThanOrEqual(2);
  });

  test('market grid renders on mobile without overflow', async ({ page }) => {
    await page.goto('/market');
    await expect(page.locator('.grid-card').first()).toBeVisible({ timeout: 15_000 });
    const overflow = await page.evaluate(() =>
      document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow).toBeLessThanOrEqual(2);
  });

  test('mobile bottom nav / tab bar is present', async ({ page }) => {
    await page.goto('/market');
    // csfloat-style fixed bottom tab bar (class varies — match a bottom-fixed
    // nav-ish element). At minimum the primary nav affordances are reachable.
    const bottomNav = page.locator('[class*="bottom-nav"], [class*="mobile-nav"], [class*="tabbar"], [class*="tab-bar"]').first();
    await expect(bottomNav).toBeVisible({ timeout: 10_000 });
  });

  test('item detail is usable on mobile (no overflow)', async ({ page }) => {
    await page.goto('/item/4');
    await expect(page.locator('text=/Listing price/i')).toBeVisible({ timeout: 15_000 });
    const overflow = await page.evaluate(() =>
      document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow).toBeLessThanOrEqual(2);
  });
});
