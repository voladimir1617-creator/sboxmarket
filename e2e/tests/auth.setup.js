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
  fs.mkdirSync('.auth', { recursive: true });
  await context.storageState({ path: AUTH_FILE });
  expect(fs.existsSync(AUTH_FILE)).toBeTruthy();
});
