// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 131.

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


// Defect pass 131. Real-jar tests take the server from JAR / E2E_BASE_URL and
// write nothing (only GETs are stubbed, and only where the seed has no data);
// stubbed tests use the Playwright baseURL (the static copy).
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
const T = (id, state, buying) => ({
  id, state, buyerUserId: buying ? ME.id : 9, sellerUserId: buying ? 9 : ME.id,
  itemId: 10 + id, itemName: 'Pass131 Item ' + id, price: '5.00', createdAt: Date.now() - id * 3600e3,
  counterpartyName: 'dave', counterpartyUserId: 9
});
const TRADES = [T(1, 'VERIFIED', true), T(2, 'PENDING_SELLER_SEND', false), T(3, 'CANCELLED', true), T(4, 'VERIFIED', false), T(5, 'PENDING_BUYER_CONFIRM', true)];

test.describe('pass131 · empty and failed-load titles are headings (real jar)', () => {
  test('Profile > Trades: "No trades yet" is a level-2 heading for an account with no trades', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await devLogin(page, 4);
    await page.goto(`${base}/profile/trades`);
    await expect(page.getByRole('heading', { level: 2, name: 'No trades yet' })).toBeVisible({ timeout: 20_000 });
  });

  test('Profile > Trades: "No trades match this filter." is a heading', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await page.route(`${base}/api/trades`, (r) => r.fulfill(json(TRADES.map((t) => ({ ...t, buyerUserId: t.buyerUserId === ME.id ? 4 : 9, sellerUserId: t.sellerUserId === ME.id ? 4 : 9 })))));
    await devLogin(page, 4);
    await page.goto(`${base}/profile/trades?state=disputed`);
    await expect(page.getByRole('heading', { level: 2, name: /No trades match this filter/ })).toBeVisible({ timeout: 20_000 });
  });

  test('My Stall: an empty stall says so in a level-2 heading', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await page.route(`${base}/api/listings/my-stall`, (r) => r.fulfill(json([])));
    await devLogin(page, 4);
    await page.goto(`${base}/me/stall`);
    await expect(page.getByRole('heading', { level: 2, name: 'Your stall is empty' })).toBeVisible({ timeout: 20_000 });
  });

  test('Profile > Listings: a failed load and an empty list are headings', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    let fail = true;
    await page.route(`${base}/api/listings/my-stall`, (r) => (fail ? r.fulfill(json({ error: 'boom' }, 500)) : r.fulfill(json([]))));
    await devLogin(page, 4);
    await page.goto(`${base}/profile/listings`);
    await expect(page.getByRole('heading', { level: 2, name: /Couldn.t load your listings/ })).toBeVisible({ timeout: 20_000 });
    fail = false;
    await page.getByRole('button', { name: 'Retry' }).click();
    await expect(page.getByRole('heading', { level: 2, name: 'No active listings' })).toBeVisible();
  });

  test('item page: "No active listings" is a heading under Active Listings', async ({ page }) => {
    await consent(page);
    await page.goto(`${base}/item/10`);
    const section = page.getByRole('heading', { level: 2, name: /^Active Listings \(0\)/ });
    await section.scrollIntoViewIfNeeded({ timeout: 20_000 });
    const empty = page.getByRole('heading', { level: 3, name: 'No active listings' });
    await empty.scrollIntoViewIfNeeded();
    await expect(empty).toBeInViewport();
  });
});

test.describe('pass131 · filter chips in named groups (real jar)', () => {
  test('My Stall: the listing-type chips sit in a named "Listing type" group', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await devLogin(page, 1);
    await page.goto(`${base}/me/stall`);
    const group = page.getByRole('group', { name: 'Listing type' });
    await expect(group).toBeVisible({ timeout: 20_000 });
    await expect(group.getByRole('button', { name: /^All · \d+$/ })).toHaveAttribute('aria-pressed', /true|false/);
    await expect(group.getByRole('button', { name: /^Auctions · \d+$/ })).toBeVisible();
  });

  test('Profile > Trades: the state "All" and the role "All" are told apart by their groups', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await page.route(`${base}/api/trades`, (r) => r.fulfill(json(TRADES.map((t) => ({ ...t, buyerUserId: t.buyerUserId === ME.id ? 4 : 9, sellerUserId: t.sellerUserId === ME.id ? 4 : 9 })))));
    await devLogin(page, 4);
    await page.goto(`${base}/profile/trades`);
    const state = page.getByRole('group', { name: 'Trade state' });
    const role = page.getByRole('group', { name: 'Role' });
    await expect(state.getByRole('button', { name: 'All', exact: true })).toHaveAttribute('aria-pressed', 'true', { timeout: 20_000 });
    await expect(state.getByRole('button', { name: 'Cancelled', exact: true })).toBeVisible();
    await expect(role.getByRole('button', { name: 'All', exact: true })).toHaveAttribute('aria-pressed', 'true');
    await role.getByRole('button', { name: 'Selling', exact: true }).click();
    await expect(role.getByRole('button', { name: 'Selling', exact: true })).toHaveAttribute('aria-pressed', 'true');
    await expect(state.getByRole('button', { name: 'All', exact: true })).toHaveAttribute('aria-pressed', 'true');
  });
});

test.describe('pass131 · stubbed empty and failed-load headings', () => {
  test('My Stall: a failed stall load is a heading inside the alert', async ({ page }) => {
    await consent(page);
    await signedIn(page);
    await page.route('**/api/listings/stall/3', (r) => r.fulfill(json({ seller: { id: 3 } })));
    await page.route('**/api/listings/my-stall', (r) => r.fulfill(json({ error: 'boom' }, 500)));
    await page.goto('/me/stall');
    await expect(page.getByRole('alert').getByRole('heading', { level: 2, name: /Couldn.t load your stall/ })).toBeVisible({ timeout: 20_000 });
  });

  test('Profile > Trades: a failed load is a heading inside the alert', async ({ page }) => {
    await consent(page);
    await signedIn(page);
    await page.route('**/api/trades', (r) => r.fulfill(json({ error: 'boom' }, 500)));
    await page.goto('/profile/trades');
    await expect(page.getByRole('alert').getByRole('heading', { level: 2, name: /Couldn.t load your trades/ })).toBeVisible({ timeout: 20_000 });
  });

  test.describe('phone', () => {
    test.use({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
    test('Profile > Trades on a phone: groups keep the bar inside the screen', async ({ page }) => {
      await consent(page);
      await signedIn(page);
      await page.route('**/api/trades', (r) => r.fulfill(json(TRADES)));
      await page.goto('/profile/trades');
      const role = page.getByRole('group', { name: 'Role' });
      await expect(role.getByRole('button', { name: 'Buying', exact: true })).toBeVisible({ timeout: 20_000 });
      const sw = await page.evaluate(() => document.documentElement.scrollWidth);
      expect(sw).toBeLessThanOrEqual(390);
    });
  });
});
