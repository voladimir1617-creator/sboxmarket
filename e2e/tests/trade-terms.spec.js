// @ts-check
const { test, expect } = require('@playwright/test');

/**
 * THE TERMS OF A TRADE, STATED BEFORE ANYONE COMMITS TO ONE.
 *
 * Seller: the sell form showed the fee and the payout, but not the seller's
 * side of the trade. He learned that he had to accept and send a Steam trade
 * offer, and by when, from a notification after the sale; missing that window
 * auto-cancels the sale and puts a failed trade on his public record.
 *
 * Buyer: an accepted offer runs the purchase on the spot (the wallet is
 * charged and the escrow trade opens). The bargain drawer said nothing of the
 * kind, showed its suggestion as a bare "0.46" beside a Send button that was
 * disabled without a reason.
 *
 * Inventory, listings and price probes are stubbed at the network boundary so
 * the forms render whatever the dev database holds. The policy figures are
 * read from the REAL server, so the copy is checked against what it enforces.
 */

async function stubQuietMarket(page) {
  await page.route('**/api/items/*/recent-sales*', (r) =>
    r.fulfill({ status: 200, contentType: 'application/json', body: '[]' }));
}

test('the sell form states the seller\'s side of the trade, with the server\'s own deadlines', async ({ page }) => {
  const policy = await (await page.request.get('/api/listings/delivery-policy')).json();
  expect(policy.sellerResponseDays).toBeGreaterThan(0);
  expect(policy.buyerConfirmDays).toBeGreaterThan(0);

  await page.route('**/api/steam/inventory', (route) => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({
      items: [{
        assetId: '910001', assetIds: ['910001'], quantity: 1, name: 'Terms Test Hat', type: 'Hat',
        iconUrl: 'https://example.com/x.png', tradable: true, marketable: true, category: 'Hats',
        rarity: 'Standard', catalogueId: 4243, suggestedPrice: 0, steamPrice: null,
        listedCount: 0, listableQuantity: 1, unlistableReason: null,
      }],
      count: 1, assetCount: 1, lastSyncedAt: Date.now(), steamId64: '76561199000000001',
    }),
  }));
  await stubQuietMarket(page);
  await page.route('**/api/listings/item/*', (r) => r.fulfill({ status: 200, contentType: 'application/json', body: '[]' }));

  await page.goto('/sell');
  await page.locator('.inventory-item').first().click({ timeout: 15_000 });
  await page.locator('.wallet-amount-input, input[type="number"]').first().fill('10.00');

  const terms = page.getByTestId('sell-obligations');
  await expect(terms).toBeVisible();
  const d = (n) => `${n} day${n === 1 ? '' : 's'}`;
  await expect(terms.locator('[data-line="send"]')).toContainText(`within ${d(policy.sellerResponseDays)}`);
  await expect(terms.locator('[data-line="miss"]')).toContainText('buyer is refunded');
  // 10.00 minus the 2% fee, and when it arrives.
  await expect(terms.locator('[data-line="paid"]')).toContainText('$9.80');
  await expect(terms.locator('[data-line="paid"]')).toContainText(`${d(policy.buyerConfirmDays)} after you mark it sent`);
  // The fee breakdown still states the fee on the same form.
  await expect(page.getByTestId('sell-fee-breakdown')).toContainText('Platform fee (2%)');
});

test('the bargain drawer says an accepted offer is charged at once, and why Send is disabled', async ({ page }) => {
  const ITEM_ID = 626262;
  const item = { id: ITEM_ID, name: 'Offer Terms Hat', category: 'Hats', rarity: 'Standard', steamPrice: 6, lowestPrice: 4 };
  await page.route(`**/api/listings/item/${ITEM_ID}*`, (r) => r.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify([{ id: 9904, listingType: 'BUY_NOW', status: 'ACTIVE', price: 4, sellerUserId: 999,
      sellerName: 'StubSeller', listedAt: Date.now(), item }]),
  }));
  await page.route(`**/api/items/${ITEM_ID}`, (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(item) }));
  await page.goto(`/item/${ITEM_ID}`);
  await page.locator('.item-rail-actions-bargain').first().click({ timeout: 15_000 });

  const input = page.getByRole('spinbutton', { name: 'Offer amount in USD' });
  await expect(input).toBeVisible();
  // The suggestion is a hint, not a value.
  await expect(input).toHaveValue('');
  expect(await input.getAttribute('placeholder')).toMatch(/^e\.g\. /);
  await expect(page.getByTestId('offer-why-disabled')).toContainText('Enter an amount below $4.00');

  await expect(page.getByTestId('offer-terms')).toContainText('charged from your wallet at once');
  await input.fill('3.50');
  await expect(page.getByTestId('offer-terms')).toContainText('$3.50');
  await expect(page.getByTestId('offer-why-disabled')).toHaveCount(0);
});
