// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 41: market cards and rows, stall and profile card links,
// the stall return-date picker.

const ME = {
  id: 1,
  steamId64: '76561202255232729',
  displayName: 'Pass Tester',
  signedIn: true,
  sessionEpoch: 0,
};

const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });

async function anon(page) {
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json({ signedIn: false })));
}

async function signedIn(page) {
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json(ME)));
}

function listing(id, itemId, extra = {}) {
  return {
    id, price: 3, listingType: 'BUY_NOW', status: 'ACTIVE', sellerName: 'seller' + id, sellerUserId: 900 + id,
    listedAt: Date.now() - 1000,
    item: { id: itemId, name: 'Pass41 Hat ' + itemId, rarity: 'Standard', category: 'Hats', lowestPrice: 3, isListed: true },
    ...extra,
  };
}

test.describe('defect pass 41', () => {
  test('a market card\'s cart and heart buttons stay visible while they hold keyboard focus', async ({ page }) => {
    await anon(page);
    await page.route('**/api/listings?**', (r) => r.fulfill(json([listing(1, 11)])));
    await page.goto('/market');
    const card = page.locator('.grid-card').first();
    await expect(card).toBeVisible({ timeout: 20_000 });
    // The card's buttons (cart, heart, magnifier) come just before its name link in tab order.
    await card.locator('.grid-card-link').focus();
    await page.keyboard.press('Shift+Tab');
    const focused = page.locator('.grid-card :focus');
    await expect(focused).toHaveCount(1);
    await expect.poll(() => focused.evaluate((el) => getComputedStyle(el).opacity)).toBe('1');
    await page.keyboard.press('Shift+Tab');
    await expect.poll(() => focused.evaluate((el) => getComputedStyle(el).opacity)).toBe('1');
    await page.keyboard.press('Shift+Tab');
    await expect.poll(() => focused.evaluate((el) => getComputedStyle(el).opacity)).toBe('1');
  });

  test('a stall card links to the listing it opens, so a new tab lands there too', async ({ page }) => {
    await anon(page);
    await page.route('**/api/listings/stall/77*', (r) => r.fulfill(json({
      seller: { id: 77, displayName: 'Stall Seller' }, listings: [listing(5077, 5)],
    })));
    await page.goto('/stall/77');
    await expect(page.locator('.grid-card .grid-card-link').first()).toHaveAttribute('href', '/item/5?listing=5077', { timeout: 20_000 });
  });

  test('a table-view market row is a plain row whose item name is the link', async ({ page }) => {
    await anon(page);
    await page.addInitScript(() => localStorage.setItem('sb_market_view', 'table'));
    await page.route('**/api/listings?**', (r) => r.fulfill(json([listing(2, 12)])));
    await page.goto('/market');
    const row = page.locator('.listing-table tbody tr').first();
    await expect(row).toBeVisible({ timeout: 20_000 });
    await expect(page.locator('.listing-table tr[role="button"]')).toHaveCount(0);
    const link = row.getByRole('link', { name: 'Pass41 Hat 12' });
    await expect(link).toHaveAttribute('href', '/item/12');
    await link.focus();
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(/\/item\/12/);
  });

  test.describe('in a time zone behind UTC', () => {
    test.use({ timezoneId: 'America/Los_Angeles' });

    test('the stall return date picker allows today after 5pm', async ({ page }) => {
      await page.clock.setFixedTime(new Date('2026-10-15T18:00:00-07:00'));
      await signedIn(page);
      await page.route('**/api/listings/away', (r) => r.fulfill(json({ hidden: true, until: null })));
      await page.goto('/me/stall');
      await page.getByRole('button', { name: 'Schedule return' }).click({ timeout: 20_000 });
      const picker = page.getByLabel('Stall return date');
      await expect(picker).toHaveAttribute('min', '2026-10-15');
      await expect(picker).toHaveValue('2026-10-22');
    });
  });
});
