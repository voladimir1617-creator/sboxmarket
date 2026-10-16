// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 43.

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

function offer(id, name, createdAt) {
  return {
    id, listingId: 100 + id, itemName: name, amount: 5, askingPrice: 8, status: 'REJECTED', author: 'BUYER',
    buyerName: 'Buyer' + id, createdAt, expiresAt: createdAt + 86_400_000,
  };
}

test.describe('defect pass 43', () => {
  test('the From / To picker on profile Offers filters the rows, not just the CSV', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/offers/incoming*', (r) => r.fulfill(json([
      offer(1, 'January Hat', Date.UTC(2026, 0, 10)),
      offer(2, 'March Hat', Date.UTC(2026, 2, 10)),
    ])));
    await page.route('**/api/offers/outgoing*', (r) => r.fulfill(json([])));
    await page.goto('/profile/offers');
    await expect(page.getByText('January Hat')).toBeVisible({ timeout: 20_000 });
    await page.getByLabel('Date range start').fill('2026-03-01');
    await page.getByLabel('Date range end').fill('2026-03-31');
    await expect(page.getByText('March Hat')).toBeVisible();
    await expect(page.getByText('January Hat')).toHaveCount(0);
    await page.getByLabel('Date range start').fill('2026-05-01');
    await page.getByLabel('Date range end').fill('2026-05-31');
    await expect(page.getByText('No offers in these dates')).toBeVisible();
  });

  test('the home Live Auctions tile states the real anti-snipe rule', async ({ page }) => {
    await page.route('**/api/**', (r) => r.fulfill(json({})));
    await page.route('**/api/auth/steam/me', (r) => r.fulfill(json({ signedIn: false })));
    await page.goto('/');
    const tile = page.locator('.csfloat-home-tile', { hasText: 'Live Auctions' });
    await expect(tile).toContainText('last 30 seconds', { timeout: 20_000 });
    await expect(tile).not.toContainText('final minute');
    await expect(tile).toContainText('moves the close to 30 seconds after that bid');
    await expect(tile).not.toContainText('adds 30 more');
  });

  test('the live sales ticker: one tab stop per sale, and it pauses on hover', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await page.route('**/api/**', (r) => r.fulfill(json({})));
    await page.route('**/api/auth/steam/me', (r) => r.fulfill(json({ signedIn: false })));
    const sale = (id) => ({ listingId: id, itemId: id, itemName: 'Ticker Hat ' + id, price: 3, soldAt: Date.now() - 60_000, sellerName: 's' });
    await page.route('**/api/listings/recent-sales*', (r) => r.fulfill(json([sale(1), sale(2)])));
    await page.goto('/market');
    await expect(page.locator('.ticker-item')).toHaveCount(4, { timeout: 20_000 });
    await expect(page.locator('.ticker-item:not([aria-hidden="true"]):not([tabindex="-1"])')).toHaveCount(2);
    await page.locator('.ticker').hover();
    await expect.poll(() => page.locator('.ticker-track').evaluate((el) => getComputedStyle(el).animationPlayState)).toBe('paused');
  });

  test('sidebar rarity counts follow the New filter', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await page.route('**/api/**', (r) => r.fulfill(json({})));
    await page.route('**/api/auth/steam/me', (r) => r.fulfill(json({ signedIn: false })));
    const l = (id, age) => ({
      id, price: 3, listingType: 'BUY_NOW', status: 'ACTIVE', sellerName: 's', sellerUserId: 900 + id, listedAt: Date.now() - age,
      item: { id: 60 + id, name: 'Count Hat ' + id, rarity: 'Standard', category: 'Hats', lowestPrice: 3, isListed: true },
    });
    await page.route('**/api/listings?**', (r) => r.fulfill(json([l(1, 3 * 86_400_000), l(2, 3 * 86_400_000), l(3, 60_000)])));
    await page.goto('/market');
    const standard = page.getByRole('radiogroup', { name: 'Rarity filter' })
      .locator('[role="radio"]', { hasText: 'Standard' }).locator('.filter-count');
    await expect(standard).toHaveText('3', { timeout: 20_000 });
    await page.locator('.new-chip').first().click();
    await expect(standard).toHaveText('1');
  });

  test('sidebar filters are real radio groups: one tab stop, arrows change the choice', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await page.route('**/api/**', (r) => r.fulfill(json({})));
    await page.route('**/api/auth/steam/me', (r) => r.fulfill(json({ signedIn: false })));
    await page.goto('/market');
    const group = page.getByRole('radiogroup', { name: 'Rarity filter' });
    await expect(group.locator('[role="radio"]').first()).toBeVisible({ timeout: 20_000 });
    await expect(page.locator('details[role]')).toHaveCount(0);
    // The category bar above the grid replaces the sidebar's Category list.
    await expect(page.getByRole('radiogroup', { name: 'Category filter' }).locator('[role="radio"]').first()).toBeHidden();
    await expect(group.locator('[role="radio"][tabindex="0"]')).toHaveCount(1);
    const checked = group.locator('[role="radio"][aria-checked="true"]');
    const first = await checked.textContent();
    await checked.focus();
    await page.keyboard.press('ArrowDown');
    await expect(checked).not.toHaveText(first || '');
    await expect(group.locator('[role="radio"]:focus')).toHaveAttribute('aria-checked', 'true');
  });
});

