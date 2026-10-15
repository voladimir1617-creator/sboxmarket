const { test, expect } = require('@playwright/test');

// The slot labels on a loadout ("HATS", "JACKETS", …) were 10px uppercase in
// the faintest grey at 4.49:1, just under the 4.5:1 small text needs.
test('loadout slot labels are readable', async ({ page }) => {
  await page.goto('/loadout/1');
  const label = page.locator('.loadout-slot-label').first();
  await expect(label).toBeVisible({ timeout: 15_000 });
  const ratio = await label.evaluate((el) => {
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
