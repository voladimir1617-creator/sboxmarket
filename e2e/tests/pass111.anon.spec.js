// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 111.

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

// Pass 111. Real-jar checks sign in through dev-login against JAR; the
// stubbed ones run on the static copy at baseURL.

const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';

async function consent(page) {
  await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
}
async function devLogin(page, userId) {
  await page.request.get(`${base}/api/auth/steam/dev-login?userId=${userId}&next=/`);
}
async function csrf(ctx) {
  const c = (await ctx.cookies(base)).find((x) => x.name === 'sbox_csrf');
  return c ? c.value : '';
}
const look = (loc) => loc.evaluate((e) => getComputedStyle(e).boxShadow + '|' + getComputedStyle(e).outlineStyle);
// The tab's look without and with keyboard focus (after its 120ms transition).
async function ringDelta(page, loc) {
  await loc.evaluate((e) => e.blur());
  await page.waitForTimeout(300);
  const before = await look(loc);
  await loc.focus();
  await page.waitForTimeout(300);
  return { fv: await loc.evaluate((e) => e.matches(':focus-visible')), before, after: await look(loc) };
}

const now = Date.now();
const trade = (o) => ({
  id: 41, state: 'PENDING_SELLER_SEND', itemName: 'Red Beanie', itemId: 12, buyerUserId: 9, sellerUserId: 3,
  price: 4.5, createdAt: now - 3600e3, counterpartyName: 'dave', counterpartyUserId: 9,
  counterpartyTradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=2&token=x', ...o,
});

test.describe('pass111 tab focus rings (real jar)', () => {
  test('the selected Profile tab shows a focus ring under keyboard focus', async ({ page }) => {
    await consent(page);
    await devLogin(page, 1);
    await page.goto(`${base}/profile/personal`);
    const tab = page.getByRole('tab', { name: /Personal Info/ });
    await expect(tab).toHaveAttribute('aria-selected', 'true');
    const r = await ringDelta(page, tab);
    expect(r.fv).toBe(true);
    expect(r.after).not.toBe(r.before);
    expect(r.after).toMatch(/0px 0px 0px 2px/);
    // Arrow keys move the selection and focus together; the new tab is ringed too.
    await page.keyboard.press('ArrowRight');
    const next = page.getByRole('tab', { name: /^Listings/ });
    await expect(next).toBeFocused();
    await page.waitForTimeout(300);
    expect(await look(next)).toMatch(/0px 0px 0px 2px/);
  });
});

test.describe('pass111 tab focus rings (stubbed)', () => {
  test('the selected CSR section tab shows a focus ring under keyboard focus', async ({ page }) => {
    await consent(page);
    await signedIn(page);
    await page.route('**/api/auth/steam/me', (r) => r.fulfill(json({ ...ME, role: 'CSR' })));
    await page.route('**/api/csr/check', (r) => r.fulfill(json({ csr: true })));
    await page.goto('/csr');
    const tab = page.getByRole('tablist', { name: 'CSR sections' }).getByRole('tab', { selected: true });
    await expect(tab).toBeVisible();
    const r = await ringDelta(page, tab);
    expect(r.fv).toBe(true);
    expect(r.after).toMatch(/0px 0px 0px 2px/);
  });
});

test.describe('pass111 loadout editor (real jar)', () => {
  test('picking an item for a slot by keyboard keeps focus in that slot', async ({ page }) => {
    await consent(page);
    await devLogin(page, 2);
    const tok = await csrf(page.context());
    const made = await page.request.post(`${base}/api/loadouts`, {
      headers: { 'X-CSRF-Token': tok, 'Content-Type': 'application/json' },
      data: { name: 'Pass111 focus set', visibility: 'PRIVATE' },
    });
    expect(made.ok()).toBe(true);
    const { id } = await made.json();
    try {
      await page.goto(`${base}/loadout/${id}`);
      const add = page.getByRole('button', { name: 'Add an item to the Hats slot' });
      await add.focus();
      await page.keyboard.press('Enter');
      await expect(page.getByRole('textbox', { name: 'Filter Hats items' })).toBeFocused();
      await page.keyboard.press('Tab');
      await expect(page.locator('.loadout-picker-item').first()).toBeFocused();
      await page.keyboard.press('Enter');
      const clear = page.getByRole('button', { name: 'Clear Hats slot' });
      await expect(clear).toBeVisible();
      await expect(clear).toBeFocused();
      // And the pick is saved.
      await page.reload();
      await expect(page.getByRole('button', { name: 'Clear Hats slot' })).toBeVisible();
    } finally {
      await page.request.delete(`${base}/api/loadouts/${id}`, { headers: { 'X-CSRF-Token': await csrf(page.context()) } });
    }
  });
});

test.describe('pass111 notifications page (real jar)', () => {
  test('deleting the last notification keeps focus on the page', async ({ page, playwright }) => {
    await consent(page);
    await devLogin(page, 4);
    const tok = await csrf(page.context());
    // Start from an empty feed, then one fresh "New follower" row from user 5.
    const feed = await (await page.request.get(`${base}/api/notifications`)).json();
    for (const n of feed.items || []) {
      await page.request.delete(`${base}/api/notifications/${n.id}`, { headers: { 'X-CSRF-Token': tok } });
    }
    const other = await playwright.request.newContext();
    await other.get(`${base}/api/auth/steam/dev-login?userId=5&next=/`);
    const otok = ((await other.storageState()).cookies.find((c) => c.name === 'sbox_csrf') || {}).value || '';
    await other.post(`${base}/api/follows/4`, { headers: { 'X-CSRF-Token': otok } });
    await other.delete(`${base}/api/follows/4`, { headers: { 'X-CSRF-Token': otok } });
    await other.dispose();

    await page.goto(`${base}/notifications`);
    const del = page.getByRole('button', { name: /^Delete this notification: New follower/ });
    await expect(del).toHaveCount(1);
    await del.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('heading', { name: /Quiet so far/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /^All \d/ })).toBeFocused();
  });
});

test.describe('pass111 trades tab (stubbed)', () => {
  test.beforeEach(async ({ page }) => {
    await consent(page);
    await signedIn(page);
    await page.route('**/api/trades', (r) => r.fulfill(json([
      trade({}),
      trade({ id: 42, state: 'VERIFIED', buyerUserId: 3, sellerUserId: 9, itemName: 'Blue Hat', settledAt: now }),
    ])));
  });

  test('Leave Review moves keyboard focus into the review dialog and Escape returns it', async ({ page }) => {
    await page.goto('/profile/trades');
    const open = page.getByRole('button', { name: /Leave Review for Blue Hat \(trade #42\)/ });
    await open.focus();
    await page.keyboard.press('Enter');
    const dialog = page.getByRole('dialog', { name: /Leave a review/ });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByRole('radio', { name: '5 stars' })).toBeFocused();
    await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0);
    await expect(open).toBeFocused();
  });

  test('each trade row names its copy buttons after that trade', async ({ page }) => {
    await page.goto('/profile/trades');
    await expect(page.getByRole('button', { name: 'Copy trade #41', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Copy trade #42', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Copy trade ID', exact: true })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /^Copy trade URL for Red Beanie \(trade #41\)$/ })).toBeVisible();
  });
});
