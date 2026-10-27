// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 85.

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

test.describe('defect pass 85 · mystall', () => {
  const L = (id, name) => ({ id, price: 3 + id, status: 'ACTIVE', listingType: 'BUY_NOW', createdAt: Date.now(), item: { id: 10 + id, name, category: 'Hats', rarity: 'Common', lowestPrice: 3 } });

  test('my stall: each listing row.s Edit / Hide / Link / Cancel button names its item', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings/my-stall*', (r) => r.fulfill(json([L(1, 'Red Beanie'), L(2, 'Blue Cap')])));
    await page.goto('/me/stall');
    await expect(page.getByText('Blue Cap').first()).toBeVisible();
    await expect(page.getByRole('button', { name: /Edit.*Blue Cap/ })).toHaveCount(1);
    await expect(page.getByRole('button', { name: /Hide.*Blue Cap/ })).toHaveCount(1);
    await expect(page.getByRole('button', { name: /link.*Blue Cap/i })).toHaveCount(1);
    await expect(page.getByRole('button', { name: /Cancel listing.*Blue Cap/ })).toHaveCount(1);
  });

  test('my stall: a rejected bulk price adjust is announced as an alert in the drawer', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings/my-stall', (r) => r.fulfill(json([L(1, 'Red Beanie'), L(2, 'Blue Cap')])));
    await page.route('**/api/listings/my-stall/bulk-adjust', (r) => r.fulfill(json({ error: 'Pricing service is down' }, 503)));
    await page.goto('/me/stall');
    await page.getByRole('button', { name: /Bulk price adjust/ }).click();
    const dlg = page.getByRole('dialog', { name: /Bulk price adjust/ });
    await dlg.getByRole('spinbutton', { name: 'Bulk adjust percent' }).fill('-10');
    await dlg.getByRole('button', { name: /-10% to 2/ }).click();
    await expect(dlg.getByText(/Pricing service is down/)).toBeVisible();
    await expect(dlg.getByRole('alert').filter({ hasText: /Pricing service is down/ })).toBeVisible();
  });
});

test.describe('defect pass 85 · loadout', () => {
  const LO = { loadout: { id: 5, name: 'Winter fit', ownerUserId: 3, ownerName: 'carol', visibility: 'PUBLIC', favorites: 0, totalValue: 0 }, slots: [] };

  test('loadout: each empty slot.s + Add button names its slot', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/loadouts/5', (r) => r.fulfill(json(LO)));
    await page.goto('/loadout/5');
    await expect(page.getByText('Winter fit').first()).toBeVisible();
    await expect(page.getByRole('button', { name: /Hats/ })).toHaveCount(1);
    await expect(page.getByRole('button', { name: /Boots/ })).toHaveCount(1);
  });

  for (const how of ['Cancel', 'Escape']) {
    test(`loadout: closing the slot item picker with ${how} returns focus to its + Add button`, async ({ page }) => {
      await signedIn(page);
      await page.route('**/api/loadouts/5', (r) => r.fulfill(json(LO)));
      await page.goto('/loadout/5');
      const add = page.locator('.loadout-slot', { hasText: 'Hats' }).getByRole('button');
      await add.click();
      const box = page.getByRole('textbox', { name: 'Filter Hats items' });
      await expect(box).toBeFocused();
      if (how === 'Escape') await box.press('Escape');
      else await page.locator('.loadout-slot', { hasText: 'Hats' }).getByRole('button', { name: 'Cancel' }).click();
      await expect(box).toHaveCount(0);
      await expect(page.locator('.loadout-slot', { hasText: 'Hats' }).getByRole('button')).toBeFocused();
      await expect(page.getByText('Winter fit').first()).toBeVisible();
    });
  }
});

