// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 161.

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

// Pass 161: market list view, Refresh toast, Help ?q= links, Profile Reviews Pending tabs.

const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';

async function consent(page) {
  await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
}
// Real-jar tests read only: every write is answered here, never by the server.
async function noWrites(page) {
  await page.route('**/api/**', (r) => (r.request().method() === 'GET' ? r.fallback() : r.fulfill(json({}))));
}
async function devLogin(page, userId) {
  await page.request.get(`${base}/api/auth/steam/dev-login?userId=${userId}&next=/`);
}

test.describe('pass161 · market list view (real jar)', () => {
  test('the v shortcut keeps keyboard focus on the same item when the view flips', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await page.addInitScript(() => { try { localStorage.setItem('sb_market_view', 'grid'); } catch (_) {} });
    await page.goto(`${base}/market`);
    const card = page.locator('.listing-grid .grid-card-link').first();
    await expect(card).toBeVisible({ timeout: 20_000 });
    const href = await card.getAttribute('href');
    await card.focus();
    await page.keyboard.press('v');
    await expect(page.locator('.listing-table')).toBeVisible();
    const rowLink = page.locator(`.listing-table .item-name a[href="${href}"]`);
    await expect(rowLink).toBeFocused();
    await page.keyboard.press('v');
    await expect(page.locator(`.listing-grid .grid-card-link[href="${href}"]`)).toBeFocused();
  });

  test('Refresh that fails does not also toast "Listings refreshed."', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await page.goto(`${base}/market`);
    await expect(page.locator('#main a[href^="/item/"]').first()).toBeVisible({ timeout: 20_000 });
    await page.route('**/api/listings?**', (r) => r.fulfill(json({ error: 'boom' }, 500)));
    const refresh = page.getByRole('button', { name: /^Refresh(ing)? listings$/ });
    await refresh.click();
    await expect(page.getByTestId('market-stale-warning').or(page.getByTestId('market-load-error'))).toBeVisible();
    await expect(refresh).not.toHaveAttribute('aria-disabled', 'true');
    await page.waitForTimeout(400);
    // A snapshot, not a retrying assertion: the toast would time out on its own.
    expect(await page.locator('.sale-toast', { hasText: /Listings refreshed/ }).count()).toBe(0);
  });
});

test.describe('pass161 · Profile Reviews Pending keeps its tabs (real jar)', () => {
  test('?view=pending shows the Reviews filter tabs while the Pending check loads', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    let release;
    const gate = new Promise((ok) => { release = ok; });
    await page.route('**/api/reviews/pending**', async (r) => { await gate; await r.fallback(); });
    await devLogin(page, 2);
    await page.goto(`${base}/profile/reviews?view=pending`);
    const tabs = page.getByRole('tablist', { name: 'Reviews filter' });
    try {
      await expect(tabs).toBeVisible({ timeout: 15_000 });
      await expect(tabs.getByRole('tab', { name: /^Pending/ })).toHaveAttribute('aria-selected', 'true');
    } finally { release(); }
    await expect(page.getByRole('tabpanel', { name: /^Pending/ })).toBeVisible();
  });
});

test.describe('pass161 · Profile Reviews Pending Retry (stubbed)', () => {
  test('Retry on a failed Pending check keeps the tabs on screen while it reloads', async ({ page }) => {
    await signedIn(page);
    await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
    let calls = 0; let release;
    const gate = new Promise((ok) => { release = ok; });
    await page.route('**/api/reviews/pending**', async (r) => {
      calls += 1;
      if (calls === 1) return r.fulfill(json({ error: 'down' }, 500));
      await gate;
      return r.fulfill(json({ count: 0, items: [] }));
    });
    await page.goto('/profile/reviews?view=pending');
    const retry = page.getByRole('alert').filter({ hasText: /Couldn.t check for trades/ }).getByRole('button', { name: 'Retry' });
    await expect(retry).toBeVisible({ timeout: 20_000 });
    await retry.click();
    const tabs = page.getByRole('tablist', { name: 'Reviews filter' });
    try {
      await expect(tabs).toBeVisible({ timeout: 5_000 });
      await expect(tabs.getByRole('tab', { name: /^Pending/ })).toHaveAttribute('aria-selected', 'true');
    } finally { release(); }
    await expect(page.getByRole('heading', { name: /all caught up/ })).toBeVisible();
  });
});

test.describe('pass161 · Help ?q= links while Help is open (stubbed)', () => {
  test('a link to /help?q=API while Help is open opens the API answer, as a fresh load does', async ({ page }) => {
    await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
    await page.route('**/api/**', (r) => r.fulfill(json({})));
    await page.goto('/help?q=2fa');
    const box = page.getByRole('searchbox', { name: 'Search frequently asked questions' });
    await expect(box).toHaveValue('2fa', { timeout: 20_000 });
    await page.evaluate(() => { history.pushState({}, '', '/help?q=API'); window.dispatchEvent(new PopStateEvent('popstate')); });
    await expect(box).toHaveValue('API');
    const q = page.getByRole('button', { name: /use the API from a bot/ });
    await expect(q).toHaveAttribute('aria-expanded', 'true');
    await expect(page.getByRole('region', { name: /use the API from a bot/ })).toBeVisible();
  });

  test('Back to an earlier /help?q= search opens that answer again', async ({ page }) => {
    await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
    await page.route('**/api/**', (r) => r.fulfill(json({})));
    await page.goto('/help?q=API');
    const box = page.getByRole('searchbox', { name: 'Search frequently asked questions' });
    await expect(box).toHaveValue('API', { timeout: 20_000 });
    await page.evaluate(() => { history.pushState({}, '', '/help?q=appeal'); window.dispatchEvent(new PopStateEvent('popstate')); });
    await expect(box).toHaveValue('appeal');
    await expect(page.getByRole('region', { name: /banned/i })).toBeVisible();
    await page.goBack();
    await expect(box).toHaveValue('API');
    await expect(page.getByRole('button', { name: /use the API from a bot/ })).toHaveAttribute('aria-expanded', 'true');
  });
});
