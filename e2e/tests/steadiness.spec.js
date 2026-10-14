// @ts-check
const { test, expect } = require('@playwright/test');

// Signed-in full pages on a phone-sized screen (390x844). Before
// 2026-10-13 a full page (wallet, offers, My stall, buy orders) first drew
// a short shell with the site footer on screen, then the data arrived and
// shoved the footer away: layout shifts of 0.4 to 0.8 on load. Each full
// page now reserves at least one screen, so the footer starts below the fold.
test.use({ viewport: { width: 390, height: 844 } });

for (const path of ['/wallet', '/offers', '/me/stall', '/buy-orders']) {
  test(`${path} reserves a full screen while it loads`, async ({ page }) => {
    // Hold the page's data back so the loading state is observable.
    await page.route(/\/api\/(?!auth)/, async (route) => {
      await new Promise((r) => setTimeout(r, 1500));
      await route.continue();
    });
    await page.goto(path);
    const backdrop = page.locator('.site-root.full-page-mode > .modal-backdrop');
    await expect(backdrop).toBeVisible({ timeout: 15_000 });
    const footerTop = await page.locator('footer.site-footer').evaluate((f) => f.getBoundingClientRect().top);
    expect(footerTop).toBeGreaterThanOrEqual(page.viewportSize().height);
  });
}

// My stall: the Payouts card used to appear only once its figures arrived,
// inserted above the stall toolbar and pushing the listings down ~200px.
test('My stall keeps the Payouts card in place while its figures load', async ({ page }) => {
  await page.route(/\/api\/wallet\/payouts/, async (route) => {
    await new Promise((r) => setTimeout(r, 2000));
    await route.continue();
  });
  await page.goto('/me/stall');
  const toolbar = page.locator('.stall-toolbar');
  await expect(toolbar).toBeVisible({ timeout: 15_000 });
  // Where it sits at first paint, while the card's data is still held back.
  const before = await toolbar.evaluate((e) => e.getBoundingClientRect().top);
  await expect(page.getByTestId('seller-payouts')).toBeVisible({ timeout: 10_000 });
  await expect(page.getByTestId('seller-payouts')).not.toHaveAttribute('aria-busy', 'true', { timeout: 10_000 });
  const after = await toolbar.evaluate((e) => e.getBoundingClientRect().top);
  expect(Math.abs(after - before)).toBeLessThan(2);
});

// Profile: the Account Standing card used to appear once the profile
// arrived, inserted above the tabs and pushing them down ~180px.
test('Profile keeps the Account Standing card in place while it loads', async ({ page }) => {
  await page.route(/\/api\/profile\/me/, async (route) => {
    await new Promise((r) => setTimeout(r, 2000));
    await route.continue();
  });
  await page.goto('/profile');
  const tabs = page.locator('.profile-tabs');
  await expect(tabs).toBeVisible({ timeout: 15_000 });
  // Where it sits at first paint, while the card's data is still held back.
  const before = await tabs.evaluate((e) => e.getBoundingClientRect().top);
  await expect(page.getByTestId('account-standing')).toBeVisible({ timeout: 10_000 });
  // Watch every frame as the standing arrives: the lit node grows over
  // 0.2s, and the tabs must not dip while it does.
  const tops = await tabs.evaluate((e) => new Promise((done) => {
    const seen = [];
    const t0 = performance.now();
    const tick = () => {
      seen.push(e.getBoundingClientRect().top);
      if (document.querySelector('[data-testid="account-standing"][aria-busy]') || performance.now() - t0 < 600) requestAnimationFrame(tick);
      else done(seen);
    };
    tick();
  }));
  for (const t of tops) expect(Math.abs(t - before)).toBeLessThan(2);
});

// Until the sign-in check answers, every visitor looks signed out. Signed-in
// pages used to flash "Sign in required" (and Settings its sign-in banner,
// then slide every row down 140px) before drawing the real page.
test.describe('while the sign-in check is still answering', () => {
  test.beforeEach(async ({ page }) => {
    await page.route(/\/api\/auth\/steam\/me$/, async (route) => {
      await new Promise((r) => setTimeout(r, 1500));
      await route.continue();
    });
  });

  for (const path of ['/wallet', '/offers', '/notifications']) {
    test(`${path} never flashes "Sign in required" at a signed-in visitor`, async ({ page }) => {
      await page.goto(path);
      await expect(page.locator('.site-root.full-page-mode > .modal-backdrop')).toBeVisible({ timeout: 15_000 });
      // A one-shot look, not a retrying expect: the flash is what we're after.
      await page.waitForTimeout(500);
      expect(await page.getByText('Sign in required').isVisible()).toBe(false);
    });
  }

  test('Loadout Lab draws its signed-in tab row once', async ({ page }) => {
    await page.goto('/loadout');
    const search = page.getByRole('textbox', { name: 'Search public loadouts' });
    await expect(search).toBeVisible({ timeout: 15_000 });
    const before = await search.evaluate((e) => e.getBoundingClientRect().top);
    await expect(page.getByRole('tab', { name: 'My Loadouts' })).toBeVisible({ timeout: 10_000 });
    const after = await search.evaluate((e) => e.getBoundingClientRect().top);
    expect(Math.abs(after - before)).toBeLessThan(2);
  });

  test('Settings draws once, in its signed-in layout', async ({ page }) => {
    await page.goto('/settings');
    await expect(page.locator('.site-root.full-page-mode > .modal-backdrop')).toBeVisible({ timeout: 15_000 });
    await page.waitForTimeout(500);
    expect(await page.getByText('Display & Accessibility only').isVisible()).toBe(false);
    await expect(page.getByText('Notifications & sound')).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText('Display & Accessibility only')).toHaveCount(0);
  });
});
