// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 51.

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

function notes(n, over = {}) {
  const now = Date.now();
  return Array.from({ length: n }, (_, i) => ({
    id: i + 1, kind: 'TRADE_SENT', title: `Note ${i + 1}`, body: 'b', read: false, createdAt: now - i * 1000, ...over,
  }));
}

test.describe('defect pass 51', () => {
  test('a saved-search match without a path opens the market from the bell, as on the notifications page', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/notifications?*', (r) => r.fulfill(json({
      items: [{ id: 1, kind: 'LISTING_MATCH', title: 'New match', body: 'b', read: false, refId: 77, path: null, createdAt: Date.now() }],
      unread: 1,
    })));
    await page.goto('/profile');
    await page.getByRole('button', { name: /^Notifications/ }).first().click({ timeout: 20_000 });
    await page.locator('.notif-dropdown [role="menuitem"]', { hasText: 'New match' }).click();
    await expect(page).toHaveURL(/\/market/);
  });

  test('the bell\'s "View all" link does not print a count of only the rows it loaded', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/notifications?*', (r) => r.fulfill(json({ items: notes(20), unread: 20 })));
    await page.goto('/market');
    await page.getByRole('button', { name: /^Notifications/ }).first().click({ timeout: 20_000 });
    const link = page.locator('.notif-dropdown').getByText(/View all notifications/);
    await expect(link).toBeVisible();
    await expect(link).toHaveText('View all notifications');
  });

  test('the notifications page All / Unread tabs say which is on', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/notifications*', (r) => r.fulfill(json({ items: notes(2), unread: 2 })));
    await page.goto('/notifications');
    const all = page.locator('.offer-tab', { hasText: /^All/ });
    await expect(all).toHaveAttribute('aria-pressed', 'true', { timeout: 20_000 });
    await expect(page.locator('.offer-tab', { hasText: /^Unread/ })).toHaveAttribute('aria-pressed', 'false');
  });

  test('Enter saves a buy-order edit and Escape cancels it', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/buy-orders*', (r) => r.fulfill(json([
      { id: 3, itemId: 13, itemName: 'Edit Hat', status: 'ACTIVE', maxPrice: 2, quantity: 2, originalQuantity: 2,
        createdAt: Date.now(), updatedAt: Date.now() },
    ])));
    let put = null;
    await page.route('**/api/buy-orders/3', (r) => {
      if (r.request().method() !== 'PUT') return r.fallback();
      put = r.request().postDataJSON();
      return r.fulfill(json({ id: 3, maxPrice: put.maxPrice, quantity: put.quantity, status: 'ACTIVE' }));
    });
    await page.goto('/profile/buyorders');
    await page.getByRole('button', { name: /Edit/ }).first().click({ timeout: 20_000 });
    const max = page.getByRole('spinbutton', { name: 'New max price per item in USD' });
    await max.press('Escape');
    await expect(max).toHaveCount(0);
    await page.getByRole('button', { name: /Edit/ }).first().click();
    await max.fill('2.50');
    await max.press('Enter');
    await expect.poll(() => put).not.toBeNull();
    expect(put.maxPrice).toBeCloseTo(2.5, 2);
  });

  test('the Pending reviews view keeps the tabs announced as tabs', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/reviews/mine*', (r) => r.fulfill(json([])));
    await page.route('**/api/reviews/pending*', (r) => r.fulfill(json({
      count: 1, items: [{ tradeId: 3, itemName: 'Pending Hat', sellerUserId: 7, sellerName: 'bob', settledAt: Date.now(), price: 4 }],
    })));
    await page.goto('/profile/reviews');
    await page.getByRole('tab', { name: /^Pending/ }).click({ timeout: 20_000 });
    const pending = page.getByRole('tab', { name: /^Pending/ });
    await expect(pending).toHaveAttribute('aria-selected', 'true');
    await expect(page.getByRole('tab', { name: /^Received/ })).toHaveAttribute('aria-selected', 'false');
  });

  test('the Sell Items source tabs say which inventory is shown', async ({ page }) => {
    await signedIn(page);
    await page.goto('/sell');
    const steam = page.locator('.sell-source-tabs .offer-tab', { hasText: 'Steam Inventory' });
    await expect(steam).toBeVisible({ timeout: 20_000 });
    const platform = page.locator('.sell-source-tabs .offer-tab', { hasText: 'Platform Inventory' });
    const states = [await steam.getAttribute('aria-pressed'), await platform.getAttribute('aria-pressed')];
    expect(states.sort()).toEqual(['false', 'true']);
  });
});
