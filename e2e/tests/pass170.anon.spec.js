// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 170.

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


// Defect pass 170.

const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';
const consent = (page) => page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
// Real-jar tests never write: every POST/PUT/PATCH/DELETE is answered here.
const noWrites = (page) => page.route(`${base}/api/**`, (r) => (['GET', 'HEAD'].includes(r.request().method()) ? r.fallback() : r.fulfill(json({}))));
const devLogin = (page, id) => page.request.get(`${base}/api/auth/steam/dev-login?userId=${id}&next=/`);
const PHONE = { width: 390, height: 844 };
// Nothing inside the element reaches past its own box or the viewport's right edge.
const clipped = (loc) => loc.evaluate((el) => {
  const W = document.documentElement.clientWidth;
  const r = el.getBoundingClientRect();
  return [el, ...el.querySelectorAll('*')].some((e) => {
    const b = e.getBoundingClientRect();
    return b.width > 0 && (b.right > Math.min(W, r.right) + 1 || b.left < Math.max(0, r.left) - 1);
  }) || el.scrollWidth > el.clientWidth + 1;
});
const LONG = 'Supercalifragilisticexpialidocious_nothing_matches_this_zz';

test.describe('pass170 · no-match headings with a long search term on a phone (real jar)', () => {
  test.use({ viewport: PHONE });

  test('Loadout Lab Discover: the no-match heading wraps the search term inside the panel', async ({ page }) => {
    await consent(page);
    await page.goto(`${base}/loadout`);
    await page.getByRole('textbox', { name: 'Search public loadouts' }).fill(LONG);
    const head = page.getByRole('heading', { name: /^No loadouts match/ });
    await expect(head).toBeVisible({ timeout: 20_000 });
    expect(await clipped(head)).toBe(false);
  });

  test('Help Center: the No FAQs match heading wraps the search term', async ({ page }) => {
    await consent(page);
    await page.goto(`${base}/help?q=${LONG}`);
    const head = page.getByRole('heading', { name: /^No FAQs match/ });
    await expect(head).toBeVisible({ timeout: 20_000 });
    expect(await clipped(head)).toBe(false);
  });

  test('FAQ page: the No FAQs match heading wraps the search term', async ({ page }) => {
    await consent(page);
    await page.goto(`${base}/faq?q=${LONG}`);
    const head = page.getByRole('heading', { name: /^No FAQs match/ });
    await expect(head).toBeVisible({ timeout: 20_000 });
    expect(await clipped(head)).toBe(false);
  });

  test('stall listing filter: the no-match heading wraps the search term', async ({ page }) => {
    await consent(page);
    await page.goto(`${base}/stall/2`);
    await page.getByRole('searchbox', { name: 'Filter stall by item name or category' }).fill(LONG);
    const head = page.getByRole('heading', { name: /^No listings match/ });
    await expect(head).toBeVisible({ timeout: 20_000 });
    await head.scrollIntoViewIfNeeded();
    expect(await clipped(head)).toBe(false);
  });

  test('Notifications: the no-match heading wraps the search term', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await devLogin(page, 2);
    await page.goto(`${base}/notifications?find=${LONG}`);
    const head = page.getByRole('heading', { name: /^No notifications match/ });
    await expect(head).toBeVisible({ timeout: 20_000 });
    expect(await clipped(head)).toBe(false);
  });
});

test.describe('pass170 · support ticket with a long subject on a phone (real jar)', () => {
  test.use({ viewport: PHONE });

  test('Profile > Support: a long unbroken subject wraps inside its row', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await devLogin(page, 2);
    const SUBJ = 'Withdrawal_reference_' + 'x'.repeat(59);
    await page.route(/\/api\/support\/tickets(\?|$)/, (r) => (r.request().method() === 'GET'
      ? r.fulfill(json([{ id: 41, subject: SUBJ, category: 'OTHER', status: 'WAITING_STAFF', updatedAt: Date.now() }]))
      : r.fallback()));
    await page.goto(`${base}/profile/support`);
    const row = page.getByRole('button', { name: /^Open ticket #41/ });
    await expect(row).toBeVisible({ timeout: 20_000 });
    expect(await clipped(row)).toBe(false);
  });
});
