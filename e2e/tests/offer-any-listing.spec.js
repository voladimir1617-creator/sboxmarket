// @ts-check
const { test, expect } = require('@playwright/test');

// Make offer on ANY listing, not only the cheapest (signed in). Item 1 in the
// demo seed has three buy-now listings: #1 $0.54 (cheapest), #4 $0.60 and
// #2 $0.68. The offer drawer used to target the cheapest listing whatever the
// buyer wanted, so there was no way to bargain with the #2 seller. Each Active
// Listings row now has its own Make offer, aimed at that row's listing id.
//
// The POST is answered by the test (route.fulfill), so no offer is stored and
// no other spec sees a pending offer from this one.

test('Make offer on a row bargains on that row\'s listing', async ({ page }) => {
  let posted = null;
  await page.route('**/api/offers', async (route) => {
    if (route.request().method() !== 'POST') return route.continue();
    posted = JSON.parse(route.request().postData() || '{}');
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ id: 999999, listingId: posted.listingId, amount: posted.amount, status: 'PENDING' })
    });
  });

  await page.goto('/item/1');
  const row = page.locator('.modal-listing-row[data-listing-id="2"]');
  await expect(row).toBeVisible({ timeout: 15_000 });
  const offerBtn = row.getByTestId('listing-offer-btn');
  await expect(offerBtn).toBeVisible();
  await expect(offerBtn).toHaveAccessibleName(/Make an offer on listing #2/);
  await offerBtn.click();

  // The drawer names the row it targets and uses that row's asking price.
  await expect(page.getByTestId('offer-drawer-target')).toContainText('listing #2');
  await expect(page.locator('text=/must be below the \\$0\\.68 asking price/')).toBeVisible();

  // $0.65 is above the cheapest ask ($0.54) but below this row's ($0.68):
  // only an offer aimed at listing #2 accepts it.
  const input = page.locator('.item-offer-drawer input[inputmode="decimal"]');
  await input.fill('0.65');
  const send = page.getByRole('button', { name: /Send Offer/i });
  await expect(send).toBeEnabled();
  await send.click();
  await expect.poll(() => posted && posted.listingId).toBe(2);
  expect(posted.amount).toBeCloseTo(0.65, 2);
});

test('rail Bargain still targets the cheapest listing', async ({ page }) => {
  await page.goto('/item/1');
  await expect(page.locator('.modal-listing-row[data-listing-id="2"]')).toBeVisible({ timeout: 15_000 });
  // Open a row's offer first, then the rail Bargain: it must go back to the cheapest.
  await page.locator('.modal-listing-row[data-listing-id="2"]').getByTestId('listing-offer-btn').click();
  await expect(page.getByTestId('offer-drawer-target')).toBeVisible();
  await page.locator('.modal-listing-row[data-listing-id="2"]').getByTestId('listing-offer-btn').click();
  await expect(page.locator('.item-offer-drawer')).toHaveCount(0);
  await page.locator('.item-rail-actions-bargain').click();
  await expect(page.locator('text=/must be below the \\$0\\.54 asking price/')).toBeVisible();
  await expect(page.getByTestId('offer-drawer-target')).toHaveCount(0);
});

test('auction rows get no Make offer, and the row fits a phone', async ({ page }) => {
  // Item 2: #6 buy-now, #5 auction.
  await page.goto('/item/2');
  await expect(page.locator('.modal-listing-row[data-listing-id="5"]')).toBeVisible({ timeout: 15_000 });
  await expect(page.locator('.modal-listing-row[data-listing-id="5"]').getByTestId('listing-offer-btn')).toHaveCount(0);
  await expect(page.locator('.modal-listing-row[data-listing-id="6"]').getByTestId('listing-offer-btn')).toHaveCount(1);

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/item/1');
  const row = page.locator('.modal-listing-row[data-listing-id="2"]');
  await expect(row).toBeVisible({ timeout: 15_000 });
  const btn = row.getByTestId('listing-offer-btn');
  await expect(btn).toBeVisible();
  const r = await row.boundingBox();
  const b = await btn.boundingBox();
  expect(b && r && b.x + b.width <= r.x + r.width + 0.5).toBeTruthy();
  expect(b && b.width >= 40 && b.height >= 40).toBeTruthy();
});
