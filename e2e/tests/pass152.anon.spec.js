// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 152.

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


// Defect pass 152: market card and table thumbnails repeat the item name,
// the table Trend cell is glyph-only, the card watcher chip reads a bare
// number, and the stall filters change the grid without saying how many
// listings are left.

const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';

async function consent(page) {
  await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
}

// Every write a real-jar test could reach is refused, so nothing on the
// server changes.
async function noWrites(page) {
  await page.route('**/api/**', (r) => (r.request().method() === 'GET' ? r.fallback() : r.fulfill(json({ error: 'blocked in test' }, 400))));
}

async function tableView(page) {
  await page.addInitScript(() => { try { localStorage.setItem('sb_market_view', 'table'); } catch (_) {} });
}

test.describe('pass152k · market table rows (real jar)', () => {
  test('table: the thumbnail does not repeat the item name beside its link', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await tableView(page);
    await page.goto(`${base}/market`);
    const table = page.getByRole('table', { name: 'Market listings' });
    await expect(table).toBeVisible({ timeout: 20_000 });
    const row = table.getByRole('rowgroup').nth(1).getByRole('row').first();
    const link = row.getByRole('link').first();
    await expect(link).toBeVisible();
    const name = (await link.textContent()) || '';
    expect(name.length).toBeGreaterThan(0);
    await expect(row.getByRole('img', { name, exact: true })).toHaveCount(0);
    const esc = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    await expect(row.getByRole('cell').first()).not.toHaveAccessibleName(new RegExp('^' + esc + ' ' + esc));
  });

  test('table: the Trend cell says the direction in words, not a bare glyph', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await tableView(page);
    await page.goto(`${base}/market`);
    const table = page.getByRole('table', { name: 'Market listings' });
    await expect(table).toBeVisible({ timeout: 20_000 });
    const row = table.getByRole('rowgroup').nth(1).getByRole('row').first();
    // Column 4 is Trend.
    await expect(row.getByRole('cell').nth(3).getByRole('img')).toHaveAccessibleName(/^(No change|Up \d+(\.\d+)?%|Down \d+(\.\d+)?%)$/);
  });
});

test.describe('pass152k · market cards (real jar)', () => {
  test('stall grid: card thumbnails are decorative beside the named card link', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await page.goto(`${base}/stall/2`);
    const grid = page.locator('.listing-grid').first();
    const link = grid.getByRole('link', { name: /^Black Modern Watch, buy now, / });
    await expect(link).toBeVisible({ timeout: 20_000 });
    await expect(grid.getByRole('img', { name: 'Black Modern Watch', exact: true })).toHaveCount(0);
    await expect(grid.getByRole('img', { name: 'Denim Jeans', exact: true })).toHaveCount(0);
  });
});

test.describe('pass152k · stall filters say how many listings show (real jar)', () => {
  test('stall: the name filter and rarity chips update a "N listings shown" status', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await page.goto(`${base}/stall/2`);
    const all = page.getByRole('button', { name: /^All · 12$/ });
    await expect(all).toBeVisible({ timeout: 20_000 });
    const status = page.getByRole('status').filter({ hasText: /listings? shown$/ });
    await expect(status).toHaveText('12 listings shown');
    await page.getByRole('button', { name: /^Limited · 5$/ }).click();
    await expect(status).toHaveText('5 listings shown');
    await page.getByRole('button', { name: /^All · 12$/ }).click();
    const box = page.getByRole('searchbox', { name: 'Filter stall by item name or category' });
    await box.fill('police');
    await expect(status).toHaveText('1 listing shown');
    await expect(page.locator('.listing-grid .grid-card-link')).toHaveCount(1);
    await box.fill('zzqx');
    await expect(status).toHaveText('0 listings shown');
    await expect(page.getByRole('heading', { name: /No listings match .zzqx. in this stall\./ })).toBeVisible();
  });

  test.describe('phone', () => {
    test.use({ viewport: { width: 390, height: 844 } });
    test('stall (390px): the status is announced but takes no room', async ({ page }) => {
      await consent(page);
      await noWrites(page);
      await page.goto(`${base}/stall/2`);
      const status = page.getByRole('status').filter({ hasText: /listings? shown$/ });
      await expect(status).toHaveText('12 listings shown', { timeout: 20_000 });
      const box = await status.boundingBox();
      expect(box && box.width).toBeLessThanOrEqual(1);
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    });
  });
});

test.describe('pass152k · market cards and rows (stubbed)', () => {
  const mk = (i, extra) => ({ id: 3000 + i, price: 5 + i, sellerUserId: 9, sellerName: 'bob', status: 'ACTIVE', listingType: 'BUY_NOW', listedAt: Date.now() - 3600e3,
    item: { id: 700 + i, name: 'Stub Item ' + i, category: 'Hats', rarity: 'Standard', supply: 4, ...extra } });

  test('grid: a card without a picture keeps its poster glyph out of the reading order', async ({ page }) => {
    await signedIn(page);
    await consent(page);
    await page.route('**/api/listings?**', (r) => r.fulfill(json([mk(1), mk(2)])));
    await page.goto('/market');
    const link = page.getByRole('link', { name: /^Stub Item 1, buy now, / });
    await expect(link).toBeVisible({ timeout: 20_000 });
    await expect(page.locator('.listing-grid').getByRole('img', { name: 'Stub Item 1', exact: true })).toHaveCount(0);
    expect(ME.id).toBe(3);
  });

  test('table: rising and falling trends read "Up N%" and "Down N%"', async ({ page }) => {
    await signedIn(page);
    await consent(page);
    await tableView(page);
    await page.route('**/api/listings?**', (r) => r.fulfill(json([mk(1, { trendPercent: 12 }), mk(2, { trendPercent: -4 })])));
    await page.goto('/market');
    const table = page.getByRole('table', { name: 'Market listings' });
    await expect(table).toBeVisible({ timeout: 20_000 });
    await expect(table.getByRole('img', { name: 'Up 12%', exact: true })).toBeVisible();
    await expect(table.getByRole('img', { name: 'Down 4%', exact: true })).toBeVisible();
    await expect(table.getByRole('img', { name: 'Stub Item 1', exact: true })).toHaveCount(0);
  });
});
