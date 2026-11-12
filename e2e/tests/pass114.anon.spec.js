// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 114.

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


// Defect pass 114.

const JAR = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';
// Static copy under test (original or fixed); real-jar tests take only its
// page assets, while /api goes to the real jar.
const STATIC = process.env.BASE_URL;

async function prep(page) {
  await page.addInitScript(() => {
    try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {}
    window.confirm = () => true;
  });
  await page.route(/^https?:\/\/(?!localhost|127\.0\.0\.1)/, (r) => r.abort());
}
async function devLogin(page, userId) {
  await page.request.get(`${JAR}/api/auth/steam/dev-login?userId=${userId}&next=/`);
}
async function useStaticAssets(page) {
  if (!STATIC) return;
  await page.route(`${JAR}/**`, async (r) => {
    const u = new URL(r.request().url());
    if (u.pathname.startsWith('/api/')) return r.continue();
    const res = await page.request.fetch(STATIC + u.pathname + u.search);
    await r.fulfill({ response: res });
  });
}

const RARITIES = ['Standard', 'Rare', 'Off-Market', 'Limited'];
const steamItems = (n) => Array.from({ length: n }, (_, i) => ({
  assetId: 'a' + i, name: 'Pass114 Item ' + i, rarity: RARITIES[i % 4], category: 'Hats',
  tradable: true, catalogueId: i + 1, suggestedPrice: '1.50', quantity: 1, listableQuantity: 1, listedCount: 0
}));
async function sellPage(page, items) {
  await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
  await signedIn(page);
  await page.route('**/api/steam/inventory', (r) => r.fulfill(json({ items, count: items.length })));
  await page.route('**/api/listings/inventory', (r) => r.fulfill(json([])));
  await page.goto('/sell');
}

test.describe('Market cards (real jar)', () => {
  test('Ctrl-click on a card\'s View magnifier opens the item in a new tab, like the name link', async ({ page, context }) => {
    await prep(page);
    await useStaticAssets(page);
    await page.goto(`${JAR}/market`);
    const card = page.locator('.grid-card').first();
    await card.hover({ timeout: 20_000 });
    const mag = card.getByRole('button', { name: /^View / });
    const popup = context.waitForEvent('page', { timeout: 5000 });
    await mag.click({ modifiers: ['Control'] });
    const tab = await popup;
    await expect(tab).toHaveURL(/\/item\/\d+/);
    await expect(page).toHaveURL(/\/market/);
  });
});

test.describe('Profile overview (real jar)', () => {
  test('the Account Standing gauge tells screen readers which level the account is at', async ({ page }) => {
    await prep(page);
    await useStaticAssets(page);
    await devLogin(page, 2);
    await page.goto(`${JAR}/profile`);
    const gauge = page.getByTestId('account-standing');
    await expect(gauge).not.toHaveAttribute('aria-busy', 'true', { timeout: 20_000 });
    const current = gauge.locator('[aria-current="step"]');
    await expect(current).toHaveCount(1);
    await expect(current).toHaveText(/(Excellent|Good|Poor|At Risk|Banned)$/);
  });
});

