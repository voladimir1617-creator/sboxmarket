// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 77.

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


test('admin simulator says when the simulated-listing count failed to load, not "0 simulated"', async ({ page }) => {
  await asAdmin(page);
  let fail = true;
  await page.route('**/api/admin/simulate/count', (r) => fail ? r.fulfill(json({ error: 'boom' }, 500)) : r.fulfill(json({ count: 7 })));
  await page.goto('/admin?tab=simulator');
  await expect(page.getByText('Marketplace Simulator')).toBeVisible();
  const alert = page.getByRole('alert').filter({ hasText: /couldn.t load the simulated-listing count/i });
  await expect(alert).toBeVisible();
  await expect(page.getByText(/^0 simulated listings currently live/)).toHaveCount(0);
  fail = false;
  await alert.getByRole('button', { name: 'Retry' }).click();
  await expect(page.getByText(/^7 simulated listings currently live/)).toBeVisible();
  await expect(page.getByRole('button', { name: 'Clear 7 simulated' })).toBeEnabled();
});

test('admin API key lookup says when the lookup failed, not "No matching API keys"', async ({ page }) => {
  await asAdmin(page);
  let fail = true;
  await page.route(/\/api\/admin\/api-keys\/lookup/, (r) => fail ? r.fulfill(json({ error: 'boom' }, 500))
    : r.fulfill(json([{ id: 4, userId: 12, label: 'bot', scope: 'RO', publicPrefix: 'sbx_live_abc12', revoked: false }])));
  await page.goto('/admin?tab=fraud');
  await page.getByPlaceholder('sbx_live_…').fill('sbx_live_abc');
  await page.getByRole('button', { name: 'Lookup' }).click();
  const alert = page.getByRole('alert').filter({ hasText: /lookup failed/i });
  await expect(alert).toBeVisible();
  await expect(page.getByText(/No matching API keys/)).toHaveCount(0);
  fail = false;
  await alert.getByRole('button', { name: 'Retry' }).click();
  await expect(page.getByRole('button', { name: 'user #12' })).toBeVisible();
});

test('admin health keeps the dashboard and the SMTP test address when one poll fails', async ({ page }) => {
  await asAdmin(page);
  let fail = false;
  const HEALTH = { uptimeMs: 7.2e6, startedAt: Date.now() - 7.2e6, memory: { heapUsedMb: 100, heapMaxMb: 400, processorCount: 4 }, threads: { live: 30, peak: 40, daemon: 20 }, systemLoad: 1, db: { schemaVersion: 120 } };
  await page.route('**/api/admin/health', (r) => fail ? r.fulfill(json({ error: 'boom' }, 500)) : r.fulfill(json(HEALTH)));
  await page.goto('/admin?tab=health');
  await expect(page.getByText('Uptime')).toBeVisible();
  await page.getByPlaceholder('ops@example.com').fill('ops@skinbox.test');
  fail = true;
  await page.waitForTimeout(6000);
  await expect(page.getByRole('alert').filter({ hasText: /couldn.t refresh server health/i })).toBeVisible();
  await expect(page.getByText('Uptime')).toBeVisible();
  await expect(page.getByPlaceholder('ops@example.com')).toHaveValue('ops@skinbox.test');
});

test('CSR lookup shows the latest search, not a slower earlier one', async ({ page }) => {
  await signedIn(page);
  await page.route('**/api/csr/check', (r) => r.fulfill(json({ csr: true })));
  const user = (id, name) => ({ id, displayName: name, steamId64: '7656' + id, role: 'USER', balance: 0, recentTx: [] });
  await page.route(/\/api\/csr\/users\/lookup/, (r) => {
    const q = new URL(r.request().url()).searchParams.get('q');
    return q === 'alice' ? slow({ matches: [user(1, 'alice')] }, 1500)(r) : r.fulfill(json({ matches: [user(2, 'bob')] }));
  });
  await page.goto('/csr?tab=lookup');
  const box = page.getByRole('textbox', { name: /look up a user/i });
  await box.fill('alice'); await box.press('Enter');
  await box.fill('bob'); await box.press('Enter');
  await expect(page.getByText('bob', { exact: true })).toBeVisible();
  await page.waitForTimeout(2000);
  await expect(page.getByText('bob', { exact: true })).toBeVisible();
  await expect(page.getByText('alice', { exact: true })).toHaveCount(0);
});


