// @ts-check
const { test, expect } = require('@playwright/test');

// The sidebar's Clear Filters used to look the same whether or not anything
// was set, so clicking it on a fresh market did nothing visible. It now
// greys out when there is nothing to clear (White.market's "Reset all").
test('sidebar Clear Filters is off until a filter is set, and off again after it clears', async ({ page }) => {
  await page.goto('/market');
  const clear = page.getByTestId('sidebar-clear-filters');
  await expect(clear).toBeVisible({ timeout: 15_000 });
  await expect(clear).toBeDisabled();

  await page.locator('.sidebar').getByRole('button', { name: /^\$2\.00/ }).first().click();
  await expect(clear).toBeEnabled();

  await clear.click();
  await expect(clear).toBeDisabled();
});
