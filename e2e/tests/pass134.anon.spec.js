// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 134.

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

// Defect pass 134: Loadout Lab slot lock buttons anchored to their own slot,
// the slot picker says it is loading instead of "No items match.", picker
// Cancel buttons name their slot, Profile > Offers row text keeps its word
// breaks for screen readers.

const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';

// Real-jar tests never write: every POST/PUT/DELETE/PATCH is answered here.
async function realSignIn(page, userId = 2) {
  await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
  await page.route('**/api/**', (r) => (['POST', 'PUT', 'DELETE', 'PATCH'].includes(r.request().method())
    ? r.fulfill(json({}))
    : r.fallback()));
  await page.request.get(`${base}/api/auth/steam/dev-login?userId=${userId}&next=/`);
  const me = await (await page.request.get(`${base}/api/auth/steam/me`)).json();
  return me.id;
}

// A loadout owned by the signed-in viewer, served in place of a real one so
// nothing is created on the server.
const ownLoadout = (ownerUserId, slots) => ({
  loadout: { id: 990134, name: 'Pass134 fit', ownerUserId, ownerName: 'me', visibility: 'PRIVATE', favorites: 0, totalValue: 4 },
  slots,
});
const SLOTS = [
  { slot: 'Hats', itemId: 1, itemName: 'Short Parted Hair', currentPrice: 2, locked: true },
  { slot: 'Boots', itemId: 2, itemName: 'Boot', currentPrice: 2, locked: false },
];

async function lockInsideSlot(page, name, slotName) {
  const lock = page.getByRole('button', { name });
  await lock.scrollIntoViewIfNeeded();
  const r = await lock.evaluate((el, slotName) => {
    const slot = [...document.querySelectorAll('.loadout-slot')].find((s) => s.querySelector('.loadout-slot-label')?.textContent === slotName);
    const b = el.getBoundingClientRect(), s = slot.getBoundingClientRect();
    const hit = document.elementFromPoint(b.left + b.width / 2, b.top + b.height / 2);
    return { inside: b.left >= s.left && b.right <= s.right && b.top >= s.top && b.bottom <= s.bottom, hitsSelf: el.contains(hit) };
  }, slotName);
  expect(r).toEqual({ inside: true, hitsSelf: true });
}

test.describe('pass134 · Loadout Lab slot lock buttons (real jar)', () => {
  test('each slot.s Lock/Unlock button sits inside its own slot, not stacked at the top of the page', async ({ page }) => {
    const myId = await realSignIn(page);
    await page.route('**/api/loadouts/990134', (r) => r.fulfill(json(ownLoadout(myId, SLOTS))));
    await page.goto(`${base}/loadout/990134`);
    await expect(page.getByRole('heading', { level: 1, name: 'Pass134 fit' })).toBeVisible({ timeout: 20_000 });
    await lockInsideSlot(page, 'Unlock Hats slot', 'Hats');
    await lockInsideSlot(page, 'Lock Boots slot', 'Boots');
  });
});

test.describe('pass134 · Loadout Lab lock buttons on a phone (stubbed)', () => {
  test.use({ viewport: { width: 390, height: 844 } });
  test('the Hats lock is in the Hats slot at phone width', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/loadouts/5', (r) => r.fulfill(json(ownLoadout(ME.id, SLOTS))));
    await page.goto('/loadout/5');
    await expect(page.getByRole('heading', { level: 1, name: 'Pass134 fit' })).toBeVisible();
    await lockInsideSlot(page, 'Unlock Hats slot', 'Hats');
    await lockInsideSlot(page, 'Lock Boots slot', 'Boots');
  });
});

test.describe('pass134 · Loadout Lab slot picker (real jar)', () => {
  test('while the catalogue loads the picker says so instead of "No items match."', async ({ page }) => {
    const myId = await realSignIn(page);
    await page.route('**/api/loadouts/990134', (r) => r.fulfill(json(ownLoadout(myId, []))));
    // The real catalogue, answered late.
    await page.route('**/api/database?**', async (r) => {
      const res = await r.fetch();
      await new Promise((ok) => setTimeout(ok, 2500));
      await r.fulfill({ response: res });
    });
    await page.goto(`${base}/loadout/990134`);
    await page.getByRole('button', { name: 'Add an item to the Hats slot' }).click({ timeout: 20_000 });
    const picker = page.locator('.loadout-picker');
    await expect(picker.getByText(/Loading items/)).toBeVisible();
    await expect(picker.getByText('No items match.')).toHaveCount(0);
    await expect(picker.getByRole('button', { name: /^Add .+ to the Hats slot/ }).first()).toBeVisible({ timeout: 15_000 });
    await expect(picker.getByText(/Loading items/)).toHaveCount(0);
  });

  test('a filter that matches nothing still says "No items match."', async ({ page }) => {
    const myId = await realSignIn(page);
    await page.route('**/api/loadouts/990134', (r) => r.fulfill(json(ownLoadout(myId, []))));
    await page.goto(`${base}/loadout/990134`);
    await page.getByRole('button', { name: 'Add an item to the Hats slot' }).click({ timeout: 20_000 });
    await expect(page.locator('.loadout-picker-item').first()).toBeVisible({ timeout: 15_000 });
    await page.getByRole('textbox', { name: 'Filter Hats items' }).fill('zzzz-nothing');
    await expect(page.locator('.loadout-picker').getByText('No items match.')).toBeVisible();
  });

  test('two open pickers have Cancel buttons that name their slot', async ({ page }) => {
    const myId = await realSignIn(page);
    await page.route('**/api/loadouts/990134', (r) => r.fulfill(json(ownLoadout(myId, []))));
    await page.goto(`${base}/loadout/990134`);
    await page.getByRole('button', { name: 'Add an item to the Hats slot' }).click({ timeout: 20_000 });
    await page.getByRole('button', { name: 'Add an item to the Boots slot' }).click();
    await expect(page.getByRole('button', { name: 'Cancel', exact: true })).toHaveCount(0);
    const cancelBoots = page.getByRole('button', { name: 'Cancel adding to the Boots slot' });
    await expect(cancelBoots).toHaveText('Cancel');
    await cancelBoots.click();
    await expect(page.getByRole('textbox', { name: 'Filter Boots items' })).toHaveCount(0);
    await expect(page.getByRole('textbox', { name: 'Filter Hats items' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Add an item to the Boots slot' })).toBeFocused();
  });
});

test.describe('pass134 · Profile > Offers row text (real jar)', () => {
  test('the age, ask and expiry read as separate words', async ({ page }) => {
    await realSignIn(page);
    const now = Date.now();
    const offer = { id: 1, listingId: 101, itemName: 'Leather Coat', amount: 4, askingPrice: 5, status: 'PENDING', author: 'BUYER', buyerName: 'bob', createdAt: now - 3_600_000, expiresAt: now + 5 * 86_400_000 };
    await page.route('**/api/offers/incoming', (r) => r.fulfill(json([offer])));
    await page.route('**/api/offers/outgoing', (r) => r.fulfill(json([{ ...offer, id: 2, itemName: 'Chef Hat' }])));
    await page.goto(`${base}/profile/offers`);
    const panel = page.getByRole('tabpanel');
    const sub = panel.locator('.offer-sub').first();
    await expect(sub).toBeVisible({ timeout: 20_000 });
    await expect(sub).toHaveText(/ago Ask: \$5\.00 · expires in 5d/);
    await page.getByRole('tab', { name: /Outgoing/ }).click();
    await expect(panel.locator('.offer-sub').first()).toHaveText(/Your offer · 1h ago Ask: \$5\.00 · expires in 5d/);
  });
});
