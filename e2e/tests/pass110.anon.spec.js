// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 110.

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


// Defect pass 110.

// The real SkinBox app (dev login on, H2 demo data), the same server the suite runs against.
// Every /api call the page makes goes to it unless a test routes one path itself.
const JAR = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';
async function realApi(page, userId) {
  if (userId) await page.request.get(`${JAR}/api/auth/steam/dev-login?userId=${userId}&next=/`, { maxRedirects: 0 });
  await page.route('**/api/**', (r) => {
    const u = new URL(r.request().url());
    return r.continue({ url: JAR + u.pathname + u.search });
  });
}
async function prefs(page) {
  await page.addInitScript(() => {
    try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {}
    window.confirm = () => true;
  });
}
// Steam inventory rows. The asset ids are not in anyone's Steam inventory, so the
// real server refuses to list them (NOT_IN_INVENTORY): the refusal these tests need.
const row = (id, name, rarity = 'Standard') => ({
  assetId: id, assetIds: [id], quantity: 1, name, type: 'Hat', iconUrl: null,
  tradable: true, marketable: true, category: 'Hats', rarity, catalogueId: 3,
  suggestedPrice: 6, steamPrice: null, listedCount: 0, listableQuantity: 1, unlistableReason: null,
});
// Listing needs a trade URL on the account, or the server refuses with TRADE_URL_MISSING
// before it ever looks at the inventory. Save the signed-in user's own (partner = their
// steamId64 - 76561197960265728) so these tests do not depend on another spec leaving one.
async function ownTradeUrl(page) {
  const me = await (await page.request.get(`${JAR}/api/auth/steam/me`)).json();
  const csrf = ((await page.context().cookies(JAR)).find(c => c.name === 'sbox_csrf') || {}).value;
  const partner = (BigInt(me.steamId64) - 76561197960265728n).toString();
  const res = await page.request.put(`${JAR}/api/profile/trade-url`, {
    headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrf },
    data: { tradeUrl: `https://steamcommunity.com/tradeoffer/new/?partner=${partner}&token=abcDEF12` },
  });
  expect(res.ok(), await res.text()).toBeTruthy();
}
async function inventory(page, rows) {
  await page.route('**/api/steam/inventory', (r) => r.fulfill(json({
    items: rows, count: rows.length, assetCount: rows.length, lastSyncedAt: Date.now(), steamId64: '7656',
  })));
}

test.describe('Sell: a refused listing keeps keyboard focus', () => {
  test('List for Sale keeps focus when the server refuses the listing (real app)', async ({ page }) => {
    await prefs(page);
    await realApi(page, 3);
    await ownTradeUrl(page);
    await inventory(page, [row('110001', 'Alpha Hat')]);
    await page.goto('/sell');
    await page.getByRole('button', { name: 'List Alpha Hat for sale' }).click();
    await page.getByRole('textbox', { name: 'Asking price' }).fill('7');
    const submit = page.getByRole('button', { name: 'List for Sale' });
    await submit.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('alert').filter({ hasText: /no longer in your Steam inventory/ })).toBeVisible();
    await expect(submit).toBeFocused();
  });

  test('the bulk List button keeps focus when every item is refused (real app)', async ({ page }) => {
    await prefs(page);
    await realApi(page, 3);
    await ownTradeUrl(page);
    await inventory(page, [row('110011', 'Alpha Hat'), row('110012', 'Beta Hat')]);
    await page.goto('/sell');
    for (const n of ['Alpha Hat', 'Beta Hat']) {
      await page.getByRole('checkbox', { name: `Select ${n} for bulk listing` }).check({ force: true });
    }
    await page.getByRole('textbox', { name: 'Bulk list price in USD' }).fill('5');
    const list = page.getByRole('button', { name: /^List 2 @/ });
    await list.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByText(/Listed 0 · 2 failed/).first()).toBeVisible();
    await expect(list).toBeFocused();
  });

  test('Quick Sell keeps focus when the server refuses it (real app)', async ({ page }) => {
    await prefs(page);
    await realApi(page, 3);
    await ownTradeUrl(page);
    await inventory(page, [row('110021', 'Alpha Hat')]);
    // A standing buy order for the row's catalogue item, so the row offers Quick Sell.
    await page.route('**/api/buy-orders/count/bulk*', (r) => r.fulfill(json({ 3: { count: 1, bestBid: 4.5 } })));
    await page.goto('/sell');
    const quick = page.getByRole('button', { name: /Quick Sell/ });
    await quick.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByText(/no longer in your Steam inventory/).first()).toBeVisible();
    await expect(quick).toBeFocused();
  });
});

