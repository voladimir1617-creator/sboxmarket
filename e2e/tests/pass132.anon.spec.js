// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 132.

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


// Defect pass 132. Real-jar tests take the server from JAR / E2E_BASE_URL and
// write nothing (every POST/PUT/DELETE is answered by a stub); stubbed tests
// use the Playwright baseURL (the static copy).
const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';

async function consent(page) {
  await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
}
async function devLogin(page, id) {
  const r = await page.request.get(`${base}/api/auth/steam/dev-login?userId=${id}&next=/`);
  expect(r.ok()).toBeTruthy();
}
// Any write a real-jar test might set off is answered here, never by the server.
async function noWrites(page) {
  await page.route(`${base}/api/**`, (r) => (r.request().method() === 'GET' ? r.fallback() : r.fulfill(json({}))));
}

test.describe('pass132 · Profile > Listings hidden listings (real jar)', () => {
  test('the active count leaves hidden listings out and a hidden card says HIDDEN', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    // The seed has no hidden listings, so mark one of user 4's three newest as hidden.
    await page.route(`${base}/api/listings/my-stall`, async (r) => {
      const res = await r.fetch();
      const rows = (await res.json()).slice(0, 3).map((l, i) => ({ ...l, hidden: i === 0 }));
      await r.fulfill({ ...json(rows), headers: { 'X-Total-Count': '3' } });
    });
    await devLogin(page, 4);
    await page.goto(`${base}/profile/listings`);
    const cards = page.locator('.listing-grid .grid-card');
    await expect(cards).toHaveCount(3, { timeout: 20_000 });
    await expect(page.getByText(/^2 active listings · 1 hidden$/)).toBeVisible();
    await expect(cards.nth(0).locator('.grid-cat')).toContainText(/HIDDEN/);
    await expect(cards.nth(1).locator('.grid-cat')).not.toContainText(/HIDDEN/);
    await expect(cards.nth(2).locator('.grid-cat')).not.toContainText(/HIDDEN/);
  });
});

// Reviews in the shape the stall and Profile > Reviews read (the seed has none).
const REVIEWS = [5, 5, 4].map((rating, i) => ({
  id: 9100 + i, rating, comment: 'Pass132 review ' + i, reviewerUserId: 20 + i, reviewerName: 'buyer' + i,
  sellerUserId: 2, tradeId: 700 + i, createdAt: Date.now() - (i + 1) * 86400e3
}));

test.describe('pass132 · filter chips in named groups, no-match titles as headings (real jar)', () => {
  test('stall: rarity chips sit in a "Rarity" group and a no-match search is a heading', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await page.goto(`${base}/stall/2`);
    const group = page.getByRole('group', { name: 'Rarity' });
    await expect(group.getByRole('button', { name: /^All · \d+/ })).toHaveCount(1, { timeout: 20_000 });
    await expect(group.getByRole('button', { name: /^Limited · \d+/ })).toHaveCount(1);
    await page.getByRole('searchbox', { name: 'Filter stall by item name or category' }).fill('zzqqxx');
    await expect(page.getByRole('heading', { level: 2, name: /No listings match .zzqqxx. in this stall/ })).toBeVisible();
  });

  test('stall: review star chips sit in a "Filter by stars" group', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await page.route(`${base}/api/reviews/user/2`, (r) => r.fulfill(json(REVIEWS)));
    await page.goto(`${base}/stall/2`);
    const group = page.getByRole('group', { name: 'Filter by stars' });
    await group.scrollIntoViewIfNeeded({ timeout: 20_000 });
    await expect(group.getByRole('button', { name: 'Show all reviews' })).toHaveCount(1);
    await expect(group.getByRole('button', { name: 'Show 5-star reviews' })).toHaveCount(1);
  });

  test('Profile > Reviews: star chips sit in a group and "No reviews match this filter." is a heading', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await page.route(`${base}/api/reviews/user/4`, (r) => r.fulfill(json(REVIEWS.map((x) => ({ ...x, sellerUserId: 4 })))));
    await devLogin(page, 4);
    await page.goto(`${base}/profile/reviews`);
    const group = page.getByRole('group', { name: 'Filter by stars' });
    await group.getByRole('button', { name: 'Show 2-star reviews' }).click({ timeout: 20_000 });
    await expect(page.getByRole('heading', { level: 2, name: /^No reviews match this filter/ })).toBeVisible();
  });

  test('Sell > Steam inventory: rarity chips sit in a "Rarity" group and a no-match search is a heading', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    const items = Array.from({ length: 7 }, (_, i) => ({
      assetId: String(5000 + i), name: 'Pass132 Steam Item ' + i, rarity: i < 3 ? 'Limited' : 'Standard',
      tradable: true, marketable: true, amount: 1
    }));
    await page.route(`${base}/api/steam/inventory`, (r) => r.fulfill(json({ items, count: items.length })));
    await devLogin(page, 4);
    await page.goto(`${base}/sell`);
    const group = page.getByRole('group', { name: 'Rarity' });
    await expect(group.getByRole('button', { name: /^Limited · 3/ })).toHaveCount(1, { timeout: 20_000 });
    await page.getByRole('textbox', { name: 'Search your inventory' }).fill('zzqqxx');
    await expect(page.getByRole('heading', { level: 2, name: /^No Steam items match this filter/ })).toBeVisible();
  });
});
