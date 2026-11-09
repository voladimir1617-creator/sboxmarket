// @ts-check
const { test, expect } = require('@playwright/test');

// The Make offer dialog is position:fixed with z-index 120, but that only
// counts inside the item page (z-index 1). The footer comes later with the
// same z-index 1, so once the page was scrolled down the footer painted over
// the dialog: its Send button could not be clicked and the footer was not
// dimmed. Seen in the full suite as decimal-comma's Send click timing out
// with "site-footer-col intercepts pointer events".

const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });
const ME = { id: 1, displayName: 'Offer Tester', signedIn: true, sessionEpoch: 0,
  tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc' };
const item = { id: 4992, name: 'Footer Hat', lowestPrice: 15, rarity: 'Standard', category: 'Hats', isListed: true };

test('the Make offer dialog stays above the footer when the page is scrolled to the bottom', async ({ page }) => {
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json(ME)));
  await page.route('**/api/items/4992', (r) => r.fulfill(json(item)));
  await page.route('**/api/listings/item/4992*', (r) => r.fulfill(json([
    { id: 4998, price: 15, listingType: 'BUY_NOW', status: 'ACTIVE', sellerUserId: 9, sellerName: 'Seller', item },
  ])));
  await page.goto('/item/4992');
  await page.getByTestId('listing-offer-btn').first().click({ timeout: 20_000 });
  const send = page.locator('[data-offer-send]');
  await expect(send).toBeVisible();
  await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
  await expect.poll(() => send.evaluate((btn) => {
    const r = btn.getBoundingClientRect();
    const top = document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2);
    return !!(top && btn.contains(top));
  })).toBe(true);
});
