// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 118.

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


// Defect pass 118: Offers rows for items with no picture, header search
// suggestion names on a phone, the phone tab bar Watchlist count, the My
// Stall Analytics table, and focus after My Stall "Cancel all".

const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';

async function consent(page) {
  await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
}
async function devLogin(page, id) {
  await page.request.get(`${base}/api/auth/steam/dev-login?userId=${id}&next=/`);
}
async function csrfHeaders(page) {
  const c = (await page.context().cookies(base)).find((x) => x.name === 'sbox_csrf');
  return { 'Content-Type': 'application/json', 'X-CSRF-Token': c ? c.value : '' };
}

test.describe('pass118 · Offers rows (real jar)', () => {
  test('Offers: an offer on an item with no picture shows the item glyph and names the thumbnail link after the item', async ({ page }) => {
    await consent(page);
    await devLogin(page, 3);
    const headers = await csrfHeaders(page);
    const me = await (await page.request.get(`${base}/api/auth/steam/me`)).json();
    const all = await (await page.request.get(`${base}/api/listings?size=200`)).json();
    const list = Array.isArray(all) ? all : (all.content || []);
    const pick = list.find((l) => l.item && !l.item.imageUrl && l.listingType === 'BUY_NOW' && l.sellerUserId !== me.id);
    expect(pick, 'a listing of an item with no picture').toBeTruthy();
    const out = await (await page.request.get(`${base}/api/offers/outgoing`)).json();
    if (!out.some((o) => o.status === 'PENDING' && o.itemId === pick.item.id)) {
      const res = await page.request.post(`${base}/api/offers`, { headers,
        data: { listingId: pick.id, amount: Math.max(0.01, Math.floor(parseFloat(pick.price) * 80) / 100) } });
      expect((await res.json()).status).toBe('PENDING');
    }
    await page.goto(`${base}/offers/outgoing`);
    const thumb = page.locator(`.offer-row a.item-thumb[href="/item/${pick.item.id}"]`).first();
    await expect(thumb).toBeVisible({ timeout: 20_000 });
    await expect(thumb).toHaveAccessibleName(pick.item.name);
    await expect(thumb.locator('.item-poster')).toBeVisible();
    await expect(thumb).not.toHaveText('—');
    // Profile > Offers draws the same row thumbnail.
    await page.goto(`${base}/profile/offers?view=outgoing`);
    const row = page.locator('.offer-row', { hasText: pick.item.name }).first();
    await expect(row).toBeVisible({ timeout: 20_000 });
    await expect(row.locator('.item-thumb .item-poster')).toBeVisible();
    await expect(row.locator('.item-thumb')).not.toHaveText('—');
  });
});

test.describe('pass118 · Phone header search (real jar)', () => {
  test.use({ viewport: { width: 390, height: 844 } });
  test('Search suggestions on a phone read each item name once', async ({ page }) => {
    await consent(page);
    await page.goto(`${base}/market`);
    const box = page.getByRole('combobox', { name: 'Search listings' });
    await box.fill('hat', { timeout: 20_000 });
    const list = page.getByRole('listbox', { name: 'Search suggestions' });
    const opt = list.getByRole('option', { name: /Wizard Hat/ });
    await expect(opt).toBeVisible();
    await box.press('ArrowDown');
    await expect(box).toHaveAttribute('aria-activedescendant', /search-opt-/);
    await expect(opt).toHaveAccessibleName(/^Wizard Hat Hats/);
    // An item with no picture had its category glyph read first.
    await box.fill('denim');
    const denim = list.getByRole('option', { name: /Denim Jeans/ });
    await expect(denim).toBeVisible();
    await expect(denim).toHaveAccessibleName(/^Denim Jeans Pants/);
    await box.press('Escape');
    await expect(list).toBeHidden();
    await expect(box).toBeFocused();
  });

  test('Search suggestions on a phone: arrowing to the last row scrolls it fully into the list', async ({ page }) => {
    await consent(page);
    await page.goto(`${base}/market`);
    const box = page.getByRole('combobox', { name: 'Search listings' });
    await box.fill('ar', { timeout: 20_000 });
    const list = page.getByRole('listbox', { name: 'Search suggestions' });
    await expect(list.getByRole('option')).toHaveCount(8);
    for (let i = 0; i < 8; i++) await box.press('ArrowDown');
    await expect(box).toHaveAttribute('aria-activedescendant', 'search-opt-7');
    await expect.poll(() => page.evaluate(() => {
      const o = document.getElementById('search-opt-7').getBoundingClientRect();
      const l = document.getElementById('search-suggest-listbox').getBoundingClientRect();
      return o.top >= l.top - 1 && o.bottom <= l.bottom + 1;
    })).toBe(true);
  });
});

