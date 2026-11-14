// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 124.

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

// Pass 124: the stall page's "Couldn't load this stall" Retry reloaded the
// whole page (offline that lost the app, and focus fell to <body>), unlike
// the item page's in-place Retry; the item page "sold this month" chip, the
// price chart's busiest-day tick and its summary, and the stall's capped
// "Sales · last 30 days" count printed raw numbers (1234) beside grouped ones
// (1,234 sold, 1,234 views, the stall's 1,234 sales stat).

const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';
const consent = (page) => page.addInitScript(() => {
  try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {}
});
const VIEWPORTS = [{ width: 1440, height: 900 }, { width: 390, height: 844 }];

test.describe('Stall load error Retry runs in place (real app)', () => {
  for (const vp of VIEWPORTS) {
    test(`Retry reloads the stall without a page reload and keeps focus at ${vp.width}px`, async ({ page }) => {
      await page.setViewportSize(vp);
      await consent(page);
      let failing = true, calls = 0;
      await page.route('**/api/listings/stall/2', (r) => {
        calls++;
        return failing ? r.fulfill(json({ error: 'boom' }, 500)) : r.continue();
      });
      await page.goto(`${base}/stall/2`);
      const retry = page.getByRole('alert').getByRole('button', { name: 'Retry' });
      await expect(page.getByRole('heading', { name: /Couldn.t load this stall/ })).toBeVisible();
      await page.evaluate(() => { window.__noReload = true; });

      // Still failing: the panel stays, focus stays on Retry, no reload.
      const before = calls;
      await retry.focus();
      await page.keyboard.press('Enter');
      await expect.poll(() => calls).toBeGreaterThan(before);
      await expect(retry).toBeFocused();
      expect(await page.evaluate(() => window.__noReload)).toBe(true);

      // Back online: the stall draws in place and focus lands inside it.
      failing = false;
      await page.keyboard.press('Enter');
      await expect(page.getByRole('heading', { level: 1, name: /AtlasTrades/ })).toBeVisible();
      expect(await page.evaluate(() => window.__noReload)).toBe(true);
      await expect.poll(() => page.evaluate(() => {
        const a = document.activeElement;
        return a !== document.body && !!a.closest('#main');
      })).toBe(true);
    });
  }
});

test.describe('Sales counts are grouped like the counts beside them', () => {
  test('item page velocity chip reads 1,234 sold this month (real app)', async ({ page }) => {
    await consent(page);
    await page.route('**/api/items/1/velocity', (r) => r.fulfill(json({
      soldLast24h: 0, soldLast7d: 0, soldLast30d: 1234, volumeLast30d: 1234, lastSoldPrice: null, lastSoldAt: null })));
    await page.goto(`${base}/item/1`);
    await expect(page.locator('.modal-demand-chip').filter({ hasText: /sold this month/ })).toHaveText(/^1,234 sold this month$/);
  });

  test('price chart busiest-day tick and summary group the trade count (real app)', async ({ page }) => {
    await consent(page);
    await page.route('**/api/items/1/history**', (r) => {
      const now = Date.now();
      const rows = Array.from({ length: 10 }, (_, i) => ({ id: i + 1, price: 1 + i / 10, volume: i === 9 ? 1500 : 2,
        recordedAt: now - (9 - i) * 86400e3, dayLabel: `Nov ${i + 1}, 2026` }));
      return r.fulfill(json(rows));
    });
    await page.goto(`${base}/item/1`);
    const chart = page.locator('.chart-wrap');
    await chart.scrollIntoViewIfNeeded();
    await expect(chart.getByText(/trades$/)).toHaveText('1,500 trades');
    await expect(chart.getByRole('img')).toHaveAccessibleName(/ 1,518 sales on SkinBox in this range\./);
  });

  test('stall Sales · last 30 days groups a capped count (real app)', async ({ page }) => {
    await consent(page);
    await page.route('**/api/listings/stall/2', async (r) => {
      const resp = await r.fetch();
      const body = await resp.json();
      body.seller = { ...body.seller, soldLast30d: 2500, soldLast7d: 2500, soldLast24h: 0 };
      return r.fulfill(json(body));
    });
    await page.route('**/api/listings/stall/2/recent-sales**', (r) => {
      const now = Date.now();
      return r.fulfill(json(Array.from({ length: 200 }, (_, i) => ({ listingId: 9000 + i, itemId: 1, itemName: 'Hat', price: 1,
        soldAt: now - (i % 20) * 86400e3, listingType: 'BUY_NOW' }))));
    });
    await page.goto(`${base}/stall/2`);
    // The stall stat already groups it; the sales strip now agrees.
    await expect(page.locator('.stall-stat').filter({ hasText: 'Last 7d' })).toContainText('2,500');
    await expect(page.locator('.stall-recent-sales .stall-reviews-head').filter({ hasText: /^Sales · last 30 days/ })).toContainText(/^Sales · last 30 days\s*2,500 sales · /);
  });
});
