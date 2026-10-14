// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 36 (signed in): the sell grid's bulk-select box was
// display:none, so the keyboard could not select items for bulk listing.

test('the sell grid bulk-select checkbox works from the keyboard', async ({ page }) => {
  await page.route('**/api/steam/inventory', (r) => r.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({
      items: [{ assetId: 'a1', name: 'Keyboard Hat', tradable: true, unlistableReason: null, listableQuantity: 1, quantity: 1 }],
      count: 1, assetCount: 1, lastSyncedAt: Date.now(), steamId64: '76561199000000001',
    }),
  }));
  await page.goto('/sell');
  const box = page.getByRole('checkbox', { name: 'Select Keyboard Hat for bulk listing' });
  await expect(box).toBeAttached({ timeout: 20_000 });
  await box.focus();
  await expect(box).toBeFocused();
  await page.keyboard.press('Space');
  await expect(box).toBeChecked();
});
