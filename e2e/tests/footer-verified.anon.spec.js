// @ts-check
const { test, expect } = require('@playwright/test');

// Footer and seller-check pass (2026-10-14).

const RULE = 'Verified seller: 10+ completed sales, rated 4★ or higher (or no reviews yet)';

// With no payment processor wired the footer's last line names none, so the
// "POWERED BY" eyebrow that headed it ("POWERED BY All prices in USD") is gone.
test('the footer meta line has no orphan "POWERED BY" eyebrow', async ({ page }) => {
  await page.goto('/');
  const meta = page.locator('footer.site-footer .site-footer-meta');
  await expect(meta).toContainText('Test mode: no real money moves', { timeout: 15_000 });
  const before = await meta.evaluate((el) => getComputedStyle(el, '::before').content);
  expect(before === 'none' || before === 'normal' || before === '""').toBe(true);
});

// Patch the home hero's listings so every seller has five 5★ reviews but
// is not verified by the server's rule (fewer than 10 sales).
async function patchHero(page, sellerVerified) {
  await page.route(/\/api\/listings\?sort=price_desc&limit=8/, async (route) => {
    const res = await route.fetch();
    const rows = await res.json();
    const list = Array.isArray(rows) ? rows : (rows.items || []);
    for (const l of list) {
      l.sellerUserId = l.sellerUserId ?? 424242;
      l.sellerReviewCount = 6; l.sellerRating = 5; l.sellerVerified = sellerVerified;
    }
    await route.fulfill({ response: res, json: rows });
  });
}

test('the home hero shows the ✓ only for sellers the server verifies', async ({ page }) => {
  await patchHero(page, false);
  await page.goto('/');
  await expect(page.locator('.csfloat-home-hero-feature, .csfloat-hero-stack').first()).toBeVisible({ timeout: 15_000 });
  await page.waitForTimeout(500);
  expect(await page.locator('.csfloat-hero-stack-verified, .csfloat-home-hero-feature-verified').count()).toBe(0);
});

test('the ✓ tooltip states the rule the server applies', async ({ page }) => {
  await patchHero(page, true);
  await page.goto('/');
  const mark = page.locator('.csfloat-hero-stack-verified, .csfloat-home-hero-feature-verified').first();
  await expect(mark).toBeAttached({ timeout: 15_000 });
  await expect(mark).toHaveAttribute('title', RULE);
  await expect(page.locator('[title="Verified seller (5+ reviews, rated 4★+)"]')).toHaveCount(0);
});

test.describe('phone', () => {
  test.use({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });

  // The back-to-top button sat over the end of the footer's last line at
  // the bottom of every page.
  test('at the bottom of the page the back-to-top button clears the footer text', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('footer.site-footer .site-footer-meta')).toBeVisible({ timeout: 15_000 });
    await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
    const fab = page.locator('.app-back-to-top');
    await expect(fab).toBeVisible();
    await page.waitForTimeout(400);
    const { metaBottom, fabTop } = await page.evaluate(() => ({
      metaBottom: document.querySelector('footer.site-footer .site-footer-meta').getBoundingClientRect().bottom,
      fabTop: document.querySelector('.app-back-to-top').getBoundingClientRect().top,
    }));
    expect(metaBottom).toBeLessThanOrEqual(fabTop);
  });
});
