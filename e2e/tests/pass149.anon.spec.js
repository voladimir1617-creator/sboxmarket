// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 149.

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


// Defect pass 149. Real-app tests take the server from JAR / E2E_BASE_URL and
// stub every write: a real-jar test must never create or change server data.

const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';

async function noWrites(page) {
  await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
  await page.route('**/api/**', (r) => (['POST', 'PUT', 'DELETE', 'PATCH'].includes(r.request().method()) ? r.fulfill(json({})) : r.fallback()));
}
async function devLogin(page, userId = 2) {
  await page.request.get(`${base}/api/auth/steam/dev-login?userId=${userId}&next=/`);
}

test.describe('maintenance page after a crash (real app)', () => {
  test('Contact support opens the Support page', async ({ page }) => {
    await noWrites(page);
    // A display name that is not text makes the header throw while rendering.
    let crash = true;
    await page.route('**/api/auth/steam/me', (r) => (crash ? r.fulfill(json({ ...ME, displayName: { bad: 1 } })) : r.fallback()));
    await page.goto(`${base}/market`);
    const h1 = page.getByRole('heading', { level: 1, name: 'Maintenance in progress' });
    await expect(h1).toBeVisible({ timeout: 20_000 });
    crash = false;
    await page.getByRole('link', { name: 'Contact support' }).click();
    await expect(page).toHaveURL(/\/support$/);
    // Pre-fix the SPA router swallowed the click: the URL changed, the
    // crash page stayed.
    await expect(h1).toHaveCount(0, { timeout: 20_000 });
  });
});

test.describe('home page sections (real app)', () => {
  test('the trust metrics and how-it-works regions have headings, and each tile title is one', async ({ page }) => {
    await noWrites(page);
    await page.goto(`${base}/`);
    await expect(page.getByRole('heading', { level: 1, name: /Buy & Sell s&box Skins/ })).toBeVisible({ timeout: 20_000 });
    const tiles = page.getByRole('region', { name: /^How SkinBox trades work$/ });
    await tiles.scrollIntoViewIfNeeded();
    await expect(tiles.getByRole('heading', { level: 2, name: /^How SkinBox trades work$/ })).toHaveCount(1);
    await expect(tiles.getByRole('heading', { level: 3 })).toHaveCount(3);
    await expect(tiles.getByRole('heading', { level: 3, name: 'Live Auctions' })).toBeInViewport();
    await expect(tiles.getByRole('heading', { level: 3, name: 'Bargain Engine' })).toBeVisible();
    const metrics = page.getByRole('region', { name: 'Marketplace trust metrics' });
    await expect(metrics.getByRole('heading', { level: 2, name: 'Marketplace trust metrics' })).toHaveCount(1);
  });
});

test.describe('Profile Reviews tabs (real app)', () => {
  test('Received and Given each control a named tab panel', async ({ page }) => {
    await noWrites(page);
    await devLogin(page);
    await page.goto(`${base}/profile/reviews`);
    const tabs = page.getByRole('tablist', { name: 'Reviews filter' });
    const received = tabs.getByRole('tab', { name: /^Received/ });
    await expect(received).toHaveAttribute('aria-selected', 'true', { timeout: 20_000 });
    await expect(received).toHaveAttribute('aria-controls', /.+/);
    const panel = page.getByRole('tabpanel', { name: /^Received/ });
    await expect(panel.getByRole('heading', { level: 2, name: 'No reviews yet' })).toBeVisible();
    await tabs.getByRole('tab', { name: /^Given/ }).click();
    const given = page.getByRole('tabpanel', { name: /^Given/ });
    await expect(given.getByRole('heading', { level: 2, name: /No reviews given yet/ })).toBeVisible({ timeout: 20_000 });
    const id = await tabs.getByRole('tab', { name: /^Given/ }).getAttribute('aria-controls');
    await expect(page.locator(`[id="${id}"]`)).toHaveAttribute('role', 'tabpanel');
  });

  test('an empty Pending view says so as a heading', async ({ page }) => {
    await noWrites(page);
    await devLogin(page);
    await page.goto(`${base}/profile/reviews?view=pending`);
    await expect(page.getByRole('tab', { name: /^Pending/ })).toHaveAttribute('aria-selected', 'true', { timeout: 20_000 });
    await expect(page.getByRole('heading', { level: 2, name: /You.re all caught up . no trades waiting on a review/ })).toBeVisible();
  });

  test('Given and Pending links and the pending CTA are named without the arrow glyph', async ({ page }) => {
    await noWrites(page);
    await devLogin(page);
    const settled = Date.now() - 86_400_000;
    await page.route('**/api/reviews/pending', (r) => r.fulfill(json({ count: 1, items: [{ tradeId: 71, itemName: 'Chef Hat', sellerUserId: 5, sellerName: 'bob', price: 3, settledAt: settled }] })));
    let mine = [];
    await page.route('**/api/reviews/mine', (r) => r.fulfill(json(mine)));
    await page.goto(`${base}/profile/reviews?view=given`);
    await expect(page.getByRole('heading', { level: 2, name: /No reviews given yet/ })).toBeVisible({ timeout: 20_000 });
    await expect(page.getByRole('button', { name: 'Check 1 pending review', exact: true })).toBeVisible();
    await page.getByRole('tab', { name: /^Pending/ }).click();
    await expect(page.getByRole('link', { name: 'Leave review', exact: true })).toHaveAttribute('href', '/stall/5?leaveReview=71');
    mine = [{ id: 9, tradeId: 71, rating: 4, comment: 'ok', toUserId: 5, toUserName: 'bob', itemName: 'Chef Hat', createdAt: settled }];
    await page.goto(`${base}/profile/reviews?view=given`);
    await expect(page.getByRole('link', { name: 'about bob', exact: true })).toHaveAttribute('href', '/stall/5', { timeout: 20_000 });
  });
});

