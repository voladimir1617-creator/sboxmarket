// @ts-check
const { test, expect } = require('@playwright/test');

// Watchlist: star an item from the market grid → it appears on /watchlist.
// A common customer action; guards the star toggle + watchlist render.
test('star a market card adds it to the watchlist', async ({ page }) => {
  await page.goto('/market');
  const firstCard = page.locator('.grid-card').first();
  await expect(firstCard).toBeVisible({ timeout: 15_000 });
  // The watchlist star is a hover-reveal control on desktop (opacity:0 /
  // pointer-events:none at rest, visible on card hover; always-on at <=760px).
  // Hover the card to bring it forward before clicking.
  await firstCard.hover();
  const star = firstCard.locator('.grid-star').first();
  await expect(star).toBeVisible();
  // The star is a TOGGLE. On a second run against the same database the first
  // card is already starred from the first run, and clicking it again took it
  // OFF the watchlist, so the test failed on its own leftovers. Only click an
  // unstarred card; a starred one is already what this test is about.
  if ((await star.getAttribute('aria-pressed')) !== 'true') {
    await star.click();
    await expect(star).toHaveAttribute('aria-pressed', 'true');
  }

  // Watchlist now has at least one item (not the empty state).
  await page.goto('/watchlist');
  await expect(page).toHaveURL(/\/watchlist/);
  await expect(
    page.locator('.grid-card').first()
      .or(page.locator('text=/your watchlist|watching/i').first())
  ).toBeVisible({ timeout: 10_000 });
  // It must NOT be the signed-in empty state.
  await expect(page.locator('text=/watchlist is empty|nothing on your watchlist/i')).toHaveCount(0);
});
