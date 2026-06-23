// @ts-check
const { test, expect } = require('@playwright/test');

// Customer-readiness BLOCKER regression guard: a hung buy request must NOT
// trap the user in the confirm dialog. We stall the buy POST, click Confirm,
// and assert the user can still Cancel out (Cancel stays enabled; dialog
// closes). The route aborts after a delay so no real purchase happens.
test('a hung buy request does not trap the user — Cancel still works', async ({ page }) => {
  // Stall the buy POST ~6s then fail it (simulates flaky-mobile hang).
  await page.route('**/api/listings/*/buy', async (route) => {
    await new Promise((r) => setTimeout(r, 6000));
    await route.abort('timedout');
  });

  await page.goto('/item/4');
  await page.getByRole('button', { name: /Buy now/i }).first().click();
  await expect(page.locator('.cart-confirm-title')).toBeVisible();

  // Fire the purchase — it will hang on the stalled route.
  const confirm = page.getByRole('button', { name: /Confirm purchase/i });
  await confirm.click();

  // While the request is in flight, Cancel MUST remain usable (the fix).
  const cancel = page.getByRole('button', { name: /^Cancel$/i });
  await expect(cancel).toBeEnabled();
  await cancel.click();

  // Dialog is dismissed — the user is not trapped.
  await expect(page.locator('.cart-confirm-actions')).toHaveCount(0, { timeout: 5000 });
});
