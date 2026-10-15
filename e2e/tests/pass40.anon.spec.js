// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 40: watchlist, profile offers, sell grid, notifications.

const ME = {
  id: 1,
  steamId64: '76561199000000001',
  displayName: 'Pass Tester',
  signedIn: true,
  sessionEpoch: 0,
};

const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });

async function signedIn(page) {
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json(ME)));
}

async function anon(page) {
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json({ signedIn: false })));
}

function offer(id, listingId, amount) {
  return {
    id, listingId, itemName: 'Offer Hat ' + listingId, amount, askingPrice: 20, status: 'PENDING', author: 'BUYER',
    buyerName: 'Buyer' + id, createdAt: Date.now(), expiresAt: Date.now() + 86_400_000,
  };
}

test.describe('defect pass 40', () => {
  test('a watched item starred with nothing listed shows no made-up rise once it is listed', async ({ page }) => {
    await anon(page);
    await page.addInitScript(() => {
      localStorage.setItem('sb_watchlist', JSON.stringify([999101]));
      localStorage.setItem('sb_watchlist_snap', JSON.stringify({ 999101: 0 }));
    });
    const item = { id: 999101, name: 'Baseline Hat', lowestPrice: 5, rarity: 'Standard', category: 'Hats', isListed: true };
    await page.route('**/api/items/batch**', (r) => r.fulfill(json([item])));
    await page.route('**/api/listings?**', (r) => r.fulfill(json([
      { id: 71, price: 5, listingType: 'BUY_NOW', status: 'ACTIVE', sellerName: 's', item },
    ])));
    await page.goto('/watchlist');
    await expect(page.getByText('Baseline Hat').first()).toBeVisible({ timeout: 20_000 });
    await expect(page.locator('.watchlist-delta')).toHaveCount(0);
  });

  test('a "NO LISTINGS" watchlist card opens its item page', async ({ page }) => {
    await anon(page);
    await page.addInitScript(() => localStorage.setItem('sb_watchlist', JSON.stringify([999102])));
    const item = { id: 999102, name: 'Unlisted Hat', lowestPrice: 0, rarity: 'Standard', category: 'Hats', isListed: false };
    await page.route('**/api/items/batch**', (r) => r.fulfill(json([item])));
    await page.route('**/api/listings?**', (r) => r.fulfill(json([])));
    await page.goto('/watchlist');
    await expect(page.getByText('NO LISTINGS')).toBeVisible({ timeout: 20_000 });
    await page.getByText('Unlisted Hat').first().click();
    await expect(page).toHaveURL(/\/item\/999102$/);
  });

  test('competing offers on one listing count once, at the best offer', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/offers/incoming*', (r) => r.fulfill(json([offer(1, 9, 10), offer(2, 9, 12), offer(3, 9, 8)])));
    await page.route('**/api/offers/outgoing*', (r) => r.fulfill(json([])));
    await page.goto('/profile/offers');
    await expect(page.getByRole('region', { name: 'Incoming offers revenue summary' }))
      .toHaveText('$12.00 in pending offers across 1 listing', { timeout: 20_000 });
  });

  test('rejecting on the profile Offers tab updates the nav Offers badge at once', async ({ page }) => {
    await signedIn(page);
    let pending = 1;
    await page.route('**/api/offers/counts', (r) => r.fulfill(json({ incomingPending: pending, outgoingPending: 0 })));
    await page.route('**/api/offers/incoming*', (r) => r.fulfill(json(pending ? [offer(5, 9, 4)] : [])));
    await page.route('**/api/offers/outgoing*', (r) => r.fulfill(json([])));
    await page.route('**/api/offers/5/reject', (r) => { pending = 0; return r.fulfill(json({ id: 5, status: 'REJECTED' })); });
    page.on('dialog', (d) => d.accept());
    await page.goto('/profile/offers');
    const badge = page.locator('a.nav-icon-btn[href="/offers"]');
    await expect(badge).toHaveAttribute('aria-label', 'Offers (1 pending)', { timeout: 20_000 });
    await page.getByRole('button', { name: 'Reject offer' }).click();
    await expect(badge).toHaveAttribute('aria-label', 'Offers', { timeout: 5_000 });
  });

  test('Sell cards keep Quick Sell and the bulk box out of a button; the name is the button', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/steam/inventory*', (r) => r.fulfill(json({ count: 1, items: [
      { assetId: 'a2', name: 'Free Hat', catalogueId: 12, tradable: true, listableQuantity: 1, quantity: 1, suggestedPrice: 3 },
    ] })));
    await page.route('**/api/buy-orders/count/bulk*', (r) => r.fulfill(json({ 12: { count: 1, bestBid: 2 } })));
    await page.goto('/sell');
    const name = page.getByRole('button', { name: 'List Free Hat for sale' });
    await expect(name).toBeVisible({ timeout: 20_000 });
    await expect(page.locator('.inventory-item[role="button"]')).toHaveCount(0);
    await expect(page.locator('[role="button"] button, [role="button"] input')).toHaveCount(0);
    await name.focus();
    await page.keyboard.press('Enter');
    await expect(page.locator('.inventory-item')).toHaveCount(0);
  });

  test('unlabelled fields now have names: sell inventory search', async ({ page }) => {
    await signedIn(page);
    const items = Array.from({ length: 7 }, (_, i) => ({
      assetId: 'b' + i, name: 'Search Hat ' + i, catalogueId: 30 + i, tradable: true, listableQuantity: 1, quantity: 1,
    }));
    await page.route('**/api/steam/inventory*', (r) => r.fulfill(json({ count: 7, items })));
    await page.goto('/sell');
    await expect(page.getByRole('textbox', { name: 'Search your inventory' })).toBeVisible({ timeout: 20_000 });
  });

  test('the /notifications page re-reads after the bell marks rows read', async ({ page }) => {
    await signedIn(page);
    let read = false;
    let pageReads = 0;
    await page.route('**/api/notifications/unread-count*', (r) => r.fulfill(json({ unread: read ? 0 : 1 })));
    await page.route('**/api/notifications/read-all*', (r) => { read = true; return r.fulfill(json({ ok: true })); });
    await page.route('**/api/notifications/read*', (r) => { read = true; return r.fulfill(json({ ok: true })); });
    await page.route(/\/api\/notifications(\?.*)?$/, (r) => {
      pageReads += 1;
      return r.fulfill(json({ unread: read ? 0 : 1, items: [
        { id: 1, kind: 'SYSTEM', title: 'Bell row', body: 'x', read, createdAt: Date.now() },
      ] }));
    });
    await page.goto('/notifications');
    await expect(page.locator('button.offer-tab', { hasText: 'Unread' })).toContainText('1', { timeout: 20_000 });
    await page.getByRole('button', { name: 'Notifications' }).click();
    await expect.poll(() => read).toBe(true);
    await expect(page.locator('button.offer-tab', { hasText: 'Unread' })).not.toContainText('1');
  });
});