test.describe('pass118 · Phone tab bar (real jar)', () => {
  test.use({ viewport: { width: 390, height: 844 } });
  test('Phone tab bar: on Offers the Profile tab is marked current, not as the page being shown', async ({ page }) => {
    await consent(page);
    await devLogin(page, 4);
    await page.goto(`${base}/offers`);
    await expect(page.getByRole('heading', { level: 1, name: /Offers/ })).toBeVisible({ timeout: 20_000 });
    const profile = page.getByRole('navigation', { name: 'Mobile navigation' }).getByRole('link', { name: 'Profile', exact: true });
    await expect(profile).toHaveClass(/is-active/);
    await expect(profile).toHaveAttribute('aria-current', 'true');
    await profile.click();
    await expect(page).toHaveURL(/\/profile$/);
    await expect(profile).toHaveAttribute('aria-current', 'page');
  });
});

test.describe('pass118 · My Stall Analytics (real jar)', () => {
  test('My Stall Analytics: the per-listing figures are a table with column headers', async ({ page }) => {
    await consent(page);
    await devLogin(page, 1);
    await page.goto(`${base}/me/stall/analytics`);
    const table = page.getByRole('table', { name: 'Per-listing analytics' });
    await expect(table).toBeVisible({ timeout: 20_000 });
    for (const col of ['Item', 'My Price', 'vs Floor', 'Views', '30d Sold']) {
      await expect(table.getByRole('columnheader', { name: col, exact: true })).toBeVisible();
    }
    const rows = table.getByRole('row');
    expect(await rows.count()).toBeGreaterThan(1);
    await expect(rows.nth(1).getByRole('cell')).toHaveCount(5);
  });
});

test.describe('pass118 · Cancel all focus on My Stall, Profile Offers and Buy Orders (stubbed)', () => {
  test('My Stall: after Cancel all empties the stall, focus lands on the Active tab, not the page', async ({ page }) => {
    await consent(page);
    await signedIn(page);
    let gone = false;
    const L = (id, name) => ({ id, price: 2.5, listingType: 'BUY_NOW', status: 'ACTIVE', listedAt: Date.now() - 86400e3,
      item: { id: id + 100, name, category: 'Hats', rarity: 'Standard' } });
    await page.route('**/api/listings/my-stall', (r) => {
      if (r.request().method() === 'DELETE') { gone = true; return r.fulfill(json({ cancelled: 3 })); }
      return r.fulfill(json(gone ? [] : [L(1, 'Red Beanie'), L(2, 'Blue Cap'), L(3, 'Green Hood')]));
    });
    await page.goto('/me/stall');
    const cancelAll = page.getByRole('button', { name: /Cancel all/ });
    await expect(cancelAll).toBeVisible();
    await cancelAll.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByText(/Your stall is empty/)).toBeVisible();
    await expect(cancelAll).toHaveCount(0);
    await expect(page.getByRole('tab', { name: /^Active/ })).toBeFocused();
  });

  test('Profile > Offers: after Cancel all pending, focus lands on the Outgoing tab, not the page', async ({ page }) => {
    await consent(page);
    await signedIn(page);
    let gone = false;
    const O = (id) => ({ id, listingId: 100 + id, itemId: 200 + id, itemName: `Hat ${id}`, amount: 4, askingPrice: 10,
      status: 'PENDING', author: 'BUYER', sellerName: 'bob', createdAt: Date.now() });
    await page.route('**/api/offers/incoming', (r) => r.fulfill(json([])));
    await page.route('**/api/offers/outgoing', (r) => r.fulfill(json(gone ? [] : [O(2), O(3)])));
    await page.route('**/api/offers/outgoing/cancel-all', (r) => { gone = true; return r.fulfill(json({ cancelled: 2 })); });
    await page.goto('/profile/offers?view=outgoing');
    const btn = page.getByRole('button', { name: /Cancel all pending/ });
    await expect(btn).toBeVisible({ timeout: 20_000 });
    await btn.focus();
    await page.keyboard.press('Enter');
    await expect(btn).toHaveCount(0);
    await expect(page.getByRole('tab', { name: /^Outgoing/ })).toBeFocused();
  });

  test('Profile > Buy Orders: after Cancel all active, focus lands on the Buy Orders tab, not the page', async ({ page }) => {
    await consent(page);
    await signedIn(page);
    let gone = false;
    const B = (id) => ({ id, itemId: 10 + id, itemName: `Order Hat ${id}`, status: gone ? 'CANCELLED' : 'ACTIVE', maxPrice: 2,
      quantity: 1, originalQuantity: 1, createdAt: Date.now(), updatedAt: Date.now() });
    await page.route('**/api/buy-orders', (r) => r.fulfill(json([B(3), B(4)])));
    await page.route('**/api/buy-orders/cancel-all', (r) => { gone = true; return r.fulfill(json({ cancelled: 2 })); });
    await page.goto('/profile/buyorders');
    const btn = page.getByRole('button', { name: /Cancel all active/ });
    await expect(btn).toBeVisible({ timeout: 20_000 });
    await btn.focus();
    await page.keyboard.press('Enter');
    await expect(btn).toHaveCount(0);
    await expect(page.getByRole('tab', { name: /^Buy Orders/ })).toBeFocused();
  });
});
