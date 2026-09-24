// @ts-check
const { test, expect } = require('@playwright/test');

/**
 * THE SELLER'S SCREEN, STATE BY STATE — the half of the seller journey that
 * can be proved on this box today.
 *
 * ── Why this file exists beside sell-journey.spec.js ─────────────────────
 * The full round trip (list → find in My Stall → reprice → cancel) needs a
 * real server session, and the only way to mint one locally is
 * `GET /api/auth/steam/dev-login`, which takes a user id and NOTHING else.
 * `config/DevLoginGate` keeps that door shut unless
 * `SBOX_DEV_LOGIN_ENABLED=true` is in the server process's environment, and it
 * is deliberately not set here: the cloudflared SERVICE is running on this
 * machine with no readable config, so its ingress cannot be shown to be empty,
 * and a credential-free login is not something to switch on against an unknown
 * ingress. So `sell-journey.spec.js` is written and will run the moment that
 * door is opened on a box where it is safe; THIS file proves the seller's
 * screens now, against the real shipped bundle, by stubbing
 * `/api/auth/steam/me` — a CLIENT-side identity, which mints nothing and
 * reaches no money path.
 *
 * Everything below renders the real `SellItemsModal` out of the real
 * `js/modals.js`, through the real router, with the real stylesheet. What is
 * faked is only what the server would have said.
 *
 * ── The defect family these pin ──────────────────────────────────────────
 * A missing signal rendered as a healthy one. Each test names the sentence
 * the seller used to be shown in place of the truth:
 *   • a failed price lookup → "Suggested price: $0.00", a number no listing
 *     may legally carry, presented as a recommendation;
 *   • an item nobody has priced → the SAME "$0.00", so "we could not ask" and
 *     "there is nothing to find" were one screen;
 *   • a copy already on sale → a card that looked listable, a form that took
 *     a price, and a refusal that arrived afterwards — about a copy he had
 *     not selected;
 *   • a Steam row → no `steamPrice` at all, so the one tab a seller importing
 *     his own skins uses had no second anchor to fall back to.
 */

const ME = {
  id: 1,
  steamId64: '76561199000000001',
  displayName: 'Seller One',
    signedIn: true,
  sessionEpoch: 0,
};

/**
 * Make the CLIENT believe it is signed in. Mints no server session.
 *
 * The catch-all is load-bearing, and for a reason worth recording: `safeJson`
 * fires `sb:session-expired` on ANY 401, and the App listens for it and flips
 * the whole shell to signed-out. So a single unstubbed authenticated read —
 * the wallet balance, the notification count — tears down the sell page a
 * moment after it renders, and the failure looks like "the sell page does not
 * work" rather than "one poll was refused". Everything not named below answers
 * `{}`, which every list-shaped caller already normalises.
 *
 * Playwright matches routes most-recently-registered first, so the catch-all
 * goes on FIRST and the specific routes registered afterwards win.
 */
async function signedIn(page) {
  await page.route('**/api/**', (r) => r.fulfill({
    status: 200, contentType: 'application/json', body: '{}',
  }));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify(ME),
  }));
}

/** One Steam-inventory row in the shape SteamInventoryController projects. */
function steamRow(over = {}) {
  return {
    assetId: '900001', assetIds: ['900001'], quantity: 1,
    name: 'Journey Test Hat', type: 'Hat', iconUrl: null,
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
      lastSyncedAt: Date.now(), steamId64: ME.steamId64,
    }),
  }));
}

/** Price probes that ANSWER, with nothing to report. Not the same as down. */
async function stubQuietMarket(page) {
  await page.route('**/api/items/*/recent-sales*', (r) =>
    r.fulfill({ status: 200, contentType: 'application/json', body: '[]' }));
  await page.route('**/api/listings/item/*', (r) =>
    r.fulfill({ status: 200, contentType: 'application/json', body: '[]' }));
}

/** Open /sell and click the first inventory card open into the price form. */
async function openFirstItem(page) {
  await page.goto('/sell');
  const card = page.locator('.inventory-item').first();
  await expect(card).toBeVisible({ timeout: 20_000 });
  await card.click();
  return card;
}

