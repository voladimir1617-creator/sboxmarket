// @ts-check
const { test, expect } = require('@playwright/test');

// Mobile smoke (signed-out reachable pages, phone + tablet). Real customers
// browse on phones — guard against horizontal overflow, lost screen width and
// missing mobile chrome.
//
// ───────────────────────────────────────────────────────────────────────────
// WHY EVERY OVERFLOW ASSERTION GOES THROUGH `overflowPx()` AND NOT THROUGH
// `documentElement.scrollWidth - clientWidth` DIRECTLY.
//
// design.css L20547 declares
//
//     @media (max-width: 640px) { html, body { overflow-x: hidden !important } }
//
// and a hidden overflow CLAMPS scrollWidth to clientWidth. On any phone-width
// viewport the naive difference is therefore pinned to 0 — structurally, for
// every page, whatever the layout does.
//
// The three overflow tests in this file used to read that naive difference.
// MEASURED: with the app loaded at 375x812, appending a 2000px-wide <div> to
// <body> moved the naive reading not at all —
//
//     clean : {"overflow":0,"naive":0,"vw":375}
//     dirty : {"overflow":1641,"naive":0,"vw":375}     <- 2000px div injected
//
// — so those assertions could not fail and had never been able to. They were a
// missing signal rendered as a healthy one, which is the defect family this
// codebase keeps paying for, sitting inside the tests meant to catch it.
//
// `overflowPx()` lifts the clamp, forces a reflow, measures, and puts it back.
// ───────────────────────────────────────────────────────────────────────────
async function overflowPx(page) {
  return page.evaluate(() => {
    const de = document.documentElement, bd = document.body;
    const savedHtml = de.style.cssText, savedBody = bd.style.cssText;
    de.style.setProperty('overflow-x', 'visible', 'important');
    bd.style.setProperty('overflow-x', 'visible', 'important');
    void de.offsetWidth; // force reflow so scrollWidth is recomputed unclamped
    const px = de.scrollWidth - de.clientWidth;
    de.style.cssText = savedHtml;
    bd.style.cssText = savedBody;
    void de.offsetWidth;
    return px;
  });
}

const box = (page, sel) => page.evaluate((s) => {
  const el = document.querySelector(s);
  if (!el) return null;
  const r = el.getBoundingClientRect();
  return { w: Math.round(r.width), h: Math.round(r.height), left: Math.round(r.left), top: Math.round(r.top) };
}, sel);

