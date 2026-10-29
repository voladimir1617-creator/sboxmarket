// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 56.

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
const cad = (page) => page.addInitScript(() => localStorage.setItem('sb_currency', 'CAD'));

async function itemPage(page, rarity = 'Standard') {
  await signedIn(page);
  const item = { id: 5601, name: 'Alert Hat', category: 'Hats', rarity, lowestPrice: 5, isListed: true };
  await page.route('**/api/items/5601', (r) => r.fulfill(json(item)));
  await page.route('**/api/listings/item/5601*', (r) => r.fulfill(json([
    { id: 5602, price: 5, listingType: 'BUY_NOW', status: 'ACTIVE', sellerUserId: 2, sellerName: 'bob', item },
  ])));
  await page.route('**/api/watchlist/alerts', (r) => r.fulfill(json([])));
  await page.goto('/item/5601');
}

test.describe('defect pass 56', () => {
  test('the price alert panel quotes the floor and target in dollars, like its input', async ({ page }) => {
    await cad(page);
    await itemPage(page);
    await page.getByRole('button', { name: /Set Price Alert/ }).first().click({ timeout: 20_000 });
    const box = page.getByRole('textbox', { name: 'Price alert target (USD)' });
    await box.fill('4.25');
    await expect(page.getByText('Current floor $5.00', { exact: false })).toBeVisible();
    await expect(page.getByText("We'll ping you when any listing on this item drops to $4.25 or lower.")).toBeVisible();
  });

  test('the item title chip names the Off-Market tier "Scarce", like its badge', async ({ page }) => {
    await itemPage(page, 'Off-Market');
    const chip = page.locator('.modal-name-rarity').first();
    await expect(chip).toHaveText('Scarce', { timeout: 20_000 });
    await expect(chip).toHaveAttribute('href', '/market?rarity=Off-Market');
  });

  test('the market price chips are in dollars, like the boxes they fill', async ({ page }) => {
    await cad(page);
    await signedIn(page);
    await page.route('**/api/listings?*', (r) => r.fulfill(json([])));
    await page.goto('/market');
    await expect(page.getByRole('button', { name: '$2.00–$5.00' }).first()).toBeVisible({ timeout: 20_000 });
  });

  test('the market rarity filter chip says Scarce for Off-Market', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings?*', (r) => r.fulfill(json([])));
    await page.goto('/market?rarity=Off-Market');
    await expect(page.getByRole('button', { name: 'Remove rarity filter: Scarce' })).toBeVisible({ timeout: 20_000 });
  });

  test('stall rarity chips say Scarce for Off-Market', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings/stall/7', (r) => r.fulfill(json({
      seller: { id: 7, displayName: 'bob' }, count: 2,
      listings: [
        { id: 1, price: 3, listingType: 'BUY_NOW', status: 'ACTIVE', item: { id: 5, name: 'Hat', rarity: 'Off-Market' } },
        { id: 2, price: 4, listingType: 'BUY_NOW', status: 'ACTIVE', item: { id: 6, name: 'Cap', rarity: 'Standard' } },
      ],
    })));
    await page.route('**/api/listings/stall/7/**', (r) => r.fulfill(json([])));
    await page.route('**/api/reviews/**', (r) => r.fulfill(json([])));
    await page.goto('/stall/7');
    await expect(page.getByRole('button', { name: 'Scarce · 1' })).toBeVisible({ timeout: 20_000 });
  });

  test('the trade URL copy button is named', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/trades*', (r) => r.fulfill(json([
      { id: 9, state: 'PENDING_SELLER_SEND', sellerUserId: 1, buyerUserId: 2, itemName: 'Hat', price: 5,
        counterpartyName: 'bob', counterpartyTradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=2&token=x', createdAt: Date.now() },
    ])));
    await page.route('**/api/reviews/mine', (r) => r.fulfill(json([])));
    await page.goto('/profile/trades');
    await expect(page.getByRole('button', { name: 'Copy trade URL' })).toBeVisible({ timeout: 20_000 });
  });

  test('countering an offer shows the offer and ask in dollars beside the USD box', async ({ page }) => {
    await cad(page);
    await signedIn(page);
    await page.route('**/api/offers/incoming*', (r) => r.fulfill(json([
      { id: 3, status: 'PENDING', author: 'BUYER', amount: 10, askingPrice: 20, itemName: 'Hat', listingId: 1, buyerName: 'al', createdAt: Date.now() },
    ])));
    await page.route('**/api/offers/outgoing*', (r) => r.fulfill(json([])));
    await page.goto('/offers/incoming');
    await page.getByRole('button', { name: /^Counter/ }).first().click({ timeout: 20_000 });
    await expect(page.getByText('$10.00', { exact: true })).toBeVisible();
    await expect(page.getByText('$20.00', { exact: true })).toBeVisible();
  });

  test('the muted-types banner has no stray leading dash', async ({ page }) => {
    await page.addInitScript(() => localStorage.setItem('sb_mute_kinds', '["MATCHES"]'));
    await signedIn(page);
    await page.route('**/api/notifications*', (r) => r.fulfill(json({
      items: [{ id: 1, kind: 'TRADE_SENT', title: 'x', read: false, createdAt: Date.now() }], unread: 1,
    })));
    await page.goto('/notifications');
    const strong = page.getByText('1 notification type muted');
    await expect(strong).toBeVisible({ timeout: 20_000 });
    const text = await strong.evaluate((el) => el.parentElement.textContent);
    expect(text.startsWith('1 notification type muted')).toBe(true);
  });

  test('the shortcut list does not promise Esc leaves a page', async ({ page }) => {
    await signedIn(page);
    await page.goto('/help');
    await page.keyboard.press('?');
    await expect(page.getByText('Close the current item detail or dialog').first()).toBeVisible({ timeout: 20_000 });
  });
});
