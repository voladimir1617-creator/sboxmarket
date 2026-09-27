// @ts-check
const { test, expect } = require('@playwright/test');

/**
 * Claims the site makes about safety must be true. Three were not:
 *   - every profile in good standing wore a CSS-injected "KYC Approved" pill,
 *     although no identity check exists anywhere on the platform;
 *   - the Trade Protection upsell said "if this trade fails through no fault
 *     of yours, get $X refunded automatically", implying an unprotected buyer
 *     is not refunded, while every cancelled / timed-out trade IS refunded;
 *   - the buy confirm listed the optional protection as a priced row between
 *     the item price and a total that did not include it.
 */

test('the profile makes no identity-verification claim', async ({ page }) => {
  await page.goto('/profile');
  const name = page.locator('.profile-name').first();
  await expect(name).toBeVisible({ timeout: 15_000 });
  // The pill was an ::after on the name, so it never showed up in innerText.
  const after = await name.evaluate((el) => getComputedStyle(el, '::after').content);
  expect(after).not.toMatch(/KYC/i);
});

test('the Trade Protection offer says unprotected trades are refunded too', async ({ page }) => {
  const now = Date.now();
  const trade = {
    id: 9002, listingId: 1, itemId: 1, itemName: 'Stub Hat', buyerUserId: 1, sellerUserId: 999,
    price: 4, feeAmount: 0.08, state: 'PENDING_SELLER_ACCEPT', note: null, createdAt: now - 60_000,
    updatedAt: now - 60_000, settledAt: null, sentAt: null, expiresAt: now + 3 * 86_400_000,
    unreadCount: 0, lastMessage: null, counterpartyTradeUrl: null, counterpartyName: 'StubSeller',
  };
  await page.route(/\/api\/trades(\?.*)?$/, (r) => r.fulfill({
    status: 200, contentType: 'application/json', headers: { 'X-Total-Count': '1' }, body: JSON.stringify([trade]),
  }));
  await page.route('**/api/trades/9002/protection', (r) => r.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify({ tradeId: 9002, protected: false, protection: null }),
  }));
  await page.route('**/api/trade-protection/quote*', (r) => r.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ price: 4, fee: 0.25, ratePercent: 2, minFee: 0.25, coverageAmount: 4 }),
  }));
  await page.goto('/profile/trades');
  const copy = page.locator('.trade-protection-copy').first();
  await expect(copy).toBeVisible({ timeout: 15_000 });
  await expect(copy).toContainText('refunded automatically either way');
  await expect(copy).toContainText('dispute');
});

test('the buy confirm prices only what the click charges', async ({ page }) => {
  const ITEM_ID = 525252;
  const item = { id: ITEM_ID, name: 'Confirm Test Hat', category: 'Hats', rarity: 'Standard', steamPrice: 5, lowestPrice: 3 };
  await page.route(`**/api/listings/item/${ITEM_ID}*`, (r) => r.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify([{ id: 9903, listingType: 'BUY_NOW', status: 'ACTIVE', price: 3, sellerUserId: 999, sellerName: 'StubSeller', listedAt: Date.now(), item }]),
  }));
  await page.route(`**/api/items/${ITEM_ID}`, (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(item) }));
  await page.goto(`/item/${ITEM_ID}`);
  await page.locator('.item-rail-actions-buy').first().click({ timeout: 15_000 });
  const dialog = page.getByRole('dialog', { name: 'Confirm purchase' });
  await expect(dialog).toBeVisible();
  // Every priced row above the total adds up to the total.
  const rows = await dialog.locator('.cart-confirm-row .cart-confirm-amt').allInnerTexts();
  const sum = rows.reduce((a, t) => a + Number(t.replace(/[^0-9.]/g, '')), 0);
  const total = Number((await dialog.locator('.cart-confirm-total-amt').innerText()).replace(/[^0-9.]/g, ''));
  expect(sum).toBeCloseTo(total, 2);
  await expect(dialog.getByTestId('buy-confirm-protection-note')).toContainText('refunded either way');
  await dialog.getByRole('button', { name: 'Cancel' }).click();
});
