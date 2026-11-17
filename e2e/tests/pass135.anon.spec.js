// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 135.

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

// Defect pass 135: the stall rating and the star breakdowns read with a
// scale, review rows wrap on a phone instead of a long name running under
// the date, the Recent searches list holds only options named by their
// search, the item-page price chart can be read with the keyboard, and an
// IME Enter in a message box no longer sends a half-typed message.

const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';
const NOW = Date.now();

async function consent(page) {
  await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
}
// Real-jar tests never write: every POST/PUT/DELETE/PATCH is answered here.
async function noWrites(page) {
  await page.route('**/api/**', (r) => (['POST', 'PUT', 'DELETE', 'PATCH'].includes(r.request().method())
    ? r.fulfill(json({}))
    : r.fallback()));
}
const reviews = (names) => names.map((n, i) => ({
  id: 13500 + i, rating: 5 - i, comment: 'Smooth trade ' + i, fromDisplayName: n,
  createdAt: NOW - (i + 1) * 86_400_000, editedAt: NOW, itemName: 'Police Hat', helpfulCount: i,
}));
// The real stall 1, with a rating summary in place of its empty one.
async function stallWithReviews(page, names) {
  const stall = await (await page.request.get(`${base}/api/listings/stall/1`)).json();
  stall.rating = { count: names.length, average: 4.0, averageRaw: 4.0, histogram: [1, 1, 1, 0, 0] };
  await page.route(/\/api\/listings\/stall\/1(\?.*)?$/, (r) => r.fulfill(json(stall)));
  await page.route(/\/api\/reviews\/user\/1(\?.*)?$/, (r) => r.fulfill(json(reviews(names))));
}
// Any two children of a review row's head drawn over each other. A box
// squeezed to 0px still paints its text (scrollWidth), so that counts.
const headOverlap = (page) => page.locator('.stall-review-head').first().evaluate((head) => {
  const kids = [...head.children].map((k) => {
    const b = k.getBoundingClientRect();
    return { left: b.left, right: b.left + Math.max(b.width, k.scrollWidth), top: b.top, bottom: b.bottom };
  });
  for (let i = 0; i < kids.length; i++) {
    for (let j = i + 1; j < kids.length; j++) {
      const a = kids[i], b = kids[j];
      if (a.left < b.right - 1 && b.left < a.right - 1 && a.top < b.bottom - 1 && b.top < a.bottom - 1) return true;
    }
  }
  return false;
});
const LONG = ['xX_DragonSlayer_2009_Xx', 'Night_Owl_Trader_4521', 'Pixel_Collector_Prime'];

test.describe('pass135 · stall rating read with its scale (real jar)', () => {
  test('the header rating reads "4.0 out of 5 stars" and each breakdown row reads "N stars: M reviews"', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await stallWithReviews(page, ['Rev A', 'Rev B', 'Rev C']);
    await page.goto(`${base}/stall/1`);
    await expect(page.getByRole('img', { name: '4.0 out of 5 stars' })).toBeVisible({ timeout: 20_000 });
    await expect(page.locator('.rating-breakdown').getByText('5 stars: 1 review', { exact: true })).toHaveCount(1);
    await expect(page.locator('.rating-breakdown').getByText('1 star: 0 reviews', { exact: true })).toHaveCount(1);
  });
});

test.describe('pass135 · Profile > Reviews breakdown (stubbed)', () => {
  test('each breakdown row reads "N stars: M reviews", not "5★ 2"', async ({ page }) => {
    await consent(page);
    await signedIn(page);
    await page.route(/\/api\/reviews\/user\/3(\?.*)?$/, (r) => r.fulfill(json(reviews(['Rev A', 'Rev B', 'Rev C']))));
    await page.route('**/api/reviews/user/3/summary', (r) => r.fulfill(json({ count: 3, average: 4, histogram: [2, 1, 0, 0, 0] })));
    await page.goto('/profile/reviews');
    const breakdown = page.locator('.rating-breakdown');
    await expect(breakdown.getByText('5 stars: 2 reviews', { exact: true })).toHaveCount(1, { timeout: 15_000 });
    await expect(breakdown.getByText('4 stars: 1 review', { exact: true })).toHaveCount(1);
  });
});

test.describe('pass135 · review rows on a phone', () => {
  test.use({ viewport: { width: 390, height: 844 } });

  test('stall: a long reviewer name does not run under the review date (real jar)', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await stallWithReviews(page, LONG);
    await page.goto(`${base}/stall/1`);
    const row = page.locator('.stall-review').first();
    await row.scrollIntoViewIfNeeded({ timeout: 20_000 });
    await expect(row.getByText(LONG[0])).toBeVisible();
    expect(await headOverlap(page)).toBe(false);
  });

  test('Profile > Reviews: a long reviewer name does not run under the review date (stubbed)', async ({ page }) => {
    await consent(page);
    await signedIn(page);
    await page.route(/\/api\/reviews\/user\/3(\?.*)?$/, (r) => r.fulfill(json(reviews(LONG))));
    await page.route('**/api/reviews/user/3/summary', (r) => r.fulfill(json({ count: 3, average: 4, histogram: [1, 1, 1, 0, 0] })));
    await page.goto('/profile/reviews');
    const row = page.locator('.stall-review').first();
    await row.scrollIntoViewIfNeeded({ timeout: 15_000 });
    await expect(row.getByText(LONG[0])).toBeVisible();
    expect(await headOverlap(page)).toBe(false);
  });
});

