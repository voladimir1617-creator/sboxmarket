// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 74.

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
const now = Date.now();
const notif = (id, read, kind = 'OFFER_RECEIVED') => ({ id, kind, title: 'Notif ' + id, body: 'b', read, createdAt: now - id * 1000 });

test('notifications: Clear visible read sends one request per click burst', async ({ page }) => {
  await signedIn(page);
  await page.route(/\/api\/notifications(\?.*)?$/, (r) => r.fulfill(json({ items: [notif(1, true), notif(2, true), notif(3, false), notif(4, true, 'TRADE_SENT')], unread: 1 })));
  let posts = 0;
  await page.route('**/api/notifications/delete-batch', async (r) => { posts++; await new Promise(f => setTimeout(f, 600)); r.fulfill(json({ deleted: 2 })); });
  await page.goto('/notifications');
  await page.getByRole('button', { name: /^Offers · / }).click({ timeout: 20_000 });
  await page.getByRole('button', { name: 'Clear visible read (2)' }).dblclick();
  await page.waitForTimeout(1200);
  expect(posts).toBe(1);
});

test('admin: unknown ?tab= falls back to the dashboard', async ({ page }) => {
  await asAdmin(page);
  await page.route('**/api/admin/stats', (r) => r.fulfill(json({ users: 7 })));
  await page.goto('/admin?tab=bogus');
  await expect(page.getByRole('tab', { name: 'Dashboard' })).toHaveAttribute('aria-selected', 'true', { timeout: 20_000 });
  await expect(page.getByText('Registered Users')).toBeVisible();
});

test('admin: dashboard tile updates the URL like a tab click', async ({ page }) => {
  await asAdmin(page);
  await page.route('**/api/admin/stats', (r) => r.fulfill(json({ users: 7, openTickets: 2 })));
  await page.goto('/admin');
  await page.getByRole('button', { name: /Open Tickets/ }).click({ timeout: 20_000 });
  await expect(page.getByRole('tab', { name: /Tickets/ })).toHaveAttribute('aria-selected', 'true');
  await expect(page).toHaveURL(/\/admin\?tab=tickets$/);
});

test('admin: failed stats load shows an error, not an endless spinner', async ({ page }) => {
  await asAdmin(page);
  await page.route('**/api/admin/stats', (r) => r.fulfill(json({ message: 'boom' }, 500)));
  await page.goto('/admin');
  await expect(page.getByRole('tab', { name: 'Dashboard' })).toBeVisible({ timeout: 20_000 });
  await expect(page.getByRole('alert').filter({ hasText: /stats|dashboard/i })).toBeVisible();
});

test('admin: failed announcements load is not "No announcements yet."', async ({ page }) => {
  await asAdmin(page);
  await page.route('**/api/admin/announcements', (r) => r.fulfill(json({ message: 'boom' }, 500)));
  await page.goto('/admin?tab=announce');
  await expect(page.getByText('Post sitewide banner')).toBeVisible({ timeout: 20_000 });
  await page.waitForTimeout(800);
  await expect(page.getByText('No announcements yet.')).toHaveCount(0);
  await expect(page.getByRole('alert').filter({ hasText: /announcements/i })).toBeVisible();
});

test('admin: Stop banner double-click sends one DELETE', async ({ page }) => {
  await asAdmin(page);
  let active = true, dels = 0;
  await page.route('**/api/admin/announcements', (r) => r.fulfill(json([{ id: 9, message: 'Maintenance tonight', severity: 'INFO', active, createdAt: now }])));
  await page.route('**/api/admin/announcements/9', async (r) => { dels++; await new Promise(f => setTimeout(f, 500)); if (dels > 1) return r.fulfill(json({ message: 'Already inactive' }, 409)); active = false; r.fulfill(json({ ok: true })); });
  await page.goto('/admin?tab=announce');
  await page.getByRole('button', { name: 'Stop' }).dblclick({ timeout: 20_000 });
  await page.waitForTimeout(1500);
  expect(dels).toBe(1);
  await expect(page.getByText(/Could not deactivate banner/)).toHaveCount(0);
});

test('admin: Announce tab form is laid out, not squashed into a 38px strip', async ({ page }) => {
  await asAdmin(page);
  await page.route('**/api/admin/announcements', (r) => r.fulfill(json([])));
  await page.goto('/admin?tab=announce');
  const msg = page.getByPlaceholder(/Message \(3–500 chars\)/);
  await msg.fill('Maintenance at 22:00 UTC', { timeout: 20_000 });
  await expect(msg).toBeInViewport({ ratio: 1 });
  await expect(page.getByRole('button', { name: 'Post banner' })).toBeInViewport({ ratio: 1 });
  await expect(page.getByRole('button', { name: '📢 Send broadcast' })).toBeInViewport({ ratio: 1 });
});

test('database: Back from an item returns to the same page and category', async ({ page }) => {
  await signedIn(page);
  await page.route('**/api/database?*', (r) => {
    const u = new URL(r.request().url()); const off = +u.searchParams.get('offset') || 0;
    const cat = u.searchParams.get('category') || 'All';
    const items = Array.from({ length: 30 }, (_, i) => ({ id: 100 + off + i, name: `${cat} Item ${off + i + 1}`, category: 'Hats', rarity: 'Standard', supply: 1, totalSold: 0, lowestPrice: 2 }));
    r.fulfill(json({ items, total: 90, indexed: 90 }));
  });
  await page.goto('/db');
  await page.getByLabel('Filter catalogue by category').selectOption('Hats', { timeout: 20_000 });
  await page.getByRole('button', { name: 'Next →' }).click();
  await expect(page.getByText('2 of 3')).toBeVisible();
  await page.getByRole('link', { name: /^Open Hats Item 31 detail/ }).click();
  await expect(page).toHaveURL(/\/item\/130/);
  await page.goBack();
  await expect(page.getByLabel('Filter catalogue by category')).toHaveValue('Hats');
  await expect(page.getByText('2 of 3')).toBeVisible();
});
