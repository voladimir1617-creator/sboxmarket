// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 103.

const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });
const slow = (body, ms = 800) => (r) => new Promise((ok) => setTimeout(ok, ms)).then(() => r.fulfill(json(body)));

const IMG = 'data:image/svg+xml,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="8" height="8"><rect width="8" height="8" fill="red"/></svg>');
const item = (id, extra = {}) => ({ id, name: 'Pass Hat ' + id, category: 'Hats', rarity: 'Standard', imageUrl: IMG, lowestPrice: 2, ...extra });
const listing = (id, itemId = id) => ({ id, item: item(itemId), price: 2 + (id % 7), sellerName: 'dave', sellerUserId: 9, status: 'ACTIVE', listingType: 'BUY_NOW', listedAt: Date.now() - 3600_000 });
const focusedIsBody = (page) => page.evaluate(() => document.activeElement === document.body);

async function itemPage(page, { sales } = {}) {
  await page.route('**/api/listings/item/1', (r) => r.fulfill(json([listing(1, 1)])));
  await page.route('**/api/items/1/history', (r) => r.fulfill(json([])));
  if (sales) await page.route('**/api/items/1/recent-sales**', sales);
}

test.describe('pass 103 · item page load error', () => {
  test('Retry keeps focus on the page when the item then loads', async ({ page }) => {
    await page.route('**/api/**', (r) => r.fulfill(json({})));
    let fail = true;
    await page.route('**/api/listings/item/1', (r) => fail ? r.fulfill(json({ error: 'down' }, 500)) : slow([listing(1, 1)], 800)(r));
    await page.route('**/api/items/1/history', (r) => r.fulfill(json([])));
    await page.goto('/item/1');
    const retry = page.getByRole('button', { name: 'Retry' });
    await expect(retry).toBeVisible();
    fail = false;
    await retry.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('heading', { level: 1, name: 'Pass Hat 1' })).toBeVisible();
    await expect.poll(() => page.evaluate(() => !!document.activeElement.closest('#main'))).toBe(true);
  });

  test('Retry that fails again lands on the new Retry', async ({ page }) => {
    await page.route('**/api/**', (r) => r.fulfill(json({})));
    await page.route('**/api/listings/item/1', (r) => new Promise((ok) => setTimeout(ok, 600)).then(() => r.fulfill(json({ error: 'down' }, 500))));
    await page.goto('/item/1');
    const retry = page.getByRole('button', { name: 'Retry' });
    await expect(retry).toBeVisible();
    await retry.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('heading', { name: /Couldn.t load this item/ })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Retry' })).toBeFocused();
  });
});

test.describe('pass 103 · thumbnails do not repeat the item name', () => {
  test('live sales ticker links read the item name once', async ({ page }) => {
    await page.route('**/api/**', (r) => r.fulfill(json({})));
    await page.route('**/api/auth/steam/me', (r) => r.fulfill(json({ signedIn: false })));
    await page.route('**/api/listings/recent-sales*', (r) => r.fulfill(json([
      { listingId: 1, itemId: 5, itemName: 'Ticker Hat', imageUrl: IMG, price: 3, soldAt: Date.now() - 120_000 },
    ])));
    await page.goto('/market');
    const link = page.locator('.ticker-item').first();
    await expect(link).toBeVisible({ timeout: 20_000 });
    await expect(link).toHaveAccessibleName(/^Ticker Hat \$3\.00 2m ago$/);
  });

  test('item page Recently viewed cards read the item name once', async ({ page }) => {
    await page.route('**/api/**', (r) => r.fulfill(json({})));
    await itemPage(page);
    await page.addInitScript((rows) => { try { localStorage.setItem('sb_recently_viewed', JSON.stringify(rows)); } catch (_) {} },
      [2, 3, 4, 5, 6].map((id) => ({ ...item(id), viewedAt: Date.now() - id * 1000 })));
    await page.goto('/item/1');
    const card = page.locator('.recently-viewed-card').first();
    await card.scrollIntoViewIfNeeded();
    await expect(card).toBeInViewport();
    // The thumb (image, or the glyph standing in for a failed one) is hidden; the name is read once.
    await expect(card.locator('.recently-viewed-thumb')).toHaveAttribute('aria-hidden', 'true');
    await expect(card).toHaveAccessibleName(/^Pass Hat 2 \$2\.00$/);
  });
});

