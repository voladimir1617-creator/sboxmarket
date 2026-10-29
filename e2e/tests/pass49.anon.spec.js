// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 49.

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

const item = { id: 4901, name: 'Offer Hat', lowestPrice: 2, rarity: 'Standard', category: 'Hats', isListed: true };

async function itemPage(page) {
  await page.route('**/api/items/4901', (r) => r.fulfill(json(item)));
  await page.route('**/api/listings/item/4901*', (r) => r.fulfill(json([
    { id: 4949, price: 2, listingType: 'BUY_NOW', status: 'ACTIVE', sellerUserId: 9, sellerName: 'Seller', item },
  ])));
}

test.describe('defect pass 49', () => {
  test('Enter in the Make Offer amount sends the offer', async ({ page }) => {
    await signedIn(page);
    await itemPage(page);
    let posted = null;
    await page.route('**/api/offers', (r) => {
      if (r.request().method() !== 'POST') return r.fulfill(json([]));
      posted = r.request().postDataJSON();
      return r.fulfill(json({ id: 1, status: 'PENDING', amount: posted.amount, listingId: posted.listingId }));
    });
    await page.goto('/item/4901');
    await page.getByTestId('listing-offer-btn').first().click({ timeout: 20_000 });
    const input = page.getByRole('textbox', { name: 'Offer amount in USD' });
    await input.fill('1.50');
    await input.press('Enter');
    await expect.poll(() => posted).not.toBeNull();
    expect(posted.amount).toBeCloseTo(1.5, 2);
  });

  test('Enter in the profile counter box sends the counter', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/offers/incoming', (r) => r.fulfill(json([
      { id: 77, status: 'PENDING', amount: 1, askingPrice: 3, itemName: 'Counter Hat', buyerName: 'Bob',
        listingId: 5, createdAt: Date.now(), expiresAt: Date.now() + 86_400_000 },
    ])));
    await page.route('**/api/offers/outgoing', (r) => r.fulfill(json([])));
    let countered = null;
    await page.route('**/api/offers/77/counter', (r) => {
      countered = r.request().postDataJSON();
      return r.fulfill(json({ id: 78, status: 'PENDING', amount: countered.amount }));
    });
    await page.goto('/profile/offers');
    await page.getByRole('button', { name: 'Counter offer' }).click({ timeout: 20_000 });
    const box = page.getByRole('textbox', { name: 'Counter price in USD' });
    await expect(box).toBeFocused();
    await box.fill('2.50');
    await box.press('Enter');
    await expect.poll(() => countered).not.toBeNull();
    expect(countered.amount).toBeCloseTo(2.5, 2);
  });

  test('unwatching an item with a price alert stops showing the alert as set', async ({ page }) => {
    await signedIn(page);
    await itemPage(page);
    await page.addInitScript(() => localStorage.setItem('sb_watchlist', JSON.stringify([4901])));
    await page.route('**/api/watchlist/alerts', (r) => r.fulfill(json([
      { id: 1, itemId: 4901, status: 'ACTIVE', targetPrice: 1.5 },
    ])));
    await page.route('**/api/watchlist/4901', (r) => r.fulfill(json({ ok: true })));
    await page.goto('/item/4901');
    const unwatch = page.getByRole('button', { name: 'Remove Offer Hat from watchlist' }).first();
    await unwatch.click({ timeout: 20_000 });
    await expect(page.getByText('Your price alert on Offer Hat was cancelled too.')).toBeVisible();
  });

  test('saving a search under an existing name replaces it instead of listing it twice', async ({ page }) => {
    await page.route('**/api/**', (r) => r.fulfill(json({})));
    await page.route('**/api/auth/steam/me', (r) => r.fulfill(json({ signedIn: false })));
    await page.addInitScript(() => {
      if (sessionStorage.getItem('seeded')) return;
      sessionStorage.setItem('seeded', '1');
      localStorage.setItem('sb_saved_searches', JSON.stringify([
        { id: 1, name: 'Cheap', search: '', category: 'Hats', rarity: 'All', sort: 'price_desc', savedAt: 1 },
      ]));
    });
    page.on('dialog', (d) => d.accept());
    await page.goto('/market?category=Boots');
    await page.locator('button', { hasText: 'Save search' }).first().click({ timeout: 20_000 });
    const name = page.getByRole('textbox', { name: 'Saved search name' });
    await name.fill('Cheap');
    await name.press('Enter');
    await expect(page.getByText('Updated search "Cheap"', { exact: false })).toBeVisible();
    const saved = await page.evaluate(() => JSON.parse(localStorage.getItem('sb_saved_searches') || '[]'));
    expect(saved.filter((s) => s.name === 'Cheap')).toHaveLength(1);
    expect(saved.find((s) => s.name === 'Cheap').category).toBe('Boots');
  });

  test('a double click on Clone makes one copy', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/loadouts/49', (r) => r.fulfill(json({
      loadout: { id: 49, name: 'Their Fit', ownerUserId: 2, ownerName: 'Ann', favorites: 0, visibility: 'PUBLIC' }, slots: [],
    })));
    let clones = 0;
    await page.route('**/api/loadouts/49/clone', (r) => {
      clones += 1;
      return new Promise((res) => setTimeout(res, 300)).then(() => r.fulfill(json({ id: 4900 + clones, name: 'Their Fit (copy)' })));
    });
    await page.goto('/loadout/49');
    await page.getByRole('button', { name: '⎘ Clone' }).dblclick({ timeout: 20_000 });
    await page.waitForTimeout(800);
    expect(clones).toBe(1);
  });
  test('Security activity names trade auto-release and Trade Protection rows', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/profile/security-activity', (r) => r.fulfill(json([
      { id: 1, eventType: 'TRADE_AUTO_RELEASED', createdAt: Date.now() },
      { id: 2, eventType: 'TRADE_PROTECTION_CLAIMED', createdAt: Date.now() },
    ])));
    await page.goto('/profile/personal');
    await page.locator('.profile-row', { hasText: 'Security activity' }).getByRole('button').first().click({ timeout: 20_000 });
    await expect(page.getByText('Trade auto-released')).toBeVisible();
    await expect(page.getByText('Trade Protection paid out')).toBeVisible();
    await expect(page.getByText('TRADE_PROTECTION_CLAIMED')).toHaveCount(0);
  });

  test('the new-ticket banner does not promise trade tickets are answered first', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/support/tickets', (r) => r.fulfill(json([])));
    await page.goto('/profile/support');
    await page.getByRole('button', { name: '+ New Ticket' }).first().click({ timeout: 20_000 });
    await page.getByRole('combobox', { name: 'Ticket category' }).selectOption('TRADE');
    await expect(page.getByText('Staff answer tickets as soon as they can.', { exact: false })).toBeVisible();
    await expect(page.getByText('answered first', { exact: false })).toHaveCount(0);
  });

  test('the API key label box is named and capped at the 80 characters the server keeps', async ({ page }) => {
    await signedIn(page);
    await page.goto('/profile/developers');
    const box = page.getByRole('textbox', { name: 'API key label' });
    await expect(box).toBeVisible({ timeout: 20_000 });
    await expect(box).toHaveAttribute('maxlength', '80');
  });
});
