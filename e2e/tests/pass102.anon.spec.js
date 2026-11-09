// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 102.

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


// Defect pass 102.

const focusedTag = (page) => page.evaluate(() => document.activeElement && document.activeElement.tagName);

test.describe('pass 102 · banner dismiss focus', () => {
  test('dismissing the sitewide announcement moves focus to the top nav, not the page body', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/announcement*', (r) => r.fulfill(json({ announcement: { id: 5, message: 'Maintenance tonight', severity: 'INFO' } })));
    await page.goto('/market');
    const close = page.getByRole('button', { name: 'Dismiss announcement' });
    await close.focus({ timeout: 20_000 });
    await page.keyboard.press('Enter');
    await expect(page.getByText(/Maintenance tonight/)).toHaveCount(0);
    await expect(page.locator('.nav-logo')).toBeFocused();
  });

  test('dismissing the email reminder hands focus to the next banner when one follows', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/auth/steam/me', (r) => r.fulfill(json({ ...ME, email: 'c@x.io', emailVerified: false })));
    const T = { id: 41, sellerUserId: 3, buyerUserId: 8, state: 'PENDING_SELLER_ACCEPT', itemName: 'Red Cap', createdAt: Date.now() - 5 * 3600_000, updatedAt: Date.now() - 5 * 3600_000 };
    await page.route('**/api/trades', (r) => r.fulfill(json([T])));
    await page.goto('/market');
    const close = page.getByRole('button', { name: 'Dismiss email verification reminder' });
    await close.focus({ timeout: 20_000 });
    await page.keyboard.press('Enter');
    await expect(close).toHaveCount(0);
    await expect(page.getByRole('link', { name: 'Open trade' })).toBeFocused();
  });

  test('dismissing the last pending-trade reminder moves focus to the top nav', async ({ page }) => {
    await signedIn(page);
    const T = { id: 41, sellerUserId: 3, buyerUserId: 8, state: 'PENDING_SELLER_ACCEPT', itemName: 'Red Cap', createdAt: Date.now() - 5 * 3600_000, updatedAt: Date.now() - 5 * 3600_000 };
    await page.route('**/api/trades', (r) => r.fulfill(json([T])));
    await page.goto('/market');
    const close = page.getByRole('button', { name: 'Dismiss reminder' });
    await close.focus({ timeout: 20_000 });
    await page.keyboard.press('Enter');
    await expect(close).toHaveCount(0);
    await expect(page.locator('.nav-logo')).toBeFocused();
  });

  test.describe('phone', () => {
    test.use({ viewport: { width: 390, height: 844 } });
    test('on a phone, dismissing the announcement keeps focus on the page header', async ({ page }) => {
      await signedIn(page);
      await page.route('**/api/announcement*', (r) => r.fulfill(json({ announcement: { id: 6, message: 'Maintenance tonight', severity: 'WARN' } })));
      await page.goto('/market');
      const close = page.getByRole('button', { name: 'Dismiss announcement' });
      await close.focus({ timeout: 20_000 });
      await page.keyboard.press('Enter');
      await expect(page.getByText(/Maintenance tonight/)).toHaveCount(0);
      await expect.poll(() => focusedTag(page)).not.toBe('BODY');
      await expect.poll(() => page.evaluate(() => !!document.activeElement.closest('nav'))).toBe(true);
    });
  });
});

test.describe('pass 102 · notifications page', () => {
  test('Retry shows the loading rows, not "Quiet so far", and focus lands on the All filter', async ({ page }) => {
    await signedIn(page);
    let n = 0;
    await page.route('**/api/notifications*', (r) => { n += 1; return n === 1 ? r.fulfill(json({ error: 'down' }, 500)) : slow({ items: [], unread: 0 }, 1500)(r); });
    await page.goto('/notifications');
    const retry = page.getByRole('button', { name: 'Retry' });
    await retry.focus({ timeout: 20_000 });
    await page.keyboard.press('Enter');
    await expect(page.getByRole('status', { name: 'Loading notifications' })).toBeVisible();
    await expect(page.getByText('Quiet so far')).toHaveCount(0);
    await expect(page.getByText('Quiet so far')).toBeVisible({ timeout: 5000 });
    await expect(page.getByRole('button', { name: /^All/ }).first()).toBeFocused();
  });

  test('a Retry that fails again leaves focus on the new Retry', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/notifications*', (r) => r.fulfill(json({ error: 'down' }, 500)));
    await page.goto('/notifications');
    const retry = page.getByRole('button', { name: 'Retry' });
    await retry.focus({ timeout: 20_000 });
    await page.keyboard.press('Enter');
    await expect(page.getByRole('alert').filter({ hasText: /Couldn.t load notifications/ })).toBeVisible();
    await expect(retry).toBeFocused();
  });

  test('the empty and failed notification states are titled with headings', async ({ page }) => {
    await signedIn(page);
    let fail = true;
    await page.route('**/api/notifications*', (r) => fail ? r.fulfill(json({ error: 'down' }, 500)) : r.fulfill(json({ items: [], unread: 0 })));
    await page.goto('/notifications');
    await expect(page.getByRole('heading', { level: 2, name: /Couldn.t load notifications/ })).toBeVisible({ timeout: 20_000 });
    fail = false;
    await page.getByRole('button', { name: 'Retry' }).click();
    await expect(page.getByRole('heading', { level: 2, name: 'Quiet so far' })).toBeVisible();
  });
});

