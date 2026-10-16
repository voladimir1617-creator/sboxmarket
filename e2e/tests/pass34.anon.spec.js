// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 34: the item page hides its bottom action bar, so the price
// alert button (and the FAQ's "open an item and click Set Price Alert")
// could not be reached anywhere on the page.

const ME = {
  id: 1,
  steamId64: '76561199000000001',
  displayName: 'Alert Tester',
  signedIn: true,
  sessionEpoch: 0,
};

async function signedIn(page) {
  await page.route('**/api/**', (r) => r.fulfill({
    status: 200, contentType: 'application/json', body: '{}',
  }));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify(ME),
  }));
  // Real item data from the seeded server.
  await page.route('**/api/items/**', (r) => r.continue());
}

test.describe('defect pass 34', () => {
  test('the item page offers a price alert, and it opens the alert editor', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings/**', (r) => r.continue());
    await page.route('**/api/watchlist/alerts', (r) => r.fulfill({
      status: 200, contentType: 'application/json', body: '[]',
    }));
    await page.goto('/item/4');
    const btn = page.getByRole('button', { name: /Set Price Alert/ });
    await expect(btn).toBeVisible({ timeout: 20_000 });
    await btn.click();
    await expect(page.getByText('Notify me when the floor drops to…')).toBeVisible();
    await expect(page.locator('.item-alert-panel input').first()).toBeFocused();
  });

  test('a sold-out item keeps a price alert as a price alert, not a $100,000 restock', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings/item/*', (r) => r.fulfill({
      status: 200, contentType: 'application/json', body: '[]',
    }));
    await page.route('**/api/watchlist/alerts', (r) => r.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify([{ id: 501, itemId: 4, targetPrice: 5, status: 'ACTIVE', createdAt: Date.now() }]),
    }));
    let posted = null;
    page.on('request', (req) => {
      if (req.method() === 'POST' && req.url().includes('/api/watchlist/alerts')) posted = req.url();
    });
    page.on('dialog', (d) => d.accept());

    await page.goto('/item/4');
    const btn = page.getByRole('button', { name: /Alert at .*5\.00 · Edit/ });
    await expect(btn).toBeVisible({ timeout: 20_000 });
    await expect(page.getByRole('button', { name: /Restock Alert/ })).toHaveCount(0);
    await btn.click();
    // Opens the editor seeded with the saved target instead of replacing it.
    await expect(page.locator('.item-alert-panel input').first()).toHaveValue('5.00');
    expect(posted).toBeNull();
  });
});

const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });

test.describe('defect pass 34: stall, trades, profile, CSR', () => {
  test('the stall Block button follows the blocked banner', async ({ page }) => {
    await signedIn(page);
    let blocked = true;
    await page.route('**/api/listings/stall/77*', (r) => r.fulfill(json({
      seller: { id: 77, displayName: 'Blocky' }, listings: [], blockedByViewer: blocked,
    })));
    await page.route('**/api/profile/blocks/77', (r) => { blocked = false; return r.fulfill(json({ ok: true })); });

    await page.goto('/stall/77');
    await expect(page.getByText("You've blocked this seller")).toBeVisible({ timeout: 20_000 });
    await expect(page.getByRole('button', { name: 'Blocked', exact: true })).toBeVisible();
    await page.getByRole('button', { name: 'Unblock', exact: true }).click();
    await expect(page.getByText("You've blocked this seller")).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Block', exact: true })).toBeVisible();
  });

  test('a stall whose reviews failed to load does not claim it has none', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings/stall/78*', (r) => r.fulfill(json({
      seller: { id: 78, displayName: 'Rated', reviewCount: 12, avgRating: 4.8 }, listings: [], blockedByViewer: false,
    })));
    await page.route('**/api/reviews/user/78*', (r) => r.fulfill(json({ error: 'boom' }, 500)));
    await page.goto('/stall/78');
    await expect(page.getByRole('heading', { name: /Rated/ }).first()).toBeVisible({ timeout: 20_000 });
    await page.waitForTimeout(1000);
    await expect(page.getByText('No reviews yet')).toHaveCount(0);
  });

  test('a reviewed trade offers Edit Review with the saved review, and Open lists disputes', async ({ page }) => {
    await signedIn(page);
    const base = { buyerUserId: 1, sellerUserId: 2, counterpartyName: 'Seller', price: 5, createdAt: Date.now() };
    await page.route('**/api/trades', (r) => r.fulfill(json([
      { ...base, id: 5, state: 'VERIFIED', itemName: 'Reviewed Hat' },
      { ...base, id: 6, state: 'DISPUTED', itemName: 'Disputed Hat' },
      { ...base, id: 7, state: 'PENDING_SELLER_SEND', itemName: 'Pending Hat' },
    ])));
    await page.route('**/api/reviews/mine*', (r) => r.fulfill(json([
      { id: 1, tradeId: 5, rating: 1, comment: 'Took a week' },
    ])));

    await page.goto('/profile/trades');
    const edit = page.getByRole('button', { name: 'Edit Review' });
    await expect(edit).toBeVisible({ timeout: 20_000 });
    await expect(page.getByRole('button', { name: 'Leave Review' })).toHaveCount(0);
    await edit.click();
    await expect(page.locator('textarea').filter({ hasText: 'Took a week' })).toBeVisible();
    await page.keyboard.press('Escape');

    await page.getByRole('button', { name: 'open', exact: true }).click();
    await expect(page.getByText('Disputed Hat').first()).toBeVisible();
    await expect(page.getByText('Pending Hat').first()).toBeVisible();
  });

  test('Profile can open and undo every block, not just the first ten', async ({ page }) => {
    await signedIn(page);
    const items = Array.from({ length: 12 }, (_, i) => ({ blockedUserId: 500 + i, displayName: `Blocked ${i}` }));
    await page.route('**/api/profile/blocks*', (r) => r.fulfill(json({ count: items.length, items })));
    await page.goto('/profile');
    const unblocks = page.getByRole('button', { name: /^Unblock Blocked \d+$/ });
    await expect(unblocks.first()).toBeVisible({ timeout: 20_000 });
    await expect(unblocks).toHaveCount(10);
    await page.getByTestId('blocked-more').click();
    await expect(unblocks).toHaveCount(12);
  });

  test('a CSR flag sends the picked reason together with the note', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/csr/check', (r) => r.fulfill(json({ csr: true })));
    let sent = null;
    await page.route('**/api/csr/listings/42/flag', (r) => {
      sent = r.request().postDataJSON();
      return r.fulfill(json({ id: 42 }));
    });
    await page.goto('/csr');
    await page.getByRole('tab', { name: 'Flag Listing' }).click();
    await page.getByPlaceholder('Listing ID').fill('42');
    await page.getByRole('combobox', { name: 'Flag reason' }).selectOption('Suspicious pricing');
    await page.getByRole('textbox', { name: 'Additional note' }).fill('half the floor');
    await expect(page.getByRole('combobox', { name: 'Flag reason' })).toHaveValue('Suspicious pricing');
    await page.getByRole('button', { name: /Flag listing/ }).click();
    await expect.poll(() => sent).not.toBeNull();
    expect(sent.reason).toBe('Suspicious pricing — half the floor');
  });
});
