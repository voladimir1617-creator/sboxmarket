// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 150.

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


// Defect pass 150. Every test runs against the real jar; only page assets
// come from the static copy under test (BASE_URL). No test writes server data.

const JAR = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';
const STATIC = process.env.BASE_URL;
void ME; void signedIn; void asAdmin;

async function prep(page) {
  await page.addInitScript(() => {
    try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {}
    window.confirm = () => true;
  });
  await page.route(/^https?:\/\/(?!localhost|127\.0\.0\.1)/, (r) => r.abort());
  // Belt and braces: a write here would change shared server data.
  await page.route(`${JAR}/api/**`, (r) => (r.request().method() === 'GET' ? r.fallback() : r.fulfill(json({}))));
}
async function useStaticAssets(page) {
  if (!STATIC) return;
  await page.route(`${JAR}/**`, async (r) => {
    const u = new URL(r.request().url());
    if (u.pathname.startsWith('/api/')) return r.fallback();
    const res = await page.request.fetch(STATIC + u.pathname + u.search);
    await r.fulfill({ response: res });
  });
}
async function devLogin(page, userId) {
  await page.request.get(`${JAR}/api/auth/steam/dev-login?userId=${userId}&next=/`);
}

test.describe('Steam profile links (real jar)', () => {
  test('stall hero Steam link is named "Steam", without the arrow glyph', async ({ page }) => {
    await prep(page);
    await useStaticAssets(page);
    await page.goto(`${JAR}/stall/2`);
    const h2 = page.getByRole('main').getByRole('heading', { level: 2, name: /^AtlasTrades/ });
    await expect(h2).toBeVisible({ timeout: 20_000 });
    await expect(h2.getByRole('link', { name: 'Steam', exact: true })).toBeVisible();
  });

  test('Profile header and Personal Info Steam links drop the arrow from their names', async ({ page }) => {
    await prep(page);
    await useStaticAssets(page);
    await devLogin(page, 2);
    await page.goto(`${JAR}/profile`);
    const main = page.getByRole('main');
    await expect(main.getByRole('link', { name: 'View Steam profile', exact: true })).toBeVisible({ timeout: 20_000 });
    await expect(main.getByRole('tabpanel').getByRole('link', { name: 'Steam', exact: true })).toBeVisible();
  });
});

test.describe('Stall trust and reviews (real jar)', () => {
  test('Trust signals rows do not read their decorative glyphs', async ({ page }) => {
    await prep(page);
    await useStaticAssets(page);
    await page.goto(`${JAR}/stall/2`);
    const list = page.locator('.stall-trust-list');
    await list.scrollIntoViewIfNeeded({ timeout: 20_000 });
    await expect(list.getByRole('listitem').first()).toBeVisible();
    const snap = await list.ariaSnapshot();
    expect(snap).not.toMatch(/(listitem|text): [·✓↗]/);
  });

  test('a star filter with no reviews shows a no-match heading, like the stall listings', async ({ page }) => {
    await prep(page);
    await useStaticAssets(page);
    const now = Date.now();
    const reviews = [1, 2, 3].map((i) => ({ id: 900 + i, rating: 5, comment: 'Great ' + i, fromDisplayName: 'Buyer' + i, createdAt: now - i * 3600_000, helpfulCount: 0 }));
    await page.route(`${JAR}/api/reviews/user/2*`, (r) => (r.request().method() === 'GET' ? r.fulfill(json(reviews)) : r.fallback()));
    await page.goto(`${JAR}/stall/2`);
    const one = page.getByRole('button', { name: 'Show 1-star reviews' });
    await one.scrollIntoViewIfNeeded({ timeout: 20_000 });
    await one.click();
    await expect(page.getByRole('heading', { level: 3, name: /^No 1. reviews yet\.$/ })).toBeVisible();
  });
});

test.describe('Profile overview (real jar)', () => {
  test('Account Standing is a section heading under the Profile h1', async ({ page }) => {
    await prep(page);
    await useStaticAssets(page);
    await devLogin(page, 2);
    await page.goto(`${JAR}/profile`);
    await expect(page.getByRole('main').getByRole('heading', { level: 2, name: 'Account Standing' })).toBeVisible({ timeout: 20_000 });
  });

  test('Account setup steps say whether each is done, not just a glyph', async ({ page }) => {
    await prep(page);
    await useStaticAssets(page);
    await devLogin(page, 2);
    await page.goto(`${JAR}/profile`);
    const banner = page.locator('.profile-setup-banner');
    await expect(banner).toBeVisible({ timeout: 20_000 });
    const step = banner.getByRole('listitem').filter({ hasText: 'Add an email address' });
    await expect(step).toHaveText(/To do: Add an email address/);
    const snap = await banner.ariaSnapshot();
    expect(snap).not.toMatch(/[◯✓⚠]/);
  });
});

test.describe('Phone width (real jar)', () => {
  test.use({ viewport: { width: 390, height: 844 } });
  test('stall star-filter no-match line is a heading on a phone too', async ({ page }) => {
    await prep(page);
    await useStaticAssets(page);
    const now = Date.now();
    const reviews = [1, 2, 3].map((i) => ({ id: 950 + i, rating: 4, comment: 'Fine ' + i, fromDisplayName: 'Pal' + i, createdAt: now - i * 3600_000, helpfulCount: 0 }));
    await page.route(`${JAR}/api/reviews/user/2*`, (r) => (r.request().method() === 'GET' ? r.fulfill(json(reviews)) : r.fallback()));
    await page.goto(`${JAR}/stall/2`);
    const two = page.getByRole('button', { name: 'Show 2-star reviews' });
    await two.scrollIntoViewIfNeeded({ timeout: 20_000 });
    await two.click();
    const h = page.getByRole('heading', { level: 3, name: /^No 2. reviews yet\.$/ });
    await h.scrollIntoViewIfNeeded();
    await expect(h).toBeInViewport();
  });
});
