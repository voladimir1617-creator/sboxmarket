// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 105.

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
// Defect pass 105: reduced motion, truncated names, sub-tab state on reload.

const IMG = 'data:image/svg+xml,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="8" height="8"><rect width="8" height="8" fill="red"/></svg>');
const item = (id, extra = {}) => ({ id, name: 'Pass Hat ' + id, category: 'Hats', rarity: 'Standard', imageUrl: IMG, lowestPrice: 2, ...extra });
const listing = (id, itemId = id) => ({ id, item: item(itemId), price: 2 + (id % 7), sellerName: 'dave', sellerUserId: 9, status: 'ACTIVE', listingType: 'BUY_NOW', listedAt: Date.now() - 3600_000 });
// Where the page (window, .layout or a scrolling backdrop) sits two frames after a click.
const scrollTops = () => [window.scrollY, ...[...document.querySelectorAll('.layout, .modal-backdrop')].map((e) => e.scrollTop)].join(',');
const twoFrames = (fn) => new Promise((ok) => requestAnimationFrame(() => requestAnimationFrame(() => ok(fn()))));

test.describe('pass 105: reduced motion', () => {
  test.beforeEach(async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    // Consent already given, so the cookie puck (bottom-right) does not sit over Back to top.
    await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
    await page.route('**/api/**', (r) => r.fulfill(json({})));
  });

  test('Back to top jumps straight to the top when motion is reduced', async ({ page }) => {
    await page.route('**/api/listings?**', (r) => r.fulfill(json(Array.from({ length: 60 }, (_, i) => listing(i + 1)))));
    await page.goto('/market');
    const last = page.getByRole('link', { name: /^Pass Hat 60,/ });
    await last.scrollIntoViewIfNeeded({ timeout: 20_000 });
    const top = page.getByRole('button', { name: 'Back to top' });
    await expect(top).toBeVisible();
    await top.click();
    const at = await page.evaluate(() => new Promise((ok) => requestAnimationFrame(() => requestAnimationFrame(() =>
      ok(Math.max(window.scrollY, document.querySelector('.layout')?.scrollTop || 0))))));
    expect(at).toBe(0);
  });

  test('a Help Center top question jumps to its answer when motion is reduced', async ({ page }) => {
    await page.goto('/help');
    const row = page.locator('.help-faq-preview-row').nth(2);
    await expect(row).toBeVisible({ timeout: 20_000 });
    await row.click();
    const early = await page.evaluate(`(${twoFrames})(${scrollTops})`);
    await page.waitForTimeout(1200);
    const settled = await page.evaluate(scrollTops);
    expect(early).toBe(settled);
    await expect(page.locator('#help-faq-panel-2')).toBeInViewport();
  });

  test('the item page listings chip jumps to Active Listings when motion is reduced', async ({ page }) => {
    await page.route('**/api/items/1', (r) => r.fulfill(json(item(1))));
    await page.route('**/api/listings/item/1', (r) => r.fulfill(json([listing(1, 1), listing(2, 1)])));
    await page.route('**/api/items/1/history', (r) => r.fulfill(json([])));
    await page.goto('/item/1');
    const chip = page.locator('button.modal-demand-chip').filter({ hasText: /listing/ }).first();
    await expect(chip).toBeVisible({ timeout: 20_000 });
    await chip.click();
    const early = await page.evaluate(`(${twoFrames})(${scrollTops})`);
    await page.waitForTimeout(1200);
    const settled = await page.evaluate(scrollTops);
    expect(early).toBe(settled);
    await expect(page.getByRole('heading', { name: /Active Listings \(2\)/ })).toBeInViewport();
  });
});

test.describe('pass 105: truncated names', () => {
  test('My Stall Top seller tile lets you read the whole item name', async ({ page }) => {
    const LONG = 'Limited Edition Holographic Grand Ball Gown With Gold Trim 2026';
    await signedIn(page);
    await page.route('**/api/listings/my-stall', (r) => r.fulfill(json([])));
    await page.route('**/api/listings/my-stall/sold', (r) => r.fulfill(json([
      { id: 253, price: 3.21, soldAt: Date.now() - 60000, item: { id: 32, name: LONG } },
    ])));
    await page.goto('/me/stall/sold');
    const val = page.locator('.admin-stat').filter({ hasText: /Top seller/ }).locator('.admin-stat-val');
    await val.scrollIntoViewIfNeeded({ timeout: 20_000 });
    await expect(val).toBeInViewport();
    // The tile cuts the name with an ellipsis...
    expect(await val.evaluate((el) => el.scrollWidth > el.clientWidth)).toBe(true);
    // ...so the hover text has to carry it.
    const hover = await val.evaluate((el) => { for (let n = el; n; n = n.parentElement) if (n.title) return n.title; return ''; });
    expect(hover).toContain(LONG);
  });
});

