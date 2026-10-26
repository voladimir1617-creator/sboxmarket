// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 42: a market card was one big <a> holding the cart and heart
// buttons and a second link (the Steam price), which is invalid HTML and
// reads to a screen reader as one link with buttons inside it. The card is
// now a plain box; its item name is the link, stretched over the card.
// Also: the live tape's 24H volume, the home hero price, the pending-trade
// nudge's link, and the item page's "More from" rail.

const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });

async function anon(page) {
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json({ signedIn: false })));
}

const ME = { id: 1, steamId64: '76561199000000001', displayName: 'Pass Tester', signedIn: true, sessionEpoch: 0 };

function listing(id, itemId) {
  return {
    id, price: 3, listingType: 'BUY_NOW', status: 'ACTIVE', sellerName: 'seller' + id, sellerUserId: 900 + id,
    listedAt: Date.now() - 1000,
    item: { id: itemId, name: 'Pass42 Hat ' + itemId, rarity: 'Standard', category: 'Hats', lowestPrice: 3, steamPrice: 9, isListed: true },
  };
}

async function market(page) {
  await anon(page);
  await page.route('**/api/listings?**', (r) => r.fulfill(json([listing(1, 21)])));
  await page.goto('/market');
  const card = page.locator('.grid-card').first();
  await expect(card).toBeVisible({ timeout: 20_000 });
  return card;
}

test.describe('defect pass 42', () => {
  test('a market card is not a link wrapping buttons or another link', async ({ page }) => {
    const card = await market(page);
    expect(await card.evaluate((c) => c.tagName)).not.toBe('A');
    await expect(page.locator('.grid-card a button, .grid-card a a, a.grid-card')).toHaveCount(0);
    await expect(card.getByRole('link', { name: /^Pass42 Hat 21, buy now/ })).toHaveAttribute('href', '/item/21');
  });

  test('clicking the card picture still opens the item', async ({ page }) => {
    const card = await market(page);
    await card.click({ position: { x: 40, y: 40 } });
    await expect(page).toHaveURL(/\/item\/21$/);
  });

  test('the heart on a card stars the item without opening it', async ({ page }) => {
    const card = await market(page);
    await card.hover();
    await card.getByRole('button', { name: /watchlist/i }).click();
    await expect(page).toHaveURL(/\/market/);
    await expect.poll(() => page.evaluate(() => localStorage.getItem('sb_watchlist'))).toContain('21');
  });

  test('tabbing to the card link rings the whole card', async ({ page }) => {
    const card = await market(page);
    await card.locator('.grid-card-link').focus();
    await page.keyboard.press('Shift+Tab');
    await page.keyboard.press('Tab');
    await expect(card.locator('.grid-card-link')).toBeFocused();
    await expect.poll(() => card.evaluate((c) => getComputedStyle(c).outlineStyle)).toBe('solid');
  });

  test('the live tape\'s 24H volume counts only sales from the last 24 hours', async ({ page }) => {
    await anon(page);
    const sale = (id, price, ageMs) => ({ listingId: id, itemId: id, itemName: 'Tape Hat ' + id, price, soldAt: Date.now() - ageMs, sellerName: 's' });
    await page.route('**/api/listings/recent-sales*', (r) => r.fulfill(json([
      sale(1, 7, 3_600_000), sale(2, 50, 3 * 86_400_000), sale(3, 40, 5 * 86_400_000),
    ])));
    await page.goto('/market');
    await expect(page.locator('.pulse')).toContainText('24H · $7.00 vol', { timeout: 20_000 });
  });

  test('the home hero leads with a buy-now listing, never an auction\'s starting bid', async ({ page }) => {
    await anon(page);
    const auction = { ...listing(1, 31), price: 5, listingType: 'AUCTION', currentBid: 40, bidCount: 3, expiresAt: Date.now() + 86_400_000 };
    const buyNow = { ...listing(2, 32), price: 12 };
    await page.route('**/api/listings?**', (r) => r.fulfill(json({ items: [auction, buyNow], total: 2 })));
    await page.goto('/');
    await expect(page.locator('.csfloat-home-hero-feature-price').first()).toContainText('12.00', { timeout: 20_000 });
  });

  test('"Open trade" on the pending-trade reminder opens the Trades tab', async ({ page }) => {
    await page.route('**/api/**', (r) => r.fulfill(json({})));
    await page.route('**/api/auth/steam/me', (r) => r.fulfill(json(ME)));
    await page.route('**/api/trades', (r) => r.fulfill(json([
      { id: 7, itemName: 'Trade Hat', state: 'PENDING_BUYER_CONFIRM', buyerUserId: 1, sellerUserId: 2, updatedAt: Date.now() - 3 * 3_600_000 },
    ])));
    await page.goto('/market');
    await expect(page.getByRole('link', { name: 'Open trade' })).toHaveAttribute('href', '/profile/trades?highlight=7', { timeout: 20_000 });
  });

  test('a "More from" card on an item page opens that seller\'s listing at its current bid', async ({ page }) => {
    await anon(page);
    const item = { id: 9, name: 'Main Hat', rarity: 'Standard', category: 'Hats', lowestPrice: 3, isListed: true };
    await page.route('**/api/items/9', (r) => r.fulfill(json(item)));
    await page.route('**/api/listings/item/9*', (r) => r.fulfill(json([
      { id: 80, price: 3, listingType: 'BUY_NOW', status: 'ACTIVE', sellerName: 'rail', sellerUserId: 7, listedAt: Date.now(), item },
    ])));
    await page.route('**/api/listings/seller/7/other*', (r) => r.fulfill(json([
      { id: 55, price: 2, listingType: 'AUCTION', currentBid: 12, status: 'ACTIVE', sellerUserId: 7, item: { id: 3, name: 'Rail Hat', rarity: 'Standard', category: 'Hats' } },
    ])));
    await page.goto('/item/9');
    const card = page.locator('.similar-card', { hasText: 'Rail Hat' });
    await expect(card).toHaveAttribute('href', '/item/3?listing=55', { timeout: 20_000 });
    await expect(card.locator('.similar-price')).toContainText('12.00');
  });
});
