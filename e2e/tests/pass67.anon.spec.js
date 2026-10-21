// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 67.

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
const offer = { id: 1, listingId: 9, itemId: 5, itemName: 'Red Hat', amount: '5.00', askingPrice: '10.00', status: 'PENDING', author: 'BUYER', createdAt: Date.now() };
async function offers(page) {
  await page.route('**/api/offers/incoming*', (r) => r.fulfill(json([offer])));
  await page.route('**/api/offers/outgoing*', (r) => r.fulfill(json([])));
}
// Back from the page a link opened returns to the page it was clicked on,
// not to an extra /market entry pushed in between.
async function backReturns(page, link, dest, origin) {
  await link.click({ timeout: 20_000 });
  await expect(page).toHaveURL(dest);
  await page.goBack();
  await expect(page).toHaveURL(origin);
}

test.describe('defect pass 67', () => {
  test('Back from Support returns to Help, not the market', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/support/tickets*', (r) => r.fulfill(json([])));
    await page.goto('/help');
    await backReturns(page, page.getByRole('button', { name: /Contact Support/ }).first(), /\/support$/, /\/help$/);
  });

  test('Back from Support returns to the FAQ, not the market', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/support/tickets*', (r) => r.fulfill(json([])));
    await page.goto('/faq');
    await backReturns(page, page.getByRole('link', { name: /Open a support ticket/ }).first(), /\/support$/, /\/faq$/);
  });

  test('Back from a notification\'s target returns to Notifications', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/notifications*', (r) => r.fulfill(json({
      items: [{ id: 1, kind: 'OFFER_RECEIVED', title: 'New offer', body: 'x', path: '/offers', read: true, createdAt: Date.now() }], unread: 0,
    })));
    await page.goto('/notifications');
    await backReturns(page, page.getByText('New offer').first(), /\/offers$/, /\/notifications$/);
  });

  test('Back from an offer\'s item returns to Offers', async ({ page }) => {
    await signedIn(page);
    await offers(page);
    await page.goto('/offers');
    await backReturns(page, page.locator('a.item-name', { hasText: 'Red Hat' }).first(), /\/item\/5$/, /\/offers$/);
  });

  test('Back from a watchlist item returns to the watchlist', async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem('sb_watchlist', '[5]');
      localStorage.setItem('sb_watchlist_synced:1', '1');
    });
    await signedIn(page);
    await page.route(/\/api\/watchlist$/, (r) => r.fulfill(json([5])));
    await page.route('**/api/listings*', (r) => r.fulfill(json([
      { id: 77, price: '4.00', listingType: 'BUY_NOW', status: 'ACTIVE', sellerUserId: 2, item: { id: 5, name: 'Red Hat', category: 'Hats', rarity: 'Common', imageUrl: '' } },
    ])));
    await page.goto('/watchlist');
    await backReturns(page, page.locator('.grid-card-link').first(), /\/item\/5$/, /\/watchlist$/);
  });

  test('"+ New Ticket" puts the caret in the subject box', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/support/tickets*', (r) => r.fulfill(json([])));
    await page.goto('/support');
    await page.getByRole('button', { name: '+ New Ticket' }).first().click({ timeout: 20_000 });
    await expect(page.getByLabel('Ticket subject')).toBeFocused();
  });

  test('editing the Steam trade URL puts the caret in the box', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/profile/me', (r) => r.fulfill(json({ user: { id: 1, displayName: 'Pass Tester', tradeUrl: ME.tradeUrl } })));
    await page.goto('/profile');
    await page.getByRole('button', { name: 'Edit Steam trade URL' }).click({ timeout: 20_000 });
    await expect(page.locator('input[type="url"][aria-label="Steam trade URL"]')).toBeFocused();
  });

  test('Escape in the counter-offer note closes the counter drawer', async ({ page }) => {
    await signedIn(page);
    await offers(page);
    await page.goto('/offers');
    await page.getByRole('button', { name: 'Counter', exact: true }).first().click({ timeout: 20_000 });
    const note = page.getByLabel('Optional note with your counter-offer');
    await note.focus();
    await note.press('Escape');
    await expect(note).toHaveCount(0);
  });
});
