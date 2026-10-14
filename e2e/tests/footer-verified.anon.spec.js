// @ts-check
const { test, expect } = require('@playwright/test');

// Footer and seller-check pass (2026-10-14).

const RULE = 'Verified seller: 10+ completed sales, rated 4★ or higher (or no reviews yet)';

// With no payment processor wired the footer's last line names none, so the
// "POWERED BY" eyebrow that headed it ("POWERED BY All prices in USD") is gone.
test('the footer meta line has no orphan "POWERED BY" eyebrow', async ({ page }) => {
  await page.goto('/');
  const meta = page.locator('footer.site-footer .site-footer-meta');
  await expect(meta).toContainText('Test mode: no real money moves', { timeout: 15_000 });
  const before = await meta.evaluate((el) => getComputedStyle(el, '::before').content);
  expect(before === 'none' || before === 'normal' || before === '""').toBe(true);
});

// Patch the home hero's listings so every seller has five 5★ reviews but
// is not verified by the server's rule (fewer than 10 sales).
async function patchHero(page, sellerVerified) {
  await page.route(/\/api\/listings\?sort=price_desc&limit=8/, async (route) => {
    const res = await route.fetch();
    const rows = await res.json();
    const list = Array.isArray(rows) ? rows : (rows.items || []);
    for (const l of list) {
      l.sellerUserId = l.sellerUserId ?? 424242;
      l.sellerReviewCount = 6; l.sellerRating = 5; l.sellerVerified = sellerVerified;
    }
    await route.fulfill({ response: res, json: rows });
  });
}

test('the home hero shows the ✓ only for sellers the server verifies', async ({ page }) => {
  await patchHero(page, false);
  await page.goto('/');
  await expect(page.locator('.csfloat-home-hero-feature, .csfloat-hero-stack').first()).toBeVisible({ timeout: 15_000 });
  await page.waitForTimeout(500);
  expect(await page.locator('.csfloat-hero-stack-verified, .csfloat-home-hero-feature-verified').count()).toBe(0);
});

test('the ✓ tooltip states the rule the server applies', async ({ page }) => {
  await patchHero(page, true);
  await page.goto('/');
  const mark = page.locator('.csfloat-hero-stack-verified, .csfloat-home-hero-feature-verified').first();
  await expect(mark).toBeAttached({ timeout: 15_000 });
  await expect(mark).toHaveAttribute('title', RULE);
  await expect(page.locator('[title="Verified seller (5+ reviews, rated 4★+)"]')).toHaveCount(0);
});

test.describe('phone', () => {
  test.use({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });

  // The back-to-top button sat over the end of the footer's last line at
  // the bottom of every page.
  test('at the bottom of the page the back-to-top button clears the footer text', async ({ page }) => {
    // A visitor who has answered the cookie banner: its pill, and the extra
    // footer room kept for it, are gone.
    await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'accepted'); } catch (_) {} });
    await page.goto('/');
    await expect(page.locator('footer.site-footer .site-footer-meta')).toBeVisible({ timeout: 15_000 });
    // The home page keeps growing as its rails load, so keep scrolling to
    // the true bottom and measure only once the page has stopped growing.
    await expect.poll(async () => page.evaluate(() => {
      window.scrollTo(0, document.documentElement.scrollHeight);
      const fab = document.querySelector('.app-back-to-top');
      const atBottom = Math.ceil(window.scrollY + window.innerHeight) >= document.documentElement.scrollHeight - 1;
      if (!fab || !atBottom) return 'scrolling';
      const metaBottom = document.querySelector('footer.site-footer .site-footer-meta').getBoundingClientRect().bottom;
      return metaBottom <= fab.getBoundingClientRect().top ? 'clear' : `overlap ${metaBottom} > ${fab.getBoundingClientRect().top}`;
    }), { timeout: 15_000, intervals: [500] }).toBe('clear');
  });
});

// On the market the back-to-top button stacks above the Filters button,
// higher than on other pages: it covered the footer's last line on a phone
// and the copyright line on a tablet.
for (const [width, mobile] of [[390, true], [768, false]]) {
  test.describe(`market at ${width}`, () => {
    test.use({ viewport: { width, height: 844 }, isMobile: mobile, hasTouch: mobile });

    test('the floating buttons clear the footer text at the bottom of the page', async ({ page }) => {
      await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'accepted'); } catch (_) {} });
      await page.goto('/market');
      await expect(page.locator('.mobile-filters-fab')).toBeVisible({ timeout: 15_000 });
      await expect.poll(async () => page.evaluate(() => {
        window.scrollTo(0, document.documentElement.scrollHeight);
        const btt = document.querySelector('.app-back-to-top');
        const atBottom = Math.ceil(window.scrollY + window.innerHeight) >= document.documentElement.scrollHeight - 1;
        if (!btt || !atBottom) return 'scrolling';
        const buttons = [btt, document.querySelector('.mobile-filters-fab')].map((b) => b.getBoundingClientRect());
        const texts = [...document.querySelectorAll('footer.site-footer *')]
          .filter((t) => [...t.childNodes].some((n) => n.nodeType === 3 && n.textContent.trim().length > 1));
        for (const t of texts) {
          const r = t.getBoundingClientRect();
          for (const b of buttons) {
            if (r.width && r.left < b.right && r.right > b.left && r.top < b.bottom && r.bottom > b.top) return `covers "${t.innerText.slice(0, 30)}"`;
          }
        }
        return 'clear';
      }), { timeout: 15_000, intervals: [500] }).toBe('clear');
    });
  });
}

// Below 900px an item page keeps a Buy bar fixed at the bottom. From 721 to
// 900px it covered the footer's last line, and at every width up to 900 it
// hid the back-to-top button behind it.
for (const [width, mobile] of [[390, true], [768, false]]) {
  test.describe(`item page at ${width}`, () => {
    test.use({ viewport: { width, height: 844 }, isMobile: mobile, hasTouch: mobile });

    test('the Buy bar and back-to-top leave the footer text and each other clear', async ({ page }) => {
      await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'accepted'); } catch (_) {} });
      await page.goto('/item/12');
      // The Buy bar appears once the page's own Buy button scrolls away.
      await expect(page.locator('h1')).toContainText('Vintage Design Crop Top', { timeout: 15_000 });
      await expect.poll(async () => page.evaluate(() => {
        window.scrollTo(0, document.documentElement.scrollHeight);
        const btt = document.querySelector('.app-back-to-top');
        const atBottom = Math.ceil(window.scrollY + window.innerHeight) >= document.documentElement.scrollHeight - 1;
        const barEl = document.querySelector('.item-buy-bar');
        if (!btt || !barEl || !atBottom) return 'scrolling';
        const bar = barEl.getBoundingClientRect();
        const b = btt.getBoundingClientRect();
        if (b.bottom > bar.top) return `back-to-top under the Buy bar ${b.bottom} > ${bar.top}`;
        const texts = [...document.querySelectorAll('footer.site-footer *')]
          .filter((t) => [...t.childNodes].some((n) => n.nodeType === 3 && n.textContent.trim().length > 1));
        for (const t of texts) {
          const r = t.getBoundingClientRect();
          for (const c of [bar, b]) {
            if (r.width && r.left < c.right && r.right > c.left && r.top < c.bottom && r.bottom > c.top) return `covers "${t.innerText.slice(0, 30)}"`;
          }
        }
        return 'clear';
      }), { timeout: 15_000, intervals: [500] }).toBe('clear');
    });
  });
}