test.describe('Item page Similar offers rail (real jar)', () => {
  test('a rail with more cards than fit shows a scrollbar, so a mouse can reach the rest', async ({ page }) => {
    await prep(page);
    await useStaticAssets(page);
    await page.goto(`${JAR}/item/22`);
    const strip = page.locator('[data-testid="similar-offers"] .similar-strip');
    await strip.scrollIntoViewIfNeeded({ timeout: 20_000 });
    // Headless Chromium draws overlay scrollbars, so check the rule that
    // hides it: with classic (Windows) scrollbars a wheel mouse has no other way past card 5.
    const m = await strip.evaluate((el) => ({ over: el.scrollWidth > el.clientWidth + 5, bar: getComputedStyle(el).scrollbarWidth }));
    expect(m.over).toBe(true);
    expect(m.bar).not.toBe('none');
  });

  test('at 200% zoom the rail still offers a scrollbar for the cards past the edge', async ({ browser }) => {
    const ctx = await browser.newContext({ viewport: { width: 640, height: 450 }, deviceScaleFactor: 2 });
    const page = await ctx.newPage();
    await prep(page);
    await useStaticAssets(page);
    await page.goto(`${JAR}/item/22`);
    const strip = page.locator('[data-testid="similar-offers"] .similar-strip');
    await strip.scrollIntoViewIfNeeded({ timeout: 20_000 });
    await expect(strip).toBeInViewport();
    const m = await strip.evaluate((el) => ({ over: el.scrollWidth > el.clientWidth + 5, bar: getComputedStyle(el).scrollbarWidth }));
    expect(m.over).toBe(true);
    expect(m.bar).not.toBe('none');
    await ctx.close();
  });
});

test.describe('Sell page item picker on a phone', () => {
  test.use({ viewport: { width: 390, height: 844 } });

  test('Clear on the Steam inventory filter keeps focus in the search box', async ({ page }) => {
    await sellPage(page, steamItems(10));
    const search = page.getByRole('textbox', { name: 'Search your inventory' });
    await search.fill('zzz', { timeout: 20_000 });
    await expect(page.getByText(/No Steam items match this filter/)).toBeVisible();
    const clear = page.getByRole('button', { name: 'Clear', exact: true });
    await clear.focus();
    await page.keyboard.press('Enter');
    await expect(clear).toHaveCount(0);
    await expect(search).toBeFocused();
    await expect(page.getByRole('button', { name: /^List Pass114 Item 0 for sale/ })).toBeVisible();
  });

  test('Clear on a rarity chip filter keeps focus in the picker', async ({ page }) => {
    await sellPage(page, steamItems(10));
    await page.getByRole('button', { name: /^Scarce · 2/ }).click({ timeout: 20_000 });
    const clear = page.getByRole('button', { name: 'Clear', exact: true });
    await clear.focus();
    await page.keyboard.press('Enter');
    await expect(clear).toHaveCount(0);
    await expect(page.getByRole('textbox', { name: 'Search your inventory' })).toBeFocused();
  });

  test('Clear on the bulk-select bar keeps focus in the picker', async ({ page }) => {
    await sellPage(page, steamItems(10));
    await page.locator('label.sell-bulk-check').nth(1).click({ timeout: 20_000 });
    await expect(page.getByText(/selected for bulk list/)).toBeVisible();
    const clear = page.locator('.sell-bulk-bar').getByRole('button', { name: 'Clear', exact: true });
    await clear.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByText(/selected for bulk list/)).toHaveCount(0);
    await expect(page.getByRole('textbox', { name: 'Search your inventory' })).toBeFocused();
  });

  test('Clear on the Platform inventory filter keeps focus in its filter box', async ({ page }) => {
    await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
    await signedIn(page);
    const rows = Array.from({ length: 8 }, (_, i) => ({ id: 900 + i, status: 'INVENTORY', price: '1.00',
      item: { id: i + 1, name: 'Pass114 Owned ' + i, rarity: RARITIES[i % 4], category: 'Hats', lowestPrice: 1.2 } }));
    await page.route('**/api/steam/inventory', (r) => r.fulfill(json({ items: [], count: 0 })));
    await page.route('**/api/listings/inventory', (r) => r.fulfill(json(rows)));
    await page.goto('/sell');
    await page.getByRole('button', { name: /^Platform Inventory/ }).click({ timeout: 20_000 });
    const box = page.getByRole('textbox', { name: 'Filter your inventory' });
    await box.fill('zzz');
    await expect(page.getByText(/No platform-inventory items match this filter/)).toBeVisible();
    const clear = page.getByRole('button', { name: 'Clear', exact: true });
    await clear.focus();
    await page.keyboard.press('Enter');
    await expect(clear).toHaveCount(0);
    await expect(box).toBeFocused();
  });
});

