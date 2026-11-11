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

// ── Item page: Buy offers book ───────────────────────────────────────────
test.describe('item page buy offers', () => {
  test('open orders show as price levels with quantity and order count, no buyer', async ({ page }) => {
    await page.route('**/api/buy-orders/book/12**', (r) => r.fulfill({ json: [
      { price: 0.5, orders: 2, quantity: 3 },
      { price: 0.46, orders: 1, quantity: 3 },
    ] }));
    await page.goto('/item/12');
    const block = page.getByTestId('buy-offers');
    await expect(block.getByTestId('buy-offers-book')).toBeVisible({ timeout: 15_000 });
    await expect(block).toContainText('3 orders · 6 wanted · buyers not shown');
    const rows = block.locator('tbody tr');
    await expect(rows).toHaveCount(2);
    await expect(rows.nth(0).locator('td')).toHaveText(['$0.50', '3', '2']);
    await expect(rows.nth(1).locator('td')).toHaveText(['$0.46', '3', '1']);
    // A visitor gets the action behind sign-in, not a dead end.
    await expect(block.getByRole('button', { name: 'Sign in to place a buy order' })).toBeVisible();
  });

  test('the real book endpoint answers anonymously with aggregate fields only', async ({ request }) => {
    const r = await request.get('/api/buy-orders/book/12');
    expect(r.status()).toBe(200);
    const rows = await r.json();
    expect(Array.isArray(rows)).toBe(true);
    for (const l of rows) expect(Object.keys(l).sort()).toEqual(['orders', 'price', 'quantity']);
  });

  test('an item with no orders says so, with the action next to it', async ({ page }) => {
    await page.route('**/api/buy-orders/book/12**', (r) => r.fulfill({ json: [] }));
    await page.goto('/item/12');
    const empty = page.getByTestId('buy-orders-empty');
    await expect(empty).toContainText('There are no active buy orders for this item.', { timeout: 15_000 });
    await expect(empty.getByRole('button', { name: 'Sign in to place one' })).toBeVisible();
  });

  test('a failed book load offers Retry instead of claiming there are none', async ({ page }) => {
    await page.route('**/api/buy-orders/book/12**', (r) => r.fulfill({ status: 500, body: '{}' }));
    await page.goto('/item/12');
    await expect(page.getByTestId('buy-offers')).toContainText("Couldn't load buy orders.", { timeout: 15_000 });
    await expect(page.getByTestId('buy-orders-empty')).toHaveCount(0);
  });
});

// ── Market: public Buy orders board ──────────────────────────────────────
test.describe('market buy orders board', () => {
  const BOARD = [
    { itemId: 12, itemName: 'Vintage Design Crop Top', category: 'Shirts', rarity: 'Standard', bestPrice: 0.5, orders: 3, quantity: 6, lowestListing: 0.6 },
    { itemId: 13, itemName: 'Unlisted Hat', category: 'Hats', rarity: 'Limited', bestPrice: 0.2, orders: 1, quantity: 1, lowestListing: null },
  ];

  test('the Buy orders chip shows what buyers pay per item, keeps it in the URL, and goes back', async ({ page }) => {
    await page.route('**/api/buy-orders/public**', (r) => r.fulfill({ json: BOARD }));
    await page.goto('/market');
    await page.getByRole('button', { name: 'Buy orders', exact: true }).click({ timeout: 15_000 });
    const board = page.getByTestId('market-buy-orders');
    await expect(board).toContainText('2 items buyers want');
    await expect(board).toContainText('Buyers are never named.');
    const rows = board.getByTestId('market-buy-order-row');
    await expect(rows).toHaveCount(2);
    await expect(rows.nth(0)).toContainText('Vintage Design Crop Top');
    await expect(rows.nth(0)).toContainText('$0.50');
    await expect(rows.nth(1)).toContainText('None listed');
    await expect(page).toHaveURL(/view=buyorders/);
    // Picking a listing type returns to the grid.
    await page.getByRole('group', { name: 'Listing type' }).getByRole('button', { name: 'All', exact: true }).click();
    await expect(board).toHaveCount(0);
    await expect(page).not.toHaveURL(/view=buyorders/);
  });

  test('a shared link opens the board, and the search box narrows it', async ({ page }) => {
    await page.route('**/api/buy-orders/public**', (r) => r.fulfill({ json: BOARD }));
    await page.goto('/market?view=buyorders&q=hat');
    const rows = page.getByTestId('market-buy-order-row');
    await expect(rows).toHaveCount(1, { timeout: 15_000 });
    await expect(rows.first()).toContainText('Unlisted Hat');
    await rows.first().getByRole('link', { name: 'Unlisted Hat' }).click();
    await expect(page).toHaveURL(/\/item\/13$/);
  });

  test('no orders and a failed load are told apart', async ({ page }) => {
    let fail = true;
    await page.route('**/api/buy-orders/public**', (r) => fail ? r.fulfill({ status: 500, body: '{}' }) : r.fulfill({ json: [] }));
    await page.goto('/market?view=buyorders');
    await expect(page.getByText("Couldn't load buy orders")).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId('market-buy-orders-empty')).toHaveCount(0);
    fail = false;
    await page.getByRole('button', { name: 'Retry' }).click();
    await expect(page.getByTestId('market-buy-orders-empty')).toContainText('No open buy orders yet');
    await expect(page.getByRole('button', { name: 'Sign in to place one' })).toBeVisible();
  });

  test('the real board endpoint answers anonymously and names no buyer', async ({ request }) => {
    const r = await request.get('/api/buy-orders/public');
    expect(r.status()).toBe(200);
    for (const row of await r.json()) {
      expect(Object.keys(row).sort()).toEqual(['accentColor', 'bestPrice', 'category', 'iconEmoji', 'itemId', 'itemImageUrl', 'itemName', 'lowestListing', 'orders', 'quantity', 'rarity']);
    }
  });
});

