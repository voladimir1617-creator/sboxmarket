// @ts-check
const { test, expect } = require('@playwright/test');

/**
 * WHO THE BUYER IS PAYING, AND WHETHER THAT SELLER DELIVERS.
 *
 * csfloat puts the seller's trade record (median trade time, share of trades
 * completed, with the count) beside every listing. The item page here named
 * nobody next to "Buy now", so a buyer about to send money to a stranger had
 * to leave the page to find out whether the stranger delivers.
 *
 * The listing and the record are stubbed at the network boundary: which
 * sellers exist and what they have traded depends on the dev database, and
 * the states that matter (a record, a brand-new seller, a FAILED lookup) have
 * to be pinned regardless.
 */

const ITEM_ID = 424242;
const SELLER_ID = 777;

function listing(over = {}) {
  return {
    id: 9901, listingType: 'BUY_NOW', status: 'ACTIVE', price: 4.2, sellerUserId: SELLER_ID,
    sellerName: 'StubSeller', listedAt: Date.now() - 3_600_000,
    item: { id: ITEM_ID, name: 'Trust Test Hat', category: 'Hats', rarity: 'Standard', steamPrice: 5, lowestPrice: 4.2 },
    ...over,
  };
}

async function stubItem(page, rows) {
  await page.route(`**/api/items/${ITEM_ID}`, (r) => r.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ id: ITEM_ID, name: 'Trust Test Hat', category: 'Hats', rarity: 'Standard', steamPrice: 5, lowestPrice: 4.2 }),
  }));
  await page.route(`**/api/listings/item/${ITEM_ID}*`, (r) => r.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify(rows),
  }));
}

test.describe('The seller card beside Buy now', () => {

  test('shows the seller and their trade record: completed, completion rate with the count, time to send', async ({ page }) => {
    await stubItem(page, [listing()]);
    await page.route('**/api/sellers/trust*', (r) => r.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ [SELLER_ID]: {
        sellerUserId: SELLER_ID, completedTrades: 49, failedTrades: 1, completionRate: 98,
        medianShipMs: 2 * 3_600_000, memberSince: Date.UTC(2026, 0, 15), lastSeenAt: Date.now(),
        rating: 4.8, reviewCount: 12,
      } }),
    }));
    await page.goto(`/item/${ITEM_ID}`);
    const card = page.getByTestId('seller-trust');
    await expect(card).toBeVisible({ timeout: 15_000 });
    await expect(card).toContainText('StubSeller');
    await expect(card).toContainText('Online');
    await expect(card.locator('[data-stat="completed"]')).toContainText('49');
    await expect(card.locator('[data-stat="rate"]')).toContainText('98%');
    await expect(card.locator('[data-stat="rate"]')).toContainText('49 of 50');
    await expect(card.locator('[data-stat="ship"]')).toContainText('~2h');
    await expect(card.locator('[data-stat="rating"]')).toContainText('4.8');
    // The Active Listings row carries the same record in short.
    await expect(page.getByTestId('listing-trade-chip').first()).toContainText('49 trades');
  });

  test('a seller with no decided trades is called new, never shown a clean 100%', async ({ page }) => {
    await stubItem(page, [listing()]);
    await page.route('**/api/sellers/trust*', (r) => r.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ [SELLER_ID]: {
        sellerUserId: SELLER_ID, completedTrades: 0, failedTrades: 0, completionRate: null,
        medianShipMs: null, memberSince: Date.now() - 86_400_000, lastSeenAt: null, rating: null, reviewCount: 0,
      } }),
    }));
    await page.goto(`/item/${ITEM_ID}`);
    const card = page.getByTestId('seller-trust');
    await expect(card).toBeVisible({ timeout: 15_000 });
    await expect(card).toContainText('New seller');
    await expect(card).not.toContainText('100%');
    await expect(card).toContainText('Offline');
  });

  test('a FAILED record lookup says so instead of rendering a spotless record', async ({ page }) => {
    await stubItem(page, [listing()]);
    await page.route('**/api/sellers/trust*', (r) => r.fulfill({ status: 500, body: 'boom' }));
    await page.goto(`/item/${ITEM_ID}`);
    const card = page.getByTestId('seller-trust');
    await expect(card).toBeVisible({ timeout: 15_000 });
    await expect(card).toContainText('unavailable');
    await expect(card).not.toContainText('New seller');
  });

  test('a house listing has no seller card (the platform delivers it)', async ({ page }) => {
    await stubItem(page, [listing({ sellerUserId: null, sellerName: 'SkinBox' })]);
    await page.goto(`/item/${ITEM_ID}`);
    await expect(page.locator('.item-rail-actions-buy').first()).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId('seller-trust')).toHaveCount(0);
  });
});

test.describe('Signals on the page must be true', () => {

  // Every card whose seller is offline drew the GREEN online dot next to the
  // word "Offline": the stylesheet greys `.gc-online-dot.offline`, and the dot
  // never got that class.
  test('an Offline card never shows the online dot colour', async ({ page }) => {
    await page.goto('/market');
    const offlineRows = page.locator('.gc-online-row:not(.is-online)');
    await expect(offlineRows.first()).toBeVisible({ timeout: 15_000 });
    const bad = await offlineRows.evaluateAll((rows) => rows.filter((row) => {
      const dot = row.querySelector('.gc-online-dot');
      return dot && !dot.classList.contains('offline');
    }).length);
    expect(bad).toBe(0);
  });
});
