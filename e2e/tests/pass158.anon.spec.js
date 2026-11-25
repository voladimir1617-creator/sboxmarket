// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 158.

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


// Defect pass 158. Real-jar tests take the server from JAR / E2E_BASE_URL and
// serve only the page assets from the static copy under test (BASE_URL, when
// set). Every non-GET API call is stubbed: no test writes server data.

const JAR = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';
const STATIC = process.env.BASE_URL;
void slow; void asAdmin;

async function prep(page) {
  await page.addInitScript(() => {
    try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {}
    window.confirm = () => true;
  });
  await page.route(/^https?:\/\/(?!localhost|127\.0\.0\.1)/, (r) => r.abort());
  await page.route(`${JAR}/api/**`, (r) => (r.request().method() === 'GET' ? r.fallback() : r.fulfill(json({}))));
}
async function useStaticAssets(page) {
  if (!STATIC) return;
  await page.route(`${JAR}/**`, async (r) => {
    const u = new URL(r.request().url());
    if (u.pathname.startsWith('/api/')) return r.fallback();
    const res = await page.request.fetch(STATIC + u.pathname + u.search);
    await r.fulfill({ response: res });
  });
}
async function devLogin(page, userId) {
  await page.request.get(`${JAR}/api/auth/steam/dev-login?userId=${userId}&next=/`);
}

test.describe('Database keyboard order (real jar)', () => {
  test('Tab from the price boxes goes to the first item row, not the rarity legend at the foot of the page', async ({ page }) => {
    await prep(page); await useStaticAssets(page);
    await page.goto(`${JAR}/db`);
    const first = page.getByRole('link', { name: /^Open .+ detail · / }).first();
    await expect(first).toBeVisible({ timeout: 20_000 });
    await page.getByRole('textbox', { name: 'Maximum floor price' }).focus();
    await page.keyboard.press('Tab');
    await expect(first).toBeFocused({ timeout: 3_000 });
  });

  test('on a phone too, the legend comes after the rows in Tab order', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await prep(page); await useStaticAssets(page);
    await page.goto(`${JAR}/db`);
    const first = page.getByRole('link', { name: /^Open .+ detail · / }).first();
    await expect(first).toBeVisible({ timeout: 20_000 });
    await page.getByRole('textbox', { name: 'Maximum floor price' }).focus();
    await page.keyboard.press('Tab');
    await expect(first).toBeFocused({ timeout: 3_000 });
  });
});

test.describe('Loadout Lab lists', () => {
  test('Discover cards form a list, one item per loadout (real jar)', async ({ page }) => {
    await prep(page); await useStaticAssets(page);
    await page.goto(`${JAR}/loadout`);
    const panel = page.getByRole('tabpanel', { name: 'Discover' });
    const links = panel.getByRole('link', { name: /^Open loadout / });
    await expect(links.first()).toBeVisible({ timeout: 20_000 });
    const n = await links.count();
    expect(n).toBeGreaterThan(0);
    await expect(panel.getByRole('list').getByRole('listitem')).toHaveCount(n, { timeout: 3_000 });
    await expect(panel.getByRole('listitem').first().getByRole('link', { name: /^Open loadout / })).toHaveCount(1);
  });

  test('My Loadouts cards form a list too (stubbed)', async ({ page }) => {
    await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
    await signedIn(page);
    await page.route('**/api/loadouts/mine', (r) => r.fulfill(json([
      { id: 11, name: 'Desert set', ownerName: ME.displayName, favorites: 0, totalValue: 4, filledSlots: 2, visibility: 'PUBLIC' },
      { id: 12, name: 'Night set', ownerName: ME.displayName, favorites: 1, totalValue: 6, filledSlots: 3, visibility: 'PRIVATE' },
    ])));
    await page.goto('/loadout?view=mine');
    const panel = page.getByRole('tabpanel', { name: 'My Loadouts' });
    await expect(panel.getByRole('link', { name: /^Open loadout Desert set/ })).toBeVisible({ timeout: 20_000 });
    await expect(panel.getByRole('list').getByRole('listitem')).toHaveCount(2, { timeout: 3_000 });
  });
});

test.describe('Personal Info Following and Blocked rows (real jar)', () => {
  test('the sellers you follow are a named list, one item per seller', async ({ page }) => {
    await prep(page); await useStaticAssets(page);
    // Read stub only: the seed has no follows, and following from here would write.
    await page.route(/\/api\/follows\?limit=/, (r) => r.fulfill(json([
      { id: 901, sellerUserId: 4, notificationsMuted: false },
      { id: 902, sellerUserId: 5, notificationsMuted: true },
    ])));
    await devLogin(page, 2);
    await page.goto(`${JAR}/profile`);
    const panel = page.getByRole('tabpanel', { name: 'Personal Info' });
    await expect(panel.getByRole('button', { name: /^Unfollow (?!all)/ }).first()).toBeVisible({ timeout: 20_000 });
    const list = panel.getByRole('list', { name: 'Sellers you follow' });
    await expect(list.getByRole('listitem')).toHaveCount(2, { timeout: 3_000 });
    await expect(list.getByRole('listitem').first().locator('a[href="/stall/4"]')).toHaveCount(1);
  });

  test('blocked users are a named list too', async ({ page }) => {
    await prep(page); await useStaticAssets(page);
    await page.route(/\/api\/profile\/blocks/, (r) => r.fulfill(json({ items: [
      { blockedUserId: 4, displayName: 'GhostlyDeals' },
      { blockedUserId: 5, displayName: 'BoneTender' },
    ], total: 2 })));
    await devLogin(page, 2);
    await page.goto(`${JAR}/profile`);
    const panel = page.getByRole('tabpanel', { name: 'Personal Info' });
    await expect(panel.getByRole('button', { name: 'Unblock GhostlyDeals' })).toBeVisible({ timeout: 20_000 });
    const list = panel.getByRole('list', { name: 'Blocked users' });
    await expect(list.getByRole('listitem')).toHaveCount(2, { timeout: 3_000 });
  });
});

test.describe('stall Follow button count (real jar)', () => {
  test('the number on Follow says it counts followers', async ({ page }) => {
    await prep(page); await useStaticAssets(page);
    await page.route(/\/api\/follows\/status\/4$/, (r) => r.fulfill(json({ sellerUserId: 4, following: false, followerCount: 3 })));
    await devLogin(page, 2);
    await page.goto(`${JAR}/stall/4`);
    const follow = page.getByRole('button', { name: /^\+\s*Follow/ });
    await expect(follow).toBeVisible({ timeout: 20_000 });
    await expect(follow).toHaveAccessibleName(/^\+\s*Follow · 3 followers$/, { timeout: 3_000 });
  });

  test('one follower reads in the singular (stubbed)', async ({ page }) => {
    await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
    await signedIn(page);
    await page.route('**/api/listings/stall/7', (r) => r.fulfill(json({ seller: { id: 7, displayName: 'dave', followerCount: 1 }, listings: [] })));
    await page.route('**/api/follows/status/7', (r) => r.fulfill(json({ sellerUserId: 7, following: false, followerCount: 1 })));
    await page.goto('/stall/7');
    const follow = page.getByRole('button', { name: /^\+\s*Follow/ });
    await expect(follow).toBeVisible({ timeout: 20_000 });
    await expect(follow).toHaveAccessibleName(/^\+\s*Follow · 1 follower$/, { timeout: 3_000 });
  });
});
