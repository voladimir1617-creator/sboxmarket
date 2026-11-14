// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 122.

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

// Pass 122: the submit button of drawers opened inside a full page
// (Profile > Trades Mark sent / Report counterparty, My Stall Bulk price
// adjust, admin Catalogue Edit) stretched to 320px and dropped 18px below
// Cancel; Revoke buttons of API keys sharing a label had one name; quick-reply
// and template chips left focus on the chip; the Trades "Clear filter" dropped
// focus to the page; the Database search-only empty state told the user to
// clear filters that were not set.

const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';
const consent = (page) => page.addInitScript(() => {
  try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {}
});
const devLogin = (page, userId = 1) => page.request.get(`${base}/api/auth/steam/dev-login?userId=${userId}&next=/`);

// The submit sits on Cancel's row (same top edge) at its own width.
async function expectRowButtons(dlg, submitName) {
  const submit = await dlg.getByRole('button', { name: submitName }).boundingBox();
  const cancel = await dlg.getByRole('button', { name: 'Cancel', exact: true }).boundingBox();
  expect(Math.abs(submit.y - cancel.y)).toBeLessThanOrEqual(2);
  expect(submit.width).toBeLessThan(200);
}

const trade = { id: 501, state: 'PENDING_SELLER_SEND', sellerUserId: ME.id, buyerUserId: 7, counterpartyName: 'dave',
  itemName: 'Police Hat', price: 4.23, listingId: 76, createdAt: Date.now() - 3600e3, updatedAt: Date.now() };

test.describe('Drawer submit buttons inside full pages sit beside Cancel', () => {
  for (const vp of [{ width: 1440, height: 900 }, { width: 390, height: 844 }]) {
    test(`My Stall Bulk price adjust Apply at ${vp.width}px (real app)`, async ({ page }) => {
      await page.setViewportSize(vp);
      await consent(page);
      await devLogin(page);
      await page.goto(`${base}/me/stall/active`);
      await page.getByRole('button', { name: /Bulk price adjust/ }).click();
      await expectRowButtons(page.getByRole('dialog', { name: /Bulk price adjust/ }), /^Apply/);
    });
  }

  test('Profile > Trades Mark sent and Report counterparty (stubbed)', async ({ page }) => {
    await consent(page);
    await signedIn(page);
    await page.route('**/api/trades', (r) => r.fulfill(json([trade])));
    await page.goto('/profile/trades');
    await page.getByRole('button', { name: /^Mark Sent for/ }).click();
    const sent = page.getByRole('dialog', { name: 'Mark trade as sent' });
    await expectRowButtons(sent, /^Mark sent$/);
    await sent.getByRole('button', { name: 'Cancel', exact: true }).click();
    await page.getByRole('button', { name: /^Report dave on/ }).click();
    await expectRowButtons(page.getByRole('dialog', { name: 'Report counterparty' }), 'File report');
  });

  test('Admin Catalogue Edit Save changes (stubbed)', async ({ page }) => {
    await consent(page);
    await asAdmin(page);
    await page.route('**/api/auth/steam/me', (r) => r.fulfill(json({ ...ME, role: 'ADMIN' })));
    await page.route('**/api/items?q=**', (r) => r.fulfill(json([{ id: 5, name: 'WW1 Helmet', category: 'Hats', rarity: 'Limited', steamPrice: 50 }])));
    await page.goto('/admin');
    await page.getByRole('tab', { name: /Catalogue/ }).click();
    await page.getByRole('textbox', { name: 'Search items by name' }).fill('WW');
    await page.getByRole('button', { name: /Edit/ }).first().click();
    await expectRowButtons(page.getByRole('dialog', { name: /Edit WW1 Helmet/ }), 'Save changes');
  });
});

test.describe('Developers: API keys that share a label', () => {
  test('each Revoke button still names one key (real app)', async ({ page }) => {
    await consent(page);
    await devLogin(page, 4);
    await page.goto(`${base}/profile/developers`);
    const label = page.getByLabel('API key label', { exact: true });
    const newKey = page.getByRole('button', { name: /New Key|Minting/ });
    for (let i = 0; i < 2; i++) {
      await label.fill('');
      await newKey.click();
      await expect(page.getByText(/COPY THIS NOW/)).toBeVisible();
      await page.getByRole('button', { name: 'Dismiss', exact: true }).click();
    }
    const revokes = page.getByRole('button', { name: /^Revoke API key Untitled/ });
    await expect.poll(() => revokes.count()).toBeGreaterThanOrEqual(2);
    const names = await revokes.evaluateAll((bs) => bs.map((b) => b.getAttribute('aria-label')));
    expect(new Set(names).size).toBe(names.length);
  });
});

