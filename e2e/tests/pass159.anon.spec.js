// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 159.

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

// Pass 159: profile header, item rarity link, 404 page and API key copy names.

const JAR = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';

async function consent(page) {
  await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
}
// Real-jar pages load the static build under test (baseURL), so a fix is
// checked against live API data. Every write is stubbed: real-jar tests
// must not change server data.
async function realJar(page, baseURL) {
  await consent(page);
  await page.route(/\/(js|css)\/[^?]+\.(js|css)(\?.*)?$/, async (route) => {
    const u = new URL(route.request().url());
    if (u.origin === new URL(baseURL).origin) return route.fallback();
    const res = await route.fetch({ url: baseURL + u.pathname });
    await route.fulfill({ response: res });
  });
  await page.route('**/api/**', (r) => (r.request().method() === 'GET' ? r.fallback() : r.fulfill(json({}))));
}
async function devLogin(page, userId) {
  await page.request.get(`${JAR}/api/auth/steam/dev-login?userId=${userId}&next=/`);
}

test.describe('Profile overview header (real jar)', () => {
  test('the display name is a level-2 heading under the Profile title', async ({ page, baseURL }) => {
    await realJar(page, baseURL);
    await devLogin(page, 2);
    await page.goto(`${JAR}/profile`);
    const main = page.getByRole('main');
    await expect(main.getByRole('heading', { level: 1, name: 'Profile' })).toBeVisible({ timeout: 20_000 });
    await expect(main.getByRole('heading', { level: 2, name: /^AtlasTrades/ })).toBeVisible();
  });

  test('Copy stall link is named without the ⎘ glyph', async ({ page, baseURL }) => {
    await realJar(page, baseURL);
    await devLogin(page, 2);
    await page.goto(`${JAR}/profile`);
    const main = page.getByRole('main');
    await expect(main.getByRole('heading', { level: 1, name: 'Profile' })).toBeVisible({ timeout: 20_000 });
    await expect(main.getByRole('button', { name: 'Copy stall link', exact: true })).toBeVisible();
  });
});

test.describe('Item page rarity link (real jar)', () => {
  test('the rarity chip link says it browses the market, not just "Standard"', async ({ page, baseURL }) => {
    await realJar(page, baseURL);
    await page.goto(`${JAR}/item/1`);
    await expect(page.getByRole('heading', { level: 1, name: 'Short Parted Hair' })).toBeVisible({ timeout: 20_000 });
    const chip = page.locator('.modal-name-rarity');
    await expect(chip).toHaveText('Standard');
    await expect(page.getByRole('link', { name: 'Browse all Standard items', exact: true })).toHaveAttribute('href', '/market?rarity=Standard');
  });

  test('a Scarce item reads "Browse all Scarce items"', async ({ page, baseURL }) => {
    await realJar(page, baseURL);
    await page.goto(`${JAR}/item/10`);
    await expect(page.getByRole('heading', { level: 1, name: 'Santa 2024 Jacket' })).toBeVisible({ timeout: 20_000 });
    await expect(page.getByRole('link', { name: 'Browse all Scarce items', exact: true })).toHaveAttribute('href', '/market?rarity=Off-Market');
  });
});

test.describe('404 page (real jar)', () => {
  test('"404 · nothing here" is a level-2 heading under "Page not found"', async ({ page, baseURL }) => {
    await realJar(page, baseURL);
    await page.goto(`${JAR}/no-such-page`);
    const main = page.getByRole('main');
    await expect(main.getByRole('heading', { level: 1, name: 'Page not found' })).toBeVisible({ timeout: 20_000 });
    await expect(main.getByRole('heading', { level: 2, name: /^404 · nothing here$/ })).toBeVisible();
  });

});

test.describe('Stubbed pages', () => {
  test('Profile > Developers: the new key Copy key button is named without the ⎘ glyph', async ({ page }) => {
    await consent(page);
    await signedIn(page);
    await page.route('**/api/api-keys', (r) => (r.request().method() === 'POST'
      ? r.fulfill(json({ id: 9, token: 'sbx_live_abcdef123456', label: 'bot', prefix: 'sbx_live_ab', scope: 'RW' }))
      : r.fulfill(json([]))));
    await page.goto('/profile/developers');
    await page.getByRole('textbox', { name: 'API key label' }).fill('bot', { timeout: 20_000 });
    await page.getByRole('button', { name: '+ New Key' }).click();
    await expect(page.getByText('sbx_live_abcdef123456')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Copy key', exact: true })).toBeVisible();
  });
});
