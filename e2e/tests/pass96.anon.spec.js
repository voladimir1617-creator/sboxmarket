// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 96.

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

const now = Date.now();
const trade = (o) => ({
  price: 4.23, feeAmount: 0.08, createdAt: now - 60000, updatedAt: now - 60000, unreadCount: 0,
  counterpartyTradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc',
  botManaged: false, ...o,
});
const SELLING = trade({ id: 41, itemId: 21, itemName: 'Police Hat', sellerUserId: ME.id, buyerUserId: 7, counterpartyName: 'dave', state: 'PENDING_SELLER_ACCEPT', expiresAt: now + 3 * 86400000 });
const SENDING = trade({ id: 42, itemId: 22, itemName: 'Chef Hat', sellerUserId: ME.id, buyerUserId: 8, counterpartyName: 'erin', state: 'PENDING_SELLER_SEND', expiresAt: now + 3 * 86400000 });
const BUYING = trade({ id: 43, itemId: 23, itemName: 'Denim Jeans', sellerUserId: 9, buyerUserId: ME.id, counterpartyName: 'frank', state: 'PENDING_BUYER_CONFIRM', sentAt: now - 30000, expiresAt: now + 8 * 86400000 });
const BOUGHT = trade({ id: 44, itemId: 24, itemName: 'Bag', sellerUserId: 9, buyerUserId: ME.id, counterpartyName: 'frank', state: 'VERIFIED', settledAt: now - 20000 });

async function tradesTab(page, rows) {
  await signedIn(page);
  let list = rows;
  await page.route('**/api/trades', (r) => r.fulfill(json(typeof list === 'function' ? list() : list)));
  await page.goto('/profile/trades');
  await page.locator('#trade-' + (Array.isArray(rows) ? rows[0].id : rows().at(0).id)).waitFor({ timeout: 20_000 });
}

test.describe('pass 96: trade rows', () => {
  test('each trade row\'s buttons say which trade they act on', async ({ page }) => {
    await tradesTab(page, [SELLING, SENDING, BUYING, BOUGHT]);
    await expect(page.getByRole('button', { name: /^Accept.*Police Hat/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /^Mark Sent.*Chef Hat/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /^Confirm receipt.*Denim Jeans/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /^Leave Review.*Bag/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /^Chat.*Police Hat/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /^Report.*Chef Hat/ })).toBeVisible();
  });

  test('the buyer\'s button reads Confirm receipt, as the bell and email tell him to click', async ({ page }) => {
    await tradesTab(page, [BUYING]);
    // The row's own Inbox tip, the TRADE_SENT bell and the offer-sent email
    // all say: click "Confirm Receipt".
    await expect(page.locator('#trade-43 a[title*="click Confirm Receipt"]')).toHaveCount(1);
    await expect(page.locator('#trade-43 .trade-actions .buy-btn')).toHaveText(/^Confirm receipt$/i);
  });

  test('Accept hands focus to the row\'s Mark Sent button', async ({ page }) => {
    let accepted = false;
    await tradesTab(page, () => [accepted ? { ...SELLING, state: 'PENDING_SELLER_SEND' } : SELLING]);
    await page.route('**/api/trades/41/accept', (r) => { accepted = true; return slow({ id: 41, state: 'PENDING_SELLER_SEND' }, 300)(r); });
    await page.locator('#trade-41 .trade-actions .buy-btn').click();
    const markSent = page.locator('#trade-41 .trade-actions .buy-btn', { hasText: /^Mark Sent$/ });
    await expect(markSent).toBeVisible();
    await expect(markSent).toBeFocused();
  });

  test('marking a trade sent leaves focus on that trade, not the page', async ({ page }) => {
    let sent = false;
    await tradesTab(page, () => [sent ? { ...SENDING, state: 'PENDING_BUYER_CONFIRM', sentAt: Date.now() } : SENDING]);
    await page.route('**/api/trades/42/sent', (r) => { sent = true; return slow({ id: 42, state: 'PENDING_BUYER_CONFIRM' }, 300)(r); });
    await page.locator('#trade-42 .trade-actions .buy-btn').click();
    await page.getByRole('dialog', { name: 'Mark trade as sent' }).getByRole('button', { name: 'Skip for now' }).click();
    await expect(page.locator('#trade-42 .trade-state')).toContainText(/Awaiting buyer confirm/);
    await expect.poll(() => page.evaluate(() => document.getElementById('trade-42').contains(document.activeElement))).toBe(true);
  });
});

test.describe('pass 96: bells', () => {
  test('a bell row\'s name carries its body and does not promise a new page', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/notifications?*', (r) => r.fulfill(json({ items: [
      { id: 14, kind: 'TRADE_SENT', title: 'Steam trade offer sent · Sports Bandage', body: 'Confirm the trade on Steam and then click Confirm Receipt.', refId: 2, path: '/profile?tab=trades', read: false, createdAt: now - 60000 },
      { id: 16, kind: 'TRADE_VERIFIED', title: 'Funds released · Sports Bandage', body: '$1.08 credited to your wallet.', refId: 2, path: '/wallet', read: true, createdAt: now - 30000 },
    ], unread: 1 })));
    await page.goto('/market');
    await page.getByRole('button', { name: /^Notifications/ }).first().click({ timeout: 20_000 });
    await expect(page.getByRole('menuitem', { name: /Steam trade offer sent · Sports Bandage.*click Confirm Receipt/ })).toBeVisible();
    await expect(page.getByRole('menuitem', { name: /Funds released.*\$1\.08 credited/ })).toBeVisible();
    await expect(page.getByRole('menuitem', { name: /Opens in new page/ })).toHaveCount(0);
  });
});

test.describe('pass 96: first-time seller', () => {
  test('the email reminder says why Resend did not send', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/auth/steam/me', (r) => r.fulfill(json({ ...ME, email: 'carol@example.com', emailVerified: false })));
    await page.route('**/api/profile/email/resend', (r) => r.fulfill(json({ code: 'RESEND_COOLDOWN', message: 'Verification email already sent — wait 59s before requesting another.' }, 400)));
    await page.goto('/market');
    await page.getByRole('button', { name: 'Resend email' }).click({ timeout: 20_000 });
    await expect(page.getByRole('button', { name: /Failed/ })).toBeVisible();
    await expect(page.getByText(/Verification email already sent .* wait 59s/)).toBeVisible();
  });

  test('a failed Steam inventory read on the Sell page is announced', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/steam/inventory', (r) => r.fulfill(json({ error: 'boom' }, 500)));
    await page.route('**/api/listings/inventory*', (r) => r.fulfill(json([])));
    await page.goto('/sell');
    await expect(page.getByText(/Couldn.t load your Steam inventory/)).toBeVisible({ timeout: 20_000 });
    await expect(page.getByRole('alert').filter({ hasText: /Couldn.t load your Steam inventory/ })).toBeVisible();
  });
});
