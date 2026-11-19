// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 141.

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

// Pass 141: Help FAQ question headings, Personal Info email code/error tie and
// saved feedback, Status page price-refresh age.

const real = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';
const consent = (page) => page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
// Real-jar tests never write: every POST/PUT/DELETE/PATCH is answered here.
const noWrites = (page, answer = {}) => page.route('**/api/**', (r) =>
  ['GET', 'HEAD', 'OPTIONS'].includes(r.request().method()) ? r.continue() : r.fulfill(json(answer)));
// Personal Info with an unverified address on file (patched onto the real profile).
async function unverifiedEmail(page) {
  await page.route('**/api/profile/me', async (r) => {
    try {
      const res = await r.fetch();
      const body = await res.json();
      body.user = { ...(body.user || {}), email: 'pass141@example.com', emailVerified: false };
      await r.fulfill({ response: res, json: body });
    } catch (_) { /* the test ended while a profile re-read was in flight */ }
  });
}

test.describe('pass141 · Help Center FAQ questions (real jar)', () => {
  test('each FAQ question is a heading under its section, with the accordion button inside it', async ({ page }) => {
    await consent(page);
    await page.goto(`${real}/help`);
    await expect(page.getByTestId('help-faq-group').first()).toBeVisible({ timeout: 20_000 });
    const q = page.getByRole('heading', { level: 4, name: /^How do I sign in\?$/ });
    await expect(q).toBeVisible();
    const btn = q.getByRole('button', { name: /^How do I sign in\?$/ });
    await expect(btn).toHaveAttribute('aria-expanded', 'false');
    await btn.click();
    await expect(btn).toHaveAttribute('aria-expanded', 'true');
    await expect(page.getByRole('region', { name: /^How do I sign in\?$/ })).toBeVisible();
    // Every question, not just this one.
    const questions = await page.locator('.help-faq button[aria-expanded]').count();
    expect(questions).toBeGreaterThan(10);
    await expect(page.locator('.help-faq').getByRole('heading', { level: 4 })).toHaveCount(questions);
  });
});

test.describe('pass141 · Personal Info email verification (real jar, writes stubbed)', () => {
  test.beforeEach(async ({ page }) => {
    await consent(page);
    await page.request.get(`${real}/api/auth/steam/dev-login?userId=4&next=/`);
  });
  test.afterEach(async ({ page }) => { await page.unrouteAll({ behavior: 'ignoreErrors' }); });

  test('a refused verification code is tied to the code box, and an empty Verify puts focus back in it', async ({ page }) => {
    await noWrites(page);
    await unverifiedEmail(page);
    await page.route('**/api/profile/email/verify', (r) => r.fulfill(json({ error: 'That code is invalid or expired.', code: 'BAD_TOKEN' }, 400)));
    await page.goto(`${real}/profile/personal`);
    const code = page.getByRole('textbox', { name: 'Email verification code' });
    await expect(code).toBeVisible({ timeout: 20_000 });
    const verify = page.getByRole('button', { name: 'Verify', exact: true });
    await verify.click();
    await expect(page.getByRole('alert').filter({ hasText: /Paste the code from your verification email/ })).toBeVisible();
    await expect(code).toBeFocused();
    await code.fill('deadbeef');
    await verify.click();
    await expect(page.getByRole('alert').filter({ hasText: /That code is invalid or expired/ })).toBeVisible();
    await expect(code).toHaveAttribute('aria-invalid', 'true');
    await expect(code).toHaveAccessibleDescription(/That code is invalid or expired/);
  });

  test('saving an address and verifying it each say so in a status message', async ({ page }) => {
    await noWrites(page);
    await unverifiedEmail(page);
    await page.route('**/api/profile/email', (r) => r.request().method() === 'PUT'
      ? r.fulfill(json({ email: 'pass141@example.com', verified: false }))
      : r.continue());
    await page.route('**/api/profile/email/verify', (r) => r.fulfill(json({ verified: true })));
    await page.goto(`${real}/profile/personal`);
    await page.getByRole('button', { name: 'Edit email address' }).click({ timeout: 20_000 });
    await page.getByRole('textbox', { name: 'Email address' }).fill('pass141@example.com');
    await page.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(page.getByRole('status').filter({ hasText: /Verification email sent to pass141@example\.com/ })).toBeVisible();
    await page.getByRole('textbox', { name: 'Email verification code' }).fill('deadbeef');
    await page.getByRole('button', { name: 'Verify', exact: true }).click();
    await expect(page.getByRole('status').filter({ hasText: /^Email verified$/ })).toBeVisible();
  });
});

test.describe('pass141 · Status page price-refresh age (stubbed)', () => {
  test('a stalled sweep reads its age in hours and minutes, not thousands of seconds', async ({ page }) => {
    await page.route('**/api/**', (r) => r.fulfill(json({ status: 'UP' })));
    await page.route('**/api/items/price-refresh-status', (r) =>
      r.fulfill(json({ floor: { finishedAt: Date.now() - (2 * 3600 + 5 * 60 + 20) * 1000 } })));
    await page.goto('/status.html');
    const row = page.locator('.status-row').filter({ has: page.getByRole('heading', { level: 3, name: 'Price refresh' }) });
    await expect(row).toContainText(/data is 2h 5m old/, { timeout: 15_000 });
    await expect(row).not.toContainText(/\d{4,}s old/);
    await expect(row).toContainText('DOWN');
  });

  test('a fresh sweep still reads in seconds, and a few minutes reads in minutes', async ({ page }) => {
    let age = 12;
    await page.route('**/api/**', (r) => r.fulfill(json({ status: 'UP' })));
    await page.route('**/api/items/price-refresh-status', (r) => r.fulfill(json({ floor: { finishedAt: Date.now() - age * 1000 } })));
    await page.goto('/status.html');
    const row = page.locator('.status-row').filter({ has: page.getByRole('heading', { level: 3, name: 'Price refresh' }) });
    await expect(row).toContainText(/data is 1[2-4]s old/, { timeout: 15_000 });
    age = 4 * 60 + 30;
    await page.evaluate(() => window.runChecks());
    await expect(row).toContainText(/data is 4m old/);
  });
});
