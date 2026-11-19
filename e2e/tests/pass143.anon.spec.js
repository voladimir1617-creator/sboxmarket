// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 143.

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


// Defect pass 143. Every test runs against the real app. Writes are stubbed:
// a real-jar test must never create or change server data.

const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';

async function noWrites(page) {
  await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
  await page.route('**/api/**', (r) => (['POST', 'PUT', 'DELETE', 'PATCH'].includes(r.request().method()) ? r.fulfill(json({})) : r.fallback()));
}

async function devLogin(page, userId = 3) {
  await page.request.get(`${base}/api/auth/steam/dev-login?userId=${userId}&next=/`);
}

// The feed is served in place of the real one, so nothing is read or marked on the server.
const now = Date.now();
const NOTES = [
  { id: 1, kind: 'TRADE_OPENED', read: false, title: 'Trade opened', body: 'Pass143 Hat', createdAt: now - 1000, path: '/profile' },
  { id: 2, kind: 'TRADE_OPENED', read: true, title: 'Trade done', body: 'Pass143 Boots', createdAt: now - 2000, path: '/profile' },
  { id: 3, kind: 'LISTING_MATCH', read: true, title: 'Match found', body: 'Pass143 Gloves', createdAt: now - 3000, path: '/profile' },
  { id: 4, kind: 'OFFER_RECEIVED', read: false, title: 'Offer in', body: 'Pass143 Cap', createdAt: now - 4000, path: '/profile' },
  { id: 5, kind: 'WELCOME', read: true, title: 'Welcome', body: 'Pass143 hello', createdAt: now - 5000, path: '/profile' },
  { id: 6, kind: 'WELCOME', read: true, title: 'Tips', body: 'Pass143 tips', createdAt: now - 6000, path: '/profile' },
];
async function feed(page) {
  await page.route((u) => u.pathname === '/api/notifications', (r) => (r.request().method() === 'GET'
    ? r.fulfill(json({ items: NOTES, unread: NOTES.filter((n) => !n.read).length }))
    : r.fulfill(json({}))));
}

test.describe('Database filters (real app)', () => {
  test('the nav Database link on a filtered /db shows the whole catalogue again', async ({ page }) => {
    await noWrites(page);
    const all = await (await page.request.get(`${base}/api/database?limit=1`)).json();
    await page.goto(`${base}/db?rarity=Limited&sort=newest`);
    const rarity = page.getByRole('combobox', { name: 'Filter catalogue by rarity' });
    await expect(rarity).toHaveValue('Limited', { timeout: 15_000 });
    await page.getByRole('navigation', { name: 'Primary' }).getByRole('link', { name: 'Database' }).click();
    await expect.poll(() => new URL(page.url()).search).toBe('');
    await expect(rarity).toHaveValue('All');
    await expect(page.getByRole('combobox', { name: 'Sort catalogue by' })).toHaveValue('rarest');
    await expect(page.locator('.db-meta')).toContainText(new RegExp(`^${Number(all.total).toLocaleString('en-US')}\\s+results`));
  });
});

test.describe('Notifications filters (real app, feed stubbed)', () => {
  test('g n on a filtered Notifications page clears the filters the URL dropped', async ({ page }) => {
    await noWrites(page);
    await feed(page);
    await devLogin(page);
    await page.goto(`${base}/notifications?kind=trades&show=unread`);
    const types = page.getByRole('group', { name: 'Notification type' });
    await expect(types.getByRole('button', { name: /^Trades · / })).toHaveAttribute('aria-pressed', 'true', { timeout: 15_000 });
    await page.locator('body').click({ position: { x: 5, y: 300 } });
    await page.keyboard.press('g');
    await page.keyboard.press('n');
    await expect.poll(() => new URL(page.url()).search).toBe('');
    await expect(types.getByRole('button', { name: /^All · / })).toHaveAttribute('aria-pressed', 'true');
    await expect(page.getByRole('group', { name: 'Read status' }).getByRole('button', { name: /^All/ })).toHaveAttribute('aria-pressed', 'true');
    await expect(page.getByRole('button', { name: /Open notification: Welcome/ })).toBeVisible();
  });
});

