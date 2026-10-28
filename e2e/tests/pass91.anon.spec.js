// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 91.

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

test.describe('defect pass 91 · a', () => {

  const FRAUD = [
    { type: 'RAPID_LISTINGS', severity: 'HIGH', summary: 'Alpha burst', userId: 7, userName: 'alpha', createdAt: Date.now() - 60000 },
    { type: 'SHARED_IP', severity: 'MED', summary: 'Beta shared ip', ip: '10.0.0.1', createdAt: Date.now() - 120000 },
  ];

  test('CSR lookup Search keeps focus while the search runs', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/csr/check', (r) => r.fulfill(json({ csr: true })));
    await page.route('**/api/csr/users/lookup**', slow({ matches: [] }, 1200));
    await page.goto('/csr?tab=lookup');
    const box = page.getByRole('textbox', { name: /Look up a user/ });
    await expect(box).toBeVisible({ timeout: 20000 });
    await box.fill('bob');
    const btn = page.getByRole('button', { name: /^(Search|…)$/ });
    await btn.click();
    await expect(page.getByText(/No matches\./)).toBeVisible();
    await expect(page.getByRole('button', { name: /^Search$/ })).toBeFocused();
  });

  test('admin Fraud Refresh keeps focus while it reloads', async ({ page }) => {
    await asAdmin(page);
    let n = 0;
    await page.route('**/api/admin/fraud', (r) => { n++; return n === 1 ? r.fulfill(json(FRAUD)) : slow(FRAUD, 1000)(r); });
    await page.goto('/admin?tab=fraud');
    const btn = page.getByRole('button', { name: /^Refresh$/ });
    await expect(btn).toBeVisible({ timeout: 20000 });
    await btn.click();
    await expect(page.getByRole('button', { name: /^Loading…$/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /^Refresh$/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /^Refresh$/ })).toBeFocused();
  });

  test('admin API key Lookup keeps focus while it searches', async ({ page }) => {
    await asAdmin(page);
    await page.route('**/api/admin/fraud', (r) => r.fulfill(json([])));
    await page.route('**/api/admin/api-keys/**', slow([], 1000));
    await page.route('**/api/admin/api-keys?**', slow([], 1000));
    await page.goto('/admin?tab=fraud');
    const box = page.getByPlaceholder('sbx_live_…');
    await expect(box).toBeVisible({ timeout: 20000 });
    await box.fill('sbx_live_abc');
    await page.getByRole('button', { name: /^Lookup$/ }).click();
    await expect(page.getByText(/No matching API keys|Lookup failed/)).toBeVisible();
    await expect(page.getByRole('button', { name: /^Lookup$/ })).toBeFocused();
  });

  test('admin Fraud marking a signal reviewed keeps focus in the list', async ({ page }) => {
    await asAdmin(page);
    await page.addInitScript(() => localStorage.removeItem('sb_fraud_reviewed'));
    await page.route('**/api/admin/fraud', (r) => r.fulfill(json(FRAUD)));
    await page.goto('/admin?tab=fraud');
    await expect(page.getByText('Alpha burst')).toBeVisible({ timeout: 20000 });
    await page.getByRole('button', { name: /Reviewed/ }).first().click();
    await expect(page.getByText('Alpha burst')).toHaveCount(0);
    await expect(page.getByRole('button', { name: /Reviewed$/ })).toBeFocused();
  });
});

