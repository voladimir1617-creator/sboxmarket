// @ts-check
const { test, expect } = require('@playwright/test');

// Trust-and-safety pass (2026-10-14): lines on our own pages that claimed
// more than the build can back, and safety advice a phone could not see.

test('home metrics are headed by what they count, not by traders we do not have', async ({ page }) => {
  await page.goto('/');
  const eyebrow = page.locator('.csfloat-home-metrics-eyebrow');
  await expect(eyebrow).toHaveText('SkinBox in numbers', { timeout: 15_000 });
  await expect(page.locator('body')).not.toContainText('Trusted by s&box traders');
});

test('the footer badge states no TLS version', async ({ page }) => {
  await page.goto('/');
  const badge = page.locator('footer.site-footer .trust-badge');
  await expect(badge).toBeVisible({ timeout: 15_000 });
  await expect(badge).not.toContainText('TLS');
});

test('the Help Center answers "How do I avoid scams?" and claims no signed writes', async ({ page }) => {
  await page.goto('/help');
  await expect(page.getByTestId('help-faq-group').first()).toBeVisible({ timeout: 15_000 });
  await expect(page.locator('body')).not.toContainText('Every write is signed');
  await page.getByRole('button', { name: /How do I avoid scams/ }).click();
  const answer = page.locator('.help-faq-a');
  await expect(answer).toContainText('never asks for your Steam password');
  await expect(answer).toContainText('Profile → Developers are a different thing');
});

// The buyer's trade row showed the offer code with its meaning only in a
// tooltip, which a phone never shows. The sentence is now on the row.
test('a delivery code on a trade row comes with its meaning as visible text', async ({ page }) => {
  const me = await (await page.request.get('/api/auth/steam/me')).json();
  const now = Date.now();
  await page.route(/\/api\/trades(\?.*)?$/, (route) => route.fulfill({
    json: [{
      id: 990001, listingId: 990001, itemId: 12, itemName: 'Vintage Design Crop Top',
      buyerUserId: me.id, sellerUserId: me.id + 1000, price: 15.0, feeAmount: 0.3,
      state: 'PENDING_BUYER_CONFIRM', createdAt: now - 3600_000, updatedAt: now - 600_000,
      sentAt: now - 600_000, expiresAt: now + 7 * 86400_000, unreadCount: 0,
      deliveryCode: 'K7Q2', counterpartyName: 'GhostlyDeals'
    }]
  }));
  await page.goto('/profile/trades');
  await expect(page.locator('.bot-code-value')).toHaveText('K7Q2', { timeout: 15_000 });
  await expect(page.getByTestId('bot-code-note')).toBeVisible();
  await expect(page.getByTestId('bot-code-note'))
    .toHaveText("Our bot's trade offer message ends with this code. Decline any offer for this item without it.");
});

// No payment processor is connected in test mode, so the footer names none.
test('the footer carries no "Powered by stripe" badge', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('footer.site-footer .trust-badge')).toBeVisible({ timeout: 15_000 });
  await expect(page.locator('footer.site-footer .stripe-badge')).toHaveCount(0);
});
