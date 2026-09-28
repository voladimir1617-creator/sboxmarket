// @ts-check
const { test, expect } = require('@playwright/test');
const fs = require('fs');

// Dev-login once and persist the authenticated session so the signed-in money
// flows reuse it. The dev-login endpoint sets the server session cookie and
// redirects to /profile (dev-only convenience; real prod uses Steam OpenID).
//
// THE DOOR IS CLOSED BY DEFAULT. `/api/auth/steam/dev-login` mints a session
// for any user id with no credential of any kind, so it is not enough for the
// deployment to be non-production -- someone has to have ASKED for it, in the
// process environment of the server we are talking to:
//
//   SBOX_DEV_LOGIN_ENABLED=true
//
// See com.sboxmarket.config.DevLoginGate and e2e/README.md. That variable is
// deliberately NOT readable from application.yml or any other committed file,
// so it cannot be inherited by a deployment that never decided to have it: it
// is set by this harness's operator, on the one server process under test.
const AUTH_FILE = '.auth/user.json';
const OPT_IN = 'SBOX_DEV_LOGIN_ENABLED';

test('authenticate via dev-login', async ({ page, context }) => {
  const resp = await page.goto('/api/auth/steam/dev-login?userId=1');

  // Diagnose the shut door BEFORE asserting on the URL. Without this the
  // failure is a 15-second timeout on `toHaveURL(/\/profile/)` with no hint
  // about why -- which is precisely the "absence read as a mystery" shape the
  // guard's response body exists to prevent. The guard SAYS why it refused;
  // read it and repeat it.
  if (resp && resp.status() === 404) {
    let reason = '';
    try {
      reason = ((await resp.json()) || {}).error || '';
    } catch {
      reason = (await resp.text().catch(() => '')).slice(0, 200);
    }
    if (reason.startsWith('dev-login disabled:')) {
      throw new Error(
        `The server refused dev-login: "${reason}"\n\n` +
          `The e2e suite authenticates through that endpoint, and it is closed by ` +
          `default. Restart the app under test with ${OPT_IN}=true in its PROCESS ` +
          `environment (see e2e/README.md), then re-run.\n` +
          `If the reason above mentions a real-money deployment, do NOT set the ` +
          `variable -- that server can move real money and must never serve a ` +
          `credential-free login.`
      );
    }
  }

  // dev-login redirects to /profile on success
  await expect(page).toHaveURL(/\/profile/, { timeout: 15_000 });
  // Sanity: the nav shows the user chip (signed-in), not the Steam sign-in CTA.
  await expect(page.locator('text=/Sign in through Steam/i')).toHaveCount(0);
  await prepareDevUser(page);
  fs.mkdirSync('.auth', { recursive: true });
  await context.storageState({ path: AUTH_FILE });
  expect(fs.existsSync(AUTH_FILE)).toBeTruthy();
});

// A fresh database gives user 1 a $0 wallet, no trade URL and nothing in
// Platform Inventory, and the signed-in money specs (buy round-trip, bargain,
// sell panel) need all three. Set them up here, once, instead of assuming a
// long-lived dev database already has them. The trade URL is always set (it
// moves no money). The wallet is topped up only when the server was ALSO
// started with SBOX_DEV_CREDIT_ENABLED=true; with that door shut the deposit is
// refused and the money specs report the empty wallet themselves.
async function prepareDevUser(page) {
  const api = page.request;
  const csrf = (await page.context().cookies()).find((c) => c.name === 'sbox_csrf')?.value || '';
  const hdrs = { 'X-CSRF-Token': csrf, 'Content-Type': 'application/json' };

  const me = await (await api.get('/api/auth/steam/me')).json().catch(() => ({}));
  if (!me.tradeUrl && me.steamId64) {
    const partner = (BigInt(me.steamId64) - 76561197960265728n).toString();
    await api.put('/api/profile/trade-url', {
      headers: hdrs,
      data: { tradeUrl: `https://steamcommunity.com/tradeoffer/new/?partner=${partner}&token=E2eSetup1` },
    });
  }

  const balance = async () => Number((await (await api.get('/api/wallet')).json()).balance || 0);
  if (await balance() < 50) {
    const dep = await api.post('/api/wallet/deposit', { headers: hdrs, data: { amount: 100 } });
    if (!dep.ok()) {
      console.warn(`[setup] dev deposit refused (${dep.status()}): the money specs need ` +
        'SBOX_DEV_CREDIT_ENABLED=true on a fresh database');
    }
  }

  const inv = await (await api.get('/api/listings/inventory')).json().catch(() => []);
  const owned = Array.isArray(inv) ? inv : inv.items || inv.content || [];
  if (owned.length === 0) {
    const listings = await (await api.get('/api/listings?sort=price_asc')).json();
    const house = listings.find((l) => l.sellerUserId == null && l.listingType !== 'AUCTION' && l.status === 'ACTIVE');
    if (house && await balance() >= Number(house.price)) {
      await api.post(`/api/listings/${house.id}/buy`, { headers: hdrs, data: {} });
    }
  }
}
