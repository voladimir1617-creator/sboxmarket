// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 69.

const ME = { id: 3, displayName: 'carol', role: 'USER', signedIn: true, sessionEpoch: 0 };
const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });

async function signedIn(page) {
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json(ME)));
}

test.describe('defect pass 69', () => {
  test('a failed block-list load says so and can be retried, instead of hiding the list', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/follows*', (r) => r.fulfill(json([])));
    let fail = true;
    await page.route('**/api/profile/blocks*', (r) => r.fulfill(fail ? json({ error: 'boom' }, 403)
      : json({ count: 1, items: [{ blockedUserId: 7, displayName: 'mallory' }] })));
    await page.goto('/profile');
    await expect(page.getByText('Could not load the users you blocked.')).toBeVisible({ timeout: 20_000 });
    fail = false;
    await page.getByRole('alert').filter({ hasText: 'users you blocked' }).getByRole('button', { name: 'Retry' }).click();
    await expect(page.locator('.profile-row-label', { hasText: 'Blocked 1' })).toBeVisible();
  });

  test('Back to bare /profile shows the Personal tab, not the last tab opened', async ({ page }) => {
    await signedIn(page);
    await page.route(/\/api\/trades(\?.*)?$/, (r) => r.fulfill(json([])));
    await page.route('**/api/follows*', (r) => r.fulfill(json([])));
    await page.route('**/api/profile/blocks*', (r) => r.fulfill(json({ count: 0, items: [] })));
    await page.goto('/profile');
    const personal = page.getByRole('tab', { name: /^Personal/ });
    await expect(personal).toHaveAttribute('aria-selected', 'true', { timeout: 20_000 });
    await page.getByRole('tab', { name: /^Trades/ }).click();
    await expect(page).toHaveURL(/\/profile\/trades$/);
    await page.goBack();
    await expect(page).toHaveURL(/\/profile$/);
    await expect(personal).toHaveAttribute('aria-selected', 'true');
  });

  test('Back to bare /me/stall shows the Active tab, not Sold', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings/my-stall', (r) => r.fulfill(json([])));
    await page.route('**/api/listings/my-stall/sold*', (r) => r.fulfill(json([])));
    await page.goto('/me/stall');
    const active = page.getByRole('tab', { name: /^Active/ });
    await expect(active).toHaveAttribute('aria-selected', 'true', { timeout: 20_000 });
    await page.getByRole('tab', { name: /^Sold/ }).click();
    await expect(page).toHaveURL(/\/me\/stall\/sold$/);
    await page.goBack();
    await expect(page).toHaveURL(/\/me\/stall$/);
    await expect(active).toHaveAttribute('aria-selected', 'true');
  });

  test('a failed follow-status load does not wipe the stall\'s follower count', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings/stall/2*', (r) => r.fulfill(json({
      seller: { id: 2, displayName: 'bob', followerCount: 120, soldCount: 40 }, listings: [], count: 0, blockedByViewer: false,
    })));
    await page.route('**/api/listings/stall/2/recent-sales*', (r) => r.fulfill(json([])));
    await page.route('**/api/reviews/**', (r) => r.fulfill(json([])));
    let fail = true;
    await page.route('**/api/follows/status/2', (r) => r.fulfill(fail ? json({ error: 'boom' }, 403)
      : json({ sellerUserId: 2, following: true, followerCount: 120 })));
    await page.goto('/stall/2');
    const retry = page.getByRole('button', { name: 'Retry follow status' });
    await expect(retry).toBeVisible({ timeout: 20_000 });
    fail = false;
    await retry.click();
    await expect(page.locator('.stall-share-btn[title="Click to unfollow"]')).toBeVisible();
    await expect(page.getByText('120').first()).toBeVisible();
  });

  test('the item offer drawer\'s "View offer" lands on Outgoing offers when opened from the market', async ({ page }) => {
    await signedIn(page);
    const item = { id: 11, name: 'Red Hat', category: 'Hats', rarity: 'Limited', lowestPrice: 5 };
    const listing = { id: 1, price: '5.00', status: 'ACTIVE', listingType: 'BUY_NOW', sellerUserId: 2, sellerName: 'bob', item };
    await page.route('**/api/items/11', (r) => r.fulfill(json(item)));
    await page.route('**/api/items/11/history*', (r) => r.fulfill(json([])));
    await page.route('**/api/listings/item/11*', (r) => r.fulfill(json([listing])));
    await page.route('**/api/offers/outgoing*', (r) => r.fulfill(json([
      { id: 9, listingId: 1, amount: '4.00', status: 'PENDING', author: 'BUYER', itemName: 'Red Hat', createdAt: Date.now() },
    ])));
    await page.route('**/api/offers/incoming*', (r) => r.fulfill(json([])));
    await page.goto('/market');
    // Open the item through the app's own link handling, as a card click does.
    await page.waitForFunction(() => document.querySelector('#root, body').children.length > 0);
    await page.evaluate(() => { const a = document.createElement('a'); a.href = '/item/11'; a.id = 'p69'; a.textContent = 'x'; document.body.appendChild(a); });
    await page.locator('#p69').click();
    await expect(page).toHaveURL(/\/item\/11$/);
    await page.getByTestId('listing-offer-btn').first().click({ timeout: 20_000 });
    await page.getByRole('link', { name: /View offer/ }).click();
    await expect(page).toHaveURL(/\/offers\/outgoing$/);
    await page.waitForTimeout(500);
    await expect(page).toHaveURL(/\/offers\/outgoing$/);
  });

  test('Back from "Manage account" returns to Settings, not the market', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/follows*', (r) => r.fulfill(json([])));
    await page.route('**/api/profile/blocks*', (r) => r.fulfill(json({ count: 0, items: [] })));
    await page.goto('/settings');
    await page.getByRole('link', { name: /Manage account/ }).click({ timeout: 20_000 });
    await expect(page).toHaveURL(/\/profile\/personal$/);
    await page.goBack();
    await expect(page).toHaveURL(/\/settings$/);
  });
});
