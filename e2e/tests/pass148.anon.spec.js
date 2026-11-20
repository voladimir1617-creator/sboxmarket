// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 148.

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


// Defect pass 148. Real-app tests take the server from JAR / E2E_BASE_URL and
// stub every write: a real-jar test must never create or change server data.

const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';

async function noWrites(page) {
  await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
  await page.route('**/api/**', (r) => (['POST', 'PUT', 'DELETE', 'PATCH'].includes(r.request().method()) ? r.fulfill(json({})) : r.fallback()));
}
async function devLogin(page, userId = 2) {
  await page.request.get(`${base}/api/auth/steam/dev-login?userId=${userId}&next=/`);
}

test.describe('header account menu (real app)', () => {
  test('the menu is named and marks the page you are on', async ({ page }) => {
    await noWrites(page);
    await devLogin(page);
    await page.goto(`${base}/settings`);
    const chip = page.getByRole('button', { name: /^User menu/ });
    await chip.click();
    const menu = page.getByRole('menu', { name: 'Account menu' });
    await expect(menu).toBeVisible({ timeout: 20_000 });
    await expect(menu.getByRole('menuitem', { name: 'Settings' })).toHaveAttribute('aria-current', 'page');
    await expect(menu.getByRole('menuitem', { name: 'Profile' })).not.toHaveAttribute('aria-current', /.+/);
    await expect(menu.locator('[aria-current]')).toHaveCount(1);
  });

  test('on Profile > Trades only Trades is current; on a loadout, Loadout Lab is', async ({ page }) => {
    await noWrites(page);
    await devLogin(page);
    await page.goto(`${base}/profile/trades`);
    const chip = page.getByRole('button', { name: /^User menu/ });
    await chip.click();
    const menu = page.getByRole('menu', { name: 'Account menu' });
    await expect(menu.getByRole('menuitem', { name: /^Trades/ })).toHaveAttribute('aria-current', 'page', { timeout: 20_000 });
    await expect(menu.getByRole('menuitem', { name: 'Profile' })).not.toHaveAttribute('aria-current', /.+/);
    await page.keyboard.press('Escape');
    await expect(chip).toBeFocused();
    await page.goto(`${base}/loadout/1`);
    await page.getByRole('button', { name: /^User menu/ }).click();
    await expect(page.getByRole('menuitem', { name: 'Loadout Lab' })).toHaveAttribute('aria-current', 'true', { timeout: 20_000 });
  });
});

test.describe('Database item rows (real app)', () => {
  test('a row names its item once: the thumbnail beside the link is decorative', async ({ page }) => {
    await noWrites(page);
    await page.goto(`${base}/db`);
    const row = page.locator('.db-table tbody tr').first();
    const link = row.getByRole('link', { name: /^Open .+ detail/ });
    await expect(link).toBeVisible({ timeout: 20_000 });
    const name = (await link.textContent() || '').trim();
    expect(name.length).toBeGreaterThan(0);
    await expect(row.getByRole('img', { name, exact: true })).toHaveCount(0);
  });
});

