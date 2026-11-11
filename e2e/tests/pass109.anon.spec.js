// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 109.

const ME = { id: 3, displayName: 'carol', role: 'USER', signedIn: true, sessionEpoch: 0, tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc' };
const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });
const slow = (body, ms = 800) => (r) => new Promise((ok) => setTimeout(ok, ms)).then(() => r.fulfill(json(body)));

async function signedIn(page) {
  await page.addInitScript(() => { window.confirm = () => true; });
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json(ME)));
}
async function asAdmin(page) {
  await signedIn(page);
  await page.route('**/api/admin/check', (r) => r.fulfill(json({ admin: true })));
}


// Defect pass 109.

// The real SkinBox app (dev login on, H2 demo data), the same server the suite runs against.
// Every /api call the page makes goes to it.
const JAR = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';
async function realApi(page, userId) {
  if (userId) await page.request.get(`${JAR}/api/auth/steam/dev-login?userId=${userId}&next=/`, { maxRedirects: 0 });
  await page.route('**/api/**', (r) => {
    const u = new URL(r.request().url());
    return r.continue({ url: JAR + u.pathname + u.search });
  });
}
async function prefs(page, extra) {
  await page.addInitScript((kv) => {
    try {
      localStorage.setItem('sb_cookie_consent', 'all');
      Object.entries(kv).forEach(([k, v]) => localStorage.setItem(k, v));
    } catch (_) {}
    window.confirm = () => true;
  }, extra || {});
}
// A pending offer of the signed-in user's own, made through the real API, so the
// test does not depend on (or use up) rows another run left behind.
async function makeRealOffer(page, listingId, amount, partner) {
  const csrf = async () => ((await page.context().cookies(JAR)).find(c => c.name === 'sbox_csrf') || {}).value;
  // Prime the CSRF cookie, then make sure the account has a trade URL (offers need one).
  await page.request.get(`${JAR}/api/auth/steam/me`);
  const headers = { 'Content-Type': 'application/json', 'X-CSRF-Token': await csrf() };
  await page.request.put(`${JAR}/api/profile/trade-url`, { headers,
    data: { tradeUrl: `https://steamcommunity.com/tradeoffer/new/?partner=${partner}&token=abcDEF12` } });
  const res = await page.request.post(`${JAR}/api/offers`, { headers, data: { listingId, amount } });
  const body = await res.json();
  expect(body.status, JSON.stringify(body)).toBe('PENDING');
  return body;
}
const scrollY = (page) => page.evaluate(() => (document.querySelector('.layout')?.scrollTop || 0) + window.scrollY);

test.describe('Settings: Reduce motion', () => {
  test('Back to top jumps straight to the top when Reduce motion is on in Settings (real app)', async ({ page }) => {
    await prefs(page, { sb_reduce_motion: '1' });
    await realApi(page, 0);
    await page.goto('/market');
    await expect(page.locator('html')).toHaveClass(/reduce-motion/);
    await expect(page.locator('.listing-grid, .listing-table').first()).toBeVisible();
    await page.mouse.wheel(0, 2400);
    const top = page.getByRole('button', { name: 'Back to top' });
    await expect(top).toBeVisible();
    await top.click();
    // An instant scroll is done when the click returns; a smooth one is still under way.
    expect(await scrollY(page)).toBe(0);
  });

  test('the item page "N listings" chip jumps to Active Listings when Reduce motion is on (real app)', async ({ page }) => {
    await prefs(page, { sb_reduce_motion: '1' });
    await realApi(page, 0);
    await page.goto('/item/12');
    const heading = page.getByRole('heading', { name: /^Active Listings \(\d+\)/ });
    await expect(heading).toBeAttached();
    const chip = page.getByRole('button', { name: /^\d+ listings?$/ });
    const before = await scrollY(page);
    await chip.click();
    expect(await scrollY(page)).toBeGreaterThan(before + 200);
    await expect(heading).toBeInViewport();
  });

  test('Help Center top question jumps to its answer when Reduce motion is on (real app)', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await prefs(page, { sb_reduce_motion: '1' });
    await realApi(page, 0);
    await page.goto('/help');
    const row = page.locator('.help-faq-preview-row').first();
    await row.scrollIntoViewIfNeeded();
    const before = await scrollY(page);
    await row.click();
    // The panel opens on the next frame and the scroll follows in that frame.
    await page.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))));
    const after = await scrollY(page);
    await page.waitForTimeout(800);
    expect(after).toBe(await scrollY(page));
    expect(after).not.toBe(before);
  });
});

test.describe('Settings: High contrast', () => {
  test('High contrast lifts the muted text and border colours across the site, not only on Settings (real app)', async ({ page }) => {
    await prefs(page, {});
    await realApi(page, 0);
    // The market's results line ("N items found · M listings") is ordinary secondary text.
    const meta = page.locator('.results-meta').first();
    const colourOf = () => meta.evaluate(el => getComputedStyle(el).color);
    const borderOf = () => page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--border').trim());
    await page.goto('/market');
    await expect(meta).toContainText(/listings?/);
    const offColour = await colourOf();
    const offBorder = await borderOf();
    await page.goto('/settings');
    const toggle = page.getByRole('switch', { name: 'High contrast' });
    await toggle.click();
    await expect(toggle).toHaveAttribute('aria-checked', 'true');
    await expect(page.locator('html')).toHaveClass(/high-contrast/);
    await page.getByRole('link', { name: 'Market', exact: true }).first().click();
    await expect(meta).toContainText(/listings?/);
    expect(await colourOf()).not.toBe(offColour);
    expect(await borderOf()).not.toBe(offBorder);
    // And it is still on after a reload.
    await page.reload();
    await expect(page.locator('html')).toHaveClass(/high-contrast/);
    await expect(meta).toContainText(/listings?/);
    expect(await colourOf()).not.toBe(offColour);
  });
});

