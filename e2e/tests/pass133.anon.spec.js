// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 133.

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

// Defect pass 133: Notifications filtered empty states, copy/share
// confirmations read out, Developers empty state and phone cells, footer
// current page.

const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';

// Real-jar tests never write: every POST/PUT/DELETE/PATCH is answered here.
async function realSignIn(page, userId = 2) {
  await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
  await page.route('**/api/**', (r) => (['POST', 'PUT', 'DELETE', 'PATCH'].includes(r.request().method())
    ? r.fulfill(json({}))
    : r.fallback()));
  await page.request.get(`${base}/api/auth/steam/dev-login?userId=${userId}&next=/`);
}

const now = Date.now();
const NOTES = [
  { id: 1, kind: 'OFFER_RECEIVED', title: 'New offer', body: 'Bob offered $3 on WW1 Helmet', read: false, createdAt: now - 60_000 },
  { id: 2, kind: 'TRADE_COMPLETED', title: 'Trade complete', body: 'Leather Coat delivered', read: true, createdAt: now - 3_600_000 },
  { id: 3, kind: 'TRADE_SHIPPED', title: 'Trade sent', body: 'Wizard Gown sent', read: true, createdAt: now - 7_200_000 },
  { id: 4, kind: 'OFFER_RECEIVED', title: 'New offer', body: 'Ann offered $2 on Chef Hat', read: false, createdAt: now - 172_800_000 },
];
const stubNotes = (page) => page.route('**/api/notifications', (r) => (r.request().method() === 'GET'
  ? r.fulfill(json({ items: NOTES, unread: 2 }))
  : r.fulfill(json({}))));

test.describe('pass133 · Notifications filtered empty states (real jar)', () => {
  test('Unread + Trades with unread offers left does not say "all caught up"', async ({ page }) => {
    await realSignIn(page);
    await stubNotes(page);
    await page.goto(`${base}/notifications?show=unread`);
    await expect(page.getByRole('heading', { level: 1, name: /Notifications · 2 unread/ })).toBeVisible({ timeout: 20_000 });
    await page.getByRole('group', { name: 'Notification type' }).getByRole('button', { name: /^Trades/ }).click();
    await expect(page.getByRole('heading', { level: 2, name: /No unread trades notifications/ })).toBeVisible();
    await expect(page.getByText(/You.re all caught up/)).toHaveCount(0);
    // Show all still lands on the trade rows.
    await page.getByRole('button', { name: 'Show all' }).click();
    await expect(page.getByRole('button', { name: /Open notification: Trade complete/ })).toBeVisible();
  });

  test('the no-match and type-filter empty lines are headings like "Quiet so far"', async ({ page }) => {
    await realSignIn(page);
    await stubNotes(page);
    await page.goto(`${base}/notifications?find=zzzz`);
    await expect(page.getByRole('heading', { level: 2, name: /No notifications match "zzzz"/ })).toBeVisible({ timeout: 20_000 });
    await page.goto(`${base}/notifications?kind=wallet`);
    await expect(page.getByRole('heading', { level: 2, name: /No wallet notifications in this view/ })).toBeVisible({ timeout: 20_000 });
  });

  test('Unread with no type filter still says "all caught up", as a heading', async ({ page }) => {
    await realSignIn(page);
    await page.route('**/api/notifications', (r) => r.fulfill(json({ items: NOTES.map((n) => ({ ...n, read: true })), unread: 0 })));
    await page.goto(`${base}/notifications?show=unread`);
    await expect(page.getByRole('heading', { level: 2, name: /You.re all caught up/ })).toBeVisible({ timeout: 20_000 });
  });
});

