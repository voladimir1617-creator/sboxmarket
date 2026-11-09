// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 101.

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


// Defect pass 101.

async function anon(page) {
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json({ signedIn: false })));
}

test.describe('Sign-in and sign-out', () => {
  test('Logout leaves keyboard focus on the Sign in through Steam button', async ({ page }) => {
    await signedIn(page);
    await page.goto('/market');
    const menuBtn = page.getByRole('button', { name: /User menu/ });
    await menuBtn.click({ timeout: 20_000 });
    await page.getByRole('menuitem', { name: /Logout/ }).focus();
    await page.keyboard.press('Enter');
    await expect(page.getByText(/Signed out/)).toBeVisible();
    await expect(page.getByRole('button', { name: 'Sign in through Steam' })).toBeFocused();
  });

  test('the Sign in required gate title is a heading', async ({ page }) => {
    await anon(page);
    await page.goto('/profile/reviews');
    await expect(page.getByRole('heading', { name: /Sign in required/ })).toBeVisible({ timeout: 20_000 });
  });
});

test.describe('Reviews', () => {
  const now = Date.now();
  async function profileReviews(page) {
    await signedIn(page);
    await page.route('**/api/reviews/user/3', (r) => r.fulfill(json([
      { id: 1, rating: 5, comment: 'great', fromDisplayName: 'alice', createdAt: now - 1000, itemName: 'Red Hat' },
      { id: 2, rating: 4, comment: 'ok', fromDisplayName: 'bob', createdAt: now - 2000, itemName: 'Blue Hat' },
    ])));
    await page.route('**/api/reviews/user/3/summary', (r) => r.fulfill(json({ count: 2, average: 4.5 })));
    await page.goto('/profile/reviews');
  }

  test('profile Received reviews: each Reply button names the reviewer', async ({ page }) => {
    await profileReviews(page);
    await expect(page.getByRole('button', { name: /Reply to alice.s review/ })).toBeVisible({ timeout: 20_000 });
    await expect(page.getByRole('button', { name: /Reply to bob.s review/ })).toBeVisible();
  });

  test('profile Received reviews: Escape in the reply box returns focus to its Reply button', async ({ page }) => {
    await profileReviews(page);
    const reply = page.getByRole('button', { name: /Reply/ }).nth(1);
    await reply.click({ timeout: 20_000 });
    await expect(page.getByRole('textbox', { name: 'Seller reply to review' })).toBeFocused();
    await page.keyboard.press('Escape');
    await expect(page.getByRole('textbox', { name: 'Seller reply to review' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /Reply/ }).nth(1)).toBeFocused();
  });

  test('stall reviews: Helpful and Report name the review they act on', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings/stall/2', (r) => r.fulfill(json({ seller: { id: 2, displayName: 'AtlasTrades' }, listings: [], count: 0, blockedByViewer: false })));
    await page.route('**/api/reviews/user/2', (r) => r.fulfill(json([
      { id: 101, rating: 5, comment: 'fast', fromDisplayName: 'dana', createdAt: now - 1000, helpfulCount: 0 },
      { id: 102, rating: 4, comment: 'fine', fromDisplayName: 'eli', createdAt: now - 2000, helpfulCount: 0 },
    ])));
    await page.goto('/stall/2');
    const helpful = page.getByRole('button', { name: /Helpful.*dana/ });
    await helpful.scrollIntoViewIfNeeded({ timeout: 20_000 });
    await expect(helpful).toBeVisible();
    await expect(page.getByRole('button', { name: /Helpful.*eli/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /Report review by dana/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /Report review by eli/ })).toBeVisible();
  });
});

test.describe('Announcements', () => {
  test('a critical sitewide announcement is announced as an alert', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/announcement*', (r) => r.fulfill(json({ announcement: { id: 9, message: 'Trading is paused for emergency maintenance', severity: 'CRITICAL' } })));
    await page.goto('/market');
    await expect(page.getByRole('alert').filter({ hasText: /Trading is paused/ })).toBeVisible({ timeout: 20_000 });
  });
});

test.describe('My Stall', () => {
  const L = (id, name, extra = {}) => ({ id, price: 2 + id, listingType: 'BUY_NOW', status: 'ACTIVE', hidden: false,
    listedAt: Date.now() - 86400e3, item: { id: id + 50, name, category: 'Hats', rarity: 'Standard' }, ...extra });
  async function myStall(page) {
    await signedIn(page);
    let rows = [L(1, 'Red Hat'), L(2, 'Blue Hat'), L(3, 'Green Hat'), L(4, 'Gold Hat', { listingType: 'AUCTION', expiresAt: Date.now() + 9e6 })];
    await page.route('**/api/listings/my-stall', (r) => r.fulfill(json(rows)));
    await page.route(/\/api\/listings\/\d+$/, (r) => {
      const id = Number(r.request().url().split('/').pop());
      if (r.request().method() === 'DELETE') rows = rows.filter((x) => x.id !== id);
      return r.fulfill(json({ ok: true }));
    });
    await page.route(/\/api\/listings\/\d+\/stall$/, (r) => {
      const id = Number(r.request().url().split('/').slice(-2)[0]);
      rows = rows.map((x) => (x.id === id ? { ...x, hidden: !x.hidden } : x));
      return r.fulfill(json({ ok: true }));
    });
    await page.goto('/me/stall');
  }

  test('removing a listing moves focus to the next row\'s Cancel listing', async ({ page }) => {
    await myStall(page);
    await page.getByRole('button', { name: 'Cancel listing for Red Hat' }).click({ timeout: 20_000 });
    await expect(page.getByRole('button', { name: 'Cancel listing for Red Hat' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Cancel listing for Blue Hat' })).toBeFocused();
  });

  test('hiding a listing under the Buy Now filter keeps focus in the list', async ({ page }) => {
    await myStall(page);
    await page.getByRole('button', { name: /^Buy Now ·/ }).click({ timeout: 20_000 });
    await page.getByRole('button', { name: 'Hide Red Hat' }).click();
    await expect(page.getByRole('button', { name: 'Hide Red Hat' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Hide Blue Hat' })).toBeFocused();
  });
});

test.describe('Sell form', () => {
  const inventoryRow = { assetId: '1000000002', assetIds: ['1000000002'], quantity: 1, listedCount: 0, listableQuantity: 1,
    name: 'Grand Ball Gown', category: 'Workshop', rarity: 'Standard', tradable: true, marketable: true, catalogueId: 38, suggestedPrice: 230 };

  test('Listing type and Auction duration choices are named groups', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/steam/inventory*', (r) => r.fulfill(json({ count: 1, items: [inventoryRow] })));
    await page.goto('/sell');
    await page.locator('.inventory-item').first().click({ timeout: 20_000 });
    await page.getByRole('group', { name: 'Listing type' }).getByRole('button', { name: 'Auction', exact: true }).click();
    await expect(page.getByRole('group', { name: 'Auction duration' }).getByRole('button', { name: '1d', exact: true })).toHaveAttribute('aria-pressed', 'true');
  });
});
