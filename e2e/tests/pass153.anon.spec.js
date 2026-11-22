// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 153.

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


// Defect pass 153: the header Offers link is not marked on /offers, the
// footer link columns have no names, a price bound the market or Database
// filter can't read is dropped without a word, sorted columns don't say
// so, and "not measured yet" stats read as a bare dash.

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

test.describe('pass153k · top nav and footer (real jar)', () => {
  test('footer link columns are named navigation regions with headings', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await page.goto(`${base}/db`);
    const footer = page.getByRole('contentinfo');
    await footer.scrollIntoViewIfNeeded();
    for (const [name, link] of [['Marketplace', 'Item Database'], ['Account', 'Profile'], ['Resources', 'Help Center'], ['Legal', 'Terms of Service']]) {
      await expect(footer.getByRole('heading', { level: 2, name, exact: true })).toBeVisible({ timeout: 20_000 });
      await expect(footer.getByRole('navigation', { name, exact: true }).getByRole('link', { name: link, exact: true })).toBeVisible();
    }
  });
});

test.describe('pass153k · sorted columns say so (real jar)', () => {
  test('Database: the column the Sort select orders by carries aria-sort', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await page.goto(`${base}/db`);
    const table = page.getByRole('table', { name: /item database/ });
    await expect(table).toBeVisible({ timeout: 20_000 });
    await expect(table.getByRole('columnheader', { name: 'Supply', exact: true })).toHaveAttribute('aria-sort', 'ascending');
    await page.getByRole('combobox', { name: 'Sort catalogue by' }).selectOption('price_desc');
    await expect(table.getByRole('columnheader', { name: 'Floor', exact: true })).toHaveAttribute('aria-sort', 'descending');
    await expect(table.getByRole('columnheader', { name: 'Supply', exact: true })).not.toHaveAttribute('aria-sort', /.*/);
  });

  test('market table view: the Price column carries aria-sort for the default sort', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await page.addInitScript(() => { try { localStorage.setItem('sb_market_view', 'table'); } catch (_) {} });
    await page.goto(`${base}/market`);
    const table = page.getByRole('table', { name: 'Market listings' });
    await expect(table).toBeVisible({ timeout: 20_000 });
    await expect(table.getByRole('columnheader', { name: 'Price', exact: true })).toHaveAttribute('aria-sort', 'descending');
  });
});

test.describe('pass153k · stall Joined stat (stubbed)', () => {
  test('a seller with no join date reads "Unknown", not a bare dash', async ({ page }) => {
    await consent(page);
    await signedIn(page);
    await page.route('**/api/listings/stall/9**', (r) => r.fulfill(json({
      seller: { id: 9, displayName: 'Zed', completedTrades: 0, failedTrades: 0 },
      listings: [], count: 0, rating: { count: 0, histogram: [0, 0, 0, 0, 0] },
    })));
    await page.goto('/stall/9');
    const stat = page.locator('.stall-stat').filter({ hasText: 'Joined' });
    await expect(stat).toBeVisible({ timeout: 20_000 });
    await expect(stat).toContainText('—');
    await expect(stat).toContainText(/Unknown/);
    expect(ME.id).not.toBe(9);
  });
});