test.describe('Market search box (real app)', () => {
  test('clicking a recent search keeps focus in the search box', async ({ page }) => {
    await noWrites(page);
    await page.addInitScript(() => { try { localStorage.setItem('sb_recent_searches', JSON.stringify(['pass143 hat', 'pass143 boots'])); } catch (_) {} });
    await page.goto(`${base}/market`);
    const box = page.getByRole('combobox', { name: 'Search listings' });
    await box.click({ timeout: 15_000 });
    await page.getByRole('option', { name: 'pass143 boots' }).click();
    await expect(box).toHaveValue('pass143 boots');
    await expect(box).toBeFocused();
  });

  test('Escape on a recent-search Forget button closes the list and goes back to the box', async ({ page }) => {
    await noWrites(page);
    await page.addInitScript(() => { try { localStorage.setItem('sb_recent_searches', JSON.stringify(['pass143 hat', 'pass143 boots'])); } catch (_) {} });
    await page.goto(`${base}/market`);
    const box = page.getByRole('combobox', { name: 'Search listings' });
    await box.focus({ timeout: 15_000 });
    const list = page.getByRole('listbox', { name: 'Recent searches' });
    await expect(list).toBeVisible();
    const forget = page.getByRole('button', { name: /Forget .pass143 hat./ });
    await forget.focus();
    await page.keyboard.press('Escape');
    await expect(list).toHaveCount(0);
    await expect(box).toBeFocused();
    await expect(box).toHaveAttribute('aria-expanded', 'false');
  });
});

test.describe('Stall header (real app, writes stubbed)', () => {
  test('Retry follow status keeps keyboard focus on the Follow button', async ({ page }) => {
    await noWrites(page);
    let fail = true;
    await page.route('**/api/follows/status/2', async (r) => {
      if (fail) return r.fulfill(json({ error: 'boom' }, 500));
      await new Promise((ok) => setTimeout(ok, 400));
      return r.fallback();
    });
    await devLogin(page);
    await page.goto(`${base}/stall/2`);
    const retry = page.getByRole('button', { name: 'Retry follow status' });
    await retry.focus({ timeout: 15_000 });
    fail = false;
    await page.keyboard.press('Enter');
    const follow = page.getByRole('button', { name: /^\+\s*Follow|Following/ });
    await expect(follow).toBeVisible();
    await expect(follow).toBeFocused();
  });
});

test.describe('Support (real app, phone, writes stubbed)', () => {
  test.use({ viewport: { width: 390, height: 844 } });
  const T = { id: 990143, subject: 'Pass143 where is my hat', category: 'TRADE', status: 'WAITING_STAFF', createdAt: now - 3600e3, updatedAt: now - 3600e3 };

  test('Report a Bug from an open ticket opens the bug form', async ({ page }) => {
    await noWrites(page);
    await page.route((u) => u.pathname === '/api/support/tickets', (r) => (r.request().method() === 'GET' ? r.fulfill(json([T])) : r.fulfill(json({}))));
    await page.route(`**/api/support/tickets/${T.id}`, (r) => r.fulfill(json({ ticket: T, messages: [{ id: 1, author: 'USER', authorName: 'me', body: 'Pass143 hello', createdAt: now }] })));
    await devLogin(page);
    await page.goto(`${base}/support`);
    await page.getByRole('button', { name: new RegExp(`Open ticket #${T.id}`) }).click({ timeout: 15_000 });
    await expect(page.getByRole('heading', { name: new RegExp(`#${T.id}`) })).toBeVisible();
    const link = page.getByRole('contentinfo').getByRole('link', { name: 'Report a Bug' });
    await link.scrollIntoViewIfNeeded();
    await link.click();
    await expect(page.getByRole('textbox', { name: 'Ticket subject' })).toHaveValue('Bug report');
    await expect(page.getByRole('textbox', { name: 'Ticket subject' })).toBeFocused();
  });
});