// ═══════════════════════════════════════════════════════════════════════════
// PHONE — pinned to 375x812 rather than inheriting the project's iPhone 13
// (390x844) so the pixel budgets below mean the same thing on every run.
// ═══════════════════════════════════════════════════════════════════════════
test.describe('mobile smoke', () => {
  test.use({ viewport: { width: 375, height: 812 } });

  test('home has no horizontal overflow + renders content', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('text=/Buy & Sell s&box Skins/i')).toBeVisible({ timeout: 15_000 });
    expect(await overflowPx(page)).toBeLessThanOrEqual(2);
  });

  test('market grid renders on mobile without overflow', async ({ page }) => {
    await page.goto('/market');
    await expect(page.locator('.grid-card').first()).toBeVisible({ timeout: 15_000 });
    expect(await overflowPx(page)).toBeLessThanOrEqual(2);
  });

  test('mobile bottom nav / tab bar is present', async ({ page }) => {
    await page.goto('/market');
    const bottomNav = page.locator('[class*="bottom-nav"], [class*="mobile-nav"], [class*="tabbar"], [class*="tab-bar"]').first();
    await expect(bottomNav).toBeVisible({ timeout: 10_000 });
  });

  test('item detail is usable on mobile (no overflow)', async ({ page }) => {
    await page.goto('/item/12');
    await expect(page.locator('.modal-body').first()).toBeVisible({ timeout: 15_000 });
    expect(await overflowPx(page)).toBeLessThanOrEqual(2);
  });

  // ── WAVE 190 regressions ────────────────────────────────────────────────

  // The cookie table was the one page in the app that genuinely scrolled the
  // document sideways: the <table> in legal/cookies.html measured L40 R409 in
  // a 335px column and the document ran 34px wide. It is also the page that
  // proves `overflowPx` works — this test fails by 34px against the old CSS.
  test('the cookie policy table does not widen the document', async ({ page }) => {
    await page.goto('/cookies');
    await expect(page.locator('.legal-doc table')).toBeVisible({ timeout: 15_000 });
    expect(await overflowPx(page)).toBeLessThanOrEqual(2);

    const t = await box(page, '.legal-doc table');
    expect(t, 'the cookie table should be present').not.toBeNull();
    // @ts-ignore  guarded above
    expect(t.left + t.w).toBeLessThanOrEqual(376);
  });

  // THE STACKED GUTTER. body(16) + main.layout(24) + .main(16) each applied a
  // desktop-scale inline padding, and they nest: the grid got 263px of a 375px
  // screen (70%) and its two columns came out 123.5px.
  // MEASURED after: 343px (91%), columns 163.5px.
  // The floor is set at 320px — below that the stacking has come back.
  test('the market grid gets most of the phone screen, not 70% of it', async ({ page }) => {
    await page.goto('/market');
    await expect(page.locator('.grid-card').first()).toBeVisible({ timeout: 15_000 });

    const grid = await box(page, '.listing-grid');
    expect(grid, '.listing-grid should be present').not.toBeNull();
    // @ts-ignore  guarded above
    expect(grid.w, 'market grid width at 375px').toBeGreaterThanOrEqual(320);

    const colW = await page.evaluate(() => {
      const g = document.querySelector('.listing-grid');
      if (!g) return 0;
      return parseFloat(getComputedStyle(g).gridTemplateColumns.split(' ')[0]);
    });
    expect(colW, 'market card column width at 375px').toBeGreaterThanOrEqual(150);
  });

  // The toolbar's children carry `margin-left: auto` for the ONE desktop row
  // (design.css L896, L20371). Wrapped onto a phone, each auto margin centred
  // or right-aligned its own row: six ragged rows, 274px tall, pushing the
  // first listing to y=542. MEASURED after: 180px tall, first card at y=448.
  test('the market toolbar does not wrap into a ragged column', async ({ page }) => {
    await page.goto('/market');
    await expect(page.locator('.grid-card').first()).toBeVisible({ timeout: 15_000 });

    const tb = await box(page, '.toolbar');
    expect(tb, '.toolbar should be present').not.toBeNull();
    // @ts-ignore  guarded above
    expect(tb.h, 'toolbar height at 375px').toBeLessThanOrEqual(220);

    // Every wrapped ROW must start at the toolbar's own left edge. Group the
    // children by their top coordinate and look at the first control in each
    // row — an indented second control in a row is ordinary flex flow and is
    // not what went wrong here; an indented ROW is the auto margin.
    const worstRowIndent = await page.evaluate(() => {
      const tbEl = document.querySelector('.toolbar');
      if (!tbEl) return 999;
      const base = tbEl.getBoundingClientRect().left;
      /** @type {Map<number, number>} */
      const rowLeft = new Map();
      for (const c of tbEl.children) {
        if (getComputedStyle(c).display === 'none') continue;
        const r = c.getBoundingClientRect();
        if (r.width === 0 || r.height === 0) continue;
        const row = Math.round(r.top / 8) * 8; // tolerate sub-pixel baselines
        const prev = rowLeft.get(row);
        if (prev === undefined || r.left < prev) rowLeft.set(row, r.left);
      }
      if (!rowLeft.size) return 999;
      return Math.max(...[...rowLeft.values()].map((l) => Math.round(l - base)));
    });
    // Before: six rows, none sharing a left edge — .sort-picker resolved
    // margin-left:80.89px, .discount-select 47.5px, .toolbar-refresh 47.5px.
    expect(worstRowIndent, 'largest indent of a toolbar ROW from its left edge').toBeLessThanOrEqual(24);
  });

  // The item page is a full-page modal with its OWN container chain, and both
  // of its inner paddings stacked too: .modal-body(24) inside
  // .modal.item-page(14). MEASURED: 313px of 375. After: 341px.
  test('the item page content column is not squeezed by nested modal padding', async ({ page }) => {
    await page.goto('/item/12');
    await expect(page.locator('.modal-body').first()).toBeVisible({ timeout: 15_000 });
    const b = await box(page, '.modal-body');
    expect(b, '.modal-body should be present').not.toBeNull();
    // @ts-ignore  guarded above
    expect(b.w, 'item page content column at 375px').toBeGreaterThanOrEqual(330);
  });

  // The cart is a THIRD chain (.info-modal-body, 24px). MEASURED: cart rows
  // 291px of 375, which is why item names truncated to "Gumball Mac..." in the
  // one view where a buyer confirms what they are paying for. After: 315px.
  test('cart rows get the width to show what is being bought', async ({ page }) => {
    await page.goto('/market');
    await expect(page.locator('.grid-card').first()).toBeVisible({ timeout: 15_000 });
    const added = await page.evaluate(async () => {
      const btns = [...document.querySelectorAll('.grid-cart-btn')].filter((b) => /** @type {HTMLElement} */(b).offsetParent !== null);
      for (const b of btns.slice(0, 2)) {
        /** @type {HTMLElement} */(b).click();
        await new Promise((r) => setTimeout(r, 400));
      }
      return btns.length;
    });
    // "no add-to-cart buttons" and "the cart stayed empty" must not look alike.
    expect(added, 'add-to-cart buttons found on the market grid').toBeGreaterThan(0);

    await page.goto('/cart');
    await expect(page.locator('.cart-row').first()).toBeVisible({ timeout: 15_000 });
    expect(await overflowPx(page)).toBeLessThanOrEqual(2);

    const row = await box(page, '.cart-row');
    expect(row, '.cart-row should be present').not.toBeNull();
    // @ts-ignore  guarded above
    expect(row.w, 'cart row width at 375px').toBeGreaterThanOrEqual(300);
  });
});

