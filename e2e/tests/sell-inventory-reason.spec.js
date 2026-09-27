// @ts-check
const { test, expect } = require('@playwright/test');

/**
 * The Sell page must SHOW the seller why his Steam inventory came back empty.
 *
 * ── Why this exists ───────────────────────────────────────────────────────
 * The server distinguishes seven causes and sends `reason`, `unreadable` and a
 * remedy sentence in `message`. The Sell modal used to read NONE of them — it
 * branched on `error` and `blocked` only — so every diagnosed cause collapsed
 * into one of two strings.
 *
 * The regression these tests pin is specific and nasty: a private profile trips
 * the negative cache, so the server sets `blocked: true` ALONGSIDE
 * reason='private_profile'. Because the old UI tested `blocked` first, that
 * seller was told "Steam is rate-limiting our requests — try again in ~5
 * minutes": advice that can never come true, because no amount of waiting makes
 * a private inventory readable.
 *
 * The server half is pinned over real HTTP in SellFlowIntegrationSpec, and the
 * shipped modals.js is pinned textually by SellInventoryReasonSurfacedSpec.
 * Neither of those EXECUTES the component — a refactor could satisfy both and
 * still render the wrong sentence. This spec closes that gap by driving the
 * real page and reading what a human would actually see.
 *
 * The inventory response is stubbed at the network boundary, so no real Steam
 * account (and no deliberately-private profile) is required.
 */

const PRIVATE_MESSAGE =
  'Your Steam inventory is private. Open Steam → Profile → Privacy Settings and set ' +
  '"Inventory" to Public, then refresh.';

/** Serve one stubbed /api/steam/inventory payload for the whole page. */
async function stubInventory(page, payload) {
  await page.route('**/api/steam/inventory', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items: [], count: 0, assetCount: 0,
        lastSyncedAt: null, steamId64: '76561199000000001',
        ...payload,
      }),
    });
  });
}

test.describe('Sell page — empty Steam inventory names its cause', () => {

  test('a private profile is told it is private, NOT told to wait for a rate-limit', async ({ page }) => {
    // Exactly what the server sends for a private profile: the typed reason
    // AND the negative-cache `blocked` fields together. Checking `blocked`
    // first is the bug; this payload is what exposes it.
    await stubInventory(page, {
      reason: 'private_profile',
      reasonDetail: 'HTTP 403',
      unreadable: true,
      message: PRIVATE_MESSAGE,
      blocked: true,
      blockedUntil: Date.now() + 300_000,
      retryInSec: 300,
    });

    await page.goto('/sell');

    // The heading names the real cause.
    await expect(page.locator('text=/Your Steam inventory is private/i').first())
      .toBeVisible({ timeout: 15_000 });

    // And the remedy that actually works is on screen.
    await expect(page.locator('text=/Privacy Settings/i').first()).toBeVisible();

    // THE REGRESSION: he must NOT be told to wait for a rate-limit that will
    // never clear, and must NOT be told he owns nothing.
    await expect(page.locator('text=/rate-limit/i')).toHaveCount(0);
    await expect(page.locator('text=/Try again in ~/i')).toHaveCount(0);
    await expect(page.locator('text=/No s&box items in your Steam inventory/i')).toHaveCount(0);
  });

  test('an unreadable Steam reply is not reported as owning nothing', async ({ page }) => {
    await stubInventory(page, {
      reason: 'malformed_response',
      reasonDetail: 'no descriptions key',
      unreadable: true,
      message: 'We reached Steam but could not read its reply, so we do not yet know what you own. ' +
               'This is our problem, not yours — please try again.',
    });

    await page.goto('/sell');

    await expect(page.locator("text=/We couldn't read your Steam inventory/i").first())
      .toBeVisible({ timeout: 15_000 });

    // "You own nothing" is the one sentence this case must never produce.
    await expect(page.locator('text=/No s&box items in your Steam inventory/i')).toHaveCount(0);
  });

  test('a genuinely empty read names the app id and context so he can self-diagnose', async ({ page }) => {
    // app/context are hardcoded (590830 / 2). A wrong one is indistinguishable
    // from owning nothing, so the copy has to name what we actually asked for.
    await stubInventory(page, {
      reason: 'empty_or_wrong_context',
      reasonDetail: null,
      unreadable: false,
      message: 'Steam returned no items for s&box (app 590830, context 2). ' +
               'If you do own s&box cosmetics, check that your inventory is public and that ' +
               'they show at steamcommunity.com/profiles/<your id>/inventory/#590830_2 — ' +
               'if they appear there under a different game or context, tell us: we query 590830/2 only.',
    });

    await page.goto('/sell');

    await expect(page.locator('text=/app 590830, context 2/i').first())
      .toBeVisible({ timeout: 15_000 });
  });

  test('a real rate-limit still shows the throttle message and a wait time', async ({ page }) => {
    // The counterpart: when throttling IS the actual cause, that advice is
    // correct and must survive. Guards against over-correcting the fix.
    await stubInventory(page, {
      reason: 'rate_limited',
      unreadable: true,
      message: 'Steam is rate-limiting our requests right now. Nothing is wrong with your account — ' +
               'wait a few minutes and refresh.',
      blocked: true,
      blockedUntil: Date.now() + 300_000,
      retryInSec: 300,
    });

    await page.goto('/sell');

    await expect(page.locator('text=/rate-limiting our requests/i').first())
      .toBeVisible({ timeout: 15_000 });
    await expect(page.locator('text=/Your Steam inventory is private/i')).toHaveCount(0);
  });
});

test.describe('Sell page - a capped inventory says so', () => {

  /**
   * The Steam fetch is capped at count=500 and does not paginate, so a seller
   * holding more than that got a quietly short list. Unlike the empty-list
   * causes this rides on a NON-empty 200, so none of the reason/unreadable
   * machinery fires -- the missing items simply looked like items he does not
   * own. He is an s&box cosmetics creator; a large inventory is his likely case.
   */
  test('a truncated inventory warns that the list is incomplete', async ({ page }) => {
    await page.route('**/api/steam/inventory', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          items: [{
            assetId: '11112222', name: 'Wizard Hat', category: 'Hats',
            rarity: 'Standard', iconUrl: 'https://example.com/h.png',
            marketable: true, tradable: true, quantity: 1,
          }],
          count: 1, assetCount: 1, lastSyncedAt: null,
          steamId64: '76561199000000001',
          truncated: true,
          totalInventoryCount: 1337,
          shownCount: 500,
          truncationMessage:
            'Steam reports 1337 items in your s&box inventory but we can only load 500 at a time, ' +
            'so this list is incomplete. Items missing here are NOT items you do not own.',
        }),
      });
    });

    await page.goto('/sell');

    // He is told the list is short, and told the count he actually owns.
    await expect(page.locator('text=/this list is incomplete/i').first())
      .toBeVisible({ timeout: 15_000 });
    await expect(page.locator('text=/1337/').first()).toBeVisible();
  });

  test('a complete inventory shows no incomplete-list warning', async ({ page }) => {
    await page.route('**/api/steam/inventory', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          items: [{
            assetId: '33334444', name: 'Wizard Hat', category: 'Hats',
            rarity: 'Standard', iconUrl: 'https://example.com/h.png',
            marketable: true, tradable: true, quantity: 1,
          }],
          count: 1, assetCount: 1, lastSyncedAt: null,
          steamId64: '76561199000000001',
        }),
      });
    });

    await page.goto('/sell');
    await expect(page.locator('text=/this list is incomplete/i')).toHaveCount(0);
  });
});
