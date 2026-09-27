// @ts-check
const { test, expect } = require('@playwright/test');

// A trade row waiting on the buyer shows when the seller marked it sent. The
// label and the relative time were two adjacent React children with no space
// between them, so the row read "sentJust now" / "sent5m ago". The trade list
// is stubbed so the row exists whatever state the dev database is in.
test('a sent trade reads "sent 5m ago", with a space', async ({ page }) => {
  const now = Date.now();
  const trade = {
    id: 9001, listingId: 1, itemId: 1, itemName: 'Stub Hat', buyerUserId: 1, sellerUserId: 999,
    price: 1.5, feeAmount: 0.03, state: 'PENDING_BUYER_CONFIRM', note: null,
    createdAt: now - 3_600_000, updatedAt: now - 300_000, settledAt: null,
    sentAt: now - 5 * 60_000, tradeOfferUrl: null, expiresAt: now + 5 * 86_400_000,
    unreadCount: 0, lastMessage: null, counterpartyTradeUrl: null, counterpartyName: 'StubSeller',
  };
  await page.route(/\/api\/trades(\?.*)?$/, (route) => route.fulfill({
    status: 200, contentType: 'application/json', headers: { 'X-Total-Count': '1' },
    body: JSON.stringify([trade]),
  }));
  await page.goto('/profile/trades');
  const sent = page.locator('.trade-sent-ago').first();
  await expect(sent).toBeVisible({ timeout: 15_000 });
  await expect(sent).toHaveText('· sent 5m ago');
});
