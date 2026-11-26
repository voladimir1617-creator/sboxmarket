// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 162.

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


// Defect pass 162. Real-app tests take the server from JAR / E2E_BASE_URL
// and stub every write: a real-jar test must never create or change server data.

const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';

async function noWrites(page) {
  await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
  await page.route('**/api/**', (r) => (['POST', 'PUT', 'DELETE', 'PATCH'].includes(r.request().method()) ? r.fulfill(json({})) : r.fallback()));
}

async function devLogin(page, userId = 3) {
  await page.request.get(`${base}/api/auth/steam/dev-login?userId=${userId}&next=/`);
}

test.describe('User menu sign out (real app)', () => {
  test('a refused Logout keeps the user signed in and says so', async ({ page }) => {
    await noWrites(page);
    await page.route('**/api/auth/steam/logout', (r) => r.fulfill(json({ error: 'down' }, 500)));
    await devLogin(page, 2);
    await page.goto(`${base}/help`);
    await page.getByRole('button', { name: /^User menu/ }).click({ timeout: 20_000 });
    await page.getByRole('menuitem', { name: 'Logout' }).click();
    await expect(page.getByText(/Could not sign out/)).toBeVisible();
    await expect(page.getByRole('button', { name: /^User menu/ })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Sign in through Steam' })).toHaveCount(0);
  });

  test('a Logout that never reaches the server is reported, not ignored', async ({ page }) => {
    await noWrites(page);
    await page.route('**/api/auth/steam/logout', (r) => r.abort());
    await devLogin(page, 2);
    await page.goto(`${base}/help`);
    await page.getByRole('button', { name: /^User menu/ }).click({ timeout: 20_000 });
    await page.getByRole('menuitem', { name: 'Logout' }).click();
    await expect(page.getByText(/Could not sign out/)).toBeVisible();
    await expect(page.getByRole('button', { name: /^User menu/ })).toBeVisible();
  });
});

test.describe('Notification bell empty state (real app)', () => {
  test('a bell whose rows are all muted types does not say "all caught up"', async ({ page }) => {
    await noWrites(page);
    await page.addInitScript(() => { try { localStorage.setItem('sb_mute_kinds', JSON.stringify(['TRADES'])); } catch (_) {} });
    const now = Date.now();
    const rows = [
      { id: 901, kind: 'TRADE_VERIFIED', title: 'Trade verified', body: 'Red Beanie', read: false, createdAt: now - 60e3 },
      { id: 902, kind: 'ITEM_PURCHASED', title: 'Item sold', body: 'Blue Cap', read: false, createdAt: now - 120e3 },
    ];
    await page.route('**/api/notifications?*', (r) => (r.request().method() === 'GET' ? r.fulfill(json({ items: rows, unread: 2 })) : r.fallback()));
    await devLogin(page, 2);
    await page.goto(`${base}/help`);
    const bell = page.getByRole('button', { name: /^Notifications/ }).first();
    const panel = page.getByRole('menu', { name: 'Notifications' });
    await expect(bell).toBeVisible({ timeout: 20_000 });
    // Open it, again if the first press landed before the page settled.
    await expect(async () => {
      if ((await bell.getAttribute('aria-expanded')) !== 'true') await bell.click();
      await expect(panel).toBeVisible({ timeout: 1000 });
    }).toPass({ timeout: 15_000 });
    await expect(panel).toContainText(/Only muted notifications/);
    await expect(panel).not.toContainText(/all caught up/);
  });
});

test.describe('Stall bio editor (real app)', () => {
  test('Ctrl+Enter on an emptied bio removes it, as Save does', async ({ page }) => {
    await noWrites(page);
    await page.route(`${base}/api/listings/stall/3`, async (r) => {
      // A reload can still be in flight when the page closes.
      try {
        const res = await r.fetch();
        const body = await res.json();
        await r.fulfill(json({ ...body, seller: { ...body.seller, stallBio: 'Fast trades, evenings.' } }));
      } catch (_) { /* page closed */ }
    });
    let sent = null;
    await page.route('**/api/profile/stall-bio', (r) => { sent = r.request().postDataJSON(); return r.fulfill(json({ stallBio: null })); });
    await devLogin(page, 3);
    await page.goto(`${base}/stall/3`);
    await page.getByRole('button', { name: /Edit$/ }).click({ timeout: 20_000 });
    const box = page.getByRole('textbox', { name: 'Stall bio' });
    await expect(box).toHaveValue('Fast trades, evenings.');
    await box.fill('');
    await box.press('Control+Enter');
    await expect.poll(() => sent).toEqual({ bio: '' });
    await expect(box).toHaveCount(0);
  });
});

test.describe('Stall owner saves when the refetch fails', () => {
  const STALL = { seller: { id: 3, displayName: 'carol', soldCount: 4, stallBio: 'Old bio text' }, count: 0, rating: { count: 1, average: 5 }, listings: [] };

  test('the saved bio shows even though the stall reload failed', async ({ page }) => {
    await signedIn(page);
    let saved = false;
    let refused = 0;
    await page.route('**/api/listings/stall/3', (r) => {
      if (!saved) return r.fulfill(json(STALL));
      refused += 1;
      return r.fulfill(json({ error: 'down' }, 500));
    });
    await page.route('**/api/profile/stall-bio', (r) => { saved = true; return r.fulfill(json({ stallBio: 'New bio text' })); });
    await page.goto('/stall/3');
    await page.getByRole('button', { name: /Edit$/ }).click({ timeout: 20_000 });
    const box = page.getByRole('textbox', { name: 'Stall bio' });
    await box.fill('New bio text');
    await page.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(box).toHaveCount(0);
    // The reload after the save has been refused; the page must still show the save.
    await expect.poll(() => refused).toBeGreaterThan(0);
    await expect(page.getByText('Old bio text')).toHaveCount(0);
    await expect(page.getByText('New bio text')).toBeVisible();
  });

  test('a posted seller reply shows even though the reviews reload failed', async ({ page }) => {
    await signedIn(page);
    const REVIEW = { id: 5, rating: 5, comment: 'fast and friendly', createdAt: Date.now() - 3600e3, fromDisplayName: 'erin' };
    let saved = false;
    await page.route('**/api/listings/stall/3', (r) => r.fulfill(json({ ...STALL, seller: { ...STALL.seller, stallBio: null } })));
    await page.route('**/api/reviews/user/3*', (r) => (saved ? r.fulfill(json({ error: 'down' }, 500)) : r.fulfill(json([REVIEW]))));
    await page.route('**/api/reviews/5/reply', (r) => { saved = true; return r.fulfill(json({ ok: true })); });
    await page.goto('/stall/3');
    await page.getByRole('button', { name: /^Reply to erin/ }).click({ timeout: 20_000 });
    await page.getByRole('textbox', { name: 'Public reply to this review' }).fill('Thanks, come again');
    await page.getByRole('button', { name: 'Post reply' }).click();
    await expect(page.getByText('Thanks, come again')).toBeVisible();
    await expect(page.getByRole('button', { name: /^Edit reply to erin/ })).toBeVisible();
  });

  test('a deleted own review leaves the stall even though the reviews reload failed', async ({ page }) => {
    await signedIn(page);
    const MINE = { id: 6, rating: 2, comment: 'slow to send', createdAt: Date.now() - 3600e3, fromDisplayName: ME.displayName, mine: true };
    let deleted = false;
    await page.route('**/api/listings/stall/8', (r) => r.fulfill(json({ ...STALL, seller: { id: 8, displayName: 'hank', soldCount: 2 } })));
    await page.route('**/api/reviews/user/8*', (r) => (deleted ? r.fulfill(json({ error: 'down' }, 500)) : r.fulfill(json([MINE]))));
    await page.route('**/api/reviews/6', (r) => { deleted = true; return r.fulfill(json({ ok: true })); });
    await page.goto('/stall/8');
    await expect(page.getByText('slow to send')).toBeVisible({ timeout: 20_000 });
    await page.getByRole('button', { name: /Delete my review/ }).click();
    await expect.poll(() => deleted).toBe(true);
    await expect(page.getByText('slow to send')).toHaveCount(0);
  });
});
