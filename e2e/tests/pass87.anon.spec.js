// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 87.

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

test.describe('defect pass 87 · admin', () => {

  const HEALTH = { uptimeMs: 7.2e6, startedAt: Date.now() - 7.2e6, memory: { heapUsedMb: 100, heapMaxMb: 400, processorCount: 4 }, threads: { live: 30, peak: 40, daemon: 20 }, systemLoad: 1, db: { schemaVersion: 120 } };

  test('admin health: a failed Run sync now is announced as an alert', async ({ page }) => {
    await asAdmin(page);
    await page.route('**/api/admin/health', (r) => r.fulfill(json(HEALTH)));
    await page.route('**/api/admin/sync-prices', (r) => r.fulfill(json({ error: 'Steam price sync is already running' }, 409)));
    await page.goto('/admin?tab=health');
    const run = page.getByRole('button', { name: 'Run sync now' });
    await run.scrollIntoViewIfNeeded();
    await run.click();
    await expect(page.getByText(/already running/)).toBeVisible();
    await expect(page.getByRole('alert').filter({ hasText: /already running/ })).toBeVisible();
  });

  test('admin simulator: a failed spawn is announced as an alert', async ({ page }) => {
    await asAdmin(page);
    await page.route('**/api/admin/simulate/count', (r) => r.fulfill(json({ count: 3 })));
    await page.route('**/api/admin/simulate/listings', (r) => r.fulfill(json({ error: 'Catalogue is empty' }, 400)));
    await page.goto('/admin?tab=simulator');
    await page.getByRole('button', { name: /^Spawn \d+ listings?$/ }).click();
    await expect(page.getByText(/Catalogue is empty/)).toBeVisible();
    await expect(page.getByRole('alert').filter({ hasText: /Catalogue is empty/ })).toBeVisible();
  });
});

test.describe('defect pass 87 · loadout', () => {

  const loadout = (owner, vis) => ({ loadout: { id: 5, name: 'Winter fit', ownerUserId: owner, ownerName: owner === 3 ? 'carol' : 'dan', visibility: vis, favorites: 0, totalValue: 0 }, slots: [], favorited: false });

  test('loadout: Favorite keeps keyboard focus while it saves', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/loadouts/5', (r) => r.fulfill(json(loadout(9, 'PUBLIC'))));
    await page.route('**/api/loadouts/5/favorite', slow({ favorited: true, favorites: 1 }, 600));
    await page.goto('/loadout/5');
    await page.getByRole('button', { name: /♡ Favorite/ }).focus();
    await page.keyboard.press('Enter');
    const fav = page.getByRole('button', { name: /♥ Favorited/ });
    await expect(fav).toBeVisible();
    await expect(fav).toBeFocused();
  });

  test('loadout: the Public/Private switch keeps keyboard focus while it saves', async ({ page }) => {
    await signedIn(page);
    let vis = 'PUBLIC';
    await page.route('**/api/loadouts/5', (r) => {
      if (r.request().method() === 'PUT') { vis = 'PRIVATE'; return slow({ id: 5, visibility: 'PRIVATE' }, 600)(r); }
      return r.fulfill(json(loadout(3, vis)));
    });
    await page.goto('/loadout/5');
    await page.getByRole('button', { name: 'Public', exact: true }).focus();
    await page.keyboard.press('Enter');
    const priv = page.getByRole('button', { name: 'Private', exact: true });
    await expect(priv).toBeVisible();
    await page.waitForTimeout(300);
    await expect(priv).toBeFocused();
  });
});

test.describe('defect pass 87 · profile', () => {

  test('profile: an email category checkbox keeps keyboard focus while it saves', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/profile/me', (r) => r.fulfill(json({ user: { id: 3, displayName: 'carol', emailNotificationsEnabled: true, email: 'c@x.com', emailVerified: true } })));
    await page.route('**/api/profile/email-mutes', (r) => r.request().method() === 'GET'
      ? r.fulfill(json({ available: ['TRADES', 'AUCTIONS'], muted: [] }))
      : slow({ muted: ['TRADES'] }, 600)(r));
    await page.goto('/profile/personal');
    const box = page.getByRole('checkbox', { name: /Trade activity/ });
    await box.scrollIntoViewIfNeeded();
    await box.focus();
    await page.keyboard.press('Space');
    await expect(page.getByText(/MUTED/)).toBeVisible();
    await page.waitForTimeout(900);
    await expect(box).not.toBeChecked();
    await expect(box).toBeFocused();
  });

  test('developers: Revoke all active keys hands focus to + New Key', async ({ page }) => {
    await signedIn(page);
    let revoked = false;
    const key = (id) => ({ id, label: 'bot' + id, publicPrefix: 'sbx_live_' + id, scope: 'RW', createdAt: Date.now(), revoked });
    await page.route('**/api/api-keys', (r) => {
      if (r.request().method() === 'DELETE') { revoked = true; return r.fulfill(json({ revoked: 2 })); }
      return r.fulfill(json([key(1), key(2)]));
    });
    await page.goto('/profile/developers');
    const all = page.getByRole('button', { name: 'Revoke all active keys' });
    await all.focus();
    await page.keyboard.press('Enter');
    await expect(all).toHaveCount(0);
    await expect(page.getByRole('button', { name: '+ New Key' })).toBeFocused();
  });
});

test.describe('defect pass 87 · stall', () => {

  const STALL = { seller: { id: 77, displayName: 'Stall Seller' }, listings: [], blockedByViewer: false };
  const REVIEW = { id: 5, rating: 5, comment: 'great', fromUserId: 2, fromDisplayName: 'B', helpfulCount: 0, viewerHasVoted: false, createdAt: Date.now() };

  async function stall(page) {
    await signedIn(page);
    await page.route('**/api/listings/stall/77*', (r) => r.fulfill(json(STALL)));
    await page.route('**/api/follows/status/77', (r) => r.fulfill(json({ following: false, followerCount: 2 })));
    await page.route('**/api/reviews/user/77*', (r) => r.fulfill(json([REVIEW])));
  }

  test('stall: Follow keeps keyboard focus while the follow saves', async ({ page }) => {
    await stall(page);
    await page.route('**/api/follows/77', slow({ ok: true }, 600));
    await page.goto('/stall/77');
    const follow = page.getByRole('button', { name: /^\+\s*Follow/ });
    await follow.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('button', { name: /Following/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /Following/ })).toBeFocused();
  });

  test('stall: Block keeps keyboard focus while the block saves', async ({ page }) => {
    await stall(page);
    await page.route('**/api/profile/blocks/77', slow({ ok: true }, 600));
    await page.goto('/stall/77');
    const block = page.getByRole('button', { name: 'Block', exact: true });
    await block.focus();
    await page.keyboard.press('Enter');
    const blocked = page.getByRole('button', { name: 'Blocked', exact: true });
    await expect(blocked).toBeVisible();
    await expect(blocked).toBeFocused();
  });

  test('stall review: Helpful exposes its voted state and keeps focus', async ({ page }) => {
    await stall(page);
    await page.route('**/api/reviews/5/helpful', slow({ helpfulCount: 1, viewerHasVoted: true }, 600));
    await page.goto('/stall/77');
    const helpful = page.getByRole('button', { name: /Helpful/ });
    await helpful.scrollIntoViewIfNeeded();
    await expect(helpful).toHaveAttribute('aria-pressed', 'false');
    await helpful.focus();
    await page.keyboard.press('Enter');
    await expect(helpful).toHaveAttribute('aria-pressed', 'true');
    await page.waitForTimeout(900);
    await expect(helpful).toBeFocused();
  });
});