test.describe('Sell: rarity filter labels', () => {
  test('the Sell page rarity chip says Scarce for Off-Market, like the market and stall (real app)', async ({ page }) => {
    await prefs(page);
    await realApi(page, 3);
    const rows = [row('110031', 'Alpha Hat', 'Off-Market')];
    for (let i = 0; i < 7; i++) rows.push(row(String(110040 + i), `Plain Hat ${i}`));
    await inventory(page, rows);
    await page.goto('/sell');
    await expect(page.getByRole('button', { name: /^Scarce · 1$/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /Off-Market ·/ })).toHaveCount(0);
  });
});

test.describe('header and toolbar pickers', () => {
  test('tabbing out of the currency picker closes it (real app)', async ({ page }) => {
    await prefs(page);
    await realApi(page, 0);
    await page.goto('/market');
    const chip = page.getByRole('button', { name: /Currency selector/ });
    await chip.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('listbox')).toBeVisible();
    // Through the eight currencies and on into the rest of the header.
    for (let i = 0; i < 9; i++) await page.keyboard.press('Tab');
    await expect(page.locator('.nav-picker-panel')).toHaveCount(0);
    await expect(chip).toHaveAttribute('aria-expanded', 'false');
  });

  test('Escape on the user menu returns focus to it, not to a currency picker left open (real app)', async ({ page }) => {
    await prefs(page);
    await realApi(page, 3);
    await page.goto('/market');
    const chip = page.getByRole('button', { name: /Currency selector/ });
    await chip.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('listbox')).toBeVisible();
    for (let i = 0; i < 9; i++) await page.keyboard.press('Tab');
    const menu = page.locator('[aria-label^="User menu"]');
    await menu.focus();
    await page.keyboard.press('Enter');
    await expect(menu).toHaveAttribute('aria-expanded', 'true');
    await page.keyboard.press('ArrowDown');
    await page.keyboard.press('Escape');
    await expect(menu).toHaveAttribute('aria-expanded', 'false');
    await expect(menu).toBeFocused();
  });

  test('tabbing out of the Sort menu closes it (real app)', async ({ page }) => {
    await prefs(page);
    await realApi(page, 0);
    await page.goto('/market');
    const sort = page.getByRole('button', { name: 'Sort listings' });
    await sort.focus();
    await page.keyboard.press('Enter');
    const panel = page.locator('.sort-picker-panel');
    await expect(panel).toBeVisible();
    const n = await panel.getByRole('option').count();
    for (let i = 0; i <= n; i++) await page.keyboard.press('Tab');
    await expect(page.getByRole('button', { name: /Save search/ })).toBeFocused();
    await expect(panel).toHaveCount(0);
    await expect(sort).toHaveAttribute('aria-expanded', 'false');
  });

  test('picking a sort with the keyboard still works and returns focus to the chip (real app)', async ({ page }) => {
    await prefs(page);
    await realApi(page, 0);
    await page.goto('/market');
    const sort = page.getByRole('button', { name: 'Sort listings' });
    await sort.focus();
    await page.keyboard.press('Enter');
    await page.keyboard.press('ArrowDown');
    await page.keyboard.press('ArrowDown');
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(/sort=price_asc/);
    await expect(sort).toBeFocused();
  });
});

test.describe('market listing-type toggle focus', () => {
  test('the selected listing-type button shows a focus ring (real app)', async ({ page }) => {
    await prefs(page);
    await realApi(page, 0);
    await page.goto('/market');
    const group = page.getByRole('group', { name: 'Listing type' });
    const all = group.getByRole('button', { name: 'All' });
    await expect(all).toHaveAttribute('aria-pressed', 'true');
    await page.mouse.move(0, 899);
    const before = await group.screenshot();
    // Keyboard focus: Tab onto it from the field before it.
    await group.getByRole('button', { name: 'Buy Now' }).focus();
    await page.keyboard.press('Shift+Tab');
    await expect(all).toBeFocused();
    expect(await all.evaluate((e) => e.matches(':focus-visible'))).toBe(true);
    const after = await group.screenshot();
    expect(after.equals(before)).toBe(false);
  });
});

test.describe('not-found pages', () => {
  test('a stall id that does not exist has "Stall not found" as its page heading (real app)', async ({ page }) => {
    await prefs(page);
    await realApi(page, 0);
    await page.goto('/stall/99999');
    await expect(page).toHaveTitle(/Stall not found/);
    const main = page.getByRole('main');
    await expect(main.getByRole('heading', { level: 1 })).toHaveText(/Stall not found/);
    await expect(main.getByRole('link', { name: 'Back to marketplace' })).toBeVisible();
  });
});
