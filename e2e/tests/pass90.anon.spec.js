// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 90.

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

test.describe('defect pass 90 · a', () => {

  test('recent searches Clear all keeps focus in the search box', async ({ page }) => {
    await signedIn(page);
    await page.addInitScript(() => localStorage.setItem('sb_recent_searches', JSON.stringify(['alpha hat', 'beta boots'])));
    await page.goto('/market');
    const box = page.getByRole('combobox', { name: 'Search listings' });
    await expect(box).toBeVisible({ timeout: 20000 });
    await box.focus();
    await page.getByRole('button', { name: /^Clear all$/ }).click();
    await expect(page.locator('#search-recent-listbox')).toHaveCount(0);
    await expect(box).toBeFocused();
  });

  test('forgetting the last recent search keeps focus in the search box', async ({ page }) => {
    await signedIn(page);
    await page.addInitScript(() => localStorage.setItem('sb_recent_searches', JSON.stringify(['alpha hat'])));
    await page.goto('/market');
    const box = page.getByRole('combobox', { name: 'Search listings' });
    await expect(box).toBeVisible({ timeout: 20000 });
    await box.focus();
    await page.getByRole('button', { name: /Forget .alpha hat./ }).click();
    await expect(page.locator('#search-recent-listbox')).toHaveCount(0);
    await expect(box).toBeFocused();
  });

  test('Settings test ding button name contains its visible text', async ({ page }) => {
    await signedIn(page);
    await page.goto('/settings');
    await expect(page.getByRole('button', { name: /Send test notification/ })).toBeVisible({ timeout: 20000 });
  });
});

test.describe('defect pass 90 · b', () => {

  test('Sync Steam keeps keyboard focus while it syncs', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/steam/inventory', (r) => r.fulfill(json({ items: [], count: 0 })));
    await page.route('**/api/steam/sync', slow({ ok: true, inventorySize: 0 }, 1500));
    await page.goto('/sell');
    const btn = page.getByRole('button', { name: /^Sync Steam$/ });
    await expect(btn).toBeVisible({ timeout: 20000 });
    await btn.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('button', { name: /Syncing/ }).first()).toBeFocused();
  });

  test('admin SMTP test: the address box has a name and a failed send is announced', async ({ page }) => {
    await asAdmin(page);
    await page.route('**/api/admin/health', (r) => r.fulfill(json({ status: 'UP', priceSync: {} })));
    await page.route('**/api/admin/test-email', (r) => r.fulfill(json({ message: 'SMTP auth failed' }, 500)));
    await page.goto('/admin?tab=health');
    const box = page.getByRole('textbox', { name: /test email|SMTP test/i });
    await expect(box).toBeVisible({ timeout: 20000 });
    await box.fill('ops@example.com');
    await page.getByRole('button', { name: 'Send test' }).click();
    await expect(page.getByRole('alert').filter({ hasText: /SMTP auth failed/ })).toBeVisible();
  });
});

test.describe('defect pass 90 · c', () => {

  test('Personal tab Sync Now keeps keyboard focus while it syncs', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/profile/me', (r) => r.fulfill(json({ user: { ...ME, steamId64: '76561190000000003' } })));
    await page.route('**/api/steam/sync', slow({ ok: true }, 1500));
    await page.goto('/profile/personal');
    const btn = page.getByRole('button', { name: 'Sync Now' });
    await expect(btn).toBeVisible({ timeout: 20000 });
    await btn.scrollIntoViewIfNeeded();
    await btn.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('button', { name: /Syncing/ })).toBeFocused();
  });

  test('email reminder Resend email keeps keyboard focus while it sends', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/auth/steam/me', (r) => r.fulfill(json({ ...ME, email: 'carol@example.com', emailVerified: false })));
    await page.route('**/api/profile/email/resend', slow({ ok: true }, 1500));
    await page.goto('/market');
    const btn = page.getByRole('button', { name: 'Resend email' });
    await expect(btn).toBeVisible({ timeout: 20000 });
    await btn.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('button', { name: /Sending/ })).toBeFocused();
    await expect(page.getByRole('button', { name: 'Sent' })).toBeFocused();
  });

  test('the notification bell announces a failed load', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/notifications/unread-count*', (r) => r.fulfill(json({ unread: 3 })));
    await page.route('**/api/notifications?*', (r) => r.fulfill(json({ error: 'boom' }, 500)));
    await page.goto('/market');
    await page.getByRole('button', { name: /^Notifications/ }).first().click({ timeout: 20000 });
    await expect(page.getByRole('alert').filter({ hasText: /Couldn.t load notifications/ })).toBeVisible();
  });
});

test.describe('defect pass 90 · d', () => {

  test.beforeEach(async ({ page }) => {
    await asAdmin(page);
    await page.route('**/api/admin/health', (r) => r.fulfill(json({ status: 'UP', priceSync: {} })));
  });

  test('admin health Run sync now keeps keyboard focus while it starts', async ({ page }) => {
    await page.route('**/api/admin/sync-prices', slow({ ok: true }, 1500));
    await page.goto('/admin?tab=health');
    const btn = page.getByRole('button', { name: 'Run sync now' });
    await expect(btn).toBeVisible({ timeout: 20000 });
    await btn.scrollIntoViewIfNeeded();
    await btn.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('button', { name: /Starting/ })).toBeFocused();
    await expect(page.getByRole('button', { name: 'Run sync now' })).toBeFocused();
  });

  test('admin SMTP Send test keeps keyboard focus while it sends', async ({ page }) => {
    await page.route('**/api/admin/test-email', slow({ to: 'ops@example.com' }, 1500));
    await page.goto('/admin?tab=health');
    await page.getByPlaceholder('ops@example.com').fill('ops@example.com', { timeout: 20000 });
    const btn = page.getByRole('button', { name: 'Send test' });
    await btn.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('button', { name: /Sending/ })).toBeFocused();
    await expect(page.getByRole('button', { name: 'Send test' })).toBeFocused();
  });
});
