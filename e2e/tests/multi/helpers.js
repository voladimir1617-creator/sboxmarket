// @ts-check
// Shared helpers for the multi-user market pass (*.multi.spec.js).
//
// Each test user is its own browser context, signed in through dev-login, so
// two or three people really are using the site at the same moment. Every
// request that is not to the app under test is aborted: nothing here may
// reach Steam, a CDN or any other outside site.
const { expect } = require('@playwright/test');

/** Seeded demo accounts (SeedService.SEED_SELLER_ACCOUNTS): id -> steamId64. */
const STEAM_IDS = {
  2: '76561199000000002',
  3: '76561199000000003',
  4: '76561199000000004',
};

const FEE_RATE = 0.02; // TradeService.FEE_RATE

function cents(v) { return Math.round(Number(v) * 100); }
/** Seller fee in cents, HALF_UP to whole cents like TradeService.open. */
function feeCents(priceCents) { return Math.floor(priceCents * FEE_RATE + 0.5 + 1e-9); }

function tradeUrlFor(steamId64) {
  const partner = (BigInt(steamId64) - 76561197960265728n).toString();
  return `https://steamcommunity.com/tradeoffer/new/?partner=${partner}&token=E2eTok3n`;
}

async function signIn(browser, baseURL, userId) {
  const context = await browser.newContext({ baseURL, viewport: { width: 1440, height: 900 } });
  const origin = new URL(baseURL).origin;
  await context.route((url) => url.origin !== origin, (route) => route.abort());
  const page = await context.newPage();
  const res = await page.goto(`/api/auth/steam/dev-login?userId=${userId}`);
  if (res && res.status() === 404) {
    const body = await res.text().catch(() => '');
    throw new Error(`dev-login refused for user ${userId}: ${body.slice(0, 200)} ` +
      '(start the server with SBOX_DEV_LOGIN_ENABLED=true, see e2e/README.md)');
  }
  await expect(page).toHaveURL(/\/profile/);
  return { id: userId, steamId64: STEAM_IDS[userId], context, page };
}

async function csrf(u) {
  const c = (await u.context.cookies()).find((k) => k.name === 'sbox_csrf');
  return c ? c.value : '';
}

/** JSON call as this user, CSRF header included. Returns {status, ok, body}. */
async function call(u, method, path, data) {
  const res = await u.page.request.fetch(path, {
    method,
    headers: { 'X-CSRF-Token': await csrf(u), 'Content-Type': 'application/json' },
    data,
  });
  let body = null;
  try { body = await res.json(); } catch { /* not JSON */ }
  return { status: res.status(), ok: res.ok(), body };
}

async function balanceCents(u) {
  const r = await call(u, 'GET', '/api/wallet');
  expect(r.ok, 'wallet readable').toBeTruthy();
  return cents(r.body.balance);
}

/** The header chip must show the same balance the server holds. */
async function expectHeaderBalance(u, c) {
  const txt = `$${(c / 100).toFixed(2)}`;
  await expect(u.page.locator('.wallet-btn-amt').first()).toHaveText(txt, { timeout: 10_000 });
}

/** Tops the wallet up to at least `minCents` through the real deposit form. */
async function depositViaUi(u, preset) {
  await u.page.goto('/wallet');
  await u.page.getByRole('button', { name: preset, exact: true }).click();
  const go = u.page.getByRole('button', { name: /^Deposit/ }).filter({ hasText: /Deposit \(dev mode\)|Deposit \$/ }).first();
  await go.click();
  await expect(u.page.getByText(/Deposited \$[\d,.]+/)).toBeVisible({ timeout: 10_000 });
}

async function setTradeUrlViaApi(u) {
  const r = await call(u, 'PUT', '/api/profile/trade-url', { tradeUrl: tradeUrlFor(u.steamId64) });
  expect(r.ok, `trade URL saved for user ${u.id} (${r.status} ${JSON.stringify(r.body)})`).toBeTruthy();
}

async function listings(u, query = 'sort=price_asc') {
  const r = await call(u, 'GET', `/api/listings?${query}`);
  expect(r.ok).toBeTruthy();
  return Array.isArray(r.body) ? r.body : (r.body.content || []);
}

async function trade(u, id) {
  const r = await call(u, 'GET', `/api/trades/${id}`);
  expect(r.ok, `trade ${id} readable by user ${u.id}`).toBeTruthy();
  return r.body;
}

async function tradeForListing(u, listingId) {
  const r = await call(u, 'GET', '/api/trades');
  const rows = Array.isArray(r.body) ? r.body : (r.body.content || r.body.trades || []);
  return rows.find((t) => t.listingId === listingId) || null;
}

/** Opens the profile Trades tab and returns the row card for this trade. */
async function tradeRow(u, tradeId) {
  await u.page.goto('/profile?tab=trades');
  const row = u.page.locator(`#trade-${tradeId}`);
  await expect(row).toBeVisible({ timeout: 15_000 });
  return row;
}

module.exports = {
  STEAM_IDS, cents, feeCents, tradeUrlFor, signIn, call, balanceCents, expectHeaderBalance,
  depositViaUi, setTradeUrlViaApi, listings, trade, tradeForListing, tradeRow,
};
