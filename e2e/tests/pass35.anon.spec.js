// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 35.

const ME = {
  id: 1,
  steamId64: '76561199000000001',
  displayName: 'Bid Tester',
  signedIn: true,
  sessionEpoch: 0,
};

async function signedIn(page, extra = {}) {
  await page.route('**/api/**', (r) => r.fulfill({
    status: 200, contentType: 'application/json', body: '{}',
  }));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify({ ...ME, ...extra }),
  }));
  await page.route('**/api/items/**', (r) => r.continue());
}

test.describe('defect pass 35', () => {
  test('a market card says "You\'re winning" on an auction the viewer leads', async ({ page }) => {
    await page.route('**/api/**', (r) => r.fulfill({
      status: 200, contentType: 'application/json', body: '{}',
    }));
    await page.route('**/api/auth/steam/me', (r) => r.fulfill({
      status: 200, contentType: 'application/json', body: JSON.stringify(ME),
    }));
    await page.route('**/api/items/**', (r) => r.continue());
    let leadId = null;
    // Real listings, with the first two turned into live auctions that have
    // bids. The viewer leads only the first.
    await page.route(/\/api\/listings\?/, async (r) => {
      const res = await r.fetch();
      const data = await res.json();
      const rows = Array.isArray(data) ? data : data.items;
      for (const l of rows.slice(0, 2)) {
        l.listingType = 'AUCTION';
        l.expiresAt = Date.now() + 3 * 3600_000;
        l.bidCount = 2;
        l.currentBid = l.price;
      }
      leadId = rows[0].id;
      await r.fulfill({ response: res, json: data });
    });
    await page.route('**/api/bids/my-active', (r) => r.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify([{ id: 9, listingId: leadId, status: 'WINNING', amount: 5 }]),
    }));
    await page.goto('/market');
    await expect(page.getByText("✓ You're winning")).toHaveCount(1, { timeout: 20_000 });
  });

  test('link previews and search results promise no Stripe payouts or Stripe checkout', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('#main')).toBeVisible({ timeout: 20_000 });
    await expect(page.locator('body')).not.toContainText('Zero Steam hold');
    const og = await page.locator('meta[property="og:description"]').getAttribute('content');
    const tw = await page.locator('meta[name="twitter:description"]').getAttribute('content');
    expect(og).not.toContain('Stripe');
    expect(tw).not.toContain('Stripe');
    const ld = await page.locator('script[type="application/ld+json"]').allTextContents();
    expect(ld.join(' ')).not.toContain('Stripe checkout');
  });

  test('the item page header has the watchlist heart and "List one of these"', async ({ page }) => {
    await page.goto('/item/4');
    const heart = page.getByRole('button', { name: /^Add .* to watchlist$/ });
    await expect(heart).toBeVisible({ timeout: 20_000 });
    await heart.click();
    await expect(page.getByRole('button', { name: /^Remove .* from watchlist$/ })).toHaveAttribute('aria-pressed', 'true');
  });

  test('a signed-in viewer sees "List one of these" on the item page', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings/**', (r) => r.continue());
    await page.goto('/item/4');
    await expect(page.getByRole('link', { name: /List one of these/ })).toBeVisible({ timeout: 20_000 });
  });

  test('sending an offer keeps the buyer on the item page, and its chips are in dollars', async ({ page }) => {
    await page.addInitScript(() => localStorage.setItem('sb_currency', 'EUR'));
    await signedIn(page, { tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc' });
    await page.route('**/api/listings/**', (r) => r.continue());
    let posted = false;
    await page.route('**/api/offers', (r) => {
      if (r.request().method() !== 'POST') return r.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
      posted = true;
      return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ id: 77, status: 'PENDING', amount: 1 }) });
    });
    await page.goto('/item/4');
    const bargain = page.getByRole('button', { name: /^(forum)?Bargain$/ });
    await expect(bargain).toBeVisible({ timeout: 20_000 });
    await bargain.click();
    const chip = page.locator('.price-suggest-chip').first();
    await expect(chip).toBeVisible();
    await expect(chip.locator('.price-suggest-chip-amt')).toHaveText(/^\$/);
    await chip.click();
    await page.getByRole('button', { name: 'Send Offer' }).click();
    await expect.poll(() => posted).toBe(true);
    await page.waitForTimeout(500);
    expect(new URL(page.url()).pathname).toBe('/item/4');
  });

  test('rejecting an offer updates the nav Offers badge at once', async ({ page }) => {
    let pending = 1;
    await signedIn(page);
    await page.route('**/api/offers/counts', (r) => r.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ incomingPending: pending, outgoingPending: 0 }),
    }));
    await page.route('**/api/offers/incoming', (r) => r.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify(pending ? [{ id: 5, listingId: 9, itemName: 'Test Hat', amount: 4, askingPrice: 5,
        status: 'PENDING', author: 'BUYER', buyerName: 'Buyer', createdAt: Date.now(), expiresAt: Date.now() + 86400000 }] : []),
    }));
    await page.route('**/api/offers/outgoing', (r) => r.fulfill({
      status: 200, contentType: 'application/json', body: '[]',
    }));
    await page.route('**/api/offers/5/reject', (r) => { pending = 0; return r.fulfill({
      status: 200, contentType: 'application/json', body: JSON.stringify({ id: 5, status: 'REJECTED' }),
    }); });
    page.on('dialog', (d) => d.accept());
    await page.goto('/offers');
    const badge = page.locator('a.nav-icon-btn[href="/offers"]');
    await expect(badge).toHaveAttribute('aria-label', 'Offers (1 pending)', { timeout: 20_000 });
    await page.getByRole('button', { name: 'Reject', exact: true }).click();
    await page.getByRole('button', { name: 'Reject offer' }).click();
    await expect(badge).toHaveAttribute('aria-label', 'Offers', { timeout: 5_000 });
  });

  test('a full page like /settings is a main region, not a modal dialog', async ({ page }) => {
    await signedIn(page);
    await page.goto('/settings');
    const main = page.locator('#main');
    await expect(main).toHaveAttribute('role', 'main', { timeout: 20_000 });
    await expect(main).not.toHaveAttribute('aria-modal', 'true');
  });

  test('Reduce motion survives a reload', async ({ page }) => {
    await page.addInitScript(() => localStorage.setItem('sb_reduce_motion', '1'));
    await page.goto('/');
    await expect(page.locator('html')).toHaveClass(/reduce-motion/, { timeout: 20_000 });
  });
});

