// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 154.

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


// Defect pass 154: the Loadout Lab tabs point at a region that is not a tab
// panel, favourite counts read as "red heart 6", the slot picker's "No items
// match." is never read out, the Affiliate thresholds are one run of text
// instead of a list, and link arrows/glyphs are read as part of link names
// (Loadout Lab, Settings, Personal Info, item page "More from").

const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';

async function consent(page) {
  await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
}

// Every write a real-jar test could reach is refused, so nothing on the
// server changes.
async function noWrites(page) {
  await page.route('**/api/**', (r) => (r.request().method() === 'GET' ? r.fallback() : r.fulfill(json({ error: 'blocked in test' }, 400))));
}

async function devLogin(page, userId) {
  await page.request.get(`${base}/api/auth/steam/dev-login?userId=${userId}&next=/`);
}

test.describe('pass154k · Loadout Lab (real jar)', () => {
  test('the section tabs control a tab panel named after the selected tab', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await page.goto(`${base}/loadout`);
    const tab = page.getByRole('tab', { name: 'Discover' });
    await expect(tab).toHaveAttribute('aria-selected', 'true');
    const panel = page.getByRole('tabpanel', { name: 'Discover' });
    await expect(panel).toBeVisible();
    await expect(panel).toHaveAttribute('id', (await tab.getAttribute('aria-controls')) || 'missing');
    await expect(panel.getByRole('link', { name: /^Open loadout / }).first()).toBeVisible();
  });

  test('a loadout and its Discover card read the favourites count as "N favorites"', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await page.goto(`${base}/loadout`);
    const meta = page.locator('.loadout-card-meta').first();
    await expect(meta).toBeVisible();
    const cardSnap = await meta.ariaSnapshot();
    expect(cardSnap).not.toContain('❤');
    expect(cardSnap).toMatch(/\d favorites?/);

    await page.goto(`${base}/loadout/1`);
    const row = page.locator('.loadout-action-row');
    await expect(row).toContainText(/By/);
    const snap = await row.ariaSnapshot();
    expect(snap).not.toContain('❤');
    expect(snap).toMatch(/\d favorites?/);
  });

  test('the missing-loadout notice link is named without its arrow', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await page.goto(`${base}/loadout/999999`);
    await expect(page.getByText(/Loadout #999999 doesn.t exist/)).toBeVisible();
    await expect(page.getByRole('link', { name: 'Browse all public loadouts', exact: true })).toBeVisible();
  });
});

test.describe('pass154k · Affiliate, Settings, Personal Info and item page (real jar)', () => {
  test('Affiliate requirements are a list of five platform thresholds', async ({ page }) => {
    await consent(page);
    await page.goto(`${base}/affiliate`);
    const list = page.getByRole('list', { name: 'Platform thresholds' });
    await list.scrollIntoViewIfNeeded();
    await expect(list.getByRole('listitem')).toHaveCount(5);
    await expect(list.getByRole('listitem').first()).toContainText(/YouTube/);
  });

  test('Settings account links are named without their arrow and download glyphs', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await devLogin(page, 2);
    await page.goto(`${base}/settings`);
    const manage = page.getByRole('link', { name: 'Manage account', exact: true });
    await manage.scrollIntoViewIfNeeded();
    await expect(manage).toBeVisible();
    await expect(page.getByRole('link', { name: 'Export my data (JSON)', exact: true })).toBeVisible();
  });

  test('Personal Info Export link is named without its download glyph', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await devLogin(page, 2);
    await page.goto(`${base}/profile/personal`);
    const link = page.getByRole('link', { name: 'Export my data (JSON)', exact: true });
    await link.scrollIntoViewIfNeeded();
    await expect(link).toBeVisible();
  });

  test('item page "More from" Visit stall link is named without its arrow', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await page.goto(`${base}/item/12`);
    const heading = page.getByRole('heading', { name: /^More from / });
    await heading.scrollIntoViewIfNeeded();
    await expect(heading.getByRole('link', { name: 'Visit stall', exact: true })).toBeVisible();
  });
});

test.describe('pass154k · Loadout Lab with stubs', () => {
  const LO = {
    loadout: { id: 9, ownerUserId: ME.id, ownerName: 'carol', name: 'Pass154 fit', visibility: 'PUBLIC', totalValue: 1, favorites: 0 },
    slots: [{ slot: 'Hats', itemId: 1, itemName: 'Chef Hat', currentPrice: 1, locked: false }],
    favorited: false
  };
  const ITEMS = { items: [
    { id: 1, name: 'Chef Hat', category: 'Hats', lowestPrice: 1 },
    { id: 3, name: 'Leather Coat', category: 'Jackets', lowestPrice: 3 }
  ], total: 2 };

  test('the slot picker reads out "No items match." when the filter empties it', async ({ page }) => {
    await consent(page);
    await signedIn(page);
    await page.route('**/api/loadouts/9', (r) => r.fulfill(json(LO)));
    await page.route('**/api/database?**', (r) => r.fulfill(json(ITEMS)));
    await page.goto('/loadout/9');
    await page.getByRole('button', { name: 'Add an item to the Jackets slot' }).click();
    const box = page.getByRole('textbox', { name: 'Filter Jackets items' });
    await expect(box).toBeFocused();
    await expect(page.getByRole('button', { name: /^Add Leather Coat to the Jackets slot/ })).toBeVisible();
    await box.fill('zzz');
    await expect(page.locator('.loadout-picker').getByRole('status')).toHaveText('No items match.');
  });

  test('the empty Favorites tab hides its heart icon and names its link without the arrow', async ({ page }) => {
    await consent(page);
    await signedIn(page);
    await page.route('**/api/loadouts/favorites', (r) => r.fulfill(json([])));
    await page.goto('/loadout?view=favorites');
    await expect(page.getByRole('heading', { name: 'No favorites yet' })).toBeVisible();
    await expect(page.locator('#loadout-results .empty-icon')).toHaveAttribute('aria-hidden', 'true');
    await expect(page.getByRole('button', { name: 'Browse public loadouts', exact: true })).toBeVisible();
  });
});
