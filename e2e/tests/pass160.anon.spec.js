// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 160.

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

// Pass 160 (k): shortcuts dialog on a phone, Developers tab mint/revoke,
// status page clock, stall listings heading.

// Real-jar checks read the app from here; they write nothing to the server.
const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';

async function consent(page) {
  await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
}

test.describe('pass160k · keyboard shortcuts dialog on a phone (real jar)', () => {
  test.use({ viewport: { width: 390, height: 844 } });

  test('the ? dialog fits the screen: its title and Close stay reachable', async ({ page }) => {
    await consent(page);
    await page.goto(`${base}/settings`);
    await expect(page.getByRole('heading', { level: 1, name: 'Settings' })).toBeVisible({ timeout: 20_000 });
    await page.keyboard.press('Shift+Slash');
    const dialog = page.getByRole('dialog', { name: 'Keyboard Shortcuts' });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByRole('button', { name: 'Close' })).toBeInViewport({ ratio: 1 });
    await expect(page.locator('#shortcuts-title')).toBeInViewport({ ratio: 1 });
    await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0);
  });
});

test.describe('pass160k · keyboard shortcuts dialog lists what Esc does (real jar)', () => {
  test('the Esc row does not promise "Back to market", which a page like Settings never does', async ({ page }) => {
    await consent(page);
    await page.goto(`${base}/settings`);
    await expect(page.getByRole('heading', { level: 1, name: 'Settings' })).toBeVisible({ timeout: 20_000 });
    await page.keyboard.press('Shift+Slash');
    const dialog = page.getByRole('dialog', { name: 'Keyboard Shortcuts' });
    const esc = dialog.locator('.shortcut-row').filter({ has: page.locator('kbd', { hasText: /^Esc$/ }) });
    await expect(esc).toHaveCount(1);
    await expect(esc).not.toContainText(/Back to market/);
    await expect(esc).toContainText(/dialog/);
    // What Escape really does here: closes the dialog, then stays on Settings.
    await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0);
    await page.keyboard.press('Escape');
    await page.waitForTimeout(300);
    await expect(page).toHaveURL(/\/settings$/);
  });
});

test.describe('pass160k · Profile Developers tab', () => {
  test('a new key is announced: "Copy this now" is a status (stubbed)', async ({ page }) => {
    await consent(page);
    await signedIn(page);
    await page.route('**/api/api-keys', (r) => r.request().method() === 'POST'
      ? r.fulfill(json({ id: 7, label: 'my-bot', token: 'sbk_test_0123456789', scope: 'RW', createdAt: Date.now() }))
      : r.fulfill(json([])));
    await page.goto('/profile/developers');
    await page.getByRole('textbox', { name: 'API key label' }).fill('my-bot', { timeout: 20_000 });
    await page.getByRole('button', { name: /New Key/ }).click();
    await expect(page.getByText('sbk_test_0123456789')).toBeVisible();
    await expect(page.getByRole('status').filter({ hasText: /COPY THIS NOW/ })).toBeVisible();
  });

  test('the Revoke confirm names the key it revokes (real jar, stubbed list)', async ({ page }) => {
    await consent(page);
    await page.request.get(`${base}/api/auth/steam/dev-login?userId=${ME.id}&next=/`);
    const now = Date.now();
    const deletes = [];
    await page.route(/\/api\/api-keys(\/\d+)?(\?|$)/, (r) => {
      if (r.request().method() === 'DELETE') { deletes.push(r.request().url()); return r.fulfill(json({ ok: true })); }
      if (r.request().method() !== 'GET') return r.fulfill(json({}));
      return r.fulfill(json([
        { id: 11, label: 'price-bot', publicPrefix: 'sbk_aa11', scope: 'RO', createdAt: now - 86_400_000, lastUsedAt: null, revoked: false },
        { id: 12, label: 'trade-bot', publicPrefix: 'sbk_bb22', scope: 'RW', createdAt: now - 2 * 86_400_000, lastUsedAt: null, revoked: false }
      ]));
    });
    const messages = [];
    page.on('dialog', (d) => { messages.push(d.message()); d.dismiss().catch(() => {}); });
    await page.goto(`${base}/profile/developers`);
    await page.getByRole('button', { name: 'Revoke API key trade-bot', exact: true }).click({ timeout: 20_000 });
    await expect.poll(() => messages.length).toBe(1);
    expect(messages[0]).toMatch(/trade-bot/);
    expect(messages[0]).not.toMatch(/price-bot/);
    // Dismissed: nothing was revoked and the button is still there.
    await expect(page.getByRole('button', { name: 'Revoke API key trade-bot', exact: true })).toBeVisible();
    expect(deletes).toEqual([]);
  });
});

test.describe('pass160k · status page (real jar)', () => {
  test('a viewer whose clock runs 30 minutes fast still sees a fresh price sweep as operational', async ({ page }) => {
    test.setTimeout(120_000);
    // A fresh server runs its first sweep shortly after start; wait for it so
    // the row has an age to show.
    await expect.poll(async () => {
      const r = await page.request.get(`${base}/api/items/price-refresh-status`);
      const b = await r.json();
      return !!(b && b.floor && b.floor.finishedAt);
    }, { timeout: 90_000 }).toBe(true);
    await page.clock.setFixedTime(new Date(Date.now() + 30 * 60_000));
    await page.goto(`${base}/status.html`);
    const row = page.locator('.status-row').filter({ has: page.getByRole('heading', { level: 3, name: 'Price refresh' }) });
    await expect(row).toContainText(/data is \d+[smh]/, { timeout: 15_000 });
    await expect(row).toContainText('OPERATIONAL');
    await expect(row).not.toContainText('DOWN');
  });
});

test.describe('pass160k · stall listings (real jar)', () => {
  test('the stall listing grid has a level-2 "Listings" heading before its filters', async ({ page }) => {
    await consent(page);
    await page.goto(`${base}/stall/2`);
    const main = page.getByRole('main');
    await expect(main.getByRole('group', { name: 'Rarity' })).toBeVisible({ timeout: 20_000 });
    const heading = main.getByRole('heading', { level: 2, name: 'Listings', exact: true });
    await expect(heading).toBeAttached();
    // In reading order it sits between the seller name and the Rarity chips.
    const order = await page.evaluate(() => {
      const hd = [...document.querySelectorAll('#main h2')].find((e) => e.textContent === 'Listings');
      const grp = document.querySelector('#main [role="group"][aria-label="Rarity"]');
      return !!(hd && grp && (hd.compareDocumentPosition(grp) & Node.DOCUMENT_POSITION_FOLLOWING));
    });
    expect(order).toBe(true);
  });
});
