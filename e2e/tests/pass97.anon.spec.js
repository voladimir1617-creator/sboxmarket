// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 97.

const ME = { id: 3, displayName: 'carol', role: 'USER', signedIn: true, sessionEpoch: 0, tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc' };
const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });
const slow = (body, ms = 800) => (r) => new Promise((ok) => setTimeout(ok, ms)).then(() => r.fulfill(json(body)));

async function signedIn(page) {
  await page.addInitScript(() => { window.confirm = () => true; });
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json(ME)));
}
async function asAdmin(page) {
  await signedIn(page);
  await page.route('**/api/admin/check', (r) => r.fulfill(json({ admin: true })));
}

// Defect pass 97: what a first buyer and seller see after the first sale.

const now = Date.now();

test.describe('pass 97: item page after the last copy sold', () => {
  const ITEM = { id: 12, name: 'Vintage Crop Top', category: 'Shirts', rarity: 'Rare', lowestPrice: 9.0, steamPrice: 12.0, supply: 1, iconEmoji: '■' };
  test('a sold-out item does not claim a saving against Steam off the sold copy.s price', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings/item/12', (r) => r.fulfill(json([])));
    await page.route('**/api/items/12', (r) => r.fulfill(json(ITEM)));
    await page.route('**/api/items/12/history**', (r) => r.fulfill(json([
      { price: 7.0, date: new Date(now - 20 * 86400000).toISOString().slice(0, 10) },
      { price: 9.0, date: new Date(now - 86400000).toISOString().slice(0, 10) },
    ])));
    await page.goto('/item/12');
    const stats = page.locator('.modal-stats');
    await expect(stats).toContainText(/Not listed/, { timeout: 15_000 });
    await expect(stats).toContainText(/\$12\.00/);
    await expect(stats.getByText(/Save \d+%/)).toHaveCount(0);
    await expect(stats).not.toContainText(/\+\$2\.00/);
  });
});

test.describe('pass 97: notifications page', () => {
  const rows = [
    { id: 1, kind: 'REVIEW_RECEIVED', title: 'New 5★ review', body: 'dave left feedback on Police Hat', read: true, createdAt: now - 60000, path: '/profile?tab=reviews' },
    { id: 2, kind: 'REVIEW_RECEIVED', title: 'New 5★ review', body: 'erin left feedback on Chef Hat', read: true, createdAt: now - 120000, path: '/profile?tab=reviews' },
  ];
  test('two sale-loop rows with the same title have different names', async ({ page }) => {
    await signedIn(page);
    await page.route((u) => u.pathname === '/api/notifications', (r) => r.fulfill(json({ items: rows, unread: 0 })));
    await page.goto('/notifications');
    await expect(page.getByRole('button', { name: /Open notification: New 5★ review.*Police Hat/ })).toHaveCount(1, { timeout: 15_000 });
    await expect(page.getByRole('button', { name: /Open notification: New 5★ review.*Chef Hat/ })).toHaveCount(1);
  });
});

test.describe('pass 97: offers', () => {
  const offer = (id, itemName, o = {}) => ({ id, listingId: 100 + id, itemId: id, itemName, amount: '8.00', askingPrice: '10.00', status: 'PENDING', author: 'BUYER', sellerName: 'dave', createdAt: now - id * 1000, ...o });
  const OUT = [offer(1, 'Police Hat'), offer(2, 'Chef Hat'), offer(3, 'Red Beanie', { author: 'SELLER', amount: '9.00' }), offer(4, 'Blue Jacket', { author: 'SELLER', amount: '9.00' })];
  async function stub(page) {
    await signedIn(page);
    await page.route('**/api/offers/incoming*', (r) => r.fulfill(json([])));
    await page.route('**/api/offers/outgoing*', (r) => r.fulfill(json(OUT)));
  }
  test('Offers page: each outgoing Cancel and Decline names its item', async ({ page }) => {
    await stub(page);
    await page.goto('/offers/outgoing');
    await expect(page.getByRole('button', { name: /^Cancel offer on Police Hat$/ })).toBeVisible({ timeout: 15_000 });
    await expect(page.getByRole('button', { name: /^Cancel offer on Chef Hat$/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /^Decline counter on Red Beanie$/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /^Decline counter on Blue Jacket$/ })).toBeVisible();
  });
  test('profile Offers tab: each outgoing Cancel and Decline names its item', async ({ page }) => {
    await stub(page);
    await page.goto('/profile/offers');
    await page.getByRole('tab', { name: /Outgoing/ }).click({ timeout: 15_000 });
    await expect(page.getByRole('button', { name: /^Cancel offer on Police Hat$/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /^Cancel offer on Chef Hat$/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /^Decline counter on Red Beanie$/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /^Decline counter on Blue Jacket$/ })).toBeVisible();
  });
});

test.describe('pass 97: buy orders after a fill', () => {
  const order = (id, status, o = {}) => ({ id, itemId: 7, itemName: 'Red Hat', maxPrice: '4.00', quantity: 0, originalQuantity: 1, status, createdAt: now - 3600e3, updatedAt: now - 600e3, currentFloor: 3.5, floorGap: -0.5, ...o });
  test('the buy-order-filled bell link shows the filled order, not "No active buy orders"', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/buy-orders', (r) => r.fulfill(json([order(5, 'FILLED')])));
    await page.goto('/profile?tab=buyorders');
    await expect(page.locator('.buyorder-row.filled')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText(/No active buy orders/)).toHaveCount(0);
  });
  test('a filled or cancelled order does not read "above floor" as if it could still match', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/buy-orders', (r) => r.fulfill(json([order(5, 'FILLED'), order(6, 'CANCELLED', { quantity: 1 }), order(8, 'ACTIVE', { quantity: 1, itemName: 'Blue Hat' })])));
    await page.goto('/profile/buyorders');
    await page.getByRole('button', { name: /^All · 3$/ }).click({ timeout: 15_000 });
    await expect(page.locator('.buyorder-row.active')).toContainText(/Floor \$3\.50 · above floor/);
    await expect(page.locator('.buyorder-row.filled')).not.toContainText(/above floor/);
    await expect(page.locator('.buyorder-row.cancelled')).not.toContainText(/above floor/);
  });
});

test.describe('pass 97: Sell page', () => {
  test('a failed Platform Inventory load is announced', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings/inventory', (r) => r.fulfill(json({}, 500)));
    await page.goto('/sell');
    await page.getByRole('button', { name: /^Platform Inventory/ }).click({ timeout: 15_000 });
    await expect(page.getByText(/Couldn.t load your platform inventory/)).toBeVisible();
    await expect(page.getByRole('alert').filter({ hasText: /Couldn.t load your platform inventory/ })).toBeVisible();
  });
});
