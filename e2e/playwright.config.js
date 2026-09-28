// @ts-check
const { defineConfig, devices } = require('@playwright/test');

/**
 * sboxmarket e2e — headless customer-flow tests against a live server.
 * BASE_URL defaults to the local dev server; point it at https://skinbox.market
 * to smoke prod. Auth: the `auth.setup.js` project dev-logs-in once and saves
 * storageState to .auth/user.json, which the `chromium-auth` project reuses so
 * the signed-in money flows don't each re-login.
 */
const BASE_URL = process.env.E2E_BASE_URL || 'http://localhost:8082';
// Optional: a pre-installed Chromium to launch instead of Playwright's own
// download (sandboxed CI boxes and cloud containers ship one).
const CHROMIUM_PATH = process.env.E2E_CHROMIUM_PATH || undefined;

module.exports = defineConfig({
  testDir: './tests',
  // Money flows mutate shared server state (cart, listings); keep within a file
  // serial-friendly but allow cross-file parallelism on read-only specs.
  // Serial: the signed-in money flows all share ONE dev user's session/cart,
  // so parallel workers would race on shared server state (cart, listings).
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : [['list'], ['html', { open: 'never' }]],
  timeout: 30_000,
  expect: { timeout: 8_000 },
  use: {
    baseURL: BASE_URL,
    viewport: { width: 1440, height: 900 },
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'off',
    launchOptions: CHROMIUM_PATH ? { executablePath: CHROMIUM_PATH } : {},
  },
  projects: [
    { name: 'setup', testMatch: /auth\.setup\.js/ },
    {
      name: 'anon',
      testMatch: /\.anon\.spec\.js/,
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } },
    },
    {
      name: 'chromium-auth',
      // `mobile(-auth)?` — a *.mobile-auth.spec.js file belongs to the
      // mobile-auth project below and must not ALSO be picked up here and
      // run at a 1440x900 desktop viewport.
      testIgnore: /\.(anon|multi|mobile(-auth)?)\.spec\.js/,
      dependencies: ['setup'],
      use: {
        ...devices['Desktop Chrome'],
        viewport: { width: 1440, height: 900 },
        storageState: '.auth/user.json',
      },
    },
    // ── several people at once ───────────────────────────────────────────
    // *.multi.spec.js signs in its own users (one browser context each) and
    // moves TEST money between them, so it needs the dev-credit opt-in as
    // well as dev-login -- see the header of market-pass.multi.spec.js.
    {
      name: 'multi-user',
      testMatch: /\.multi\.spec\.js/,
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } },
    },
    // ── mobile ────────────────────────────────────────────────────────────
    // SPLIT FROM A SINGLE `mobile-auth` PROJECT, AND HERE IS WHY.
    //
    // Every mobile spec used to sit in one project carrying
    // `dependencies: ['setup']` + `storageState: '.auth/user.json'`. `setup`
    // is auth.setup.js, which dev-logs-in through an endpoint that is CLOSED
    // BY DEFAULT (SBOX_DEV_LOGIN_ENABLED, see auth.setup.js and e2e/README.md
    // — and it must stay closed on the port a cloudflared tunnel maps
    // skinbox.market to). So on any server that has not opted in, `setup`
    // fails and Playwright SKIPS the whole dependent project.
    //
    // The specs it was skipping do not need a session. They load `/`,
    // `/market`, `/item/:id`, `/cart` and `/cookies` signed out and assert on
    // horizontal overflow, grid/toolbar geometry and the bottom nav — the
    // file's own header says "Mobile smoke (signed-out reachable pages)".
    // MEASURED directly at phone width: those surfaces render with 0px of
    // horizontal overflow and a bottom nav, signed out. They were not failing;
    // they were not running, which is worse, because a project that never ran
    // reports nothing and reads like coverage.
    //
    // `mobile-anon` runs them with no dependency and no storageState. The
    // `mobile-auth` lane is kept for mobile specs that genuinely need a
    // session — they opt in by name (`*.mobile-auth.spec.js`) rather than by
    // being in the default bucket, so the gate is something a spec asks for
    // instead of something it inherits. `testIgnore` on `mobile-anon` keeps
    // the two from double-running the same file.
    {
      name: 'mobile-anon',
      testMatch: /\.mobile\.spec\.js/,
      use: { ...devices['iPhone 13'] },
    },
    {
      name: 'mobile-auth',
      testMatch: /\.mobile-auth\.spec\.js/,
      dependencies: ['setup'],
      use: { ...devices['iPhone 13'], storageState: '.auth/user.json' },
    },
  ],
});
