// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 84.

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

test.describe('defect pass 84 · buyorders', () => {
  const later = (r, res, ms = 1500) => new Promise((ok) => setTimeout(ok, ms)).then(() => r.fulfill(res));

  const now = Date.now();
  const ORDERS = [
    { id: 1, itemId: 11, itemName: 'Red Beanie', status: 'ACTIVE', maxPrice: 1.5, quantity: 1, originalQuantity: 1, createdAt: now, updatedAt: now },
    { id: 2, itemId: 12, itemName: 'Blue Jacket', status: 'ACTIVE', maxPrice: 2.5, quantity: 2, originalQuantity: 2, createdAt: now, updatedAt: now },
  ];

  test('buy orders page: each row.s Pause / Cancel button names its item', async ({ page }) => {
    await signedIn(page);
    await page.route(/\/api\/buy-orders(\?.*)?$/, (r) => r.fulfill(json(ORDERS)));
    await page.goto('/buy-orders');
    await expect(page.getByText('Blue Jacket')).toBeVisible();
    await expect(page.getByRole('button', { name: /Pause.*Red Beanie/ })).toHaveCount(1);
    await expect(page.getByRole('button', { name: /Cancel.*Blue Jacket/ })).toHaveCount(1);
  });

  test('profile buy orders tab: each row.s Edit / Pause / Cancel button names its item', async ({ page }) => {
    await signedIn(page);
    await page.route(/\/api\/buy-orders(\?.*)?$/, (r) => r.fulfill(json(ORDERS)));
    await page.goto('/profile/buyorders');
    await expect(page.getByText('Blue Jacket')).toBeVisible();
    await expect(page.getByRole('button', { name: /Edit.*Red Beanie/ })).toHaveCount(1);
    await expect(page.getByRole('button', { name: /Pause.*Blue Jacket/ })).toHaveCount(1);
    await expect(page.getByRole('button', { name: /Cancel.*Red Beanie/ })).toHaveCount(1);
  });

  test('profile buy orders tab: Escape while a save is in flight keeps the editor and its values', async ({ page }) => {
    await signedIn(page);
    await page.route(/\/api\/buy-orders(\?.*)?$/, (r) => r.fulfill(json(ORDERS)));
    await page.route('**/api/buy-orders/1', (r) => r.request().method() === 'PUT'
      ? later(r, json({ message: 'Max price is above the floor' }, 400)) : r.fulfill(json(ORDERS[0])));
    await page.goto('/profile/buyorders');
    await page.getByRole('button', { name: /Edit/ }).first().click();
    const max = page.getByRole('textbox', { name: 'New max price per item in USD' });
    await max.fill('9.99');
    await max.press('Enter');
    await max.press('Escape');
    await expect(max).toBeVisible();
    await expect(page.getByText(/Max price is above the floor/)).toBeVisible();
    await expect(max).toHaveValue('9.99');
  });
});

test.describe('defect pass 84 · profile-email', () => {
  const later = (r, res, ms = 1500) => new Promise((ok) => setTimeout(ok, ms)).then(() => r.fulfill(res));

  test('personal tab: Escape while the email save is in flight keeps the editor and its draft', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/profile/me', (r) => r.fulfill(json({ user: { id: 3, displayName: 'carol', email: 'old@example.com', emailVerified: true } })));
    await page.route('**/api/profile/email', (r) => later(r, json({ message: 'That address is already in use' }, 409)));
    await page.goto('/profile/personal');
    await page.getByRole('button', { name: 'Edit email address' }).click();
    const box = page.getByRole('textbox', { name: 'Email address' });
    await box.fill('new@example.com');
    await box.press('Enter');
    await box.press('Escape');
    await expect(page.getByText(/already in use/)).toBeVisible();
    await expect(box).toBeVisible();
    await expect(box).toHaveValue('new@example.com');
  });
});

test.describe('defect pass 84 · loadout', () => {
  const later = (r, res, ms = 1500) => new Promise((ok) => setTimeout(ok, ms)).then(() => r.fulfill(res));

  const LO = { loadout: { id: 5, name: 'Winter fit', ownerUserId: 3, ownerName: 'carol', visibility: 'PUBLIC', favorites: 0, totalValue: 0 }, slots: [] };

  test('loadout rename: Escape while the rename is saving keeps the box and its draft', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/loadouts/5', (r) => r.request().method() === 'GET'
      ? r.fulfill(json(LO)) : later(r, json({ message: 'Name is taken' }, 409)));
    await page.goto('/loadout/5');
    await page.getByRole('button', { name: /Rename/ }).click();
    const box = page.getByRole('textbox', { name: 'New loadout name' });
    await box.fill('Summer fit');
    await box.press('Enter');
    await box.press('Escape');
    await expect(page.getByText(/Name is taken/)).toBeVisible();
    await expect(box).toBeVisible();
    await expect(box).toHaveValue('Summer fit');
  });
});

