// @ts-check
const { test, expect } = require('@playwright/test');

// Phone market cards carry a full-width "Add to cart" bar under the price,
// the way White.market, Waxpeer and CS.Deals show every card at 390px
// (look-learning pass 2). Before this, the card's cart action was a 32px "+"
// that only faded in on :hover, which a mouse-less phone browser never fires,
// so it read as missing. The bar is the same button, handler and styles; on
// desktop it stays the small "+" in the picture's top-right corner.

async function clearCart(page) {
  await page.goto('/');
  await page.evaluate(async () => {
    const csrf = (document.cookie.match(/sbox_csrf=([^;]+)/) || [])[1] || '';
    await fetch('/api/cart', { method: 'DELETE', credentials: 'same-origin', headers: { 'X-CSRF-Token': decodeURIComponent(csrf) } });
  });
}

// The first market card that has a cart button (auctions and the signed-in
// user's own listings have none), with the boxes the assertions need.
async function measureCard(page) {
  return page.evaluate(() => {
    const card = [...document.querySelectorAll('.grid-card')].find((c) => c.querySelector('.grid-cart-btn'));
    if (!card) return null;
    const r = (el) => { const q = el.getBoundingClientRect(); return { left: q.left, right: q.right, top: q.top, bottom: q.bottom, width: q.width, height: q.height }; };
    const btn = card.querySelector('.grid-cart-btn');
    const details = [...card.querySelectorAll('.grid-thumb, .grid-name, .grid-cat, .grid-price, .grid-steam-price, .grid-footer')];
    return {
      id: card.querySelector('.grid-card-link')?.getAttribute('href') || '',
      card: r(card),
      btn: r(btn),
      opacity: Number(getComputedStyle(btn).opacity),
      text: btn.textContent.trim(),
      details: details.map((d) => ({ cls: d.className, ...r(d) })),
    };
  });
}

const overlaps = (a, b) => a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom;

test.describe('phone market card', () => {
  test.use({ viewport: { width: 390, height: 844 } });

  test('shows a full-width Add to cart bar below every card detail, and it adds the listing', async ({ page }) => {
    await clearCart(page);
    await page.goto('/market');
    await expect(page.locator('.grid-card .grid-cart-btn').first()).toBeAttached({ timeout: 15_000 });

    const m = await measureCard(page);
    expect(m, 'a market card with a cart button').not.toBeNull();
    // @ts-ignore guarded above
    const { card, btn, opacity, text, details } = m;
    expect(opacity, 'bar is visible without hover').toBe(1);
    expect(text).toMatch(/Add to cart/);
    expect(btn.width, 'bar spans the card').toBeGreaterThanOrEqual(card.width - 24);
    expect(btn.height, 'bar is a comfortable tap target').toBeGreaterThanOrEqual(36);
    expect(btn.bottom, 'bar stays inside the card').toBeLessThanOrEqual(card.bottom);
    for (const d of details) {
      expect(overlaps(btn, d), `bar must not cover ${d.cls}`).toBe(false);
    }

    const bar = page.locator('.grid-card .grid-cart-btn').first();
    await bar.click();
    await expect(bar).toHaveText(/In cart/, { timeout: 8_000 });
    await expect(bar).toBeDisabled();

    await clearCart(page);
  });
});

test.describe('desktop market card', () => {
  test.use({ viewport: { width: 1440, height: 900 } });

  test('keeps the small cart button in the picture corner', async ({ page }) => {
    await page.goto('/market');
    await expect(page.locator('.grid-card .grid-cart-btn').first()).toBeAttached({ timeout: 15_000 });
    const m = await measureCard(page);
    expect(m, 'a market card with a cart button').not.toBeNull();
    // @ts-ignore guarded above
    const { btn, details } = m;
    const thumb = details.find((d) => /grid-thumb/.test(d.cls));
    expect(btn.width).toBeLessThanOrEqual(40);
    expect(btn.top).toBeGreaterThanOrEqual(thumb.top);
    expect(btn.bottom).toBeLessThanOrEqual(thumb.top + thumb.height / 2);
    expect(btn.right).toBeLessThanOrEqual(thumb.right);
  });
});
