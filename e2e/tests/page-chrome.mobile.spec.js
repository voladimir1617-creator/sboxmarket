// @ts-check
const { test, expect } = require('@playwright/test');

/**
 * EVERY PAGE KEEPS THE SITE'S OWN NAVIGATION ON A PHONE.
 *
 * Measured at 393x852 before the fix: /help, /faq, /cart, /db, /stall/:id,
 * /loadout, /settings, the 404 page and every signed-in page built on the
 * info-modal shell (/profile/*, /offers, /notifications, /watchlist, /sell,
 * /me/stall) rendered as a full-screen sheet that covered BOTH the header and
 * the bottom nav, with no close control. `.modal-backdrop:has(.info-modal)` is
 * position:fixed at z-index 200 (it is the dialog backdrop), and the only
 * full-page override that lowers it is scoped to min-width 901px. A shopper
 * who opened Help on a phone could leave only with the browser's Back button.
 *
 * "Visible" is checked the honest way: the element at the centre of the bottom
 * nav and of the header must BELONG to them, not to a sheet drawn over them.
 */

const PAGES = ['/help', '/faq', '/cart', '/db', '/loadout', '/this-page-does-not-exist'];

test.describe('page chrome on a phone', () => {
  test.use({ viewport: { width: 393, height: 852 } });

  for (const path of PAGES) {
    test(`${path} keeps the header and the bottom nav on top`, async ({ page }) => {
      await page.goto(path);
      const nav = page.locator('nav.nav').first();
      const bottom = page.locator('nav.csfloat-mobile-nav').first();
      await expect(nav).toBeVisible({ timeout: 15_000 });
      await expect(bottom).toBeVisible();
      const owners = await page.evaluate(() => {
        const hit = (sel) => {
          const el = document.querySelector(sel);
          if (!el) return 'missing';
          const r = el.getBoundingClientRect();
          const top = document.elementFromPoint(r.left + r.width / 2, r.top + Math.min(r.height / 2, 20));
          return top && el.contains(top) ? 'own' : ('covered by ' + (top ? top.className : 'nothing'));
        };
        return { header: hit('nav.nav'), bottom: hit('nav.csfloat-mobile-nav') };
      });
      expect(owners.header).toBe('own');
      expect(owners.bottom).toBe('own');
    });
  }
});
