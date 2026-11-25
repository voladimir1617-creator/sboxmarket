// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 157.

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


// Defect pass 157. Real-jar tests take the server from JAR / E2E_BASE_URL and
// serve only the page assets from the static copy under test (BASE_URL, when
// set). Every non-GET API call is stubbed: no test writes server data.

const JAR = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';
const STATIC = process.env.BASE_URL;
void asAdmin;

async function prep(page) {
  await page.addInitScript(() => {
    try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {}
    window.confirm = () => true;
  });
  await page.route(/^https?:\/\/(?!localhost|127\.0\.0\.1)/, (r) => r.abort());
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

test.describe('Profile Account setup checklist', () => {
  test('a fully set-up account does not flash "0/4 steps complete" while the profile loads', async ({ page }) => {
    await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
    await signedIn(page);
    const profile = {
      user: { id: ME.id, displayName: ME.displayName, steamId64: '76561190000000003', email: 'carol@example.com', emailVerified: true, tradeUrl: ME.tradeUrl },
      twoFactorEnabled: true, stats: {}, portfolio: {}
    };
    await page.route('**/api/profile/me', slow(profile, 6000));
    await page.goto('/profile');
    const panel = page.getByRole('tabpanel', { name: 'Personal Info' });
    await expect(panel.getByText('Display Name')).toBeVisible({ timeout: 20_000 });
    // While /profile/me is still out, nothing says the steps are undone.
    // A short timeout: the stub answers after 6 s, and a longer retry would
    // only pass once the banner had already flashed and gone.
    await expect(panel.getByText(/steps complete/)).toHaveCount(0, { timeout: 1000 });
    await expect(panel.getByText('(not set)', { exact: true })).toHaveCount(0, { timeout: 1000 });
    // Once it answers the checklist stays away (all four done) and the email shows.
    await expect(panel.getByText('carol@example.com')).toBeVisible({ timeout: 15_000 });
    await expect(panel.getByText(/steps complete/)).toHaveCount(0);
  });

  test('Account setup title is a heading (real jar)', async ({ page }) => {
    await prep(page); await useStaticAssets(page);
    await devLogin(page, 2);
    await page.goto(`${JAR}/profile`);
    const panel = page.getByRole('tabpanel', { name: 'Personal Info' });
    await expect(panel.getByRole('heading', { level: 2, name: /Account setup · \d\/4 steps complete/ })).toBeVisible({ timeout: 20_000 });
    await expect(panel.locator('.profile-setup-banner').getByRole('list').getByRole('listitem')).toHaveCount(4);
  });
});

test.describe('Stall About text and empty states (real jar)', () => {
  test('a URL at the end of a bio sentence links without the trailing punctuation', async ({ page }) => {
    await prep(page); await useStaticAssets(page);
    await page.route(`${JAR}/api/listings/stall/2`, async (r) => {
      if (r.request().method() !== 'GET') return r.fallback();
      const res = await r.fetch();
      const body = await res.json();
      body.seller.stallBio = 'Ping me on Discord (https://discord.gg/atlas). Trade hours: https://example.com/hours.';
      return r.fulfill(json(body));
    });
    await page.goto(`${JAR}/stall/2`);
    const main = page.getByRole('main');
    const discord = main.getByRole('link', { name: /discord\.gg/ });
    await expect(discord).toBeVisible({ timeout: 20_000 });
    await expect(discord).toHaveAttribute('href', 'https://discord.gg/atlas');
    await expect(main.getByRole('link', { name: /example\.com/ })).toHaveAttribute('href', 'https://example.com/hours');
    await expect(main.getByText(/Ping me on Discord \(https:\/\/discord\.gg\/atlas\)\. Trade hours/)).toBeVisible();
  });

  test('stall "No reviews yet" is a heading under the Reviews card', async ({ page }) => {
    await prep(page); await useStaticAssets(page);
    await page.goto(`${JAR}/stall/1`);
    const h3 = page.getByRole('main').getByRole('heading', { level: 3, name: 'No reviews yet' });
    await h3.scrollIntoViewIfNeeded({ timeout: 20_000 });
    await expect(h3).toBeInViewport();
  });
});

test.describe('Support thread links', () => {
  test('a URL ending a support message sentence links without the full stop', async ({ page }) => {
    await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
    await signedIn(page);
    const now = Date.now();
    const ticket = { id: 41, subject: 'Payout question', category: 'OTHER', status: 'WAITING_USER', createdAt: now - 7200_000, updatedAt: now - 3600_000 };
    await page.route('**/api/support/tickets', (r) => r.fulfill(json([ticket])));
    await page.route('**/api/support/tickets/41', (r) => r.fulfill(json({
      ticket,
      messages: [{ id: 1, body: 'See the status page: https://status.example.com/incidents/9.', authorRole: 'STAFF', createdAt: now - 3600_000 }]
    })));
    await page.goto('/support');
    await page.getByText('Payout question').first().click({ timeout: 20_000 });
    const link = page.getByRole('link', { name: /status\.example\.com/ });
    await expect(link).toBeVisible({ timeout: 20_000 });
    await expect(link).toHaveAttribute('href', 'https://status.example.com/incidents/9');
  });
});

test.describe('Phone width (real jar)', () => {
  test.use({ viewport: { width: 390, height: 844 } });
  test('stall bio link drops the trailing full stop on a phone too', async ({ page }) => {
    await prep(page); await useStaticAssets(page);
    await page.route(`${JAR}/api/listings/stall/1`, async (r) => {
      if (r.request().method() !== 'GET') return r.fallback();
      const body = await (await r.fetch()).json();
      body.seller.stallBio = 'Rules: https://example.com/rules.';
      return r.fulfill(json(body));
    });
    await page.goto(`${JAR}/stall/1`);
    const link = page.getByRole('main').getByRole('link', { name: /example\.com\/rules/ });
    await link.scrollIntoViewIfNeeded({ timeout: 20_000 });
    await expect(link).toBeInViewport();
    await expect(link).toHaveAttribute('href', 'https://example.com/rules');
  });
});
