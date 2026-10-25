// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 70.

const ME = { id: 3, displayName: 'carol', role: 'USER', signedIn: true, sessionEpoch: 0 };
const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });

async function signedIn(page) {
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json(ME)));
}
const card = (id, name) => ({ id, name, favorites: 1, totalValue: 5, filledSlots: 1, ownerName: 'bob' });
const body = (id, name) => ({ loadout: { id, name, ownerUserId: 9, ownerName: 'bob', favorites: 2, visibility: 'PUBLIC' }, slots: [], favorited: false });
// Opens a path through the app's own link handling, as an in-app click does.
async function inApp(page, path) {
  await page.evaluate((p) => {
    const a = document.createElement('a'); a.href = p; a.textContent = 'x';
    document.body.appendChild(a); a.click(); a.remove();
  }, path);
}

test.describe('defect pass 70', () => {
  test('a loadout link that fails to load says so instead of showing another loadout as "doesn\'t exist"', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/loadouts/discover*', (r) => r.fulfill(json([card(3, 'Public Three')])));
    await page.route('**/api/loadouts/3', (r) => r.fulfill(json(body(3, 'Public Three'))));
    let fail = true;
    await page.route('**/api/loadouts/7', (r) => r.fulfill(fail ? json({ error: 'boom' }, 500) : json(body(7, 'Seven Fit'))));
    await page.goto('/loadout/7');
    await expect(page.getByText("Couldn't load this loadout")).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText(/doesn't exist/)).toHaveCount(0);
    await expect(page.getByText('Public Three')).toHaveCount(0);
    fail = false;
    await page.getByRole('button', { name: 'Retry', exact: true }).click();
    await expect(page.getByText('Seven Fit').first()).toBeVisible();
  });

  test('a loadout that really is missing still says so', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/loadouts/discover*', (r) => r.fulfill(json([])));
    await page.route('**/api/loadouts/7', (r) => r.fulfill(json({ notFound: true })));
    await page.goto('/loadout/7');
    await expect(page.getByText('Loadout not found')).toBeVisible({ timeout: 20_000 });
  });

  test('opening a loadout card gives it its own URL, and Back returns to the list', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/loadouts/discover*', (r) => r.fulfill(json([card(3, 'Public Three'), card(4, 'Public Four')])));
    await page.route('**/api/loadouts/3', (r) => r.fulfill(json(body(3, 'Public Three'))));
    await page.route('**/api/loadouts/4', (r) => r.fulfill(json(body(4, 'Public Four'))));
    await page.goto('/market');
    await inApp(page, '/loadout');
    await page.getByRole('link', { name: /Open loadout Public Four/ }).click({ timeout: 20_000 });
    await expect(page).toHaveURL(/\/loadout\/4$/);
    await page.goBack();
    await expect(page).toHaveURL(/\/loadout$/);
    await expect(page.locator('.loadout-card')).toHaveCount(2);
  });

  test('the Notifications filter chips do not claim 0 when the list failed to load', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/notifications/unread-count*', (r) => r.fulfill(json({ unread: 7, count: 7 })));
    await page.route(/\/api\/notifications(\?.*)?$/, (r) => r.fulfill(json({ error: 'boom' }, 500)));
    await page.goto('/notifications');
    await expect(page.getByText(/Couldn.t load notifications/).first()).toBeVisible({ timeout: 20_000 });
    await expect(page.locator('button.offer-tab').nth(0)).toHaveText('All');
    await expect(page.locator('button.offer-tab').nth(1)).toHaveText('Unread');
  });

  test('a star added on the Database page shows on the Watchlist page', async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem('sb_watchlist', '[]');
      localStorage.setItem('sb_watchlist_synced:3', '1');
    });
    await signedIn(page);
    const item = { id: 11, name: 'Cool Hat', category: 'Hats', rarity: 'Limited', lowestPrice: '5', supply: 1 };
    let server = [];
    await page.route('**/api/database*', (r) => r.fulfill(json({ items: [item], total: 1, indexed: 1 })));
    await page.route(/\/api\/watchlist$/, (r) => r.fulfill(json(server)));
    await page.route('**/api/watchlist/11', (r) => { server = [11]; return r.fulfill(json({ ids: [11] })); });
    await page.route('**/api/items/batch*', (r) => r.fulfill(json([item])));
    await page.route('**/api/listings*', (r) => r.fulfill(json([
      { id: 70, price: '5.00', listingType: 'BUY_NOW', status: 'ACTIVE', sellerUserId: 2, item },
    ])));
    await page.goto('/db');
    await page.locator('.db-row-star').first().click({ timeout: 20_000 });
    await expect(page.getByText('Added to watchlist')).toBeVisible();
    await inApp(page, '/watchlist');
    await expect(page.locator('h1.info-modal-header')).toContainText('1 item', { timeout: 10_000 });
  });
});
