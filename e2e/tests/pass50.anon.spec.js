// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 50.

const ME = {
  id: 1,
  steamId64: '76561199000000001',
  displayName: 'Pass Tester',
  signedIn: true,
  sessionEpoch: 0,
  tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc',
};

const json = (body, status = 200, headers = {}) => ({ status, contentType: 'application/json', headers, body: JSON.stringify(body) });

async function signedIn(page) {
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json(ME)));
}

function trade(over) {
  const now = Date.now();
  return {
    id: 5001, listingId: 1, itemId: 1, itemName: 'Pass Hat', buyerUserId: 999, sellerUserId: 1,
    price: 4, feeAmount: 0.08, state: 'PENDING_SELLER_SEND', note: null, createdAt: now - 60_000,
    updatedAt: now - 60_000, settledAt: null, sentAt: null, expiresAt: now + 3 * 86_400_000,
    unreadCount: 0, lastMessage: null, counterpartyTradeUrl: null, counterpartyName: 'Bob',
    ...over,
  };
}

async function tradesPage(page, t, protection) {
  await page.route(/\/api\/trades(\?.*)?$/, (r) => r.fulfill(json([t], 200, { 'X-Total-Count': '1' })));
  await page.route(`**/api/trades/${t.id}/protection`, (r) => r.fulfill(json(protection)));
  await page.goto('/profile/trades');
}

test.describe('defect pass 50', () => {
  test('Enter in the price-alert box saves the alert', async ({ page }) => {
    await signedIn(page);
    const item = { id: 5050, name: 'Alert Hat', lowestPrice: 2, rarity: 'Standard', category: 'Hats', isListed: true };
    await page.route('**/api/items/5050', (r) => r.fulfill(json(item)));
    await page.route('**/api/listings/item/5050*', (r) => r.fulfill(json([
      { id: 5051, price: 2, listingType: 'BUY_NOW', status: 'ACTIVE', sellerUserId: 9, sellerName: 'Seller', item },
    ])));
    let posted = null;
    await page.route('**/api/watchlist/alerts', (r) => {
      if (r.request().method() !== 'POST') return r.fulfill(json([]));
      posted = r.request().postDataJSON();
      return r.fulfill(json({ id: 1, itemId: 5050, targetPrice: posted.targetPrice, status: 'ACTIVE' }));
    });
    await page.goto('/item/5050');
    await page.getByRole('button', { name: /Set Price Alert/ }).first().click({ timeout: 20_000 });
    const box = page.getByRole('spinbutton', { name: 'Price alert target (USD)' });
    await box.fill('1.25');
    await box.press('Enter');
    await expect.poll(() => posted).not.toBeNull();
    expect(posted.targetPrice).toBeCloseTo(1.25, 2);
  });

  test('a trade-offer URL over the 200-character server limit is not called valid', async ({ page }) => {
    await signedIn(page);
    await tradesPage(page, trade({}), { tradeId: 5001, protected: false, protection: null });
    await page.getByRole('button', { name: 'Mark Sent' }).click({ timeout: 20_000 });
    const input = page.getByRole('textbox', { name: 'Steam offer URL' });
    const long = 'https://steamcommunity.com/tradeoffer/' + '1'.repeat(220) + '/';
    await input.evaluate((el, v) => {
      const set = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
      set.call(el, v);
      el.dispatchEvent(new Event('input', { bubbles: true }));
    }, long);
    await expect(page.getByText('Looks valid. Submit to mark the trade sent')).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Mark sent', exact: true })).toBeDisabled();
  });

  test('a clawed-back protection claim no longer shows a green Trade Protected badge', async ({ page }) => {
    await signedIn(page);
    await tradesPage(page, trade({ id: 5002, buyerUserId: 1, sellerUserId: 999, state: 'VERIFIED', settledAt: Date.now() }), {
      tradeId: 5002, protected: true,
      protection: { id: 1, tradeId: 5002, status: 'EXPIRED', feeAmount: 0.25, coverageAmount: 4,
        claimReason: 'Claim reversed — trade released as valid', createdAt: Date.now(), resolvedAt: Date.now() },
    });
    const badge = page.locator('.trade-protection-badge').first();
    await expect(badge).toBeVisible({ timeout: 20_000 });
    await expect(badge).toContainText('Trade Protection reversed');
    await expect(badge).not.toContainText('Cover completed');
  });

  test('support tickets show readable categories', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/support/tickets', (r) => r.fulfill(json([
      { id: 5, subject: 'Broken page', category: 'BUG', status: 'OPEN', updatedAt: Date.now() },
    ])));
    await page.goto('/profile/support');
    const row = page.getByRole('button', { name: /Open ticket #5/ });
    await expect(row).toContainText('Bug report', { timeout: 20_000 });
    await expect(row).not.toContainText('BUG');
  });

  test('double-clicking Mark Resolved sends one request', async ({ page }) => {
    await signedIn(page);
    const ticket = { id: 6, subject: 'Help', category: 'OTHER', status: 'OPEN', updatedAt: Date.now() };
    await page.route('**/api/support/tickets', (r) => r.fulfill(json([ticket])));
    await page.route('**/api/support/tickets/6', (r) => r.fulfill(json({ ticket, messages: [] })));
    let resolves = 0;
    await page.route('**/api/support/tickets/6/resolve', async (r) => {
      resolves += 1;
      await new Promise((ok) => setTimeout(ok, 400));
      return r.fulfill(json({ ok: true }));
    });
    await page.goto('/profile/support');
    await page.getByRole('button', { name: /Open ticket #6/ }).click({ timeout: 20_000 });
    const btn = page.getByRole('button', { name: 'Mark Resolved' });
    await btn.evaluate((b) => { b.click(); b.click(); });
    await page.waitForTimeout(800);
    expect(resolves).toBe(1);
  });

  test('arrow keys move between notifications in the bell menu', async ({ page }) => {
    await signedIn(page);
    const now = Date.now();
    await page.route('**/api/notifications?*', (r) => r.fulfill(json({
      items: [
        { id: 1, kind: 'TRADE_SENT', title: 'First note', body: 'a', read: false, createdAt: now },
        { id: 2, kind: 'TRADE_SENT', title: 'Second note', body: 'b', read: false, createdAt: now - 1000 },
      ],
      unread: 2,
    })));
    await page.goto('/market');
    await page.getByRole('button', { name: /^Notifications/ }).first().click({ timeout: 20_000 });
    const items = page.locator('.notif-dropdown [role="menuitem"]');
    await expect(items.first()).toBeVisible();
    await items.first().focus();
    await page.keyboard.press('ArrowDown');
    await expect(items.nth(1)).toBeFocused();
    await page.keyboard.press('Home');
    await expect(items.first()).toBeFocused();
  });
});
