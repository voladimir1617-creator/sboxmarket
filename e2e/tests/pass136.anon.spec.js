// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 136.

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

// Defect pass 136: seller ratings in the item-page listings table (and the
// other rating chips) read with their scale, and the failed-load messages on
// My Stall Analytics, Personal Info and Support are headings like their
// siblings.

const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';

async function consent(page) {
  await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
}
// Real-jar tests never write: every POST/PUT/DELETE/PATCH is answered here.
async function noWrites(page) {
  await page.route('**/api/**', (r) => (['POST', 'PUT', 'DELETE', 'PATCH'].includes(r.request().method())
    ? r.fulfill(json({}))
    : r.fallback()));
}
async function devLogin(page, userId) {
  await page.request.get(`${base}/api/auth/steam/dev-login?userId=${userId}&next=/`);
}

test.describe('pass136 · item-page listings table (real jar)', () => {
  test('a seller rating in the Active Listings table reads "Rated 4.5 out of 5 from 12 reviews"', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    // The real listings of item 12, with review figures on the first row.
    const rows = await (await page.request.get(`${base}/api/listings/item/12`)).json();
    rows[0].sellerRating = 4.5;
    rows[0].sellerReviewCount = 12;
    await page.route(/\/api\/listings\/item\/12(\?.*)?$/, (r) => r.fulfill(json(rows)));
    await page.goto(`${base}/item/12`);
    const row = page.locator(`.modal-listing-row[data-listing-id="${rows[0].id}"]`);
    await expect(row.getByRole('img', { name: 'Rated 4.5 out of 5 from 12 reviews' })).toBeVisible({ timeout: 20_000 });
  });
});

test.describe('pass136 · failed loads are headings (real jar)', () => {
  test('My Stall Analytics: "Could not load your listing analytics." is a heading', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await devLogin(page, 2);
    await page.route('**/api/listings/my-stall/analytics', (r) => r.fulfill(json({ error: 'boom' }, 500)));
    await page.goto(`${base}/me/stall`);
    await page.getByRole('tab', { name: /^Analytics/ }).click({ timeout: 20_000 });
    await expect(page.getByRole('alert').getByRole('heading', { name: /Could not load your listing analytics/ })).toBeVisible();
  });

  test('Personal Info: "Couldn.t load your profile details." is a heading', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await devLogin(page, 2);
    await page.route('**/api/profile/me', (r) => r.fulfill(json({ error: 'boom' }, 500)));
    await page.goto(`${base}/profile`);
    await expect(page.getByRole('alert').getByRole('heading', { name: /Couldn.t load your profile details/ })).toBeVisible({ timeout: 20_000 });
  });
});

test.describe('pass136 · stubbed', () => {
  test('Support: "Could not load your support tickets." is a heading', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/support/tickets', (r) => r.fulfill(json({ error: 'boom' }, 500)));
    await page.goto('/support');
    await expect(page.getByRole('alert').getByRole('heading', { name: /Could not load your support tickets/ })).toBeVisible({ timeout: 20_000 });
  });
});
