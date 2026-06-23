// @ts-check
const { test, expect } = require('@playwright/test');

// Make-offer (bargain) validation (signed in): the offer must be BELOW the
// asking price. An over-asking offer keeps Send disabled; a valid one enables it.
test('bargain dialog blocks over-asking offers, allows below-asking', async ({ page }) => {
  await page.goto('/item/4');
  const bargain = page.getByRole('button', { name: /Bargain/i }).first();
  await expect(bargain).toBeVisible({ timeout: 15_000 });
  await bargain.click();

  // Offer dialog: input + quick-discount chips + Send Offer
  await expect(page.locator('text=/must be below asking price/i')).toBeVisible();
  const send = page.getByRole('button', { name: /Send Offer/i });
  const input = page.locator('input[type="number"], input[inputmode="decimal"]').first();
  await expect(input).toBeVisible();

  // Over-asking ($20 on a ~$13 item) → Send disabled
  await input.fill('20.00');
  await expect(send).toBeDisabled();

  // Below-asking ($11) → Send enabled
  await input.fill('11.00');
  await expect(send).toBeEnabled();
});
