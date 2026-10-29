// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 33: notification type chips count only what the list can show,
// and price alerts stay reachable on the watchlist page.

const ME = {
  id: 1,
  steamId64: '76561202255232729',
  displayName: 'Alert Tester',
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

test.describe('defect pass 33', () => {
  test('a muted notification type does not offer a chip that opens an empty list', async ({ page }) => {
    await signedIn(page);
    await page.addInitScript(() => { try { localStorage.setItem('sb_mute_kinds', '["MATCHES"]'); } catch (_) {} });
    const now = Date.now();
    const items = [
      ...[1, 2, 3, 4].map(i => ({ id: i, kind: 'PRICE_DROPPED', title: `Drop ${i}`, body: 'Cheaper now', read: false, createdAt: now - i * 1000 })),
      { id: 9, kind: 'TRADE_MESSAGE', title: 'New message', body: 'hi', read: false, createdAt: now },
    ];
    await page.route('**/api/notifications*', (r) => r.fulfill({
      status: 200, contentType: 'application/json', body: JSON.stringify({ items, unread: items.length }),
    }));

    await page.goto('/notifications');
    const matches = page.getByRole('button', { name: /^Matches · \d+$/ });
    await expect(matches).toBeVisible({ timeout: 20_000 });
    await expect(matches).toHaveText('Matches · 0');
    await expect(matches).toBeDisabled();
  });

  test('price alerts on items that are not starred still show, all of them', async ({ page }) => {
    await signedIn(page);
    const alerts = [1, 2, 3, 4, 5, 6, 7].map(i => ({
      id: 100 + i, itemId: 9000 + i, targetPrice: 5 + i, status: 'ACTIVE', createdAt: Date.now(),
    }));
    await page.route('**/api/watchlist/alerts', (r) => r.fulfill({
      status: 200, contentType: 'application/json', body: JSON.stringify(alerts),
    }));

    await page.goto('/watchlist');
    const cancels = page.getByRole('button', { name: 'Cancel this price alert' });
    await expect(cancels.first()).toBeVisible({ timeout: 20_000 });
    await expect(cancels).toHaveCount(5);
    await page.getByTestId('watchlist-alerts-more').click();
    await expect(cancels).toHaveCount(7);
  });

});