test.describe('pass133 · copy and share confirmations are read out (real jar)', () => {
  test.beforeEach(async ({ context }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);
  });

  test('item page Share: the copied link is announced, not only flashed', async ({ page }) => {
    await realSignIn(page);
    await page.goto(`${base}/item/38`);
    const btn = page.getByTitle('Share this item');
    await btn.click({ timeout: 20_000 });
    await expect(btn).toHaveText('Copied');
    await expect(page.getByRole('status').filter({ hasText: /Link copied/ })).toBeVisible();
  });

  test('market Copy Filtered URL: the copy is announced', async ({ page }) => {
    await realSignIn(page);
    await page.goto(`${base}/market?search=hat`);
    await page.getByRole('button', { name: /Copy Filtered URL/ }).click({ timeout: 20_000 });
    await expect(page.getByRole('status').filter({ hasText: /Link copied/ })).toBeVisible();
  });

  test('Profile Copy Steam ID: the copy is announced', async ({ page }) => {
    await realSignIn(page);
    await page.goto(`${base}/profile`);
    const btn = page.getByRole('button', { name: 'Copy Steam ID', exact: true });
    await btn.click({ timeout: 20_000 });
    await expect(btn).toHaveText('✓');
    await expect(page.getByRole('status').filter({ hasText: /Steam ID copied/ })).toBeVisible();
  });

  test('My Stall Share stall: the copy is announced', async ({ page }) => {
    await realSignIn(page);
    await page.goto(`${base}/me/stall`);
    await page.getByRole('button', { name: 'Share stall', exact: true }).click({ timeout: 20_000 });
    await expect(page.getByRole('status').filter({ hasText: /Stall link copied/ })).toBeVisible();
  });
});

test.describe('pass133 · Developers page', () => {
  test('real jar: "No API keys yet." is a heading', async ({ page }) => {
    await realSignIn(page);
    await page.goto(`${base}/profile/developers`);
    await expect(page.getByRole('heading', { level: 2, name: 'No API keys yet.' })).toBeVisible({ timeout: 20_000 });
  });

  test('real jar: a failed key list load is a heading', async ({ page }) => {
    await realSignIn(page);
    await page.route('**/api/api-keys', (r) => r.fulfill(json({ error: 'boom' }, 500)));
    await page.goto(`${base}/profile/developers`);
    await expect(page.getByRole('heading', { level: 2, name: 'Could not load your API keys.' })).toBeVisible({ timeout: 20_000 });
  });

  test('phone: the key Prefix and Scope cells say what they are', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await signedIn(page);
    await page.route('**/api/api-keys', (r) => r.fulfill(json([
      { id: 1, label: 'my-bot', publicPrefix: 'sbx_ab12', scope: 'RO', createdAt: now - 86_400_000, lastUsedAt: null, revoked: false },
    ])));
    await page.goto('/profile/developers');
    const row = page.getByRole('row', { name: /my-bot/ });
    await row.scrollIntoViewIfNeeded({ timeout: 20_000 });
    await expect(row.getByRole('cell', { name: /^Prefix · sbx_ab12/ })).toBeVisible();
    await expect(row.getByRole('cell', { name: /^Scope · RO$/ })).toBeVisible();
    // The Revoke name is unchanged.
    await expect(row.getByRole('button', { name: 'Revoke API key my-bot' })).toBeVisible();
  });
});

test.describe('pass133 · footer marks the current page (real jar)', () => {
  test('the footer link to the page being shown has aria-current=page', async ({ page }) => {
    await realSignIn(page);
    const footer = page.locator('footer.site-footer');
    await page.goto(`${base}/faq`);
    await expect(footer.getByRole('link', { name: 'FAQ', exact: true })).toHaveAttribute('aria-current', 'page', { timeout: 20_000 });
    await expect(footer.getByRole('link', { name: 'Help Center' })).not.toHaveAttribute('aria-current', /./);
    await page.goto(`${base}/notifications`);
    await expect(footer.getByRole('link', { name: 'Notifications', exact: true })).toHaveAttribute('aria-current', 'page', { timeout: 20_000 });
    await expect(footer.getByRole('link', { name: 'FAQ', exact: true })).not.toHaveAttribute('aria-current', /./);
    await expect(footer.locator('a[aria-current]')).toHaveCount(1);
  });
});
