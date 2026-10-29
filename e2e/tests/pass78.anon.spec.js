// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 78.

const ME = { id: 3, displayName: 'carol', role: 'USER', signedIn: true, sessionEpoch: 0, tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc' };
const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });

async function signedIn(page) {
  await page.addInitScript(() => { window.confirm = () => true; });
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json(ME)));
}
async function asAdmin(page) {
  await signedIn(page);
  await page.route('**/api/admin/check', (r) => r.fulfill(json({ admin: true })));
}

test.describe('defect pass 78 · loadout', () => {
  const LO = { loadout: { id: 5, name: 'Winter Kit', ownerUserId: ME.id, ownerName: 'carol', favorites: 0, visibility: 'PUBLIC', totalValue: 0 }, slots: [], favorited: false };

  test('loadout: deleting an open loadout leaves its /loadout/:id URL', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/loadouts/5', (r) => r.request().method() === 'DELETE' ? r.fulfill(json({ ok: true })) : r.fulfill(json(LO)));
    await page.route('**/api/loadouts/mine', (r) => r.fulfill(json([])));
    await page.goto('/loadout/5');
    await page.getByRole('button', { name: 'Delete', exact: true }).click();
    await expect(page.getByText(/Loadout "Winter Kit" deleted/)).toBeVisible();
    await expect(page).toHaveURL(/\/loadout$/);
  });

  test('loadout: closing the rename box returns focus to Rename', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/loadouts/5', (r) => r.fulfill(json(LO)));
    await page.goto('/loadout/5');
    const rename = page.getByRole('button', { name: /Rename/ });
    await rename.click();
    const box = page.getByRole('textbox', { name: 'New loadout name' });
    await expect(box).toBeFocused();
    await page.keyboard.press('Escape');
    await expect(box).toHaveCount(0);
    await expect(page).toHaveURL(/\/loadout\/5$/);
    await expect(rename).toBeFocused();
    await rename.click();
    await page.getByRole('button', { name: 'Cancel', exact: true }).click();
    await expect(rename).toBeFocused();
  });

  test('loadout: Escape in the new-loadout box returns focus to + Create', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/loadouts/discover**', (r) => r.fulfill(json([])));
    await page.goto('/loadout');
    await page.getByRole('button', { name: '+ Create' }).click();
    const box = page.getByRole('textbox', { name: 'New loadout name' });
    await expect(box).toBeFocused();
    await page.keyboard.press('Escape');
    await expect(box).toHaveCount(0);
    await expect(page.getByRole('button', { name: '+ Create' })).toBeFocused();
  });
});

test.describe('defect pass 78 · nav', () => {

  test('currency picker: arrow keys move between currencies', async ({ page }) => {
    await signedIn(page);
    await page.goto('/market');
    const chip = page.getByRole('button', { name: /Currency selector/ });
    await chip.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('listbox')).toBeVisible();
    await page.keyboard.press('ArrowDown');
    await expect(page.getByRole('option', { name: /USD/ })).toBeFocused();
    await page.keyboard.press('ArrowDown');
    await expect(page.getByRole('option', { name: /EUR/ })).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(chip).toHaveAccessibleName(/EUR/);
    await expect(chip).toBeFocused();
  });
});

