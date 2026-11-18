// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 138.

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

// Defect pass 138: the Report listing "thanks" is announced and inset, trade
// rows and chat messages expose their full date, trade-chat read receipts say
// what the ✓ / ✓✓ marks mean, the trade search finds a pasted "#id", and the
// seller rating chips on market cards and trade rows read with their scale.

const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';
const now = Date.now();

async function consent(page) {
  await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
}
// Real-jar tests never write: every POST/PUT/DELETE/PATCH is answered here.
async function noWrites(page) {
  await page.route('**/api/**', (r) => (['POST', 'PUT', 'DELETE', 'PATCH'].includes(r.request().method())
    ? r.fulfill(json({}))
    : r.fallback()));
}
async function devLogin(page, userId) {
  await page.request.get(`${base}/api/auth/steam/dev-login?userId=${userId}&next=/`);
}

test.describe('pass138 · report listing drawer (real jar)', () => {
  test('a sent report.s thanks is announced and sits inside the panel.s inset', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await page.route('**/api/listings/25/report', (r) => r.fulfill(json({ thanks: 'Report received. Thanks — an admin will review it.' })));
    await devLogin(page, 2);
    await page.goto(`${base}/item/12`);
    await page.getByRole('button', { name: 'Report listing 25' }).click({ timeout: 20_000 });
    const dlg = page.getByRole('dialog', { name: 'Report listing' });
    await dlg.getByRole('button', { name: 'Submit report' }).click();
    const status = dlg.getByRole('status');
    await expect(status).toHaveText(/Report received/);
    const panel = await dlg.boundingBox();
    const box = await status.boundingBox();
    expect(box.x - panel.x).toBeGreaterThanOrEqual(16);
    expect(panel.x + panel.width - (box.x + box.width)).toBeGreaterThanOrEqual(16);
  });
});

