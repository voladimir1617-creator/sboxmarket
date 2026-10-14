// @ts-check
const { test, expect } = require('@playwright/test');

// Public pages that used to jump as their data arrived. Each test holds the
// data back, notes where something below sits at first paint, lets the data
// in, and checks it has not moved.
const hold = (page, re, ms = 1500) => page.route(re, async (route) => {
  await new Promise((r) => setTimeout(r, ms));
  await route.continue();
});
const top = (loc) => loc.evaluate((e) => e.getBoundingClientRect().top);

test.describe('on a phone', () => {
  test.use({ viewport: { width: 390, height: 844 } });

  // The featured strip used to be inserted above the "how trades work"
  // tiles when listings arrived, pushing them down 460px.
  test('home keeps the featured strip\'s place while listings load', async ({ page }) => {
    await hold(page, /\/api\/listings(\/featured)?(\?|$)/);
    await page.goto('/');
    const tiles = page.locator('.csfloat-home-tiles');
    await expect(tiles).toBeVisible({ timeout: 15_000 });
    const before = await top(tiles);
    await expect(page.locator('.csfloat-home-preview-tail')).toBeVisible({ timeout: 10_000 });
    expect(Math.abs((await top(tiles)) - before)).toBeLessThan(2);
  });
});

// The LIVE TAPE bar used to be inserted above the market when recent sales
// arrived, dropping the whole page 32px.
test('market keeps the live tape\'s place while recent sales load', async ({ page }) => {
  await hold(page, /\/api\/listings\/recent-sales/);
  await page.goto('/market');
  const main = page.locator('main#main');
  await expect(main).toBeVisible({ timeout: 15_000 });
  const before = await top(main);
  await expect(page.locator('.pulse .pulse-stream')).toBeAttached({ timeout: 10_000 });
  expect(Math.abs((await top(main)) - before)).toBeLessThan(2);
});

// The seller card on an item page grew from one line to its figures and
// note when the trade record arrived, pushing the price and buttons 78px.
test('item page keeps the seller card\'s size while the trade record loads', async ({ page }) => {
  await hold(page, /\/api\/sellers\/trust/);
  await page.goto('/item/1');
  const card = page.getByTestId('seller-trust');
  await expect(card).toBeVisible({ timeout: 15_000 });
  const before = (await card.boundingBox())?.height || 0;
  await expect(card.locator('[data-stat="completed"] .seller-trust-val')).toHaveText(/\d/, { timeout: 10_000 });
  const after = (await card.boundingBox())?.height || 0;
  expect(Math.abs(after - before)).toBeLessThan(2);
});
