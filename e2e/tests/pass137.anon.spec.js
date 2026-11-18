// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 137.

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

// Defect pass 137: bell rows name their time, the notifications page row
// buttons name the row's body, the phone filter drawer closes when Tab leaves
// it, 2FA buttons keep focus on a refusal, the Sell page's blocked Steam
// tiles are exposed, and the empty stall line is
// a heading.

const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';
const now = Date.now();

async function consent(page) {
  await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
}
// Real-jar tests never write: every POST/PUT/DELETE/PATCH is answered here.
async function noWrites(page) {
  await page.route('**/api/**', (r) => (['POST', 'PUT', 'DELETE', 'PATCH'].includes(r.request().method())
    ? r.fulfill(json({}))
    : r.fallback()));
}
async function devLogin(page, userId) {
  await page.request.get(`${base}/api/auth/steam/dev-login?userId=${userId}&next=/`);
}

test.describe('pass137 · notifications (stubbed)', () => {
  const items = [
    { id: 1, kind: 'ITEM_SOLD', title: 'Item sold', body: 'Your Sports Bandage sold for $1.08.', read: false, createdAt: now - 3 * 3600_000 },
    { id: 2, kind: 'REVIEW_RECEIVED', title: 'New 5★ review', body: 'dave reviewed your Police Hat sale.', read: true, createdAt: now - 26 * 3600_000 },
    { id: 3, kind: 'REVIEW_RECEIVED', title: 'New 5★ review', body: 'erin reviewed your Chef Hat sale.', read: true, createdAt: now - 27 * 3600_000 },
  ];
  async function stub(page) {
    await signedIn(page);
    await page.route((u) => u.pathname === '/api/notifications', (r) => r.fulfill(json({ items, unread: 1 })));
    await page.route('**/api/notifications/unread-count', (r) => r.fulfill(json({ unread: 1 })));
  }

  test('a bell row is named with the time shown under it ("3h ago")', async ({ page }) => {
    await stub(page);
    await page.goto('/market');
    await page.getByRole('button', { name: /^Notifications/ }).click();
    const row = page.getByRole('menuitem', { name: /Item sold\. Your Sports Bandage sold for \$1\.08\./ });
    await expect(row).toBeVisible();
    await expect(row).toContainText('3h ago');
    await expect(row).toHaveAccessibleName(/— 3h ago$/);
  });

  test('two "New 5★ review" rows get different Delete and Mark as unread names', async ({ page }) => {
    await stub(page);
    await page.goto('/notifications');
    await expect(page.getByRole('button', { name: /Open notification: New 5★ review.*Police Hat/ })).toHaveCount(1, { timeout: 15_000 });
    await expect(page.getByRole('button', { name: /^Delete this notification: New 5★ review\. dave reviewed your Police Hat/ })).toHaveCount(1);
    await expect(page.getByRole('button', { name: /^Delete this notification: New 5★ review\. erin reviewed your Chef Hat/ })).toHaveCount(1);
    await expect(page.getByRole('button', { name: /^Mark as unread: New 5★ review\. erin reviewed your Chef Hat/ })).toHaveCount(1);
  });
});

test.describe('pass137 · market filters on a phone (real jar)', () => {
  test.use({ viewport: { width: 390, height: 844 } });
  test('Tab past the last filter closes the drawer instead of landing under its backdrop', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await page.goto(`${base}/market`);
    const fab = page.getByRole('button', { name: 'Open filters' });
    await fab.click({ timeout: 20_000 });
    await expect(page.getByRole('button', { name: 'Close filters' })).toHaveAttribute('aria-expanded', 'true');
    const drawer = page.getByRole('complementary', { name: 'Filters' });
    // Shift+Tab back to the Filters button keeps the drawer open.
    await drawer.locator('summary:visible').first().focus();
    await page.keyboard.press('Shift+Tab');
    await expect(page.getByRole('button', { name: 'Close filters' })).toBeFocused();
    // Tab out past the last price chip.
    await drawer.getByRole('button', { name: />\$10\.00/ }).focus();
    await page.keyboard.press('Tab');
    await expect(page.getByRole('button', { name: 'Open filters' })).toHaveAttribute('aria-expanded', 'false');
    // The control that took focus is not covered by the drawer's backdrop.
    const covered = await page.evaluate(() => {
      const r = document.activeElement.getBoundingClientRect();
      const top = document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2);
      return !!top && !document.activeElement.contains(top) && top !== document.activeElement;
    });
    expect(covered).toBe(false);
  });
});

