const { test, expect } = require('@playwright/test');

// The "· v1.0.0" build tag at the end of the footer copyright line was drawn
// in the faint ink, 4.21:1 on the footer, under the 4.5:1 floor for text.
test('the footer version tag is readable', async ({ page }) => {
  await page.goto('/');
  const tag = page.locator('footer.site-footer .site-footer-copy > span').first();
  await expect(tag).toBeVisible({ timeout: 15_000 });
  const ratio = await tag.evaluate((el) => {
    const parse = (s) => s.match(/[\d.]+/g).map(Number);
    const lum = ([r, g, b]) => {
      const f = (x) => { x /= 255; return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4); };
      return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
    };
    let bg = el;
    while (bg && (getComputedStyle(bg).backgroundColor === 'rgba(0, 0, 0, 0)' || parse(getComputedStyle(bg).backgroundColor)[3] === 0)) bg = bg.parentElement;
    const a = lum(parse(getComputedStyle(el).color));
    const b = lum(parse(getComputedStyle(bg || document.body).backgroundColor));
    return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
  });
  expect(ratio).toBeGreaterThanOrEqual(4.5);
});
