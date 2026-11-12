// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 112.

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

// Pass 112. Real-jar checks sign in through dev-login against JAR; the
// stubbed ones run on the static copy at baseURL.

const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';

async function consent(page) {
  await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
}
async function devLogin(page, userId) {
  await page.request.get(`${base}/api/auth/steam/dev-login?userId=${userId}&next=/`);
}
// Records whether the app cancelled the last click (read after dispatch, as
// the pass 108 specs do): a modifier-click the app leaves alone is what makes
// the browser open the link in a new tab.
async function recordClicks(page) {
  await page.addInitScript(() => {
    window.__clickPrevented = null;
    window.addEventListener('click', (e) => { setTimeout(() => { window.__clickPrevented = e.defaultPrevented; }, 0); }, true);
  });
}
// Steam's image CDN failing (or refusing a hotlink): every item image errors.
async function brokenItemImages(page) {
  await page.route(/\/economy\/image\//, (r) => r.abort());
}
// Item images on screen that failed and still show (a broken-image box or an empty slot).
const brokenImgs = (scope) => scope.evaluate((root) => [...root.querySelectorAll('img')]
  .filter((i) => i.complete && i.naturalWidth === 0 && getComputedStyle(i).display !== 'none').length);

const L = (id, name, price, o = {}) => ({
  id, item: { id: 100 + id, name, category: 'Hats', rarity: 'Standard', imageUrl: null },
  price, status: 'ACTIVE', listingType: 'BUY_NOW', listedAt: Date.now() - 86400e3, hidden: false, sellerUserId: 3, ...o,
});

test.describe('pass112 new-tab clicks on FAQ and home links (real jar)', () => {
  test('Ctrl/Cmd-click on the FAQ "Open a support ticket" link leaves the FAQ in place', async ({ page }) => {
    await consent(page);
    await recordClicks(page);
    await page.goto(`${base}/faq`);
    const link = page.getByRole('link', { name: /Open a support ticket/ });
    await expect(link).toBeVisible();
    expect(new URL(await link.getAttribute('href'), base).pathname).toBe('/support');
    await link.click({ modifiers: ['ControlOrMeta'] });
    await expect.poll(() => page.evaluate(() => window.__clickPrevented)).toBe(false);
    await expect(page).toHaveURL(/\/faq$/);
    await expect(page.getByRole('heading', { level: 1, name: 'FAQ' })).toBeVisible();
    // A plain click still moves in-app.
    await link.click();
    await expect(page).toHaveURL(/\/support$/);
  });

  test('Ctrl/Cmd-click on the home "View full FAQ" link leaves the home page in place', async ({ page }) => {
    await consent(page);
    await recordClicks(page);
    await page.goto(`${base}/`);
    const link = page.getByRole('link', { name: /View full FAQ/ });
    await link.scrollIntoViewIfNeeded();
    await expect(link).toBeInViewport();
    await link.click({ modifiers: ['ControlOrMeta'] });
    await expect.poll(() => page.evaluate(() => window.__clickPrevented)).toBe(false);
    await expect(page).toHaveURL(new RegExp(`^${base}/?$`));
  });
});

test.describe('pass112 item image fallbacks (real jar)', () => {
  test('item page Recently viewed cards show the category glyph when an image fails', async ({ page }) => {
    await consent(page);
    await brokenItemImages(page);
    for (const id of [1, 2, 3, 4, 5]) {
      await page.goto(`${base}/item/${id}`);
      await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    }
    await page.goto(`${base}/item/6`);
    const rail = page.locator('section.recently-viewed');
    await rail.scrollIntoViewIfNeeded();
    await expect(rail).toBeInViewport();
    const cards = rail.locator('.recently-viewed-card');
    await expect(cards).toHaveCount(5);
    await expect(rail.locator('.recently-viewed-thumb .item-poster')).toHaveCount(5);
    await expect(rail.locator('.recently-viewed-thumb .item-poster').first()).toBeVisible();
    expect(await brokenImgs(rail)).toBe(0);
    // The card still reads its name once.
    const name = (await cards.first().locator('.recently-viewed-name').innerText()).trim();
    const esc = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    await expect(cards.first()).toHaveAccessibleName(new RegExp(`^${esc} [^]*$`));
    expect((await cards.first().evaluate((e) => e.textContent)).split(name).length).toBe(2);
  });

  test('My Stall Analytics rows show the category glyph, not a broken-image box, when an image fails', async ({ page }) => {
    await consent(page);
    await brokenItemImages(page);
    await devLogin(page, 2);
    await page.goto(`${base}/me/stall/analytics`);
    const panel = page.getByRole('tabpanel', { name: /Analytics/ });
    await expect(panel.getByText(/Per-listing analytics/)).toBeVisible();
    await expect(panel.locator('.item-poster').first()).toBeVisible();
    expect(await panel.locator('.item-poster').count()).toBeGreaterThan(3);
    expect(await brokenImgs(panel)).toBe(0);
  });
});

test.describe('pass112 My Stall keeps focus when the server refuses (stubbed)', () => {
  test('a refused Bulk price adjust keeps focus on its Apply button', async ({ page }) => {
    await consent(page);
    await signedIn(page);
    await page.route('**/api/listings/my-stall', (r) => r.fulfill(json([L(1, 'Red Beanie', 4.5), L(2, 'Blue Cap', 3)])));
    await page.route('**/api/listings/my-stall/bulk-adjust', (r) => setTimeout(() => r.fulfill(json({ error: 'Bulk adjust is paused' }, 503)), 600));
    await page.goto('/me/stall');
    await page.getByRole('button', { name: /Bulk price adjust/ }).click();
    const dlg = page.getByRole('dialog', { name: /Bulk price adjust/ });
    await dlg.getByRole('button', { name: '-5%' }).click();
    const apply = dlg.getByRole('button', { name: /-5% to 2/ });
    await apply.focus();
    await page.keyboard.press('Enter');
    await expect(dlg.getByRole('alert').filter({ hasText: /Bulk adjust is paused/ })).toBeVisible();
    await expect(apply).toBeFocused();
    await expect(apply).toBeEnabled();
  });

  test('a refused listing cancel keeps focus on that row.s Cancel button', async ({ page }) => {
    await consent(page);
    await signedIn(page);
    await page.route('**/api/listings/my-stall', (r) => r.fulfill(json([L(1, 'Red Beanie', 4.5), L(2, 'Blue Cap', 3)])));
    let deletes = 0;
    await page.route('**/api/listings/1', (r) => {
      if (r.request().method() !== 'DELETE') return r.fallback();
      deletes++;
      setTimeout(() => r.fulfill(json({ error: 'This listing has a trade in progress' }, 409)), 600);
    });
    await page.goto('/me/stall');
    const x = page.getByRole('button', { name: 'Cancel listing for Red Beanie' });
    await x.focus();
    await page.keyboard.press('Enter');
    // A second press while the first is out sends nothing.
    await page.keyboard.press('Enter');
    await expect(page.getByText(/trade in progress/).first()).toBeVisible();
    await page.waitForTimeout(400);
    await expect(x).toBeFocused();
    expect(deletes).toBe(1);
  });
});

const now = Date.now();
const trade = (o) => ({
  id: 41, state: 'PENDING_SELLER_SEND', itemName: 'Red Beanie', itemId: 12, buyerUserId: 9, sellerUserId: 3,
  price: 4.5, createdAt: now - 3600e3, counterpartyName: 'dave', counterpartyUserId: 9,
  counterpartyTradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=2&token=x',
  itemImageUrl: 'https://steamcommunity-a.akamaihd.net/economy/image/pass112-missing', ...o,
});

test.describe('pass112 trade and offer row thumbnails', () => {
  test('a trade row whose item image fails shows the item glyph, not an empty tile (stubbed)', async ({ page }) => {
    await consent(page);
    await signedIn(page);
    await brokenItemImages(page);
    await page.route('**/api/trades', (r) => r.fulfill(json([trade({})])));
    await page.goto('/profile/trades');
    const copy = page.getByRole('button', { name: 'Copy trade #41', exact: true });
    await expect(copy).toBeVisible();
    const row = page.locator('.trade-row', { has: copy });
    await expect(row.locator('.item-poster')).toHaveCount(1);
    await expect(row.locator('.item-poster')).toBeVisible();
  });

  test('offer rows whose item image fails show the item glyph on Offers and Profile > Offers (real jar)', async ({ page }) => {
    await consent(page);
    await brokenItemImages(page);
    await devLogin(page, 1);
    const csrf = async () => ((await page.context().cookies(base)).find((c) => c.name === 'sbox_csrf') || {}).value;
    const me = await (await page.request.get(`${base}/api/auth/steam/me`)).json();
    const headers = { 'Content-Type': 'application/json', 'X-CSRF-Token': await csrf() };
    // Offers need a trade URL whose partner id is the account's own.
    const partner = (BigInt(me.steamId64) - 76561197960265728n).toString();
    await page.request.put(`${base}/api/profile/trade-url`, { headers,
      data: { tradeUrl: `https://steamcommunity.com/tradeoffer/new/?partner=${partner}&token=abcDEF12` } });
    const out = await (await page.request.get(`${base}/api/offers/outgoing`)).json();
    if (!(Array.isArray(out) && out.some((o) => o.status === 'PENDING'))) {
      const all = await (await page.request.get(`${base}/api/listings?size=200`)).json();
      const pick = all.find((l) => l.sellerUserId === 2 && l.listingType === 'BUY_NOW' && parseFloat(l.price) >= 1);
      const res = await page.request.post(`${base}/api/offers`, { headers,
        data: { listingId: pick.id, amount: Math.floor(parseFloat(pick.price) * 90) / 100 } });
      const body = await res.json();
      expect(body.status, JSON.stringify(body)).toBe('PENDING');
    }
    await page.goto(`${base}/offers/outgoing`);
    const row = page.locator('.offer-row').first();
    await expect(row).toBeVisible();
    await expect(row.locator('.item-poster')).toBeVisible();
    await page.goto(`${base}/profile/offers?view=outgoing`);
    const prow = page.locator('.offer-row').first();
    await expect(prow).toBeVisible();
    await expect(prow.locator('.item-poster')).toBeVisible();
  });
});

test.describe('pass112 changelog headings (real jar)', () => {
  test('each changelog entry title is a heading under its date, so the outline says what changed', async ({ page }) => {
    await page.goto(`${base}/changelog.html`);
    await expect(page.getByRole('heading', { level: 1, name: 'Changelog' })).toBeVisible();
    const dates = page.getByRole('heading', { level: 2 });
    const titles = page.getByRole('heading', { level: 3 });
    const n = await dates.count();
    expect(n).toBeGreaterThan(5);
    await expect(titles).toHaveCount(n);
    await expect(page.getByRole('heading', { level: 3, name: /Site reliability \+ UX sweep/ })).toBeVisible();
    // Dates alone repeat ("April 2026" again and again); the titles tell entries apart.
    const names = await titles.allInnerTexts();
    expect(new Set(names).size).toBe(n);
  });
});
