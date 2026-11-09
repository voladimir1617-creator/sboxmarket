// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 99.

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


const LO = (id, name, extra = {}) => ({ id, ownerUserId: -100, ownerName: 'CardKing', name, visibility: 'PUBLIC', totalValue: 7.65, favorites: 6, filledSlots: 1, ...extra });
const LO_VIEW = (id, name) => ({
  loadout: LO(id, name),
  slots: [{ id: 1, loadoutId: id, slot: 'Hats', itemId: 1, itemName: 'Short Parted Hair', itemEmoji: 'H', snapshotPrice: 0.54, currentPrice: 0.54, locked: false }],
  favorited: true, currentTotal: 0.54
});
async function loadoutStubs(page) {
  await signedIn(page);
  await page.route('**/api/loadouts/discover**', (r) => r.fulfill(json([LO(1, 'Cardboard Connoisseur'), LO(2, 'Plague Doctor')])));
  await page.route('**/api/loadouts/favorites', (r) => r.fulfill(json([LO(2, 'Plague Doctor')])));
  await page.route('**/api/loadouts/mine', (r) => r.fulfill(json([])));
  await page.route(/\/api\/loadouts\/\d+$/, (r) => {
    const id = +r.request().url().match(/(\d+)$/)[1];
    return r.fulfill(json(LO_VIEW(id, id === 2 ? 'Plague Doctor' : 'Cardboard Connoisseur')));
  });
}

test.describe('Loadout Lab', () => {
  test('Back from an opened loadout keeps keyboard focus on that loadout card', async ({ page }) => {
    await loadoutStubs(page);
    await page.goto('/loadout');
    const link = page.getByRole('link', { name: /^Open loadout Cardboard Connoisseur/ });
    await link.focus();
    await link.press('Enter');
    const back = page.getByRole('button', { name: /Back/ });
    await expect(back).toBeVisible();
    await back.press('Enter');
    await expect(page.getByRole('link', { name: /^Open loadout Cardboard Connoisseur/ })).toBeFocused();
  });

  test('Back from a loadout opened on the Favorites tab returns to Favorites', async ({ page }) => {
    await loadoutStubs(page);
    await page.goto('/loadout');
    await page.getByRole('tab', { name: 'Favorites' }).click();
    await page.getByRole('link', { name: /^Open loadout Plague Doctor/ }).click();
    await page.getByRole('button', { name: /Back/ }).click();
    await expect(page.getByRole('tab', { name: 'Favorites' })).toHaveAttribute('aria-selected', 'true');
  });

  test('empty My Loadouts and Favorites tabs title their state with a heading', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/loadouts/discover**', (r) => r.fulfill(json([])));
    await page.route('**/api/loadouts/favorites', (r) => r.fulfill(json([])));
    await page.route('**/api/loadouts/mine', (r) => r.fulfill(json([])));
    await page.goto('/loadout');
    await expect(page.getByRole('heading', { name: /No public loadouts yet/ })).toBeVisible();
    await page.getByRole('tab', { name: 'My Loadouts' }).click();
    await expect(page.getByRole('heading', { name: /No loadouts yet/ })).toBeVisible();
    await page.getByRole('tab', { name: 'Favorites' }).click();
    await expect(page.getByRole('heading', { name: /No favorites yet/ })).toBeVisible();
  });
});

const ITEM = { id: 1, name: 'Short Parted Hair', category: 'Hats', rarity: 'Standard', iconEmoji: 'H', accentColor: '#7a8b9c', supply: 3, totalSold: 1, lowestPrice: 0.54, steamPrice: null, trendPercent: 0, isListed: true };
const DAY = 86_400_000;
async function itemStubs(page, history) {
  await signedIn(page);
  await page.route('**/api/items/1', (r) => r.fulfill(json(ITEM)));
  await page.route('**/api/items/1/history**', (r) => r.fulfill(json(history)));
  await page.route('**/api/listings/item/1**', (r) => r.fulfill(json([])));
}

