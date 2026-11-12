// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 113.

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


const JAR = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';
// Static copy under test (original or fixed); real-jar tests ignore it.
const STATIC = process.env.BASE_URL;

async function prep(page) {
  await page.addInitScript(() => {
    try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {}
    window.confirm = () => true;
  });
  await page.route(/^https?:\/\/(?!localhost|127\.0\.0\.1)/, (r) => r.abort());
}
async function devLogin(page, userId) {
  await page.request.get(`${JAR}/api/auth/steam/dev-login?userId=${userId}&next=/`);
}
// The session ends just as the user saves: the save answers the server's 401
// body. (Dropping the cookie instead races the page's own polls, which
// sign the page out a moment earlier.)
const EXPIRED = json({ code: 'UNAUTHORIZED', message: 'Sign in required' }, 401);
async function expireOn(page, url) {
  await page.route(url, (r) => (r.request().method() === 'POST' ? r.fulfill(EXPIRED) : r.continue()));
}
// Serve the static copy under test for the page itself while /api goes to the
// real jar: only used when a check needs the fixed JS against real data.
async function useStaticAssets(page) {
  if (!STATIC) return;
  await page.route(`${JAR}/**`, async (r) => {
    const u = new URL(r.request().url());
    if (u.pathname.startsWith('/api/')) return r.continue();
    const res = await page.request.fetch(STATIC + u.pathname + u.search);
    await r.fulfill({ response: res });
  });
}

test.describe('Support ticket thread (real jar)', () => {
  test('a reply sent on an expired session is still typed after signing back in', async ({ page }) => {
    await prep(page);
    await useStaticAssets(page);
    await devLogin(page, 4);
    await page.goto(`${JAR}/support`);
    const subject = 'Pass113 reply keep ' + Date.now();
    const created = await page.evaluate(async (subject) => {
      const t = (document.cookie.match(/sbox_csrf=([^;]+)/) || [])[1] || '';
      const r = await fetch('/api/support/tickets', { method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': t },
        body: JSON.stringify({ subject, category: 'OTHER', body: 'First message' }) });
      return r.json();
    }, subject);
    expect(created.id).toBeTruthy();
    await page.reload();
    await page.getByRole('button', { name: new RegExp(`Open ticket #${created.id}`) }).click();
    const box = page.getByRole('textbox', { name: 'Reply to support' });
    await box.fill('The trade offer never arrived, here is the id 12345');
    await expireOn(page, `${JAR}/api/support/tickets/${created.id}/reply`);
    await page.getByRole('button', { name: 'Send', exact: true }).click();
    await expect(page.getByRole('heading', { name: /Sign in required/ })).toBeVisible();
    await page.goto('about:blank');
    await devLogin(page, 4);
    await page.goto(`${JAR}/support`);
    await expect(page.getByRole('textbox', { name: 'Reply to support' })).toHaveValue(/never arrived, here is the id 12345/);
    await expect(page.getByRole('heading', { name: new RegExp(`#${created.id} · `) })).toBeVisible();
  });
});

const REVIEW = { id: 9101, rating: 4, comment: 'Fast trade, friendly seller', fromUserId: 3, fromDisplayName: 'PixelPusher',
  toUserId: 1, itemName: 'Wizard Hat', createdAt: Date.now() - 3600_000, helpfulCount: 0 };

