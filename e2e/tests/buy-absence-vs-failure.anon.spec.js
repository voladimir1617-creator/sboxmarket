// @ts-check
const { test, expect } = require('@playwright/test');

/**
 * THE BUY-SIDE "ABSENCE READ AS SUCCESS" GUARD.
 *
 * This codebase's recurring defect is a missing signal rendered as a healthy
 * one. On the buy side it had three instances, all shipped, all measured in a
 * real browser on 2026-09-20 against the seeded catalogue:
 *
 *   1. `/api/listings` 500  → "Marketplace is empty · Be the first to list an
 *      item" with a Sell CTA. No error, no Retry. `fetchListings` funnelled
 *      every failure through `safeJson`'s null and returned `[]`, so the
 *      grid's own `loadError` branch — which was already written, with copy
 *      and a Retry button — could never fire, and the success path's
 *      `setLoadError(null)` ran on the failure.
 *   2. `/item/:id` with the backend down → "Item not found · has been removed
 *      or never existed. It may have been merged into another entry by the
 *      catalogue sync." An invented, specific explanation for an answer we
 *      never received.
 *   3. Load-more page 2 fails → `[]` → `setHasMore(false)` → the button
 *      disappears and its absence tells the user they have reached the end of
 *      the catalogue.
 *
 * Every one of those is a confident FALSE statement about the market, made at
 * the moment the platform is broken, to the buyer, with no way forward.
 *
 * These tests are the teeth. Each asserts BOTH directions — the failure must
 * look like a failure AND a genuinely empty/missing result must still look
 * empty/missing — because a fix that renders everything as an error is the
 * same bug facing the other way.
 *
 * Signed out on purpose: all three surfaces are public, so this guard runs in
 * the `anon` project and needs no dev-login opt-in.
 */

/** Is this the marketplace GRID query (not /listings/item/:id, not a POST)? */
const isGridQuery = (url) => {
  try {
    const u = new URL(url);
    return u.pathname === '/api/listings';
  } catch {
    return false;
  }
};

/** Wait for the grid to have settled into one of its terminal states. */
async function gridSettled(page) {
  await expect(
    page
      .locator('[data-testid="market-load-error"]')
      .or(page.locator('[data-testid="market-empty"]'))
      .or(page.locator('.grid-card').first())
  ).toBeVisible({ timeout: 20_000 });
}

