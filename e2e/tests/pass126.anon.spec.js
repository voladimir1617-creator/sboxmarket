// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 126.

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

// Pass 126: the stall's listing filter, sort and name search were lost on
// Back from a listing (and on a stall Retry); the API reference's example
// request was a nameless scroller a keyboard could not reach outside
// Chromium, and on a phone the route tables dropped their column headers,
// leaving a bare "RO"/"RW"; Profile > Support rows on a phone showed
// "Other", "Waiting you" and "Just now" with no word for what each was.

const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';
const consent = (page) => page.addInitScript(() => {
  try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {}
});

test.describe('Stall listing filters survive Back from a listing', () => {
  test('rarity chip, sort and name search come back after Back (real app)', async ({ page }) => {
    await consent(page);
    await page.goto(`${base}/stall/2`);
    const sort = page.getByRole('combobox', { name: 'Sort stall listings' });
    await sort.selectOption('price_desc');
    await page.getByRole('button', { name: /^Limited · \d+/ }).click();
    const box = page.getByRole('searchbox', { name: 'Filter stall by item name or category' });
    await box.fill('a');
    const first = page.locator('.listing-grid a[href*="/item/"]').first();
    await first.click();
    await expect(page).toHaveURL(/\/item\/\d+/);
    await page.goBack();
    await expect(sort).toHaveValue('price_desc');
    await expect(box).toHaveValue('a');
    await expect(page.getByRole('button', { name: /^Limited · \d+/ })).toHaveAttribute('aria-pressed', 'true');
  });

  test('the stall filters stay out of the market search (real app)', async ({ page }) => {
    await consent(page);
    await page.goto(`${base}/stall/2?name=a&filter=Limited&order=price_desc`);
    await expect(page.getByRole('searchbox', { name: 'Filter stall by item name or category' })).toHaveValue('a');
    await page.locator('a[href="/market"]').first().click();
    await expect(page).toHaveURL(/\/market$/);
    await expect(page.locator('.search-input').first()).toHaveValue('');
  });

  test('a stall URL with a rarity the stall does not carry still shows its listings (real app)', async ({ page }) => {
    await consent(page);
    await page.goto(`${base}/stall/2?filter=Nope`);
    await expect(page.getByRole('button', { name: /^All · \d+/ })).toHaveAttribute('aria-pressed', 'true');
    await expect(page.locator('.listing-grid a[href*="/item/"]').first()).toBeVisible();
  });
});

test.describe('API reference on a keyboard and a phone', () => {
  test('the example request is a named, focusable region (real app)', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(`${base}/api-reference.html`);
    const ex = page.getByRole('region', { name: 'Example request' });
    await expect(ex).toHaveAttribute('tabindex', '0');
    await ex.focus();
    await expect(ex).toBeFocused();
    expect(await ex.evaluate((el) => el.scrollWidth > el.clientWidth)).toBe(true);
    await page.keyboard.press('ArrowRight');
    await expect.poll(() => ex.evaluate((el) => el.scrollLeft)).toBeGreaterThan(0);
  });

  test('route tables keep their column headers on a phone (real app)', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(`${base}/api-reference.html`);
    const tables = page.locator('table.routes');
    const n = await tables.count();
    expect(n).toBeGreaterThan(0);
    for (let i = 0; i < n; i++) {
      await expect(tables.nth(i).getByRole('columnheader', { name: 'Key', exact: true })).toHaveCount(1);
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBeLessThanOrEqual(0);
  });
});

test.describe('Profile > Support rows on a phone', () => {
  test('category, status and updated time are labelled (stubbed)', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await consent(page);
    await signedIn(page);
    await page.route('**/api/support/tickets', (r) => r.fulfill(json([
      { id: 126, subject: 'Where is my hat', category: 'OTHER', status: 'WAITING_USER', createdAt: Date.now(), updatedAt: Date.now() },
    ])));
    await page.goto('/profile/support');
    const row = page.getByRole('button', { name: /Open ticket #126/ });
    await expect(row).toBeVisible();
    const labels = await row.locator('td').evaluateAll((tds) => tds
      .filter((td) => getComputedStyle(td).display !== 'none')
      .map((td) => (getComputedStyle(td, '::before').content.replace(/^"|"$/g, '').replace(/^none$/, '') + td.textContent).trim()));
    expect(labels).toContain('Category · Other');
    expect(labels).toContain('Status · Waiting you');
    expect(labels.some((t) => /^Updated · /.test(t))).toBe(true);
  });
});
