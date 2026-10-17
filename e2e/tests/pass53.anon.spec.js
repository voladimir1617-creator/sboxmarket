// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 53.

const ME = {
  id: 1,
  steamId64: '76561199000000001',
  displayName: 'Pass Tester',
  signedIn: true,
  sessionEpoch: 0,
  tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc',
};

const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });

async function signedIn(page) {
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json(ME)));
}

test.describe('defect pass 53', () => {
  test('on a bot-delivered sale the sell form says the seller is paid when the buyer accepts the Steam offer', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/custody', (r) => r.fulfill(json({ custodial: true })));
    await page.route('**/api/listings/delivery-policy', (r) => r.fulfill(json({ sellerResponseDays: 3, buyerConfirmDays: 8 })));
    await page.route('**/api/listings/item/*', (r) => r.fulfill(json([])));
    await page.route('**/api/steam/inventory', (r) => r.fulfill(json({
      items: [{
        assetId: '910001', assetIds: ['910001'], quantity: 1, name: 'Terms Test Hat', type: 'Hat',
        iconUrl: 'https://example.com/x.png', tradable: true, marketable: true, category: 'Hats',
        rarity: 'Standard', catalogueId: 4243, suggestedPrice: 0, steamPrice: null,
        listedCount: 0, listableQuantity: 1, unlistableReason: null,
      }],
      count: 1, assetCount: 1, lastSyncedAt: Date.now(), steamId64: '76561199000000001',
    })));
    await page.goto('/sell');
    await page.locator('.inventory-item').first().click({ timeout: 20_000 });
    await page.locator('.wallet-amount-input, input[type="number"]').first().fill('10.00');
    const terms = page.getByTestId('sell-obligations');
    await expect(terms).toContainText('there is no trade offer for you to send');
    await expect(terms).toContainText('You are paid $9.80 when the buyer accepts the Steam trade offer.');
    await expect(terms).not.toContainText('confirms it arrived');
  });
});
