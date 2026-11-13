// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 119.

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


// Defect pass 119: the Following list caption, the Sell form's stale
// validation message, Notifications glyphs and day headings, the Affiliate
// section headings, the Changelog nav roles and Space on account-menu links.

const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';

async function consent(page) {
  await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
}
async function devLogin(page, id) {
  await page.request.get(`${base}/api/auth/steam/dev-login?userId=${id}&next=/`);
}
async function csrfHeaders(page) {
  const c = (await page.context().cookies(base)).find((x) => x.name === 'sbox_csrf');
  return { 'Content-Type': 'application/json', 'X-CSRF-Token': c ? c.value : '' };
}

test.describe('pass119 · Following list (real jar)', () => {
  test('Profile: a followed seller.s link is named after the seller, with no "recent activity" caption', async ({ page }) => {
    await consent(page);
    await devLogin(page, 3);
    const headers = await csrfHeaders(page);
    // Already following is fine; the list below shows the follow either way.
    await page.request.post(`${base}/api/follows/5`, { headers });
    const names = await (await page.request.get(`${base}/api/sellers/names?ids=5`)).json();
    const who = names['5'] || names[5];
    expect(who, 'seller 5 has a display name').toBeTruthy();
    await page.goto(`${base}/profile`);
    await expect(page.getByRole('button', { name: `Unfollow ${who}` })).toBeVisible({ timeout: 20_000 });
    const link = page.locator('.profile-row a[href="/stall/5"]').first();
    await link.scrollIntoViewIfNeeded();
    await expect(link).toHaveAccessibleName(who);
    await expect(page.getByRole('link', { name: /recent activity/ })).toHaveCount(0);
    const caption = await link.evaluate((a) => getComputedStyle(a, '::after').content);
    expect(caption).not.toMatch(/recent activity/);
  });
});

test.describe('pass119 · Sell form validation (real jar)', () => {
  const inv = {
    items: ['Red Beanie', 'Wizard Hat', 'Gold Chain'].map((n, i) => ({
      assetId: String(9100 + i), name: n, rarity: 'Standard', category: 'Hats', tradable: true,
      quantity: 1, listableQuantity: 1, listedCount: 0, catalogueId: null, suggestedPrice: 0,
    })),
    count: 3, lastSyncedAt: Date.now(),
  };

  test('Sell: a Buy Now price error goes away when the seller switches back to Buy Now (the field is gone)', async ({ page }) => {
    await consent(page);
    await page.route('**/api/steam/inventory', (r) => r.fulfill(json(inv)));
    await devLogin(page, 3);
    await page.goto(`${base}/sell`);
    await page.getByRole('button', { name: /^List Wizard Hat for sale/ }).click({ timeout: 20_000 });
    await page.getByRole('group', { name: 'Listing type' }).getByRole('button', { name: 'Auction' }).click();
    await page.getByRole('textbox', { name: 'Starting bid' }).fill('5');
    await page.getByRole('textbox', { name: 'Buy Now price' }).fill('3');
    await page.getByRole('button', { name: 'List for Sale' }).click();
    const bnErr = page.getByRole('alert').filter({ hasText: /Buy-Now price must be greater/ });
    await expect(bnErr).toBeVisible();
    await page.getByRole('group', { name: 'Listing type' }).getByRole('button', { name: 'Buy Now' }).click();
    await expect(page.getByRole('textbox', { name: 'Buy Now price' })).toHaveCount(0);
    await expect(bnErr).toHaveCount(0);
  });

  test('Sell: an Auto-accept error goes away when the seller switches to Auction (the field is gone)', async ({ page }) => {
    await consent(page);
    await page.route('**/api/steam/inventory', (r) => r.fulfill(json(inv)));
    await devLogin(page, 3);
    await page.goto(`${base}/sell`);
    await page.getByRole('button', { name: /^List Red Beanie for sale/ }).click({ timeout: 20_000 });
    await page.getByRole('textbox', { name: 'Asking price' }).fill('5');
    await page.getByRole('spinbutton', { name: 'Auto-accept discount percent' }).fill('7.5');
    await page.getByRole('button', { name: 'List for Sale' }).click();
    const pctErr = page.getByRole('alert').filter({ hasText: /Auto-accept discount must be/ });
    await expect(pctErr).toBeVisible();
    await page.getByRole('group', { name: 'Listing type' }).getByRole('button', { name: 'Auction' }).click();
    await expect(page.getByRole('spinbutton', { name: 'Auto-accept discount percent' })).toHaveCount(0);
    await expect(pctErr).toHaveCount(0);
  });
});

