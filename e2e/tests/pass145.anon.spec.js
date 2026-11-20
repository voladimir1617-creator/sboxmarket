// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 145.

const ME = { id: 3, displayName: 'carol', role: 'USER', signedIn: true, sessionEpoch: 0, tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc' };
const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });
const slow = (body, ms = 800) => (r) => new Promise((ok) => setTimeout(ok, ms)).then(() => r.fulfill(json(body)));

async function signedIn(page) {
  await page.addInitScript(() => { window.confirm = () => true; });
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json(ME)));
}
async function asAdmin(page) {
  await signedIn(page);
  await page.route('**/api/admin/check', (r) => r.fulfill(json({ admin: true })));
}


// Defect pass 145. Real-app tests take the server from JAR / E2E_BASE_URL
// and stub every write: a real-jar test must never create or change server data.

const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';

async function noWrites(page) {
  await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
  await page.route('**/api/**', (r) => (['POST', 'PUT', 'DELETE', 'PATCH'].includes(r.request().method()) ? r.fulfill(json({})) : r.fallback()));
}

async function tableView(page) {
  await page.addInitScript(() => { try { localStorage.setItem('sb_market_view', 'table'); } catch (_) {} });
}

test.describe('market table view (real app)', () => {
  test('the table view is named and its headers are column headers, like the Database table', async ({ page }) => {
    await noWrites(page);
    await tableView(page);
    await page.goto(`${base}/market`);
    const table = page.getByRole('table', { name: 'Market listings' });
    await expect(table).toBeVisible({ timeout: 20_000 });
    for (const name of ['Item', 'Rarity', 'Seller', 'Price', 'Action']) {
      await expect(table.getByRole('columnheader', { name, exact: true })).toHaveAttribute('scope', 'col');
    }
  });
});

test.describe('paging button names (stubbed)', () => {
  const mk = (i) => ({ id: 1000 + i, price: 5 + i, sellerUserId: 9, sellerName: 'bob', status: 'ACTIVE', listingType: 'BUY_NOW', item: { id: 500 + i, name: 'Item ' + i, category: 'Hats', rarity: 'Common' } });

  test('market Load more is named without its arrow glyph', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings?**', (r) => r.fulfill(json(Array.from({ length: 100 }, (_, i) => mk(i)))));
    await page.goto('/market');
    const btn = page.getByRole('button', { name: 'Load more (100 listings loaded)', exact: true });
    await btn.scrollIntoViewIfNeeded({ timeout: 20_000 });
    await expect(btn).toBeVisible();
  });

  test('item page Show all listings is named without its arrow glyph', async ({ page }) => {
    await signedIn(page);
    const item = { id: 77, name: 'Wizard Hat', category: 'Hats', rarity: 'Standard', lowestPrice: 5, supply: 8 };
    const listings = Array.from({ length: 8 }, (_, i) => ({ id: 900 + i, price: 5 + i, sellerUserId: 20 + i, sellerName: 'seller' + i, status: 'ACTIVE', listingType: 'BUY_NOW', item }));
    await page.route('**/api/items/77', (r) => r.fulfill(json(item)));
    await page.route('**/api/listings/item/77**', (r) => r.fulfill(json(listings)));
    await page.goto('/item/77');
    const all = page.getByRole('button', { name: 'Show all 8 listings (+2)', exact: true });
    await all.scrollIntoViewIfNeeded({ timeout: 20_000 });
    await all.click();
    await expect(page.getByRole('button', { name: 'Collapse to top 6', exact: true })).toHaveAttribute('aria-expanded', 'true');
  });
});

test.describe('error page after a crash', () => {
  async function crash(page) {
    await signedIn(page);
    // A display name that is not text makes the header throw while rendering.
    await page.route('**/api/auth/steam/me', (r) => r.fulfill(json({ ...ME, displayName: { bad: 1 } })));
    await page.goto('/market');
    await expect(page.getByRole('heading', { level: 1, name: 'Maintenance in progress' })).toBeVisible();
  }

  test('Skip to main content lands on the error page', async ({ page }) => {
    await crash(page);
    await page.keyboard.press('Tab');
    const skip = page.getByRole('link', { name: 'Skip to main content' });
    await expect(skip).toBeFocused();
    await page.keyboard.press('Enter');
    const main = page.getByRole('main');
    await expect(main).toContainText(/Maintenance in progress/);
    await expect(main).toBeFocused();
  });

  test('Show technical details says whether the details are open', async ({ page }) => {
    await crash(page);
    const show = page.getByRole('button', { name: 'Show technical details' });
    await expect(show).toHaveAttribute('aria-expanded', 'false');
    await show.click();
    await expect(page.getByRole('button', { name: 'Hide technical details' })).toHaveAttribute('aria-expanded', 'true');
  });
});

test.describe('site banners read without their icon glyphs', () => {
  test('the outage banner does not read out the warning sign (real app)', async ({ page }) => {
    await noWrites(page);
    // Every read answers 503, as in an outage: any 2xx would clear the banner.
    await page.route('**/api/**', (r) => r.fulfill(json({ code: 'UNAVAILABLE' }, 503)));
    await page.goto(`${base}/market`);
    const alert = page.getByRole('alert').filter({ hasText: /temporarily unavailable/ });
    await expect(alert).toBeVisible({ timeout: 20_000 });
    expect(await alert.ariaSnapshot()).not.toContain('⚠');
  });

  test('the email confirmation nag does not read out the envelope', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/auth/steam/me', (r) => r.fulfill(json({ ...ME, email: 'carol@example.com', emailVerified: false })));
    await page.goto('/market');
    const nag = page.getByRole('status').filter({ hasText: /is not confirmed yet/ });
    await expect(nag).toBeVisible();
    expect(await nag.ariaSnapshot()).not.toContain('✉');
  });
});