test.describe('pass138 · profile trades (real jar)', () => {
  const OPENED = Date.UTC(2025, 9, 14, 15, 30);
  const TRADE = {
    id: 71, state: 'PENDING_BUYER_CONFIRM', buyerUserId: 2, sellerUserId: 5, itemId: 12, itemName: 'Vintage Design Crop Top',
    price: 0.52, counterpartyName: 'EmberWolf', createdAt: OPENED, updatedAt: now - 3600_000, sentAt: now - 3600_000,
    expiresAt: now + 30 * 3600_000, counterpartyRating: 4.6, counterpartyReviewCount: 12,
  };
  const MSGS = [
    { id: 1, senderUserId: 2, body: 'Sent you the request', createdAt: now - 7200_000, readAt: now - 5400_000 },
    { id: 2, senderUserId: 2, body: 'Any news?', createdAt: now - 600_000, readAt: null },
  ];
  async function tradesPage(page) {
    await consent(page);
    await noWrites(page);
    await page.route((u) => u.pathname === '/api/trades', (r) => (r.request().method() === 'GET' ? r.fulfill(json([TRADE])) : r.fallback()));
    await page.route((u) => u.pathname === '/api/trades/71/messages', (r) => (r.request().method() === 'GET' ? r.fulfill(json(MSGS)) : r.fallback()));
    await devLogin(page, 2);
    await page.goto(`${base}/profile/trades`);
  }

  test('a trade row says when it opened, not only "Nd ago"', async ({ page }) => {
    await tradesPage(page);
    const date = page.locator('#trade-71 .trade-date');
    await expect(date).toHaveText(/^\d+d ago$/, { timeout: 20_000 });
    const full = await page.evaluate((ms) => new Date(ms).toLocaleString(), OPENED);
    await expect(date).toHaveAttribute('title', new RegExp(full.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
  });

  test('the counterparty rating chip reads with its scale', async ({ page }) => {
    await tradesPage(page);
    const chip = page.locator('#trade-71').getByRole('img', { name: 'Rated 4.6 out of 5 from 12 reviews' });
    await expect(chip).toBeVisible({ timeout: 20_000 });
    await expect(chip).toHaveText('4.6★');
  });

  test('chat read receipts say Read / not read yet instead of a bare check mark', async ({ page }) => {
    await tradesPage(page);
    await page.getByRole('button', { name: /^Chat about Vintage Design Crop Top \(trade #71\)/ }).click({ timeout: 20_000 });
    await expect(page.getByText('Any news?')).toBeVisible();
    await expect(page.getByRole('img', { name: /^Read / })).toHaveCount(1);
    await expect(page.getByRole('img', { name: /^Sent, not read yet$/ })).toHaveCount(1);
    // The message time also carries the full date.
    const full = await page.evaluate((ms) => new Date(ms).toLocaleString(), MSGS[1].createdAt);
    await expect(page.getByText(/^You · 10m ago$/)).toHaveAttribute('title', full);
  });
});

test.describe('pass138 · profile trades search (real jar)', () => {
  const row = (id, itemName) => ({
    id, state: 'VERIFIED', buyerUserId: 2, sellerUserId: 5, itemId: 12, itemName, price: 0.52,
    counterpartyName: 'EmberWolf', createdAt: now - id * 86400_000, updatedAt: now - id * 86400_000,
  });
  const TRADES = [row(71, 'Vintage Design Crop Top'), row(72, 'Short Parted Hair'), row(73, 'Black Modern Watch'), row(74, 'Police Hat')];

  test('searching the "#72" the row shows and copies finds that trade', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await page.route((u) => u.pathname === '/api/trades', (r) => (r.request().method() === 'GET' ? r.fulfill(json(TRADES)) : r.fallback()));
    await devLogin(page, 2);
    await page.goto(`${base}/profile/trades`);
    await expect(page.getByRole('button', { name: 'Copy trade #72' })).toHaveText(/#72/, { timeout: 20_000 });
    await page.getByRole('textbox', { name: 'Search trades' }).fill('#72');
    await expect(page.locator('.trade-row')).toHaveCount(1);
    await expect(page.locator('#trade-72')).toBeVisible();
    await expect(page.getByRole('heading', { name: /No trades match/ })).toHaveCount(0);
  });
});

test.describe('pass138 · market grid cards (real jar)', () => {
  test('a card.s seller rating chip reads with its scale, as the table view.s does', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await page.route((u) => u.pathname === '/api/listings', async (route) => {
      const res = await route.fetch();
      const body = await res.json();
      const list = Array.isArray(body) ? body : (body.content || body.items || []);
      for (const l of list) { l.sellerRating = 4.5; l.sellerReviewCount = 12; }
      await route.fulfill({ response: res, json: body });
    });
    await page.goto(`${base}/market`);
    const card = page.locator('.grid-card').first();
    await expect(card).toBeVisible({ timeout: 20_000 });
    const chip = card.getByRole('img', { name: 'Rated 4.5 out of 5 from 12 reviews' });
    await expect(chip).toHaveCount(1);
    await expect(chip).toHaveText(/★ 4\.5/);
  });
});

test.describe('pass138 · report listing drawer on a phone (stubbed)', () => {
  test.use({ viewport: { width: 390, height: 844 } });
  const ITEM = { id: 5, name: 'Black Watch', category: 'Hats', rarity: 'Common', lowestPrice: 10, imageUrl: '' };
  const LISTING = { id: 11, price: 10, sellerUserId: 9, sellerName: 'bob', status: 'ACTIVE', item: ITEM };

  test('the thanks line keeps the panel.s side margins and is a status', async ({ page }) => {
    await consent(page);
    await signedIn(page);
    await page.route('**/api/listings/item/5**', (r) => r.fulfill(json([LISTING])));
    await page.route('**/api/items/5', (r) => r.fulfill(json(ITEM)));
    await page.route('**/api/listings/report-reasons', (r) => r.fulfill(json(['Suspicious pricing', 'Other'])));
    await page.route('**/api/listings/11/report', (r) => r.fulfill(json({ thanks: 'Report received.' })));
    expect(ME.id).not.toBe(LISTING.sellerUserId);
    await page.goto('/item/5');
    await page.getByRole('button', { name: 'Report listing 11' }).click();
    const dlg = page.getByRole('dialog', { name: 'Report listing' });
    await dlg.getByRole('button', { name: 'Submit report' }).click();
    const status = dlg.getByRole('status');
    await expect(status).toHaveText('Report received.');
    const panel = await dlg.boundingBox();
    const box = await status.boundingBox();
    expect(box.x - panel.x).toBeGreaterThanOrEqual(16);
  });
});

test.describe('pass138 · rarity tier tooltips match how tiers are assigned (real jar)', () => {
  // SboxApiService: Limited = past its Steam sell-end; Off-Market ("Scarce")
  // = under 5% of minted supply left and still on sale. The copy had them
  // the other way round.
  test('database legend: Scarce says Steam still sells it, Limited says Steam no longer does', async ({ page }) => {
    await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
    await page.goto(`${base}/db`);
    const legend = page.getByRole('group', { name: 'Rarity tier legend' });
    const scarce = legend.getByRole('button', { name: 'Scarce', exact: true });
    const limited = legend.getByRole('button', { name: 'Limited', exact: true });
    await expect(scarce).toBeVisible({ timeout: 20_000 });
    await expect(scarce).toHaveAccessibleDescription(/Steam still sells/);
    await expect(scarce).not.toHaveAccessibleDescription(/not currently sold by Steam/);
    await expect(limited).toHaveAccessibleDescription(/Steam no longer sells/);
  });
});
