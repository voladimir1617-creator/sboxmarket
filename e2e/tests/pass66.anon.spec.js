// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 66.

const ME = {
  id: 1,
  steamId64: '76561199000000001',
  displayName: 'Pass Tester',
  signedIn: true,
  sessionEpoch: 0,
  tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc',
};

const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });
const slow = (body, ms = 400) => (r) => new Promise((ok) => setTimeout(ok, ms)).then(() => r.fulfill(json(body)));

async function signedIn(page) {
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json(ME)));
}
const loadout = { loadout: { id: 56, name: 'Mine', ownerUserId: 1, visibility: 'PUBLIC', totalValue: 5, favorites: 0 }, slots: [] };

test.describe('defect pass 66', () => {
  test('arrowing from Received to Given on Profile reviews keeps focus on the tabs', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/reviews/mine*', slow([], 800));
    await page.goto('/profile/reviews');
    const received = page.getByRole('tab', { name: /^Received/ });
    await received.focus({ timeout: 20_000 });
    await page.keyboard.press('ArrowRight');
    await expect(page.getByRole('tab', { name: /^Given/ })).toBeFocused();
  });

  test('a failed Loadout Lab load says so instead of "No public loadouts yet"', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/loadouts/discover*', (r) => r.fulfill(json({ error: 'boom' }, 500)));
    await page.goto('/loadout');
    await expect(page.getByText("Couldn't load loadouts").first()).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText('No public loadouts yet')).toHaveCount(0);
  });

  test('failed sign-in history and security events say they failed, not that nothing is recorded', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/profile/sign-in-history*', (r) => r.fulfill(json({ error: 'boom' }, 500)));
    await page.route('**/api/profile/security-activity*', (r) => r.fulfill(json({ error: 'boom' }, 500)));
    await page.goto('/profile');
    await page.getByRole('button', { name: /Show last 20 sign-ins/ }).click({ timeout: 20_000 });
    await expect(page.getByText(/Couldn't load your sign-ins/)).toBeVisible();
    await expect(page.getByText('No sign-in history recorded yet.')).toHaveCount(0);
    await page.getByRole('button', { name: /Show recent security events/ }).click();
    await expect(page.getByText(/Couldn't load your security events/)).toBeVisible();
    await expect(page.getByText('No security-relevant events on record yet.')).toHaveCount(0);
  });

  test('the Database heading and result line wait for the first load instead of showing zeros', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/database*', slow({
      items: [{ id: 1, name: 'Hat', category: 'Hats', rarity: 'Standard', supply: 5, lowestPrice: 2 }], total: 1, indexed: 39,
    }, 2000));
    await page.goto('/db');
    const head = page.locator('h1.info-modal-header');
    await expect(head).toHaveText('Database', { timeout: 20_000 });
    await expect(page.locator('.db-meta')).toHaveCount(0);
    await expect(head).toHaveText('Database · 39 indexed', { timeout: 10_000 });
  });

  test('a shared loadout link does not flash the Discover list while it loads', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/loadouts/discover*', (r) => r.fulfill(json([])));
    await page.route('**/api/loadouts/56', slow(loadout, 1500));
    await page.goto('/loadout/56');
    await page.waitForTimeout(600);
    expect(await page.getByText('No public loadouts yet').count()).toBe(0);
    await expect(page.getByText('Mine').first()).toBeVisible({ timeout: 10_000 });
  });

  test('the loadout rename row fits a phone screen', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await signedIn(page);
    await page.route('**/api/loadouts/56', (r) => r.fulfill(json(loadout)));
    await page.goto('/loadout/56');
    await page.getByRole('button', { name: /Rename/ }).first().click({ timeout: 20_000 });
    const cancel = page.getByRole('button', { name: 'Cancel', exact: true });
    const b = await cancel.boundingBox();
    expect(b && b.x + b.width).toBeLessThanOrEqual(390);
  });

  test('opening the stall bio editor puts the caret in the box', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings/stall/1*', (r) => r.fulfill(json({
      seller: { id: 1, displayName: 'Me' }, listings: [], count: 0, blockedByViewer: false,
    })));
    await page.goto('/stall/1');
    await page.getByRole('button', { name: /Add a bio/ }).click({ timeout: 20_000 });
    await expect(page.getByRole('textbox', { name: 'Stall bio' })).toBeFocused();
  });

  test('Loadout Lab "+ Create" puts the caret in the name box', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/loadouts/**', (r) => r.fulfill(json([])));
    await page.goto('/loadout');
    await page.getByRole('button', { name: /^\+ Create$/ }).first().click({ timeout: 20_000 });
    await expect(page.getByRole('textbox', { name: 'New loadout name' })).toBeFocused();
  });
});
