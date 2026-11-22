// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 155.

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


// Defect pass 155: Notifications search/filter narrows the list with no
// count read out; with every loaded notification a muted type the page says
// "Quiet so far" (nothing has ever happened); a Personal Info email error
// stays up after the email editor is cancelled and marks the verification
// code box invalid.

const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';

async function consent(page) {
  await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
}

// Every write a real-jar test could reach is refused, so nothing on the
// server changes.
async function noWrites(page) {
  await page.route('**/api/**', (r) => (r.request().method() === 'GET' ? r.fallback() : r.fulfill(json({ error: 'blocked in test' }, 400))));
}

async function devLogin(page, userId) {
  await page.request.get(`${base}/api/auth/steam/dev-login?userId=${userId}&next=/`);
}

const now = Date.now();
const NOTES = [
  { id: 1, kind: 'TRADE_COMPLETED', title: 'Trade complete', body: 'Wizard Hat delivered', read: false, createdAt: now - 60e3 },
  { id: 2, kind: 'TRADE_SENT', title: 'Trade sent', body: 'Seller sent Wizard Gown', read: true, createdAt: now - 120e3 },
  { id: 3, kind: 'OFFER_RECEIVED', title: 'New offer', body: 'Someone offered $4.00', read: false, createdAt: now - 180e3 },
  { id: 4, kind: 'OFFER_REJECTED', title: 'Offer declined', body: 'Your offer was declined', read: true, createdAt: now - 240e3 },
  { id: 5, kind: 'LISTING_MATCH', title: 'New match', body: 'Bandana Mask listed', read: false, createdAt: now - 300e3 },
  { id: 6, kind: 'REVIEW_RECEIVED', title: 'New 5 star review', body: 'From carol', read: true, createdAt: now - 360e3 },
  { id: 7, kind: 'SELLER_FOLLOWED', title: 'New follower', body: 'dave follows you', read: true, createdAt: now - 420e3 },
];
// GET only: the page reads the feed; every write is refused by noWrites.
const stubNotes = (page, items = NOTES) => page.route('**/api/notifications', (r) => (r.request().method() === 'GET'
  ? r.fulfill(json({ items, unread: items.filter((n) => !n.read).length }))
  : r.fallback()));

test.describe('pass155k · Notifications page filters (real jar)', () => {
  test('a search reads out how many notifications are left', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await devLogin(page, 2);
    await stubNotes(page);
    await page.goto(`${base}/notifications`);
    const box = page.getByRole('textbox', { name: 'Search notifications' });
    await expect(box).toBeVisible({ timeout: 20_000 });
    await box.fill('trade');
    await expect(page.getByRole('button', { name: /Open notification: Trade complete/ })).toBeVisible();
    await expect(page.getByRole('status').filter({ hasText: /^2 notifications shown$/ })).toBeAttached();
  });

  test('a type filter and the Unread filter read out the narrowed count', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await devLogin(page, 2);
    await stubNotes(page);
    await page.goto(`${base}/notifications`);
    await page.getByRole('group', { name: 'Notification type' }).getByRole('button', { name: /^Offers · 2$/ }).click({ timeout: 20_000 });
    await expect(page.getByRole('status').filter({ hasText: /^2 notifications shown$/ })).toBeAttached();
    await page.getByRole('group', { name: 'Read status' }).getByRole('button', { name: /^Unread/ }).click();
    await expect(page.getByRole('status').filter({ hasText: /^1 notification shown$/ })).toBeAttached();
  });

  test('with every notification a muted type the page does not say "Quiet so far"', async ({ page }) => {
    await consent(page);
    await page.addInitScript(() => { try { localStorage.setItem('sb_mute_kinds', JSON.stringify(['TRADES'])); } catch (_) {} });
    await noWrites(page);
    await devLogin(page, 2);
    await stubNotes(page, NOTES.slice(0, 2));
    await page.goto(`${base}/notifications`);
    await expect(page.getByText(/1 notification type muted/)).toBeVisible({ timeout: 20_000 });
    // Loaded: the read-status counts show once the feed is in.
    await expect(page.getByRole('group', { name: 'Read status' }).getByRole('button', { name: /^All 0$/ })).toBeVisible();
    await expect(page.getByRole('heading', { level: 2, name: /Quiet so far/ })).toHaveCount(0);
    await expect(page.getByRole('heading', { level: 2, name: /Only muted notifications/ })).toBeVisible();
  });
});

