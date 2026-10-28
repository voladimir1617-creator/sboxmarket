// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 92.

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

test.describe('defect pass 92 · a', () => {

  const TRADE = { id: 41, state: 'PENDING_SELLER_SEND', itemName: 'Red Beanie', itemId: 11, price: 5, buyerUserId: 3, sellerUserId: 9, counterpartyName: 'dave', createdAt: Date.now() - 3600e3, updatedAt: Date.now() - 600e3 };
  const TICKET = { id: 41, subject: 'Trade stuck', category: 'TRADE', status: 'WAITING_STAFF', username: 'dave', userId: 9, updatedAt: Date.now() };
  const thread = (extra = []) => ({ ticket: TICKET, messages: [{ id: 1, author: 'USER', authorName: 'dave', body: 'Help', createdAt: Date.now() }, ...extra] });

  test('trade chat Send keeps keyboard focus after the message goes out', async ({ page }) => {
    await signedIn(page);
    let msgs = [{ id: 1, tradeId: 41, senderUserId: 9, body: 'hello there', createdAt: Date.now() - 60e3 }];
    await page.route('**/api/trades', (r) => r.fulfill(json([TRADE])));
    await page.route('**/api/profile/me', (r) => r.fulfill(json({ user: { ...ME }, accountStanding: { level: 'GOOD' } })));
    await page.route('**/api/trades/41/messages', async (r) => {
      if (r.request().method() === 'POST') {
        await new Promise((ok) => setTimeout(ok, 600));
        msgs = msgs.concat([{ id: 2, tradeId: 41, senderUserId: 3, body: 'my fresh reply', createdAt: Date.now() }]);
        return r.fulfill(json(msgs[1]));
      }
      return r.fulfill(json(msgs));
    });
    await page.goto('/profile/trades');
    await page.locator('#trade-41').getByRole('button', { name: /Chat/ }).click();
    const box = page.getByRole('textbox', { name: 'Message to your trade partner' });
    await box.scrollIntoViewIfNeeded();
    await box.fill('my fresh reply');
    const send = page.locator('#trade-41').getByRole('button', { name: /^Send$/ });
    await send.click();
    await expect(page.getByText('my fresh reply')).toBeVisible();
    await expect.poll(() => page.evaluate(() => document.activeElement.tagName)).not.toBe('BODY');
    await expect(send).toBeFocused();
  });

  test('support ticket Send keeps keyboard focus after the reply goes out', async ({ page }) => {
    await signedIn(page);
    let sent = false;
    await page.route('**/api/support/tickets', (r) => r.fulfill(json([TICKET])));
    await page.route('**/api/support/tickets/41', (r) => r.fulfill(json(thread(sent ? [{ id: 2, author: 'USER', authorName: 'carol', body: 'still stuck', createdAt: Date.now() }] : []))));
    await page.route('**/api/support/tickets/41/reply', async (r) => { await new Promise((ok) => setTimeout(ok, 600)); sent = true; r.fulfill(json({ ok: true })); });
    await page.goto('/profile/support');
    await page.getByRole('button', { name: /Open ticket #41/ }).click();
    await page.getByRole('textbox', { name: 'Reply to support' }).fill('still stuck');
    const send = page.getByRole('button', { name: /^Send$/ });
    await send.click();
    await expect(page.getByText('still stuck')).toBeVisible();
    await expect.poll(() => page.evaluate(() => document.activeElement.tagName)).not.toBe('BODY');
    await expect(send).toBeFocused();
  });

  for (const who of ['admin', 'CSR']) {
    test(`${who} ticket reply box is labelled and Send keeps keyboard focus`, async ({ page }) => {
      const base = who === 'admin' ? 'admin' : 'csr';
      if (who === 'admin') await asAdmin(page); else { await signedIn(page); await page.route('**/api/csr/check', (r) => r.fulfill(json({ csr: true }))); }
      let sent = false;
      await page.route(`**/api/${base}/tickets?**`, (r) => r.fulfill(json([TICKET])));
      await page.route(`**/api/${base}/tickets`, (r) => r.fulfill(json([TICKET])));
      await page.route(`**/api/${base}/tickets/41`, (r) => r.fulfill(json(thread(sent ? [{ id: 2, author: 'STAFF', authorName: 'carol', body: 'on it now', createdAt: Date.now() }] : []))));
      await page.route(`**/api/${base}/tickets/41/reply`, async (r) => { await new Promise((ok) => setTimeout(ok, 600)); sent = true; r.fulfill(json({ ok: true })); });
      await page.goto(`/${base}?tab=tickets`);
      await page.getByRole('button', { name: /Open ticket #41/ }).click();
      const box = page.getByLabel(who === 'admin' ? /^Staff reply/ : /^Reply as CSR/);
      await box.fill('on it now');
      const send = page.getByRole('button', { name: /^Send$/ });
      await send.click();
      await expect(page.getByText('on it now', { exact: true })).toBeVisible();
      await expect.poll(() => page.evaluate(() => document.activeElement.tagName)).not.toBe('BODY');
      await expect(send).toBeFocused();
    });
  }

  test('admin audit log filter boxes have labels', async ({ page }) => {
    await asAdmin(page);
    await page.route('**/api/admin/audit**', (r) => r.fulfill(json([])));
    await page.goto('/admin?tab=audit');
    await expect(page.getByRole('combobox', { name: 'Filter by audit event' })).toBeVisible({ timeout: 20000 });
    await expect(page.getByLabel('Actor user #id')).toBeVisible();
    await expect(page.getByLabel('Subject user #id')).toBeVisible();
    await expect(page.getByLabel(/Search summary/)).toBeVisible();
  });

  test('admin user drawer Save notes keeps keyboard focus and the notes box is labelled', async ({ page }) => {
    await asAdmin(page);
    await page.route('**/api/admin/users**', (r) => r.fulfill(json([{ id: 7, displayName: 'alice', steamId64: '7656', role: 'USER', banned: false }])));
    await page.route('**/api/admin/users/7/notes', (r) => r.request().method() === 'PUT' ? slow({ ok: true }, 600)(r) : r.fulfill(json({ adminNotes: '' })));
    await page.goto('/admin?tab=users');
    await page.getByRole('button', { name: 'View user detail for alice' }).click();
    const notes = page.getByLabel(/Staff notes/);
    await notes.fill('Prior chargeback in 2025');
    const save = page.getByRole('button', { name: /^Save notes$/ });
    await save.click();
    await expect(page.getByText('Staff notes saved.')).toBeVisible();
    await expect.poll(() => page.evaluate(() => document.activeElement.tagName)).not.toBe('BODY');
    await expect(save).toBeFocused();
  });
});

test.describe('defect pass 92 · b', () => {

  test('admin Catalogue and Users search boxes have labels', async ({ page }) => {
    await asAdmin(page);
    await page.route('**/api/admin/users**', (r) => r.fulfill(json([])));
    await page.goto('/admin?tab=catalogue');
    await expect(page.getByPlaceholder('Search items by name…')).toBeVisible({ timeout: 20000 });
    await expect(page.getByLabel(/Search items by name/)).toBeVisible();
    await page.goto('/admin?tab=users');
    await expect(page.getByPlaceholder('Search by name, Steam ID…')).toBeVisible({ timeout: 20000 });
    await expect(page.getByLabel(/Search by name, Steam ID/)).toBeVisible();
  });

  test('admin API key lookup and CSR Flag listing ID boxes have labels', async ({ page }) => {
    await asAdmin(page);
    await page.route('**/api/csr/check', (r) => r.fulfill(json({ csr: true })));
    await page.route('**/api/admin/fraud', (r) => r.fulfill(json([])));
    await page.goto('/admin?tab=fraud');
    await expect(page.getByPlaceholder('sbx_live_…')).toBeVisible({ timeout: 20000 });
    await expect(page.getByLabel(/API key prefix/)).toBeVisible();
    await page.goto('/csr?tab=flag');
    await expect(page.getByPlaceholder('Listing ID')).toBeVisible({ timeout: 20000 });
    await expect(page.getByLabel(/Listing ID/)).toBeVisible();
  });

  test('admin Disputes load failure is announced and Refresh keeps focus', async ({ page }) => {
    await asAdmin(page);
    let n = 0;
    await page.route('**/api/admin/disputes', (r) => { n++; return n === 1 ? r.fulfill(json({ error: 'boom' }, 500)) : slow([], 800)(r); });
    await page.goto('/admin?tab=disputes');
    await expect(page.getByRole('alert').filter({ hasText: /Disputes failed to load/ })).toBeVisible({ timeout: 20000 });
    const btn = page.getByRole('button', { name: /^Refresh$/ });
    await btn.click();
    await expect(page.getByRole('button', { name: /^Loading…$/ })).toBeVisible();
    await expect(page.getByText(/No active chargebacks/)).toBeVisible();
    await expect(page.getByRole('button', { name: /^Refresh$/ })).toBeFocused();
  });
});
