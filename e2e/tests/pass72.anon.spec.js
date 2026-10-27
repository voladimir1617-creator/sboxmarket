// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 72.

const ME = { id: 3, displayName: 'carol', role: 'USER', signedIn: true, sessionEpoch: 0 };
const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });
const conflict = (code, message) => json({ code, message }, 409);

async function signedIn(page) {
  await page.addInitScript(() => { window.confirm = () => true; });
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json(ME)));
}
const offer = (id, status) => ({ id, listingId: 9, itemId: 5, itemName: 'Red Hat', amount: '8.00', askingPrice: '10.00', status, author: 'BUYER', createdAt: Date.now() });
const order = (status) => ({ id: 5, itemId: 7, itemName: 'Red Hat', maxPrice: '4.00', quantity: 2, originalQuantity: 2, status, createdAt: Date.now() - 3_600_000, updatedAt: Date.now() - 3_600_000 });
const listing = { id: 1, price: 5, listingType: 'BUY_NOW', status: 'ACTIVE', hidden: false, item: { id: 101, name: 'Stall Hat 1', category: 'Hats', rarity: 'Standard' } };
const card = (id, name) => ({ id, name, favorites: 1, totalValue: 5, filledSlots: 1, ownerName: 'bob' });
const body = (id, name) => ({ loadout: { id, name, ownerUserId: 9, ownerName: 'bob', favorites: 2, visibility: 'PUBLIC' }, slots: [], favorited: false });

