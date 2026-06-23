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
      testIgnore: /\.(anon|mobile)\.spec\.js/,
      dependencies: ['setup'],
      use: {
        ...devices['Desktop Chrome'],
        viewport: { width: 1440, height: 900 },
        storageState: '.auth/user.json',
      },
    },
    {
      name: 'mobile-auth',
      testMatch: /\.mobile\.spec\.js/,
      dependencies: ['setup'],
      use: { ...devices['iPhone 13'], storageState: '.auth/user.json' },
    },
  ],
});
