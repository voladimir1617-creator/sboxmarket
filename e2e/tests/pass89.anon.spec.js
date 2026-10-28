// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 89.

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

test.describe('defect pass 89 · pages', () => {

  const NOTE = (id, extra) => Object.assign({ id, kind: 'TRADE_SENT', title: 'Trade note ' + id, body: 'Body ' + id, read: false, createdAt: Date.now() - id * 60000 }, extra || {});

  test('market: a failed listings load is announced as an alert', async ({ page }) => {
    await page.route('**/api/**', (r) => r.fulfill(json({})));
    await page.route('**/api/listings?**', (r) => r.fulfill(json({ error: 'boom' }, 500)));
    await page.goto('/market');
    await expect(page.getByTestId('market-load-error')).toBeVisible();
    await expect(page.getByRole('alert').filter({ hasText: /Couldn.t load listings/ })).toBeVisible();
  });

  test('stall: a failed stall load is announced as an alert', async ({ page }) => {
    await page.route('**/api/**', (r) => r.fulfill(json({})));
    await page.route('**/api/listings/stall/42', (r) => r.fulfill(json({ error: 'boom' }, 500)));
    await page.goto('/stall/42');
    await expect(page.getByRole('heading', { name: /Couldn.t load this stall/ })).toBeVisible();
    await expect(page.getByRole('alert').filter({ hasText: /Couldn.t load this stall/ })).toBeVisible();
  });

  test('market: Clear Filters on the no-results state keeps keyboard focus on the page', async ({ page }) => {
    await page.route('**/api/**', (r) => r.fulfill(json({})));
    await page.route('**/api/listings?**', (r) => r.fulfill(json([])));
    await page.goto('/market?q=zzzz');
    const clear = page.locator('.empty-state').getByRole('button', { name: 'Clear Filters' });
    await clear.scrollIntoViewIfNeeded();
    await clear.focus();
    await page.keyboard.press('Enter');
    await expect(clear).toHaveCount(0);
    await expect(page.getByRole('navigation', { name: 'Category filter' }).getByRole('button', { name: 'All', exact: true })).toBeFocused();
  });

  test('database: Clear Filters on the no-match state moves focus to the search box', async ({ page }) => {
    await page.route('**/api/**', (r) => r.fulfill(json({})));
    await page.route('**/api/database?**', (r) => {
      const q = new URL(r.request().url()).searchParams.get('q');
      return r.fulfill(json(q ? { items: [], total: 0, indexed: 1 } : { items: [{ id: 1, name: 'Wizard Hat', category: 'Hats', rarity: 'Standard', supply: 3, totalSold: 1, viewCount: 2, lowestPrice: 2.5 }], total: 1, indexed: 1 }));
    });
    await page.goto('/db?q=zzzz');
    const clear = page.getByRole('button', { name: 'Clear Filters' });
    await clear.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('link', { name: /Open Wizard Hat detail/ })).toBeVisible();
    await expect(page.getByRole('searchbox', { name: 'Search items in catalogue' })).toBeFocused();
  });

  test('notifications: Clear search on the no-match state moves focus to the search box', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/notifications**', (r) => r.fulfill(json({ items: [1, 2, 3, 4, 5, 6].map((i) => NOTE(i)), unread: 6 })));
    await page.goto('/notifications');
    const box = page.getByRole('textbox', { name: 'Search notifications' });
    await box.fill('zzzz');
    const clear = page.getByRole('button', { name: 'Clear search' });
    await clear.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByText('Trade note 1')).toBeVisible();
    await expect(box).toBeFocused();
  });

  test('notifications: Show all on the all-caught-up state keeps focus on the filter', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/notifications**', (r) => r.fulfill(json({ items: [NOTE(1, { read: true })], unread: 0 })));
    await page.goto('/notifications');
    await page.getByRole('button', { name: /^Unread/ }).click();
    const all = page.getByRole('button', { name: 'Show all' });
    await all.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByText('Trade note 1')).toBeVisible();
    await expect(page.getByRole('button', { name: /^All 1$/ })).toBeFocused();
  });

  test('notifications: Clear type filter keeps focus on the type chips', async ({ page }) => {
    await signedIn(page);
    let items = [NOTE(1), NOTE(2, { kind: 'WELCOME' }), NOTE(3, { kind: 'WELCOME' }), NOTE(4, { kind: 'WELCOME' }), NOTE(5, { kind: 'WELCOME' })];
    await page.route('**/api/notifications**', (r) => {
      if (r.request().method() === 'DELETE') { items = items.filter((n) => n.id !== 1); return r.fulfill(json({ ok: true })); }
      return r.fulfill(json({ items, unread: items.length }));
    });
    await page.goto('/notifications');
    await page.getByRole('button', { name: 'Trades · 1' }).click();
    await page.getByRole('button', { name: 'Delete this notification: Trade note 1' }).click();
    const clear = page.getByRole('button', { name: 'Clear type filter' });
    await clear.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByText('Trade note 2')).toBeVisible();
    await expect(page.getByRole('button', { name: 'All · 4' })).toBeFocused();
  });

  test('notifications: Clear read keeps keyboard focus in the toolbar', async ({ page }) => {
    await signedIn(page);
    let items = [NOTE(1), NOTE(2, { read: true })];
    await page.route('**/api/notifications**', (r) => {
      if (r.request().method() === 'DELETE' || /clear-read|read\/clear/.test(r.request().url())) { items = items.filter((n) => !n.read); return r.fulfill(json({ deleted: 1 })); }
      return r.fulfill(json({ items, unread: 1 }));
    });
    await page.goto('/notifications');
    const clear = page.getByRole('button', { name: 'Clear read' });
    await clear.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByText('Trade note 2')).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Mark all read' })).toBeFocused();
  });

  test('item page: a failed item load is announced as an alert', async ({ page }) => {
    await page.route('**/api/**', (r) => r.fulfill(json({})));
    await page.route('**/api/listings/item/5', (r) => r.fulfill(json({ error: 'boom' }, 500)));
    await page.route('**/api/items/5', (r) => r.fulfill(json({ error: 'boom' }, 500)));
    await page.goto('/item/5');
    await expect(page.getByTestId('item-load-error')).toBeVisible();
    await expect(page.getByRole('alert').filter({ hasText: /couldn.t reach the marketplace/ })).toBeVisible();
  });
});
