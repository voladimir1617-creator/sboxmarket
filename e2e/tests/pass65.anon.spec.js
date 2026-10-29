// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 65.

const ME = {
  id: 1,
  steamId64: '76561202255232729',
  displayName: 'Pass Tester',
  signedIn: true,
  sessionEpoch: 0,
  tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc',
};

const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });

async function signedIn(page, me = ME) {
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json(me)));
}
// Marks the loaded page; a full browser reload wipes the mark.
const markSpa = (page) => page.evaluate(() => { window.__spa = 1; });
const stillSpa = (page) => page.evaluate(() => window.__spa === 1);

async function clickStaysInApp(page, link, url) {
  await link.waitFor({ timeout: 20_000 });
  await markSpa(page);
  await link.click();
  await page.waitForURL(url);
  expect(await stillSpa(page)).toBe(true);
}

test.describe('defect pass 65', () => {
  test('an offer row\'s item link opens the item without reloading the site', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/offers/incoming*', (r) => r.fulfill(json([
      { id: 1, itemId: 7, itemName: 'Hat', listingId: 3, amount: '4.00', askingPrice: '5.00', status: 'PENDING' },
    ])));
    await page.route('**/api/offers/outgoing*', (r) => r.fulfill(json([])));
    await page.goto('/offers');
    await clickStaysInApp(page, page.locator('a.item-name', { hasText: 'Hat' }).first(), '**/item/7');
  });

  test('Buy Orders "Open Profile" for a missing trade URL opens the profile without reloading', async ({ page }) => {
    await signedIn(page, { ...ME, tradeUrl: null });
    await page.goto('/buy-orders');
    await clickStaysInApp(page, page.getByRole('link', { name: 'Open Profile' }).first(), '**/profile');
  });

  test('the staff-only page\'s "Back to marketplace" opens the market without reloading', async ({ page }) => {
    await signedIn(page);
    await page.goto('/admin');
    await clickStaysInApp(page, page.getByRole('link', { name: /Back to marketplace/ }), '**/market');
  });

  test('the offer drawer\'s "View offer" link opens Offers without reloading', async ({ page }) => {
    await signedIn(page);
    const item = { id: 7, name: 'Hat', category: 'Hats', rarity: 'Standard', lowestPrice: 5, isListed: true };
    await page.route('**/api/items/7', (r) => r.fulfill(json(item)));
    await page.route('**/api/items/7/history*', (r) => r.fulfill(json([])));
    await page.route('**/api/listings/item/7*', (r) => r.fulfill(json([
      { id: 3, price: '5.00', listingType: 'BUY_NOW', status: 'ACTIVE', sellerUserId: 42, sellerName: 'S', item },
    ])));
    await page.route('**/api/offers/outgoing*', (r) => r.fulfill(json([
      { id: 9, listingId: 3, status: 'PENDING', author: 'BUYER', amount: '4.00' },
    ])));
    await page.goto('/item/7');
    await page.getByTestId('listing-offer-btn').first().click({ timeout: 20_000 });
    await clickStaysInApp(page, page.getByRole('link', { name: /View offer/ }), '**/offers/outgoing');
  });

  test('the Buy Orders heading does not claim 0 orders when they failed to load', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/buy-orders*', (r) => r.fulfill(json({ error: 'boom' }, 500)));
    await page.goto('/buy-orders');
    await expect(page.getByText(/Couldn.t load buy orders/).first()).toBeVisible({ timeout: 20_000 });
    await expect(page.locator('h1.info-modal-header')).toHaveText(/^Buy Orders/);
    await expect(page.locator('h1.info-modal-header')).not.toContainText('· 0');
  });

  test('the Notifications heading does not claim 0 unread when they failed to load', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/notifications*', (r) => r.fulfill(json({ error: 'boom' }, 500)));
    await page.goto('/notifications');
    await expect(page.getByText(/Couldn.t load notifications/).first()).toBeVisible({ timeout: 20_000 });
    await expect(page.locator('h1.info-modal-header')).toHaveText(/^Notifications/);
    await expect(page.locator('h1.info-modal-header')).not.toContainText('0 unread');
  });
});
