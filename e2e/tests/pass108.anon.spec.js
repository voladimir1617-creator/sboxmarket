// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 108.

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
// Defect pass 108.

// The real SkinBox app (dev login on, H2 demo data), the same server the suite runs against.
// Every /api call goes to it, so these checks exercise the real API.
const JAR = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';
async function realApi(page, userId) {
  if (userId) await page.request.get(`${JAR}/api/auth/steam/dev-login?userId=${userId}&next=/`, { maxRedirects: 0 });
  await page.route('**/api/**', (r) => {
    const u = new URL(r.request().url());
    return r.continue({ url: JAR + u.pathname + u.search });
  });
}
async function asCsr(page) {
  await signedIn(page);
  await page.route('**/api/csr/check', (r) => r.fulfill(json({ csr: true })));
}

// A cold new tab on a loaded CI runner can take well over 5s to open and
// commit its first navigation (main run 591 timed out at 5s, then sat on
// about:blank for 8s on retry; locally the first one took 19s). The
// assertions are unchanged, only the wait is longer.
const NEW_TAB_MS = 20000;

test.describe('database item links', () => {
  test('Ctrl/Cmd-click on an item name opens it in a new tab and leaves the Database in place (real app)', async ({ page, context }) => {
    test.slow(); // two NEW_TAB_MS waits must fit in the test budget
    await realApi(page, 0);
    await page.goto('/db');
    const link = page.locator('a.db-name').first();
    await expect(link).toBeVisible();
    const href = await link.getAttribute('href');
    const popup = context.waitForEvent('page', { timeout: NEW_TAB_MS });
    await link.click({ modifiers: ['ControlOrMeta'] });
    const tab = await popup;
    // A new tab starts at about:blank; wait for it to reach its URL.
    await expect.poll(() => { try { return new URL(tab.url()).pathname; } catch (_) { return ''; } }, { timeout: NEW_TAB_MS }).toBe(href);
    await expect(page).toHaveURL(/\/db(\?|$)/);
    await expect(page.getByRole('status').filter({ hasText: /results? · page/ })).toBeVisible();
  });
});

test.describe('sell bulk list', () => {
  const row = (id, name) => ({
    assetId: id, assetIds: [id], quantity: 1, name, type: 'Hat', iconUrl: null,
    tradable: true, marketable: true, category: 'Hats', rarity: 'Standard', catalogueId: Number(id) % 1000,
    suggestedPrice: 6, steamPrice: null, listedCount: 0, listableQuantity: 1, unlistableReason: null,
  });
  test('items that failed a bulk list stay selected; the listed one drops out', async ({ page }) => {
    await signedIn(page);
    const A = row('108001', 'Alpha Hat'), B = row('108002', 'Beta Hat'), C = row('108003', 'Gamma Hat');
    let listed = false;
    await page.route('**/api/steam/inventory', (r) => r.fulfill(json({
      items: listed ? [B, C] : [A, B, C], count: listed ? 2 : 3, assetCount: listed ? 2 : 3, lastSyncedAt: Date.now(), steamId64: '7656',
    })));
    await page.route('**/api/steam/list-bulk', (r) => { listed = true; return r.fulfill(json({
      ok: [{ assetId: '108001', listingId: 9001, itemId: 1, price: 5, status: 'ACTIVE', escrowPending: false }],
      failed: [
        { assetId: '108002', code: 'NOT_TRADABLE', message: 'Not tradable on Steam right now' },
        { assetId: '108003', code: 'INTERNAL_ERROR', message: 'Could not list this item' },
      ],
    })); });
    await page.goto('/sell');
    for (const n of ['Alpha Hat', 'Beta Hat', 'Gamma Hat']) {
      await page.getByRole('checkbox', { name: `Select ${n} for bulk listing` }).check({ force: true });
    }
    await page.getByRole('textbox', { name: 'Bulk list price in USD' }).fill('5');
    await page.getByRole('button', { name: /^List 3 @/ }).click();
    await expect(page.getByText(/Listed 1 · 2 failed/)).toBeVisible();
    await expect(page.getByRole('checkbox', { name: 'Select Alpha Hat for bulk listing' })).toHaveCount(0);
    await expect(page.getByRole('checkbox', { name: 'Select Beta Hat for bulk listing' })).toBeChecked();
    await expect(page.getByRole('checkbox', { name: 'Select Gamma Hat for bulk listing' })).toBeChecked();
    await expect(page.getByText(/2 selected for bulk list/)).toBeVisible();
  });
});

