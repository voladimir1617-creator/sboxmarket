// @ts-check
const { test, expect } = require('@playwright/test');

// A buyer who declined the bot's Steam delivery offer never got the item.
// The row used to read "Delivered — awaiting confirmation" with a Confirm
// receipt button that paid the seller anyway.

const ME = { id: 3, displayName: 'carol', role: 'USER', signedIn: true, sessionEpoch: 0, tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc' };
const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });

async function signedIn(page) {
  await page.addInitScript(() => { window.confirm = () => true; });
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json(ME)));
}

const now = Date.now();
const botTrade = (o) => ({
  id: 51, itemId: 23, itemName: 'Denim Jeans', sellerUserId: 9, buyerUserId: ME.id, counterpartyName: 'frank',
  state: 'PENDING_BUYER_CONFIRM', price: 4.23, feeAmount: 0.08, createdAt: now - 60000, updatedAt: now - 60000,
  unreadCount: 0, expiresAt: now + 8 * 86400000, botManaged: true, ...o,
});

async function tradesTab(page, rows) {
  await signedIn(page);
  await page.route('**/api/trades', (r) => r.fulfill(json(rows)));
  await page.goto('/profile/trades');
  await page.locator('#trade-' + rows[0].id).waitFor({ timeout: 20_000 });
}

test.describe('picks money: declined bot offer', () => {
  test('a declined bot offer is not shown as delivered and offers no Confirm receipt', async ({ page }) => {
    await tradesTab(page, [botTrade({ botDeliveryState: 'declined' })]);
    await expect(page.locator('#trade-51 .trade-state')).toContainText('Steam offer declined');
    await expect(page.locator('#trade-51 .trade-state')).not.toContainText('Delivered');
    await expect(page.locator('#trade-51')).not.toContainText('Delivered by our secure bot');
    await expect(page.getByRole('button', { name: /^Confirm receipt/ })).toHaveCount(0);
  });

  test('an accepted bot offer still shows Delivered and Confirm receipt', async ({ page }) => {
    await tradesTab(page, [botTrade({ botDeliveryState: 'accepted' })]);
    await expect(page.locator('#trade-51 .trade-state')).toContainText('Delivered — awaiting confirmation');
    await expect(page.getByRole('button', { name: /^Confirm receipt.*Denim Jeans/ })).toBeVisible();
  });
});
