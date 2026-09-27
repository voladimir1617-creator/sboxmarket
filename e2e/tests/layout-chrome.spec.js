// @ts-check
const { test, expect } = require('@playwright/test');

/**
 * Layout defects found walking every route at 1440x900 signed in. Each test
 * measures the rendered geometry the defect broke.
 */

test('the Watchlist count in the nav is a small badge, not a 40px capsule', async ({ page }) => {
  await page.goto('/market');
  const card = page.locator('.grid-card').first();
  await expect(card).toBeVisible({ timeout: 15_000 });
  // Make sure there is something to count.
  await card.hover();
  const star = card.locator('.grid-star').first();
  if ((await star.getAttribute('aria-pressed')) !== 'true') await star.click();
  const badge = page.locator('nav.nav .nav-link .nav-link-badge').first();
  await expect(badge).toBeVisible();
  const h = await badge.evaluate((el) => el.getBoundingClientRect().height);
  // It inherited the nav link's 36px line-height and drew 40px tall.
  expect(h).toBeLessThanOrEqual(20);
});

test('a stall\'s stats are not squeezed into a column by its action buttons', async ({ page }) => {
  // A seller other than the signed-in user, so the full action row renders.
  const r = await page.request.get('/api/listings?size=200');
  const rows = await r.json();
  const other = (Array.isArray(rows) ? rows : []).find((l) => l.sellerUserId && l.sellerUserId !== 1);
  test.skip(!other, 'no stall other than the signed-in user\'s to open');
  await page.goto('/stall/' + other.sellerUserId);
  const grid = page.locator('.stall-stat-grid').first();
  await expect(grid).toBeVisible({ timeout: 15_000 });
  const w = await grid.evaluate((el) => el.getBoundingClientRect().width);
  // It measured 204px beside five buttons at 1440.
  expect(w).toBeGreaterThan(500);
});

test('a deep link to a profile section lands on that section', async ({ page }) => {
  await page.goto('/profile/offers');
  const tabs = page.locator('.profile-tabs').first();
  await expect(tabs).toBeVisible({ timeout: 15_000 });
  // The tab bar is brought to the top, under the sticky header; before, it sat
  // a screen and a half down, below earnings, stat tiles and standing.
  await expect.poll(async () => tabs.evaluate((el) => Math.round(el.getBoundingClientRect().top)),
    { timeout: 8_000 }).toBeLessThan(200);
});

test('the wallet page has no dialog close button', async ({ page }) => {
  await page.goto('/wallet');
  await expect(page.locator('.wallet-hero').first()).toBeVisible({ timeout: 15_000 });
  await expect(page.locator('.wallet-modal > .modal-close')).toBeHidden();
});
