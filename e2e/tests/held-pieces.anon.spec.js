const { test, expect } = require('@playwright/test');

// The pieces the owner approved from look-learning passes 7, 8 and 18,
// against the real app (H2 demo data). Each block is one feature.

const day = (i, price, volume) => ({ id: i + 1, price, volume, recordedAt: Date.now() - (10 - i) * 86400000, dayLabel: `Nov ${i + 1}, 2026` });

// ── Price history: volume bars under the line ────────────────────────────
test.describe('price history volume bars', () => {
  test('days with SkinBox sales get bars, a trade-count tick and a legend', async ({ page }) => {
    const rows = [day(0, 1, 2), day(1, 1.1, 0), day(2, 1.2, 5), day(3, 1.15, 1), day(4, 1.3, 3)];
    await page.route('**/api/items/12/history', (r) => r.fulfill({ json: rows }));
    await page.goto('/item/12');
    const strip = page.getByTestId('chart-volume');
    await expect(strip).toBeVisible({ timeout: 15_000 });
    // One bar per day that had a sale (4 of 5), the busiest day's count as the tick.
    await expect(strip.locator('rect')).toHaveCount(4);
    await expect(strip.locator('text')).toHaveText('5 trades');
    await expect(page.getByText('Bars: number of sales on SkinBox each day.')).toBeVisible();
    await expect(page.locator('.chart-wrap svg')).toHaveAttribute('aria-label', /11 sales on SkinBox in this range\./);
  });

  test('a line with no sales in it (Steam price only) draws no bars and no legend', async ({ page }) => {
    const rows = [day(0, 1, 0), day(1, 1.1, 0), day(2, 1.2, 0)];
    await page.route('**/api/items/12/history', (r) => r.fulfill({ json: rows }));
    await page.goto('/item/12');
    await expect(page.locator('.chart-wrap svg')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId('chart-volume')).toHaveCount(0);
    await expect(page.locator('.chart-volume-legend')).toHaveCount(0);
  });
});