test.describe('defect pass 78 · staff', () => {
  const now = Date.now();
  const T = (id, subject) => ({ id, subject, category: 'TRADE', status: 'WAITING_STAFF', userId: 9, username: 'dave', updatedAt: now });

  // Older search answers slowly; the newer one fast. The list must show the newer.
  async function stubTickets(page, path) {
    await page.route('**/api/' + path + '**', (r) => {
      const u = new URL(r.request().url());
      if (!/\/tickets$/.test(u.pathname)) return r.fallback();
      const q = u.searchParams.get('search') || '';
      if (q === 'be') return new Promise((ok) => setTimeout(ok, 1500)).then(() => r.fulfill(json([T(1, 'Old beanie match')])));
      if (q === 'beanie') return r.fulfill(json([T(2, 'Red beanie never arrived')]));
      return r.fulfill(json([T(3, 'Unrelated ticket')]));
    });
  }

  test('admin tickets: a slow older search does not overwrite the newer results', async ({ page }) => {
    await asAdmin(page);
    await stubTickets(page, 'admin/tickets');
    await page.goto('/admin?tab=tickets');
    await expect(page.getByText('Unrelated ticket')).toBeVisible();
    const box = page.getByRole('textbox', { name: 'Search tickets' });
    await box.fill('be');
    await page.waitForTimeout(450);
    await box.fill('beanie');
    await expect(page.getByText('Red beanie never arrived')).toBeVisible();
    await page.waitForTimeout(1800);
    await expect(page.getByText('Red beanie never arrived')).toBeVisible();
    await expect(page.getByText('Old beanie match')).toHaveCount(0);
  });

  test('CSR tickets: a slow older search does not overwrite the newer results', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/csr/check', (r) => r.fulfill(json({ csr: true })));
    await stubTickets(page, 'csr/tickets');
    await page.goto('/csr?tab=tickets');
    await expect(page.getByText('Unrelated ticket')).toBeVisible();
    const box = page.getByRole('textbox', { name: 'Search tickets' });
    await box.fill('be');
    await page.waitForTimeout(450);
    await box.fill('beanie');
    await expect(page.getByText('Red beanie never arrived')).toBeVisible();
    await page.waitForTimeout(1800);
    await expect(page.getByText('Red beanie never arrived')).toBeVisible();
    await expect(page.getByText('Old beanie match')).toHaveCount(0);
  });
});

test.describe('defect pass 78 · mystall', () => {
  const ITEM = { id: 11, name: 'Red Beanie', category: 'Hats', rarity: 'Standard', lowestPrice: 5, isListed: true, supply: 1 };
  const L = { id: 101, price: 5, listingType: 'BUY_NOW', status: 'ACTIVE', item: ITEM, sellerUserId: ME.id, sellerName: 'carol', listedAt: Date.now() };

  test('my stall: a failed away-mode lookup is not shown as "listings visible"', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings/my-stall', (r) => r.fulfill(json([L])));
    await page.route('**/api/listings/away', (r) => r.request().method() === 'GET' ? r.fulfill(json({ error: 'boom' }, 500)) : r.fulfill(json({ hidden: true })));
    await page.goto('/me/stall');
    await expect(page.getByText('Red Beanie').first()).toBeVisible();
    const toolbar = page.locator('.stall-toolbar');
    await expect(toolbar).toContainText(/Away Mode/);
    await expect(toolbar).not.toContainText(/listings visible/);
    await expect(toolbar).toContainText(/couldn.t load/i);
    await expect(page.getByRole('switch', { name: /Away mode/ })).toBeDisabled();
  });
});

test.describe('defect pass 78 · stall', () => {
  const now = Date.now();
  const mk = (i, name, rarity) => ({ id: 200 + i, price: 3 + i, listingType: 'BUY_NOW', status: 'ACTIVE', sellerUserId: 9, sellerName: 'dave', listedAt: now,
    item: { id: 30 + i, name, category: 'Hats', rarity, lowestPrice: 3 + i, isListed: true } });
  const LISTINGS = [mk(0, 'Golden Crown', 'Limited')].concat(Array.from({ length: 11 }, (_, i) => mk(i + 1, 'Plain Cap ' + (i + 1), 'Standard')));

  test('stall: a name search under a rarity chip does not claim the stall has no match', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings/stall/9', (r) => r.fulfill(json({ seller: { id: 9, displayName: 'dave' }, count: LISTINGS.length, listings: LISTINGS })));
    await page.goto('/stall/9');
    await page.getByRole('button', { name: /^Limited · 1/ }).click();
    await page.getByRole('searchbox', { name: /Filter stall by item name/ }).fill('plain cap');
    const empty = page.getByText(/No .*listings match/);
    await expect(empty).toBeVisible();
    await expect(empty).not.toContainText(/in this stall/);
    await expect(empty).toContainText(/Limited/);
  });
});