test.describe('Item price history', () => {
  test('the Price History heading is named by its text, not by the range buttons', async ({ page }) => {
    const now = Date.now();
    const hist = Array.from({ length: 20 }, (_, i) => ({ id: i + 1, price: 0.5 + i / 100, volume: 1, recordedAt: now - (19 - i) * DAY, dayLabel: 'D' + i }));
    await itemStubs(page, hist);
    await page.goto('/item/1');
    const range = page.getByRole('button', { name: 'Show 7D price history' });
    await range.scrollIntoViewIfNeeded();
    await expect(range).toBeInViewport();
    await expect(page.getByRole('heading', { name: 'Price History', exact: true })).toBeVisible();
  });

  test('with one sale, a disabled range does not point to ALL, which is already showing nothing', async ({ page }) => {
    await itemStubs(page, [{ id: 1, price: 0.54, volume: 1, recordedAt: Date.now() - 3600_000, dayLabel: 'Today' }]);
    await page.goto('/item/1');
    const all = page.getByRole('button', { name: /ALL/ });
    await all.scrollIntoViewIfNeeded();
    await expect(all).toHaveAttribute('aria-pressed', 'true');
    await expect(page.locator('.chart-empty')).toBeVisible();
    await expect(page.getByRole('button', { name: /^7D/ })).not.toHaveAttribute('title', /try ALL/);
  });
});

test.describe('Market filters on a phone', () => {
  test.use({ viewport: { width: 390, height: 844 } });

  test('Escape inside the filter drawer hands focus back to the Filters button', async ({ page }) => {
    await page.route('**/api/**', (r) => r.fulfill(json({})));
    await page.goto('/market');
    const fab = page.getByRole('button', { name: 'Open filters' });
    await fab.click();
    const all = page.getByRole('radio', { name: /^All/ }).first();
    await all.focus();
    await expect(all).toBeFocused();
    await page.keyboard.press('Escape');
    await expect(page.getByRole('button', { name: 'Open filters' })).toBeFocused();
  });

  test('removing a filter chip or Clear all keeps keyboard focus on the page', async ({ page }) => {
    await page.route('**/api/**', (r) => r.fulfill(json({})));
    await page.goto('/market?q=zorro&rarity=Limited');
    const chip = page.getByRole('button', { name: /Remove search filter/ });
    await chip.scrollIntoViewIfNeeded();
    await chip.press('Enter');
    await expect(page.getByRole('button', { name: /Remove rarity filter: Limited/ })).toBeFocused();
    const clear = page.getByRole('button', { name: 'Clear all active filters' });
    await clear.focus();
    await clear.press('Enter');
    await expect(clear).toHaveCount(0);
    await expect(page.locator('body')).not.toBeFocused();
    await expect(page.locator(':focus')).toHaveCount(1);
  });

  test('the no-results state titles itself with a heading', async ({ page }) => {
    await page.route('**/api/**', (r) => r.fulfill(json({})));
    await page.route('**/api/listings?**', (r) => r.fulfill(json([])));
    await page.goto('/market?q=zzzzqqq');
    const title = page.getByTestId('market-empty');
    await title.scrollIntoViewIfNeeded();
    await expect(title).toBeInViewport();
    await expect(page.getByRole('heading', { name: /No listings for .zzzzqqq./ })).toBeVisible();
  });
});

test.describe('Profile personal info', () => {
  test('removing the Steam trade URL keeps focus on the trade URL row', async ({ page }) => {
    await signedIn(page);
    let url = ME.tradeUrl;
    await page.route('**/api/profile/me', (r) => r.fulfill(json({ user: { ...ME, tradeUrl: url, email: null } })));
    await page.route('**/api/profile/trade-url', (r) => { url = null; return r.fulfill(json({ ok: true })); });
    await page.goto('/profile/personal');
    const remove = page.getByRole('button', { name: 'Remove' }).first();
    await remove.scrollIntoViewIfNeeded();
    await remove.focus();
    await remove.press('Enter');
    await expect(page.getByRole('button', { name: 'Add Steam trade URL' })).toBeFocused();
  });
});
