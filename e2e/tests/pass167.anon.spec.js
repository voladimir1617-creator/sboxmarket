// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 167.

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

// Pass 167 — market search arrows, Back from an item, Profile review replies,
// stall header names and toast, status page on a phone.

// Real-jar tests: the running app, signed in through dev-login.
const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';
const consent = (page) => page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
// Real-jar tests never write: every POST/PUT/PATCH/DELETE is answered here.
const noWrites = (page) => page.route('**/api/**', (r) => {
  const m = r.request().method();
  if (m === 'GET' || m === 'HEAD') return r.fallback();
  return r.fulfill(json({ ok: true }));
});
const devLogin = (page, id) => page.request.get(`${base}/api/auth/steam/dev-login?userId=${id}&next=/`);

test.describe('market search suggestions', () => {
  test('ArrowUp from the box highlights the last suggestion, not the one above it', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await page.goto(`${base}/market`);
    const box = page.getByRole('combobox', { name: 'Search listings' });
    await box.click({ timeout: 20_000 });
    await box.fill('hat');
    const opts = page.getByRole('listbox', { name: 'Search suggestions' }).getByRole('option');
    await expect(opts.nth(2)).toBeVisible({ timeout: 15_000 });
    const n = await opts.count();
    await box.press('ArrowUp');
    await expect(box).toHaveAttribute('aria-activedescendant', 'search-opt-' + (n - 1));
    await expect(opts.nth(n - 1)).toHaveAttribute('aria-selected', 'true');
  });

  test('ArrowUp in Recent searches goes to the last search', async ({ page }) => {
    await consent(page);
    await page.addInitScript(() => { try { localStorage.setItem('sb_recent_searches', JSON.stringify(['boots', 'gloves', 'crown'])); } catch (_) {} });
    await page.route('**/api/**', (r) => r.fulfill(json({})));
    await page.route('**/api/auth/steam/me', (r) => r.fulfill(json({ signedIn: false }, 401)));
    await page.route('**/api/listings*', (r) => r.fulfill(json({ items: [], total: 0 })));
    await page.goto('/market');
    const box = page.getByRole('combobox', { name: 'Search listings' });
    await box.click();
    await expect(page.getByRole('listbox', { name: 'Recent searches' })).toBeVisible();
    await box.press('ArrowUp');
    await expect(box).toHaveAttribute('aria-activedescendant', 'search-recent-opt-2');
    await expect(page.getByRole('option', { name: 'crown' })).toHaveAttribute('aria-selected', 'true');
  });
});

