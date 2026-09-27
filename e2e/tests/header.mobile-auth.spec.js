// @ts-check
const { test, expect } = require('@playwright/test');

// Signed-in phone header (iPhone 13 project, 390px; measured at 393px too).
// Before 2026-09-26 the currency picker + Offers icon + bell + cart + balance
// pill + avatar chip needed ~410px inside a ~360px nav: the balance pill ran
// past the right edge and the avatar chip was drawn on top of it.
test('signed-in header fits the phone width and the balance is fully visible', async ({ page }) => {
  await page.goto('/market');
  const pill = page.locator('nav.nav .nav-right .wallet-btn');
  await expect(pill).toBeVisible({ timeout: 15_000 });
  const vw = page.viewportSize().width;
  const boxes = await page.locator('nav.nav .nav-right > *').evaluateAll(els =>
    els.map(e => e.getBoundingClientRect()).filter(b => b.width > 0).map(b => ({ l: b.left, r: b.right })));
  for (const b of boxes) expect(b.r).toBeLessThanOrEqual(vw);
  // No two visible header controls overlap.
  const sorted = [...boxes].sort((a, b) => a.l - b.l);
  for (let i = 1; i < sorted.length; i++) expect(sorted[i].l).toBeGreaterThanOrEqual(sorted[i - 1].r - 0.5);
});
