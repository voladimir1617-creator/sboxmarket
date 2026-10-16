// @ts-check
const { test, expect } = require('@playwright/test');

// A cart row that arrives as an id only (added on another device) is filled
// in from GET /api/listings/:id. The sign-in cart sync answers GET /api/cart
// on its own clock, and when it answered after the listing lookups it put the
// bare "Loading…" row back over the filled one. The row then stayed
// "Loading…" with no seller, and the checkout confirm said the item was held
// by SkinBox with no seller to wait on, about a listing a person must send.
// On a phone the cart sync is often the slower of the two.

const ID = 990001;
const LISTING = {
  id: ID, price: 2.5, status: 'ACTIVE', listingType: 'BUY_NOW', hidden: false,
  sellerName: 'Race Seller', sellerUserId: 2,
  item: { id: 4, name: 'Race Test Hat', imageUrl: null, steamPrice: null },
};

test('a cart row filled in before the cart sync answers stays filled, and checkout names the seller', async ({ page }) => {
  await page.addInitScript((id) => {
    localStorage.setItem('sb_cart', JSON.stringify([
      { id, itemId: null, name: 'Loading…', price: null, steamPrice: null, thumb: null },
    ]));
    localStorage.setItem('sb_cart_synced:1', '1');
  }, ID);
  await page.route(/\/api\/cart$/, async (route) => {
    if (route.request().method() !== 'GET') return route.continue();
    await new Promise((r) => setTimeout(r, 1500));
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([ID]) });
  });
  await page.route(`**/api/listings/${ID}`, async (route) => {
    await new Promise((r) => setTimeout(r, 500));
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(LISTING) });
  });
  await page.route('**/api/listings/check-active', (route) => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify([{ id: ID, active: true, price: LISTING.price }]),
  }));

  await page.goto('/cart');
  const row = page.locator('.cart-name').filter({ hasText: 'Race Test Hat' });
  await expect(row).toBeVisible({ timeout: 10_000 });
  // Past the slow cart sync: the filled row must survive it.
  await page.waitForResponse(/\/api\/cart$/);
  await page.waitForTimeout(500);
  await expect(row).toBeVisible();
  await expect(page.locator('.cart-name').filter({ hasText: 'Loading…' })).toHaveCount(0);

  await page.getByRole('button', { name: /Checkout/i }).first().click();
  await expect(page.getByTestId('delivery-expectation-seller')).toBeVisible();
  await expect(page.getByTestId('delivery-expectation-platform')).toHaveCount(0);
  await page.getByRole('button', { name: /^Cancel$/ }).click();
});

test('a cart row whose listing could not be read is not called a SkinBox item at checkout', async ({ page }) => {
  await page.addInitScript((id) => {
    localStorage.setItem('sb_cart', JSON.stringify([
      { id, itemId: null, name: 'Loading…', price: 2.5, steamPrice: null, thumb: null },
    ]));
    localStorage.setItem('sb_cart_synced:1', '1');
  }, ID);
  await page.route(/\/api\/cart$/, (route) => route.request().method() === 'GET'
    ? route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([ID]) })
    : route.continue());
  await page.route(`**/api/listings/${ID}`, (route) => route.fulfill({ status: 503, body: '' }));
  await page.route('**/api/listings/check-active', (route) => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify([{ id: ID, active: true, price: 2.5 }]),
  }));

  await page.goto('/cart');
  await expect(page.locator('.cart-name').first()).toBeVisible({ timeout: 10_000 });
  await page.getByRole('button', { name: /Checkout/i }).first().click();
  await expect(page.getByTestId('delivery-expectation-platform')).toHaveCount(0);
  await expect(page.getByTestId('delivery-expectation-seller')).toBeVisible();
  await page.getByRole('button', { name: /^Cancel$/ }).click();
});