test.describe('pass137 · Profile 2FA keeps focus on a refusal', () => {
  test('a refused Enable 2FA keeps focus on the button (real jar)', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await page.route('**/api/profile/2fa/enroll', slow({ error: 'Two-factor setup is unavailable' }, 600));
    await devLogin(page, 2);
    await page.goto(`${base}/profile/personal`);
    const enable = page.getByRole('button', { name: 'Enable 2FA' });
    await enable.click({ timeout: 20_000 });
    await expect(page.getByRole('alert').filter({ hasText: /Two-factor setup is unavailable/ })).toBeVisible();
    await expect(enable).toBeFocused();
  });

  test('a refused Disable 2FA code keeps focus on Disable 2FA (stubbed)', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/profile/me', (r) => r.fulfill(json({ user: { ...ME, email: 'c@example.com', emailVerified: true }, twoFactorEnabled: true })));
    await page.route('**/api/profile/2fa/disable', (r) => new Promise((ok) => setTimeout(ok, 600))
      .then(() => r.fulfill(json({ error: 'That code is not valid' }, 400))));
    await page.goto('/profile/personal');
    await page.getByRole('button', { name: 'Disable', exact: true }).click({ timeout: 15_000 });
    await page.getByRole('textbox', { name: /authenticator code or backup code/ }).fill('backup-1234');
    const submit = page.getByRole('button', { name: /^(Disable 2FA|Disabling…)$/ });
    await submit.click();
    await expect(page.getByRole('alert').filter({ hasText: /That code is not valid/ })).toBeVisible();
    await expect(submit).toBeFocused();
  });

  const refuse = (r) => new Promise((ok) => setTimeout(ok, 600)).then(() => r.fulfill(json({ error: 'That code is not valid' }, 400)));

  test('a refused code keeps focus on Mint new codes (stubbed)', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/profile/me', (r) => r.fulfill(json({ user: { ...ME, email: 'c@example.com', emailVerified: true }, twoFactorEnabled: true })));
    await page.route('**/api/profile/2fa/regenerate-codes', refuse);
    await page.goto('/profile/personal');
    await page.getByRole('button', { name: 'Regenerate codes' }).click({ timeout: 15_000 });
    // Six digits submit by themselves; the second try is the button.
    await page.getByRole('textbox', { name: '6-digit authenticator code' }).fill('123456');
    await expect(page.getByRole('alert').filter({ hasText: /That code is not valid/ })).toBeVisible();
    const mint = page.getByRole('button', { name: /^(Mint new codes|Regenerating…)$/ });
    await mint.click();
    await expect(mint).toHaveText('Regenerating…');
    await expect(page.getByRole('alert').filter({ hasText: /That code is not valid/ })).toBeVisible();
    await expect(mint).toHaveText('Mint new codes');
    await expect(mint).toBeFocused();
  });

  test('a refused code keeps focus on Confirm & Enable (stubbed)', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/profile/me', (r) => r.fulfill(json({ user: { ...ME, twoFactorEnabled: false }, twoFactorEnabled: false })));
    await page.route('**/api/profile/2fa/enroll', (r) => r.fulfill(json({ secret: 'JBSWY3DPEHPK3PXP', otpauthUrl: 'otpauth://totp/SkinBox:carol?secret=JBSWY3DPEHPK3PXP' })));
    await page.route('**/api/profile/2fa/confirm', refuse);
    await page.goto('/profile/personal');
    await page.getByRole('button', { name: 'Enable 2FA' }).click({ timeout: 15_000 });
    await page.getByRole('textbox', { name: /6-digit code from your authenticator app/ }).fill('123456');
    await expect(page.getByRole('alert').filter({ hasText: /That code is not valid/ })).toBeVisible();
    const confirm = page.getByRole('button', { name: 'Confirm & Enable' });
    await confirm.click();
    // The old error clears while the second try is in flight, then returns.
    await expect(page.getByRole('alert').filter({ hasText: /That code is not valid/ })).toHaveCount(0);
    await expect(page.getByRole('alert').filter({ hasText: /That code is not valid/ })).toBeVisible();
    await expect(confirm).toBeFocused();
  });
});

test.describe('pass137 · Sell page (real jar)', () => {
  const inventory = {
    items: [
      { assetId: 'p1', name: 'Police Hat', rarity: 'Standard', category: 'Hats', tradable: true, catalogueId: 1, suggestedPrice: '1.50', quantity: 1, listableQuantity: 1, listedCount: 0, unlistableReason: null },
      { assetId: 'p2', name: 'Grand Ball Gown', rarity: 'Limited', category: 'Shirts', tradable: false, catalogueId: 2, suggestedPrice: '9.00', quantity: 1, listableQuantity: 0, listedCount: 0, unlistableReason: 'NOT_TRADABLE' },
      { assetId: 'p3', name: 'Chef Hat', rarity: 'Standard', category: 'Hats', tradable: true, catalogueId: 3, suggestedPrice: '2.00', quantity: 1, listableQuantity: 0, listedCount: 1, unlistableReason: 'ALREADY_LISTED' },
    ],
    count: 3, assetCount: 3, lastSyncedAt: now,
  };

  test('a Steam tile that cannot be listed is a group named with its reason', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await page.route('**/api/steam/inventory', (r) => r.fulfill(json(inventory)));
    await devLogin(page, 2);
    await page.goto(`${base}/sell`);
    await expect(page.getByRole('button', { name: 'List Police Hat for sale' })).toBeVisible({ timeout: 20_000 });
    await expect(page.getByRole('group', { name: 'Grand Ball Gown — not tradable on Steam right now' })).toBeVisible();
    await expect(page.getByRole('group', { name: 'Chef Hat — already listed, not available to list again' })
      .getByRole('link', { name: 'Manage in My Stall' })).toBeVisible();
  });
});

test.describe('pass137 · public stall (real jar)', () => {
  test('"This seller has no active listings right now." is a heading', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    const stall = await (await page.request.get(`${base}/api/listings/stall/3`)).json();
    await page.route(/\/api\/listings\/stall\/3(\?.*)?$/, (r) => r.fulfill(json({ ...stall, listings: [], count: 0, away: false, awayCount: 0 })));
    await page.goto(`${base}/stall/3`);
    await expect(page.getByRole('heading', { level: 2, name: /This seller has no active listings right now/ })).toBeVisible({ timeout: 20_000 });
  });
});
