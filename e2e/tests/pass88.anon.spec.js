// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 88.

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

test.describe('defect pass 88 · loadout', () => {

  const loadout = () => ({ loadout: { id: 5, name: 'Winter fit', ownerUserId: 3, ownerName: 'carol', visibility: 'PRIVATE', favorites: 0, totalValue: 0 }, slots: [], favorited: false });

  test('loadout: Generate keeps keyboard focus while it fills the slots', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/loadouts/5', (r) => r.fulfill(json(loadout())));
    await page.route('**/api/loadouts/5/generate', slow({ ok: true }, 600));
    await page.goto('/loadout/5');
    const gen = page.getByRole('button', { name: 'Generate' });
    await gen.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByText(/Loadout already complete|Generate ran|Generated/)).toBeVisible();
    await expect(page.getByRole('button', { name: 'Generate' })).toBeFocused();
  });
});

test.describe('defect pass 88 · profile', () => {

  test('developers: + New Key keeps keyboard focus while the key is minted', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/api-keys', (r) => r.request().method() === 'POST'
      ? slow({ id: 9, label: 'bot', token: 'sbx_live_9_secret', publicPrefix: 'sbx_live_9', scope: 'RW', createdAt: Date.now() }, 600)(r)
      : r.fulfill(json([])));
    await page.goto('/profile/developers');
    await page.getByRole('textbox', { name: 'API key label' }).fill('bot');
    const btn = page.getByRole('button', { name: /New Key|Minting/ });
    await btn.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByText('sbx_live_9_secret')).toBeVisible();
    await expect(page.getByRole('button', { name: '+ New Key' })).toBeFocused();
  });

  test('profile: pausing a buy order keeps keyboard focus on its Pause/Resume button', async ({ page }) => {
    await signedIn(page);
    let paused = false;
    const order = () => ({ id: 5, itemId: 1, itemName: 'Wizard Hat', maxPrice: '3.00', quantity: 1, filledQuantity: 0, status: paused ? 'PAUSED' : 'ACTIVE', createdAt: Date.now(), expiresAt: Date.now() + 86400000 * 20 });
    await page.route('**/api/buy-orders', (r) => r.fulfill(json([order()])));
    await page.route('**/api/buy-orders/5/pause', (r) => { paused = true; return slow(order(), 500)(r); });
    await page.goto('/profile/buyorders');
    await page.getByRole('button', { name: /^All/ }).click();
    const pause = page.getByRole('button', { name: 'Pause buy order for Wizard Hat' });
    await pause.scrollIntoViewIfNeeded();
    await pause.focus();
    await page.keyboard.press('Enter');
    const resume = page.getByRole('button', { name: 'Resume buy order for Wizard Hat' });
    await expect(resume).toBeVisible();
    await expect(resume).toBeFocused();
  });
});

