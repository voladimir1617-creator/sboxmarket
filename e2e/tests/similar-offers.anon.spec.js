// @ts-check
const { test, expect } = require('@playwright/test');

// "Similar offers" under an item: nearby items someone is selling right now,
// each with its cheapest price and how many offers back it. Items with no
// live offer stay in "You might also like", with no price.

const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });

async function anon(page) {
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json({ signedIn: false })));
}

test('similar items on sale show their cheapest price and offer count; unsold ones stay apart', async ({ page }) => {
  await anon(page);
  const item = { id: 31, name: 'Offer Main Hat', rarity: 'Standard', category: 'Hats', lowestPrice: 3, isListed: true, supply: 1 };
  await page.route('**/api/items/31', (r) => r.fulfill(json(item)));
  await page.route('**/api/listings/item/31*', (r) => r.fulfill(json([
    { id: 310, price: 3, listingType: 'BUY_NOW', status: 'ACTIVE', sellerName: 's', sellerUserId: 931, listedAt: Date.now(), item },
  ])));
  await page.route('**/api/items/31/similar*', (r) => r.fulfill(json([
    { id: 32, name: 'Busy Hat', rarity: 'Standard', category: 'Hats', lowestPrice: 2.5, isListed: true, supply: 4 },
    { id: 33, name: 'Lone Hat', rarity: 'Standard', category: 'Hats', lowestPrice: 3.25, isListed: true, supply: 1 },
    { id: 34, name: 'Unsold Hat', rarity: 'Standard', category: 'Hats', lowestPrice: 0, isListed: false, supply: 0 },
  ])));
  await page.goto('/item/31');

  const offers = page.getByTestId('similar-offers');
  await expect(offers).toBeVisible({ timeout: 20_000 });
  await expect(offers).toContainText('Similar offers');
  const busy = offers.locator('a.similar-card', { hasText: 'Busy Hat' });
  await expect(busy).toHaveAttribute('href', '/item/32');
  await expect(busy.locator('.similar-price')).toContainText('2.50');
  await expect(busy.getByTestId('similar-offer-count')).toHaveText('4 offers');
  await expect(offers.locator('a.similar-card', { hasText: 'Lone Hat' }).getByTestId('similar-offer-count')).toHaveText('1 offer');
  await expect(offers.locator('a.similar-card', { hasText: 'Unsold Hat' })).toHaveCount(0);

  const unsold = page.locator('a.similar-card', { hasText: 'Unsold Hat' });
  await expect(unsold.locator('.similar-price')).toHaveText('—');
  await expect(page.getByText('You might also like')).toBeVisible();
});
