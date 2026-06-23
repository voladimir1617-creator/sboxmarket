// @ts-check
const { test, expect } = require('@playwright/test');

// Sell / list-an-item flow (signed in). The /sell page has two tabs — Steam
// Inventory (synced) and Platform Inventory (items the user owns on-platform).
// Selecting a platform item opens the list panel with a price input and the
// 2% fee math. We verify the panel + fee math; we do NOT submit a real listing.
test('list-an-item panel shows price input + correct 2% fee math', async ({ page }) => {
  await page.goto('/sell');
  await expect(page).toHaveURL(/\/sell/);

  // Switch to Platform Inventory (the dev user owns items there)
  const platformTab = page.getByRole('button', { name: /Platform Inventory/i })
    .or(page.locator('text=/Platform Inventory/i')).first();
  await expect(platformTab).toBeVisible({ timeout: 15_000 });
  await platformTab.click();

  // Click the first ownable inventory item to open the list panel
  const invItem = page.locator('.inventory-item').first();
  await expect(invItem).toBeVisible({ timeout: 10_000 });
  await invItem.click();

  // List panel: price input + "List for Sale"
  const priceInput = page.locator('.wallet-amount-input, input[type="number"]').first();
  await expect(priceInput).toBeVisible({ timeout: 10_000 });
  await expect(page.getByRole('button', { name: /List for Sale/i })).toBeVisible();

  // Fee math: enter $10 → 2% platform fee = $0.20, you receive $9.80
  await priceInput.fill('10.00');
  await expect(page.locator('text=/Platform fee \\(2%\\)/i')).toBeVisible();
  await expect(page.locator("text=/[−-]\\$0\\.20/").first()).toBeVisible();
  await expect(page.locator("text=/\\$9\\.80/").first()).toBeVisible();
});
