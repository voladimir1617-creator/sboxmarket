// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 128.

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


// Defect pass 128. Real-jar tests take the server from JAR / E2E_BASE_URL.
const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';

async function devLogin(page, userId) {
  await page.request.get(`${base}/api/auth/steam/dev-login?userId=${userId}&next=/`);
}
async function consent(page) {
  await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
}

test.describe('pass128 · My Stall away mode return date (real jar)', () => {
  test('Escape, Cancel and Set on the return-date picker keep keyboard focus in the toolbar', async ({ page }) => {
    await consent(page);
    await devLogin(page, 3);
    await page.goto(`${base}/me/stall`);
    const sw = page.getByRole('switch', { name: /Away mode/ });
    await expect(sw).toBeEnabled({ timeout: 20_000 });
    // Start from away mode on with no return date (turning it off clears the date).
    if ((await sw.getAttribute('aria-checked')) === 'true') {
      await sw.click();
      await expect(sw).toHaveAttribute('aria-checked', 'false');
    }
    await sw.click();
    await expect(sw).toHaveAttribute('aria-checked', 'true');
    try {
      const schedule = page.getByRole('button', { name: 'Schedule return' });
      const date = page.getByRole('textbox', { name: 'Stall return date' }).or(page.getByLabel('Stall return date'));

      await schedule.click();
      await expect(date.first()).toBeFocused();
      await page.keyboard.press('Escape');
      await expect(date).toHaveCount(0);
      await expect(schedule).toBeFocused();

      await schedule.click();
      await page.getByRole('button', { name: 'Cancel', exact: true }).click();
      await expect(date).toHaveCount(0);
      await expect(schedule).toBeFocused();

      await schedule.click();
      await page.getByRole('button', { name: 'Set', exact: true }).click();
      await expect(page.getByText(/^Returns /)).toBeVisible();
      await expect(sw).toBeFocused();
    } finally {
      if ((await sw.getAttribute('aria-checked')) === 'true') await sw.click();
      await expect(sw).toHaveAttribute('aria-checked', 'false');
    }
  });
});

test.describe('pass128 · Loadout Discover no-match state', () => {
  test('Loadout Discover: a search with no match is a heading like the other empty states (real jar)', async ({ page }) => {
    await consent(page);
    await page.goto(`${base}/loadout`);
    await page.getByRole('textbox', { name: 'Search public loadouts' }).fill('zzqqxx-pass128');
    await expect(page.getByRole('heading', { level: 2, name: /No loadouts match .zzqqxx-pass128./ })).toBeVisible({ timeout: 20_000 });
  });
});

test.describe('pass128 · Changelog (real jar)', () => {
  test('a skip link jumps past the top nav into the changelog', async ({ page }) => {
    await page.goto(`${base}/changelog.html`);
    await page.keyboard.press('Tab');
    const skip = page.getByRole('link', { name: /Skip to main content/ });
    await expect(skip).toBeFocused();
    await page.keyboard.press('Enter');
    await page.keyboard.press('Tab');
    await expect(page.getByRole('link', { name: /Back to SkinBox/ })).toBeFocused();
  });
});
