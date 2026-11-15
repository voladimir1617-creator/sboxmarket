// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 127.

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


// Defect pass 127. Real-jar tests take the server from JAR / E2E_BASE_URL.
const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';

async function devLogin(page, userId) {
  await page.request.get(`${base}/api/auth/steam/dev-login?userId=${userId}&next=/`);
}
async function consent(page) {
  await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
}

const TRADE = { id: 41, state: 'VERIFIED', buyerUserId: ME.id, sellerUserId: 7, itemName: 'Cardboard Crown',
  counterpartyName: 'dave', price: 12.5, createdAt: Date.now() - 86400000 };

async function tradesWithReview(page, reviewReply) {
  await consent(page);
  await signedIn(page);
  await page.route('**/api/trades', (r) => r.fulfill({ ...json([TRADE]), headers: { 'X-Total-Count': '1' } }));
  await page.route('**/api/reviews', reviewReply);
  await page.goto('/profile/trades');
  await page.getByRole('button', { name: /Leave Review for Cardboard Crown/ }).click();
  return page.getByRole('dialog', { name: 'Leave a review' });
}

test.describe('Leave a review from a completed trade', () => {
  test('Submit review keeps focus while it sends and after a failed save', async ({ page }) => {
    const dlg = await tradesWithReview(page, (r) => slow({ code: 'REVIEW_WINDOW', message: 'Review window closed' }, 700)(r));
    await dlg.getByRole('textbox').fill('Fast and friendly');
    const submit = dlg.getByRole('button', { name: /Submit review|Submitting/ });
    await submit.click();
    await expect(dlg.getByRole('button', { name: /Submitting/ })).toBeFocused();
    await expect(dlg.getByRole('alert')).toHaveText(/Review window closed/);
    await expect(dlg.getByRole('button', { name: 'Submit review' })).toBeFocused();
  });

  test('a click outside the review form while it sends does not close it or lose the error', async ({ page }) => {
    const dlg = await tradesWithReview(page, (r) => slow({ code: 'REVIEW_WINDOW', message: 'Review window closed' }, 700)(r));
    await dlg.getByRole('textbox').fill('Fast and friendly');
    await dlg.getByRole('button', { name: 'Submit review' }).click();
    await page.locator('.modal-backdrop').filter({ has: dlg }).last().click({ position: { x: 5, y: 5 } });
    await expect(dlg.getByRole('alert')).toHaveText(/Review window closed/);
    await expect(dlg.getByRole('textbox')).toHaveValue('Fast and friendly');
  });
});

test.describe('Notifications page filters (real jar)', () => {
  const now = Date.now();
  const N = (id, kind, title, read, h, path) => ({ id, kind, title, body: 'About ' + title, read, createdAt: now - h * 3600e3, path });
  const ITEMS = [
    N(1, 'TRADE_OPENED', 'Trade opened', false, 1, '/profile/trades'),
    N(2, 'OFFER_RECEIVED', 'New offer on Wooden Mask', false, 2, '/offers'),
    N(3, 'AUCTION_OUTBID', 'You were outbid', true, 30, '/profile'),
    N(4, 'TRADE_VERIFIED', 'Trade complete', true, 50, '/profile/trades'),
    N(5, 'OFFER_ACCEPTED', 'Offer accepted', true, 70, '/offers'),
  ];
  async function openNotifications(page) {
    await consent(page);
    await devLogin(page, 2);
    await page.route('**/api/notifications**', (r) => (r.request().method() === 'GET'
      ? r.fulfill(json({ items: ITEMS, unread: 2 })) : r.fulfill(json({}))));
    await page.goto(`${base}/notifications`);
    await expect(page.getByRole('button', { name: /Open notification: Trade opened/ })).toBeVisible({ timeout: 20_000 });
  }

  test('the Unread and type filters are still on after opening a notification and pressing Back', async ({ page }) => {
    await openNotifications(page);
    const unread = page.getByRole('button', { name: /^Unread \d/ });
    const offers = page.getByRole('button', { name: /^Offers · \d/ });
    await unread.click();
    await offers.click();
    await page.getByRole('button', { name: /Open notification: New offer on Wooden Mask/ }).click();
    await expect(page).toHaveURL(/\/offers/);
    await page.goBack();
    await expect(page).toHaveURL(/\/notifications\?/);
    await expect(unread).toHaveAttribute('aria-pressed', 'true');
    await expect(offers).toHaveAttribute('aria-pressed', 'true');
    await expect(page.getByRole('button', { name: /Open notification: Trade opened/ })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /Open notification: New offer on Wooden Mask/ })).toBeVisible();
  });

  test('the two rows that both start with an All toggle are named groups', async ({ page }) => {
    await openNotifications(page);
    await expect(page.getByRole('group', { name: 'Read status' }).getByRole('button', { name: /^All/ })).toHaveCount(1);
    await expect(page.getByRole('group', { name: 'Notification type' }).getByRole('button', { name: /^All/ })).toHaveCount(1);
  });
});

