// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 140.

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

// Defect pass 140: Retry buttons on failed loads (stall reviews, public
// loadout) and the Profile > Reviews edit form.

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

test.describe('pass140 · stall reviews Retry (real jar)', () => {
  test('a double-click on the reviews Retry asks for the reviews once', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    let gets = 0;
    await page.route(/\/api\/reviews\/user\/2(\?.*)?$/, (r) => {
      if (r.request().method() !== 'GET') return r.fallback();
      gets += 1;
      return new Promise((ok) => setTimeout(ok, 700)).then(() => r.fulfill(json({ error: 'boom' }, 500)));
    });
    await devLogin(page, 3);
    await page.goto(`${base}/stall/2`);
    const alert = page.getByRole('alert').filter({ hasText: /couldn.t load this seller.s reviews/i });
    await expect(alert).toBeVisible({ timeout: 20_000 });
    const retry = alert.getByRole('button', { name: 'Retry' });
    const before = gets;
    await retry.dblclick();
    await expect(retry).toHaveAttribute('aria-disabled', 'true');
    await page.waitForTimeout(1500);
    expect(gets - before).toBe(1);
    await expect(retry).not.toHaveAttribute('aria-disabled', 'true');
    await expect(retry).toBeFocused();
  });
});

test.describe('pass140 · public loadout Retry (real jar)', () => {
  test('Retry on a loadout that failed to load keeps keyboard focus on the page', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    let fail = true;
    await page.route(/\/api\/loadouts\/1(\?.*)?$/, (r) => (fail && r.request().method() === 'GET'
      ? r.fulfill(json({ error: 'boom' }, 500))
      : r.fallback()));
    await page.goto(`${base}/loadout/1`);
    await expect(page.getByText(/Couldn.t load this loadout/)).toBeVisible({ timeout: 20_000 });
    fail = false;
    await page.getByRole('button', { name: 'Retry', exact: true }).press('Enter');
    await expect(page.getByRole('heading', { level: 1, name: 'Cardboard Connoisseur' })).toBeVisible();
    await expect(page.getByRole('button', { name: '← Back' })).toBeFocused();
  });

  test('Retry that fails again lands on the new Retry, not the page body', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await page.route(/\/api\/loadouts\/1(\?.*)?$/, (r) => (r.request().method() === 'GET'
      ? new Promise((ok) => setTimeout(ok, 300)).then(() => r.fulfill(json({ error: 'boom' }, 500)))
      : r.fallback()));
    await page.goto(`${base}/loadout/1`);
    await expect(page.getByText(/Couldn.t load this loadout/)).toBeVisible({ timeout: 20_000 });
    await page.getByRole('button', { name: 'Retry', exact: true }).press('Enter');
    await expect(page.getByText(/Couldn.t load this loadout/)).toBeVisible();
    await expect(page.getByRole('button', { name: 'Retry', exact: true })).toBeFocused();
  });
});

test.describe('pass140 · desktop header current section (real jar)', () => {
  test('on an item page the header marks Market as the current section, as the phone tab bar does', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await page.goto(`${base}/item/1`);
    const primary = page.getByRole('navigation', { name: 'Primary' });
    const market = primary.getByRole('link', { name: 'Market', exact: true });
    await expect(market).toHaveAttribute('aria-current', 'true', { timeout: 20_000 });
    await expect(market).toHaveClass(/\bactive\b/);
    await expect(primary.locator('[aria-current]')).toHaveCount(1);
    await page.goto(`${base}/market`);
    await expect(market).toHaveAttribute('aria-current', 'page', { timeout: 20_000 });
  });
});

const STALL = { seller: { id: 9, displayName: 'dave', soldCount: 4 }, count: 0, rating: { count: 1, average: 5 }, listings: [] };
const REVIEW = { id: 5, rating: 5, comment: 'fast and friendly', createdAt: Date.now() - 86400e3, fromDisplayName: 'erin' };
test.describe('pass140 · stall reviews Retry (stubbed)', () => {
  test('a double-click on the reviews Retry sends one request and the reviews come back', async ({ page }) => {
    await consent(page);
    await signedIn(page);
    let fail = true, gets = 0;
    await page.route('**/api/listings/stall/9', (r) => r.fulfill(json(STALL)));
    await page.route('**/api/reviews/user/9', (r) => {
      gets += 1;
      return new Promise((ok) => setTimeout(ok, 500)).then(() => r.fulfill(fail ? json({ error: 'boom' }, 500) : json([REVIEW])));
    });
    await page.goto('/stall/9');
    const alert = page.getByRole('alert').filter({ hasText: /couldn.t load this seller.s reviews/i });
    await expect(alert).toBeVisible();
    fail = false;
    const before = gets;
    await alert.getByRole('button', { name: 'Retry' }).dblclick();
    await expect(page.getByText('fast and friendly')).toBeVisible();
    expect(gets - before).toBe(1);
  });
});
