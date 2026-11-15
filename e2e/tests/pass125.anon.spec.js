// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 125.

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

// Pass 125: on a phone the fixed tab bar (and the item page's Buy bar) hid
// the control that keyboard focus had just moved to; an empty email Save did
// nothing, and an empty trade URL Save removed the URL with no confirm (and
// said "removed" when none was set); a long email pushed its badge and Edit
// button past a phone's edge; three Personal Info buttons were all named
// "Copy"; the item page "More from" and stall name headings read their
// "Visit stall" / "Steam" links as part of the heading.

const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';
const PHONE = { width: 390, height: 844 };
const consent = (page) => page.addInitScript(() => {
  try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {}
});
const devLogin = (page, userId = 1) => page.request.get(`${base}/api/auth/steam/dev-login?userId=${userId}&next=/`);

// Put `target` just below the fold, focus the control before it without
// scrolling, press Tab, and return how far the focused control's bottom edge
// sits below the top of the fixed bar that covers the bottom of the screen.
async function tabIntoView(page, target, barSelector) {
  await target.evaluate((t) => {
    window.scrollTo({ top: window.scrollY + t.getBoundingClientRect().bottom - innerHeight + 10, behavior: 'instant' });
  });
  // Back to top mounts once the page has scrolled; let it settle first.
  await page.waitForTimeout(300);
  await target.evaluate((t) => {
    const all = [...document.querySelectorAll('a[href], button:not([disabled]), input, select, textarea, [tabindex="0"]')]
      .filter((e) => e.offsetParent !== null || getComputedStyle(e).position === 'fixed');
    all[all.indexOf(t) - 1].focus({ preventScroll: true });
  });
  await page.keyboard.press('Tab');
  await expect(target).toBeFocused();
  await page.waitForTimeout(100);
  return page.evaluate((sel) => {
    const a = document.activeElement.getBoundingClientRect();
    const bar = [...document.querySelectorAll(sel)].map((b) => b.getBoundingClientRect()).filter((b) => b.height > 0)
      .reduce((m, b) => Math.min(m, b.top), innerHeight);
    return a.bottom - bar;
  }, barSelector);
}

test.describe('Phone: keyboard focus is not hidden under the fixed bottom bars', () => {
  test('item page Active Listings row buttons clear the Buy bar and tab bar (real app)', async ({ page }) => {
    await page.setViewportSize(PHONE);
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await consent(page);
    await devLogin(page);
    await page.goto(`${base}/item/12`);
    await expect(page.getByRole('heading', { name: /Active Listings/ })).toBeVisible({ timeout: 20_000 });
    const report = page.getByRole('button', { name: /^Report listing \d+$/ });
    const n = await report.count();
    expect(n).toBeGreaterThan(0);
    for (let i = 0; i < n; i++) {
      expect(await tabIntoView(page, report.nth(i), '.item-buy-bar, .csfloat-mobile-nav')).toBeLessThanOrEqual(0);
    }
  });

  test('FAQ page footer links clear the tab bar (real app)', async ({ page }) => {
    await page.setViewportSize(PHONE);
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await consent(page);
    await page.goto(`${base}/faq`);
    const footer = page.getByRole('contentinfo');
    for (const name of ['Browse Market', 'Help Center', 'Settings']) {
      expect(await tabIntoView(page, footer.getByRole('link', { name, exact: true }), '.csfloat-mobile-nav')).toBeLessThanOrEqual(0);
    }
  });
});

test.describe('Profile > Personal Info: an empty Save says what is missing', () => {
  test('email: Save with an empty box shows an alert and keeps focus in the box (real app)', async ({ page }) => {
    await consent(page);
    await devLogin(page, 2);
    await page.goto(`${base}/profile/personal`);
    await page.getByRole('button', { name: 'Edit email address' }).click();
    const box = page.getByRole('textbox', { name: 'Email address' });
    await box.fill('');
    await page.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(page.getByRole('alert').filter({ hasText: /Enter your email address/ })).toBeVisible();
    await expect(box).toBeFocused();
    await expect(box).toHaveAttribute('aria-invalid', 'true');
  });

  test('trade URL: Save with an empty box does not remove anything (real app)', async ({ page }) => {
    await consent(page);
    await devLogin(page, 4);
    let puts = 0;
    page.on('request', (r) => { if (/\/api\/profile\/trade-url/.test(r.url()) && r.method() !== 'GET') puts++; });
    await page.goto(`${base}/profile/personal`);
    await page.getByRole('button', { name: /^(Add|Edit) Steam trade URL$/ }).click();
    const box = page.getByRole('textbox', { name: 'Steam trade URL' });
    await box.fill('');
    await page.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(page.getByRole('alert').filter({ hasText: /Paste your Steam trade URL/ })).toBeVisible();
    await expect(box).toBeFocused();
    await expect(page.getByText(/trade URL removed/)).toHaveCount(0);
    expect(puts).toBe(0);
  });
});