test.describe('Item page price history (real jar)', () => {
  test('when the history fails to load, the range buttons say it is unavailable, not that history is short', async ({ page }) => {
    await consent(page);
    await page.route('**/api/items/1/history**', (r) => r.fulfill({ status: 500, contentType: 'application/json', body: '{}' }));
    await page.goto(`${base}/item/1`);
    const heading = page.getByRole('heading', { name: 'Price History' });
    await heading.scrollIntoViewIfNeeded({ timeout: 20_000 });
    await expect(page.getByText(/Price history could not be loaded/)).toBeVisible();
    for (const r of ['7D', '1M', '3M', '1Y']) {
      await expect(page.getByRole('button', { name: `${r} (price history unavailable)` })).toBeDisabled();
    }
    await expect(page.getByRole('button', { name: /insufficient history/ })).toHaveCount(0);
  });
});

test.describe('Help Center contact links (real jar)', () => {
  test('Contact Support is a link to /support that opens in-app', async ({ page }) => {
    await consent(page);
    await page.goto(`${base}/help`);
    const link = page.getByRole('link', { name: 'Contact Support' });
    await link.scrollIntoViewIfNeeded({ timeout: 20_000 });
    await expect(link).toHaveAttribute('href', '/support');
    await link.click();
    await expect(page).toHaveURL(/\/support$/);
    await page.goBack();
    await expect(page).toHaveURL(/\/help$/);
  });

  test('a ?q= search opens the answer with its contact address as a highlighted mailto link', async ({ page }) => {
    await consent(page);
    await page.goto(`${base}/help?q=appeal`);
    const region = page.getByRole('region', { name: /banned/i });
    await region.scrollIntoViewIfNeeded({ timeout: 20_000 });
    const mail = region.getByRole('link', { name: 'appeals@skinbox.market' });
    await expect(mail).toHaveAttribute('href', 'mailto:appeals@skinbox.market');
    await expect(mail.locator('mark')).toHaveText(/appeal/i);
  });
});

test.describe('Profile header avatar (real jar)', () => {
  test('without a loaded Steam photo the avatar reads as the user name on Profile and on the stall', async ({ page }) => {
    await consent(page);
    await page.route('https://avatars.fastly.steamstatic.com/**', (r) => r.abort());
    await devLogin(page, 2);
    await page.goto(`${base}/profile`);
    // Wait for the failed photo to give way to the initials stand-in.
    await expect(page.locator('.profile-avatar').getByText('AT', { exact: true })).toBeVisible({ timeout: 20_000 });
    await expect(page.locator('.profile-avatar').getByRole('img', { name: 'AtlasTrades' })).toBeVisible();
    await page.goto(`${base}/stall/2`);
    await expect(page.locator('.stall-avatar').getByText('AT', { exact: true })).toBeVisible({ timeout: 20_000 });
    await expect(page.locator('.stall-avatar').getByRole('img', { name: 'AtlasTrades' })).toBeVisible();
  });
});