test.describe('pass155k · Personal Info email editor (real jar)', () => {
  test('cancelling the email editor clears its error', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await devLogin(page, 2);
    await page.goto(`${base}/profile/personal`);
    await page.getByRole('button', { name: 'Edit email address' }).click({ timeout: 20_000 });
    const box = page.getByRole('textbox', { name: 'Email address' });
    await box.fill('');
    await box.press('Enter');
    await expect(page.getByRole('alert').filter({ hasText: /Enter your email address/ })).toBeVisible();
    await box.press('Escape');
    await expect(page.getByRole('button', { name: 'Edit email address' })).toBeFocused();
    await expect(page.getByText(/Enter your email address, then press Save/)).toHaveCount(0);
  });

  test('the ✕ button also clears the error', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await devLogin(page, 2);
    await page.goto(`${base}/profile/personal`);
    await page.getByRole('button', { name: 'Edit email address' }).click({ timeout: 20_000 });
    const box = page.getByRole('textbox', { name: 'Email address' });
    await box.fill('not-an-email');
    await page.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(page.getByRole('alert').filter({ hasText: /look like a valid email/ })).toBeVisible();
    await page.getByRole('button', { name: 'Cancel email edit' }).click();
    await expect(page.getByText(/look like a valid email/)).toHaveCount(0);
  });
});

test.describe('pass155k · Personal Info verification code (stubbed)', () => {
  const PROFILE = { user: { ...ME, email: 'carol@example.com', emailVerified: false, tradeUrl: ME.tradeUrl, emailNotificationsEnabled: true }, twoFactorEnabled: false };

  test('an email-editor error does not mark the verification code box invalid once the editor is closed', async ({ page }) => {
    await consent(page);
    await signedIn(page);
    await page.route('**/api/profile/me', (r) => r.fulfill(json(PROFILE)));
    await page.goto('/profile/personal');
    const code = page.getByRole('textbox', { name: 'Email verification code' });
    await expect(code).toBeVisible({ timeout: 20_000 });
    await page.getByRole('button', { name: 'Edit email address' }).click();
    const box = page.getByRole('textbox', { name: 'Email address' });
    await box.fill('');
    await box.press('Enter');
    await expect(box).toHaveAttribute('aria-invalid', 'true');
    await box.press('Escape');
    await expect(code).toBeVisible();
    await expect(code).not.toHaveAttribute('aria-invalid', 'true');
    await expect(code).not.toHaveAttribute('aria-describedby', /profile-email-error/);
  });

  test('a verify-code error does not follow the user into the email editor', async ({ page }) => {
    await consent(page);
    await signedIn(page);
    await page.route('**/api/profile/me', (r) => r.fulfill(json(PROFILE)));
    await page.goto('/profile/personal');
    const code = page.getByRole('textbox', { name: 'Email verification code' });
    await expect(code).toBeVisible({ timeout: 20_000 });
    await page.getByRole('button', { name: 'Verify', exact: true }).click();
    await expect(code).toHaveAttribute('aria-invalid', 'true');
    await page.getByRole('button', { name: 'Edit email address' }).click();
    const box = page.getByRole('textbox', { name: 'Email address' });
    await expect(box).toBeFocused();
    await expect(box).not.toHaveAttribute('aria-invalid', 'true');
    await expect(page.getByText(/Paste the code from your verification email/)).toHaveCount(0);
  });
});