test.describe('Profile > Personal Info on a phone and its button names (stubbed)', () => {
  const PROFILE = (extra = {}) => ({ user: { ...ME, steamId64: '76561198000000000', email: 'alexander.thompson@gmail.com', emailVerified: false, ...extra }, twoFactorEnabled: false });

  test('a long email keeps Unverified and Edit on screen at 390px', async ({ page }) => {
    await page.setViewportSize(PHONE);
    await consent(page);
    await signedIn(page);
    await page.route('**/api/profile/me', (r) => r.fulfill(json(PROFILE())));
    await page.goto('/profile/personal');
    const edit = page.getByRole('button', { name: 'Edit email address' });
    await expect(edit).toBeVisible({ timeout: 20_000 });
    const box = await edit.boundingBox();
    expect(box.x + box.width).toBeLessThanOrEqual(PHONE.width);
    const badge = await page.getByText('Unverified', { exact: true }).boundingBox();
    expect(badge.x + badge.width).toBeLessThanOrEqual(PHONE.width);
  });

  test('stall link, trade URL and backup-code Copy buttons say what they copy', async ({ page }) => {
    await consent(page);
    await signedIn(page);
    let enabled = false;
    await page.route('**/api/profile/me', (r) => r.fulfill(json({ ...PROFILE(), twoFactorEnabled: enabled })));
    await page.route('**/api/profile/2fa/enroll', (r) => r.fulfill(json({ secret: 'JBSWY3DPEHPK3PXP', otpauthUrl: 'otpauth://totp/x?secret=JBSWY3DPEHPK3PXP' })));
    await page.route('**/api/profile/2fa/confirm', (r) => { enabled = true; r.fulfill(json({ ok: true, recoveryCodes: ['aaaa-bbbb', 'cccc-dddd'] })); });
    await page.goto('/profile/personal');
    await expect(page.getByRole('button', { name: 'Copy public stall link' })).toBeVisible({ timeout: 20_000 });
    await expect(page.getByRole('button', { name: 'Copy Steam trade URL' })).toBeVisible();
    await page.getByRole('button', { name: 'Enable 2FA' }).click();
    await page.getByRole('textbox', { name: '6-digit code from your authenticator app' }).fill('123456');
    // Six digits submit on their own; the panel of new codes then shows.
    await expect(page.getByRole('button', { name: 'Copy backup codes' })).toBeVisible();
    await expect(page.getByRole('tabpanel').getByRole('button', { name: /^\W*Copy$/ })).toHaveCount(0);
  });
});

test.describe('Headings are named without the links inside them (real app)', () => {
  test('item page "More from" rail heading', async ({ page }) => {
    await consent(page);
    await page.goto(`${base}/item/12`);
    const h = page.getByRole('heading', { level: 2, name: /^More from / });
    await expect(h).toBeVisible({ timeout: 20_000 });
    await expect(h).not.toHaveAccessibleName(/Visit stall/);
    await expect(h.getByRole('link', { name: /Visit stall/ })).toBeVisible();
  });

  test('stall hero name heading', async ({ page }) => {
    await consent(page);
    await page.goto(`${base}/stall/5`);
    const link = page.getByRole('link', { name: /Steam/ }).filter({ hasText: '↗ Steam' });
    await expect(link).toBeVisible({ timeout: 20_000 });
    const h = page.getByRole('heading', { level: 2 }).filter({ has: link });
    await expect(h).toHaveAccessibleName(/^EmberWolf(, Verified)?$/);
  });
});
