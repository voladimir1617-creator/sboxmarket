// @ts-check
const { test, expect } = require('@playwright/test');

/**
 * THE SELLER JOURNEY, END TO END — the one flow that has to work before any
 * other part of this product matters. The operator's own words: "I ALREADY
 * HAVE SKINS TO SELL." He is seller number one. If one person cannot list a
 * skin, see it, reprice it and take it down, nothing else here is worth
 * anything.
 *
 * ── What is real and what is stubbed, and why ─────────────────────────────
 * The first test drives the REAL server: a real listing is created, repriced
 * through the real PUT, and cancelled through the real DELETE, so the money
 * path is exercised rather than mimed. It cleans up after itself.
 *
 * The rest stub `/api/steam/inventory` at the network boundary, because they
 * pin states that depend on what STEAM holds and on what the price oracle
 * answers — neither of which a local run can arrange, and both of which are
 * exactly the states a first seller meets. Stubbing the boundary is how
 * sell-inventory-reason.spec.js already pins the empty-inventory reasons; this
 * file continues that pattern one screen further into the flow.
 *
 * ── The defect family these pin ───────────────────────────────────────────
 * A missing signal rendered as a healthy one. Before this spec:
 *   • an item with no live listing showed "Suggested price: $0.00" — a number
 *     no listing may legally carry, presented as a recommendation;
 *   • a FAILED price lookup produced that same screen, so "we could not ask"
 *     and "there is nothing to find" were indistinguishable;
 *   • a copy that was already on sale was offered for sale again, and the
 *     refusal only arrived after the seller had priced it — and on a stack,
 *     the refusal was about a copy he had not selected.
 */

/** One Steam-inventory row in the shape the server's projection sends. */
function steamRow(over = {}) {
  return {
    assetId: '900001', assetIds: ['900001'], quantity: 1,
    name: 'Journey Test Hat', type: 'Hat', iconUrl: 'https://example.com/x.png',
    tradable: true, marketable: true, category: 'Hats', rarity: 'Standard',
    catalogueId: 4242, suggestedPrice: 0, steamPrice: null,
    listedCount: 0, listableQuantity: 1, unlistableReason: null,
    ...over,
  };
}

async function stubSteamInventory(page, rows) {
  await page.route('**/api/steam/inventory', (route) => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({
      items: rows, count: rows.length,
      assetCount: rows.reduce((a, r) => a + (r.quantity || 1), 0),
      lastSyncedAt: Date.now(), steamId64: '76561199000000001',
    }),
  }));
}

/** Price probes that ANSWER, with nothing to report. */
async function stubQuietMarket(page) {
  await page.route('**/api/items/*/recent-sales*', (r) =>
    r.fulfill({ status: 200, contentType: 'application/json', body: '[]' }));
  await page.route('**/api/listings/item/*', (r) =>
    r.fulfill({ status: 200, contentType: 'application/json', body: '[]' }));
}

