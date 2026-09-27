// @ts-check
const { test, expect } = require('@playwright/test');

// Clear the dev user's cart so the test starts from a known empty state
// (the cart persists in the shared dev session across runs).
async function clearCart(page) {
  // Deterministic clear via the API (DELETE /api/cart empties the whole cart).
  // The cart is server-side per-user, shared across sessions, so a UI-button
  // clear is flaky (no confirm for <2 items, async timing). fetch is reliable.
  await page.goto('/');
  await page.evaluate(async () => {
    const csrf = (document.cookie.match(/sbox_csrf=([^;]+)/) || [])[1] || '';
    await fetch('/api/cart', { method: 'DELETE', credentials: 'same-origin', headers: { 'X-CSRF-Token': decodeURIComponent(csrf) } });
  });
}

// Add-to-cart from the item page → cart page shows the item + an enabled
// checkout with a subtotal.
test('add to cart from item page, cart shows item + enabled checkout total', async ({ page }) => {
  await clearCart(page);

  await page.goto('/item/4');
  // The rail add-to-cart control (title "Add this listing to your cart").
  const addToCart = page.locator('.item-rail-actions-cart').first();
  await expect(addToCart).toBeVisible({ timeout: 15_000 });
  await expect(addToCart).toBeEnabled();
  await addToCart.click();
  // An "In Cart" indicator appears once added.
  await expect(page.getByRole('button', { name: /In Cart/i }).first()).toBeVisible({ timeout: 8_000 });

  // Cart page: item present, checkout enabled with a $ total
  await page.goto('/cart');
  await expect(page.locator('text=/Subtotal/i')).toBeVisible({ timeout: 10_000 });
  const checkout = page.getByRole('button', { name: /Checkout/i }).first();
  await expect(checkout).toBeVisible();
  await expect(checkout).toBeEnabled();
  await expect(checkout).toHaveText(/\$\d+\.\d{2}/);

  await clearCart(page); // leave the cart empty for the next run
});