test.describe('defect pass 85 · admin-users', () => {
  const U = (id, name) => ({ id, displayName: name, steamId64: '7656119800000000' + id, role: 'USER', banned: false, createdAt: Date.now() });

  test('admin users: the user detail drawer is a named modal dialog that takes focus', async ({ page }) => {
    await asAdmin(page);
    await page.route('**/api/admin/users**', (r) => r.fulfill(json([U(5, 'erin')])));
    await page.goto('/admin?tab=users');
    await page.getByRole('button', { name: 'View user detail for erin' }).click();
    const dlg = page.getByRole('dialog', { name: 'erin' });
    await expect(dlg).toBeVisible();
    await expect(dlg).toHaveAttribute('aria-modal', 'true');
    await expect.poll(() => dlg.evaluate((el) => el.contains(document.activeElement))).toBe(true);
  });
});

test.describe('defect pass 85 · market', () => {
  for (const how of ['Cancel', 'Escape']) {
    test(`market: closing the Save search name box with ${how} returns focus to Save search`, async ({ page }) => {
      await signedIn(page);
      await page.goto('/market?q=hat');
      const save = page.getByRole('button', { name: /Save search/ });
      await save.scrollIntoViewIfNeeded();
      await save.click();
      const box = page.getByRole('textbox', { name: 'Saved search name' });
      await expect(box).toBeFocused();
      if (how === 'Escape') await box.press('Escape');
      else await page.getByRole('dialog', { name: 'Name this preset' }).getByRole('button', { name: 'Cancel' }).click();
      await expect(box).toHaveCount(0);
      await expect(save).toBeFocused();
    });
  }
});

test.describe('defect pass 85 · personal', () => {
  const later = (r, res, ms) => new Promise((ok) => setTimeout(ok, ms)).then(() => r.fulfill(res));

  for (const [what, url, show, err, row] of [
    ['sign-ins', '**/api/profile/sign-in-history', /Show last 20 sign-ins/, /Couldn.t load your sign-ins/, [{ id: 1, createdAt: Date.now(), ipAddress: '10.0.0.7', userAgent: 'Chrome/120' }]],
    ['security events', '**/api/profile/security-activity', /Show recent security events/, /Couldn.t load your security events/, [{ id: 1, eventType: 'API_KEY_MINTED', createdAt: Date.now(), summary: 'Key bot-7 minted' }]],
  ]) {
    test(`personal: retrying failed ${what} shows Loading, not the old error`, async ({ page }) => {
      await signedIn(page);
      let calls = 0;
      await page.route(url, (r) => (++calls === 1 ? r.fulfill(json({ error: 'boom' }, 500)) : later(r, json(row), 4000)));
      await page.goto('/profile/personal');
      const btn = page.getByRole('button', { name: show });
      await btn.scrollIntoViewIfNeeded();
      await btn.click();
      const alert = page.getByRole('alert').filter({ hasText: err });
      await expect(alert).toBeVisible();
      const hide = page.getByRole('button', { name: 'Hide', exact: true });
      await hide.click();
      await btn.click();
      await expect(alert).toHaveCount(0, { timeout: 1500 });
      await expect(page.getByText('Loading…').first()).toBeVisible();
      await expect(page.getByText(/10\.0\.0\.7|Key bot-7 minted/)).toBeVisible();
    });
  }

  const F = (id) => ({ id: 100 + id, sellerUserId: id, notificationsMuted: false, createdAt: Date.now() });
  test('personal: unfollowing a seller moves focus to the next seller.s Unfollow button', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/follows?limit=200', (r) => r.fulfill(json([F(7), F(8), F(9)])));
    await page.route('**/api/sellers/names**', (r) => r.fulfill(json({ 7: 'dave', 8: 'erin', 9: 'fay' })));
    await page.goto('/profile/personal');
    const erin = page.getByRole('button', { name: 'Unfollow erin' });
    await erin.scrollIntoViewIfNeeded();
    await erin.click();
    await expect(erin).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Unfollow fay' })).toBeFocused();
    await page.getByRole('button', { name: 'Unfollow fay' }).press('Enter');
    await expect(page.getByRole('button', { name: 'Unfollow fay' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Unfollow dave' })).toBeFocused();
  });
});
