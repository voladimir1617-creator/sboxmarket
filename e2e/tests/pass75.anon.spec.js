// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 75.

const ME = { id: 3, displayName: 'carol', role: 'USER', signedIn: true, sessionEpoch: 0 };
const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });

async function signedIn(page) {
  await page.addInitScript(() => { window.confirm = () => true; });
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json(ME)));
}
async function asAdmin(page) {
  await signedIn(page);
  await page.route('**/api/admin/check', (r) => r.fulfill(json({ admin: true })));
}
const STALL = { seller: { id: 9, displayName: 'dave' }, listings: [], blockedByViewer: true, rating: null };
test('stall Unblock clears blocked banner even if refresh fails', async ({ page }) => {
  await signedIn(page);
  let unblocked = false;
  await page.route('**/api/listings/stall/9', (r) => unblocked ? r.fulfill(json({ error: 'x' }, 500)) : r.fulfill(json(STALL)));
  await page.route('**/api/profile/blocks/9', (r) => { unblocked = true; return r.fulfill(json({ ok: true })); });
  await page.goto('/stall/9');
  await expect(page.getByText("You've blocked this seller")).toBeVisible();
  await page.getByRole('button', { name: 'Unblock', exact: true }).click();
  await expect(page.getByText('Unblocked.').first()).toBeVisible();
  await expect(page.getByText("You've blocked this seller")).toHaveCount(0);
});
test('stall header Block shows the blocked banner even if refresh fails', async ({ page }) => {
  await signedIn(page);
  let blocked = false;
  await page.route('**/api/listings/stall/9', (r) => blocked ? r.fulfill(json({ error: 'x' }, 500)) : r.fulfill(json({ ...STALL, blockedByViewer: false })));
  await page.route('**/api/profile/blocks/9', (r) => { blocked = true; return r.fulfill(json({ ok: true })); });
  await page.goto('/stall/9');
  await page.getByRole('button', { name: 'Block', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Blocked', exact: true })).toBeVisible();
  await expect(page.getByText("You've blocked this seller")).toBeVisible();
});

async function asCsr(page) {
  await signedIn(page);
  await page.route('**/api/csr/check', (r) => r.fulfill(json({ csr: true })));
}
test('CSR queue stats failure offers Retry', async ({ page }) => {
  await asCsr(page);
  let ok = false;
  await page.route('**/api/csr/stats', (r) => ok ? r.fulfill(json({ openTickets: 7, waitingStaff: 2, waitingUser: 1, oldestWaitingAgeMs: 0, creditCap: 50 })) : r.fulfill(json({ error: 'x' }, 500)));
  await page.goto('/csr');
  await expect(page.getByText('Failed to load queue stats')).toBeVisible();
  ok = true;
  await page.getByRole('tabpanel').getByRole('button', { name: 'Retry' }).click({ timeout: 5000 });
  await expect(page.getByText('Open Tickets')).toBeVisible();
});
test('CSR ticket Close double-click posts once', async ({ page }) => {
  await asCsr(page);
  const T = { id: 5, subject: 'Help me', category: 'OTHER', status: 'WAITING_STAFF', userId: 4, username: 'erin', updatedAt: Date.now() };
  await page.route('**/api/csr/tickets?**', (r) => r.fulfill(json([T])));
  await page.route('**/api/csr/tickets/5', (r) => r.fulfill(json({ ticket: T, messages: [] })));
  let posts = 0;
  await page.route('**/api/csr/tickets/5/close', (r) => { posts++; return new Promise(ok => setTimeout(ok, 800)).then(() => r.fulfill(json({ ok: true }))); });
  await page.goto('/csr?tab=tickets');
  await page.getByRole('button', { name: /Open ticket #5/ }).click();
  const close = page.getByRole('button', { name: 'Close', exact: true });
  await close.click();
  await close.click({ force: true, timeout: 2000 }).catch(() => {});
  await page.waitForTimeout(1500);
  expect(posts).toBe(1);
});
test('Admin audit log failure is not shown as "no entries"', async ({ page }) => {
  await asAdmin(page);
  await page.route('**/api/admin/audit?**', (r) => r.fulfill(json({ error: 'boom' }, 500)));
  await page.goto('/admin?tab=audit');
  await expect(page.getByRole('combobox', { name: 'Filter by audit event' })).toBeVisible();
  await expect(page.getByRole('alert').filter({ hasText: /load the audit log/ })).toBeVisible();
  await expect(page.getByText('No audit entries match those filters.')).toHaveCount(0);
});
test('Admin ticket Close Ticket double-click posts once', async ({ page }) => {
  await asAdmin(page);
  const T = { id: 6, subject: 'Help me', category: 'OTHER', status: 'WAITING_STAFF', userId: 4, username: 'erin', updatedAt: Date.now() };
  await page.route(/\/api\/admin\/tickets(\?.*)?$/, (r) => r.fulfill(json([T])));
  await page.route('**/api/admin/tickets/6', (r) => r.fulfill(json({ ticket: T, messages: [] })));
  let posts = 0;
  await page.route('**/api/admin/tickets/6/close', (r) => { posts++; return new Promise(ok => setTimeout(ok, 800)).then(() => r.fulfill(json({ ok: true }))); });
  await page.goto('/admin?tab=tickets');
  await page.getByRole('tabpanel').getByText('Help me').click();
  const close = page.getByRole('button', { name: 'Close Ticket', exact: true });
  await close.click();
  await close.click({ force: true, timeout: 2000 }).catch(() => {});
  await page.waitForTimeout(1500);
  expect(posts).toBe(1);
});

const ITEM = { id: 11, name: 'Red Beanie', category: 'Hats', rarity: 'Limited', lowestPrice: 2, steamPrice: 3, imageUrl: null };
test('catalogue search failure is not shown as "No items match"', async ({ page }) => {
  await asAdmin(page);
  await page.route('**/api/items?q=**', (r) => r.fulfill(json({ error: 'boom' }, 500)));
  await page.goto('/admin?tab=catalogue');
  await page.getByPlaceholder('Search items by name…').fill('beanie');
  await expect(page.getByRole('alert').filter({ hasText: /search the catalogue/ })).toBeVisible();
  await expect(page.getByText('No items match.')).toHaveCount(0);
});
test('catalogue edit drawer closes on Escape and keeps the admin panel', async ({ page }) => {
  await asAdmin(page);
  await page.route('**/api/items?q=**', (r) => r.fulfill(json([ITEM])));
  await page.goto('/admin?tab=catalogue');
  await page.getByPlaceholder('Search items by name…').fill('beanie');
  await page.getByRole('button', { name: '✎ Edit' }).click();
  await expect(page.getByText('✎ Edit Red Beanie')).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.getByText('✎ Edit Red Beanie')).toHaveCount(0);
  await expect(page.getByPlaceholder('Search items by name…')).toBeVisible();
  await expect(page).toHaveURL(/\/admin\?tab=catalogue$/);
});

test('email category mutes load failure is reported, not hidden', async ({ page }) => {
  await signedIn(page);
  await page.route('**/api/profile/me', (r) => r.fulfill(json({ user: { ...ME, emailNotificationsEnabled: true } })));
  let ok = false;
  await page.route('**/api/profile/email-mutes', (r) => ok
    ? r.fulfill(json({ available: ['TRADES', 'WATCHLIST'], muted: [] }))
    : r.fulfill(json({ error: 'x' }, 500)));
  await page.goto('/profile/personal');
  await expect(page.getByRole('switch', { name: 'Email notifications' })).toBeVisible();
  const alert = page.getByRole('alert').filter({ hasText: 'Email categories failed to load' });
  await expect(alert).toBeVisible();
  ok = true;
  await alert.getByRole('button', { name: 'Retry' }).click();
  await expect(page.getByText('Trade activity')).toBeVisible();
});