test.describe('defect pass 91 · b', () => {

  const mk = (i) => ({ id: 1000 + i, price: 5 + i, sellerUserId: 9, sellerName: 'bob', status: 'ACTIVE', listingType: 'BUY_NOW', item: { id: 500 + i, name: 'Item ' + i, category: 'Hats', rarity: 'Common' } });
  const PAGE1 = Array.from({ length: 100 }, (_, i) => mk(i));

  test('market Refresh listings keeps focus while it reloads', async ({ page }) => {
    await signedIn(page);
    let n = 0;
    await page.route('**/api/listings?**', (r) => { n++; return n <= 1 ? r.fulfill(json(PAGE1.slice(0, 5))) : slow(PAGE1.slice(0, 5), 1000)(r); });
    await page.goto('/market');
    await expect(page.getByText('Item 1', { exact: true }).first()).toBeVisible({ timeout: 20000 });
    const btn = page.getByRole('button', { name: /^Refresh listings$/ });
    await btn.click();
    await expect(page.getByRole('button', { name: /^Refreshing listings$/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /^Refresh listings$/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /^Refresh listings$/ })).toBeFocused();
  });

  test('market Load more keeps focus while the next page loads', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings?**', (r) => {
      const off = +(new URL(r.request().url()).searchParams.get('offset') || 0);
      if (off === 0) return r.fulfill(json(PAGE1));
      return slow({ items: Array.from({ length: 100 }, (_, i) => mk(100 + i)), total: 300, limit: 100, offset: off }, 1000)(r);
    });
    await page.goto('/market');
    const btn = page.getByRole('button', { name: /Load more \(100 listings loaded\)/ });
    await btn.scrollIntoViewIfNeeded({ timeout: 20000 });
    await btn.click();
    await expect(page.getByRole('button', { name: /Load more \(200 listings loaded\)/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /Load more \(200 listings loaded\)/ })).toBeFocused();
  });
});

test.describe('defect pass 91 · c', () => {

  const STALL = { seller: { id: 9, displayName: 'bob' }, listings: [] };
  const SOLD = Array.from({ length: 14 }, (_, i) => ({ id: 70 + i, price: 3 + i, soldAt: Date.now() - (i + 1) * 3600000, item: { id: 300 + i, name: 'Sold thing ' + i } }));
  const REVIEWS = Array.from({ length: 13 }, (_, i) => ({ id: 40 + i, rating: 5 - (i % 3), comment: 'Review text ' + i, reviewerName: 'buyer' + i, createdAt: Date.now() - (i + 1) * 86400000, helpfulCount: 0 }));

  async function stall(page, { eligible = [] } = {}) {
    await signedIn(page);
    await page.route('**/api/listings/stall/9', (r) => r.fulfill(json(STALL)));
    await page.route('**/api/listings/stall/9/recent-sales**', (r) => r.fulfill(json(SOLD)));
    await page.route('**/api/reviews/user/9', (r) => r.fulfill(json(REVIEWS)));
    await page.route('**/api/reviews/eligible/9', (r) => r.fulfill(json(eligible)));
    await page.goto('/stall/9');
  }

  test('stall Show all sales expander reports aria-expanded', async ({ page }) => {
    await stall(page);
    const btn = page.getByRole('button', { name: /Show all 14 sales/ });
    await btn.scrollIntoViewIfNeeded({ timeout: 20000 });
    await expect(btn).toHaveAttribute('aria-expanded', 'false');
    await btn.click();
    await expect(page.getByRole('button', { name: /Collapse to 10 most recent/ })).toHaveAttribute('aria-expanded', 'true');
  });

  test('stall Show all reviews expander reports aria-expanded', async ({ page }) => {
    await stall(page);
    const btn = page.getByRole('button', { name: /Show all 13 reviews/ });
    await btn.scrollIntoViewIfNeeded({ timeout: 20000 });
    await expect(btn).toHaveAttribute('aria-expanded', 'false');
    await btn.click();
    await expect(page.getByRole('button', { name: /Collapse to 10 most relevant/ })).toHaveAttribute('aria-expanded', 'true');
  });

  test('stall review form Cancel returns focus to Write review', async ({ page }) => {
    await stall(page, { eligible: [{ tradeId: 501, itemName: 'Blue Hat', price: 4, settledAt: Date.now() - 86400000, reviewed: false }] });
    const write = page.getByRole('button', { name: /^Write review$/ });
    await write.scrollIntoViewIfNeeded({ timeout: 20000 });
    await write.click();
    await page.getByRole('button', { name: /^Cancel$/ }).click();
    await expect(page.getByRole('button', { name: /^Write review$/ })).toBeFocused();
  });
});

test.describe('defect pass 91 · d', () => {

  test('Help Center top-questions preview is a named group', async ({ page }) => {
    await signedIn(page);
    await page.goto('/help');
    const group = page.getByRole('group', { name: 'Top FAQ questions' });
    await group.scrollIntoViewIfNeeded({ timeout: 20000 });
    await expect(group).toBeInViewport();
    await expect(group.getByRole('button')).toHaveCount(3);
  });
});
