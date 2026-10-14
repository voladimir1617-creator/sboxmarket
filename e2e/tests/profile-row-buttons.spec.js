const { test, expect } = require('@playwright/test');

// The profile page renders inside the info-modal shell, whose empty-state
// call-to-action rule (full width up to 320px, 44px tall) also caught the
// "Enable 2FA" button in the Two-Factor Auth row. It ran 320px wide next to
// "Disabled" and, on a phone, 32px past the edge of the profile card.
for (const [width, mobile] of [[390, true], [1440, false]]) {
  test.describe(`profile at ${width}`, () => {
    test.use({ viewport: { width, height: 900 }, isMobile: mobile, hasTouch: mobile });

    test('the Enable 2FA button is a row button and stays inside its row', async ({ page }) => {
      await page.goto('/profile');
      const btn = page.locator('.profile-row-value button.btn-accent', { hasText: 'Enable 2FA' });
      await expect(btn).toBeVisible({ timeout: 15_000 });
      const m = await btn.evaluate((b) => {
        const r = b.getBoundingClientRect();
        const row = b.closest('.profile-row-value').getBoundingClientRect();
        return { height: Math.round(r.height), width: Math.round(r.width), overRow: Math.round(r.right - row.right) };
      });
      expect(m.height).toBe(32);
      expect(m.width).toBeLessThan(160);
      expect(m.overRow).toBeLessThanOrEqual(0);
    });
  });
}
