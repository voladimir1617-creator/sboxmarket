// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 130.

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


// Defect pass 130. Real-jar tests take the server from JAR / E2E_BASE_URL;
// stubbed tests use the Playwright baseURL (the static copy).
const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';

async function consent(page) {
  await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
}
async function devLogin(page, id) {
  const r = await page.request.get(`${base}/api/auth/steam/dev-login?userId=${id}&next=/`);
  expect(r.ok()).toBeTruthy();
}

test.describe('pass130 · Profile > Personal email categories (real jar)', () => {
  test('email categories: each checkbox is named by its category alone, inside a named group', async ({ page }) => {
    await consent(page);
    await devLogin(page, 1);
    await page.goto(`${base}/profile/personal`);
    const group = page.getByRole('group', { name: 'Email categories' });
    await group.scrollIntoViewIfNeeded({ timeout: 20_000 });
    const trades = group.getByRole('checkbox', { name: 'Trade activity', exact: true });
    await expect(trades).toBeVisible();
    await expect(trades).toHaveAccessibleDescription(/New sales to send/);
    await expect(group.getByRole('checkbox', { name: 'Auction activity', exact: true })).toBeVisible();
    await expect(group.getByRole('checkbox', { name: 'Followed sellers', exact: true })).toBeVisible();
  });

  test('email categories: muting a category keeps its name (no "· MUTED" in the name)', async ({ page }) => {
    await consent(page);
    await devLogin(page, 1);
    // Only the save is stubbed, so the real account's mutes are left alone.
    await page.route('**/api/profile/email-mutes', (r) => (r.request().method() === 'PUT'
      ? r.fulfill(json({ muted: ['AUCTIONS'] }))
      : r.fallback()));
    await page.goto(`${base}/profile/personal`);
    const box = page.getByRole('checkbox', { name: 'Auction activity', exact: true });
    await box.scrollIntoViewIfNeeded({ timeout: 20_000 });
    await box.focus();
    await page.keyboard.press('Space');
    await expect(page.getByText(/· MUTED/)).toBeVisible();
    await expect(box).not.toBeChecked();
    await expect(box).toBeFocused();
    await expect(page.getByRole('checkbox', { name: 'Auction activity', exact: true })).toHaveCount(1);
  });
});

test.describe('pass130 · Support new-ticket draft (real jar)', () => {
  test('support: a typed ticket survives visiting another Profile tab and coming Back', async ({ page }) => {
    await consent(page);
    await devLogin(page, 2);
    await page.goto(`${base}/support`);
    await page.getByRole('button', { name: /New Ticket/ }).first().click({ timeout: 20_000 });
    await page.getByRole('textbox', { name: 'Ticket subject' }).fill('Pass130 draft subject');
    await page.getByRole('combobox', { name: 'Ticket category' }).selectOption('TRADE');
    await page.getByRole('textbox', { name: 'Ticket message body' }).fill('Pass130 draft body, trade #12');
    await page.getByRole('tab', { name: /^Trades/ }).click();
    await expect(page).toHaveURL(/\/profile\/trades/);
    await page.goBack();
    await expect(page).toHaveURL(/\/support/);
    await expect(page.getByRole('textbox', { name: 'Ticket subject' })).toHaveValue('Pass130 draft subject', { timeout: 15_000 });
    await expect(page.getByRole('combobox', { name: 'Ticket category' })).toHaveValue('TRADE');
    await expect(page.getByRole('textbox', { name: 'Ticket message body' })).toHaveValue('Pass130 draft body, trade #12');
  });

  test('support: a reply typed in a ticket thread survives another tab and Back', async ({ page }) => {
    await consent(page);
    await devLogin(page, 2);
    await page.goto(`${base}/support`);
    await expect(page.getByRole('button', { name: /New Ticket/ }).first()).toBeVisible({ timeout: 20_000 });
    const subject = 'Pass130 reply keep ' + Date.now();
    const created = await page.evaluate(async (subject) => {
      const t = (document.cookie.match(/sbox_csrf=([^;]+)/) || [])[1] || '';
      const r = await fetch('/api/support/tickets', { method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': t },
        body: JSON.stringify({ subject, category: 'OTHER', body: 'First message' }) });
      return r.json();
    }, subject);
    expect(created.id).toBeTruthy();
    await page.reload();
    await page.getByRole('button', { name: new RegExp(`Open ticket #${created.id} `) }).click({ timeout: 20_000 });
    await page.getByRole('textbox', { name: 'Reply to support' }).fill('Pass130 half-typed reply');
    await page.getByRole('tab', { name: /^Trades/ }).click();
    await expect(page).toHaveURL(/\/profile\/trades/);
    await page.goBack();
    await expect(page).toHaveURL(/\/support/);
    await expect(page.getByRole('heading', { name: new RegExp(`#${created.id} · `) })).toBeVisible({ timeout: 15_000 });
    await expect(page.getByRole('textbox', { name: 'Reply to support' })).toHaveValue('Pass130 half-typed reply');
    // Leaving the thread on purpose drops the reply, as Cancel drops a new ticket.
    await page.getByRole('button', { name: '← Tickets' }).click();
    await page.getByRole('button', { name: new RegExp(`Open ticket #${created.id} `) }).click();
    await expect(page.getByRole('textbox', { name: 'Reply to support' })).toHaveValue('');
  });

  test('support: Cancel still throws the draft away', async ({ page }) => {
    await consent(page);
    await devLogin(page, 2);
    await page.goto(`${base}/support`);
    await page.getByRole('button', { name: /New Ticket/ }).first().click({ timeout: 20_000 });
    await page.getByRole('textbox', { name: 'Ticket subject' }).fill('Pass130 cancelled');
    await page.getByRole('button', { name: 'Cancel' }).click();
    await page.getByRole('tab', { name: /^Trades/ }).click();
    await expect(page).toHaveURL(/\/profile\/trades/);
    await page.goBack();
    await expect(page).toHaveURL(/\/support/);
    await expect(page.getByRole('button', { name: /New Ticket/ }).first()).toBeVisible({ timeout: 15_000 });
    await expect(page.getByRole('textbox', { name: 'Ticket subject' })).toHaveCount(0);
  });
});

