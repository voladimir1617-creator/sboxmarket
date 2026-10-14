const { test, expect } = require('@playwright/test');

// From 721 to 900px the bell sits in the header's left-hand cluster, and the
// notifications panel, right-aligned to it, opened past the left edge of the
// screen. It must stay on screen at every header layout.
for (const width of [390, 768, 820, 900, 1024, 1280]) {
  test(`at ${width}px the notifications panel opens fully on screen`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 });
    await page.goto('/market');
    await page.getByRole('button', { name: 'Notifications' }).first().click();
    const panel = page.locator('.notif-dropdown');
    await expect(panel).toBeVisible({ timeout: 15_000 });
    const box = await panel.evaluate((d) => {
      const r = d.getBoundingClientRect();
      return { left: Math.round(r.left), right: Math.round(r.right), vw: document.documentElement.clientWidth };
    });
    expect(box.left).toBeGreaterThanOrEqual(0);
    expect(box.right).toBeLessThanOrEqual(box.vw);
  });
}