test.describe('the market page tells a fault from an empty shelf', () => {
  test('a 500 on the listings query renders a fault with a Retry — never "Marketplace is empty"', async ({ page }) => {
    await page.route(isGridQuery, (route) =>
      route.fulfill({ status: 500, contentType: 'application/json', body: '{"error":"boom"}' })
    );

    await page.goto('/market');
    await gridSettled(page);

    // The fault is named as a fault.
    const fault = page.locator('[data-testid="market-load-error"]');
    await expect(fault).toBeVisible();
    await expect(fault).toHaveText(/Couldn't load listings/i);

    // And the FALSE claim is absent. This is the half that actually regressed:
    // the page used to say the marketplace had no inventory.
    await expect(page.locator('[data-testid="market-empty"]')).toHaveCount(0);
    await expect(page.locator('text=/Be the first to list an item/i')).toHaveCount(0);

    // A dead end is not an error state — there must be a way forward.
    const retry = page.getByRole('button', { name: /^Retry$/i });
    await expect(retry).toBeVisible();

    // ...and it must actually recover, or it is decoration. Drop the fault
    // and press it: the real catalogue comes back in place.
    await page.unroute(isGridQuery);
    await retry.click();
    await expect(page.locator('.grid-card').first()).toBeVisible({ timeout: 20_000 });
    await expect(page.locator('[data-testid="market-load-error"]')).toHaveCount(0);
  });

  test('a genuinely empty catalogue still reads as empty, not as a fault', async ({ page }) => {
    // The other direction. A 200 carrying an empty list is a real answer and
    // must keep its own copy — otherwise the fix above has merely swapped one
    // wrong state for another.
    await page.route(isGridQuery, (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
    );

    await page.goto('/market');
    await gridSettled(page);

    await expect(page.locator('[data-testid="market-empty"]')).toBeVisible();
    await expect(page.locator('[data-testid="market-load-error"]')).toHaveCount(0);
  });

  test('a 200 that is not a list is a fault, not an empty market', async ({ page }) => {
    // An unparseable success is still "we could not read the market". Letting
    // this fall through to `[]` is the same lie by a different route.
    await page.route(isGridQuery, (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: '{"unexpected":"shape"}' })
    );

    await page.goto('/market');
    await gridSettled(page);

    await expect(page.locator('[data-testid="market-load-error"]')).toBeVisible();
    await expect(page.locator('[data-testid="market-empty"]')).toHaveCount(0);
  });

  test('a refresh that fails while rows are on screen says the rows are stale', async ({ page }) => {
    // The quiet half. The first load succeeds, so the grid has rows; then the
    // query for the filter the user just picked dies. Keeping the old rows is
    // right — silently presenting them as the answer to the new filter is not.
    await page.goto('/market');
    await expect(page.locator('.grid-card').first()).toBeVisible({ timeout: 20_000 });

    await page.route(isGridQuery, (route) =>
      route.fulfill({ status: 500, contentType: 'application/json', body: '{"error":"boom"}' })
    );

    // Flip a server-side filter — this re-issues the grid query.
    await page.getByRole('button', { name: /^Buy Now$/ }).first().click();

    const stale = page.locator('[data-testid="market-stale-warning"]');
    await expect(stale).toBeVisible({ timeout: 15_000 });
    await expect(stale).toHaveText(/Couldn't refresh listings/i);
    // The rows are still browsable — we warn, we do not blank the page.
    await expect(page.locator('.grid-card').first()).toBeVisible();
  });

  test('a failed page 2 keeps Load more alive instead of implying the end of the catalogue', async ({ page }) => {
    // The seeded catalogue is smaller than one page, so page 1 is padded to
    // exactly 100 rows to make the app believe a page 2 exists (hasMore is
    // `data.length >= 100`). Page 2 then fails.
    // Page 2 is identified by the `offset` parameter, NOT by call order —
    // ordering would silently mis-target the moment anything else on the
    // page issues a listings query first, and a fixture that quietly points
    // at the wrong request is its own version of this bug.
    await page.route(isGridQuery, async (route, request) => {
      const u = new URL(request.url());
      if (u.searchParams.has('offset')) {
        return route.fulfill({ status: 500, contentType: 'application/json', body: '{"error":"boom"}' });
      }
      const real = await route.fetch();
      const body = await real.json();
      const rows = Array.isArray(body) ? body : (body && body.items) || [];
      if (rows.length === 0) {
        // Fail the fixture LOUDLY rather than throwing inside the handler
        // (which hangs the request and reads as a UI bug).
        return route.fulfill({ status: 599, contentType: 'application/json', body: '{"error":"fixture: empty catalogue"}' });
      }
      const padded = [];
      for (let i = 0; i < 100; i++) {
        const src = rows[i % rows.length];
        padded.push({
          ...src,
          id: 900000 + i,
          item: { ...src.item, id: 900000 + i, name: `${src.item.name} #${i}` },
        });
      }
      return route.fulfill({
        status: 200, contentType: 'application/json', body: JSON.stringify(padded),
      });
    });

    await page.goto('/market');
    await expect(page.locator('.grid-card').first()).toBeVisible({ timeout: 20_000 });

    const loadMore = page.getByRole('button', { name: /Load more/i }).first();
    await expect(loadMore).toBeVisible({ timeout: 15_000 });
    await loadMore.click();

    // THE ASSERTION: the button is still there. Before the fix the failed
    // page-2 fetch came back as `[]`, hit `setHasMore(false)` and removed it,
    // so the rest of the catalogue was stranded behind a control that had
    // quietly deleted itself.
    await expect(loadMore).toBeVisible({ timeout: 15_000 });
    // And the click is acknowledged rather than looking like a no-op.
    await expect(page.locator('text=/Couldn.t load more listings/i').first())
      .toBeVisible({ timeout: 10_000 });
  });
});

test.describe('the item page tells "gone" from "could not ask"', () => {
  test('a 500 looking up an item says so and offers a Retry — never "never existed"', async ({ page }) => {
    await page.route('**/api/listings/item/**', (route) =>
      route.fulfill({ status: 500, contentType: 'application/json', body: '{"error":"boom"}' })
    );
    await page.route('**/api/items/4', (route) =>
      route.fulfill({ status: 500, contentType: 'application/json', body: '{"error":"boom"}' })
    );

    await page.goto('/item/4');

    const fault = page.locator('[data-testid="item-load-error"]');
    await expect(fault).toBeVisible({ timeout: 20_000 });
    await expect(fault).toHaveText(/Couldn't load this item/i);

    // The invented explanation must be gone. A buyer mid-purchase being told
    // the item "never existed" during an outage is how a dispute starts.
    await expect(page.locator('[data-testid="item-not-found"]')).toHaveCount(0);
    await expect(page.locator('text=/removed or never existed/i')).toHaveCount(0);

    // Recoverable in place — Retry re-runs the load without a full reload.
    const retry = page.getByRole('button', { name: /^Retry$/i });
    await expect(retry).toBeVisible();
    await page.unroute('**/api/listings/item/**');
    await page.unroute('**/api/items/4');
    await retry.click();
    await expect(page.locator('[data-testid="item-load-error"]')).toHaveCount(0, { timeout: 20_000 });
  });

  test('an item that really is not in the catalogue still reads as not found', async ({ page }) => {
    // The other direction, with no interception at all: a real dead id must
    // keep the real not-found panel.
    await page.goto('/item/99999901');
    await expect(page.locator('[data-testid="item-not-found"]')).toBeVisible({ timeout: 20_000 });
    await expect(page.locator('[data-testid="item-load-error"]')).toHaveCount(0);
  });
});
