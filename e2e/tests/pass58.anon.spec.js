// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 58.

const ME = {
  id: 1,
  steamId64: '76561199000000001',
  displayName: 'Pass Tester',
  signedIn: true,
  sessionEpoch: 0,
  tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc',
};

const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });
const slow = (body, ms = 400) => (r) => new Promise((ok) => setTimeout(ok, ms)).then(() => r.fulfill(json(body)));
const doubleClick = (btn) => btn.evaluate((b) => { b.click(); b.click(); });

async function signedIn(page) {
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json(ME)));
}
async function anon(page) {
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json({ signedIn: false })));
}
async function bell(page, unread) {
  await page.route('**/api/notifications*', (r) => r.fulfill(json({
    items: [{ id: 1, title: 'Hi', message: 'Hello', read: true, kind: 'SYSTEM', type: 'SYSTEM', createdAt: Date.now() }],
    unread,
  })));
  await page.route('**/api/notifications/unread-count', (r) => r.fulfill(json({ unread })));
}
async function unverifiedProfile(page) {
  await signedIn(page);
  await page.route('**/api/profile/me', (r) => r.fulfill(json({ user: { id: 1, email: 'a@example.com', emailVerified: false } })));
}

test.describe('defect pass 58', () => {
  test('a fresh market card says "Listed just now", not "Listed Just now"', async ({ page }) => {
    await anon(page);
    await page.route('**/api/listings?*', (r) => r.fulfill(json([
      { id: 1, price: 5, listingType: 'BUY_NOW', status: 'ACTIVE', sellerName: 's', listedAt: Date.now(),
        item: { id: 11, name: 'Fresh Hat', category: 'Hats', rarity: 'Standard', lowestPrice: 5 } },
    ])));
    await page.goto('/market');
    await expect(page.locator('.gc-card-age').first()).toHaveText('Listed just now', { timeout: 20_000 });
  });

  test('the footer says "Catalog updated just now"', async ({ page }) => {
    await anon(page);
    await page.route('**/api/items/stats', (r) => r.fulfill(json({ lastSyncedAt: Date.now() })));
    await page.goto('/market');
    await expect(page.locator('.footer-sync-badge')).toContainText('Catalog updated just now', { timeout: 20_000 });
  });

  test('double-clicking the bell Mark all read sends one request', async ({ page }) => {
    await signedIn(page);
    await bell(page, 3);
    let posts = 0;
    await page.route('**/api/notifications/read-all', (r) => { posts += 1; return slow({})(r); });
    await page.goto('/market');
    await page.getByRole('button', { name: 'Notifications (3 unread)' }).click({ timeout: 20_000 });
    await doubleClick(page.locator('.notif-dropdown button.notif-clear'));
    await page.waitForTimeout(800);
    expect(posts).toBe(1);
  });

  test('the bell Mark all read button is named in the singular for one unread', async ({ page }) => {
    await signedIn(page);
    await bell(page, 1);
    await page.goto('/market');
    await page.getByRole('button', { name: 'Notifications (1 unread)' }).click({ timeout: 20_000 });
    await expect(page.locator('.notif-dropdown button.notif-clear')).toHaveAttribute('aria-label', 'Mark 1 unread notification read');
  });

  test('double-clicking Resend verification email sends one email', async ({ page }) => {
    await unverifiedProfile(page);
    let sends = 0;
    await page.route('**/api/profile/email/resend', (r) => { sends += 1; return slow({ ok: true })(r); });
    await page.goto('/profile');
    const btn = page.getByRole('button', { name: '↻ Resend verification email' });
    await btn.waitFor({ timeout: 20_000 });
    await doubleClick(btn);
    await page.waitForTimeout(800);
    expect(sends).toBe(1);
  });

  test('double-clicking Verify keeps "Email verified" instead of an invalid-code error', async ({ page }) => {
    await unverifiedProfile(page);
    let verifies = 0;
    await page.route('**/api/profile/email/verify', (r) => {
      verifies += 1;
      return verifies === 1 ? slow({ ok: true })(r) : r.fulfill(json({ error: 'Invalid or expired code' }, 400));
    });
    await page.goto('/profile');
    await page.getByLabel('Email verification code').fill('123456', { timeout: 20_000 });
    await doubleClick(page.getByRole('button', { name: 'Verify', exact: true }));
    await page.waitForTimeout(800);
    expect(verifies).toBe(1);
    await expect(page.getByText('Email verified', { exact: true })).toBeVisible();
  });

  test('the security hints point at rows that really sit below them', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/profile/security-activity*', (r) => r.fulfill(json([{ id: 1, eventType: 'API_KEY_MINTED', createdAt: Date.now(), summary: 'x' }])));
    await page.route('**/api/profile/sign-in-history*', (r) => r.fulfill(json([{ id: 1, createdAt: Date.now(), ipAddress: '1.2.3.4', userAgent: 'Chrome/1' }])));
    await page.goto('/profile');
    await page.getByRole('button', { name: /Show recent security events/ }).click({ timeout: 20_000 });
    await expect(page.getByText(/Sign out everywhere below, then/)).toBeVisible();
    await page.getByRole('button', { name: /Show last 20 sign-ins/ }).click();
    await expect(page.getByText(/reset 2FA from the Two-Factor Auth row below/)).toBeVisible();
  });

  test('clearing a one-item watchlist asks in the singular', async ({ page }) => {
    await anon(page);
    await page.addInitScript(() => localStorage.setItem('sb_watchlist', JSON.stringify([999301])));
    const item = { id: 999301, name: 'Solo Hat', lowestPrice: 5, rarity: 'Standard', category: 'Hats', isListed: true };
    await page.route('**/api/items/batch**', (r) => r.fulfill(json([item])));
    await page.route('**/api/listings?**', (r) => r.fulfill(json([{ id: 71, price: 5, listingType: 'BUY_NOW', status: 'ACTIVE', sellerName: 's', item }])));
    await page.goto('/watchlist');
    await expect(page.getByText('Solo Hat').first()).toBeVisible({ timeout: 20_000 });
    let msg = '';
    page.once('dialog', (d) => { msg = d.message(); d.dismiss(); });
    await page.getByRole('button', { name: '✕ Clear all' }).click();
    await expect.poll(() => msg).toBe('Clear 1 watchlisted item and cancel its price alert?');
  });

  test('the user chip says "1 action needs attention"', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/profile/pending-actions', (r) => r.fulfill(json({ total: 1 })));
    await page.goto('/market');
    await expect(page.locator('.user-chip')).toHaveAttribute('aria-label', /1 action needs attention/, { timeout: 20_000 });
  });

  test('the My Stall watcher chip says "1 buyer has starred"', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings/my-stall', (r) => r.fulfill(json([
      { id: 9101, price: 5, listingType: 'BUY_NOW', status: 'ACTIVE', item: { id: 9102, name: 'Watched Hat', category: 'Hats', rarity: 'Standard' } },
    ])));
    await page.route('**/api/watchlist/counts?*', (r) => r.fulfill(json({ 9102: 1 })));
    await page.goto('/me/stall');
    await expect(page.locator('[title^="1 buyer"]').first()).toHaveAttribute('title', /^1 buyer has starred this item/, { timeout: 20_000 });
  });

  test('double-clicking Delete on a notification sends one request', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/notifications*', (r) => r.fulfill(json({
      items: [{ id: 5, title: 'Old', message: 'Old one', read: true, kind: 'SYSTEM', type: 'SYSTEM', createdAt: Date.now() }], unread: 0,
    })));
    let deletes = 0;
    await page.route('**/api/notifications/5', (r) => {
      if (r.request().method() === 'DELETE') deletes += 1;
      return slow({})(r);
    });
    await page.goto('/notifications');
    const btn = page.getByRole('button', { name: 'Delete this notification' });
    await btn.waitFor({ timeout: 20_000 });
    await doubleClick(btn);
    await page.waitForTimeout(800);
    expect(deletes).toBe(1);
  });
});
