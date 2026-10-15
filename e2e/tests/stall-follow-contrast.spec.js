const { test, expect } = require('@playwright/test');

// On a seller's stall the Follow button drew the hero's grey button text on
// its own blue fill, a 1.6:1 contrast that made "Follow" close to unreadable.
test('the stall Follow button text is readable on its blue fill', async ({ page }) => {
  await page.goto('/stall/5');
  const btn = page.locator('.stall-hero > .stall-share-btn[title*="Get notified"]');
  await expect(btn).toBeVisible({ timeout: 15_000 });
  await page.mouse.move(0, 0);
  // The button fades its colours over 280ms, so poll until the settled
  // colour is read rather than a mid-transition one.
  await expect.poll(() => btn.evaluate((el) => {
    const parse = (s) => s.match(/[\d.]+/g).map(Number);
    const lum = ([r, g, b]) => {
      const f = (x) => { x /= 255; return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4); };
      return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
    };
    const s = getComputedStyle(el);
    const a = lum(parse(s.color)), b = lum(parse(s.backgroundColor));
    return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
  }), { timeout: 5_000 }).toBeGreaterThanOrEqual(4.5);
});
