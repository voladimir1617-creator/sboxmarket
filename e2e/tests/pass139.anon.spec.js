// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 139.

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

// Defect pass 139: the My Stall Away switch ignores a second click while its
// write is out, the My Stall "Link" button copies a link to that listing,
// the stall bio error is tied to the bio box, the Help Center contact entry
// is a heading, and the Database "Listed only" empty state names that box.

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

test.describe('pass139 · My Stall toolbar (real jar)', () => {
  test('a double-click on the Away switch sends one write, not on-then-off', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    const writes = [];
    await page.route('**/api/listings/away', (r) => {
      if (r.request().method() !== 'POST') return r.fallback();
      writes.push(r.request().postDataJSON());
      return new Promise((ok) => setTimeout(ok, 700)).then(() => r.fulfill(json({ hidden: true, affected: 0, until: null })));
    });
    await devLogin(page, 2);
    await page.goto(`${base}/me/stall`);
    const sw = page.getByRole('switch', { name: 'Away mode (hide all listings)' });
    await expect(sw).toHaveAttribute('aria-checked', 'false', { timeout: 20_000 });
    await sw.dblclick();
    await page.waitForTimeout(1500);
    expect(writes).toEqual([{ hidden: true }]);
    await expect(sw).toHaveAttribute('aria-checked', 'true');
    await expect(page.getByText(/Away Mode — all listings hidden/)).toBeVisible();
  });

  test('a row.s Link button copies a link that opens on that listing', async ({ page }) => {
    await consent(page);
    await page.addInitScript(() => {
      window.__copied = [];
      try {
        Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async (t) => { window.__copied.push(t); } } });
      } catch (_) {}
    });
    await noWrites(page);
    await devLogin(page, 2);
    await page.goto(`${base}/me/stall`);
    await page.getByRole('button', { name: 'Copy a link to Sports Bandage' }).click({ timeout: 20_000 });
    await expect(page.getByText(/Link copied to clipboard/)).toBeVisible();
    const copied = await page.evaluate(() => window.__copied);
    expect(copied).toHaveLength(1);
    const u = new URL(copied[0]);
    expect(u.pathname).toMatch(/^\/item\/\d+$/);
    const lid = u.searchParams.get('listing');
    expect(lid).toMatch(/^\d+$/);
    // The listing really is this seller's Sports Bandage.
    const res = await page.request.get(`${base}/api/listings/my-stall`);
    const rows = await res.json();
    const mine = (Array.isArray(rows) ? rows : rows.items || []).find((l) => String(l.id) === lid);
    expect(mine?.item?.name).toBe('Sports Bandage');
  });
});

test.describe('pass139 · stall bio editor (real jar)', () => {
  test('the "Type a bio first" error is tied to the bio box', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await devLogin(page, 2);
    await page.goto(`${base}/stall/2`);
    await page.getByRole('button', { name: '+ Add a bio' }).click({ timeout: 20_000 });
    const box = page.getByRole('textbox', { name: 'Stall bio' });
    await expect(box).toBeFocused();
    await page.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(page.getByRole('alert').filter({ hasText: /Type a bio first/ })).toBeVisible();
    await expect(box).toHaveAttribute('aria-invalid', 'true');
    await expect(box).toHaveAccessibleDescription(/Type a bio first/);
  });
});

test.describe('pass139 · Help Center (real jar)', () => {
  test('the contact entry at the foot of the page is a heading', async ({ page }) => {
    await consent(page);
    await page.goto(`${base}/help`);
    const head = page.getByRole('heading', { name: /Still need a hand/, level: 2 });
    await head.scrollIntoViewIfNeeded({ timeout: 20_000 });
    await expect(head).toBeInViewport();
    await expect(page.getByRole('link', { name: 'Contact Support' })).toHaveAttribute('href', '/support');
  });
});

test.describe('pass139 · Database empty state (real jar)', () => {
  test('with only Listed only on, the empty state says to clear Listed only', async ({ page }) => {
    await consent(page);
    await page.addInitScript(() => { try { localStorage.setItem('sb_db_listed_only', '1'); } catch (_) {} });
    await page.route((u) => u.pathname === '/api/database', (r) => r.fulfill(json({ items: [], total: 0, indexed: 39 })));
    await page.goto(`${base}/db`);
    await expect(page.getByRole('heading', { name: 'No items match your filters' })).toBeVisible({ timeout: 20_000 });
    await expect(page.getByRole('checkbox', { name: 'Listed only' })).toBeChecked();
    await expect(page.getByText(/Try clearing .*Listed only/)).toBeVisible();
    await page.getByRole('button', { name: 'Clear Filters' }).click();
    await expect(page.getByRole('checkbox', { name: 'Listed only' })).not.toBeChecked();
  });
});

test.describe('pass139 · Database empty state (stubbed)', () => {
  test('a search plus Listed only names Listed only too', async ({ page }) => {
    await consent(page);
    await page.route('**/api/**', (r) => r.fulfill(json({})));
    await page.route('**/api/auth/steam/me', (r) => r.fulfill(json({ signedIn: false })));
    await page.addInitScript(() => { try { localStorage.setItem('sb_db_listed_only', '1'); } catch (_) {} });
    await page.route((u) => u.pathname === '/api/database', (r) => r.fulfill(json({ items: [], total: 0, indexed: 39 })));
    await page.goto('/db?q=zzz');
    await expect(page.getByRole('heading', { name: 'No items match your filters' })).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText(/Try clearing .*Listed only/)).toBeVisible();
  });
});
