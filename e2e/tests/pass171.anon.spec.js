// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 171.

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


// Defect pass 171: long unbroken names and replies spill out of stall and
// Profile review rows, the Loadout Lab detail and the Notifications page list
// on a phone; Escape pressed in another menu
// also closes the stall Contact drawer and the market Save search drawer.

const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';
const LONG = 'Q'.repeat(70);

async function consent(page) {
  await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
}
// Real-jar tests read only: every write is refused before it reaches the server.
async function noWrites(page) {
  await page.route('**/api/**', (r) => (r.request().method() === 'GET' ? r.fallback() : r.fulfill(json({ error: 'blocked in test' }, 400))));
}
async function devLogin(page, userId) {
  await page.request.get(`${base}/api/auth/steam/dev-login?userId=${userId}&next=/`);
}
// The element's text fits inside its own box and the viewport.
async function fits(loc) {
  const box = await loc.evaluate((el) => ({ sw: el.scrollWidth, cw: el.clientWidth, right: el.getBoundingClientRect().right, vw: window.innerWidth }));
  expect(box.sw).toBeLessThanOrEqual(box.cw + 1);
  expect(box.right).toBeLessThanOrEqual(box.vw);
}

const REVIEW = { id: 41, rating: 5, comment: 'Smooth trade', fromDisplayName: 'Buyer' + LONG, itemName: 'Item' + LONG, createdAt: Date.now() - 3600e3, sellerReply: 'Thanks' + LONG };

test.describe('pass171 · stall review long names (real jar)', () => {
  test('stall review rows wrap a long reviewer name, item name and seller reply on a phone', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await consent(page);
    await noWrites(page);
    await page.route(/\/api\/reviews\/user\/2(\?.*)?$/, (r) => (r.request().method() === 'GET' ? r.fulfill(json([REVIEW])) : r.fallback()));
    await page.goto(`${base}/stall/2`);
    const row = page.locator('.stall-review').first();
    await row.scrollIntoViewIfNeeded({ timeout: 20_000 });
    for (const sel of ['.stall-review-from', '.stall-review-item', '.stall-review-reply-body']) await fits(row.locator(sel));
  });
});

test.describe('pass171 · Profile reviews long names (real jar)', () => {
  test('Profile > Reviews wraps a long reviewer name, item name and own reply on a phone', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await consent(page);
    await noWrites(page);
    await devLogin(page, 2);
    await page.route(/\/api\/reviews\/user\/2(\?.*)?$/, (r) => (r.request().method() === 'GET' ? r.fulfill(json([REVIEW])) : r.fallback()));
    await page.goto(`${base}/profile/reviews`);
    const row = page.locator('.profile-reviews-list .stall-review').first();
    await row.scrollIntoViewIfNeeded({ timeout: 20_000 });
    for (const sel of ['.stall-review-from', '.stall-review-item', '.stall-review-reply-body']) await fits(row.locator(sel));
  });
});

test.describe('pass171 · Loadout Lab detail long names (real jar)', () => {
  test('a long owner name and slot item name wrap on a phone', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await consent(page);
    await noWrites(page);
    await page.route(/\/api\/loadouts\/1(\?.*)?$/, async (r) => {
      if (r.request().method() !== 'GET') return r.fallback();
      const res = await r.fetch();
      const d = await res.json();
      d.loadout.ownerName = 'Owner' + LONG;
      d.slots[0].itemName = 'Hair' + LONG;
      await r.fulfill({ json: d });
    });
    await page.goto(`${base}/loadout/1`);
    const owner = page.locator('strong', { hasText: /^OwnerQ+$/ });
    await expect(owner).toBeVisible({ timeout: 20_000 });
    await fits(owner);
    const slot = page.locator('.loadout-slot-name', { hasText: /HairQ+/ }).first();
    await slot.scrollIntoViewIfNeeded();
    await fits(slot);
    await fits(slot.locator('a'));
  });
});

test.describe('pass171 · Notifications page long title', () => {
  test('a long unbroken notification title wraps inside its row on a phone', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await signedIn(page);
    await page.route('**/api/notifications', (r) => r.fulfill(json({ items: [
      { id: 1, kind: 'TRADE_COMPLETED', title: 'Trade' + LONG, body: 'Wizard Hat delivered', read: false, createdAt: Date.now() - 60e3 },
    ], unread: 1 })));
    await page.goto('/notifications');
    const title = page.locator('.notif-feed-title', { hasText: /TradeQ+/ });
    await expect(title).toBeVisible({ timeout: 15_000 });
    await fits(title);
  });
});

test.describe('pass171 · Escape closes one layer (real jar)', () => {
  test('stall: Escape that closes the bell leaves the Contact drawer open', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await devLogin(page, 3);
    await page.goto(`${base}/stall/2`);
    await page.getByRole('button', { name: 'Contact' }).click({ timeout: 20_000 });
    const drawer = page.getByRole('dialog', { name: /Contact @/ });
    await expect(drawer).toBeVisible();
    const bell = page.getByRole('button', { name: /^Notifications/ }).first();
    await bell.click();
    await expect(page.locator('.notif-dropdown')).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(page.locator('.notif-dropdown')).toHaveCount(0);
    await expect(drawer).toBeVisible();
    // Escape from inside the drawer still closes it.
    await page.getByLabel('Message to support about this seller').press('Escape');
    await expect(drawer).toHaveCount(0);
  });
});