test.describe('pass135 · market Recent searches list (real jar)', () => {
  test('the list holds only options, each named by its search, with the heading and Clear all outside it', async ({ page }) => {
    await consent(page);
    await page.addInitScript(() => { try { localStorage.setItem('sb_recent_searches', JSON.stringify(['boot', 'hat'])); } catch (_) {} });
    await noWrites(page);
    await page.goto(`${base}/market`);
    const box = page.getByRole('combobox', { name: 'Search listings' });
    await box.click();
    const list = page.getByRole('listbox', { name: 'Recent searches' });
    await expect(list).toBeVisible();
    await expect(list.getByRole('option', { name: 'boot', exact: true })).toBeVisible();
    await expect(list.getByRole('option', { name: 'hat', exact: true })).toBeVisible();
    await expect(list.getByRole('button', { name: 'Clear all' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Clear all' })).toBeVisible();
    // Arrow keys still walk the options.
    await box.press('ArrowDown');
    await expect(box).toHaveAttribute('aria-activedescendant', 'search-recent-opt-0');
    await expect(page.locator('#search-recent-opt-0')).toHaveAttribute('aria-selected', 'true');
  });
});

test.describe('pass135 · item page price chart by keyboard (real jar)', () => {
  test('Tab reaches the chart and the arrow keys show each day.s price in the tooltip', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    const hist = await (await page.request.get(`${base}/api/items/5/history`)).json();
    expect(hist.length).toBeGreaterThan(3);
    await page.goto(`${base}/item/5`);
    const all = page.getByRole('button', { name: 'Show ALL price history' });
    await all.scrollIntoViewIfNeeded({ timeout: 20_000 });
    await all.click();
    await page.keyboard.press('Tab');
    const chart = page.locator('.chart-wrap svg.chart');
    await expect(chart).toBeFocused();
    const tip = page.locator('.chart-wrap .sparkline-tooltip');
    await expect(tip).toContainText(hist[hist.length - 1].dayLabel);
    await page.keyboard.press('ArrowLeft');
    await expect(tip).toContainText(hist[hist.length - 2].dayLabel);
    await page.keyboard.press('Home');
    await expect(tip).toContainText(hist[0].dayLabel);
    await expect(page.getByRole('status').filter({ hasText: hist[0].dayLabel })).toHaveCount(1);
    await page.keyboard.press('Shift+Tab');
    await expect(tip).toHaveCount(0);
  });
});

// Enter that ends an IME composition (picking a Japanese word) must not send.
const composingEnter = (locator) => locator.evaluate((el) => el.dispatchEvent(new KeyboardEvent('keydown', {
  key: 'Enter', code: 'Enter', keyCode: 229, isComposing: true, bubbles: true, cancelable: true,
})));

test.describe('pass135 · IME Enter in message boxes (stubbed)', () => {
  test('Support thread: an IME Enter does not send the half-typed reply; a plain Enter does', async ({ page }) => {
    await consent(page);
    await signedIn(page);
    const T = { id: 41, subject: 'Trade stuck', category: 'TRADE', status: 'WAITING_USER', updatedAt: NOW - 3600e3, createdAt: NOW - 7200e3 };
    let posts = 0;
    await page.route(/\/api\/support\/tickets(\?.*)?$/, (r) => r.fulfill(json([T])));
    await page.route('**/api/support/tickets/41', (r) => r.fulfill(json({ ticket: T, messages: [
      { id: 1, author: 'USER', authorName: ME.displayName, body: 'Where is my hat?', createdAt: NOW - 7200e3 },
      { id: 2, author: 'STAFF', authorName: 'Clara (auto)', body: 'Looking into it.', createdAt: NOW - 7199e3 },
    ] })));
    await page.route('**/api/support/tickets/41/reply', (r) => { posts += 1; return r.fulfill(json({ id: 3 })); });
    await page.goto('/support');
    await page.getByRole('button', { name: /Open ticket #41/ }).click();
    const box = page.getByRole('textbox', { name: 'Reply to support' });
    await box.fill('kon');
    await composingEnter(box);
    await page.waitForTimeout(500);
    expect(posts).toBe(0);
    await expect(box).toHaveValue('kon');
    await box.press('Enter');
    await expect.poll(() => posts).toBe(1);
  });

  test('Profile > Trades chat: an IME Enter does not send the half-typed message; a plain Enter does', async ({ page }) => {
    await consent(page);
    await signedIn(page);
    const trade = { id: 501, state: 'PENDING_SELLER_SEND', sellerUserId: ME.id, buyerUserId: 7, counterpartyName: 'dave',
      itemName: 'Police Hat', price: 4.23, listingId: 76, createdAt: NOW - 3600e3, updatedAt: NOW };
    let posts = 0;
    await page.route('**/api/trades', (r) => r.fulfill(json([trade])));
    await page.route('**/api/trades/501/messages', (r) => {
      if (r.request().method() === 'POST') { posts += 1; return r.fulfill(json({ id: 2 })); }
      return r.fulfill(json([]));
    });
    await page.goto('/profile/trades');
    await page.getByRole('button', { name: /^Chat about/ }).click();
    const box = page.getByRole('textbox', { name: 'Message to your trade partner' });
    await box.fill('kon');
    await composingEnter(box);
    await page.waitForTimeout(500);
    expect(posts).toBe(0);
    await box.press('Enter');
    await expect.poll(() => posts).toBe(1);
  });
});
