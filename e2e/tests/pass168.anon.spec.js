// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 168.

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


// Defect pass 168.

// Real-jar tests: the running app, signed in through dev-login.
const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';
const consent = (page) => page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
// Real-jar tests never write: every POST/PUT/PATCH/DELETE is answered here.
const noWrites = (page) => page.route('**/api/**', (r) => {
  const m = r.request().method();
  if (m === 'GET' || m === 'HEAD') return r.fallback();
  return r.fulfill(json({ ok: true }));
});
const devLogin = (page, id) => page.request.get(`${base}/api/auth/steam/dev-login?userId=${id}&next=/`);

test.describe('link names keep their arrows out', () => {
  test('stall "Browse all listings" is named without its arrow', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await page.goto(`${base}/stall/1`);
    const link = page.getByRole('link', { name: 'Browse all listings', exact: true });
    await link.scrollIntoViewIfNeeded({ timeout: 20_000 });
    await expect(link).toBeVisible();
  });

  test('an empty stall\'s "Browse marketplace" is named without its arrow', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings/stall/9', (r) => r.fulfill(json({ seller: { id: 9, displayName: 'dave' }, listings: [], count: 0, rating: null })));
    await page.goto('/stall/9');
    await expect(page.getByRole('link', { name: 'Browse marketplace', exact: true })).toBeVisible();
  });
});

test.describe('toasts say the same thing for the same action', () => {
  test('Personal Info "Copy public stall link" toasts "Stall link copied.", like the stall and My Stall', async ({ page, context }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);
    await consent(page);
    await noWrites(page);
    await devLogin(page, 2);
    await page.goto(`${base}/profile`);
    await page.getByRole('button', { name: 'Copy public stall link' }).click({ timeout: 20_000 });
    await expect(page.getByRole('status').filter({ hasText: /Stall link copied\./ })).toBeVisible();
  });

  test('Profile Unblock toasts the stall header\'s wording', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await page.route('**/api/profile/blocks?limit=200', (r) => r.fulfill(json({ items: [{ blockedUserId: 5, displayName: 'EmberWolf' }] })));
    await devLogin(page, 2);
    await page.goto(`${base}/profile`);
    await page.getByRole('button', { name: 'Unblock EmberWolf' }).click({ timeout: 20_000 });
    await expect(page.getByRole('status').filter({ hasText: /@EmberWolf unblocked\. Their listings return to your grid\./ })).toBeVisible();
  });

  test('stall banner Unblock and header Block toggle toast the same wording', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings/stall/9', (r) => r.fulfill(json({ seller: { id: 9, displayName: 'dave' }, listings: [], blockedByViewer: true, rating: null })));
    await page.goto('/stall/9');
    await page.getByRole('button', { name: 'Unblock', exact: true }).click();
    await expect(page.getByRole('status').filter({ hasText: /@dave unblocked\. Their listings return to your grid\./ })).toBeVisible();
  });
});

test.describe('drafts kept across an expired session', () => {
  test('a stall bio saved on an expired session is still there after signing back in', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await page.route('**/api/profile/stall-bio', (r) => r.fulfill(json({ error: 'Not signed in' }, 401)));
    await devLogin(page, 1);
    await page.goto(`${base}/stall/1`);
    await page.getByTitle(/Edit your stall bio|Add a short bio/).click({ timeout: 20_000 });
    await page.getByRole('textbox', { name: 'Stall bio' }).fill('Fast trades, DMs open');
    await page.getByRole('button', { name: 'Save', exact: true }).click();
    // The expired session signs the page out.
    await expect(page.getByRole('button', { name: /Sign in/ }).first()).toBeVisible();
    await page.reload();
    await expect(page.getByRole('textbox', { name: 'Stall bio' })).toHaveValue('Fast trades, DMs open', { timeout: 20_000 });
  });

  test('a loadout rename saved on an expired session is still there after signing back in', async ({ page }) => {
    await signedIn(page);
    const LO = { loadout: { id: 7, name: 'Fit', ownerUserId: ME.id, ownerName: ME.displayName, visibility: 'PRIVATE', totalValue: 0 }, slots: [], currentTotal: 0 };
    await page.route('**/api/loadouts/7', (r) => r.request().method() === 'PUT'
      ? r.fulfill(json({ error: 'Not signed in' }, 401))
      : r.fulfill(json(LO)));
    await page.goto('/loadout/7');
    await page.getByRole('button', { name: /Rename/ }).click();
    await page.getByRole('textbox', { name: 'New loadout name' }).fill('Winter fit');
    await page.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(page.getByText(/Not signed in/).first()).toBeVisible();
    await page.reload();
    await expect(page.getByRole('textbox', { name: 'New loadout name' })).toHaveValue('Winter fit');
  });

  test('a new loadout name sent on an expired session is still there after signing back in', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/loadouts', (r) => r.request().method() === 'POST'
      ? r.fulfill(json({ error: 'Not signed in' }, 401))
      : r.fulfill(json([])));
    await page.goto('/loadout');
    await page.getByRole('button', { name: '+ Create', exact: true }).click();
    await page.getByRole('textbox', { name: 'New loadout name' }).fill('Winter fit');
    await page.getByRole('button', { name: 'Create', exact: true }).click();
    await expect(page.getByText(/Not signed in/).first()).toBeVisible();
    await page.reload();
    await expect(page.getByRole('textbox', { name: 'New loadout name' })).toHaveValue('Winter fit');
  });
});

test.describe('Database on a phone', () => {
  test.use({ viewport: { width: 390, height: 844 } });

  test('Next brings the new page\'s first rows into view', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await page.goto(`${base}/database`);
    const next = page.getByRole('button', { name: /Next/ });
    await next.scrollIntoViewIfNeeded({ timeout: 20_000 });
    await next.click();
    await expect(page).toHaveURL(/page=2/);
    const first = page.locator('tbody tr').first();
    await expect(first.locator('.db-rank')).toHaveText('#31');
    await expect(first).toBeInViewport();
  });
});
