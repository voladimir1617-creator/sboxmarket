const { test, expect } = require('@playwright/test');

// The "Listed 6d ago" line on every market card was 11px in the faintest grey
// on the card, about 4.0:1, under the 4.5:1 small text needs. It now matches
// the presence line beside it.
test('the listed-ago line on a market card is readable', async ({ page }) => {
  await page.goto('/market');
  const age = page.locator('.listing-grid > .grid-card .gc-card-age').first();
  await expect(age).toBeVisible({ timeout: 15_000 });
  const ratio = await age.evaluate((el) => {
    const parse = (s) => s.match(/[\d.]+/g).map(Number);
    const lum = ([r, g, b]) => {
      const f = (x) => { x /= 255; return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4); };
      return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
    };
    let bgEl = el;
    while (bgEl && (parse(getComputedStyle(bgEl).backgroundColor)[3] ?? 1) < 0.9) bgEl = bgEl.parentElement;
    const fg = lum(parse(getComputedStyle(el).color)), bg = lum(parse(getComputedStyle(bgEl).backgroundColor));
    return (Math.max(fg, bg) + 0.05) / (Math.min(fg, bg) + 0.05);
  });
  expect(ratio).toBeGreaterThanOrEqual(4.5);
});