test.describe('pass130 · Database empty and error states', () => {
  test('database: a search with no match shows a level-2 heading (real jar)', async ({ page }) => {
    await consent(page);
    await page.goto(`${base}/db?q=zzpass130nomatch`);
    await expect(page.getByRole('heading', { level: 2, name: 'No items match your filters' })).toBeVisible({ timeout: 20_000 });
    await expect(page.getByRole('button', { name: 'Clear Filters' })).toBeVisible();
  });

  test('database: the rarity legend buttons are named by their tier alone (real jar)', async ({ page }) => {
    await consent(page);
    await page.goto(`${base}/db`);
    const legend = page.getByRole('group', { name: 'Rarity tier legend' });
    await expect(legend.getByRole('button', { name: 'Scarce', exact: true })).toBeVisible({ timeout: 20_000 });
    await expect(legend.getByRole('button', { name: 'Limited', exact: true })).toHaveAttribute('aria-pressed', 'false');
    await expect(legend.getByRole('button', { name: 'Scarce', exact: true })).toHaveAccessibleDescription(/limited-supply items/);
    await legend.getByRole('button', { name: 'Limited', exact: true }).click();
    await expect(legend.getByRole('button', { name: 'Limited', exact: true })).toHaveAttribute('aria-pressed', 'true');
  });

  test('database: a failed load names its panel with a heading (stubbed)', async ({ page }) => {
    await consent(page);
    await page.route('**/api/**', (r) => r.fulfill(json({})));
    await page.route('**/api/database*', (r) => r.fulfill(json({ error: 'boom' }, 500)));
    await page.goto('/database');
    await expect(page.getByRole('heading', { level: 2, name: /Couldn.t load catalogue/ })).toBeVisible({ timeout: 20_000 });
  });

  test('home: an empty marketplace names its panel with a heading (stubbed)', async ({ page }) => {
    await consent(page);
    await page.route('**/api/**', (r) => r.fulfill(json({})));
    await page.route('**/api/listings*', (r) => r.fulfill(json({ items: [], total: 0 })));
    await page.goto('/');
    await expect(page.getByTestId('home-market-empty')).toBeVisible({ timeout: 20_000 });
    await expect(page.getByRole('heading', { level: 2, name: 'Marketplace is empty' })).toBeVisible();
  });
});
