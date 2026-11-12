// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 115.

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

// Defect pass 115: front page on a failed listings load, Loadout Back and
// image fallbacks, the email verification code box, the Database stale page.

const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';

async function consent(page) {
  await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
}

test.describe('pass115 · front page when the listings request fails', () => {
  for (const [label, vp] of [['desktop', { width: 1440, height: 900 }], ['phone', { width: 390, height: 844 }]]) {
    test(`home (${label}): a 500 on /api/listings shows an alert with Retry, and Retry brings the cards`, async ({ page }) => {
      await page.setViewportSize(vp);
      await consent(page);
      let fail = true;
      await page.route('**/api/listings?**', (r) => (fail ? r.fulfill(json({ error: 'boom' }, 500)) : r.continue()));
      await page.goto(`${base}/`);
      const alert = page.getByRole('alert').filter({ hasText: /Couldn.t load listings/ });
      await alert.scrollIntoViewIfNeeded({ timeout: 20_000 });
      await expect(alert).toBeVisible();
      const retry = alert.getByRole('button', { name: 'Retry' });
      await expect(retry).toBeInViewport();
      fail = false;
      await retry.click();
      await expect(alert).toHaveCount(0);
      await expect(page.locator('.csfloat-home-preview-row a[href^="/item/"], .csfloat-home-preview-row [href^="/item/"]').first()).toBeAttached({ timeout: 20_000 });
    });
  }

  test('home: an aborted /api/listings request is not a blank page either', async ({ page }) => {
    await consent(page);
    await page.route('**/api/listings?**', (r) => r.abort());
    await page.goto(`${base}/`);
    await expect(page.getByRole('heading', { name: /Couldn.t load listings/ })).toBeAttached({ timeout: 20_000 });
  });
});

test.describe('pass115 · market when the listings request is dropped', () => {
  test('market: an aborted /api/listings request does not say the marketplace is reachable', async ({ page }) => {
    await consent(page);
    await page.route('**/api/listings?**', (r) => r.abort('internetdisconnected'));
    await page.goto(`${base}/market`);
    const alert = page.getByRole('alert').filter({ hasText: /Couldn.t load listings/ });
    await expect(alert).toBeVisible({ timeout: 20_000 });
    await expect(alert).not.toContainText(/reachable/i);
    await expect(alert).toContainText(/connection/i);
    await expect(alert.getByRole('button', { name: 'Retry' })).toBeVisible();
  });
});

test.describe('pass115 · Loadout Lab as a signed-out visitor', () => {
  test('Back from a loadout opened from a Discover search returns to that search', async ({ page }) => {
    await consent(page);
    await page.goto(`${base}/loadout?q=plague`);
    const card = page.getByRole('link', { name: /^Open loadout Plague Doctor/ });
    await expect(page.locator('.loadout-card')).toHaveCount(1, { timeout: 20_000 });
    await card.click();
    await expect(page).toHaveURL(/\/loadout\/\d+$/);
    await page.getByRole('button', { name: '← Back' }).click();
    await expect(page).toHaveURL(/\/loadout\?q=plague$/);
    await expect(page.getByPlaceholder(/Search loadouts/)).toHaveValue('plague');
    await expect(page.locator('.loadout-card')).toHaveCount(1);
  });

  test('a loadout slot whose image fails shows the item emoji, not an empty box', async ({ page }) => {
    await consent(page);
    await page.route('**/economy/image/**', (r) => r.fulfill({ status: 404, body: '' }));
    const res = await page.request.get(`${base}/api/loadouts/1`);
    const body = await res.json();
    const withImg = (body.slots || []).filter((s) => s.itemImageUrl);
    expect(withImg.length).toBeGreaterThan(0);
    await page.goto(`${base}/loadout/1`);
    await expect(page.locator('.loadout-slot-filled')).toHaveCount((body.slots || []).filter((s) => s.itemId).length, { timeout: 20_000 });
    // Every filled slot shows something: a loaded image or the emoji glyph.
    await expect(page.locator('.loadout-slot-emoji')).toHaveCount((body.slots || []).filter((s) => s.itemId).length);
    await expect(page.locator('.loadout-slot-img')).toHaveCount(0);
  });

  test('a Discover card preview tile whose image fails shows the emoji', async ({ page }) => {
    await consent(page);
    await page.route('**/economy/image/**', (r) => r.fulfill({ status: 404, body: '' }));
    await page.goto(`${base}/loadout`);
    const first = page.locator('.loadout-card').first();
    await expect(first).toBeVisible({ timeout: 20_000 });
    const tiles = first.locator('.loadout-card-preview-tile');
    const n = await tiles.count();
    expect(n).toBeGreaterThan(0);
    await expect(first.locator('.loadout-card-preview-emoji')).toHaveCount(n);
  });
});

test.describe('pass115 · Profile email verification code field', () => {
  test('the code box takes the lower-case code as typed (no auto-capital, autocorrect or spellcheck) and offers one-time-code autofill', async ({ page }) => {
    await consent(page);
    await page.request.get(`${base}/api/auth/steam/dev-login?userId=4&next=/`);
    await page.goto(`${base}/profile`);
    const edit = page.getByRole('button', { name: 'Edit email address' });
    await edit.scrollIntoViewIfNeeded({ timeout: 20_000 });
    const code = page.getByRole('textbox', { name: 'Email verification code' });
    // The box shows whenever the address is unverified; give the user one first if needed.
    if (await code.count() === 0) {
      await edit.click();
      await page.getByRole('textbox', { name: 'Email address' }).fill('pass115@example.com');
      await page.getByRole('button', { name: 'Save', exact: true }).click();
    }
    await expect(code).toBeVisible({ timeout: 20_000 });
    await expect(code).toHaveAttribute('autocomplete', 'one-time-code');
    await expect(code).toHaveAttribute('autocapitalize', 'off');
    await expect(code).toHaveAttribute('spellcheck', 'false');
  });
});

test.describe('pass115 · Database page URL state', () => {
  test('a ?page= past the end of an empty result goes back to page 1', async ({ page }) => {
    await consent(page);
    await page.goto(`${base}/db?q=zzzzqq&page=3`);
    await expect(page.getByText(/No items match your filters/)).toBeVisible({ timeout: 20_000 });
    await expect(page.getByRole('status').filter({ hasText: /results? · page/ })).toHaveText(/0\s*results · page\s*1\s*\/\s*1/);
    await expect(page).not.toHaveURL(/page=3/);
  });

  test('stubbed: an empty filtered result on page 3 reads page 1 / 1', async ({ page }) => {
    await page.route('**/api/**', (r) => r.fulfill(json({})));
    await page.route('**/api/auth/steam/me', (r) => r.fulfill(json({ signedIn: false })));
    await page.route('**/api/database?**', (r) => r.fulfill(json({ items: [], total: 0, indexed: 39 })));
    await page.goto('/db?category=Boots&page=3');
    await expect(page.getByText(/No items match your filters/)).toBeVisible({ timeout: 20_000 });
    await expect(page.locator('.db-meta')).toHaveText(/0\s*results · page\s*1\s*\/\s*1/);
  });
});