test.describe('pass 105: truncated names on a phone', () => {
  test.use({ viewport: { width: 390, height: 844 } });
  const LONG = 'Limited Edition Holographic Grand Ball Gown With Gold Trim 2026';
  const hoverText = (el) => { for (let n = el; n; n = n.parentElement) if (n.title) return n.title; return ''; };
  const cut = (el) => el.scrollWidth > el.clientWidth;
  const mine = { id: 253, price: 3.21, status: 'ACTIVE', listingType: 'BUY_NOW', listedAt: Date.now() - 60000, soldAt: Date.now() - 60000, item: { id: 32, name: LONG } };

  test('My Stall active rows let you read the whole item name', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings/my-stall', (r) => r.fulfill(json([mine])));
    await page.goto('/me/stall');
    const name = page.locator('.stall-row .item-name').first();
    await name.scrollIntoViewIfNeeded({ timeout: 20_000 });
    await expect(name).toBeInViewport();
    expect(await name.evaluate(cut)).toBe(true);
    expect(await name.evaluate(hoverText)).toContain(LONG);
  });

  test('My Stall Sold rows let you read the whole item name', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings/my-stall', (r) => r.fulfill(json([])));
    await page.route('**/api/listings/my-stall/sold', (r) => r.fulfill(json([mine])));
    await page.goto('/me/stall/sold');
    const name = page.locator('.recent-sales-row').getByText(LONG, { exact: true });
    await name.scrollIntoViewIfNeeded({ timeout: 20_000 });
    await expect(name).toBeInViewport();
    expect(await name.evaluate(cut)).toBe(true);
    expect(await name.evaluate(hoverText)).toContain(LONG);
  });
});

test.describe('pass 105: sub-tab state survives a reload', () => {
  test('Profile Reviews keeps the Given filter on reload', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/reviews/user/3', (r) => r.fulfill(json([])));
    await page.route('**/api/reviews/mine', (r) => r.fulfill(json([])));
    await page.route('**/api/reviews/pending', (r) => r.fulfill(json([])));
    await page.goto('/profile/reviews');
    const list = page.getByRole('tablist', { name: 'Reviews filter' });
    await list.getByRole('tab', { name: /^Given/ }).click({ timeout: 20_000 });
    await expect(list.getByRole('tab', { name: /^Given/ })).toHaveAttribute('aria-selected', 'true');
    await page.reload();
    await expect(page.getByRole('tablist', { name: 'Reviews filter' }).getByRole('tab', { name: /^Given/ }))
      .toHaveAttribute('aria-selected', 'true', { timeout: 20_000 });
  });

  test('Profile Offers keeps the Outgoing direction on reload', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/offers/incoming', (r) => r.fulfill(json([])));
    await page.route('**/api/offers/outgoing', (r) => r.fulfill(json([])));
    await page.goto('/profile/offers');
    const list = page.getByRole('tablist', { name: 'Offer direction' });
    await list.getByRole('tab', { name: /^Outgoing/ }).click({ timeout: 20_000 });
    await expect(list.getByRole('tab', { name: /^Outgoing/ })).toHaveAttribute('aria-selected', 'true');
    await page.reload();
    await expect(page.getByRole('tablist', { name: 'Offer direction' }).getByRole('tab', { name: /^Outgoing/ }))
      .toHaveAttribute('aria-selected', 'true', { timeout: 20_000 });
  });

  test('Help Center keeps the FAQ search on reload', async ({ page }) => {
    await page.route('**/api/**', (r) => r.fulfill(json({})));
    await page.goto('/help');
    const box = page.getByRole('searchbox', { name: 'Search frequently asked questions' });
    await box.fill('refund', { timeout: 20_000 });
    await page.reload();
    const again = page.getByRole('searchbox', { name: 'Search frequently asked questions' });
    await expect(again).toHaveValue('refund', { timeout: 20_000 });
    // Clearing it clears the URL too, so the next reload starts empty.
    await again.fill('');
    await page.reload();
    await expect(page.getByRole('searchbox', { name: 'Search frequently asked questions' })).toHaveValue('', { timeout: 20_000 });
  });
});