test.describe('defect pass 72', () => {
  test('a failed Cancel on the Offers page refreshes the offer row', async ({ page }) => {
    await signedIn(page);
    let status = 'PENDING';
    await page.route('**/api/offers/incoming*', (r) => r.fulfill(json([])));
    await page.route('**/api/offers/outgoing*', (r) => r.fulfill(json([offer(41, status)])));
    await page.route('**/api/offers/41', (r) => { status = 'ACCEPTED'; return r.fulfill(conflict('OFFER_NOT_PENDING', 'This offer is no longer pending (ACCEPTED)')); });
    await page.goto('/offers/outgoing');
    await page.getByRole('button', { name: 'Cancel', exact: true }).click({ timeout: 20_000 });
    await expect(page.getByText(/no longer pending/).first()).toBeVisible();
    await expect(page.getByRole('button', { name: 'Cancel', exact: true })).toHaveCount(0);
  });

  test('a failed Counter on the Offers page closes the form and refreshes the row', async ({ page }) => {
    await signedIn(page);
    let status = 'PENDING';
    await page.route('**/api/offers/incoming*', (r) => r.fulfill(json([offer(42, status)])));
    await page.route('**/api/offers/outgoing*', (r) => r.fulfill(json([])));
    await page.route('**/api/offers/42/counter', (r) => { status = 'CANCELLED'; return r.fulfill(conflict('OFFER_NOT_PENDING', 'This offer is no longer pending (CANCELLED)')); });
    await page.goto('/offers/incoming');
    await page.getByRole('button', { name: 'Counter', exact: true }).click({ timeout: 20_000 });
    await page.getByLabel('Counter-offer amount').fill('9');
    await page.getByRole('button', { name: 'Send', exact: true }).click();
    await expect(page.getByText(/no longer pending/).first()).toBeVisible();
    await expect(page.getByLabel('Counter-offer amount')).toHaveCount(0);
  });

  test('a failed buy-order cancel on the profile tab refreshes the order', async ({ page }) => {
    await signedIn(page);
    let status = 'ACTIVE';
    await page.route(/\/api\/buy-orders$/, (r) => r.fulfill(json([order(status)])));
    await page.route('**/api/buy-orders/5', (r) => { status = 'FILLED'; return r.fulfill(conflict('BUY_ORDER_NOT_ACTIVE', 'This buy order is no longer active')); });
    await page.goto('/profile/buyorders');
    await page.getByRole('button', { name: /Cancel buy order for/ }).click({ timeout: 20_000 });
    await expect(page.getByText(/no longer active/).first()).toBeVisible();
    await expect(page.getByRole('button', { name: /Cancel buy order for/ })).toHaveCount(0);
  });

  test('a failed buy-order cancel on the Buy Orders page refreshes the order', async ({ page }) => {
    await signedIn(page);
    let status = 'ACTIVE';
    await page.route(/\/api\/buy-orders$/, (r) => r.fulfill(json([order(status)])));
    await page.route('**/api/buy-orders/5', (r) => { status = 'FILLED'; return r.fulfill(conflict('BUY_ORDER_NOT_ACTIVE', 'This buy order is no longer active')); });
    await page.goto('/buy-orders');
    await page.getByRole('button', { name: /Cancel buy order for/ }).click({ timeout: 20_000 });
    await expect(page.getByText(/no longer active/).first()).toBeVisible();
    await expect(page.getByRole('button', { name: /Cancel buy order for/ })).toHaveCount(0);
  });

  test('editing a buy order puts the caret in the max price box', async ({ page }) => {
    await signedIn(page);
    await page.route(/\/api\/buy-orders$/, (r) => r.fulfill(json([order('ACTIVE')])));
    await page.goto('/profile/buyorders');
    await page.getByRole('button', { name: /Edit buy order for/ }).click({ timeout: 20_000 });
    await expect(page.getByLabel('New max price per item in USD')).toBeFocused();
  });

  test('a failed listing cancel on My Stall refreshes the list', async ({ page }) => {
    await signedIn(page);
    let rows = [listing];
    await page.route('**/api/listings/my-stall', (r) => r.fulfill(json(rows)));
    await page.route('**/api/listings/1', (r) => { rows = []; return r.fulfill(conflict('LISTING_NOT_ACTIVE', 'Listing is no longer active (SOLD)')); });
    await page.goto('/me/stall');
    await page.getByRole('button', { name: 'Cancel listing' }).click({ timeout: 20_000 });
    await expect(page.getByText(/no longer active/).first()).toBeVisible();
    await expect(page.getByRole('button', { name: 'Cancel listing' })).toHaveCount(0);
  });

  test('a failed Hide on My Stall refreshes the list', async ({ page }) => {
    await signedIn(page);
    let rows = [listing];
    await page.route('**/api/listings/my-stall', (r) => r.fulfill(json(rows)));
    await page.route('**/api/listings/1/stall', (r) => { rows = []; return r.fulfill(conflict('LISTING_NOT_ACTIVE', 'Listing is no longer active (SOLD)')); });
    await page.goto('/me/stall');
    await page.getByRole('button', { name: 'Hide', exact: true }).click({ timeout: 20_000 });
    await expect(page.getByText(/no longer active/).first()).toBeVisible();
    await expect(page.getByRole('button', { name: 'Hide', exact: true })).toHaveCount(0);
  });

  for (const [path, name] of [['/profile/developers', /Developers/], ['/support', /Support/]]) {
    test(`on a phone, ${path} shows its selected profile tab`, async ({ page }) => {
      await page.setViewportSize({ width: 390, height: 844 });
      await signedIn(page);
      await page.goto(path);
      const tab = page.getByRole('tab', { name });
      await expect(tab).toHaveAttribute('aria-selected', 'true', { timeout: 20_000 });
      await expect.poll(async () => { const b = await tab.boundingBox(); return b ? b.x + b.width : 9999; }).toBeLessThanOrEqual(390);
    });
  }

  test('the loadout page\'s own Back button leaves the loadout\'s URL', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/loadouts/discover*', (r) => r.fulfill(json([card(3, 'Public Three'), card(4, 'Public Four')])));
    await page.route('**/api/loadouts/4', (r) => r.fulfill(json(body(4, 'Public Four'))));
    await page.goto('/loadout');
    await page.getByRole('link', { name: /Open loadout Public Four/ }).click({ timeout: 20_000 });
    await expect(page).toHaveURL(/\/loadout\/4$/);
    await page.getByRole('button', { name: '← Back' }).click();
    await expect(page.locator('.loadout-card')).toHaveCount(2);
    await expect(page).toHaveURL(/\/loadout$/);
  });
});