test.describe('The seller journey — what he is shown', () => {

  test.beforeEach(async ({ page }) => { await signedIn(page); });

  test('he can price an item and the form shows the fee, not just a box', async ({ page }) => {
    // The one screen the whole product rests on: he picks a thing he owns,
    // types a number, and is told what he will actually be paid.
    await stubSteamInventory(page, [steamRow({ name: 'Priceable Hat', suggestedPrice: 6 })]);
    await stubQuietMarket(page);
    await openFirstItem(page);

    const priceInput = page.locator('.wallet-amount-input, input[type="number"]').first();
    await expect(priceInput).toBeVisible({ timeout: 10_000 });

    // Seeded from a price we actually have, so he is not starting from zero.
    await expect(priceInput).toHaveValue('6.00');

    await priceInput.fill('10.00');
    await expect(page.locator('text=/Platform fee \\(2%\\)/i')).toBeVisible();
    await expect(page.locator("text=/[−-]\\$0\\.20/").first()).toBeVisible();
    await expect(page.locator("text=/\\$9\\.80/").first()).toBeVisible();

    // And the button he has to press is live.
    await expect(page.getByRole('button', { name: /List for Sale/i })).toBeEnabled();
  });

  test('a refused price check is never shown as a price of zero', async ({ page }) => {
    await stubSteamInventory(page, [steamRow({ name: 'Oracle Down Hat', catalogueId: 4243 })]);
    // The oracle is DOWN. Not empty — down. These are different facts.
    await page.route('**/api/items/4243/recent-sales*', (r) => r.fulfill({ status: 500, body: 'boom' }));
    await page.route('**/api/listings/item/4243', (r) => r.fulfill({ status: 500, body: 'boom' }));

    await openFirstItem(page);

    await expect(page.locator("text=/Couldn't check this item's market price/i").first())
      .toBeVisible({ timeout: 15_000 });
    // He can ask again without losing his place — not a dead end, not a spinner.
    await expect(page.getByRole('button', { name: /Retry price check/i })).toBeVisible();
    // The two sentences that would be lies here are absent.
    await expect(page.locator('text=/Suggested price/i')).toHaveCount(0);
    await expect(page.locator('text=/No price reference for this item yet/i')).toHaveCount(0);
    // A failed probe must not block the sale: he can still name his own price.
    await expect(page.getByRole('button', { name: /List for Sale/i })).toBeEnabled();
  });

  test('an item nobody has priced says so, instead of quoting $0.00', async ({ page }) => {
    await stubSteamInventory(page, [steamRow({ name: 'Unpriced Test Hat', catalogueId: 4242 })]);
    await stubQuietMarket(page);   // the market ANSWERS: there is nothing

    await openFirstItem(page);

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
    // This is NOT the failure state; that one says something different.
    await expect(page.locator("text=/Couldn't check this item's market price/i")).toHaveCount(0);
  });

  test('the Steam reference price reaches the sell form, and says it is Steam', async ({ page }) => {
    // The projection did not carry `steamPrice`, so on the Steam tab the
    // fallback anchor and the "Steam Community Market reference" basis line
    // were unreachable — on the one tab a seller importing his own Steam
    // skins actually uses.
    await stubSteamInventory(page, [steamRow({
      name: 'Steam Priced Hat', catalogueId: 4247, suggestedPrice: 0, steamPrice: 3.5,
    })]);
    await stubQuietMarket(page);

    await openFirstItem(page);

    await expect(page.locator('text=/Suggested price/i').first()).toBeVisible({ timeout: 15_000 });
    // The BASIS is on screen, not just the number. A figure with no stated
    // source is how "$0.00" passed for a recommendation in the first place.
    await expect(page.locator('text=/Steam Community Market reference/i').first()).toBeVisible();
    await expect(page.locator('text=/\\$3\\.50/').first()).toBeVisible();
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
    await expect(card).toBeVisible({ timeout: 20_000 });

    // It says so on the card, BEFORE any click and before any pricing.
    await expect(card).toContainText(/ALL 2 LISTED/i);
    // And it offers the way out.
    await expect(card.getByRole('link', { name: /My Stall/i })).toBeVisible();
    // It is not a button any more — neither for a mouse nor for a screen
    // reader. The old row kept role="button" while claiming to be disabled,
    // so assistive tech announced "unavailable" on a control that still
    // opened a form that could only fail.
    await expect(card).not.toHaveAttribute('role', 'button');
  });

  test('a partly-listed stack still offers the free copies, and says how many', async ({ page }) => {
    // THE REGRESSION: the representative assetId was always the FIRST
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
    await expect(card).toBeVisible({ timeout: 20_000 });
    await expect(card).toContainText(/1 OF 3 LISTED/i);

    // Still listable, and the panel says which copy he is listing.
    await card.click();
    await expect(page.locator('text=/1 of 3 already listed/i').first()).toBeVisible();
    await expect(page.getByRole('button', { name: /List for Sale/i })).toBeEnabled();
  });

  test('a locked copy names the lock and says it is not ours to lift', async ({ page }) => {
    // The pre-existing reason must survive the new one. "Not tradable" with
    // no explanation reads as a fault of the site; the hold is Steam's.
    await stubSteamInventory(page, [steamRow({
      name: 'Held Gloves', tradable: false, listableQuantity: 0,
      unlistableReason: 'NOT_TRADABLE',
    })]);
    await stubQuietMarket(page);

    await page.goto('/sell');
    const card = page.locator('.inventory-item').first();
    await expect(card).toBeVisible({ timeout: 20_000 });
    await expect(card).toContainText(/NOT TRADABLE/i);
    // And it is NOT mislabelled as already listed — the two blocks are
    // different problems with different remedies.
    await expect(card).not.toContainText(/ALREADY LISTED/i);
    await expect(card).not.toHaveAttribute('role', 'button');
  });

  test('the summary strip counts what he can still list, not what he owns', async ({ page }) => {
    // The strip is the FIRST thing on the page, above the grid. It counted
    // every Steam-tradable copy as "tradable" and valued every one of them,
    // listed or not — so a seller with fifty copies and forty already on
    // sale read "50 tradable · $500 est. value" while the grid below him
    // said, correctly, that he could list ten. The summary was the last
    // place still making the old claim, and it is the number he reads first.
    await stubSteamInventory(page, [steamRow({
      name: 'Lunar Trousers', assetId: '900100',
      assetIds: Array.from({ length: 50 }, (_, i) => String(901000 + i)),
      quantity: 50, suggestedPrice: 10,
      listedCount: 40, listableQuantity: 10, unlistableReason: null,
    })]);
    await stubQuietMarket(page);

    await page.goto('/sell');
    const listable = page.getByTestId('sell-summary-listable');
    await expect(listable).toBeVisible({ timeout: 20_000 });
    await expect(listable).toContainText(/10\s*can list now/i);
    // And it says where the other forty went, rather than leaving him to
    // work out why the two numbers differ.
    await expect(page.getByTestId('sell-summary-listed')).toContainText(/40\s*already listed/i);
    // Total still means every copy he holds.
    await expect(page.locator('.sell-summary')).toContainText(/50\s*total/i);
    // The estimate is over the ten he can sell, not the fifty he holds:
    // the other forty are already priced in My Stall and counting them here
    // quotes the same money to him twice.
    await expect(page.locator('.sell-summary')).toContainText(/\$100\.00/);
    await expect(page.locator('.sell-summary')).not.toContainText(/\$500\.00/);
  });

  test('a listing whose reply we could not read is neither a success nor a failure', async ({ page }) => {
    // `writeJson` parses with `try { body = await r.json(); } catch { body = null; }`,
    // so a 2xx whose body is not JSON — a proxy's HTML error page, a
    // truncated response, a 204 — reaches the sell form as `null`. Reading
    // `res.code` off it threw a TypeError past a `finally` that only cleared
    // `busy`: the modal stayed open and NOTHING was said, on the one click
    // that decides whether the operator's skin is for sale.
    //
    // Both guesses are worse than the truth. "It failed" sends him to list
    // again, and the double-list guard then refuses the copy he has just
    // successfully listed. "It worked" sends him to a stall that may be
    // empty. The POST may well have been applied; the ANSWER is what is
    // missing, and that is what the screen has to say.
    await stubSteamInventory(page, [steamRow({ name: 'Torn Reply Hat', suggestedPrice: 3 })]);
    await stubQuietMarket(page);
    await page.route('**/api/steam/list', (r) => r.fulfill({
      status: 200, contentType: 'text/html', body: '<html>gateway hiccup</html>',
    }));

    await openFirstItem(page);
    const priceInput = page.locator('.wallet-amount-input, input[type="number"]').first();
    await expect(priceInput).toBeVisible({ timeout: 10_000 });
    await priceInput.fill('3.00');
    await page.getByRole('button', { name: /List for Sale/i }).click();

    await expect(page.locator('text=/could not read the reply/i').first())
      .toBeVisible({ timeout: 10_000 });
    // It names the one action that resolves the ambiguity without risking a
    // second listing.
    await expect(page.locator('text=/Check My Stall before listing this item again/i').first())
      .toBeVisible();
    // And it does NOT claim the listing went up.
    await expect(page.locator('text=/^Listed "/i')).toHaveCount(0);
  });

  test('an unreadable inventory is not reported as an empty one', async ({ page }) => {
    // The oldest member of this defect family, re-pinned here because the
    // sell page is where it hurts most: the seller concludes his items have
    // vanished. `sell-inventory-reason.spec.js` covers the seven causes; this
    // keeps the split alive in the journey file.
    await page.route('**/api/steam/inventory', (r) => r.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({
        items: [], count: 0, assetCount: 0, lastSyncedAt: null,
        steamId64: ME.steamId64,
        reason: 'malformed_response', reasonDetail: 'no descriptions key',
        unreadable: true,
        message: 'We reached Steam but could not read its reply, so we do not yet know what you own. ' +
                 'This is our problem, not yours — please try again.',
      }),
    }));

    await page.goto('/sell');
    await expect(page.locator("text=/We couldn't read your Steam inventory/i").first())
      .toBeVisible({ timeout: 20_000 });
    await expect(page.locator('text=/No s&box items in your Steam inventory/i')).toHaveCount(0);
  });
});
