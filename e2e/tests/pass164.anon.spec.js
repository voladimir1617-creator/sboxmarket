// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 164.

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

// Pass 164: Loadout Discover search, Loadout tabs on a nav link / Back,
// the shortcuts dialog with "/" and Ctrl+K, and modifier-clicks on Database
// rows and loadout cards. Real-jar tests sign in with dev-login and write nothing.

const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
});

test.describe('Loadout Lab Discover (real jar)', () => {
  test('a search with trailing spaces still finds the loadout it names', async ({ page }) => {
    await page.goto(`${base}/loadout`);
    const first = page.locator('.loadout-card-name').first();
    await expect(first).toBeVisible({ timeout: 15_000 });
    const name = ((await first.getAttribute('title')) || (await first.innerText())).trim();
    const box = page.getByRole('textbox', { name: 'Search public loadouts' });
    await box.fill(name + '  ');
    // The search has run once the URL carries it (trimmed).
    await expect(page).toHaveURL(new RegExp('[?&]q=' + encodeURIComponent(name).replace(/%20/g, '(\\+|%20)') + '$'));
    await expect(page.locator('.loadout-card-name').filter({ hasText: name }).first()).toBeVisible();
    await expect(page.getByRole('heading', { name: /No loadouts match/ })).toHaveCount(0);
    // Spaces alone are no search: the whole list, not "No public loadouts yet".
    await box.fill('zzqq-none-164');
    await expect(page.getByRole('heading', { name: /No loadouts match/ })).toBeVisible();
    await box.fill('  ');
    await expect(page).toHaveURL(/\/loadout$/);
    await expect(page.locator('.loadout-card-name').first()).toBeVisible();
    await expect(page.getByRole('heading', { name: /No public loadouts yet/ })).toHaveCount(0);
  });

  test('the nav Loadout Lab link from My Loadouts opens Discover, and Back returns to My Loadouts', async ({ page }) => {
    await page.request.get(`${base}/api/auth/steam/dev-login?userId=2&next=/`);
    await page.goto(`${base}/loadout?view=mine`);
    await expect(page.getByRole('tab', { name: 'My Loadouts' })).toHaveAttribute('aria-selected', 'true', { timeout: 15_000 });
    await page.locator('a.nav-link[href="/loadout"]').first().click();
    await expect(page).toHaveURL(/\/loadout$/);
    await expect(page.getByRole('tab', { name: 'Discover' })).toHaveAttribute('aria-selected', 'true');
    await expect(page.getByRole('textbox', { name: 'Search public loadouts' })).toBeVisible();
    await page.goBack();
    await expect(page).toHaveURL(/\/loadout\?view=mine$/);
    await expect(page.getByRole('tab', { name: 'My Loadouts' })).toHaveAttribute('aria-selected', 'true');
  });
});

test.describe('Help Center deep links (real jar)', () => {
  test('a /help?q= link that matches nothing brings the search and its no-match line into view', async ({ page }) => {
    await page.goto(`${base}/help?q=zzqq-none-164`);
    const none = page.getByRole('heading', { name: /No FAQs match "zzqq-none-164"/ });
    await expect(none).toBeAttached({ timeout: 15_000 });
    await expect(none).toBeInViewport();
    await expect(page.getByRole('searchbox', { name: 'Search frequently asked questions' })).toBeInViewport();
  });
});

test.describe('Keyboard shortcuts dialog (real jar)', () => {
  test('"/" with the shortcuts dialog open closes it and focuses the search', async ({ page }) => {
    await page.goto(`${base}/market`);
    const search = page.locator('input.search-input');
    await expect(search).toBeVisible({ timeout: 15_000 });
    await page.locator('body').press('Shift+Slash');
    const dialog = page.getByRole('dialog', { name: 'Keyboard Shortcuts' });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByRole('button', { name: 'Close' })).toBeFocused();
    await page.keyboard.press('/');
    await expect(dialog).toHaveCount(0);
    await expect(search).toBeFocused();
  });

  test('Ctrl+K with the shortcuts dialog open closes it and focuses the search', async ({ page }) => {
    await page.goto(`${base}/market`);
    const search = page.locator('input.search-input');
    await expect(search).toBeVisible({ timeout: 15_000 });
    await page.locator('body').press('Shift+Slash');
    const dialog = page.getByRole('dialog', { name: 'Keyboard Shortcuts' });
    await expect(dialog.getByRole('button', { name: 'Close' })).toBeFocused();
    await page.keyboard.press('Control+k');
    await expect(dialog).toHaveCount(0);
    await expect(search).toBeFocused();
  });
});

test.describe('Modifier-click opens a new tab (real jar)', () => {
  test('Ctrl-click on a Database row cell opens the item in a new tab and keeps /db', async ({ page }) => {
    await page.goto(`${base}/db`);
    const cell = page.locator('.db-table tbody tr td.db-cat').first();
    await expect(cell).toBeVisible({ timeout: 15_000 });
    const [popup] = await Promise.all([
      page.context().waitForEvent('page', { timeout: 10_000 }),
      cell.click({ modifiers: ['Control'] }),
    ]);
    await popup.waitForURL(/\/item\/\d+/);
    await expect(page).toHaveURL(/\/db/);
    await popup.close();
  });

  test('Ctrl-click on a loadout card opens the loadout in a new tab and keeps the list', async ({ page }) => {
    await page.goto(`${base}/loadout`);
    const meta = page.locator('.loadout-card .loadout-card-meta').first();
    await expect(meta).toBeVisible({ timeout: 15_000 });
    const [popup] = await Promise.all([
      page.context().waitForEvent('page', { timeout: 10_000 }),
      meta.click({ modifiers: ['Control'] }),
    ]);
    await popup.waitForURL(/\/loadout\/\d+/);
    await expect(page).toHaveURL(/\/loadout$/);
    await popup.close();
  });
});
