// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 107.

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
// The real SkinBox app (dev login on, H2 demo data), the same server the suite runs against.
// Every /api call goes to it, so these checks exercise the real API.
const JAR = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';
async function realApi(page, userId) {
  await page.request.get(`${JAR}/api/auth/steam/dev-login?userId=${userId}&next=/`, { maxRedirects: 0 });
  await page.route('**/api/**', (r) => {
    const u = new URL(r.request().url());
    return r.continue({ url: JAR + u.pathname + u.search });
  });
}

const SELLER = { id: 9, displayName: 'dave', completedTrades: 3, failedTrades: 0, completionRate: 100, followerCount: 0 };
async function stallWithPendingReview(page) {
  await page.route('**/api/listings/stall/9', (r) => r.fulfill(json({ seller: SELLER, listings: [], count: 0 })));
  await page.route('**/api/reviews/eligible/9', (r) => r.fulfill(json([{ tradeId: 5, itemName: 'Top Hat', price: 2, settledAt: Date.now(), reviewed: false }])));
  await page.route('**/api/reviews/user/9**', (r) => r.fulfill(json([])));
  await page.route('**/api/listings/stall/9/recent-sales**', (r) => r.fulfill(json([])));
}

test.describe('stall review deep link', () => {
  test('review-reminder bell opens the review form while already on that stall', async ({ page }) => {
    await signedIn(page);
    await stallWithPendingReview(page);
    const items = [{ id: 1, kind: 'REVIEW_REMINDER', title: 'How was your trade?', body: 'Rate dave for Top Hat', read: false, createdAt: Date.now(), refId: 5, path: '/stall/9?leaveReview=5' }];
    await page.route('**/api/notifications**', (r) => r.fulfill(json({ items, unread: 1 })));
    await page.route('**/api/notifications/unread-count', (r) => r.fulfill(json({ unread: 1 })));
    await page.goto('/stall/9');
    await expect(page.getByRole('button', { name: 'Write review' })).toBeVisible();
    await page.getByRole('button', { name: /^Notifications/ }).click();
    await page.getByRole('menuitem', { name: /How was your trade/ }).click();
    await expect(page.getByRole('textbox', { name: 'Review comment (optional)' })).toBeVisible();
    await expect(page.getByText(/Leave a review · Top Hat/)).toBeVisible();
  });

  test('a direct ?leaveReview= link still opens the form and drops the param', async ({ page }) => {
    await signedIn(page);
    await stallWithPendingReview(page);
    await page.goto('/stall/9?leaveReview=5');
    await expect(page.getByRole('textbox', { name: 'Review comment (optional)' })).toBeVisible();
    await expect(page).toHaveURL(/\/stall\/9$/);
  });
});

test.describe('profile reviews pending view', () => {
  test('a failed pending check on ?view=pending shows an alert with Retry, not the crash page (real app)', async ({ page }) => {
    await realApi(page, 1);
    let fail = true;
    await page.route('**/api/reviews/pending**', (r) => fail
      ? r.fulfill(json({ code: 'INTERNAL', message: 'boom' }, 500))
      : r.fulfill(json({ count: 0, items: [] })));
    await page.goto('/profile/reviews?view=pending');
    await expect(page.getByText(/Maintenance in progress/)).toHaveCount(0);
    const alert = page.getByRole('alert').filter({ hasText: /Couldn.t check for trades waiting on a review/ });
    await expect(alert).toBeVisible();
    await expect(page.getByRole('tab', { name: /Received/ })).toBeVisible();
    fail = false;
    await alert.getByRole('button', { name: 'Retry' }).click();
    await expect(page.getByText(/You.re all caught up/)).toBeVisible();
  });
});

test.describe('profile header standing', () => {
  test('header does not claim Good standing when the profile failed to load (real app)', async ({ page }) => {
    await realApi(page, 1);
    await page.route('**/api/profile/me', (r) => r.fulfill(json({ code: 'INTERNAL', message: 'boom' }, 500)));
    await page.goto('/profile/trades');
    await expect(page.locator('.profile-name')).toContainText('BoneTender');
    await expect(page.locator('.profile-name')).not.toContainText(/Good standing/);
  });

  test('an At Risk seller is not shown Good standing while the profile loads', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/profile/me', slow({ user: { ...ME }, accountStanding: { state: 'at_risk', label: 'At Risk', note: 'Low ratings' } }, 3000));
    await page.goto('/profile/trades');
    const name = page.locator('.profile-name');
    await expect(name).toContainText('carol');
    expect(await name.getByText('Good standing', { exact: true }).count()).toBe(0);
    await expect(name).toContainText('At Risk');
  });
});