test.describe('The seller journey', () => {

  test('he lists an item, finds it in My Stall, reprices it, and takes it down', async ({ page }) => {
    // ── Does he have anything to sell? An empty inventory and a broken
    // inventory are different answers and this test must not confuse them
    // either: a failed fetch FAILS here, an empty one skips with a reason.
    const inv = await page.request.get('/api/listings/inventory');
    expect(inv.ok(), 'GET /api/listings/inventory must answer before we can test selling').toBeTruthy();
    const owned = await inv.json();
    test.skip(!Array.isArray(owned) || owned.length === 0,
      'this dev account owns no platform items, so there is nothing to list');

    await page.goto('/sell');
    await page.getByRole('button', { name: /Platform Inventory/i }).first().click();

    const firstItem = page.locator('.inventory-item').first();
    await expect(firstItem).toBeVisible({ timeout: 15_000 });
    const itemName = (await firstItem.innerText()).split('\n')[0].trim();
    await firstItem.click();

    // 1. PRICE IT — and the form must show the fee, not just take a number.
    const priceInput = page.locator('.wallet-amount-input, input[type="number"]').first();
    await expect(priceInput).toBeVisible({ timeout: 10_000 });
    await priceInput.fill('4.44');
    await expect(page.locator('text=/Platform fee \\(2%\\)/i')).toBeVisible();
    await expect(page.locator("text=/\\$4\\.35/").first()).toBeVisible();  // 4.44 − 2%

    // 2. LIST IT.
    await page.getByRole('button', { name: /List for Sale/i }).click();

    // 3. FIND IT. This is the step that decides whether he believes the site
    //    works: the listing has to be somewhere he can point at.
    await page.goto('/me/stall');
    const row = page.locator('text=' + JSON.stringify(itemName)).first();
    await expect(row).toBeVisible({ timeout: 15_000 });
    await expect(page.locator('text=/\\$4\\.44/').first()).toBeVisible();

    // 4. REPRICE IT.
    const stallRow = page.locator('.stall-row, .mystall-row, .listing-row')
      .filter({ hasText: itemName }).first();
    await stallRow.getByRole('button', { name: /Edit/i }).click();
    const editPrice = stallRow.locator('input.price-input').first();
    await expect(editPrice).toBeVisible();
    await editPrice.fill('5.55');
    await editPrice.press('Enter');
    await expect(page.locator('text=/\\$5\\.55/').first()).toBeVisible({ timeout: 10_000 });

    // 5. TAKE IT DOWN — and it really goes, on the server, not just on screen.
    //
    // Matched by ACCESSIBLE NAME, not by the glyph. The control renders the
    // character '✕' but carries `aria-label: 'Cancel listing'`, and an
    // aria-label replaces the text content for name computation — so
    // `{ name: '✕' }` matches nothing and the test times out at the last step
    // of the journey, reporting "he cannot cancel" about a button that works.
    // A locator written against what the glyph looks like rather than what
    // the control is called is its own small version of this codebase's
    // defect: the absence of a match read as the absence of the feature.
    page.once('dialog', d => d.accept());
    await stallRow.getByRole('button', { name: /Cancel listing/i }).first().click();
    await expect(page.locator('.stall-row, .mystall-row, .listing-row')
      .filter({ hasText: itemName })).toHaveCount(0, { timeout: 10_000 });

    const after = await page.request.get('/api/listings/my-stall');
    expect(after.ok()).toBeTruthy();
    const stillThere = (await after.json()).filter(l => l?.item?.name === itemName
      && String(l.price) === '5.55');
    expect(stillThere, 'the cancelled listing must be gone server-side, not just hidden').toHaveLength(0);
  });

  test('a copy that is already on sale is not offered for sale again', async ({ page }) => {
    // Every copy stays in the seller's Steam inventory after he lists it, so
    // the row keeps coming back. It used to come back looking listable.
    await stubSteamInventory(page, [steamRow({
      name: 'Fully Listed Cap', assetId: '900020', assetIds: ['900020', '900021'],
      quantity: 2, suggestedPrice: 4, listedCount: 2, listableQuantity: 0,
      unlistableReason: 'ALREADY_LISTED',
    })]);
    await stubQuietMarket(page);

    await page.goto('/sell');

    const card = page.locator('.inventory-item').first();
    await expect(card).toBeVisible({ timeout: 15_000 });
    // It says so on the card, before any click.
    await expect(card).toContainText(/ALL 2 LISTED/i);
    // And it offers the way out.
    await expect(card.getByRole('link', { name: /My Stall/i })).toBeVisible();
    // It is not a button any more — neither for a mouse nor for a screen
    // reader. The old row kept role="button" while claiming to be disabled.
    await expect(card).not.toHaveAttribute('role', 'button');
  });

  test('a partly-listed stack still offers the free copies, and says how many', async ({ page }) => {
    // THE REGRESSION: the representative assetId was always the first
    // tradable copy, so listing one of three and then listing another
    // re-sent the copy already on sale and was refused ALREADY_LISTED —
    // for an item the seller demonstrably still owned twice over.
    await stubSteamInventory(page, [steamRow({
      name: 'Partly Listed Trousers', assetId: '900011',
      assetIds: ['900010', '900011', '900012'], quantity: 3,
      suggestedPrice: 2.5, listedCount: 1, listableQuantity: 2,
      unlistableReason: null,
    })]);
    await stubQuietMarket(page);

    await page.goto('/sell');
    const card = page.locator('.inventory-item').first();
    await expect(card).toBeVisible({ timeout: 15_000 });
    await expect(card).toContainText(/1 OF 3 LISTED/i);

    // Still listable, and the panel says which copy he is listing.
    await card.click();
    await expect(page.locator('text=/1 of 3 already listed/i').first()).toBeVisible();
    await expect(page.getByRole('button', { name: /List for Sale/i })).toBeEnabled();
  });

  test('a refused price check is never shown as a price of zero', async ({ page }) => {
    await stubSteamInventory(page, [steamRow({ name: 'Oracle Down Hat', catalogueId: 4243 })]);
    // The oracle is DOWN. Not empty — down.
    await page.route('**/api/items/4243/recent-sales*', (r) => r.fulfill({ status: 500, body: 'boom' }));
    await page.route('**/api/listings/item/4243', (r) => r.fulfill({ status: 500, body: 'boom' }));

    await page.goto('/sell');
    await page.locator('.inventory-item').first().click();

    await expect(page.locator("text=/Couldn't check this item's market price/i").first())
      .toBeVisible({ timeout: 15_000 });
    // He can ask again without losing his place.
    await expect(page.getByRole('button', { name: /Retry price check/i })).toBeVisible();
    // And the two sentences that would be lies here are absent.
    await expect(page.locator('text=/Suggested price/i')).toHaveCount(0);
    await expect(page.locator('text=/No price reference for this item yet/i')).toHaveCount(0);
  });

  test('an item nobody has priced says so, instead of quoting $0.00', async ({ page }) => {
    await stubSteamInventory(page, [steamRow({ name: 'Unpriced Test Hat', catalogueId: 4242 })]);
    await stubQuietMarket(page);   // the market ANSWERS: there is nothing

    await page.goto('/sell');
    await page.locator('.inventory-item').first().click();

    await expect(page.locator('text=/No price reference for this item yet/i').first())
      .toBeVisible({ timeout: 15_000 });
    // The zero is gone from the recommendation AND from the input.
    await expect(page.locator('text=/Suggested price: \\$0\\.00/i')).toHaveCount(0);
    const priceInput = page.locator('.wallet-amount-input, input[type="number"]').first();
    await expect(priceInput).toHaveValue('');
    // AND the placeholder, which is the other half of the same field. A
    // placeholder of "0.00" is a suggestion — greyed out, but a number, in
    // the one box that decides what he is paid, and one the server rejects
    // with "Price must be at least $0.01" if he trusts it. The empty-anchor
    // state has to say what to do, not quote a figure nobody gave.
    await expect(priceInput).toHaveAttribute('placeholder', /Set your price/i);
    await expect(priceInput).not.toHaveAttribute('placeholder', /0\.00/);
    // This is not the failure state — that one says something different.
    await expect(page.locator("text=/Couldn't check this item's market price/i")).toHaveCount(0);
  });

  test('the Steam reference price reaches the sell form', async ({ page }) => {
    // The projection did not carry `steamPrice`, so on the Steam tab the
    // fallback anchor, the "Steam" chip and the "you are above the Steam
    // market price" warning were all unreachable — on the one tab a seller
    // importing his own Steam skins uses.
    await stubSteamInventory(page, [steamRow({
      name: 'Steam Priced Hat', catalogueId: 4247, suggestedPrice: 0, steamPrice: 3.5,
    })]);
    await stubQuietMarket(page);

    await page.goto('/sell');
    await page.locator('.inventory-item').first().click();

    await expect(page.locator('text=/Suggested price/i').first()).toBeVisible({ timeout: 15_000 });
    await expect(page.locator('text=/Steam Community Market reference/i').first()).toBeVisible();
    await expect(page.locator('text=/\\$3\\.50/').first()).toBeVisible();
  });
});