test.describe('CSR user lookup', () => {
  test('a "#5" user id, as the placeholder suggests, finds user 5', async ({ page }) => {
    await asCsr(page);
    const user = { id: 5, steamId64: '76561202255232733', displayName: 'EmberWolf', role: 'USER', balance: 0, recentTx: [] };
    // Mirrors CsrService.lookupUser: a bare number matches the user id, "#5" matches nothing.
    await page.route('**/api/csr/users/lookup?**', (r) => {
      const q = new URL(r.request().url()).searchParams.get('q');
      return r.fulfill(json({ matches: /^\d+$/.test(q || '') && Number(q) === 5 ? [user] : [] }));
    });
    await page.goto('/csr?tab=lookup');
    const box = page.getByRole('textbox', { name: 'Look up a user by Steam ID, display name or user id' });
    await expect(box).toHaveAttribute('placeholder', /user #id/);
    await box.fill('#5');
    await box.press('Enter');
    await expect(page.getByText('EmberWolf', { exact: true })).toBeVisible();
    await expect(page.getByText('No matches.')).toHaveCount(0);
  });
});

test.describe('loadout lab tabs', () => {
  test('a Discover search does not turn an empty My Loadouts tab into "No loadouts match" (real app)', async ({ page }) => {
    await realApi(page, 4);
    await page.goto('/loadout');
    const search = page.getByRole('searchbox', { name: 'Search public loadouts' }).or(page.getByRole('textbox', { name: 'Search public loadouts' }));
    await search.fill('zzqqxx-no-such-loadout');
    await expect(page.getByText(/No loadouts match/)).toBeVisible();
    await page.getByRole('tab', { name: 'My Loadouts' }).click();
    await expect(page.getByRole('heading', { name: 'No loadouts yet' })).toBeVisible();
    await expect(page.getByRole('button', { name: /Create my first loadout/ })).toBeVisible();
    await expect(page.getByText(/No loadouts match/)).toHaveCount(0);
  });
});

test.describe('CSR ticket queue', () => {
  test('Back from a ticket the reply moved out of "Waiting on us" keeps focus in the queue', async ({ page }) => {
    await asCsr(page);
    const T = { id: 5, subject: 'Help me', category: 'OTHER', status: 'WAITING_STAFF', userId: 4, username: 'erin', updatedAt: Date.now() };
    let replied = false;
    await page.route('**/api/csr/tickets?**', (r) => r.fulfill(json(replied ? [] : [T])));
    await page.route('**/api/csr/tickets/5', (r) => r.fulfill(json({ ticket: { ...T, status: replied ? 'WAITING_USER' : T.status }, messages: [] })));
    await page.route('**/api/csr/tickets/5/reply', (r) => { replied = true; return r.fulfill(json({ ok: true })); });
    await page.goto('/csr?tab=tickets');
    await page.getByRole('button', { name: /Open ticket #5/ }).click();
    await page.getByRole('textbox', { name: 'Reply as CSR' }).fill('On it');
    await page.getByRole('button', { name: 'Send', exact: true }).click();
    await expect(page.getByText(/WAITING_USER/)).toBeVisible();
    await page.getByRole('button', { name: '← Tickets' }).click();
    await expect(page.getByText('Queue is clear.')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Waiting on us', exact: true })).toBeFocused();
  });
});

test.describe('My Stall cancel all', () => {
  test('the confirm counts only the listings it will cancel and does not offer a single cancel for bid-locked auctions (real app)', async ({ page }) => {
    await realApi(page, 2);
    const confirms = [];
    await page.addInitScript(() => { window.__confirms = []; window.confirm = (m) => { window.__confirms.push(m); return false; }; });
    await page.goto('/me/stall');
    const res = await page.request.get(`${JAR}/api/listings/my-stall`);
    const rows = await res.json();
    const kept = rows.filter((l) => l.listingType === 'AUCTION' && (l.bidCount || 0) > 0).length;
    const n = rows.length - kept;
    await page.getByRole('button', { name: '✕ Cancel all', exact: true }).click();
    confirms.push(...await page.evaluate(() => window.__confirms));
    expect(confirms).toHaveLength(1);
    expect(confirms[0]).not.toMatch(/cancel those individually/);
    expect(confirms[0]).toMatch(kept > 0 ? new RegExp(`^Cancel ${n} of your ${rows.length} active listings`) : new RegExp(`^Cancel all ${rows.length} active listings`));
  });

  test('with only bid-locked auctions left it says there is nothing to cancel instead of asking to cancel them', async ({ page }) => {
    await signedIn(page);
    const auction = (id) => ({ id, price: 5, listingType: 'AUCTION', bidCount: 3, currentBid: 6, expiresAt: Date.now() + 86400000, status: 'ACTIVE', hidden: false, item: { id: 100 + id, name: `Bid Hat ${id}`, category: 'Hats', rarity: 'Standard' } });
    let deletes = 0, asked = 0;
    await page.exposeFunction('__asked', () => { asked += 1; });
    await page.addInitScript(() => { window.confirm = () => { window.__asked(); return true; }; });
    await page.route('**/api/listings/my-stall', (r) => {
      if (r.request().method() === 'DELETE') { deletes += 1; return r.fulfill(json({ cancelled: 0, skippedAuctions: 2, failed: 0 })); }
      return r.fulfill(json([auction(1), auction(2)]));
    });
    await page.goto('/me/stall');
    await page.getByRole('button', { name: '✕ Cancel all', exact: true }).click();
    await expect(page.locator('#sb-toast-host')).toContainText(/Nothing to cancel/);
    expect(asked).toBe(0);
    expect(deletes).toBe(0);
  });
});

test.describe('help center contact link', () => {
  test('Ctrl/Cmd-click on "Open a ticket" opens Support in a new tab (real app)', async ({ page, context }) => {
    test.slow(); // two NEW_TAB_MS waits must fit in the test budget
    await realApi(page, 0);
    await page.goto('/help?q=zzqqxx');
    const link = page.getByRole('link', { name: /Open a ticket/ });
    await expect(link).toBeVisible();
    const popup = context.waitForEvent('page', { timeout: NEW_TAB_MS });
    await link.click({ modifiers: ['ControlOrMeta'] });
    const tab = await popup;
    // A new tab starts at about:blank; wait for it to reach its URL.
    await expect.poll(() => { try { return new URL(tab.url()).pathname; } catch (_) { return ''; } }, { timeout: NEW_TAB_MS }).toBe('/support');
    await expect(page).toHaveURL(/\/help/);
  });
});
