// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 76.

const ME = { id: 3, displayName: 'carol', role: 'USER', signedIn: true, sessionEpoch: 0, tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc' };
const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });

async function signedIn(page) {
  await page.addInitScript(() => { window.confirm = () => true; });
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json(ME)));
}
async function asAdmin(page) {
  await signedIn(page);
  await page.route('**/api/admin/check', (r) => r.fulfill(json({ admin: true })));
}

const STEAM = { items: [
  { assetId: 'a1', name: 'Red Beanie', rarity: 'Standard', tradable: true, itemId: 11 },
  { assetId: 'a2', name: 'Blue Jacket', rarity: 'Standard', tradable: true, itemId: 12 },
], count: 2, lastSyncedAt: Date.now() };
test('sell deep-link filter with a small inventory can be cleared', async ({ page }) => {
  await signedIn(page);
  await page.route('**/api/steam/inventory', (r) => r.fulfill(json(STEAM)));
  await page.route('**/api/listings/inventory', (r) => r.fulfill(json([])));
  await page.goto('/sell?q=' + encodeURIComponent('Green Gloves'));
  await expect(page.getByText('No Steam items match this filter.')).toBeVisible();
  // The user must be able to drop the filter that hides their items.
  await page.getByRole('button', { name: 'Clear', exact: true }).click();
  await expect(page.getByText('Red Beanie')).toBeVisible();
});

const U = (id, name) => ({ id, displayName: name, steamId64: '7656' + id, role: 'USER', banned: false });
test('admin users tab: failed load is not "No matching users"', async ({ page }) => {
  await asAdmin(page);
  await page.route('**/api/admin/users**', (r) => r.fulfill(json({ error: 'boom' }, 500)));
  await page.goto('/admin?tab=users');
  await expect(page.getByRole('alert').filter({ hasText: /couldn.t load users/i })).toBeVisible();
  await expect(page.getByText('No matching users.')).toHaveCount(0);
});
test('admin users search: a slow earlier query does not overwrite the newer one', async ({ page }) => {
  await asAdmin(page);
  await page.route('**/api/admin/users**', async (r) => {
    const q = new URL(r.request().url()).searchParams.get('search') || '';
    if (q === 'al') { await new Promise((ok) => setTimeout(ok, 1500)); return r.fulfill(json([U(1, 'alice'), U(2, 'albert')])); }
    if (q === 'alice') return r.fulfill(json([U(1, 'alice')]));
    return r.fulfill(json([U(1, 'alice'), U(2, 'albert'), U(3, 'bob')]));
  });
  await page.goto('/admin?tab=users');
  await expect(page.getByText('bob')).toBeVisible();
  const box = page.getByPlaceholder('Search by name, Steam ID…');
  await box.fill('al');
  await page.waitForTimeout(100);
  await box.fill('alice');
  await expect(page.getByText('albert')).toHaveCount(0);
  await page.waitForTimeout(2000);
  await expect(page.getByText('albert')).toHaveCount(0);
  await expect(page.getByText('alice', { exact: true })).toBeVisible();
});
test('admin user detail drawer closes on Escape', async ({ page }) => {
  await asAdmin(page);
  await page.route('**/api/admin/users**', (r) => r.fulfill(json([U(5, 'erin')])));
  await page.goto('/admin?tab=users');
  await page.getByRole('button', { name: 'View user detail for erin' }).click();
  const close = page.getByRole('button', { name: 'Close', exact: true });
  await expect(close).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(close).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'View user detail for erin' })).toBeFocused();
});

const T = { id: 41, itemName: 'Red Beanie', buyerName: 'bob', sellerName: 'dave', state: 'DISPUTED', price: 5, updatedAt: Date.now() };
test('admin trades tab: failed load is not "No disputed trades"', async ({ page }) => {
  await asAdmin(page);
  await page.route('**/api/admin/trades**', (r) => r.fulfill(json({ error: 'boom' }, 500)));
  await page.goto('/admin?tab=trades');
  await expect(page.getByRole('alert').filter({ hasText: /couldn.t load trades/i })).toBeVisible();
  await expect(page.getByText('No disputed trades.')).toHaveCount(0);
});
test('admin trade chat: failed load is not "No messages in this trade"', async ({ page }) => {
  await asAdmin(page);
  await page.route('**/api/admin/trades**', (r) => r.fulfill(json([T])));
  await page.route('**/api/trades/41/messages', (r) => r.fulfill(json({ error: 'boom' }, 500)));
  await page.goto('/admin?tab=trades');
  await page.getByRole('button', { name: '💬 Chat' }).click();
  await expect(page.getByRole('alert').filter({ hasText: /couldn.t load this chat/i })).toBeVisible();
  await expect(page.getByText('No messages in this trade.')).toHaveCount(0);
});

const STALL = { seller: { id: 9, displayName: 'dave', soldCount: 4 }, count: 0, rating: { count: 12, average: 4.8 }, listings: [] };
const REVIEW = { id: 5, rating: 5, comment: 'fast and friendly', createdAt: Date.now() - 86400e3, reviewerName: 'erin' };
test('stall page says when its reviews failed to load, and Retry brings them back', async ({ page }) => {
  await signedIn(page);
  let fail = true;
  await page.route('**/api/listings/stall/9', (r) => r.fulfill(json(STALL)));
  await page.route('**/api/reviews/user/9', (r) => fail ? r.fulfill(json({ error: 'boom' }, 500)) : r.fulfill(json([REVIEW])));
  await page.goto('/stall/9');
  await expect(page.getByText('dave', { exact: true }).first()).toBeVisible();
  const alert = page.getByRole('alert').filter({ hasText: /couldn.t load this seller.s reviews/i });
  await expect(alert).toBeVisible();
  fail = false;
  await alert.getByRole('button', { name: 'Retry' }).click();
  await expect(page.getByText('fast and friendly')).toBeVisible();
});
test('profile reviews says when pending reviews failed to load, and Retry shows the Pending tab', async ({ page }) => {
  await signedIn(page);
  let fail = true;
  await page.route('**/api/profile/me', (r) => r.fulfill(json({ user: { ...ME }, accountStanding: { level: 'GOOD' } })));
  await page.route('**/api/reviews/user/3', (r) => r.fulfill(json([])));
  await page.route('**/api/reviews/pending', (r) => fail ? r.fulfill(json({ error: 'boom' }, 500))
    : r.fulfill(json({ count: 1, items: [{ tradeId: 41, itemName: 'Red Beanie', sellerUserId: 9, sellerName: 'dave', settledAt: Date.now() }] })));
  await page.goto('/profile/reviews');
  await expect(page.getByRole('tab', { name: /Received/ })).toBeVisible();
  const alert = page.getByRole('alert').filter({ hasText: /couldn.t check for trades waiting on a review/i });
  await expect(alert).toBeVisible();
  fail = false;
  await alert.getByRole('button', { name: 'Retry' }).click();
  await expect(page.getByRole('tab', { name: /Pending/ })).toBeVisible();
});
