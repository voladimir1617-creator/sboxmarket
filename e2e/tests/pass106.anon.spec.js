// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 106.

const ME = { id: 3, displayName: 'carol', role: 'USER', signedIn: true, sessionEpoch: 0, tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc' };
const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });
const slow = (body, ms = 800) => (r) => new Promise((ok) => setTimeout(ok, ms)).then(() => r.fulfill(json(body)));

async function signedIn(page) {
  await page.addInitScript(() => { window.confirm = () => true; });
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json(ME)));
}
async function asAdmin(page) {
  await signedIn(page);
  await page.route('**/api/admin/check', (r) => r.fulfill(json({ admin: true })));
}
const SELLER = { id: 9, displayName: 'dave', completedTrades: 1234, failedTrades: 20, completionRate: 98, followerCount: 1234 };
async function stall(page) {
  await page.route('**/api/listings/stall/9', (r) => r.fulfill(json({ seller: SELLER, listings: [], count: 0 })));
  await page.route('**/api/follows/status/9', (r) => r.fulfill(json({ following: false, followerCount: 1234 })));
  await page.route('**/api/reviews/user/9**', (r) => r.fulfill(json([])));
  await page.route('**/api/listings/stall/9/recent-sales**', (r) => r.fulfill(json([])));
}

test.describe('page titles', () => {
  test('Profile Listings tab names the browser tab like its siblings', async ({ page }) => {
    await signedIn(page);
    await page.goto('/profile/listings');
    await expect(page).toHaveTitle(/^Listings · Profile · /);
  });
});

test.describe('number grouping', () => {
  test('stall Follow button groups its follower count', async ({ page }) => {
    await signedIn(page);
    await stall(page);
    await page.goto('/stall/9');
    const follow = page.getByRole('button', { name: /^\+\s*Follow/ });
    await expect(follow).toContainText(/1,234/);
  });

  test('stall Completion stat groups its trade counts', async ({ page }) => {
    await signedIn(page);
    await stall(page);
    await page.goto('/stall/9');
    const stat = page.locator('[data-stat="completion"]');
    await stat.scrollIntoViewIfNeeded();
    await expect(stat).toContainText(/1,234 of 1,254/);
  });

  test('Loadout Lab cards group the favorites count', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/loadouts/discover**', (r) => r.fulfill(json([{ id: 5, name: 'Popular set', ownerName: 'dave', favorites: 1234, totalValue: 10, filledSlots: 3 }])));
    await page.goto('/loadout');
    const card = page.locator('.loadout-card').first();
    await expect(card.locator('.loadout-card-meta')).toContainText(/❤ 1,234/);
  });

  test('My Stall Analytics groups the 30d Sold column like Views', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings/my-stall/analytics', (r) => r.fulfill(json([{ listingId: 1, itemId: 1, itemName: 'Top Hat', itemRarity: 'Limited', category: 'Hats', price: 2, floorDelta: 0, viewCount: 4321, itemSales30d: 1234 }])));
    await page.goto('/me/stall/analytics');
    const row = page.getByText('Top Hat', { exact: true }).locator('xpath=ancestor::div[contains(@style,"subgrid")][1]');
    await row.scrollIntoViewIfNeeded();
    await expect(row).toContainText('4,321');
    await expect(row).toContainText('1,234');
  });
});

test.describe('unread counts', () => {
  test('Notifications page heading and Unread chip group a large unread count', async ({ page }) => {
    await signedIn(page);
    const items = [{ id: 1, kind: 'SUPPORT_REPLY', title: 'Support replied', body: 'See your ticket', read: false, createdAt: Date.now() }];
    await page.route('**/api/notifications**', (r) => r.fulfill(json({ items, unread: 1234 })));
    await page.goto('/notifications');
    await expect(page.getByRole('heading', { name: /Notifications · 1,234 unread/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /^Unread 1,234$/ })).toBeVisible();
  });
});
