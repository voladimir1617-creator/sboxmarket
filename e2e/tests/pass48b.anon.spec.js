// @ts-check
const { test, expect } = require('@playwright/test');

// The away-seller banner must not promise a review form the stall does not have:
// only the buyer of a VERIFIED trade can review, from Profile → Trades.

const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });

test('the away-seller banner says buyers review past trades, not "leave a review" here', async ({ page }) => {
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json({ signedIn: false })));
  await page.route('**/api/listings/stall/48*', (r) => r.fulfill(json({
    seller: { id: 48, displayName: 'Away Seller' }, listings: [], away: true, awayCount: 2, blockedByViewer: false,
  })));
  await page.goto('/stall/48');
  const banner = page.locator('.stall-away-sub');
  await expect(banner).toBeVisible({ timeout: 20_000 });
  await expect(banner).toContainText('buyers can still review past trades');
  await expect(banner).not.toContainText('leave a review');
});
