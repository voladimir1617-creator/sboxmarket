// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 38: market grid, item page, loadouts, cart page, FAQ.

const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });

async function anon(page) {
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json({ signedIn: false })));
}

function listing(id, itemId, extra = {}) {
  return {
    id, price: 3, listingType: 'BUY_NOW', status: 'ACTIVE', sellerName: 'seller' + id, sellerUserId: 900 + id,
    listedAt: Date.now() - 1000,
    item: { id: itemId, name: 'Pass38 Item ' + itemId, rarity: 'Standard', category: 'Hats', lowestPrice: 3, isListed: true },
    ...extra,
  };
}

test.describe('defect pass 38', () => {
  test('the listing count beside the item count honours the Buy Now filter', async ({ page }) => {
    await anon(page);
    await page.route('**/api/listings?**', (r) => r.fulfill(json([
      listing(1, 11),
      listing(2, 11, { listingType: 'AUCTION', currentBid: 2 }),
      listing(3, 12),
    ])));
    await page.goto('/market?type=BUY_NOW');
    const meta = page.locator('.results-meta');
    await expect(meta).toContainText('2 items found', { timeout: 20_000 });
    await expect(meta).not.toContainText('listings');
  });

  test('a first page with more to load says "100+ items" and counts listings loaded', async ({ page }) => {
    await anon(page);
    const rows = Array.from({ length: 100 }, (_, i) => listing(1000 + i, 2000 + i));
    await page.route('**/api/listings?**', (r) => r.fulfill(json(rows)));
    await page.goto('/market');
    await expect(page.locator('.results-meta')).toContainText('100+ items found', { timeout: 20_000 });
    await expect(page.getByRole('button', { name: /Load more \(100 listings loaded\)/ })).toBeVisible();
  });

  test('the $2–$5 price chip stops a cent short of $5', async ({ page }) => {
    await anon(page);
    const urls = [];
    await page.route('**/api/listings?**', (r) => { urls.push(r.request().url()); return r.fulfill(json([listing(1, 11)])); });
    await page.goto('/market');
    await expect(page.locator('.results-meta')).toContainText('1 item found', { timeout: 20_000 });
    await page.getByRole('button', { name: /^\$2\.00\s*–\s*\$5\.00$/ }).first().click();
    await expect.poll(() => urls.some((u) => /maxPrice=4\.99/.test(u))).toBe(true);
  });

  test('the item page re-reads its listings past the browser cache', async ({ page }) => {
    await page.goto('/faq');
    const mode = await page.evaluate(async () => {
      const api = await import('/js/api.js');
      const seen = [];
      const orig = window.fetch;
      window.fetch = (url, init) => { seen.push({ url: String(url), cache: init && init.cache }); return orig(url, init); };
      try { await api.fetchListingsForItem(1); } catch (_) { /* a 404 is fine */ }
      window.fetch = orig;
      const hit = seen.find((s) => s.url.includes('/listings/item/1'));
      return hit ? hit.cache : 'not-called';
    });
    expect(mode).toBe('no-cache');
  });

  test('"You might also like" shows no price for an item nobody sells here', async ({ page }) => {
    await anon(page);
    await page.route('**/api/items/5', (r) => r.fulfill(json({
      id: 5, name: 'Pass38 Main Hat', rarity: 'Standard', category: 'Hats', lowestPrice: 3, isListed: true, supply: 1,
    })));
    await page.route('**/api/listings/item/5*', (r) => r.fulfill(json([listing(50, 5)])));
    await page.route('**/api/items/5/similar*', (r) => r.fulfill(json([
      { id: 6, name: 'Steam Only Hat', rarity: 'Standard', category: 'Hats', lowestPrice: 3.5, isListed: false },
    ])));
    await page.goto('/item/5');
    const card = page.locator('a.similar-card', { hasText: 'Steam Only Hat' });
    await expect(card).toBeVisible({ timeout: 20_000 });
    await expect(card.locator('.similar-price')).toHaveText('—');
  });

  test('loadout cards open through a link, with Copy link as its own control', async ({ page }) => {
    await anon(page);
    await page.route('**/api/loadouts/discover*', (r) => r.fulfill(json([
      { id: 7, name: 'Wizard Fit', ownerName: 'bob', favorites: 2, totalValue: 5, visibility: 'PUBLIC', filledSlots: 3 },
    ])));
    await page.goto('/loadout');
    const link = page.getByRole('link', { name: /Open loadout Wizard Fit/ });
    await expect(link).toBeVisible({ timeout: 20_000 });
    await expect(link).toHaveAttribute('href', '/loadout/7');
    await expect(page.getByRole('button', { name: /Open loadout Wizard Fit/ })).toHaveCount(0);
    await expect(page.locator('.loadout-card').getByRole('button', { name: 'Copy link' })).toBeVisible();
  });

  test('the cart page draws no made-up wear bar', async ({ page }) => {
    await anon(page);
    await page.addInitScript(() => localStorage.setItem('sb_cart', JSON.stringify([{
      id: 501, name: 'Pass38 Cart Hat', price: 3, sellerName: 'seller', itemId: 9,
    }])));
    await page.goto('/cart');
    await expect(page.getByRole('button', { name: 'Remove Pass38 Cart Hat from cart' })).toBeVisible({ timeout: 20_000 });
    await expect(page.locator('.cart-floatbar')).toHaveCount(0);
  });

  test('the FAQ names the heart the cards actually show', async ({ page }) => {
    await page.goto('/faq');
    await page.getByText('What is the Watchlist and what are Price Alerts?').click();
    await expect(page.getByText(/Click the ♡ heart on any item card/)).toBeVisible();
  });
});
