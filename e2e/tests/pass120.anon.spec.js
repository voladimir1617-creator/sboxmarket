// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 120.

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

// Pass 120: Loadout Lab delete/rename focus, inline Save buttons, Report listing
// Ctrl+Enter refusal, item-page buy-orders chip state, tablet nav focus ring.

const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';
const consent = (page) => page.addInitScript(() => {
  try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {}
  window.confirm = () => true;
});
const devLogin = (page, userId = 1) => page.request.get(`${base}/api/auth/steam/dev-login?userId=${userId}&next=/`);
const refuse = (msg) => ({ status: 400, contentType: 'application/json', body: JSON.stringify({ error: msg, code: 'INVALID_PARAMETER' }) });

async function createLoadout(page, name) {
  await page.goto(`${base}/loadout?view=mine`);
  await page.getByRole('button', { name: /^\+ Create$/ }).click();
  await page.getByRole('textbox', { name: 'New loadout name' }).fill(name);
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(/\/loadout\/\d+$/);
  await expect(page.getByRole('button', { name: /Rename/ })).toBeVisible();
}

test.describe('Loadout Lab builder (real app)', () => {
  test('Delete lands on My Loadouts with its tab focused', async ({ page }) => {
    await consent(page);
    await devLogin(page);
    await createLoadout(page, 'Pass120 delete me');
    await page.getByRole('button', { name: 'Delete', exact: true }).focus();
    await page.keyboard.press('Enter');
    await expect(page.getByText(/Loadout "Pass120 delete me" deleted/)).toBeVisible();
    const mine = page.getByRole('tab', { name: 'My Loadouts' });
    await expect(mine).toHaveAttribute('aria-selected', 'true');
    await expect(mine).toBeFocused();
    await expect(page).toHaveURL(/\/loadout\?view=mine$/);
  });

  test('a refused rename keeps focus on Save', async ({ page }) => {
    await consent(page);
    await devLogin(page);
    await createLoadout(page, 'Pass120 rename me');
    await page.getByRole('button', { name: /Rename/ }).click();
    await page.getByRole('textbox', { name: 'New loadout name' }).fill('Pass120 renamed');
    await page.route('**/api/loadouts/*', (r) => (r.request().method() === 'PUT' ? r.fulfill(refuse('Name refused')) : r.fallback()));
    const save = page.getByRole('button', { name: 'Save', exact: true });
    await save.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByText('Name refused')).toBeVisible();
    await expect(save).toBeFocused();
    await expect(page.getByRole('textbox', { name: 'New loadout name' })).toHaveValue('Pass120 renamed');
  });
});

test.describe('Loadout Lab admin remove (stubbed)', () => {
  test('Admin remove lands on Discover with its tab focused', async ({ page }) => {
    await consent(page);
    await signedIn(page);
    await page.route('**/api/auth/steam/me', (r) => r.fulfill(json({ ...ME, role: 'ADMIN' })));
    await page.route('**/api/admin/check', (r) => r.fulfill(json({ admin: true })));
    await page.route('**/api/loadouts/discover**', (r) => r.fulfill(json([])));
    await page.route('**/api/loadouts/77', (r) => r.fulfill(json({
      loadout: { id: 77, name: 'Rude set', ownerUserId: 99, ownerName: 'bob', visibility: 'PUBLIC', favorites: 0, totalValue: 0 },
      slots: [], favorited: false,
    })));
    await page.route('**/api/admin/loadouts/77', (r) => r.fulfill(json({ ok: true })));
    await page.goto('/loadout/77');
    await page.getByRole('button', { name: 'Admin remove' }).click();
    await page.getByRole('button', { name: 'Remove loadout' }).click();
    const discover = page.getByRole('tab', { name: 'Discover' });
    await expect(discover).toHaveAttribute('aria-selected', 'true');
    await expect(discover).toBeFocused();
  });
});

test.describe('inline Save buttons keep focus on a refusal (real app)', () => {
  test('stall bio Save keeps focus when the save is refused', async ({ page }) => {
    await consent(page);
    await devLogin(page);
    await page.goto(`${base}/stall/1`);
    await page.getByRole('button', { name: /Add a bio|Edit/ }).first().click();
    await page.getByRole('textbox', { name: 'Stall bio' }).fill('Fast trades, evenings.');
    await page.route('**/api/profile/stall-bio', (r) => r.fulfill(refuse('Bio refused')));
    const save = page.getByRole('button', { name: 'Save', exact: true });
    await save.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('alert').filter({ hasText: 'Bio refused' })).toBeVisible();
    await expect(save).toBeFocused();
  });

  test('trade URL Save keeps focus when the server refuses it', async ({ page }) => {
    await consent(page);
    await devLogin(page);
    await page.goto(`${base}/profile/personal`);
    await page.getByRole('button', { name: /^(Add|Edit) Steam trade URL$/ }).click();
    await page.getByRole('textbox', { name: 'Steam trade URL' }).fill('https://steamcommunity.com/tradeoffer/new/?partner=12345&token=abcDEF12');
    await page.route('**/api/profile/trade-url**', (r) => r.fulfill(refuse('Trade URL refused')));
    const save = page.getByRole('button', { name: 'Save', exact: true });
    await save.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByText('Trade URL refused')).toBeVisible();
    await expect(save).toBeFocused();
  });
});

test.describe('item page dialogs and rows (real app)', () => {
  test('Report listing: a refused Ctrl+Enter send keeps focus in the note box', async ({ page }) => {
    await consent(page);
    await devLogin(page);
    await page.goto(`${base}/item/12`);
    const report = page.getByRole('button', { name: /^Report listing \d+$/ }).first();
    await report.scrollIntoViewIfNeeded();
    await report.click();
    const note = page.getByRole('textbox', { name: 'Optional moderator note' });
    await note.fill('Looks like a duplicate');
    await page.route('**/api/listings/*/report', (r) => r.fulfill(refuse('Report refused')));
    await note.press('Control+Enter');
    await expect(page.getByRole('dialog').getByRole('alert')).toHaveText(/Report refused/);
    await expect(note).toBeFocused();
    await expect(note).toHaveValue('Looks like a duplicate');
  });
});

test.describe('header nav at tablet width (real app)', () => {
  test.use({ viewport: { width: 1024, height: 768 } });
  test('a focused nav link shows its whole focus ring', async ({ page }) => {
    await consent(page);
    await page.goto(`${base}/loadout`);
    for (const name of ['Market', 'Help']) {
      const link = page.locator('nav.nav .nav-links').getByRole('link', { name, exact: true });
      await link.focus();
      await page.keyboard.press('Shift+Tab');
      await page.keyboard.press('Tab');
      await expect(link).toBeFocused();
      const clipped = await link.evaluate((a) => {
        const s = getComputedStyle(a);
        const grow = parseFloat(s.outlineOffset) + parseFloat(s.outlineWidth);
        const r = a.getBoundingClientRect();
        const c = a.closest('.nav-links').getBoundingClientRect();
        return { top: r.top - grow < c.top - 0.5, left: r.left - grow < c.left - 0.5, bottom: r.bottom + grow > c.bottom + 0.5 };
      });
      expect(clipped, name).toEqual({ top: false, left: false, bottom: false });
    }
  });
});
