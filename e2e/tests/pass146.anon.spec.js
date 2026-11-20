// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 146.

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


// Defect pass 146. Real-app tests take the server from JAR / E2E_BASE_URL
// and stub every write: a real-jar test must never create or change server data.

const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';

async function noWrites(page) {
  await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
  await page.route('**/api/**', (r) => (['POST', 'PUT', 'DELETE', 'PATCH'].includes(r.request().method()) ? r.fulfill(json({})) : r.fallback()));
}

test.describe('stall and item expanders name their action without arrow glyphs', () => {
  const STALL = { seller: { id: 9, displayName: 'bob' }, listings: [] };
  const SOLD = Array.from({ length: 14 }, (_, i) => ({ listingId: 70 + i, price: 3 + i, soldAt: Date.now() - (i + 1) * 3600000, item: { id: 300 + i, name: 'Sold thing ' + i } }));
  const REVIEWS = Array.from({ length: 13 }, (_, i) => ({ id: 40 + i, rating: 5 - (i % 3), comment: 'Review text ' + i, reviewerName: 'buyer' + i, createdAt: Date.now() - (i + 1) * 86400000, helpfulCount: 0 }));

  async function stall(page) {
    await signedIn(page);
    await page.route('**/api/listings/stall/9', (r) => r.fulfill(json(STALL)));
    await page.route('**/api/listings/stall/9/recent-sales**', (r) => r.fulfill(json(SOLD)));
    await page.route('**/api/reviews/user/9', (r) => r.fulfill(json(REVIEWS)));
    await page.goto('/stall/9');
  }

  test('stall Recent sales Show all / Collapse', async ({ page }) => {
    await stall(page);
    const btn = page.getByRole('button', { name: /Show all 14 sales/ });
    await btn.scrollIntoViewIfNeeded({ timeout: 20_000 });
    await expect(btn).toHaveAccessibleName('Show all 14 sales');
    await expect(btn).toContainText('↓');
    await btn.click();
    const collapse = page.getByRole('button', { name: /Collapse to 10 most recent/ });
    await expect(collapse).toHaveAccessibleName('Collapse to 10 most recent');
    await expect(collapse).toHaveAttribute('aria-expanded', 'true');
  });

  test('stall Reviews Show all / Collapse', async ({ page }) => {
    await stall(page);
    const btn = page.getByRole('button', { name: /Show all 13 reviews/ });
    await btn.scrollIntoViewIfNeeded({ timeout: 20_000 });
    await expect(btn).toHaveAccessibleName('Show all 13 reviews');
    await btn.click();
    await expect(page.getByRole('button', { name: /Collapse to 10 most relevant/ })).toHaveAccessibleName('Collapse to 10 most relevant');
  });

  test('item page Show more sales', async ({ page }) => {
    const item = { id: 1, name: 'Pass Hat 1', category: 'Hats', rarity: 'Standard', lowestPrice: 2 };
    await page.route('**/api/**', (r) => r.fulfill(json({})));
    await page.route('**/api/listings/item/1', (r) => r.fulfill(json([{ id: 1, item, price: 3, sellerName: 'dave', sellerUserId: 9, status: 'ACTIVE', listingType: 'BUY_NOW', listedAt: Date.now() - 3600_000 }])));
    await page.route('**/api/items/1/history', (r) => r.fulfill(json([])));
    await page.route('**/api/items/1/recent-sales**', (r) => r.fulfill(json(Array.from({ length: 10 }, (_, i) => ({ listingId: 100 + i, price: 2 + i / 10, listingType: 'BUY_NOW', soldAt: Date.now() - i * 3600_000 })))));
    await page.goto('/item/1');
    const more = page.getByRole('button', { name: /Show more sales/ });
    await more.scrollIntoViewIfNeeded({ timeout: 20_000 });
    await expect(more).toHaveAccessibleName('Show more sales');
    await expect(more).toContainText('↓');
  });
});