test.describe('Profile Reviews Received reply editor', () => {
  const now = Date.now();
  const REVIEWS = [
    { id: 11, rating: 4, comment: 'Quick trade', fromDisplayName: 'dave', fromUserId: 4, toUserId: 3, createdAt: now - 3600e3 },
    { id: 12, rating: 5, comment: 'Great', fromDisplayName: 'erin', fromUserId: 5, toUserId: 3, createdAt: now - 7200e3, sellerReply: 'Thanks!' }
  ];
  const stubReviews = async (page) => {
    await consent(page);
    await signedIn(page);
    await page.route('**/api/reviews/user/' + ME.id, (r) => r.fulfill(json(REVIEWS)));
    await page.route('**/api/reviews/user/' + ME.id + '/summary', (r) => r.fulfill(json({ count: 2, average: 4.5 })));
    await page.route('**/api/reviews/pending', (r) => r.fulfill(json({ items: [] })));
  };

  test('Remove reply on one review keeps the reply being written on another', async ({ page }) => {
    await stubReviews(page);
    await page.route('**/api/reviews/12/reply', (r) => r.fulfill(json({ ok: true })));
    await page.goto('/profile/reviews');
    await page.getByRole('button', { name: /^Reply to dave.s review/ }).click();
    const box = page.getByRole('textbox', { name: 'Seller reply to review' });
    await box.fill('Thanks for the quick trade');
    await page.getByRole('button', { name: /^Remove reply to erin.s review/ }).click();
    await expect(page.getByRole('status').filter({ hasText: /Reply removed from @erin.s review/ })).toBeVisible();
    await expect(box).toHaveValue('Thanks for the quick trade');
  });

  test('a reply sent on an expired session is still there after signing back in', async ({ page }) => {
    await stubReviews(page);
    await page.route('**/api/reviews/11/reply', (r) => r.fulfill(json({ error: 'Not signed in' }, 401)));
    await page.goto('/profile/reviews');
    await page.getByRole('button', { name: /^Reply to dave.s review/ }).click();
    await page.getByRole('textbox', { name: 'Seller reply to review' }).fill('Thanks, enjoy the hat');
    await page.getByRole('button', { name: 'Post reply' }).click();
    await expect(page.getByText(/Not signed in/).first()).toBeVisible();
    await page.reload();
    await expect(page.getByRole('textbox', { name: 'Seller reply to review' })).toHaveValue('Thanks, enjoy the hat');
  });

  test('a Given review edit saved on an expired session is still there after signing back in', async ({ page }) => {
    await stubReviews(page);
    await page.route('**/api/reviews/mine', (r) => r.fulfill(json([
      { id: 21, tradeId: 91, rating: 3, comment: 'Slow', toUserId: 7, toUserName: 'frank', createdAt: now - 3600e3 }
    ])));
    await page.route('**/api/reviews', (r) => r.request().method() === 'POST' ? r.fulfill(json({ error: 'Not signed in' }, 401)) : r.fallback());
    await page.goto('/profile/reviews?view=given');
    await page.getByRole('button', { name: 'Edit your review of frank' }).click();
    await page.getByRole('textbox', { name: 'Edit your review comment' }).fill('Slow, but it arrived');
    await page.getByRole('button', { name: 'Save changes' }).click();
    await expect(page.getByText(/Not signed in/).first()).toBeVisible();
    await page.reload();
    await expect(page.getByRole('textbox', { name: 'Edit your review comment' })).toHaveValue('Slow, but it arrived');
  });
});

test.describe('stall page header', () => {
  test('Share stall announces "Stall link copied", like My Stall and Profile', async ({ page, context }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);
    await consent(page);
    await noWrites(page);
    await devLogin(page, 2);
    await page.goto(`${base}/stall/5`);
    await page.getByRole('button', { name: 'Share stall' }).click({ timeout: 20_000 });
    await expect(page.getByRole('status').filter({ hasText: /Stall link copied/ })).toBeVisible();
  });

  test('Contact is named without its envelope glyph', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await devLogin(page, 2);
    await page.goto(`${base}/stall/5`);
    await expect(page.getByRole('button', { name: /Share stall/ })).toBeVisible({ timeout: 20_000 });
    await expect(page.getByRole('button', { name: 'Contact', exact: true })).toBeVisible();
  });

  test('Manage stall on your own stall is named without its gear glyph', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await devLogin(page, 5);
    await page.goto(`${base}/stall/5`);
    await expect(page.getByRole('button', { name: /Share stall/ })).toBeVisible({ timeout: 20_000 });
    await expect(page.getByRole('link', { name: 'Manage stall', exact: true })).toBeVisible();
  });
});

test.describe('status page on a phone', () => {
  test.use({ viewport: { width: 390, height: 844 } });
  test('each row keeps a gap between its endpoint text and the badge', async ({ page }) => {
    await page.goto(`${base}/status.html`);
    const rows = page.locator('.status-row');
    await expect(rows.first()).toBeVisible({ timeout: 20_000 });
    const gaps = await rows.evaluateAll((els) => els.map((r) =>
      r.lastElementChild.getBoundingClientRect().left - r.firstElementChild.getBoundingClientRect().right));
    expect(gaps.length).toBeGreaterThan(3);
    for (const g of gaps) expect(g).toBeGreaterThanOrEqual(8);
  });
});
