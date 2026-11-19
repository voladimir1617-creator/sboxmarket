// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 142.

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


// Defect pass 142. Every test runs against the real app. Writes are stubbed:
// a real-jar test must never create or change server data.

const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';
const LOADOUT_ID = 990142;

async function noWrites(page) {
  await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
  await page.route('**/api/**', (r) => (['POST', 'PUT', 'DELETE', 'PATCH'].includes(r.request().method()) ? r.fulfill(json({})) : r.fallback()));
}

async function devLogin(page, userId = 2) {
  await page.request.get(`${base}/api/auth/steam/dev-login?userId=${userId}&next=/`);
  const me = await (await page.request.get(`${base}/api/auth/steam/me`)).json();
  return me.id;
}

// A loadout owned by the signed-in viewer, served in place of a real one so
// nothing is created on the server.
async function ownLoadout(page) {
  const myId = await devLogin(page);
  await page.route(`**/api/loadouts/${LOADOUT_ID}`, (r) => (r.request().method() === 'GET'
    ? r.fulfill(json({
      loadout: { id: LOADOUT_ID, name: 'Pass142 fit', ownerUserId: myId, ownerName: 'me', visibility: 'PRIVATE', favorites: 0, totalValue: 0 },
      slots: [], favorited: false,
    }))
    : r.fulfill(json({}))));
  await page.goto(`${base}/loadout/${LOADOUT_ID}`);
  await expect(page.getByRole('heading', { level: 1, name: 'Pass142 fit' })).toBeVisible({ timeout: 15_000 });
}

test.describe('Loadout Lab builder (real app, writes stubbed)', () => {
  test('the Wild slot picker lists the whole catalogue, not the first 20 items', async ({ page }) => {
    await noWrites(page);
    const db = await (await page.request.get(`${base}/api/database?limit=500&sort=most_traded`)).json();
    const total = (db.items || []).length;
    expect(total).toBeGreaterThan(20);
    await ownLoadout(page);
    await page.getByRole('button', { name: 'Add an item to the Wild slot' }).click();
    const items = page.locator('.loadout-picker-item');
    await expect(items.first()).toBeVisible({ timeout: 15_000 });
    await expect(items).toHaveCount(total);
  });

  test('Escape on a picker item closes the picker and returns to + Add', async ({ page }) => {
    await noWrites(page);
    await ownLoadout(page);
    const add = page.getByRole('button', { name: 'Add an item to the Hats slot' });
    await add.click();
    const item = page.locator('.loadout-picker-item').first();
    await expect(item).toBeVisible({ timeout: 15_000 });
    await item.focus();
    await page.keyboard.press('Escape');
    await expect(page.locator('.loadout-picker')).toHaveCount(0);
    await expect(add).toBeFocused();
  });

  test('Escape on the picker Cancel closes the picker and returns to + Add', async ({ page }) => {
    await noWrites(page);
    await ownLoadout(page);
    const add = page.getByRole('button', { name: 'Add an item to the Boots slot' });
    await add.click();
    await page.getByRole('button', { name: 'Cancel adding to the Boots slot' }).focus();
    await page.keyboard.press('Escape');
    await expect(page.locator('.loadout-picker')).toHaveCount(0);
    await expect(add).toBeFocused();
  });

  test('Escape on the rename Save button closes the rename box and returns to Rename', async ({ page }) => {
    await noWrites(page);
    await ownLoadout(page);
    const rename = page.getByRole('button', { name: /Rename/ });
    await rename.click();
    await page.getByRole('textbox', { name: 'New loadout name' }).fill('Pass142 renamed');
    await page.getByRole('button', { name: 'Save', exact: true }).focus();
    await page.keyboard.press('Escape');
    await expect(page.getByRole('textbox', { name: 'New loadout name' })).toHaveCount(0);
    await expect(rename).toBeFocused();
  });

  test('Escape on the Create button closes the new-loadout box and returns to + Create', async ({ page }) => {
    await noWrites(page);
    await devLogin(page);
    await page.goto(`${base}/loadout`);
    const create = page.getByRole('button', { name: '+ Create' });
    await create.click({ timeout: 15_000 });
    await page.getByRole('textbox', { name: 'New loadout name' }).fill('Pass142 never made');
    await page.getByRole('button', { name: 'Create', exact: true }).focus();
    await page.keyboard.press('Escape');
    await expect(page.getByRole('textbox', { name: 'New loadout name' })).toHaveCount(0);
    await expect(create).toBeFocused();
  });
});
