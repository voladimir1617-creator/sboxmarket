// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 86.

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

test.describe('defect pass 86 · tickets', () => {
  const T = { id: 41, subject: 'Trade stuck', category: 'TRADE', status: 'WAITING_STAFF', username: 'dave', userId: 9, updatedAt: Date.now() };
  const thread = (status) => ({ ticket: { ...T, status }, messages: [{ id: 1, author: 'USER', authorName: 'dave', body: 'Help', createdAt: Date.now() }] });

  async function csr(page) {
    await signedIn(page);
    await page.route('**/api/csr/check', (r) => r.fulfill(json({ csr: true })));
    await page.route('**/api/csr/tickets?**', (r) => r.fulfill(json([T])));
    await page.route('**/api/csr/tickets/41', (r) => r.fulfill(json(thread('WAITING_STAFF'))));
  }

  test('CSR ticket opened from the keyboard keeps focus in the thread', async ({ page }) => {
    await csr(page);
    await page.goto('/csr?tab=tickets');
    const row = page.getByRole('button', { name: /Open ticket #41/ });
    await row.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('button', { name: /← Tickets/ })).toBeVisible();
    await expect.poll(() => page.evaluate(() => document.activeElement.tagName)).not.toBe('BODY');
    await expect(page.locator(':focus')).toContainText(/#41 · Trade stuck/);
  });

  test('admin ticket opened from the keyboard keeps focus in the thread', async ({ page }) => {
    await asAdmin(page);
    await page.route('**/api/admin/tickets?**', (r) => r.fulfill(json([T])));
    await page.route('**/api/admin/tickets', (r) => r.fulfill(json([T])));
    await page.route('**/api/admin/tickets/41', (r) => r.fulfill(json(thread('WAITING_STAFF'))));
    await page.goto('/admin?tab=tickets');
    const row = page.getByRole('button', { name: /Open ticket #41/ });
    await row.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('button', { name: /← Tickets/ })).toBeVisible();
    await expect.poll(() => page.evaluate(() => document.activeElement.tagName)).not.toBe('BODY');
    await expect(page.locator(':focus')).toContainText(/#41 · Trade stuck/);
  });

  test('CSR Close keeps keyboard focus in the ticket thread', async ({ page }) => {
    await csr(page);
    let closed = false;
    await page.route('**/api/csr/tickets/41/close', (r) => { closed = true; r.fulfill(json({ ok: true })); });
    await page.route('**/api/csr/tickets/41', (r) => r.fulfill(json(thread(closed ? 'RESOLVED' : 'WAITING_STAFF'))));
    await page.goto('/csr?tab=tickets');
    await page.getByRole('button', { name: /Open ticket #41/ }).click();
    const close = page.getByRole('button', { name: /^Close$/ });
    await close.focus();
    await page.keyboard.press('Enter');
    await expect(close).toHaveCount(0);
    await expect.poll(() => page.evaluate(() => document.activeElement.tagName)).not.toBe('BODY');
    await expect(page.locator(':focus')).toContainText(/#41 · Trade stuck/);
  });

  test('support Mark Resolved keeps keyboard focus in the ticket thread', async ({ page }) => {
    await signedIn(page);
    let resolved = false;
    await page.route('**/api/support/tickets', (r) => r.fulfill(json([{ ...T, status: resolved ? 'RESOLVED' : 'WAITING_STAFF' }])));
    await page.route('**/api/support/tickets/41/resolve', (r) => { resolved = true; r.fulfill(json({ ok: true })); });
    await page.route('**/api/support/tickets/41', (r) => r.fulfill(json(thread(resolved ? 'RESOLVED' : 'WAITING_STAFF'))));
    await page.goto('/profile/support');
    await page.getByRole('button', { name: /Open ticket #41/ }).click();
    const btn = page.getByRole('button', { name: /Mark Resolved/ });
    await btn.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('button', { name: /Reopen/ })).toBeVisible();
    await expect.poll(() => page.evaluate(() => document.activeElement.tagName)).not.toBe('BODY');
    await expect(page.locator(':focus')).toContainText(/#41 · Trade stuck/);
  });
});

test.describe('defect pass 86 · loadout-slot', () => {
  const base = { id: 5, name: 'Winter fit', ownerUserId: 3, ownerName: 'carol', visibility: 'PUBLIC', favorites: 0, totalValue: 0 };
  const HAT = { slot: 'Hats', itemId: 11, itemName: 'Wool Beanie', snapshotPrice: 2.5, locked: false };

  test('loadout: clearing a slot with the keyboard moves focus to that slot.s + Add button', async ({ page }) => {
    await signedIn(page);
    let cleared = false;
    await page.route('**/api/loadouts/5', (r) => r.fulfill(json({ loadout: base, slots: cleared ? [] : [HAT] })));
    await page.route('**/api/loadouts/5/slot/**', (r) => { cleared = true; r.fulfill(json({ ok: true })); });
    await page.goto('/loadout/5');
    const clear = page.getByRole('button', { name: 'Clear Hats slot' });
    await clear.focus();
    await page.keyboard.press('Enter');
    await expect(clear).toHaveCount(0);
    await expect(page.getByRole('button', { name: /Add an item to the Hats slot/ })).toBeFocused();
  });
});

test.describe('defect pass 86 · database', () => {
  const items = (page) => Array.from({ length: 30 }, (_, i) => ({ id: page * 30 + i + 1, name: 'Item ' + (page * 30 + i + 1), category: 'Hats', rarity: 'Common', lowestPrice: 1, listingCount: 1, supply: 5 }));

  test('database: paging to the last page with the keyboard keeps focus on the pager', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/database?**', (r) => {
      const p = Number(new URL(r.request().url()).searchParams.get('page') || 0);
      r.fulfill(json({ items: items(p), total: 60, indexed: 60 }));
    });
    await page.goto('/db');
    const next = page.getByRole('button', { name: /Next/ });
    await next.scrollIntoViewIfNeeded();
    await next.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByText('2 of 2')).toBeVisible();
    await expect(next).toBeDisabled();
    await expect(page.getByRole('button', { name: /Prev/ })).toBeFocused();
  });
});

test.describe('defect pass 86 · notifications', () => {
  const now = Date.now();
  const N = (id, read) => ({ id, kind: 'OTHER', title: 'Note ' + id, body: 'Body ' + id, read, createdAt: now - id * 1000 });

  test('notifications: deleting a row with the keyboard moves focus to the next row', async ({ page }) => {
    await signedIn(page);
    let rows = [N(1, true), N(2, true), N(3, true)];
    await page.route('**/api/notifications', (r) => r.fulfill(json({ items: rows, unread: 0 })));
    await page.route('**/api/notifications/2', (r) => { rows = rows.filter((n) => n.id !== 2); r.fulfill(json({ ok: true })); });
    await page.goto('/notifications');
    const del = page.getByRole('button', { name: 'Delete this notification: Note 2' });
    await del.focus();
    await page.keyboard.press('Enter');
    await expect(del).toHaveCount(0);
    await expect(page.getByRole('button', { name: /Open notification: Note 3/ })).toBeFocused();
  });

  test('notifications: Mark as unread with the keyboard keeps focus on that row', async ({ page }) => {
    await signedIn(page);
    let rows = [N(1, true), N(2, true)];
    await page.route('**/api/notifications', (r) => r.fulfill(json({ items: rows, unread: rows.filter((n) => !n.read).length })));
    await page.route('**/api/notifications/2/unread', (r) => { rows = rows.map((n) => (n.id === 2 ? { ...n, read: false } : n)); r.fulfill(json({ ok: true })); });
    await page.goto('/notifications');
    const btn = page.getByRole('button', { name: 'Mark as unread: Note 2' });
    await btn.focus();
    await page.keyboard.press('Enter');
    await expect(btn).toHaveCount(0);
    await expect(page.getByRole('button', { name: /Unread\. Open notification: Note 2/ })).toBeFocused();
  });
});

test.describe('defect pass 86 · twofa', () => {
  async function twofa(page) {
    await signedIn(page);
    await page.route('**/api/profile/me', (r) => r.fulfill(json({ user: { ...ME, email: 'c@x.io', emailVerified: true }, twoFactorEnabled: true })));
    await page.route('**/api/profile/2fa/recovery-status', (r) => r.fulfill(json({ remainingCodes: 8 })));
    await page.route('**/api/profile/2fa/regenerate-codes', (r) => r.fulfill(json({ recoveryCodes: ['aaaa-1111', 'bbbb-2222'] })));
    await page.goto('/profile/personal');
  }

  test('2FA Disable drawer: Escape returns focus to the Disable button', async ({ page }) => {
    await twofa(page);
    const open = page.getByRole('button', { name: 'Disable', exact: true });
    await open.click({ timeout: 20000 });
    const box = page.getByLabel('6-digit authenticator code or backup code');
    await expect(box).toBeFocused();
    await box.press('Escape');
    await expect(box).toHaveCount(0);
    await expect(open).toBeFocused();
  });

  test('2FA Regenerate drawer: Cancel returns focus to the Regenerate codes button', async ({ page }) => {
    await twofa(page);
    const open = page.getByRole('button', { name: 'Regenerate codes' });
    await open.click({ timeout: 20000 });
    await page.getByRole('button', { name: 'Cancel', exact: true }).click();
    await expect(page.getByLabel('6-digit authenticator code', { exact: true })).toHaveCount(0);
    await expect(open).toBeFocused();
  });

  test('2FA backup codes: I.ve saved them returns focus to the Regenerate codes button', async ({ page }) => {
    await twofa(page);
    await page.getByRole('button', { name: 'Regenerate codes' }).click({ timeout: 20000 });
    await page.getByLabel('6-digit authenticator code', { exact: true }).fill('123456');
    const done = page.getByRole('button', { name: /I.ve saved them/ });
    await expect(done).toBeVisible();
    await done.click();
    await expect(done).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Regenerate codes' })).toBeFocused();
  });
});
