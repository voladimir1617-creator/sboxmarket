// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 169.

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

// Pass 169: shortcuts list reachable by keyboard on a phone, bell rows wrap
// long words, Save stays off on an unchanged stall bio / trade URL, and the
// Notifications empty state names "Browse marketplace" without its arrow.

const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';
const consent = (page) => page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
// Real-jar tests never write: every POST/PUT/PATCH/DELETE is answered here.
const noWrites = (page) => page.route(`${base}/api/**`, (r) => (['GET', 'HEAD'].includes(r.request().method()) ? r.fallback() : r.fulfill(json({}))));
const devLogin = (page, id) => page.request.get(`${base}/api/auth/steam/dev-login?userId=${id}&next=/`);

test.describe('pass169 · keyboard shortcuts list on a phone (real jar)', () => {
  test.use({ viewport: { width: 390, height: 844 } });

  test('arrow and page keys from Close scroll the shortcuts list to its last row', async ({ page }) => {
    await consent(page);
    await page.goto(`${base}/settings`);
    await expect(page.getByRole('heading', { level: 1, name: 'Settings' })).toBeVisible({ timeout: 20_000 });
    await page.keyboard.press('Shift+Slash');
    const dialog = page.getByRole('dialog', { name: 'Keyboard Shortcuts' });
    await expect(dialog.getByRole('button', { name: 'Close' })).toBeFocused();
    const last = dialog.locator('.shortcut-row').last();
    await expect(last).not.toBeInViewport({ ratio: 1 });
    await page.keyboard.press('End');
    await expect(last).toBeInViewport({ ratio: 1 });
    await expect(dialog.getByRole('button', { name: 'Close' })).toBeFocused();
    await page.keyboard.press('Home');
    await expect(dialog.locator('.shortcut-row').first()).toBeInViewport({ ratio: 1 });
    await page.keyboard.press('PageDown');
    await expect(dialog.locator('.shortcut-row').first()).not.toBeInViewport({ ratio: 1 });
  });
});

test.describe('pass169 · notification bell rows with a long word', () => {
  const LONG = 'https://steamcommunity.com/tradeoffer/new/?partner=123456789&token=abcdefghijklmnopqrstuvwxyz0123456789';
  for (const vp of [{ width: 390, height: 844 }, { width: 1500, height: 900 }]) {
    test(`a long link in a bell row wraps inside the row at ${vp.width}px (stubbed)`, async ({ page }) => {
      await page.setViewportSize(vp);
      await consent(page);
      await signedIn(page);
      const items = [
        { id: 1, kind: 'TRADE_OPENED', title: 'Trade opened', body: 'Send it to ' + LONG, read: false, createdAt: Date.now() },
        { id: 2, kind: 'TRADE_OPENED', title: 'Payout_reference_' + 'x'.repeat(60), body: 'Done', read: true, createdAt: Date.now() - 60e3 }
      ];
      await page.route('**/api/notifications**', (r) => r.fulfill(json(r.request().url().includes('unread-count') ? { unread: 1 } : { items, unread: 1 })));
      await page.goto('/market');
      await page.getByRole('button', { name: /^Notifications/ }).click({ timeout: 20_000 });
      const texts = page.locator('.notif-dropdown .notif-text');
      await expect(texts).toHaveCount(2);
      // Nothing in the row is wider than the row: no word is cut at its right edge.
      await expect.poll(() => texts.evaluateAll((els) => els.flatMap((t) => [t, ...t.querySelectorAll('div')])
        .filter((e) => e.scrollWidth > e.clientWidth + 1).length)).toBe(0);
    });
  }
});

test.describe('pass169 · Save with nothing changed', () => {
  test('stall bio: Save is off and sends nothing while the bio is unchanged (stubbed)', async ({ page }) => {
    await consent(page);
    await signedIn(page);
    await page.route(`**/api/listings/stall/${ME.id}`, (r) => r.fulfill(json({ seller: { id: ME.id, displayName: ME.displayName, stallBio: 'Fast trades' }, listings: [], count: 0, blockedByViewer: false })));
    const puts = [];
    await page.route('**/api/profile/stall-bio', (r) => { puts.push(r.request().postData()); return r.fulfill(json({ stallBio: 'Fast trades' })); });
    await page.goto(`/stall/${ME.id}`);
    await page.getByRole('button', { name: '✎ Edit' }).click({ timeout: 20_000 });
    const box = page.getByRole('textbox', { name: 'Stall bio' });
    await expect(box).toHaveValue('Fast trades');
    const save = page.getByRole('button', { name: 'Save', exact: true });
    await expect(save).toHaveAttribute('aria-disabled', 'true');
    await save.focus();
    await page.keyboard.press('Enter');
    await box.press('Control+Enter');
    await expect(box).toBeVisible();
    expect(puts).toHaveLength(0);
    // A real edit turns Save back on.
    await box.fill('Fast trades, evenings');
    await expect(save).not.toHaveAttribute('aria-disabled', 'true');
  });
});

test.describe('pass169 · Notifications empty state (real jar)', () => {
  test('"Browse marketplace" is named without its arrow', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await devLogin(page, 3);
    await page.goto(`${base}/notifications`);
    await expect(page.getByRole('heading', { name: 'Quiet so far' })).toBeVisible({ timeout: 20_000 });
    const link = page.getByRole('link', { name: 'Browse marketplace', exact: true });
    await expect(link).toBeVisible();
    await expect(link).toContainText('→');
  });
});