test('user menu: arrow keys move between its items', async ({ page }) => {
  await signedIn(page);
  await page.goto('/market');
  const chip = page.getByRole('button', { name: /user menu/i });
  await chip.focus();
  await page.keyboard.press('Enter');
  const menu = page.locator('#user-menu-panel');
  await expect(menu).toBeVisible();
  await page.keyboard.press('ArrowDown');
  await expect(menu.getByRole('menuitem', { name: 'Profile' })).toBeFocused();
  await page.keyboard.press('ArrowDown');
  await expect(menu.getByRole('menuitem', { name: 'Deposit' })).toBeFocused();
  await page.keyboard.press('ArrowUp');
  await expect(menu.getByRole('menuitem', { name: 'Profile' })).toBeFocused();
});

test('sort picker: arrow keys move between sort options', async ({ page }) => {
  await signedIn(page);
  await page.goto('/market');
  const chip = page.getByRole('button', { name: 'Sort listings' });
  await chip.focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('listbox')).toBeVisible();
  await page.keyboard.press('ArrowDown');
  await expect(page.getByRole('option', { name: /Price: High to Low/ })).toBeFocused();
  await page.keyboard.press('ArrowDown');
  await expect(page.getByRole('option', { name: /Price: Low to High/ })).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(chip).toContainText('Price: Low to High');
});

test('pending-trade reminder "Open trade" shows that trade even with a saved Selling filter', async ({ page }) => {
  await signedIn(page);
  await page.addInitScript(() => { try { localStorage.setItem('sb_trade_role', 'selling'); } catch (_) {} });
  const T = { id: 41, state: 'PENDING_BUYER_CONFIRM', itemName: 'Red Beanie', itemId: 11, price: 5, buyerUserId: 3, sellerUserId: 9, counterpartyName: 'dave', createdAt: Date.now() - 5 * 3600e3, updatedAt: Date.now() - 4 * 3600e3 };
  await page.route('**/api/trades', (r) => r.fulfill(json([T])));
  await page.route('**/api/profile/me', (r) => r.fulfill(json({ user: { ...ME }, accountStanding: { level: 'GOOD' } })));
  await page.goto('/market');
  const nudge = page.locator('.pending-trade-nudge');
  await expect(nudge).toContainText('Confirm your trade');
  await nudge.getByRole('link', { name: 'Open trade' }).click();
  await expect(page).toHaveURL(/\/profile\/trades/);
  await expect(page.locator('#trade-41')).toBeVisible();
  await expect(page.getByText('No trades match this filter.')).toHaveCount(0);
});

const T = { id: 41, state: 'PENDING_SELLER_SEND', itemName: 'Red Beanie', itemId: 11, price: 5, buyerUserId: 3, sellerUserId: 9, counterpartyName: 'dave', createdAt: Date.now() - 3600e3, updatedAt: Date.now() - 600e3 };
const MSGS = Array.from({ length: 20 }, (_, i) => ({ id: i + 1, tradeId: 41, senderUserId: i % 2 ? 3 : 9, body: 'message number ' + (i + 1), createdAt: Date.now() - (20 - i) * 60e3 }));
test('trade chat opens on the newest message and keeps a sent reply in view', async ({ page }) => {
  await signedIn(page);
  let msgs = MSGS.slice();
  await page.route('**/api/trades', (r) => r.fulfill(json([T])));
  await page.route('**/api/profile/me', (r) => r.fulfill(json({ user: { ...ME }, accountStanding: { level: 'GOOD' } })));
  await page.route('**/api/trades/41/messages', (r) => {
    if (r.request().method() === 'POST') { msgs = msgs.concat([{ id: 99, tradeId: 41, senderUserId: 3, body: 'my fresh reply', createdAt: Date.now() }]); return r.fulfill(json(msgs[msgs.length - 1])); }
    return r.fulfill(json(msgs));
  });
  await page.goto('/profile/trades');
  await page.locator('#trade-41').getByRole('button', { name: /Chat/ }).click();
  const box = page.getByRole('textbox', { name: 'Message to your trade partner' });
  // The chat opens below the fold on a desktop window; bring it on screen.
  await box.scrollIntoViewIfNeeded();
  await expect(page.getByText('message number 20')).toBeInViewport();
  await box.fill('my fresh reply');
  await box.press('Enter');
  await expect(page.getByText('my fresh reply')).toBeInViewport();
});
