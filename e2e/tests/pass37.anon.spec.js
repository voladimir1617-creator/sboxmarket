// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 37: profile, wallet history, reviews, buy orders, CSR.

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

const STAFF_CREDIT = {
  id: 5, type: 'DEPOSIT', amount: 10, status: 'COMPLETED', currency: 'USD',
  stripeReference: 'admin_42', description: null, createdAt: Date.now(),
};

test.describe('defect pass 37', () => {
  test('Profile Transactions and Wallet history never show the staff member id', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/wallet/transactions*', (r) => r.fulfill(json([STAFF_CREDIT])));
    await page.goto('/profile/transactions');
    await expect(page.locator('tr.db-row', { hasText: '#5' })).toBeVisible({ timeout: 20_000 });
    await expect(page.locator('tr.db-row', { hasText: '#5' })).toContainText('admin');
    await expect(page.getByText('admin_42')).toHaveCount(0);

    await page.goto('/wallet');
    await page.getByRole('tab', { name: 'History' }).click({ timeout: 20_000 });
    await expect(page.getByText(/#5/).first()).toBeVisible();
    await expect(page.getByText(/admin_42/)).toHaveCount(0);
  });

  test('deleting a given review brings its trade back to Pending without a reload', async ({ page }) => {
    await signedIn(page);
    let deleted = false;
    await page.route('**/api/reviews/mine*', (r) => r.fulfill(json(deleted ? [] : [{
      id: 9, tradeId: 3, rating: 5, comment: 'great', toUserId: 7, toUserName: 'bob', createdAt: Date.now(),
    }])));
    await page.route('**/api/reviews/pending*', (r) => r.fulfill(json(deleted
      ? { count: 1, items: [{ tradeId: 3, itemName: 'Pending Hat', sellerUserId: 7, sellerName: 'bob', settledAt: Date.now(), price: 4 }] }
      : { count: 0, items: [] })));
    await page.route('**/api/reviews/9', (r) => { deleted = true; return r.fulfill(json({ ok: true })); });
    page.on('dialog', (d) => d.accept());
    await page.goto('/profile/reviews');
    await page.getByRole('tab', { name: /^Given/ }).click();
    await page.getByRole('button', { name: 'Delete' }).click();
    await expect.poll(() => deleted).toBe(true);
    await expect(page.getByRole('tab', { name: /^Pending\s*1/ })).toBeVisible();
  });

  test('Buy Orders shows lifetime spend to a user whose orders have all filled', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/buy-orders*', (r) => r.fulfill(json([
      { id: 1, itemId: 11, itemName: 'Filled Hat', status: 'FILLED', maxPrice: 5, quantity: 0, originalQuantity: 2, createdAt: Date.now(), updatedAt: Date.now() },
    ])));
    await page.goto('/profile/buyorders');
    await expect(page.getByRole('region', { name: 'Buy orders capital summary' })).toContainText('Lifetime: ~$10.00', { timeout: 20_000 });
    await expect(page.getByText(/potential outlay/)).toHaveCount(0);
  });

  test('an order priced above the Buy Now floor reads "above floor", not "= floor"', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/buy-orders*', (r) => r.fulfill(json([
      { id: 2, itemId: 12, itemName: 'Above Hat', status: 'ACTIVE', maxPrice: 60, quantity: 1, originalQuantity: 1,
        currentFloor: 50, floorGap: -10, queuePosition: 1, createdAt: Date.now(), updatedAt: Date.now() },
    ])));
    await page.goto('/profile/buyorders');
    await expect(page.getByText('Floor $50.00 · above floor')).toBeVisible({ timeout: 20_000 });
  });

  test('CSR queue shows a 30-minute wait and the cap in USD', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/csr/check', (r) => r.fulfill(json({ csr: true })));
    await page.route('**/api/csr/stats', (r) => r.fulfill(json({
      openTickets: 1, waitingStaff: 1, waitingUser: 0, oldestWaitingAgeMs: 30 * 60_000, creditCap: 50,
    })));
    await page.goto('/csr');
    const wait = page.locator('.admin-stat', { hasText: 'Oldest wait' });
    await expect(wait.locator('.admin-stat-val')).toHaveText('30m', { timeout: 20_000 });
    await expect(page.locator('.admin-stat', { hasText: 'Goodwill credit cap' }).locator('.admin-stat-val')).toHaveText('$50.00 USD');
  });

  test('arrow keys move focus with the selected tab on the CSR panel and the profile', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/csr/check', (r) => r.fulfill(json({ csr: true })));
    await page.goto('/csr');
    const queue = page.getByRole('tab', { name: 'Queue' });
    await queue.focus();
    await page.keyboard.press('ArrowRight');
    await expect(page.getByRole('tab', { name: 'User Lookup' })).toBeFocused();
    await expect(page).toHaveURL(/\/csr\?tab=lookup$/);

    await page.goto('/profile/personal');
    const personal = page.getByRole('tab', { name: 'Personal Info' });
    await personal.focus();
    await page.keyboard.press('ArrowRight');
    await expect(page.getByRole('tab', { name: 'Listings' })).toBeFocused();
  });

  test('the Portfolio tooltip says listings count at the asking price', async ({ page }) => {
    await signedIn(page);
    await page.goto('/profile/personal');
    await expect(page.getByText('Portfolio', { exact: true })).toHaveAttribute('title',
      'Items you own at their current market floor, plus your active listings at your asking price.', { timeout: 20_000 });
  });
});
