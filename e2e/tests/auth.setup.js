// @ts-check
const { test, expect } = require('@playwright/test');
const fs = require('fs');

// Dev-login once and persist the authenticated session so the signed-in money
// flows reuse it. The dev-login endpoint sets the server session cookie and
// redirects to /profile (dev-only convenience; real prod uses Steam OpenID).
const AUTH_FILE = '.auth/user.json';

test('authenticate via dev-login', async ({ page, context }) => {
  const resp = await page.goto('/api/auth/steam/dev-login?userId=1');
  // dev-login redirects to /profile on success
  await expect(page).toHaveURL(/\/profile/, { timeout: 15_000 });
  // Sanity: the nav shows the user chip (signed-in), not the Steam sign-in CTA.
  await expect(page.locator('text=/Sign in through Steam/i')).toHaveCount(0);
  fs.mkdirSync('.auth', { recursive: true });
  await context.storageState({ path: AUTH_FILE });
  expect(fs.existsSync(AUTH_FILE)).toBeTruthy();
});
