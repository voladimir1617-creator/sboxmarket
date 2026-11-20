// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 147.

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


// Defect pass 147. Real-app tests take the server from JAR / E2E_BASE_URL
// and stub every write: a real-jar test must never create or change server data.

const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';

async function noWrites(page) {
  await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
  await page.route('**/api/**', (r) => (['POST', 'PUT', 'DELETE', 'PATCH'].includes(r.request().method()) ? r.fulfill(json({})) : r.fallback()));
}

test.describe('Loadout Lab slots (real app)', () => {
  test('each slot is a group named by its slot, and the item is named once', async ({ page }) => {
    await noWrites(page);
    await page.goto(`${base}/loadout/1`);
    const hats = page.getByRole('group', { name: 'Hats', exact: true });
    await expect(hats.getByRole('link')).toHaveCount(1, { timeout: 20_000 });
    const name = (await hats.getByRole('link').textContent() || '').trim();
    expect(name.length).toBeGreaterThan(0);
    // The thumbnail no longer repeats the link's name.
    await expect(hats.getByRole('img', { name })).toHaveCount(0);
    for (const slot of ['Jackets', 'Shirts', 'Pants', 'Gloves', 'Boots', 'Accessories', 'Wild']) {
      await expect(page.getByRole('group', { name: slot, exact: true })).toHaveCount(1);
    }
  });
});

test.describe('market filter sidebar (real app)', () => {
  test('the Rarity and Price Range sections are groups named by their titles', async ({ page }) => {
    await noWrites(page);
    await page.goto(`${base}/market`);
    const side = page.getByRole('complementary', { name: 'Filters' });
    await expect(side.getByRole('group', { name: 'Rarity', exact: true }).getByRole('radiogroup', { name: 'Rarity filter' })).toBeVisible({ timeout: 20_000 });
    await expect(side.getByRole('group', { name: 'Price Range', exact: true }).getByRole('textbox', { name: /^Minimum price filter/ })).toBeVisible();
  });

  test('Clear Filters with the Rarity section folded keeps focus in the sidebar', async ({ page }) => {
    await noWrites(page);
    await page.goto(`${base}/market?rarity=Limited`);
    const side = page.getByRole('complementary', { name: 'Filters' });
    await expect(side.getByRole('radio', { name: /^Limited/ })).toHaveAttribute('aria-checked', 'true', { timeout: 20_000 });
    await side.locator('summary', { hasText: /Rarity/i }).click();
    await expect(side.getByRole('radio', { name: /^Limited/ })).toBeHidden();
    const clear = side.getByRole('button', { name: 'Clear Filters' });
    await clear.focus();
    await page.keyboard.press('Enter');
    await expect(clear).toBeDisabled();
    await expect(side.locator('summary', { hasText: /Rarity/i })).toBeFocused();
  });

  test('phone: the filter drawer sections are named groups too', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await noWrites(page);
    await page.goto(`${base}/market`);
    await page.getByRole('button', { name: 'Open filters' }).click({ timeout: 20_000 });
    const side = page.getByRole('complementary', { name: 'Filters' });
    await expect(side.getByRole('group', { name: 'Rarity', exact: true })).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(page.getByRole('button', { name: 'Open filters' })).toBeVisible();
  });
});

test.describe('switch descriptions (real app)', () => {
  test('Personal Info: Email notifications and Anonymous shop read their hint lines', async ({ page }) => {
    await noWrites(page);
    await page.request.get(`${base}/api/auth/steam/dev-login?userId=2&next=/`);
    await page.goto(`${base}/profile`);
    const email = page.getByRole('switch', { name: 'Email notifications' });
    await expect(email).toBeVisible({ timeout: 20_000 });
    await expect(email).toHaveAccessibleDescription(/Security \+ account-state emails .* always send/);
    const anon = page.getByRole('switch', { name: 'Anonymous shop' });
    await anon.scrollIntoViewIfNeeded();
    await expect(anon).toHaveAccessibleDescription(/show as .Anonymous seller./);
  });

  test('Settings: each switch reads its sublabel', async ({ page }) => {
    await noWrites(page);
    await page.request.get(`${base}/api/auth/steam/dev-login?userId=2&next=/`);
    await page.goto(`${base}/settings`);
    await expect(page.getByRole('switch', { name: 'Hide dollar amounts' })).toHaveAccessibleDescription(/Item prices stay visible/, { timeout: 20_000 });
    await expect(page.getByRole('switch', { name: 'Reduce motion' })).toHaveAccessibleDescription(/Disable animations/);
    await expect(page.getByRole('switch', { name: 'Sale notifications' })).toHaveAccessibleDescription(/Toast when someone buys/);
    await expect(page.getByRole('combobox', { name: 'Display currency' })).toHaveAccessibleDescription(/Prices shown in your chosen currency/);
  });
});

test.describe('changelog and status page (real app)', () => {
  test('changelog: the back link is named without its arrow and dates are time elements', async ({ page }) => {
    await page.goto(`${base}/changelog.html`);
    await expect(page.getByRole('main').getByRole('link', { name: 'Back to SkinBox', exact: true })).toBeVisible();
    const first = page.getByRole('heading', { level: 2, name: /^May 3, 2026/ });
    await expect(first.locator('time')).toHaveAttribute('datetime', '2026-05-03');
    await expect(page.locator('h2.changelog-date:not(:has(time[datetime]))')).toHaveCount(0);
  });

  test('status: the version line gives the start as a time element', async ({ page }) => {
    const startupAt = Date.now() - 2 * 3_600_000;
    await page.route('**/api/version', (r) => r.fulfill(json({ version: '1.0.0', startupAt })));
    await page.goto(`${base}/status.html`);
    await expect(page.locator('#version-line')).toContainText(/started/, { timeout: 20_000 });
    await expect(page.locator('#version-line time')).toHaveAttribute('datetime', new Date(startupAt).toISOString());
    await expect(page.locator('#version-line')).toContainText(/^v1\.0\.0 · uptime 2h 0m · started /);
  });
});