test.describe('Stall reviews on an expired session (real jar)', () => {
  test('a review submitted on an expired session is still typed after signing back in', async ({ page }) => {
    await prep(page);
    await useStaticAssets(page);
    // Seed data has no verified trades; give user 3 one with seller 1.
    await page.route(`${JAR}/api/reviews/eligible/1`, (r) => r.fulfill(json([
      { tradeId: 777, itemName: 'Wizard Hat', price: 13.16, settledAt: Date.now() - 86_400_000, reviewed: false }])));
    await devLogin(page, 3);
    await page.goto(`${JAR}/stall/1`);
    await page.getByRole('button', { name: 'Write review' }).click();
    await page.getByRole('button', { name: '3 stars' }).click();
    await page.getByRole('textbox', { name: /Review comment/ }).fill('Item arrived late but the seller kept me posted');
    await expireOn(page, `${JAR}/api/reviews`);
    await page.getByRole('button', { name: 'Submit review' }).click();
    await expect(page.getByRole('button', { name: 'Write review' })).toHaveCount(0);
    await page.goto('about:blank');
    await devLogin(page, 3);
    await page.goto(`${JAR}/stall/1`);
    await expect(page.getByRole('textbox', { name: /Review comment/ })).toHaveValue(/arrived late but the seller kept me posted/);
    await expect(page.getByRole('button', { name: '3 stars' })).toHaveAttribute('aria-pressed', 'true');
  });

  test('a seller reply posted on an expired session is still typed after signing back in', async ({ page }) => {
    await prep(page);
    await useStaticAssets(page);
    await page.route(`${JAR}/api/reviews/user/1`, (r) => r.fulfill(json([REVIEW])));
    await devLogin(page, 1);
    await page.goto(`${JAR}/stall/1`);
    await page.getByRole('button', { name: /Reply/ }).first().click();
    await page.getByRole('textbox', { name: 'Public reply to this review' }).fill('Thanks, sorry about the wait');
    await expireOn(page, `${JAR}/api/reviews/${REVIEW.id}/reply`);
    await page.getByRole('button', { name: 'Post reply' }).click();
    await expect(page.getByRole('textbox', { name: 'Public reply to this review' })).toHaveCount(0);
    await page.goto('about:blank');
    await devLogin(page, 1);
    await page.goto(`${JAR}/stall/1`);
    await expect(page.getByRole('textbox', { name: 'Public reply to this review' })).toHaveValue(/sorry about the wait/);
  });

  test('a review report filed on an expired session is still typed after signing back in', async ({ page }) => {
    await prep(page);
    await useStaticAssets(page);
    await page.route(`${JAR}/api/reviews/user/1`, (r) => r.fulfill(json([REVIEW])));
    await devLogin(page, 5);
    await page.goto(`${JAR}/stall/1`);
    await page.getByRole('button', { name: 'Report review by PixelPusher' }).click();
    await page.getByRole('dialog').getByRole('textbox').fill('This review contains a phone number');
    await expireOn(page, `${JAR}/api/support/tickets`);
    await page.getByRole('dialog').getByRole('button', { name: 'File report' }).click();
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await page.goto('about:blank');
    await devLogin(page, 5);
    await page.goto(`${JAR}/stall/1`);
    await expect(page.getByRole('dialog').getByRole('textbox')).toHaveValue(/contains a phone number/);
  });
});

