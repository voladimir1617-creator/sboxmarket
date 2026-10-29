// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 47.

const ME = {
  id: 1,
  steamId64: '76561202255232729',
  displayName: 'Pass Tester',
  signedIn: true,
  sessionEpoch: 0,
};

const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });

async function signedIn(page) {
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json(ME)));
}

async function anon(page) {
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json({ signedIn: false })));
}

test.describe('defect pass 47', () => {
  test('recent searches can be picked with the arrow keys and Enter', async ({ page }) => {
    await anon(page);
    await page.addInitScript(() => localStorage.setItem('sb_recent_searches', JSON.stringify(['alpha hat', 'beta boots'])));
    await page.goto('/market');
    const box = page.getByRole('combobox', { name: 'Search listings' });
    await expect(box).toBeVisible({ timeout: 20_000 });
    await box.focus();
    await expect(page.locator('#search-recent-listbox')).toBeVisible();
    await page.keyboard.press('ArrowDown');
    await page.keyboard.press('ArrowDown');
    await expect(box).toHaveAttribute('aria-activedescendant', 'search-recent-opt-1');
    await page.keyboard.press('Enter');
    await expect(box).toHaveValue('beta boots');
    await expect(page.getByRole('button', { name: 'Forget "alpha hat"' })).toHaveCount(0);
  });

  test('Back to a Deals view turns the Deals toggle back on', async ({ page }) => {
    await anon(page);
    await page.goto('/market');
    await expect(page.getByRole('combobox', { name: 'Search listings' })).toBeVisible({ timeout: 20_000 });
    await page.evaluate(() => {
      history.pushState({}, '', '/market?deals=1');
      window.dispatchEvent(new PopStateEvent('popstate'));
    });
    await expect(page.getByRole('button', { name: 'Remove Deals filter' })).toBeVisible();
    await expect.poll(() => page.evaluate(() => location.search)).toContain('deals=1');
  });

  test('the market and Database rarity filters say Scarce like the badge', async ({ page }) => {
    await anon(page);
    await page.goto('/market');
    const group = page.getByRole('radiogroup', { name: 'Rarity filter' });
    await expect(group.getByRole('radio', { name: /Scarce/ })).toBeVisible({ timeout: 20_000 });
    await expect(group.getByRole('radio', { name: /Off-Market/ })).toHaveCount(0);
    await page.route('**/api/database**', (r) => r.fulfill(json({ items: [], total: 0 })));
    await page.goto('/db');
    const sel = page.getByRole('combobox', { name: 'Filter catalogue by rarity' });
    await expect(sel).toBeVisible({ timeout: 20_000 });
    await expect.poll(() => sel.evaluate((el) => [...el.options].map((o) => o.textContent)))
      .toEqual(['All rarities', 'Limited', 'Scarce', 'Standard']);
  });

  test('the seller nudge says the trade is cancelled, not auto-released', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/trades', (r) => r.fulfill(json([
      { id: 47, state: 'PENDING_SELLER_ACCEPT', sellerUserId: 1, buyerUserId: 2, itemName: 'Late Hat',
        createdAt: Date.now() - 5 * 3600 * 1000, updatedAt: Date.now() - 5 * 3600 * 1000 },
    ])));
    await page.goto('/market');
    const nudge = page.locator('.pending-trade-nudge');
    await expect(nudge).toBeVisible({ timeout: 20_000 });
    await expect(nudge).toContainText('before the trade is cancelled and the buyer refunded');
    await expect(nudge).not.toContainText('auto-release');
  });

  test('each Following row names its seller on the mute and unfollow buttons', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/follows?**', (r) => r.fulfill(json([
      { id: 1, sellerUserId: 71, notificationsMuted: false },
      { id: 2, sellerUserId: 72, notificationsMuted: true },
    ])));
    await page.route('**/api/sellers/names?**', (r) => r.fulfill(json({ 71: 'Ann', 72: 'Bob' })));
    await page.goto('/profile/personal');
    await expect(page.getByRole('button', { name: 'Unfollow Ann' })).toBeVisible({ timeout: 20_000 });
    await expect(page.getByRole('button', { name: 'Unfollow Bob' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Mute new-listing pings from Ann' })).toBeVisible();
  });
  test('arrow keys on the review stars move the rating and the focus', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/trades', (r) => r.fulfill(json([
      { id: 5, state: 'VERIFIED', itemName: 'Good Hat', buyerUserId: 1, sellerUserId: 2,
        counterpartyName: 'Seller', price: 5, createdAt: Date.now() },
    ])));
    await page.route('**/api/reviews/mine*', (r) => r.fulfill(json([])));
    await page.goto('/profile/trades');
    await page.getByRole('button', { name: 'Leave Review' }).click({ timeout: 20_000 });
    const stars = page.getByRole('radiogroup', { name: 'Star rating (1 to 5)' });
    await stars.getByRole('radio', { name: '5 stars' }).focus();
    await page.keyboard.press('ArrowLeft');
    await expect(stars.getByRole('radio', { name: '4 stars' })).toBeFocused();
    await page.keyboard.press('ArrowLeft');
    await expect(stars.getByRole('radio', { name: '3 stars' })).toHaveAttribute('aria-checked', 'true');
    await expect(stars.getByRole('radio', { name: '3 stars' })).toBeFocused();
  });

  test('the Auto-accept chip fills the seller threshold rounded to the cent', async ({ page }) => {
    await signedIn(page);
    const item = { id: 4701, name: 'Offer Hat', lowestPrice: 1.15, rarity: 'Standard', category: 'Hats', isListed: true };
    await page.route('**/api/items/4701', (r) => r.fulfill(json(item)));
    await page.route('**/api/listings/item/4701*', (r) => r.fulfill(json([
      { id: 4747, price: 1.15, maxDiscount: 0.1, listingType: 'BUY_NOW', status: 'ACTIVE',
        sellerUserId: 9, sellerName: 'Seller', item },
    ])));
    await page.goto('/item/4701');
    await page.getByTestId('listing-offer-btn').first().click({ timeout: 20_000 });
    const chip = page.locator('.price-suggest-chip.accept');
    await expect(chip).toContainText('1.04');
    await chip.click();
    await expect(page.locator('.item-offer-drawer input[inputmode="decimal"]')).toHaveValue('1.04');
  });
});
