// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 165.

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


// Defect pass 165. Real-app tests take the server from JAR / E2E_BASE_URL and
// stub every write: a real-jar test must never create or change server data.
const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';

async function noWrites(page) {
  await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
  await page.route('**/api/**', (r) => (['POST', 'PUT', 'DELETE', 'PATCH'].includes(r.request().method()) ? r.fulfill(json({})) : r.fallback()));
}
async function devLogin(page, userId = 2) {
  const res = await page.request.get(`${base}/api/auth/steam/dev-login?userId=${userId}&next=/`);
  expect(res.ok()).toBeTruthy();
}

test.describe('API key label box (real jar)', () => {
  test('Enter that commits an IME composition does not mint a key', async ({ page }) => {
    await noWrites(page);
    await devLogin(page);
    await page.route('**/api/api-keys', (r) => (r.request().method() === 'POST'
      ? r.fulfill(json({ id: 9001, token: 'sbx_test_token', label: 'stub', publicPrefix: 'sbx_test', scope: 'RW', createdAt: Date.now() }))
      : r.fallback()));
    await page.goto(`${base}/profile/developers`);
    const box = page.getByRole('textbox', { name: 'API key label' });
    await expect(box).toBeVisible({ timeout: 20_000 });
    const firstMint = page.waitForRequest((q) => /\/api\/api-keys$/.test(q.url()) && q.method() === 'POST');
    await box.fill('ab');
    // The Enter an IME sends to pick a candidate (isComposing true).
    await box.evaluate((el) => el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', isComposing: true, bubbles: true, cancelable: true })));
    await box.fill('abc');
    await box.press('Enter');
    const req = await firstMint;
    // Pre-fix the composing Enter minted a key labelled with the half-typed "ab".
    expect(JSON.parse(req.postData() || '{}').label).toBe('abc');
  });
});

test.describe('Support ticket status filter (real jar)', () => {
  const tickets = [
    { id: 41, subject: 'Old payout question', category: 'PAYMENT', status: 'RESOLVED', updatedAt: Date.now() - 86400000 },
    { id: 42, subject: 'Trade stuck', category: 'TRADE', status: 'WAITING_STAFF', updatedAt: Date.now() - 3600000 }
  ];
  async function setup(page) {
    await noWrites(page);
    await devLogin(page);
    await page.route(/\/api\/support\/tickets(\?|$)/, (r) => (r.request().method() === 'GET' ? r.fulfill(json(tickets)) : r.fallback()));
  }
  test('the chosen filter survives a reload', async ({ page }) => {
    await setup(page);
    await page.goto(`${base}/profile/support`);
    const group = page.getByRole('group', { name: 'Ticket status' });
    await group.getByRole('button', { name: /^Resolved · 1$/ }).click({ timeout: 20_000 });
    await expect(page.getByRole('button', { name: /Open ticket #41/ })).toBeVisible();
    await page.reload();
    await expect(group.getByRole('button', { name: /^Resolved · 1$/ })).toHaveAttribute('aria-pressed', 'true', { timeout: 20_000 });
    await expect(page.getByRole('button', { name: /Open ticket #41/ })).toBeVisible();
  });
  test('the chosen filter is back after another tab and browser Back', async ({ page }) => {
    await setup(page);
    await page.goto(`${base}/profile/support`);
    const group = page.getByRole('group', { name: 'Ticket status' });
    await group.getByRole('button', { name: /^Resolved · 1$/ }).click({ timeout: 20_000 });
    await page.getByRole('tab', { name: 'Developers' }).click();
    await expect(page).toHaveURL(/\/profile\/developers$/);
    await page.goBack();
    await expect(group.getByRole('button', { name: /^Resolved · 1$/ })).toHaveAttribute('aria-pressed', 'true', { timeout: 20_000 });
  });
});

test.describe('link names without arrow glyphs', () => {
  test('Developers help links are named without "→" (real jar)', async ({ page }) => {
    await noWrites(page);
    await devLogin(page);
    await page.goto(`${base}/profile/developers`);
    await expect(page.getByRole('link', { name: 'How to use the API', exact: true })).toBeVisible({ timeout: 20_000 });
    await expect(page.getByRole('link', { name: 'API reference', exact: true })).toBeVisible();
  });
  test('Notifications muted banner "Manage" link is named without "→"', async ({ page }) => {
    await signedIn(page);
    await page.addInitScript(() => { try { localStorage.setItem('sb_mute_kinds', '["MATCHES"]'); } catch (_) {} });
    await page.route(/\/api\/notifications(\?|$)/, (r) => r.fulfill(json({ items: [], unread: 0 })));
    await page.goto('/notifications');
    await expect(page.getByRole('link', { name: 'Manage', exact: true })).toBeVisible({ timeout: 20_000 });
  });
});