test.describe('pass 103 · market search suggestions', () => {
  async function openSuggestions(page) {
    await page.route('**/api/**', (r) => r.fulfill(json({})));
    await page.route('**/api/listings?**', (r) => r.fulfill(json([listing(1, 1)])));
    await page.route('**/api/items?q=**', (r) => r.fulfill(json([item(1), item(2), item(3)])));
    await page.goto('/market');
    const box = page.getByRole('combobox', { name: 'Search listings' });
    await box.click();
    await box.fill('pass');
    await expect(page.getByRole('listbox', { name: 'Search suggestions' })).toBeVisible();
    return box;
  }

  test('tabbing out of the search closes the suggestions', async ({ page }) => {
    const box = await openSuggestions(page);
    await page.keyboard.press('Tab'); // Clear search, still in the search box group
    await page.keyboard.press('Tab'); // Sort picker
    await expect(page.getByRole('button', { name: /Sort listings/ })).toBeFocused();
    await expect(page.getByRole('listbox', { name: 'Search suggestions' })).toBeHidden();
    await expect(box).toHaveAttribute('aria-expanded', 'false');
  });

  test('ArrowDown reopens the suggestions after Escape', async ({ page }) => {
    const box = await openSuggestions(page);
    await page.keyboard.press('Escape');
    await expect(page.getByRole('listbox', { name: 'Search suggestions' })).toBeHidden();
    await expect(box).toBeFocused();
    await page.keyboard.press('ArrowDown');
    await expect(page.getByRole('listbox', { name: 'Search suggestions' })).toBeVisible();
    await expect(box).toHaveAttribute('aria-expanded', 'true');
    await page.keyboard.press('ArrowDown');
    await expect(box).toHaveAttribute('aria-activedescendant', 'search-opt-0');
  });
});

test.describe('pass 103 · market Load more', () => {
  test('loading the last page keeps focus in the grid', async ({ page }) => {
    await page.route('**/api/**', (r) => r.fulfill(json({})));
    await page.route('**/api/listings?**', (r) => {
      const off = Number(new URL(r.request().url()).searchParams.get('offset') || 0);
      if (off === 0) return r.fulfill(json(Array.from({ length: 100 }, (_, i) => listing(i + 1))));
      return r.fulfill(json(Array.from({ length: 30 }, (_, i) => listing(off + i + 1))));
    });
    await page.goto('/market');
    const more = page.getByRole('button', { name: /Load more/ });
    await more.scrollIntoViewIfNeeded();
    await expect(more).toBeInViewport();
    await more.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('link', { name: /^Pass Hat 105,/ })).toHaveCount(1);
    await expect(more).toHaveCount(0);
    await expect.poll(() => focusedIsBody(page)).toBe(false);
    await expect.poll(() => page.evaluate(() => !!document.activeElement.closest('.grid-card'))).toBe(true);
  });
});

test.describe('pass 103 · item page Recent sales', () => {
  const sale = (i) => ({ listingId: 100 + i, price: 2 + i / 10, listingType: 'BUY_NOW', soldAt: Date.now() - i * 3600_000 });

  test('Show more sales keeps focus on the strip', async ({ page }) => {
    await page.route('**/api/**', (r) => r.fulfill(json({})));
    await itemPage(page, { sales: (r) => {
      const lim = Number(new URL(r.request().url()).searchParams.get('limit') || 10);
      return slow(Array.from({ length: lim === 50 ? 20 : 10 }, (_, i) => sale(i)), 300)(r);
    } });
    await page.goto('/item/1');
    const more = page.getByRole('button', { name: /Show more sales/ });
    await more.scrollIntoViewIfNeeded();
    await expect(more).toBeInViewport();
    await more.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByText(/Showing 20 most-recent sales/)).toBeVisible();
    await expect.poll(() => focusedIsBody(page)).toBe(false);
    await expect(page.getByText(/Showing 20 most-recent sales/)).toBeFocused();
  });

  test('a failed Show more sales leaves the button to try again', async ({ page }) => {
    await page.route('**/api/**', (r) => r.fulfill(json({})));
    await itemPage(page, { sales: (r) => {
      const lim = Number(new URL(r.request().url()).searchParams.get('limit') || 10);
      return lim === 50 ? r.fulfill(json({ error: 'down' }, 500)) : r.fulfill(json(Array.from({ length: 10 }, (_, i) => sale(i))));
    } });
    await page.goto('/item/1');
    const more = page.getByRole('button', { name: /Show more sales/ });
    await more.scrollIntoViewIfNeeded();
    await more.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByText(/Could not load more sales/)).toBeVisible();
    await expect(page.getByRole('button', { name: /Show more sales/ })).toBeFocused();
    await expect(page.getByText(/server cap: 50/)).toHaveCount(0);
  });
});
