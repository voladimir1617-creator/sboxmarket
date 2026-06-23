// @ts-check
const { test, expect } = require('@playwright/test');

// Wallet deposit form (signed in). Verifies the deposit step renders, a preset
// chip fills the amount input, and a payment method is offered. Does NOT submit
// — no real Stripe charge in e2e.
test('wallet deposit form: preset chip fills amount, payment method shown', async ({ page }) => {
  await page.goto('/wallet');
  // Balance header + Deposit/Withdraw/History tabs
  await expect(page.locator('text=/Wallet balance/i')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText(/^Deposit$/).first()).toBeVisible();

  // Step 1: amount. Click the $50 preset → the amount input reflects it.
  const preset = page.getByRole('button', { name: /^\$50(\.00)?$/ }).first();
  await expect(preset).toBeVisible({ timeout: 10_000 });
  await preset.click();
  const amount = page.locator('.wallet-amount-input, input[inputmode="decimal"], input[type="number"]').first();
  await expect(amount).toHaveValue(/50/);

  // Step 2: a real payment method is offered (no submit).
  await expect(page.locator('text=/Credit\\/Debit Card/i')).toBeVisible();
  // The daily deposit cap copy is present (guards the cap UI didn't vanish).
  await expect(page.locator('text=/daily deposit cap/i')).toBeVisible();
});

// Withdraw tab renders its form without error (signed in).
test('wallet withdraw tab renders', async ({ page }) => {
  await page.goto('/wallet');
  await page.getByText(/^Withdraw$/).first().click();
  // A withdraw amount input appears
  await expect(page.locator('.wallet-amount-input, input[inputmode="decimal"], input[type="number"]').first())
    .toBeVisible({ timeout: 10_000 });
});