test.describe('defect pass 88 · staff', () => {

  async function asCsr(page) {
    await signedIn(page);
    await page.route('**/api/csr/check', (r) => r.fulfill(json({ csr: true })));
  }

  test('csr: an unknown ?tab= falls back to the Queue tab', async ({ page }) => {
    await asCsr(page);
    await page.route('**/api/csr/stats', (r) => r.fulfill(json({ openTickets: 4, waitingStaff: 1, waitingUser: 0, creditCap: 50 })));
    await page.goto('/csr?tab=bogus');
    const queue = page.getByRole('tab', { name: 'Queue' });
    await expect(queue).toBeVisible();
    await expect(queue).toHaveAttribute('aria-selected', 'true');
    await expect(page.getByText(/Open Tickets/)).toBeVisible();
  });

  test('csr: flagging a listing keeps keyboard focus in the form', async ({ page }) => {
    await asCsr(page);
    await page.route('**/api/csr/listings/*/flag', slow({ id: 42 }, 400));
    await page.goto('/csr?tab=flag');
    await page.getByPlaceholder('Listing ID').fill('42');
    await page.getByRole('combobox', { name: 'Flag reason' }).selectOption('Suspicious pricing');
    const btn = page.getByRole('button', { name: /Flag listing/ });
    await btn.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('status').filter({ hasText: /#42 added to the admin review queue/ })).toBeVisible();
    await expect(page.getByPlaceholder('Listing ID')).toBeFocused();
  });

  test('admin: opening a tab from a dashboard tile moves focus to that tab', async ({ page }) => {
    await asAdmin(page);
    await page.route('**/api/admin/stats', (r) => r.fulfill(json({ users: 10, items: 5, openTickets: 3 })));
    await page.route('**/api/admin/audit**', (r) => r.fulfill(json([])));
    await page.route('**/api/admin/tickets**', (r) => r.fulfill(json([])));
    await page.goto('/admin');
    const tile = page.getByRole('button', { name: /^Open Tickets/ });
    await tile.scrollIntoViewIfNeeded();
    await tile.focus();
    await page.keyboard.press('Enter');
    const tab = page.getByRole('tab', { name: /Tickets/ });
    await expect(tab).toHaveAttribute('aria-selected', 'true');
    await expect(tab).toBeFocused();
  });

  test('admin: user row role buttons name the user they act on', async ({ page }) => {
    await asAdmin(page);
    await page.route('**/api/admin/stats', (r) => r.fulfill(json({})));
    await page.route('**/api/admin/users**', (r) => r.fulfill(json([
      { id: 7, displayName: 'alice', steamId64: '7656', role: 'USER', banned: false },
      { id: 8, displayName: 'bob', steamId64: '7657', role: 'CSR', banned: false },
    ])));
    await page.goto('/admin?tab=users');
    await expect(page.getByRole('button', { name: /Grant CSR role to alice/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /Revoke CSR role from bob/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /Grant admin role to alice/i })).toBeVisible();
    await expect(page.getByRole('button', { name: /Force logout bob/i })).toBeVisible();
  });

  test('admin: dismissing reports keeps keyboard focus in the Reports tab', async ({ page }) => {
    await asAdmin(page);
    await page.route('**/api/admin/stats', (r) => r.fulfill(json({})));
    let dismissed = false;
    const row = (id) => ({ id, itemId: id, itemName: 'Hat ' + id, sellerName: 's', price: 3, reportCount: 2, distinctReporters: 2, topReasons: ['Scam'], lastReportedAt: Date.now(), recentNotes: [] });
    await page.route('**/api/admin/listings/reported', (r) => r.fulfill(json(dismissed ? [row(2)] : [row(1), row(2)])));
    await page.route('**/api/admin/listings/*/dismiss-reports', (r) => { dismissed = true; return slow({ ok: true }, 300)(r); });
    await page.goto('/admin?tab=reported');
    const dismiss = page.getByRole('button', { name: /Dismiss reports on listing #1/ });
    await dismiss.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('textbox')).toBeFocused();
    await page.getByRole('button', { name: 'Dismiss reports', exact: true }).click();
    await expect(page.getByRole('button', { name: /Dismiss reports on listing #1/ })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /Dismiss reports on listing #2/ })).toBeVisible();
    await page.waitForTimeout(300);
    await expect(page.locator('body')).not.toBeFocused();
    const inPanel = await page.evaluate(() => !!document.activeElement && !!document.activeElement.closest('#admin-tabpanel'));
    expect(inPanel).toBe(true);
  });

  test('admin: the simulator count field can be cleared and retyped', async ({ page }) => {
    await asAdmin(page);
    await page.route('**/api/admin/stats', (r) => r.fulfill(json({})));
    await page.route('**/api/admin/simulate/count', (r) => r.fulfill(json({ count: 0 })));
    await page.route('**/api/admin/simulate**', (r) => r.fulfill(json({ count: 0 })));
    await page.goto('/admin?tab=simulator');
    const count = page.getByRole('spinbutton', { name: 'Count' });
    await count.click();
    await count.press('End');
    await count.press('Backspace');
    await count.press('Backspace');
    await count.pressSequentially('5');
    await expect(count).toHaveValue('5');
    await expect(page.getByRole('button', { name: 'Spawn 5 listings' })).toBeVisible();
  });
});
