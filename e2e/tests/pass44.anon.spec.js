// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 44.

const ME = {
  id: 1,
  steamId64: '76561202255232729',
  displayName: 'Pass Tester',
  signedIn: true,
  sessionEpoch: 0,
};

const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });

async function signedIn(page) {
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json(ME)));
}

function steamRow() {
  return {
    assetId: '900044', assetIds: ['900044'], quantity: 1,
    name: 'Pass Auction Hat', type: 'Hat', iconUrl: null,
    tradable: true, marketable: true, category: 'Hats', rarity: 'Standard',
    catalogueId: 4444, suggestedPrice: 5, steamPrice: null,
    listedCount: 0, listableQuantity: 1, unlistableReason: null,
  };
}

test.describe('defect pass 44', () => {
  test('an Auto-accept value typed under Buy Now does not block an Auction listing', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/steam/inventory', (r) => r.fulfill(json({
      items: [steamRow()], count: 1, assetCount: 1, lastSyncedAt: Date.now(), steamId64: ME.steamId64,
    })));
    await page.route('**/api/items/*/recent-sales*', (r) => r.fulfill(json([])));
    await page.route('**/api/listings/item/*', (r) => r.fulfill(json([])));
    const posted = [];
    await page.route('**/api/steam/list', (r) => {
      posted.push(r.request().postDataJSON());
      return r.fulfill(json({ id: 1 }));
    });
    await page.goto('/sell');
    const card = page.locator('.inventory-item').first();
    await expect(card).toBeVisible({ timeout: 20_000 });
    await card.click();

    const buyNow = page.getByRole('button', { name: 'Buy Now', exact: true });
    const auction = page.getByRole('button', { name: 'Auction', exact: true });
    await expect(buyNow).toHaveAttribute('aria-pressed', 'true');
    await expect(auction).toHaveAttribute('aria-pressed', 'false');

    await page.getByLabel('Auto-accept discount percent').fill('60');
    await auction.click();
    await expect(auction).toHaveAttribute('aria-pressed', 'true');
    await expect(page.getByLabel('Auto-accept discount percent')).toHaveCount(0);
    await expect(page.getByRole('button', { name: '1d', exact: true })).toHaveAttribute('aria-pressed', 'true');
    await expect(page.getByRole('button', { name: '7d', exact: true })).toHaveAttribute('aria-pressed', 'false');

    await page.getByRole('button', { name: /List for Sale/i }).click();
    await expect(page.getByText('Auto-accept discount must be a whole number')).toHaveCount(0);
    await expect.poll(() => posted.length, { timeout: 10_000 }).toBeGreaterThan(0);
    expect(JSON.stringify(posted[0])).not.toContain('maxDiscount');
  });

  test('the ? shortcuts panel takes focus and lists g t and v', async ({ page }) => {
    await page.route('**/api/**', (r) => r.fulfill(json({})));
    await page.route('**/api/auth/steam/me', (r) => r.fulfill(json({ signedIn: false })));
    await page.goto('/market');
    await expect(page.locator('.sort-picker-chip')).toBeVisible({ timeout: 20_000 });
    await page.locator('body').click({ position: { x: 5, y: 5 } });
    await page.keyboard.press('Shift+Slash');
    const card = page.locator('.shortcuts-card');
    await expect(card).toBeVisible();
    await expect(card).toContainText('Go to Settings');
    await expect(card).toContainText('Toggle grid / table view');
    await expect.poll(() => page.evaluate(() => !!document.activeElement?.closest('.shortcuts-card'))).toBe(true);
    await page.keyboard.press('Tab');
    await expect.poll(() => page.evaluate(() => !!document.activeElement?.closest('.shortcuts-card'))).toBe(true);
    await page.keyboard.press('Escape');
    await expect(card).toHaveCount(0);
  });

  test('picking a sort with the keyboard returns focus to the Sort chip', async ({ page }) => {
    await page.route('**/api/**', (r) => r.fulfill(json({})));
    await page.route('**/api/auth/steam/me', (r) => r.fulfill(json({ signedIn: false })));
    await page.goto('/market');
    const chip = page.locator('.sort-picker-chip');
    await expect(chip).toBeVisible({ timeout: 20_000 });
    await chip.focus();
    await page.keyboard.press('Enter');
    const options = page.locator('.sort-picker-panel [role="option"]');
    await expect(options.first()).toBeVisible();
    await options.nth(1).focus();
    await page.keyboard.press('Enter');
    await expect(page.locator('.sort-picker-panel')).toHaveCount(0);
    await expect(chip).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(options.first()).toBeVisible();
    await options.first().focus();
    await page.keyboard.press('Escape');
    await expect(page.locator('.sort-picker-panel')).toHaveCount(0);
    await expect(chip).toBeFocused();
  });
});
