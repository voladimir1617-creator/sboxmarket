// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 73.

const ME = { id: 3, displayName: 'carol', role: 'USER', signedIn: true, sessionEpoch: 0, tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc' };
const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });

async function signedIn(page) {
  await page.addInitScript(() => { window.confirm = () => true; });
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json(ME)));
}

test.describe('defect pass 73', () => {

  // ---- 1
  const off = (id, author = 'BUYER') => ({ id, listingId: id, itemId: id, itemName: 'Red Hat ' + id, amount: '8.00', askingPrice: '10.00', status: 'PENDING', author, createdAt: Date.now() - id * 1000 });
  test('profile offers toolbar reachable at 390px', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await signedIn(page);
    await page.route('**/api/offers/incoming*', (r) => r.fulfill(json([])));
    await page.route('**/api/offers/outgoing*', (r) => r.fulfill(json([off(1), off(2), off(3)])));
    await page.goto('/profile/offers');
    await page.getByRole('tab', { name: /Outgoing/ }).click({ timeout: 20000 });
    await expect(page.getByRole('button', { name: '✕ Cancel all pending' })).toBeInViewport();
    await expect(page.getByRole('link', { name: '⇣ CSV' })).toBeInViewport();
    await expect(page.getByLabel('Sort offers')).toBeInViewport();
  });

  // ---- 2
  async function twofa(page) {
    await signedIn(page);
    await page.route('**/api/profile/me', (r) => r.fulfill(json({ user: { ...ME, email: 'c@x.io', emailVerified: true }, twoFactorEnabled: true })));
    await page.route('**/api/profile/2fa/recovery-status', (r) => r.fulfill(json({ remainingCodes: 8 })));
    await page.goto('/profile/personal');
  }
  test('disable 2FA drawer focuses code input', async ({ page }) => {
    await twofa(page);
    await page.getByRole('button', { name: 'Disable', exact: true }).click({ timeout: 20000 });
    await expect(page.getByLabel('6-digit authenticator code or backup code')).toBeFocused();
  });
  test('regenerate codes drawer focuses code input', async ({ page }) => {
    await twofa(page);
    await page.getByRole('button', { name: 'Regenerate codes' }).click({ timeout: 20000 });
    await expect(page.getByLabel('6-digit authenticator code', { exact: true })).toBeFocused();
  });

  // ---- 3
  const listing3 = (id) => ({ id, price: 5, listingType: 'BUY_NOW', status: 'ACTIVE', hidden: false, item: { id: 100 + id, name: `Stall Hat ${id}`, category: 'Hats', rarity: 'Standard' } });
  test('my stall: failed price edit refreshes the list', async ({ page }) => {
    await signedIn(page);
    let rows = [listing3(1)];
    await page.route('**/api/listings/my-stall', (r) => r.fulfill(json(rows)));
    await page.route('**/api/listings/1/stall', (r) => { rows = []; return r.fulfill(json({ code: 'LISTING_NOT_ACTIVE', message: 'Listing is no longer active (SOLD)', error: 'Listing is no longer active (SOLD)' }, 409)); });
    await page.goto('/me/stall');
    await page.getByRole('button', { name: '✎ Edit' }).click({ timeout: 20000 });
    await page.getByLabel('Price in US dollars').fill('6');
    await page.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(page.getByText(/no longer active/).first()).toBeVisible();
    await expect(page.getByLabel('Price in US dollars')).toHaveCount(0);
    await expect(page.getByText('Stall Hat 1')).toHaveCount(0);
  });

  // ---- 4
  test('profile offers: failed counter closes the form and refreshes the row', async ({ page }) => {
    await signedIn(page);
    let status = 'PENDING';
    await page.route('**/api/offers/incoming*', (r) => r.fulfill(json([
      { id: 42, listingId: 9, itemId: 5, itemName: 'Red Hat', amount: '8.00', askingPrice: '10.00', status, author: 'BUYER', buyerName: 'bob', createdAt: Date.now() },
    ])));
    await page.route('**/api/offers/outgoing*', (r) => r.fulfill(json([])));
    await page.route('**/api/offers/42/counter', (r) => { status = 'CANCELLED'; return r.fulfill(json({ code: 'OFFER_NOT_PENDING', message: 'This offer is no longer pending (CANCELLED)' }, 409)); });
    await page.goto('/profile/offers');
    await page.getByRole('button', { name: 'Counter offer' }).click({ timeout: 20000 });
    await page.getByLabel('Counter price in USD').fill('9');
    await page.getByRole('button', { name: 'Send counter' }).click();
    await expect(page.getByText(/no longer pending/).first()).toBeVisible();
    await expect(page.getByRole('button', { name: 'Accept offer' })).toHaveCount(0);
    await expect(page.getByLabel('Counter price in USD')).toHaveCount(0);
  });

  // ---- 5
  const listing5 = (id) => ({ id, price: 5, listingType: 'BUY_NOW', status: 'ACTIVE', hidden: true, item: { id: 100 + id, name: `Stall Hat ${id}`, category: 'Hats', rarity: 'Standard' } });
  test('my stall: Schedule return puts the caret in the date box', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings/my-stall', (r) => r.fulfill(json([listing5(1)])));
    await page.route('**/api/listings/away', (r) => r.fulfill(json({ hidden: true, until: null })));
    await page.goto('/me/stall');
    await page.getByRole('button', { name: 'Schedule return' }).click({ timeout: 20000 });
    await expect(page.getByLabel('Stall return date')).toBeFocused();
  });

  // ---- 6
  test('profile: a failed profile load does not claim 2FA is off and the trade URL is unset', async ({ page }) => {
    await signedIn(page);
    let fail = true;
    await page.route('**/api/profile/me', (r) => fail
      ? r.fulfill(json({ error: 'boom' }, 500))
      : r.fulfill(json({ user: { id: 3, displayName: 'carol', email: 'c@x.io', emailVerified: true, tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc' }, twoFactorEnabled: true, stats: { totalSold: 12, totalPurchased: 3, net: 9 } })));
    await page.goto('/profile/personal');
    await expect(page.getByText('Display Name')).toBeVisible({ timeout: 20000 });
    await page.waitForTimeout(1500);
    await expect(page.getByRole('button', { name: 'Enable 2FA' })).toHaveCount(0);
    await expect(page.getByText('(not set)')).toHaveCount(0);
    fail = false;
    await page.getByRole('alert').filter({ hasText: /load your profile/i }).getByRole('button', { name: 'Retry' }).click();
    await expect(page.getByText('Enabled', { exact: true })).toBeVisible();
  });

  // ---- 7
  const lo = (id, name, ownerUserId, ownerName) => ({ loadout: { id, name, ownerUserId, ownerName, favorites: 0, visibility: id === 4 ? 'PUBLIC' : 'PRIVATE' }, slots: [], favorited: false });
  test('cloning a loadout opens the copy at its own URL', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/loadouts/4', (r) => r.fulfill(json(lo(4, 'Bob Fit', 2, 'bob'))));
    await page.route('**/api/loadouts/9', (r) => r.fulfill(json(lo(9, 'Bob Fit (copy)', 3, 'carol'))));
    await page.route('**/api/loadouts/4/clone', (r) => r.fulfill(json({ id: 9, name: 'Bob Fit (copy)' })));
    await page.goto('/loadout/4');
    await page.getByRole('button', { name: '⎘ Clone' }).click({ timeout: 20000 });
    await expect(page.getByText('Bob Fit (copy)').first()).toBeVisible();
    await expect(page).toHaveURL(/\/loadout\/9$/);
  });
  test('creating a loadout opens it at its own URL', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/loadouts/discover*', (r) => r.fulfill(json([])));
    await page.route(/\/api\/loadouts$/, (r) => r.fulfill(json({ id: 9, name: 'New Fit' })));
    await page.route('**/api/loadouts/9', (r) => r.fulfill(json(lo(9, 'New Fit', 3, 'carol'))));
    await page.goto('/loadout');
    await page.getByRole('button', { name: '+ Create' }).click({ timeout: 20000 });
    await page.keyboard.type('New Fit');
    await page.keyboard.press('Enter');
    await expect(page.getByText(/Created loadout/).first()).toBeVisible();
    await expect(page).toHaveURL(/\/loadout\/9$/);
  });

});
