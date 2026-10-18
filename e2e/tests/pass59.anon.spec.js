// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 59.

const ME = {
  id: 1,
  steamId64: '76561199000000001',
  displayName: 'Pass Tester',
  signedIn: true,
  sessionEpoch: 0,
  tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc',
};

const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });
const slow = (body, ms = 400) => (r) => new Promise((ok) => setTimeout(ok, ms)).then(() => r.fulfill(json(body)));
const doubleClick = (btn) => btn.evaluate((b) => { b.click(); b.click(); });

async function signedIn(page) {
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json(ME)));
}
async function anon(page) {
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json({ signedIn: false })));
}
// Counts requests of one method on a route, answering slowly so a second
// click lands while the first is still in flight.
async function countSlow(page, url, method, body = {}) {
  const hits = { n: 0 };
  await page.route(url, (r) => {
    if (r.request().method() === method) { hits.n += 1; return slow(body)(r); }
    return r.fallback();
  });
  return hits;
}
const stall = (id, extra = {}) => ({ seller: { id, displayName: 'eve', ...extra }, listings: [], count: 0, blockedByViewer: false });

test.describe('defect pass 59', () => {
  test('double-clicking + New Key mints one API key', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/api-keys', (r) => r.fulfill(json([])));
    const posts = await countSlow(page, '**/api/api-keys', 'POST', { id: 1, label: 'bot', token: 'sbx_live_x', scope: 'RW', createdAt: Date.now() });
    await page.goto('/profile/developers');
    const btn = page.getByRole('button', { name: '+ New Key' });
    await btn.waitFor({ timeout: 20_000 });
    await doubleClick(btn);
    await page.waitForTimeout(800);
    expect(posts.n).toBe(1);
  });

  test('double-clicking Follow on a stall sends one request', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings/stall/9*', (r) => r.fulfill(json(stall(9))));
    await page.route('**/api/follows/status/9', (r) => r.fulfill(json({ following: false, followerCount: 0 })));
    const posts = await countSlow(page, '**/api/follows/9', 'POST');
    await page.goto('/stall/9');
    const btn = page.getByTitle('Get notified when this seller lists something new');
    await btn.waitFor({ timeout: 20_000 });
    await doubleClick(btn);
    await page.waitForTimeout(800);
    expect(posts.n).toBe(1);
  });

  test('double-clicking Blocked on a stall header sends one unblock', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings/stall/9*', (r) => r.fulfill(json({ ...stall(9), blockedByViewer: true })));
    const deletes = await countSlow(page, '**/api/profile/blocks/9', 'DELETE');
    await page.goto('/stall/9');
    const btn = page.getByRole('button', { name: 'Blocked', exact: true });
    await btn.waitFor({ timeout: 20_000 });
    await doubleClick(btn);
    await page.waitForTimeout(800);
    expect(deletes.n).toBe(1);
  });

  test('double-clicking Helpful on a stall review sends one vote', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings/stall/80*', (r) => r.fulfill(json(stall(80, { displayName: 'Sel' }))));
    await page.route('**/api/reviews/user/80*', (r) => r.fulfill(json([
      { id: 5, rating: 5, comment: 'great', fromUserId: 2, fromDisplayName: 'B', helpfulCount: 0, viewerHasVoted: false, createdAt: Date.now() },
    ])));
    const posts = await countSlow(page, '**/api/reviews/5/helpful', 'POST', { helpfulCount: 1, viewerHasVoted: true });
    await page.goto('/stall/80');
    const btn = page.locator('.stall-review-helpful').first();
    await btn.waitFor({ timeout: 20_000 });
    await doubleClick(btn);
    await page.waitForTimeout(800);
    expect(posts.n).toBe(1);
  });

  test('double-clicking Sync Now runs one Steam sync', async ({ page }) => {
    await signedIn(page);
    const posts = await countSlow(page, '**/api/steam/sync', 'POST', { ok: true });
    await page.goto('/profile');
    const btn = page.getByRole('button', { name: 'Sync Now' });
    await btn.waitFor({ timeout: 20_000 });
    await doubleClick(btn);
    await page.waitForTimeout(800);
    expect(posts.n).toBe(1);
  });

  test('double-clicking Save on the stall bio sends one request', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings/stall/1*', (r) => r.fulfill(json(stall(1, { displayName: 'Me', stallBio: '' }))));
    const puts = await countSlow(page, '**/api/profile/stall-bio', 'PUT');
    await page.goto('/stall/1');
    await page.getByRole('button', { name: /Add a bio/ }).click({ timeout: 20_000 });
    await page.getByLabel('Stall bio').fill('hi');
    await doubleClick(page.getByRole('button', { name: 'Save', exact: true }));
    await page.waitForTimeout(800);
    expect(puts.n).toBe(1);
  });

  test('double-clicking Save on a loadout rename sends one request', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/loadouts/55', (r) => r.fulfill(json({ loadout: { id: 55, name: 'L', ownerUserId: 1, visibility: 'PUBLIC', totalValue: 0 }, slots: [] })));
    const puts = await countSlow(page, '**/api/loadouts/55', 'PUT');
    await page.goto('/loadout/55');
    await page.getByRole('button', { name: /Rename/ }).click({ timeout: 20_000 });
    await page.getByLabel('New loadout name').fill('L2');
    await doubleClick(page.getByRole('button', { name: 'Save', exact: true }));
    await page.waitForTimeout(800);
    expect(puts.n).toBe(1);
  });

  test("an old seller's trust card says the year they joined, not days", async ({ page }) => {
    await anon(page);
    const joinedAt = Date.now() - 800 * 86_400_000;
    await page.route('**/api/listings/stall/79*', (r) => r.fulfill(json(stall(79, { displayName: 'Old', joinedAt }))));
    await page.goto('/stall/79');
    const list = page.locator('.stall-trust-list');
    await expect(list).toContainText(`in ${new Date(joinedAt).getFullYear()}`, { timeout: 20_000 });
    await expect(list).not.toContainText(/\dd ago/);
  });

  test('a loadout card with one favorite is announced in the singular', async ({ page }) => {
    await anon(page);
    await page.route('**/api/loadouts/discover*', (r) => r.fulfill(json([
      { id: 1, name: 'Wiz', ownerName: 'Bob', favorites: 1, totalValue: 5, filledSlots: 2, visibility: 'PUBLIC' },
    ])));
    await page.goto('/loadout');
    await expect(page.locator('.loadout-card-name a').first()).toHaveAttribute('aria-label', 'Open loadout Wiz by Bob (1 favorite, $5.00)', { timeout: 20_000 });
  });

  test('the database table caption says "1 item" for a single row', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/database?*', (r) => r.fulfill(json({
      items: [{ id: 1, name: 'Hat', category: 'Hats', rarity: 'Standard', supply: 5, lowestPrice: 2 }], total: 1, indexed: 1,
    })));
    await page.goto('/db');
    await expect(page.locator('table caption').first()).toContainText('s&box item database — 1 item, columns', { timeout: 20_000 });
  });

  test('double-clicking Submit review on a stall posts one review', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings/stall/80*', (r) => r.fulfill(json(stall(80, { displayName: 'Sel' }))));
    await page.route('**/api/reviews/eligible/80', (r) => r.fulfill(json([{ tradeId: 3, itemName: 'Hat', price: 5, settledAt: Date.now(), reviewed: false }])));
    const posts = await countSlow(page, '**/api/reviews', 'POST', { id: 1 });
    await page.goto('/stall/80');
    await page.getByRole('button', { name: 'Write review' }).click({ timeout: 20_000 });
    await doubleClick(page.getByRole('button', { name: 'Submit review' }));
    await page.waitForTimeout(800);
    expect(posts.n).toBe(1);
  });

  test('double-clicking Sync Steam on the sell page runs one sync', async ({ page }) => {
    await signedIn(page);
    const posts = await countSlow(page, '**/api/steam/sync', 'POST', { ok: true });
    await page.goto('/sell');
    const btn = page.getByRole('button', { name: /Sync Steam/ }).first();
    await btn.waitFor({ timeout: 20_000 });
    await doubleClick(btn);
    await page.waitForTimeout(800);
    expect(posts.n).toBe(1);
  });
});
