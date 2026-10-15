const { test, expect } = require('@playwright/test');

// /database: the category and rarity dropdowns both read just "All", so a
// visitor could not tell which was which; and an item with no listing showed
// a bare dash that a screen reader announced as nothing.
test('the database filters name what they filter, and an empty floor says why', async ({ page }) => {
  await page.route('**/api/database**', async (route) => {
    const res = await route.fetch();
    const body = await res.json();
    if (Array.isArray(body.items) && body.items.length) body.items[0].lowestPrice = 0;
    await route.fulfill({ response: res, json: body });
  });
  await page.goto('/database');
  await expect(page.getByLabel('Filter catalogue by category')).toHaveText(/All categories/, { timeout: 15_000 });
  await expect(page.getByLabel('Filter catalogue by category').locator('option:checked')).toHaveText('All categories');
  await expect(page.getByLabel('Filter catalogue by rarity').locator('option:checked')).toHaveText('All rarities');

  const floor = page.locator('.db-table tbody tr.db-row').first().locator('td').nth(7);
  await expect(floor).toContainText('No listings right now');
  await expect(floor.locator('[title="No listings right now"]')).toHaveCount(1);
});

// A signed-out visitor opening Buy Orders was told only to sign in, with
// nothing saying what a buy order does.
test('signed out, Buy Orders says what a buy order is above the sign-in prompt', async ({ page }) => {
  await page.goto('/buy-orders');
  await expect(page.getByText('Sign in required')).toBeVisible({ timeout: 15_000 });
  await expect(page.locator('.buy-orders-explainer')).toHaveText(
    'Set a standing "pay up to $X" on any item and SkinBox buys the next matching Buy Now listing from your wallet. No money is held while it waits.');
});

// The only buy-order question in the Help Center was why one expired.
test('the Help Center answers how buy orders work', async ({ page }) => {
  await page.goto('/help');
  const q = page.getByRole('button', { name: /How do buy orders work\?/ });
  await expect(q).toBeVisible({ timeout: 20_000 });
  await q.click();
  const body = page.locator('body');
  await expect(body).toContainText('a quantity up to 100');
  await expect(body).toContainText('Buy orders never buy auctions');
  await expect(body).toContainText('editing it restarts the clock');
});