// ── Anonymous shop ───────────────────────────────────────────────────────
// Real API: a seller turns it on, a visitor's item page shows the neutral
// label with no stall link, the seller's stall says why it lists nothing.
test.describe('anonymous shop', () => {
  test('a seller with Anonymous shop on is not named on their listings', async ({ page, playwright, baseURL }) => {
    const anonCtx = await playwright.request.newContext({ baseURL });
    const rows = await (await anonCtx.get('/api/listings')).json();
    const target = rows.find((l) => l.sellerUserId && l.item && l.listingType !== 'AUCTION');
    test.skip(!target, 'no seller listing in the demo data');
    const sellerName = target.sellerName;

    const seller = await playwright.request.newContext({ baseURL });
    await seller.get(`/api/auth/steam/dev-login?userId=${target.sellerUserId}&next=/`, { maxRedirects: 0 });
    const csrf = (await seller.storageState()).cookies.find((c) => c.name === 'sbox_csrf')?.value || '';
    const put = (enabled) => seller.put('/api/profile/anonymous-shop', { headers: { 'X-CSRF-Token': csrf }, data: { enabled } });
    expect((await (await put(true)).json()).anonymousShop).toBe(true);
    try {
      const masked = (await (await anonCtx.get(`/api/listings/item/${target.item.id}`)).json()).find((l) => l.id === target.id);
      expect(masked).toMatchObject({ sellerName: 'Anonymous seller', sellerUserId: 0, sellerAnonymous: true, sellerAvatar: null });

      await page.goto(`/item/${target.item.id}`);
      const list = page.locator('.modal-listings');
      await expect(list.getByText('Anonymous seller')).toBeVisible({ timeout: 15_000 });
      await expect(list.locator(`a[href="/stall/${target.sellerUserId}"]`)).toHaveCount(0);

      await page.goto(`/stall/${target.sellerUserId}`);
      await expect(page.getByTestId('stall-anonymous')).toContainText('This seller lists anonymously', { timeout: 15_000 });
      // Their own view still names them.
      const own = (await (await seller.get(`/api/listings/item/${target.item.id}`)).json()).find((l) => l.id === target.id);
      expect(own.sellerName).toBe(sellerName);
    } finally {
      await put(false);
    }
    const back = (await (await anonCtx.get(`/api/listings/item/${target.item.id}`)).json()).find((l) => l.id === target.id);
    expect(back.sellerName).toBe(sellerName);
  });
});

// ── Public API reference ─────────────────────────────────────────────────
test.describe('api reference page', () => {
  test('the page lists scopes, routes and error codes, and fits a phone', async ({ page }) => {
    for (const path of ['/api-reference.html', '/api-reference']) {
      await page.goto(path);
      await expect(page.getByRole('heading', { level: 1, name: 'API Reference' })).toBeVisible();
    }
    await expect(page.locator('tr[data-route="GET /api/listings"]')).toContainText('RO');
    await expect(page.locator('tr[data-route="POST /api/listings/{id}/buy"]')).toContainText('RW');
    for (const code of ['INVALID_API_KEY', 'RO_KEY_WRITE_FORBIDDEN', 'API_KEY_FORBIDDEN', 'RATE_LIMITED']) {
      await expect(page.locator(`tr[data-error$=" ${code}"]`)).toBeVisible();
    }
    await expect(page.getByText('Requests are rate limited.', { exact: true })).toBeVisible();
    await page.setViewportSize({ width: 390, height: 800 });
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    expect(overflow).toBeLessThanOrEqual(0);
  });

  test('an unknown key gets the documented 401 code', async ({ request }) => {
    const r = await request.get('/api/wallet', { headers: { Authorization: 'Bearer sbx_live_not_a_real_key' } });
    expect(r.status()).toBe(401);
    expect((await r.json()).code).toBe('INVALID_API_KEY');
  });

  test('Profile → Developers links to the reference', async ({ page, playwright, baseURL }) => {
    const ctx = await playwright.request.newContext({ baseURL });
    await ctx.get('/api/auth/steam/dev-login?userId=5&next=/', { maxRedirects: 0 });
    await page.context().addCookies((await ctx.storageState()).cookies);
    await page.goto('/profile/developers');
    const link = page.getByTestId('api-reference-link');
    await expect(link).toBeVisible({ timeout: 15_000 });
    await link.click();
    await expect(page).toHaveURL(/\/api-reference\.html$/);
    await expect(page.getByRole('heading', { level: 1, name: 'API Reference' })).toBeVisible();
  });
});
