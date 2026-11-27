// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 163.

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

// Defect pass 163: loadout busy names and Public toggle, support new-ticket
// errors and success, Settings reset wording, phone filter count.

const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';
const consent = (page) => page.addInitScript(() => {
  try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {}
});
const devLogin = (page, userId = 2) => page.request.get(`${base}/api/auth/steam/dev-login?userId=${userId}&next=/`);
// Real-jar tests never write: every POST/PUT/DELETE is answered here.
const noWrites = (page) => page.route(`${base}/api/**`, (r) => (r.request().method() === 'GET' ? r.fallback() : r.fulfill(json({}))));
// A write held open until the test lets it go, so "while it saves" never races the server.
function gate() {
  let open;
  const p = new Promise((ok) => { open = ok; });
  return { wait: () => p, open: () => open() };
}

test.describe('pass 163: Loadout builder (real app)', () => {
  // User 2 owns no loadout on the seeded jar; this one is read from a stub.
  const owned = (vis = 'PUBLIC') => ({ loadout: { id: 16301, name: 'Pass163 fit', ownerUserId: 2, ownerName: 'AtlasTrades', visibility: vis, favorites: 0, totalValue: 0 }, slots: [], favorited: false });

  test('rename Save and Generate keep a name while they run', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await devLogin(page);
    const put = gate();
    const gen = gate();
    await page.route(`${base}/api/loadouts/16301`, async (r) => {
      if (r.request().method() === 'PUT') { await put.wait(); return r.fulfill(json({ id: 16301, name: 'Pass163 renamed' })); }
      return r.fulfill(json(owned()));
    });
    await page.route(`${base}/api/loadouts/16301/generate`, async (r) => { await gen.wait(); return r.fulfill(json({ ok: true })); });
    await page.goto(`${base}/loadout/16301`);
    await page.getByRole('button', { name: /Rename/ }).click({ timeout: 20_000 });
    await page.getByRole('textbox', { name: 'New loadout name' }).fill('Pass163 renamed');
    await page.getByRole('button', { name: 'Save', exact: true }).focus();
    await page.keyboard.press('Enter');
    const saving = page.getByRole('button', { name: 'Saving…', exact: true });
    await expect(saving).toBeFocused();
    await expect(saving).toHaveAttribute('aria-disabled', 'true');
    put.open();
    await expect(page.getByRole('textbox', { name: 'New loadout name' })).toHaveCount(0);

    await page.getByRole('button', { name: 'Generate', exact: true }).focus();
    await page.keyboard.press('Enter');
    const generating = page.getByRole('button', { name: 'Generating…', exact: true });
    await expect(generating).toBeFocused();
    await expect(generating).toHaveAttribute('aria-disabled', 'true');
    gen.open();
    await expect(page.getByRole('button', { name: 'Generate', exact: true })).toBeVisible();
  });
});

test.describe('pass 163: support new ticket', () => {
  function stubTickets(page, start) {
    let list = start;
    return page.route('**/api/support/tickets', (r) => {
      if (r.request().method() === 'POST') {
        const t = { id: 16341, ...JSON.parse(r.request().postData() || '{}'), status: 'OPEN', createdAt: Date.now(), updatedAt: Date.now() };
        list = [t, ...list];
        return r.fulfill(json(t));
      }
      return r.fulfill(json(list));
    });
  }
  const old = { id: 16340, subject: 'Old payout question', category: 'PAYMENT', status: 'RESOLVED', createdAt: Date.now() - 86_400_000, updatedAt: Date.now() - 86_400_000 };

  test('a filed ticket takes focus on its own row', async ({ page }) => {
    await signedIn(page);
    await stubTickets(page, [{ ...old, status: 'OPEN' }]);
    await page.goto('/support');
    await page.getByRole('button', { name: /New Ticket/ }).first().click({ timeout: 20_000 });
    await page.getByRole('textbox', { name: 'Ticket subject' }).fill('Where is my item');
    await page.getByRole('textbox', { name: 'Ticket message body' }).fill('It never arrived');
    await page.getByRole('button', { name: 'Submit Ticket' }).click();
    await expect(page.getByRole('button', { name: /Open ticket #16341/ })).toBeFocused();
  });

  test('a ticket filed from the Resolved filter is shown, not "No tickets in this filter."', async ({ page }) => {
    await signedIn(page);
    await stubTickets(page, [old]);
    await page.goto('/support');
    await page.getByRole('button', { name: /^Resolved/ }).click({ timeout: 20_000 });
    await expect(page.getByRole('button', { name: /Open ticket #16340/ })).toBeVisible();
    await page.getByRole('button', { name: /New Ticket/ }).first().click();
    await page.getByRole('textbox', { name: 'Ticket subject' }).fill('Where is my item');
    await page.getByRole('textbox', { name: 'Ticket message body' }).fill('It never arrived');
    await page.getByRole('button', { name: 'Submit Ticket' }).click();
    await expect(page.getByRole('button', { name: /Open ticket #16341/ })).toBeVisible();
    await expect(page.getByText(/No tickets in this filter/)).toHaveCount(0);
  });
});

test.describe('pass 163: Settings (real app)', () => {
  test('Reset UI preferences names what it resets', async ({ page }) => {
    await consent(page);
    await page.addInitScript(() => {
      window.__asked = [];
      window.confirm = (m) => { window.__asked.push(String(m)); return false; };
    });
    await noWrites(page);
    await devLogin(page);
    await page.goto(`${base}/settings`);
    await page.getByRole('button', { name: 'Reset UI preferences' }).click({ timeout: 20_000 });
    const asked = await page.evaluate(() => window.__asked.join('\n'));
    expect(asked).toMatch(/hidden \$ amounts/);
    expect(asked).toMatch(/sale notifications/);
    // There is no theme picker ("Dark · mono").
    expect(asked).not.toMatch(/theme/);
  });
});

test.describe('pass 163: market filters at 390px (real app)', () => {
  test('the result count names the rarity picked in the filter drawer', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await consent(page);
    await page.goto(`${base}/market`);
    const fab = page.getByRole('button', { name: 'Open filters' });
    await fab.click({ timeout: 20_000 });
    await page.getByRole('radio', { name: /^Limited/ }).click();
    const meta = page.locator('.results-meta').first();
    await expect(meta).toHaveAttribute('role', 'status');
    await expect(meta).toContainText(/items? found.*Limited/);
    await page.getByRole('button', { name: 'Clear Filters' }).click();
    await expect(meta).not.toContainText(/Limited/);
  });
});
