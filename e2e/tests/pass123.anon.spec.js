// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 123.

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

// Pass 123: stall review rows. The seller's Reply / Edit reply / Remove reply
// buttons all read the same on every row, and closing the reply box (Post
// reply, Cancel, Escape), removing a reply or deleting your own review left
// keyboard focus on the page instead of the row.

const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';
const consent = (page) => page.addInitScript(() => {
  try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {}
});
const devLogin = (page, userId = 1) => page.request.get(`${base}/api/auth/steam/dev-login?userId=${userId}&next=/`);
const expectPageNotFocused = (page) => expect.poll(() => page.evaluate(() => document.activeElement === document.body)).toBe(false);

const now = Date.now();
const STALL = { seller: { id: ME.id, displayName: ME.displayName, soldCount: 4 }, count: 0, rating: { count: 2, average: 4.5 }, listings: [] };

// A stall whose review list follows replies and deletes the stubs accept.
async function stallWithReviews(page, sellerId, reviews) {
  let rows = reviews.map((r) => ({ ...r }));
  await page.route(`**/api/listings/stall/${sellerId}`, (r) => r.fulfill(json({ ...STALL, seller: { ...STALL.seller, id: sellerId } })));
  await page.route(`**/api/reviews/user/${sellerId}`, (r) => r.fulfill(json(rows)));
  await page.route('**/api/reviews/*/reply', async (r) => {
    const id = Number(r.request().url().match(/reviews\/(\d+)\/reply/)[1]);
    const reply = JSON.parse(r.request().postData() || '{}').reply || null;
    rows = rows.map((x) => (x.id === id ? { ...x, sellerReply: reply } : x));
    await r.fulfill(json({ ok: true }));
  });
  await page.route('**/api/reviews/*', async (r) => {
    if (r.request().method() !== 'DELETE') return r.fallback();
    const id = Number(r.request().url().match(/reviews\/(\d+)/)[1]);
    rows = rows.filter((x) => x.id !== id);
    await r.fulfill(json({ ok: true }));
  });
}

test.describe('Stall Reviews tab: seller reply controls', () => {
  const REVIEWS = [
    { id: 11, rating: 5, comment: 'fast', fromDisplayName: 'dana', createdAt: now - 1000, sellerReply: 'Thanks!' },
    { id: 12, rating: 4, comment: 'fine', fromDisplayName: 'eli', createdAt: now - 2000 },
  ];

  for (const vp of [{ width: 1440, height: 900 }, { width: 390, height: 844 }]) {
    test(`each Reply, Edit reply and Remove reply names its reviewer at ${vp.width}px (stubbed)`, async ({ page }) => {
      await page.setViewportSize(vp);
      await consent(page);
      await signedIn(page);
      await stallWithReviews(page, ME.id, REVIEWS);
      await page.goto(`/stall/${ME.id}`);
      await expect(page.getByRole('button', { name: /^Edit reply to dana.s review$/ })).toBeVisible({ timeout: 20_000 });
      await expect(page.getByRole('button', { name: /^Remove reply to dana.s review$/ })).toBeVisible();
      await expect(page.getByRole('button', { name: /^Reply to eli.s review$/ })).toBeVisible();
    });
  }

  test('Escape and Cancel in the reply box return focus to that row.s Reply button (stubbed)', async ({ page }) => {
    await consent(page);
    await signedIn(page);
    await stallWithReviews(page, ME.id, REVIEWS);
    await page.goto(`/stall/${ME.id}`);
    const row = page.locator('.stall-review').filter({ hasText: 'eli' });
    await row.getByRole('button', { name: /Reply/ }).click({ timeout: 20_000 });
    await expect(page.getByRole('textbox', { name: 'Public reply to this review' })).toBeFocused();
    await page.keyboard.press('Escape');
    await expect(page.getByRole('textbox', { name: 'Public reply to this review' })).toHaveCount(0);
    await expect(row.getByRole('button', { name: /Reply/ })).toBeFocused();
    await page.keyboard.press('Enter');
    await page.getByRole('button', { name: 'Cancel', exact: true }).click();
    await expect(row.getByRole('button', { name: /Reply/ })).toBeFocused();
  });

  test('Post reply and Remove reply keep focus on the row (stubbed)', async ({ page }) => {
    await consent(page);
    await signedIn(page);
    await stallWithReviews(page, ME.id, REVIEWS);
    await page.goto(`/stall/${ME.id}`);
    const row = page.locator('.stall-review').filter({ hasText: 'eli' });
    await row.getByRole('button', { name: /Reply/ }).click({ timeout: 20_000 });
    await page.getByRole('textbox', { name: 'Public reply to this review' }).fill('Glad it went well');
    await page.getByRole('button', { name: 'Post reply' }).focus();
    await page.keyboard.press('Enter');
    await expect(row.getByText('Glad it went well')).toBeVisible();
    await expect(row.getByRole('button', { name: /Edit reply/ })).toBeFocused();

    await row.getByRole('button', { name: /Remove reply/ }).focus();
    await page.keyboard.press('Enter');
    await expect(row.getByText('Glad it went well')).toHaveCount(0);
    await expect(row.getByRole('button', { name: /^Reply to eli/ })).toBeFocused();
  });
});

