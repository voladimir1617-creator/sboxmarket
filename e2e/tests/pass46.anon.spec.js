// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 46.

const ME = {
  id: 1,
  steamId64: '76561199000000001',
  displayName: 'Pass Tester',
  signedIn: true,
  sessionEpoch: 0,
};

const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });

async function signedIn(page) {
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json(ME)));
}

const focusIsTab = (page, name) => page.evaluate((n) =>
  document.activeElement?.getAttribute('role') === 'tab' && (document.activeElement.textContent || '').includes(n), name);

test.describe('defect pass 46', () => {
  test('the user menu exposes its items and Escape hands focus back to the chip', async ({ page }) => {
    await signedIn(page);
    await page.goto('/market');
    const chip = page.locator('.user-chip');
    await expect(chip).toBeVisible({ timeout: 20_000 });
    await chip.focus();
    await page.keyboard.press('Enter');
    const items = page.locator('#user-menu-panel [role="menuitem"]');
    await expect(items.first()).toBeVisible();
    expect(await items.count()).toBeGreaterThan(5);
    await items.nth(2).focus();
    await page.keyboard.press('Escape');
    await expect(page.locator('#user-menu-panel')).toHaveCount(0);
    await expect(chip).toBeFocused();
  });

  test('arrow keys on the Watchlist tabs move focus with the choice', async ({ page }) => {
    await page.route('**/api/**', (r) => r.fulfill(json({})));
    await page.route('**/api/auth/steam/me', (r) => r.fulfill(json({ signedIn: false })));
    await page.addInitScript(() => localStorage.setItem('sb_watchlist', JSON.stringify([999046])));
    const item = { id: 999046, name: 'Watched Hat', lowestPrice: 3, rarity: 'Standard', category: 'Hats', isListed: true };
    await page.route('**/api/items/batch**', (r) => r.fulfill(json([item])));
    await page.route('**/api/listings?**', (r) => r.fulfill(json([
      { id: 46, price: 3, listingType: 'BUY_NOW', status: 'ACTIVE', sellerName: 's', item },
    ])));
    await page.goto('/watchlist/all');
    const tabs = page.locator('[role="tablist"][aria-label="Watchlist filter"] [role="tab"]');
    await expect(tabs.first()).toBeVisible({ timeout: 20_000 });
    await tabs.first().focus();
    await page.keyboard.press('ArrowRight');
    await expect(tabs.nth(1)).toHaveAttribute('aria-selected', 'true');
    await expect(tabs.nth(1)).toBeFocused();
  });

  test('arrow keys on the Loadout tabs move focus with the choice', async ({ page }) => {
    await signedIn(page);
    await page.goto('/loadout');
    const tabs = page.locator('[role="tablist"][aria-label="Loadout sections"] [role="tab"]');
    await expect(tabs.nth(1)).toBeVisible({ timeout: 20_000 });
    await tabs.first().focus();
    await page.keyboard.press('ArrowRight');
    await expect(tabs.nth(1)).toHaveAttribute('aria-selected', 'true');
    await expect.poll(() => focusIsTab(page, 'My Loadouts')).toBe(true);
  });

  test('a type-filtered grid emptied by New says nothing matches, not "no live auctions"', async ({ page }) => {
    await page.route('**/api/**', (r) => r.fulfill(json({})));
    await page.route('**/api/auth/steam/me', (r) => r.fulfill(json({ signedIn: false })));
    const item = { id: 46001, name: 'Old Auction Hat', lowestPrice: 3, rarity: 'Standard', category: 'Hats' };
    await page.route('**/api/listings?**', (r) => r.fulfill(json([
      { id: 4601, price: 3, currentBid: 5, listingType: 'AUCTION', status: 'ACTIVE', sellerName: 's', item,
        listedAt: Date.now() - 5 * 86_400_000, expiresAt: Date.now() + 86_400_000 },
    ])));
    await page.goto('/market?type=AUCTION&new=1');
    await expect(page.getByText('No listings match your filters')).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText('No live auctions right now')).toHaveCount(0);
  });

  test('a Deals-only market view offers the Copy Filtered URL button', async ({ page }) => {
    await page.route('**/api/**', (r) => r.fulfill(json({})));
    await page.route('**/api/auth/steam/me', (r) => r.fulfill(json({ signedIn: false })));
    await page.goto('/market?deals=1');
    await expect(page.getByRole('button', { name: /Copy Filtered URL/ })).toBeVisible({ timeout: 20_000 });
  });

  test('watchlist price sorts use an auction\'s current bid, the price its card shows', async ({ page }) => {
    await page.route('**/api/**', (r) => r.fulfill(json({})));
    await page.route('**/api/auth/steam/me', (r) => r.fulfill(json({ signedIn: false })));
    await page.addInitScript(() => localStorage.setItem('sb_watchlist', JSON.stringify([46101, 46102])));
    const bidUp = { id: 46101, name: 'Bid Up Hat', lowestPrice: 1, rarity: 'Standard', category: 'Hats', isListed: true };
    const flat = { id: 46102, name: 'Flat Hat', lowestPrice: 10, rarity: 'Standard', category: 'Hats', isListed: true };
    await page.route('**/api/items/batch**', (r) => r.fulfill(json([bidUp, flat])));
    await page.route('**/api/listings?**', (r) => r.fulfill(json([
      { id: 4611, price: 1, currentBid: 40, listingType: 'AUCTION', status: 'ACTIVE', sellerName: 's', item: bidUp, expiresAt: Date.now() + 86_400_000 },
      { id: 4612, price: 10, listingType: 'BUY_NOW', status: 'ACTIVE', sellerName: 's', item: flat },
    ])));
    await page.goto('/watchlist/all');
    await expect(page.getByText('Flat Hat').first()).toBeVisible({ timeout: 20_000 });
    const sort = page.locator('select').filter({ has: page.locator('option[value="price_asc"]') }).first();
    await sort.selectOption('price_asc');
    const order = async () => page.evaluate(() => {
      const t = document.body.innerText;
      return t.indexOf('Flat Hat') < t.indexOf('Bid Up Hat') ? 'flat-first' : 'bid-first';
    });
    await expect.poll(order).toBe('flat-first');
  });
});