test.describe('offers: Cancel keeps keyboard focus', () => {
  test('Offers page: Cancel on an outgoing offer leaves focus on the page, not <body> (real app)', async ({ page }) => {
    await prefs(page, {});
    await realApi(page, 1);
    const offer = await makeRealOffer(page, 83, 150, 4294967001);
    await page.goto('/offers/outgoing');
    const cancel = page.getByRole('button', { name: `Cancel offer on ${offer.itemName}` }).first();
    await cancel.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByText(/Cancelled your offer on/)).toBeVisible();
    await expect(cancel).toHaveCount(0);
    await expect.poll(() => page.evaluate(() => document.activeElement?.tagName)).not.toBe('BODY');
  });

  test('Profile → Offers: Cancel on an outgoing offer leaves focus on the page, not <body> (real app)', async ({ page }) => {
    await prefs(page, {});
    await realApi(page, 1);
    const offer = await makeRealOffer(page, 17, 9, 4294967001);
    await page.goto('/profile/offers?view=outgoing');
    const cancel = page.getByRole('button', { name: `Cancel offer on ${offer.itemName}` }).first();
    await cancel.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByText(/was cancelled/)).toBeVisible();
    await expect(cancel).toHaveCount(0);
    await expect.poll(() => page.evaluate(() => document.activeElement?.tagName)).not.toBe('BODY');
  });

  test('Offers page: Decline on a seller counter leaves focus on the page, not <body>', async ({ page }) => {
    await prefs(page, {});
    await signedIn(page);
    const row = (status) => ({ id: 41, listingId: 7, itemId: 3, itemName: 'Red Cap', amount: 4, askingPrice: 5, status,
      author: 'SELLER', sellerName: 'dave', buyerName: 'carol', createdAt: Date.now(), expiresAt: Date.now() + 86400000 });
    let cancelled = false;
    await page.route('**/api/offers/incoming', (r) => r.fulfill(json([])));
    await page.route('**/api/offers/outgoing', (r) => r.fulfill(json([row(cancelled ? 'CANCELLED' : 'PENDING')])));
    await page.route('**/api/offers/41', (r) => { cancelled = true; return r.fulfill(json({ id: 41, status: 'CANCELLED' })); });
    await page.goto('/offers/outgoing');
    const decline = page.getByRole('button', { name: 'Decline counter on Red Cap' });
    await decline.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByText(/Declined the seller.s counter/)).toBeVisible();
    await expect(decline).toHaveCount(0);
    await expect.poll(() => page.evaluate(() => document.activeElement?.tagName)).not.toBe('BODY');
  });
});

test.describe('buy-order Retry keeps keyboard focus', () => {
  test('Market Buy orders board: Retry after a failed load keeps focus on the page', async ({ page }) => {
    await prefs(page, {});
    await page.route('**/api/**', (r) => r.fulfill(json([])));
    await page.route('**/api/auth/steam/me', (r) => r.fulfill(json({ signedIn: false })));
    let calls = 0;
    const rows = [{ itemId: 5, itemName: 'Blue Hat', category: 'Hats', rarity: 'Standard', bestPrice: 3, orders: 2, quantity: 2, lowestListing: 4 }];
    await page.route('**/api/buy-orders/public**', (r) => (++calls === 1 ? r.fulfill(json({ code: 'ERR' }, 500)) : slow(rows, 600)(r)));
    await page.goto('/market?view=buyorders');
    const retry = page.getByRole('button', { name: 'Retry' });
    await retry.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByTestId('market-buy-order-row')).toHaveCount(1);
    await expect.poll(() => page.evaluate(() => document.activeElement?.tagName)).not.toBe('BODY');
  });

  test('Item page Buy offers: Retry after a failed book load keeps focus on the page (real app)', async ({ page }) => {
    await prefs(page, {});
    await realApi(page, 0);
    let calls = 0;
    await page.route('**/api/buy-orders/book/**', (r) => {
      if (++calls === 1) return r.fulfill(json({ code: 'ERR' }, 500));
      const u = new URL(r.request().url());
      return r.continue({ url: JAR + u.pathname + u.search });
    });
    await page.goto('/item/12');
    const block = page.getByTestId('buy-offers');
    const retry = block.getByRole('button', { name: 'Retry' });
    await retry.scrollIntoViewIfNeeded();
    await retry.focus();
    await page.keyboard.press('Enter');
    await expect(block.getByText(/no active buy orders|wanted/)).toBeVisible();
    await expect.poll(() => page.evaluate(() => document.activeElement?.tagName)).not.toBe('BODY');
  });
});