test.describe('Quick-reply and template chips put the caret in the reply box', () => {
  test('Profile > Trades chat: a chip then Enter sends the message (stubbed)', async ({ page }) => {
    await consent(page);
    await signedIn(page);
    let posted = null;
    await page.route('**/api/trades', (r) => r.fulfill(json([trade])));
    await page.route('**/api/trades/501/messages', (r) => {
      if (r.request().method() === 'POST') { posted = r.request().postDataJSON(); return r.fulfill(json({ id: 2 })); }
      return r.fulfill(json([]));
    });
    await page.goto('/profile/trades');
    await page.getByRole('button', { name: /^Chat about/ }).click();
    await page.getByRole('button', { name: '5 min', exact: true }).click();
    const box = page.getByRole('textbox', { name: 'Message to your trade partner' });
    await expect(box).toHaveValue(/Give me 5 minutes/);
    await expect(box).toBeFocused();
    await page.keyboard.press('Enter');
    await expect.poll(() => posted && posted.body).toMatch(/Give me 5 minutes/);
  });

  test('Admin and CSR ticket template chips focus the reply box (stubbed)', async ({ page }) => {
    await consent(page);
    await asAdmin(page);
    await page.route('**/api/csr/check', (r) => r.fulfill(json({ csr: true })));
    const T = { id: 6, subject: 'Help me', category: 'OTHER', status: 'WAITING_STAFF', userId: 4, username: 'erin', updatedAt: Date.now() };
    await page.route(/\/api\/(admin|csr)\/tickets(\?.*)?$/, (r) => r.fulfill(json([T])));
    await page.route(/\/api\/(admin|csr)\/tickets\/6$/, (r) => r.fulfill(json({ ticket: T, messages: [] })));
    await page.goto('/admin?tab=tickets');
    await page.getByRole('tabpanel').getByText('Help me').click();
    await page.getByRole('button', { name: /Greet/ }).click();
    await expect(page.getByRole('textbox', { name: 'Staff reply' })).toBeFocused();
    await page.goto('/csr?tab=tickets');
    await page.getByRole('button', { name: /Open ticket #6/ }).click();
    await page.getByRole('button', { name: /Greet/ }).click();
    await expect(page.getByRole('textbox', { name: 'Reply as CSR' })).toBeFocused();
  });
});

test.describe('Profile > Trades empty filter state', () => {
  test('Clear filter lands on the All chip, not the page (stubbed)', async ({ page }) => {
    await consent(page);
    await signedIn(page);
    await page.route('**/api/trades', (r) => r.fulfill(json([trade])));
    await page.goto('/profile/trades');
    await page.getByRole('button', { name: /^Disputed$/ }).click();
    await expect(page.getByText('No trades match this filter.')).toBeVisible();
    await page.getByRole('button', { name: 'Clear filter', exact: true }).click();
    await expect(page.getByRole('button', { name: /^Mark Sent for/ })).toBeVisible();
    const all = page.getByRole('button', { name: 'All', exact: true }).first();
    await expect(all).toHaveAttribute('aria-pressed', 'true');
    await expect(all).toBeFocused();
  });
});

test.describe('Database search with no matches', () => {
  for (const vp of [{ width: 1440, height: 900 }, { width: 390, height: 844 }]) {
    test(`a search-only miss points at the search at ${vp.width}px (real app)`, async ({ page }) => {
      await page.setViewportSize(vp);
      await consent(page);
      await page.goto(`${base}/database`);
      await page.getByRole('searchbox', { name: 'Search items in catalogue' }).fill('zzqqxxnomatch');
      await expect(page.getByText(/No items match your filters/)).toBeVisible();
      await expect(page.getByText(/match your search\. Check the spelling or clear the search/)).toBeVisible();
      await expect(page.getByText(/Try clearing the category, rarity, or price range/)).toHaveCount(0);
    });
  }
});
