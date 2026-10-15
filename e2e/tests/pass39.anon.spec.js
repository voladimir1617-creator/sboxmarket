// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 39.

const ME = {
  id: 1,
  steamId64: '76561199000000001',
  displayName: 'Pass Tester',
  signedIn: true,
  sessionEpoch: 0,
};

const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });

async function signedIn(page) {
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json(ME)));
}

const CART = [
  { id: 501, name: 'Gone Hat', price: 3, sellerName: 'seller', itemId: 9 },
  { id: 502, name: 'Live Hat', price: 4, sellerName: 'seller', itemId: 10 },
];

test.describe('defect pass 39', () => {
  test('cart page hides row prices and the checkout total with Hide $ amounts on', async ({ page }) => {
    await signedIn(page);
    await page.addInitScript((cart) => {
      localStorage.setItem('sb_privacy', '1');
      localStorage.setItem('sb_cart', JSON.stringify(cart));
      localStorage.setItem('sb_cart_synced:1', '1');
    }, CART);
    await page.route('**/api/cart', (r) => r.fulfill(json([501, 502])));
    await page.route('**/api/listings/check-active', (r) => r.fulfill(json([
      { id: 501, active: true, price: 3 }, { id: 502, active: true, price: 4 },
    ])));
    await page.goto('/cart');
    const main = page.getByRole('main');
    await expect(main.getByText('Live Hat').first()).toBeVisible({ timeout: 20_000 });
    await expect(main.getByRole('button', { name: /^Checkout · \$•••••$/ })).toBeVisible();
    await expect(main).not.toContainText('$3.00');
    await expect(main).not.toContainText('$4.00');
    await expect(main).not.toContainText('$7.00');
  });

  test('"Remove unavailable" also drops the rows from a signed-in server cart', async ({ page }) => {
    await signedIn(page);
    await page.addInitScript((cart) => {
      localStorage.setItem('sb_cart', JSON.stringify(cart));
      localStorage.setItem('sb_cart_synced:1', '1');
    }, CART);
    await page.route('**/api/cart', (r) => r.fulfill(json([501, 502])));
    await page.route('**/api/listings/check-active', (r) => r.fulfill(json([
      { id: 501, active: false }, { id: 502, active: true, price: 4 },
    ])));
    const deleted = [];
    await page.route('**/api/cart/*', (r) => {
      if (r.request().method() === 'DELETE') deleted.push(r.request().url().split('/').pop());
      return r.fulfill(json({ ok: true }));
    });
    await page.goto('/cart');
    await page.getByRole('button', { name: 'Remove unavailable' }).click({ timeout: 20_000 });
    await expect(page.getByText('Gone Hat')).toHaveCount(0);
    await expect.poll(() => deleted).toEqual(['501']);
  });

  test('a restock alert on a watchlist card is not an "Alert hit" at $100,000', async ({ page }) => {
    await page.route('**/api/**', (r) => r.fulfill(json({})));
    await page.route('**/api/auth/steam/me', (r) => r.fulfill(json({ signedIn: false })));
    await page.addInitScript(() => {
      localStorage.setItem('sb_watchlist', JSON.stringify([999001]));
      localStorage.setItem('sb_watchlist_alerts', JSON.stringify({ 999001: 100000 }));
    });
    const item = { id: 999001, name: 'Restock Hat', lowestPrice: 3, rarity: 'Standard', category: 'Hats', isListed: true };
    await page.route('**/api/items/batch**', (r) => r.fulfill(json([item])));
    await page.route('**/api/listings?**', (r) => r.fulfill(json([
      { id: 61, price: 3, listingType: 'BUY_NOW', status: 'ACTIVE', sellerName: 's', item },
    ])));
    await page.goto('/watchlist');
    await expect(page.getByRole('button', { name: '↻ Restock alert' })).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText('Alert hit')).toHaveCount(0);
    await expect(page.getByText(/Alert ≤ \$100,000/)).toHaveCount(0);
  });

  test('an active bid links to the auction it is on', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/bids/my-active*', (r) => r.fulfill(json([
      { id: 1, listingId: 77, itemId: 5, itemName: 'Bid Hat', amount: 3, status: 'WINNING', bidType: 'MANUAL', createdAt: Date.now() },
    ])));
    await page.goto('/profile/autobids');
    await expect(page.getByRole('link', { name: 'Bid Hat' }).first()).toHaveAttribute('href', '/item/5?listing=77', { timeout: 20_000 });
  });

  test('a bare /offers link while on Outgoing switches to Incoming and re-reads', async ({ page }) => {
    await signedIn(page);
    let incomingReads = 0;
    await page.route('**/api/offers/incoming*', (r) => { incomingReads += 1; return r.fulfill(json([])); });
    await page.route('**/api/offers/outgoing*', (r) => r.fulfill(json([])));
    await page.goto('/offers/outgoing');
    await expect(page.getByRole('tab', { name: /^Outgoing/ })).toHaveAttribute('aria-selected', 'true', { timeout: 20_000 });
    const before = incomingReads;
    await page.evaluate(() => { window.history.pushState({}, '', '/offers'); window.dispatchEvent(new PopStateEvent('popstate')); });
    await expect(page.getByRole('tab', { name: /^Incoming/ })).toHaveAttribute('aria-selected', 'true');
    await expect.poll(() => incomingReads).toBeGreaterThan(before);
  });

  test('"Clear visible read" is not offered under the Unread filter', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/notifications*', (r) => r.fulfill(json({
      unread: 1,
      items: [
        { id: 1, kind: 'SYSTEM', title: 'Unread one', body: 'x', read: false, createdAt: Date.now() },
        { id: 2, kind: 'SYSTEM', title: 'Read one', body: 'y', read: true, createdAt: Date.now() },
      ],
    })));
    await page.goto('/notifications');
    await expect(page.getByRole('button', { name: /^Clear read/ })).toBeVisible({ timeout: 20_000 });
    await page.locator('button.offer-tab', { hasText: 'Unread' }).click();
    await expect(page.getByRole('button', { name: /Clear (visible )?read/ })).toHaveCount(0);
  });

  async function sellInventory(page) {
    await page.route('**/api/steam/inventory*', (r) => r.fulfill(json({ count: 2, items: [
      { assetId: 'a1', name: 'Listed Hat', catalogueId: 11, tradable: true, listableQuantity: 0, listedCount: 1, quantity: 1 },
      { assetId: 'a2', name: 'Free Hat', catalogueId: 12, tradable: true, listableQuantity: 1, quantity: 1 },
    ] })));
    await page.route('**/api/buy-orders/count/bulk*', (r) => r.fulfill(json({
      11: { count: 1, bestBid: 5 }, 12: { count: 1, bestBid: 2 },
    })));
  }

  test('Sell: "liquid now" skips rows with nothing left to sell', async ({ page }) => {
    await signedIn(page);
    await sellInventory(page);
    await page.goto('/sell');
    await expect(page.getByText(/liquid now/).first()).toContainText('$2.00', { timeout: 20_000 });
  });

  test('Sell: the bulk list price is entered and quoted in USD whatever the display currency', async ({ page }) => {
    await signedIn(page);
    await page.addInitScript(() => localStorage.setItem('sb_currency', 'EUR'));
    await sellInventory(page);
    await page.goto('/sell');
    await page.getByLabel('Select Free Hat for bulk listing').check({ timeout: 20_000, force: true });
    await page.getByLabel('Bulk list price in USD').fill('10');
    await expect(page.getByRole('button', { name: /^List 1 @ \$10\.00$/ })).toBeVisible();
  });

  test('a ?listing= link highlights that listing on the item page, even past the first six', async ({ page }) => {
    await page.route('**/api/**', (r) => r.fulfill(json({})));
    await page.route('**/api/auth/steam/me', (r) => r.fulfill(json({ signedIn: false })));
    const item = { id: 5, name: 'Deep Hat', rarity: 'Standard', category: 'Hats', lowestPrice: 3, isListed: true, supply: 8 };
    await page.route('**/api/items/5', (r) => r.fulfill(json(item)));
    const rows = Array.from({ length: 8 }, (_, i) => ({
      id: 700 + i, price: 3 + i, listingType: 'BUY_NOW', status: 'ACTIVE', sellerName: 'seller' + i, sellerUserId: 800 + i,
      listedAt: Date.now() - 1000, item,
    }));
    await page.route('**/api/listings/item/5*', (r) => r.fulfill(json(rows)));
    await page.goto('/item/5?listing=707');
    await expect(page.locator('.modal-listing-row.is-deeplinked[data-listing-id="707"]')).toBeVisible({ timeout: 20_000 });
  });
});