// ═══════════════════════════════════════════════════════════════════════════
// TABLET — 768x1024, iPad portrait.
// ═══════════════════════════════════════════════════════════════════════════
test.describe('tablet smoke', () => {
  test.use({ viewport: { width: 768, height: 1024 } });

  // A ONE-PIXEL CLIFF AT EXACTLY 768. Two rules claimed the grid and their
  // bands touched instead of meeting:
  //     @media (min-width: 768px) and (max-width: 1023px)  -> 3 columns
  //     @media (max-width: 768px)  main.layout .listing-grid -> 2 columns
  // 768 satisfies both; the second is later and more specific, so it won and
  // the first never applied at the width it was written for. MEASURED:
  //     766 -> 2 cols   767 -> 2 cols   768 -> 2 cols   769 -> 3 cols
  test('iPad portrait gets the 3-column grid its own breakpoint declares', async ({ page }) => {
    await page.goto('/market');
    await expect(page.locator('.grid-card').first()).toBeVisible({ timeout: 15_000 });
    expect(await overflowPx(page)).toBeLessThanOrEqual(2);

    const cols = await page.evaluate(() => {
      const g = document.querySelector('.listing-grid');
      if (!g) return 0;
      return getComputedStyle(g).gridTemplateColumns.trim().split(/\s+/).length;
    });
    expect(cols, 'market grid columns at exactly 768px').toBe(3);
  });

  // `@media (max-width: 800px) { .toolbar > * { width: 100% } }` stacks every
  // toolbar control to the full row. Below ~720px something narrower already
  // supersedes it and the chips render at their natural size; nothing does in
  // the 721-800 band, so iPad portrait was the only width still showing them
  // as full-width grey bars. MEASURED .new-chip at four widths:
  //     375 -> 56px    768 -> 707px    900 -> 56px    1000 -> 56px
  test('tablet toolbar chips are chips, not full-width bars', async ({ page }) => {
    await page.goto('/market');
    await expect(page.locator('.grid-card').first()).toBeVisible({ timeout: 15_000 });

    const widths = await page.evaluate(() => {
      const w = (s) => {
        const e = document.querySelector(s);
        return e ? Math.round(e.getBoundingClientRect().width) : -1;
      };
      return { deals: w('.deals-chip'), fresh: w('.new-chip'), sort: w('.sort-picker') };
    });
    // -1 means the control is absent, which must not read as "narrow enough".
    expect(widths.deals, '.deals-chip present').toBeGreaterThan(0);
    expect(widths.fresh, '.new-chip present').toBeGreaterThan(0);
    expect(widths.sort, '.sort-picker present').toBeGreaterThan(0);

    expect(widths.deals, '% Deals chip width at 768px').toBeLessThanOrEqual(200);
    expect(widths.fresh, 'New chip width at 768px').toBeLessThanOrEqual(200);
    expect(widths.sort, 'sort picker width at 768px').toBeLessThanOrEqual(300);
  });

  test('tablet market keeps the screen width', async ({ page }) => {
    await page.goto('/market');
    await expect(page.locator('.grid-card').first()).toBeVisible({ timeout: 15_000 });
    const grid = await box(page, '.listing-grid');
    expect(grid, '.listing-grid should be present').not.toBeNull();
    // Before: 595px of 768 (77%). After: 707px (92%).
    // @ts-ignore  guarded above
    expect(grid.w, 'market grid width at 768px').toBeGreaterThanOrEqual(660);
  });
});
