// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 52.

const ME = {
  id: 1,
  steamId64: '76561199000000001',
  displayName: 'Pass Tester',
  signedIn: true,
  sessionEpoch: 0,
  tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc',
};

const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });

async function signedIn(page) {
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json(ME)));
}

test.describe('defect pass 52', () => {
  test('the email box is named, capped at 254, saves on Enter once, and Escape closes it', async ({ page }) => {
    await signedIn(page);
    let puts = 0;
    await page.route('**/api/profile/email', async (r) => {
      if (r.request().method() !== 'PUT') return r.fallback();
      puts += 1;
      await new Promise((ok) => setTimeout(ok, 400));
      return r.fulfill(json({ email: 'new@example.com', verified: false }));
    });
    await page.goto('/profile');
    const row = page.locator('.profile-row', { hasText: 'Email' }).first();
    await row.getByRole('button', { name: 'Edit email address' }).click({ timeout: 20_000 });
    const box = page.getByRole('textbox', { name: 'Email address' });
    await expect(box).toHaveAttribute('maxlength', '254');
    await box.press('Escape');
    await expect(box).toHaveCount(0);
    await row.getByRole('button', { name: 'Edit email address' }).click();
    await box.fill('new@example.com');
    await box.press('Enter');
    await box.press('Enter').catch(() => {});
    await page.waitForTimeout(800);
    expect(puts).toBe(1);
  });

  test('Enter in the API key label mints the key', async ({ page }) => {
    await signedIn(page);
    let posts = 0;
    await page.route('**/api/api-keys', (r) => {
      if (r.request().method() !== 'POST') return r.fulfill(json([]));
      posts += 1;
      return r.fulfill(json({ key: { id: 1, label: 'bot', scope: 'RW', publicPrefix: 'sbx_live_ab' }, token: 'sbx_live_abc' }));
    });
    await page.goto('/profile/developers');
    const box = page.getByRole('textbox', { name: 'API key label' });
    await box.fill('bot', { timeout: 20_000 });
    await box.press('Enter');
    await expect.poll(() => posts).toBe(1);
  });

  test('saving a stall edit twice in a row sends one update', async ({ page }) => {
    await signedIn(page);
    const item = { id: 21, name: 'Stall Hat', category: 'Hats', rarity: 'Standard', lowestPrice: 3 };
    await page.route('**/api/listings/my-stall', (r) => r.fulfill(json([
      { id: 5201, price: 3, status: 'ACTIVE', listingType: 'BUY_NOW', listedAt: Date.now(), item, itemId: 21, sellerUserId: 1 },
    ])));
    let patches = 0;
    await page.route('**/api/listings/5201/stall', async (r) => {
      patches += 1;
      await new Promise((ok) => setTimeout(ok, 400));
      return r.fulfill(json({ id: 5201, price: 4 }));
    });
    await page.goto('/me/stall');
    await page.getByRole('button', { name: /^Edit price of / }).first().click({ timeout: 20_000 });
    const price = page.getByRole('textbox', { name: 'Price in US dollars' });
    await price.fill('4');
    await price.press('Enter');
    await price.press('Enter').catch(() => {});
    await page.waitForTimeout(800);
    expect(patches).toBe(1);
  });

  test('the stall auto-accept box is named', async ({ page }) => {
    await signedIn(page);
    const item = { id: 22, name: 'Stall Cap', category: 'Hats', rarity: 'Standard', lowestPrice: 3 };
    await page.route('**/api/listings/my-stall', (r) => r.fulfill(json([
      { id: 5202, price: 3, status: 'ACTIVE', listingType: 'BUY_NOW', listedAt: Date.now(), item, itemId: 22, sellerUserId: 1 },
    ])));
    await page.goto('/me/stall');
    await page.getByRole('button', { name: /^Edit price of / }).first().click({ timeout: 20_000 });
    await expect(page.getByRole('textbox', { name: /Auto-accept offers at this % below ask/ })).toBeVisible();
  });
});
