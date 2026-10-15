const { test, expect } = require('@playwright/test');

async function clearCart(page) {
  await page.goto('/');
  await page.evaluate(async () => {
    const csrf = (document.cookie.match(/sbox_csrf=([^;]+)/) || [])[1] || '';
    await fetch('/api/cart', { method: 'DELETE', credentials: 'same-origin', headers: { 'X-CSRF-Token': decodeURIComponent(csrf) } });
  });
}

// Each cart row's "· by <seller>" line was 11px grey on the row's dark panel
// at 4.4:1, just under the 4.5:1 small text needs.
test('the cart row seller line is readable', async ({ page }) => {
  await clearCart(page);
  await page.goto('/item/4');
  const add = page.locator('.item-rail-actions-cart').first();
  await expect(add).toBeVisible({ timeout: 15_000 });
  await add.click();
  await expect(page.getByRole('button', { name: /In Cart/i }).first()).toBeVisible({ timeout: 8_000 });

  await page.goto('/cart');
  const seller = page.locator('.cart-row .cart-id > span').first();
  await expect(seller).toBeVisible({ timeout: 10_000 });
  const ratios = await seller.evaluate((el) => {
    const parse = (s) => s.match(/[\d.]+/g).map(Number);
    const lum = ([r, g, b]) => {
      const f = (x) => { x /= 255; return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4); };
      return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
    };
    let bgEl = el;
    while (bgEl && (parse(getComputedStyle(bgEl).backgroundColor)[3] ?? 1) < 0.9) bgEl = bgEl.parentElement;
    const bg = lum(parse(getComputedStyle(bgEl).backgroundColor));
    return [el, ...el.querySelectorAll('a, span')].map((n) => {
      const fg = lum(parse(getComputedStyle(n).color));
      return (Math.max(fg, bg) + 0.05) / (Math.min(fg, bg) + 0.05);
    });
  });
  for (const r of ratios) expect(r).toBeGreaterThanOrEqual(4.5);

  await clearCart(page);
});
