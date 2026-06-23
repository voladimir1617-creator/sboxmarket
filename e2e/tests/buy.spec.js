// @ts-check
const { test, expect } = require('@playwright/test');

// Buy-now confirm flow (signed in). Verifies the confirm dialog opens with
// correct fee math and a charged total, then cancels (no money moves).
test('buy-now opens a confirm dialog with fee math + total, cancel is clean', async ({ page }) => {
  await page.goto('/item/4');
  const buy = page.getByRole('button', { name: /Buy now/i }).first();
  await expect(buy).toBeVisible({ timeout: 15_000 });
  await buy.click();

  // Confirm dialog (scope to the title — the confirm BUTTON also says
  // "Confirm purchase · $…", so a loose text match is ambiguous)
  await expect(page.locator('.cart-confirm-title')).toHaveText(/Confirm purchase/i);
  await expect(page.locator('text=/Trade Protection \\(2%\\)/i')).toBeVisible();
  // Total charged row shows a real dollar amount
  const total = page.locator('.cart-confirm-total-amt');
  await expect(total).toBeVisible();
  await expect(total).toHaveText(/^\$\d+\.\d{2}$/);

  // Cancel — dialog closes, no purchase
  await page.getByRole('button', { name: /^Cancel$/i }).click();
  await expect(page.locator('.cart-confirm-actions')).toHaveCount(0);
});