test.describe('Help Center and FAQ search (real app)', () => {
  test('Help: the match count is announced as the list narrows, and a miss is announced too', async ({ page }) => {
    await noWrites(page);
    await page.goto(`${base}/help`);
    const box = page.getByRole('searchbox', { name: 'Search frequently asked questions' });
    await box.scrollIntoViewIfNeeded({ timeout: 20_000 });
    await box.fill('withdraw');
    const live = page.getByRole('status').filter({ hasText: /matching question/ });
    await expect(live).toHaveText(/^\d+ matching questions?$/);
    const shown = await page.locator('.help-faq-q').count();
    await expect(live).toHaveText(new RegExp(`^${shown} matching question`));
    await box.fill('zzqqxx');
    await expect(page.getByRole('status').filter({ hasText: /^No matching questions$/ })).toHaveCount(1);
  });

  test('FAQ page: the match count is announced too', async ({ page }) => {
    await noWrites(page);
    await page.goto(`${base}/faq`);
    const box = page.getByRole('textbox', { name: 'Search FAQ' });
    await box.fill('auction');
    await expect(page.getByRole('status').filter({ hasText: /^\d+ matching questions?$/ })).toHaveCount(1, { timeout: 20_000 });
    await box.fill('zzqqxx');
    await expect(page.getByRole('status').filter({ hasText: /^No matching questions$/ })).toHaveCount(1);
  });

  test('Help: the no-match Open a ticket link carries no arrow glyph', async ({ page }) => {
    await noWrites(page);
    await page.goto(`${base}/help`);
    const box = page.getByRole('searchbox', { name: 'Search frequently asked questions' });
    await box.scrollIntoViewIfNeeded({ timeout: 20_000 });
    await box.fill('zzqqxx');
    await expect(page.getByRole('link', { name: 'Open a ticket', exact: true })).toBeVisible();
  });

  test('FAQ page: the support links carry no arrow glyph', async ({ page }) => {
    await noWrites(page);
    await page.goto(`${base}/faq`);
    await expect(page.getByRole('link', { name: 'Open a support ticket', exact: true })).toBeVisible({ timeout: 20_000 });
    await expect(page.getByTestId('faq-still-stuck').getByRole('link', { name: 'Open a ticket', exact: true })).toHaveCount(1);
    await page.getByRole('textbox', { name: 'Search FAQ' }).fill('zzqqxx');
    await expect(page.getByRole('link', { name: 'Open a ticket', exact: true })).toBeVisible();
  });
});

const NOW = Date.now();
const TICKET = { id: 7, subject: 'Stuck trade', category: 'TRADE', status: 'WAITING_USER', userId: ME.id, username: 'carol', updatedAt: NOW };
const MSGS = [
  { id: 1, author: 'USER', authorName: 'carol', body: 'My trade is stuck', createdAt: NOW - 600e3 },
  { id: 2, author: 'STAFF', authorName: 'Ann', body: 'Looking into it', createdAt: NOW - 300e3 }
];

test.describe('Support ticket thread (stubbed)', () => {
  test('the message list is a named log, so a reply that lands is read out', async ({ page }) => {
    await signedIn(page);
    let msgs = MSGS.slice();
    await page.route('**/api/support/tickets', (r) => r.fulfill(json([TICKET])));
    await page.route('**/api/support/tickets/7', (r) => r.fulfill(json({ ticket: TICKET, messages: msgs })));
    await page.route('**/api/support/tickets/7/reply', (r) => {
      msgs = msgs.concat({ id: 3, author: 'USER', authorName: 'carol', body: 'Thanks', createdAt: Date.now() });
      return r.fulfill(json({ ok: true }));
    });
    await page.goto('/support');
    await page.getByRole('button', { name: /^Open ticket #7/ }).click();
    const log = page.getByRole('log', { name: 'Messages in ticket #7' });
    await expect(log).toContainText('Looking into it');
    const send = page.getByRole('button', { name: 'Send', exact: true });
    await expect(send).toHaveAttribute('aria-disabled', 'true');
    await page.getByRole('textbox', { name: 'Reply to support' }).fill('Thanks');
    await send.click();
    await expect(log).toContainText('Thanks');
  });

  test('CSR and admin ticket threads are named logs too', async ({ page }) => {
    await asAdmin(page);
    await page.route('**/api/csr/check', (r) => r.fulfill(json({ csr: true })));
    await page.route(/\/api\/admin\/tickets(\?.*)?$/, (r) => r.fulfill(json([TICKET])));
    await page.route('**/api/admin/tickets/7', (r) => r.fulfill(json({ ticket: TICKET, messages: MSGS })));
    await page.route('**/api/csr/tickets?**', (r) => r.fulfill(json([TICKET])));
    await page.route('**/api/csr/tickets/7', (r) => r.fulfill(json({ ticket: TICKET, messages: MSGS })));
    await page.goto('/admin?tab=tickets');
    await page.getByRole('tabpanel').getByText('Stuck trade').click();
    await expect(page.getByRole('log', { name: 'Messages in ticket #7' })).toContainText('Looking into it');
    await page.goto('/csr?tab=tickets');
    await page.getByRole('button', { name: /Open ticket #7/ }).click();
    await expect(page.getByRole('log', { name: 'Messages in ticket #7' })).toContainText('Looking into it');
  });
});