test.describe('pass 102 · market network error', () => {
  test('market "Couldn.t load listings" Retry keeps keyboard focus on the category strip', async ({ page }) => {
    await signedIn(page);
    let fail = true;
    await page.route('**/api/listings?**', (r) => fail ? r.fulfill(json({ error: 'down' }, 500)) : slow([], 800)(r));
    await page.goto('/market');
    const alert = page.getByRole('alert').filter({ hasText: /Couldn.t load listings/ });
    await expect(alert).toBeVisible({ timeout: 20_000 });
    fail = false;
    await alert.getByRole('button', { name: 'Retry' }).focus();
    await page.keyboard.press('Enter');
    await expect(alert).toHaveCount(0);
    await expect(page.locator('.csfloat-subnav-tab').first()).toBeFocused();
  });
});

test.describe('pass 102 · seller stall blocked view', () => {
  test('Unblock in the "You.ve blocked this seller" banner hands focus to the header Block button', async ({ page }) => {
    await signedIn(page);
    let blocked = true;
    await page.route('**/api/listings/stall/9**', (r) => r.request().url().includes('recent-sales')
      ? r.fulfill(json([]))
      : r.fulfill(json({ seller: { id: 9, displayName: 'dave' }, listings: [], count: 0, blockedByViewer: blocked })));
    await page.route('**/api/profile/blocks/9', (r) => { blocked = false; return r.fulfill(json({ ok: true })); });
    await page.goto('/stall/9');
    const unblock = page.getByRole('button', { name: 'Unblock', exact: true });
    await unblock.focus({ timeout: 20_000 });
    await page.keyboard.press('Enter');
    await expect(page.getByText(/You.ve blocked this seller/)).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Block', exact: true })).toBeFocused();
  });
});

test.describe('pass 102 · profile blocked and following lists', () => {
  test('unblocking the only blocked user moves focus to the next Personal Info control', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/profile/blocks?**', (r) => r.fulfill(json({ items: [{ blockedUserId: 9, displayName: 'dave' }] })));
    await page.route('**/api/profile/blocks/9', (r) => r.fulfill(json({ ok: true })));
    await page.goto('/profile');
    const btn = page.getByRole('button', { name: 'Unblock dave' });
    await btn.scrollIntoViewIfNeeded({ timeout: 20_000 });
    await btn.focus();
    await page.keyboard.press('Enter');
    await expect(btn).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Show last 20 sign-ins' })).toBeFocused();
  });

  test('"Unblock all users" moves focus past the emptied Blocked row', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/profile/blocks?**', (r) => r.fulfill(json({ items: [{ blockedUserId: 9, displayName: 'dave' }, { blockedUserId: 10, displayName: 'eve' }] })));
    await page.route('**/api/profile/blocks', (r) => r.fulfill(json({ removed: 2 })));
    await page.goto('/profile');
    const all = page.getByRole('button', { name: 'Unblock all users' });
    await all.scrollIntoViewIfNeeded({ timeout: 20_000 });
    await all.focus();
    await page.keyboard.press('Enter');
    await expect(all).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Show last 20 sign-ins' })).toBeFocused();
  });

  test('unfollowing the only followed seller moves focus past the emptied Following row', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/follows?**', (r) => r.fulfill(json([{ id: 1, sellerUserId: 9, notificationsMuted: false }])));
    await page.route('**/api/sellers/names**', (r) => r.fulfill(json({ 9: 'dave' })));
    await page.route('**/api/follows/9', (r) => r.fulfill(json({ ok: true })));
    await page.route('**/api/profile/blocks?**', (r) => r.fulfill(json({ items: [] })));
    await page.goto('/profile');
    const btn = page.getByRole('button', { name: 'Unfollow dave' });
    await btn.scrollIntoViewIfNeeded({ timeout: 20_000 });
    await btn.focus();
    await page.keyboard.press('Enter');
    await expect(btn).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Show last 20 sign-ins' })).toBeFocused();
  });
});
