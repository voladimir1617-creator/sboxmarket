// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 64.

const ME = {
  id: 1,
  steamId64: '76561202255232729',
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
// Marks the loaded page; a full browser reload wipes the mark.
async function markSpa(page) {
  await page.evaluate(() => { window.__spa = 1; });
}
const stillSpa = (page) => page.evaluate(() => window.__spa === 1);

const now = Date.now();
const trade = {
  id: 9001, listingId: 1, itemId: 1, itemName: 'Stub Hat', buyerUserId: 1, sellerUserId: 999,
  price: 1.5, feeAmount: 0.03, state: 'PENDING_BUYER_CONFIRM', note: null,
  createdAt: now - 3_600_000, updatedAt: now - 300_000, settledAt: null,
  sentAt: now - 5 * 60_000, tradeOfferUrl: null, expiresAt: now + 5 * 86_400_000,
  unreadCount: 0, lastMessage: null, counterpartyTradeUrl: null, counterpartyName: 'StubSeller',
};
async function trades(page, rows) {
  await page.route(/\/api\/trades(\?.*)?$/, (r) => r.fulfill({
    status: 200, contentType: 'application/json', headers: { 'X-Total-Count': String(rows.length) },
    body: JSON.stringify(rows),
  }));
}

test.describe('defect pass 64', () => {
  test('a user menu link opens its page without reloading the site', async ({ page }) => {
    await signedIn(page);
    await page.goto('/market');
    await page.getByRole('button', { name: /^User menu/ }).click({ timeout: 20_000 });
    await markSpa(page);
    await page.getByRole('menuitem', { name: 'Profile' }).click();
    await page.waitForURL('**/profile');
    expect(await stillSpa(page)).toBe(true);
  });

  test('a plain link inside a full page opens without reloading the site', async ({ page }) => {
    await signedIn(page);
    await trades(page, []);
    await page.goto('/profile/trades');
    const link = page.getByRole('link', { name: 'Sell an item' });
    await link.waitFor({ timeout: 20_000 });
    await markSpa(page);
    await link.click();
    await page.waitForURL('**/sell');
    expect(await stillSpa(page)).toBe(true);
  });

  test('the item and counterparty links on a trade row open without reloading the site', async ({ page }) => {
    await signedIn(page);
    await trades(page, [trade]);
    await page.goto('/profile/trades');
    const item = page.locator('a[title="Open item detail"]').first();
    await item.waitFor({ timeout: 20_000 });
    await markSpa(page);
    await item.click();
    await page.waitForURL('**/item/1');
    expect(await stillSpa(page)).toBe(true);
    await page.goto('/profile/trades');
    const cp = page.locator('a[href="/stall/999"]').first();
    await cp.waitFor({ timeout: 20_000 });
    await markSpa(page);
    await cp.click();
    await page.waitForURL('**/stall/999');
    expect(await stillSpa(page)).toBe(true);
  });

  test('the notification bell does not say "all caught up" when the list failed to load', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/notifications/unread-count*', (r) => r.fulfill(json({ unread: 3 })));
    await page.route('**/api/notifications?*', (r) => r.fulfill(json({ error: 'boom' }, 500)));
    await page.goto('/market');
    await page.getByRole('button', { name: /^Notifications/ }).first().click({ timeout: 20_000 });
    const empty = page.locator('.notif-dropdown .notif-empty');
    await expect(empty).toBeVisible();
    await expect(empty).toHaveText("Couldn't load notifications.");
  });

  test('the bell says "all caught up" once an empty list has loaded', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/notifications/unread-count*', (r) => r.fulfill(json({ unread: 0 })));
    await page.route('**/api/notifications?*', (r) => r.fulfill(json({ items: [], unread: 0 })));
    await page.goto('/market');
    await page.getByRole('button', { name: /^Notifications/ }).first().click({ timeout: 20_000 });
    await expect(page.locator('.notif-dropdown .notif-empty')).toHaveText("You're all caught up.");
  });

  test('the My Stall Sold date range narrows the sales shown, not only the CSV', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings/my-stall', (r) => r.fulfill(json([])));
    await page.route('**/api/listings/my-stall/sold', (r) => r.fulfill(json([
      { id: 1, price: 5, soldAt: '2026-01-10T00:00:00Z', item: { id: 1, name: 'Old Hat' } },
      { id: 2, price: 7, soldAt: '2026-09-10T00:00:00Z', item: { id: 2, name: 'New Hat' } },
    ])));
    await page.goto('/me/stall/sold');
    const rows = page.locator('.recent-sales-row');
    await expect(rows).toHaveCount(2, { timeout: 20_000 });
    await page.getByLabel('Date range start').fill('2026-06-01');
    await expect(rows).toHaveCount(1);
    await expect(rows).toContainText('New Hat');
    await expect(page.locator('.mystall-sold-label')).toHaveText('Gross · last 1 sale');
  });

  test('the Sell page buy-order chip says "1 stack has", not "1 stack have"', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/steam/inventory*', (r) => r.fulfill(json({ count: 2, items: [
      { assetId: 'a1', name: 'Listed Hat', catalogueId: 11, tradable: true, listableQuantity: 0, listedCount: 1, quantity: 1 },
      { assetId: 'a2', name: 'Free Hat', catalogueId: 12, tradable: true, listableQuantity: 1, quantity: 1 },
    ] })));
    await page.route('**/api/buy-orders/count/bulk*', (r) => r.fulfill(json({
      11: { count: 1, bestBid: 5 }, 12: { count: 1, bestBid: 2 },
    })));
    await page.goto('/sell');
    await expect(page.getByText(/liquid now/).first()).toBeVisible({ timeout: 20_000 });
    await expect(page.locator('[title*="a standing buy order"]').first()).toHaveAttribute('title', /^1 stack has a standing buy order/);
  });
});