test.describe('defect pass 84 · admin', () => {
  const now = Date.now();
  const REPORTED = [
    { id: 41, itemId: 11, itemName: 'Red Beanie', sellerName: 'bob', price: 3, reportCount: 2, distinctReporters: 2, topReasons: ['Scam'], lastReportedAt: now, recentNotes: [] },
    { id: 42, itemId: 12, itemName: 'Blue Jacket', sellerName: 'dan', price: 5, reportCount: 1, distinctReporters: 1, topReasons: ['Wrong item'], lastReportedAt: now, recentNotes: [] },
  ];

  test('admin reports tab: each row.s Dismiss / Remove button names its listing', async ({ page }) => {
    await asAdmin(page);
    await page.route('**/api/admin/listings/reported*', (r) => r.fulfill(json(REPORTED)));
    await page.goto('/admin?tab=reported');
    await expect(page.getByText('Blue Jacket')).toBeVisible();
    await expect(page.getByRole('button', { name: /Dismiss.*#41/ })).toHaveCount(1);
    await expect(page.getByRole('button', { name: /Remove.*Blue Jacket/ })).toHaveCount(1);
  });

  test('admin announce tab: history does not claim "No announcements yet." while it is still loading', async ({ page }) => {
    await asAdmin(page);
    await page.route('**/api/admin/announcements', slow([{ id: 1, message: 'Maintenance tonight', severity: 'INFO', active: false, createdAt: now }], 4000));
    await page.goto('/admin?tab=announce');
    await expect(page.getByText(/Broadcast notification/)).toBeVisible();
    await expect(page.getByText('History', { exact: true })).toBeVisible();
    // While the request is still in flight (4s), the history must not read as empty.
    expect(await page.getByText('No announcements yet.').count()).toBe(0);
    await expect(page.getByText('Maintenance tonight')).toBeVisible();
    await expect(page.getByText('No announcements yet.')).toHaveCount(0);
  });

  test('admin announce tab: each live banner.s Stop button names its banner', async ({ page }) => {
    await asAdmin(page);
    await page.route('**/api/admin/announcements', (r) => r.fulfill(json([
      { id: 1, message: 'Maintenance tonight', severity: 'INFO', active: true, createdAt: now },
      { id: 2, message: 'Steam is down', severity: 'WARN', active: true, createdAt: now },
    ])));
    await page.goto('/admin?tab=announce');
    await expect(page.getByText('LIVE NOW')).toBeVisible();
    await expect(page.getByRole('button', { name: /Stop/ })).toHaveCount(2);
    await expect(page.getByRole('button', { name: /Stop.*Steam is down/ })).toHaveCount(1);
  });
});

test.describe('defect pass 84 · tickets', () => {
  const now = Date.now();
  const T = (id, subject) => ({ id, subject, userId: 9, username: 'erin', category: 'TRADE', status: 'WAITING_STAFF', updatedAt: now });
  const LIST = [T(7, 'Item never arrived'), T(8, 'Cannot sign in')];
  const THREAD = { ticket: T(8, 'Cannot sign in'), messages: [{ id: 1, author: 'USER', authorName: 'erin', body: 'Help please', createdAt: now }] };

  test('CSR tickets: ← Tickets returns focus to the ticket row that was opened', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/csr/check', (r) => r.fulfill(json({ csr: true })));
    await page.route(/\/api\/csr\/tickets(\?.*)?$/, (r) => r.fulfill(json(LIST)));
    await page.route('**/api/csr/tickets/8', (r) => r.fulfill(json(THREAD)));
    await page.goto('/csr?tab=tickets');
    const row = page.getByRole('button', { name: /Open ticket #8/ });
    await row.focus();
    await row.press('Enter');
    await expect(page.getByText('Help please')).toBeVisible();
    await page.getByRole('button', { name: '← Tickets' }).click();
    await expect(page.getByRole('button', { name: /Open ticket #8/ })).toBeFocused();
  });

  test('admin tickets: ← Tickets returns focus to the ticket row that was opened', async ({ page }) => {
    await asAdmin(page);
    await page.route(/\/api\/admin\/tickets(\?.*)?$/, (r) => r.fulfill(json(LIST)));
    await page.route('**/api/admin/tickets/8', (r) => r.fulfill(json(THREAD)));
    await page.goto('/admin?tab=tickets');
    const row = page.getByRole('button', { name: /Open ticket #8/ });
    await row.focus();
    await row.press('Enter');
    await expect(page.getByText('Help please')).toBeVisible();
    await page.getByRole('button', { name: '← Tickets' }).click();
    await expect(page.getByRole('button', { name: /Open ticket #8/ })).toBeFocused();
  });
});
