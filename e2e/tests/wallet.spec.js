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

// The withdraw request the form sends. Found by walking the whole sell loop
// (2026-09-26): the form always posted the 2FA box's value, "" for a user
// without 2FA, which the server's `^[0-9]{6}$` rejected -- every withdrawal
// from the UI died as 400 "Request body failed validation". It also REQUIRED
// a free-text "Stripe Connect ID or bank reference" the server never reads
// (live payouts go to the Connect account). The request is intercepted and
// answered here, so nothing is debited.
test('withdraw submits without a destination box and without an empty 2FA code', async ({ page }) => {
  let sent = null;
  await page.route('**/api/wallet/withdraw', async (route) => {
    sent = route.request().postDataJSON();
    await route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ transactionId: 1, status: 'COMPLETED', newBalance: 0, amount: 0.01, processingFee: 0, netPayout: 0.01 }),
    });
  });
  await page.goto('/wallet');
  await page.getByText(/^Withdraw$/).first().click();
  const amount = page.getByRole('spinbutton', { name: 'Withdrawal amount' });
  await expect(amount).toBeVisible({ timeout: 10_000 });
  await expect(page.locator('input[placeholder*="Stripe Connect ID"]')).toHaveCount(0);
  await amount.fill('0.01');
  await page.getByRole('button', { name: /^Withdraw \$0\.01$/ }).click();
  await expect.poll(() => sent, { timeout: 8_000 }).not.toBeNull();
  expect(Number(sent.amount)).toBe(0.01);
  expect(sent).not.toHaveProperty('totpCode');
  await expect(page.locator('.wallet-error')).toHaveCount(0);
});

// The wallet summary tiles. They were fed by /wallet/spend, which counts
// PURCHASE rows only: a seller's sale credits and every deposit were missing,
// and a seller with no purchases saw no summary at all. The tiles now read
// /wallet/activity and report each kind of money movement on its own.
test('wallet activity tiles report deposits and sales, not only purchases', async ({ page }) => {
  const b = (amount, count) => ({ amount, count });
  const win = { deposits: b(150, 2), sales: b(29.4, 3), purchases: b(0, 0), withdrawals: b(20, 1), refunds: b(0, 0) };
  await page.route('**/api/wallet/activity', (route) => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ windows: { '7d': win, '30d': win, all: win } }),
  }));
  await page.goto('/wallet');
  const tiles = page.getByTestId('wallet-activity');
  await expect(tiles).toBeVisible({ timeout: 15_000 });
  await expect(tiles.locator('[data-kind="deposits"]')).toContainText('+$150.00');
  await expect(tiles.locator('[data-kind="deposits"]')).toContainText('2 deposits');
  await expect(tiles.locator('[data-kind="sales"]')).toContainText('+$29.40');
  await expect(tiles.locator('[data-kind="sales"]')).toContainText('3 sales');
  await expect(tiles.locator('[data-kind="withdrawals"]')).toContainText('$20.00');
  await expect(tiles.locator('[data-kind="purchases"]')).toContainText('none');
});

// The payout-method icon was painted var(--accent) on a tile whose selected
// background IS the accent, so "Bank Account / Stripe" showed a blank blue
// square. The glyph must not be the colour of the tile behind it.
test('the payout method icon is visible against its tile', async ({ page }) => {
  await page.goto('/wallet/withdraw');
  const icon = page.locator('.wallet-method-card.selected .wallet-method-icon').first();
  await expect(icon).toBeVisible({ timeout: 15_000 });
  const { fg, bg } = await icon.evaluate((el) => ({
    fg: getComputedStyle(el.firstElementChild).color,
    bg: getComputedStyle(el).backgroundColor,
  }));
  expect(fg).not.toBe(bg);
});