test.describe('Offers page empty-state buttons', () => {
  const offers = Array.from({ length: 9 }, (_, i) => ({
    id: 700 + i, status: 'PENDING', amount: '1.00', listingId: 50 + i, itemName: 'Pass114 Offer ' + i,
    itemId: i + 1, buyerUserId: 9, buyerName: 'zed', sellerUserId: ME.id, sellerName: ME.displayName,
    createdAt: Date.now() - 60000, expiresAt: Date.now() + 86400000
  }));

  test('Clear search on a no-match offers search keeps focus in the search box', async ({ page }) => {
    await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
    await signedIn(page);
    await page.route('**/api/offers/incoming*', (r) => r.fulfill(json(offers)));
    await page.route('**/api/offers/outgoing*', (r) => r.fulfill(json([])));
    await page.goto('/offers');
    const search = page.getByRole('searchbox', { name: 'Search offers' });
    await search.fill('zzzz', { timeout: 20_000 });
    const clear = page.getByRole('button', { name: 'Clear search' });
    await clear.focus();
    await page.keyboard.press('Enter');
    await expect(clear).toHaveCount(0);
    await expect(search).toBeFocused();
  });
});

test.describe('Offers page status filter', () => {
  test('Show all offers on a filter emptied by a cancel keeps focus on the All chip', async ({ page }) => {
    await signedIn(page);
    await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
    let gone = false;
    const row = (i) => ({ id: 800 + i, status: gone ? 'CANCELLED' : 'PENDING', amount: '1.00', listingId: 60 + i, itemName: 'Pass114 Status ' + i,
      itemId: i + 1, buyerUserId: ME.id, buyerName: ME.displayName, sellerUserId: 9, sellerName: 'zed',
      createdAt: Date.now() - 60000, expiresAt: Date.now() + 86400000 });
    await page.route('**/api/offers/incoming*', (r) => r.fulfill(json([])));
    await page.route('**/api/offers/outgoing*', (r) => r.fulfill(json([4, 5, 6, 7].map(row))));
    await page.route(/\/api\/offers\/\d+$/, (r) => { gone = true; return r.fulfill(json({ ok: true })); });
    await page.goto('/offers/outgoing');
    await page.getByRole('button', { name: /^Pending · 4/ }).click({ timeout: 20_000 });
    await page.locator('.offer-list').getByRole('button', { name: /^Cancel/ }).first().click();
    const showAll = page.getByRole('button', { name: 'Show all offers' });
    await showAll.focus();
    await page.keyboard.press('Enter');
    await expect(showAll).toHaveCount(0);
    await expect(page.getByRole('button', { name: /^All · 4/ })).toBeFocused();
  });
});

test.describe('Notification bell', () => {
  test('a failed load offers Retry, and Retry brings the rows in with focus back on the bell', async ({ page }) => {
    await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
    await signedIn(page);
    let fail = true;
    await page.route('**/api/notifications/unread-count*', (r) => r.fulfill(json({ unread: 1 })));
    await page.route('**/api/notifications?*', (r) => fail
      ? r.fulfill(json({ error: 'down' }, 500))
      : r.fulfill(json({ items: [{ id: 41, kind: 'TRADE_OPENED', title: 'Pass114 trade opened', body: 'Send it', read: false, createdAt: Date.now() }], unread: 1 })));
    await page.goto('/market');
    const bell = page.getByRole('button', { name: /^Notifications/ }).first();
    await bell.click({ timeout: 20_000 });
    const alert = page.getByRole('alert').filter({ hasText: /Couldn.t load notifications/ });
    await expect(alert).toBeVisible();
    fail = false;
    const retry = alert.getByRole('button', { name: 'Retry' });
    await retry.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('menuitem', { name: /Pass114 trade opened/ })).toBeVisible();
    await expect(bell).toBeFocused();
  });
});