test.describe('Stall Reviews tab: deleting your own review', () => {
  test('Delete my review moves focus past the removed row, not to the page (stubbed)', async ({ page }) => {
    await consent(page);
    await signedIn(page);
    await stallWithReviews(page, 2, [
      { id: 21, rating: 2, comment: 'slow', fromDisplayName: ME.displayName, createdAt: now - 1000, mine: true },
      { id: 22, rating: 5, comment: 'great', fromDisplayName: 'fay', createdAt: now - 2000 },
    ]);
    await page.goto('/stall/2');
    const del = page.getByRole('button', { name: 'Delete my review' });
    await del.focus({ timeout: 20_000 });
    await page.keyboard.press('Enter');
    await expect(page.getByText('slow')).toHaveCount(0);
    await expectPageNotFocused(page);
  });
});

test.describe('Profile > Reviews: the same reply and delete controls', () => {
  test('Received: Remove reply keeps focus on that row.s Reply (stubbed)', async ({ page }) => {
    await consent(page);
    await signedIn(page);
    await stallWithReviews(page, ME.id, [
      { id: 31, rating: 5, comment: 'fast', fromDisplayName: 'gus', createdAt: now - 1000, sellerReply: 'Cheers' },
    ]);
    await page.route(`**/api/reviews/user/${ME.id}/summary`, (r) => r.fulfill(json({ count: 1, average: 5 })));
    await page.goto('/profile/reviews');
    await page.getByRole('button', { name: /Remove reply to gus/ }).focus({ timeout: 20_000 });
    await page.keyboard.press('Enter');
    await expect(page.getByText('Cheers')).toHaveCount(0);
    await expect(page.getByRole('button', { name: /^Reply to gus/ })).toBeFocused();
  });

  test('Given: Delete moves focus past the removed row, not to the page (stubbed)', async ({ page }) => {
    await consent(page);
    await signedIn(page);
    let mine = [
      { id: 41, rating: 2, comment: 'slow', toUserId: 8, toUserName: 'hank', createdAt: now - 1000 },
      { id: 42, rating: 5, comment: 'great', toUserId: 9, toUserName: 'ivy', createdAt: now - 2000 },
    ];
    await page.route('**/api/reviews/mine', (r) => r.fulfill(json(mine)));
    await page.route('**/api/reviews/41', (r) => { mine = mine.filter((x) => x.id !== 41); return r.fulfill(json({ ok: true })); });
    await page.goto('/profile/reviews?view=given');
    await page.getByRole('button', { name: /Delete your review of hank/ }).focus({ timeout: 20_000 });
    await page.keyboard.press('Enter');
    await expect(page.getByText('slow')).toHaveCount(0);
    await expectPageNotFocused(page);
  });
});