test.describe('Refused submits keep keyboard focus (real jar)', () => {
  test('Report seller: a refused File report keeps focus on the button', async ({ page }) => {
    await prep(page);
    await useStaticAssets(page);
    await page.route(`${JAR}/api/support/report-user/1`, (r) => r.fulfill(json({ code: 'RATE_LIMITED', message: 'Report desk is busy' }, 400)));
    await devLogin(page, 3);
    await page.goto(`${JAR}/stall/1`);
    await page.getByRole('button', { name: 'Report', exact: true }).click();
    const dlg = page.getByRole('dialog', { name: 'Report seller' });
    await dlg.getByRole('textbox').fill('Asked me to trade outside the site');
    const file = dlg.getByRole('button', { name: /File report|Submitting/ });
    await file.focus();
    await page.keyboard.press('Enter');
    await expect(dlg.getByRole('alert')).toHaveText(/Report desk is busy/);
    await expect(file).toBeFocused();
  });

  test('Stall review: a refused Submit review keeps focus on the button', async ({ page }) => {
    await prep(page);
    await useStaticAssets(page);
    await page.route(`${JAR}/api/reviews/eligible/1`, (r) => r.fulfill(json([
      { tradeId: 777, itemName: 'Wizard Hat', price: 13.16, settledAt: Date.now() - 86_400_000, reviewed: false }])));
    await devLogin(page, 3);
    await page.goto(`${JAR}/stall/1`);
    await page.getByRole('button', { name: 'Write review' }).click();
    const submit = page.getByRole('button', { name: /Submit review|Sending/ });
    await submit.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByText(/trade|review/i).filter({ hasText: /not|already|cannot|could/i }).first()).toBeVisible();
    await expect(submit).toBeFocused();
  });
  async function newTicket(page, resolved) {
    return page.evaluate(async ({ resolved }) => {
      const t = (document.cookie.match(/sbox_csrf=([^;]+)/) || [])[1] || '';
      const post = (u, b) => fetch(u, { method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': t }, body: JSON.stringify(b || {}) }).then((r) => r.json());
      const tk = await post('/api/support/tickets', { subject: 'Pass113 focus ' + Date.now(), category: 'OTHER', body: 'Hello' });
      if (resolved) await post(`/api/support/tickets/${tk.id}/resolve`);
      return tk;
    }, { resolved });
  }

  test('Support thread: a refused Mark Resolved keeps focus on the button', async ({ page }) => {
    await prep(page);
    await useStaticAssets(page);
    await devLogin(page, 6);
    await page.goto(`${JAR}/support`);
    const tk = await newTicket(page, false);
    await page.route(`${JAR}/api/support/tickets/${tk.id}/resolve`, (r) => r.fulfill(json({ code: 'CONFLICT', message: 'Ticket changed, reload' }, 409)));
    await page.reload();
    await page.getByRole('button', { name: new RegExp(`Open ticket #${tk.id}`) }).click();
    const btn = page.getByRole('button', { name: 'Mark Resolved' });
    await btn.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByText(/Ticket changed, reload/).first()).toBeVisible();
    await expect(btn).toBeFocused();
  });

  test('Support thread: a refused Reopen keeps focus on the button', async ({ page }) => {
    await prep(page);
    await useStaticAssets(page);
    await devLogin(page, 6);
    await page.goto(`${JAR}/support`);
    const tk = await newTicket(page, true);
    await page.route(`${JAR}/api/support/tickets/${tk.id}/reopen`, (r) => r.fulfill(json({ code: 'CONFLICT', message: 'Reopen window passed' }, 409)));
    await page.goto(`${JAR}/support?ticket=${tk.id}`);
    const btn = page.getByRole('button', { name: /Reopen/ });
    await btn.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByText(/Reopen window passed/).first()).toBeVisible();
    await expect(btn).toBeFocused();
  });

  test('Item page: a refused Submit report keeps focus on the button', async ({ page }) => {
    await prep(page);
    await useStaticAssets(page);
    await devLogin(page, 6);
    const listings = await (await page.request.get(`${JAR}/api/listings?size=30`)).json();
    const l = listings.find((x) => x.sellerUserId && x.sellerUserId !== 6 && x.listingType !== 'AUCTION');
    await page.route(`${JAR}/api/listings/${l.id}/report`, (r) => r.fulfill(json({ code: 'BAD', message: 'Report service down' }, 400)));
    await page.goto(`${JAR}/item/${l.item.id}`);
    await page.getByRole('button', { name: `Report listing ${l.id}` }).click();
    const dlg = page.getByRole('dialog', { name: 'Report listing' });
    const btn = dlg.getByRole('button', { name: /Submit report|Submitting/ });
    await btn.focus();
    await page.keyboard.press('Enter');
    await expect(dlg.getByRole('alert')).toHaveText(/Report service down/);
    await expect(btn).toBeFocused();
  });

  test('Stall review: a refused Post reply keeps focus on the button', async ({ page }) => {
    await prep(page);
    await useStaticAssets(page);
    await page.route(`${JAR}/api/reviews/user/1`, (r) => r.fulfill(json([REVIEW])));
    await page.route(`${JAR}/api/reviews/${REVIEW.id}/reply`, (r) => r.fulfill(json({ code: 'BAD', message: 'Reply refused' }, 400)));
    await devLogin(page, 1);
    await page.goto(`${JAR}/stall/1`);
    await page.getByRole('button', { name: /Reply/ }).first().click();
    await page.getByRole('textbox', { name: 'Public reply to this review' }).fill('Thanks');
    const btn = page.getByRole('button', { name: /Post reply|Saving/ });
    await btn.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('alert').filter({ hasText: /Reply refused/ })).toBeVisible();
    await expect(btn).toBeFocused();
  });

  test('Stall review: a refused Delete my review keeps focus on the button', async ({ page }) => {
    await prep(page);
    await useStaticAssets(page);
    await page.route(`${JAR}/api/reviews/user/1`, (r) => r.fulfill(json([{ ...REVIEW, mine: true }])));
    await page.route(`${JAR}/api/reviews/${REVIEW.id}`, (r) => r.request().method() === 'DELETE'
      ? r.fulfill(json({ code: 'BAD', message: 'Delete refused' }, 400)) : r.continue());
    await devLogin(page, 3);
    await page.goto(`${JAR}/stall/1`);
    const btn = page.getByRole('button', { name: /Delete my review|Deleting/ });
    await btn.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('alert').filter({ hasText: /Delete refused/ })).toBeVisible();
    await expect(btn).toBeFocused();
  });
});

test.describe('Report a user from Profile > Trades (stubbed)', () => {
  const TRADE = { id: 71, state: 'PENDING_SELLER_SEND', buyerUserId: ME.id, sellerUserId: 9, itemName: 'Black Watch', price: 10,
    counterpartyName: 'bob', createdAt: Date.now(), updatedAt: Date.now() };
  test('a refused counterparty File report keeps focus on the button', async ({ page }) => {
    await prep(page);
    await signedIn(page);
    await page.route('**/api/support/report-user/9', (r) => r.fulfill(json({ error: 'Report desk unavailable' }, 500)));
    await page.route('**/api/trades', (r) => r.fulfill(json([TRADE])));
    await page.goto('/profile/trades');
    await page.getByRole('button', { name: /^Report bob on / }).click();
    const dlg = page.getByRole('dialog', { name: 'Report counterparty' });
    await dlg.getByRole('textbox').fill('Never sent the item');
    const btn = dlg.getByRole('button', { name: /File report|Submitting/ });
    await btn.focus();
    await page.keyboard.press('Enter');
    await expect(dlg.getByRole('alert').filter({ hasText: /Report desk unavailable/ })).toBeVisible();
    await expect(btn).toBeFocused();
  });
});