test.describe('pass119 · Info pages (real jar)', () => {
  test('Changelog: the top nav items are links, not menu items of a menubar with no arrow keys', async ({ page }) => {
    await page.goto(`${base}/changelog.html`);
    const nav = page.getByRole('navigation', { name: 'Primary' });
    await expect(nav.getByRole('link', { name: 'Market' })).toBeVisible();
    await expect(nav.getByRole('link', { name: 'Changelog' })).toHaveAttribute('aria-current', 'page');
    await expect(page.getByRole('menubar')).toHaveCount(0);
    await expect(page.getByRole('menuitem')).toHaveCount(0);
  });

  test('Affiliate: About Us, Requirements and Ready to apply? are headings under the page heading', async ({ page }) => {
    await consent(page);
    await page.goto(`${base}/affiliate`);
    await expect(page.getByRole('heading', { level: 1, name: 'Affiliate Program' })).toBeVisible({ timeout: 20_000 });
    await expect(page.getByRole('heading', { level: 2, name: 'About Us' })).toBeVisible();
    await expect(page.getByRole('heading', { level: 2, name: 'Requirements' })).toBeVisible();
    const apply = page.getByRole('heading', { level: 2, name: /Ready to apply/ });
    await apply.scrollIntoViewIfNeeded();
    await expect(apply).toBeVisible();
  });
});

test.describe('pass119 · Account menu (real jar)', () => {
  test('Account menu: Space on a link item (Settings) opens it, as Enter does', async ({ page }) => {
    await consent(page);
    await devLogin(page, 3);
    await page.goto(`${base}/market`);
    const chip = page.getByRole('button', { name: /^User menu/ });
    await chip.focus({ timeout: 20_000 });
    await page.keyboard.press('Enter');
    await expect(page.getByRole('menu')).toBeVisible();
    await page.keyboard.press('End');
    await expect(page.getByRole('menuitem', { name: 'Logout' })).toBeFocused();
    await page.keyboard.press('ArrowUp');
    await expect(page.getByRole('menuitem', { name: 'Settings' })).toBeFocused();
    await page.keyboard.press(' ');
    await expect(page).toHaveURL(/\/settings$/);
    await expect(page.getByRole('menu')).toHaveCount(0);
  });
});

test.describe('pass119 · Notifications page', () => {
  const now = Date.now();
  const day = 24 * 3600 * 1000;
  const items = [
    { id: 1, kind: 'AUCTION_OUTBID', title: 'Outbid on Wizard Hat', body: 'Someone bid $3.00', read: false, createdAt: now - 60_000 },
    { id: 2, kind: 'OFFER_REJECTED', title: 'Offer declined', body: 'bob declined your offer', read: true, createdAt: now - 120_000 },
    { id: 3, kind: 'SELLER_FOLLOWED', title: 'New follower', body: 'ann follows your stall', read: true, createdAt: now - day - 60_000 },
  ];

  test('Notifications: row glyphs are not read out, and each day label is a heading', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/notifications**', (r) => r.fulfill(json({ items, unread: 1 })));
    await page.goto('/notifications');
    await expect(page.getByRole('button', { name: /Open notification: Outbid on Wizard Hat/ })).toBeVisible();
    await expect(page.getByRole('heading', { level: 2, name: 'Today' })).toBeVisible();
    await expect(page.getByRole('heading', { level: 2, name: 'Yesterday' })).toBeVisible();
    const snap = await page.getByRole('main').first().ariaSnapshot();
    expect(snap).not.toContain('- text: ↑');
    expect(snap).not.toContain('- text: ✕');
    expect(snap).not.toContain('- text: +');
  });

  test('Notifications: the "all caught up" check mark is not read out', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/notifications**', (r) => r.fulfill(json({ items: items.map((n) => ({ ...n, read: true })), unread: 0 })));
    await page.goto('/notifications');
    await page.getByRole('button', { name: /^Unread \d/ }).click();
    await expect(page.getByText(/You.re all caught up/)).toBeVisible();
    const snap = await page.getByRole('main').first().ariaSnapshot();
    expect(snap).not.toContain('- text: ✓');
  });
});
