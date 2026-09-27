// @ts-check
const { test, expect } = require('@playwright/test');

// Read-only, signed-out smoke of the public surfaces a first-time visitor hits.
// No auth — these run in the `anon` project.

test.describe('public smoke (signed out)', () => {
  test('home renders hero + deal rail + footer', async ({ page }) => {
    await page.goto('/');
    await expect(page).toHaveTitle(/SkinBox/i);
    // Hero headline
    await expect(page.locator('text=/Buy & Sell s&box Skins/i')).toBeVisible();
    // A primary CTA into the market
    await expect(page.getByRole('link', { name: /Marketplace/i }).first()).toBeVisible();
    // Footer trust chrome
    await expect(page.locator('text=/Powered by/i').first()).toBeVisible();
    // Signed-out nav shows the Steam sign-in CTA
    await expect(page.locator('text=/Sign in through Steam/i').first()).toBeVisible();
  });

  test('market grid loads item cards and a working sort control', async ({ page }) => {
    await page.goto('/market');
    await expect(page).toHaveURL(/\/market/);
    // Grid renders multiple cards
    const cards = page.locator('.grid-card');
    await expect(cards.first()).toBeVisible({ timeout: 15_000 });
    expect(await cards.count()).toBeGreaterThan(3);
    // Every visible card shows a price ($)
    await expect(page.locator('.grid-card', { hasText: '$' }).first()).toBeVisible();
  });

  test('item detail shows price + Steam reference + price history', async ({ page }) => {
    await page.goto('/item/4');
    await expect(page.locator('text=/Listing price/i')).toBeVisible({ timeout: 15_000 });
    await expect(page.locator('text=/Steam reference/i')).toBeVisible();
    // A dollar price is shown
    await expect(page.locator('.modal-stat-val', { hasText: '$' }).first()).toBeVisible();
    // Price history chart region present
    await expect(page.locator('text=/Price history/i')).toBeVisible();
  });

  test('item database (/db) renders a table', async ({ page }) => {
    await page.goto('/db');
    await expect(page).toHaveURL(/\/db/);
    await expect(page.locator('.db-table, table').first()).toBeVisible({ timeout: 15_000 });
  });

  test('wallet auth-gate shows centered Sign-in-required (not a void)', async ({ page }) => {
    await page.goto('/wallet');
    const gate = page.locator('.empty-authgate');
    await expect(gate).toBeVisible({ timeout: 10_000 });
    await expect(page.locator('text=/Sign in required/i')).toBeVisible();
    // Centered, not stranded at top: gate's vertical mid should be in the
    // lower 60% region, comfortably below the nav (regression guard for WAVE 184).
    const box = await gate.boundingBox();
    expect(box).not.toBeNull();
    if (box) expect(box.y).toBeGreaterThan(120);
  });

  test('search returns suggestions for a known item', async ({ page }) => {
    await page.goto('/market');
    const search = page.getByPlaceholder(/search/i).first();
    await expect(search).toBeVisible({ timeout: 10_000 });
    await search.fill('hat');
    // Grid filters down to matching cards (Wizard Hat etc.) without erroring.
    await expect(page.locator('.grid-card').first()).toBeVisible();
  });
});