test.describe('Reports on an expired session', () => {
  test('stall Report seller (real jar): the report is still typed after signing back in', async ({ page }) => {
    await prep(page);
    await useStaticAssets(page);
    await devLogin(page, 3);
    await page.goto(`${JAR}/stall/1`);
    await page.getByRole('button', { name: 'Report', exact: true }).click();
    const dlg = page.getByRole('dialog', { name: 'Report seller' });
    await dlg.getByRole('combobox', { name: 'Report reason' }).selectOption('Impersonation');
    await dlg.getByRole('textbox').fill('Uses the name of a staff member in chat');
    await expireOn(page, `${JAR}/api/support/report-user/1`);
    await dlg.getByRole('button', { name: 'File report' }).click();
    await expect(dlg.getByRole('alert')).toBeVisible();
    await page.goto('about:blank');
    await devLogin(page, 3);
    await page.goto(`${JAR}/stall/1`);
    await expect(dlg.getByRole('textbox')).toHaveValue(/name of a staff member/);
    await expect(dlg.getByRole('combobox', { name: 'Report reason' })).toHaveValue('Impersonation');
  });

  test('Profile > Trades Report counterparty (stubbed): the report is still typed after signing back in', async ({ page }) => {
    const TRADE = { id: 71, state: 'PENDING_SELLER_SEND', buyerUserId: ME.id, sellerUserId: 9, itemName: 'Black Watch', price: 10,
      counterpartyName: 'bob', createdAt: Date.now(), updatedAt: Date.now() };
    await prep(page);
    await signedIn(page);
    await page.route('**/api/support/report-user/9', (r) => r.fulfill(EXPIRED));
    await page.route('**/api/trades', (r) => r.fulfill(json([TRADE])));
    await page.goto('/profile/trades');
    await page.getByRole('button', { name: /^Report bob on / }).click();
    const dlg = page.getByRole('dialog', { name: 'Report counterparty' });
    await dlg.getByRole('textbox').fill('Asked me to pay outside the site');
    await dlg.getByRole('button', { name: 'File report' }).click();
    await expect(page.getByRole('heading', { name: /Sign in required/ })).toBeVisible();
    await page.goto('/profile/trades');
    await expect(dlg.getByRole('textbox')).toHaveValue(/pay outside the site/);
  });
  test('item page Report listing (real jar): the report is still typed after signing back in', async ({ page }) => {
    await prep(page);
    await useStaticAssets(page);
    await devLogin(page, 6);
    const listings = await (await page.request.get(`${JAR}/api/listings?size=30`)).json();
    const l = listings.find((x) => x.sellerUserId && x.sellerUserId !== 6 && x.listingType !== 'AUCTION');
    await page.goto(`${JAR}/item/${l.item.id}`);
    await page.getByRole('button', { name: `Report listing ${l.id}` }).click();
    const dlg = page.getByRole('dialog', { name: 'Report listing' });
    await dlg.getByRole('textbox').fill('Same photo as another seller');
    await expireOn(page, `${JAR}/api/listings/${l.id}/report`);
    await dlg.getByRole('button', { name: 'Submit report' }).click();
    await expect(dlg.getByRole('alert')).toBeVisible();
    await page.goto('about:blank');
    await devLogin(page, 6);
    await page.goto(`${JAR}/item/${l.item.id}`);
    await expect(dlg.getByRole('textbox')).toHaveValue(/Same photo as another seller/);
  });
});

test.describe('Item price history with no sales (stubbed)', () => {
  const ITEM = { id: 1, name: 'Short Parted Hair', category: 'Hats', rarity: 'Standard', iconEmoji: 'H', accentColor: '#7a8b9c',
    supply: 3, totalSold: 0, lowestPrice: 0.54, steamPrice: null, trendPercent: 0, isListed: true };
  test('an item with no sales offers no range that cannot draw, and ALL is the one shown', async ({ page }) => {
    await prep(page);
    await signedIn(page);
    await page.route('**/api/items/1', (r) => r.fulfill(json(ITEM)));
    await page.route('**/api/items/1/history**', (r) => r.fulfill(json([])));
    await page.route('**/api/listings/item/1**', (r) => r.fulfill(json([])));
    await page.goto('/item/1');
    const all = page.getByRole('button', { name: /ALL/ });
    await all.scrollIntoViewIfNeeded();
    await expect(page.getByText(/Not enough sales in this range to chart yet/)).toBeVisible();
    await expect(all).toHaveAttribute('aria-pressed', 'true');
    for (const r of ['7D', '1M', '3M', '1Y']) {
      await expect(page.getByRole('button', { name: `${r} (insufficient history)` })).toBeDisabled();
    }
  });
});
