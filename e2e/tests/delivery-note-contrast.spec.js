const { test, expect } = require('@playwright/test');

// The delivery note under an item's Buy button ("Delivery is not instant…" or
// "Delivered in-platform…") drew its 12px body text in the faintest grey on
// its raised panel, about 3.5:1, under the 4.5:1 that small text needs.
test('the delivery note on an item page is readable on its panel', async ({ page }) => {
  await page.goto('/item/12');
  const note = page.locator('.delivery-expectation').first();
  await expect(note).toBeVisible({ timeout: 15_000 });
  const ratio = await note.evaluate((el) => {
    const parse = (s) => s.match(/[\d.]+/g).map(Number);
    const lum = ([r, g, b]) => {
      const f = (x) => { x /= 255; return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4); };
      return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
    };
    const s = getComputedStyle(el);
    const a = lum(parse(s.color)), b = lum(parse(s.backgroundColor));
    return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
  });
  expect(ratio).toBeGreaterThanOrEqual(4.5);
});
