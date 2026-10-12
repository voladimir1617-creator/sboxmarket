// @ts-check
const { test, expect } = require('@playwright/test');

// Three things a real buyer hit on a click-through of the shop (2026-10-12).
//
// 1. Buying from the item page left the page reading "Item not found · has
//    been removed or never existed" -- right under the "Bought ..." toast, on
//    an item that still had other copies for sale.
// 2. The trade countdown read "2d left" a second after the buy dialog said the
//    seller has 3 days, and accepting the trade quietly restarted that clock.
// 3. Profile > Email: pressing Verify with the code box empty did nothing.
//
// Signed in as dev user 1 (auth.setup.js gives it a trade URL and test money).

async function csrfHeaders(page) {
  const csrf = (await page.context().cookies()).find((c) => c.name === 'sbox_csrf')?.value || '';
  return { 'X-CSRF-Token': csrf, 'Content-Type': 'application/json' };
}

async function activeListings(page) {
  const res = await page.request.get('/api/listings?sort=price_asc');
  expect(res.ok()).toBeTruthy();
  return (await res.json()).filter((l) => l.status === 'ACTIVE' && l.listingType !== 'AUCTION');
}

test('buying on the item page keeps the item page up', async ({ page }) => {
  const me = await (await page.request.get('/api/auth/steam/me')).json();
  const all = await activeListings(page);
  // A cheap copy SkinBox holds itself (no trade needed) of an item that has
  // at least one other copy for sale, so there is still a page to show.
  const perItem = {};
  for (const l of all) (perItem[l.item.id] = perItem[l.item.id] || []).push(l);
  const target = all.find((l) => l.sellerUserId == null && Number(l.price) < 5 && perItem[l.item.id].length > 1);
  test.skip(!target, 'no cheap in-platform listing of a multi-listing item left in this database');

  // Make the target the cheapest copy so the page's main Buy button is it.
  const cheapest = perItem[target.item.id][0];
  expect(cheapest.sellerUserId === me.id, 'cheapest copy must not be our own').toBeFalsy();

  await page.goto(`/item/${target.item.id}`);
  await page.getByRole('button', { name: /Buy now/i }).first().click();
  await page.getByRole('button', { name: /Confirm purchase/ }).click();

  await expect(page.getByText(/^Bought "/).first()).toBeVisible({ timeout: 15_000 });
  await expect(page).toHaveURL(new RegExp(`/item/${target.item.id}$`));
  await expect(page.getByTestId('item-not-found')).toHaveCount(0);
  await expect(page.getByRole('heading', { name: target.item.name }).first()).toBeVisible();
});

test('the seller window counts down from the purchase and shows hours', async ({ page }) => {
  const me = await (await page.request.get('/api/auth/steam/me')).json();
  const p2p = (await activeListings(page)).find(
    (l) => l.sellerUserId != null && l.sellerUserId !== me.id && Number(l.price) < 5
  );
  test.skip(!p2p, 'no cheap player listing left in this database');

  const buy = await page.request.post(`/api/listings/${p2p.id}/buy`, { headers: await csrfHeaders(page), data: {} });
  expect(buy.ok(), `buy should succeed (got ${buy.status()})`).toBeTruthy();

  await page.goto('/profile/trades');
  const card = page.locator('.trade-row, [class*="trade-card"], li, div')
    .filter({ hasText: p2p.item.name }).filter({ hasText: 'Awaiting seller accept' }).last();
  // 3 days from a purchase made seconds ago: "2d 23h left", never a bare "2d left".
  await expect(card).toContainText(/2d 23h left/, { timeout: 15_000 });
});

test('Verify with an empty code box says what to do', async ({ page }) => {
  // Show the profile as having an unverified address without changing the
  // shared dev user's real email (other specs read that account too).
  await page.route('**/api/profile/me', async (route) => {
    const res = await route.fetch();
    const body = await res.json();
    body.user = { ...(body.user || {}), email: 'e2e@example.com', emailVerified: false };
    await route.fulfill({ response: res, json: body });
  });
  await page.goto('/profile');
  const row = page.locator('.profile-row').filter({ hasText: /^\s*Email/ }).first();
  await row.getByRole('button', { name: 'Verify', exact: true }).click();
  await expect(row).toContainText(/Paste the code from your verification email/);
});
