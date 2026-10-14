// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 36.

const ME = {
  id: 1,
  steamId64: '76561199000000001',
  displayName: 'Pass Tester',
  signedIn: true,
  sessionEpoch: 0,
};

async function signedIn(page) {
  await page.route('**/api/**', (r) => r.fulfill({
    status: 200, contentType: 'application/json', body: '{}',
  }));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify(ME),
  }));
}

test.describe('defect pass 36', () => {
  test('a watchlist whose item lookup fails says so with a Retry, not "Nothing on your watchlist"', async ({ page }) => {
    await page.addInitScript(() => localStorage.setItem('sb_watchlist', JSON.stringify([999001])));
    let calls = 0;
    await page.route('**/api/items/batch**', (r) => {
      calls += 1;
      if (calls === 1) return r.fulfill({ status: 500, contentType: 'application/json', body: '{}' });
      return r.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify([{ id: 999001, name: 'Lookup Hat', lowestPrice: 0, rarity: 'Standard', category: 'Hats' }]) });
    });
    await page.goto('/watchlist');
    await expect(page.getByTestId('watchlist-lookup-failed')).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText('Nothing on your watchlist')).toHaveCount(0);
    await page.getByTestId('watchlist-lookup-failed').getByRole('button', { name: 'Retry' }).click();
    await expect(page.getByText('Lookup Hat').first()).toBeVisible();
    await expect(page.getByTestId('watchlist-lookup-failed')).toHaveCount(0);
  });

  test('a watchlist still loading shows Loading, not the empty state', async ({ page }) => {
    await page.addInitScript(() => localStorage.setItem('sb_watchlist', JSON.stringify([999002])));
    await page.route('**/api/items/batch**', () => { /* never answers */ });
    await page.goto('/watchlist');
    await expect(page.getByTestId('watchlist-loading')).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText('Nothing on your watchlist')).toHaveCount(0);
  });

  test('a notification\'s Delete button is not nested inside its open button', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/notifications**', (r) => r.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ unread: 0, items: [{ id: 3, kind: 'SYSTEM', title: 'Hello there', body: 'Body text', read: true, createdAt: Date.now() - 60_000 }] }),
    }));
    await page.goto('/notifications');
    const openBtn = page.getByRole('button', { name: /Open notification: Hello there/ });
    await expect(openBtn).toBeVisible({ timeout: 20_000 });
    await expect(openBtn.getByRole('button')).toHaveCount(0);
    await expect(openBtn.locator('button')).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Delete this notification' })).toBeVisible();
  });
});
