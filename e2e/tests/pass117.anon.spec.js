// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 117.

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


// Defect pass 117: Settings currency box, stall Contact failure focus,
// review star ratings, Profile > Reviews names and failure focus, and the
// Support category of a user report. All run against the real jar.

const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';

async function consent(page) {
  await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
}
async function devLogin(page, id) {
  await page.request.get(`${base}/api/auth/steam/dev-login?userId=${id}&next=/`);
}
const failSlow = (ms = 500) => async (r) => {
  if (r.request().method() === 'GET') return r.fallback();
  await new Promise((ok) => setTimeout(ok, ms));
  return r.fulfill(json({ code: 'INTERNAL', message: 'Something broke on our side' }, 500));
};
const REVIEWS = [
  { id: 701, rating: 4, comment: 'Smooth trade', fromDisplayName: 'amy', createdAt: Date.now() - 3_600_000, helpfulCount: 0 },
  { id: 702, rating: 2, comment: 'Slow to send', fromDisplayName: 'ben', createdAt: Date.now() - 7_200_000, helpfulCount: 0 },
];

test.describe('pass117 · Settings', () => {
  test('Settings: a currency picked in the header shows in the Settings currency box', async ({ page }) => {
    await consent(page);
    await page.goto(`${base}/settings`);
    const box = page.getByRole('combobox', { name: 'Display currency' });
    await expect(box).toHaveValue('USD', { timeout: 20_000 });
    await page.getByRole('button', { name: /^Currency selector/ }).click();
    await page.locator('.nav-picker-panel').getByRole('option', { name: /GBP/ }).click();
    await expect(page.getByRole('button', { name: /^Currency selector: GBP/ })).toBeVisible();
    await expect(box).toHaveValue('GBP');
  });
});

test.describe('pass117 · Stall page', () => {
  test('Stall: a failed Contact message keeps keyboard focus on Send', async ({ page }) => {
    await consent(page);
    await devLogin(page, 1);
    await page.route(`${base}/api/support/tickets`, failSlow());
    await page.goto(`${base}/stall/2`);
    await page.getByRole('button', { name: /Contact/ }).click({ timeout: 20_000 });
    await page.getByRole('textbox', { name: 'Message to support about this seller' }).fill('Is the Police Hat still available?');
    const send = page.getByRole('dialog', { name: /Contact @AtlasTrades/ }).getByRole('button', { name: /^Send/ });
    await send.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByText(/Something broke on our side/).first()).toBeVisible();
    await expect(send).toBeFocused();
  });

  test('Stall: each review star rating is read as "N out of 5 stars"', async ({ page }) => {
    await consent(page);
    await page.route(/\/api\/reviews\/user\/2(\?.*)?$/, (r) => r.fulfill(json(REVIEWS)));
    await page.goto(`${base}/stall/2`);
    const reviews = page.locator('.stall-reviews');
    await reviews.scrollIntoViewIfNeeded({ timeout: 20_000 });
    await expect(reviews.getByRole('img', { name: '4 out of 5 stars' })).toBeVisible();
    await expect(reviews.getByRole('img', { name: '2 out of 5 stars' })).toBeVisible();
  });
});

test.describe('pass117 · Profile > Reviews', () => {
  const GIVEN = [
    { id: 801, tradeId: 801, rating: 5, comment: 'Great', toUserId: 2, toUserName: 'AtlasTrades', createdAt: Date.now() - 3_600_000, mine: true },
    { id: 802, tradeId: 802, rating: 3, comment: 'Fine', toUserId: 4, toUserName: 'GhostlyDeals', createdAt: Date.now() - 7_200_000, mine: true },
  ];

  test('Given: Edit and Delete name the review they act on, and stars read as a rating', async ({ page }) => {
    await consent(page);
    await devLogin(page, 3);
    await page.route(/\/api\/reviews\/mine(\?.*)?$/, (r) => r.fulfill(json(GIVEN)));
    await page.goto(`${base}/profile/reviews?view=given`);
    await expect(page.getByRole('button', { name: 'Edit your review of AtlasTrades' })).toBeVisible({ timeout: 20_000 });
    await expect(page.getByRole('button', { name: 'Delete your review of GhostlyDeals' })).toBeVisible();
    await expect(page.getByRole('img', { name: '3 out of 5 stars' })).toBeVisible();
  });

  test('Given: a failed Save changes keeps keyboard focus on Save changes', async ({ page }) => {
    await consent(page);
    await devLogin(page, 3);
    await page.route(/\/api\/reviews\/mine(\?.*)?$/, (r) => r.fulfill(json(GIVEN)));
    await page.route(`${base}/api/reviews`, failSlow());
    await page.goto(`${base}/profile/reviews?view=given`);
    await page.getByRole('button', { name: /Edit your review of AtlasTrades|✎ Edit/ }).first().click({ timeout: 20_000 });
    const save = page.getByRole('button', { name: /Save changes|Saving/ });
    await save.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByText(/Something broke on our side/).first()).toBeVisible();
    await expect(save).toBeFocused();
  });

  test('Received: a failed Post reply keeps keyboard focus on Post reply', async ({ page }) => {
    await consent(page);
    await devLogin(page, 1);
    await page.route(/\/api\/reviews\/user\/1(\?.*)?$/, (r) => r.fulfill(json(REVIEWS)));
    await page.route(`${base}/api/reviews/701/reply`, failSlow());
    await page.goto(`${base}/profile/reviews`);
    await page.getByRole('button', { name: /^Reply to amy.s review/ }).click({ timeout: 20_000 });
    await page.getByRole('textbox', { name: 'Seller reply to review' }).fill('Thanks for trading');
    const post = page.getByRole('button', { name: /Post reply|Saving/ });
    await post.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByText(/Something broke on our side/).first()).toBeVisible();
    await expect(post).toBeFocused();
    await expect(page.getByRole('textbox', { name: 'Seller reply to review' })).toHaveValue('Thanks for trading');
  });
});

test.describe('pass117 · Support on a phone', () => {
  test.use({ viewport: { width: 390, height: 844 } });

  test('Support: a seller report filed from a stall is listed under a readable category', async ({ page }) => {
    await consent(page);
    await devLogin(page, 5);
    await page.goto(`${base}/stall/6`);
    await page.getByRole('button', { name: 'Report', exact: true }).click({ timeout: 20_000 });
    await page.getByRole('button', { name: 'File report' }).click();
    await expect(page.getByRole('dialog', { name: 'Report seller' })).toHaveCount(0);
    await page.goto(`${base}/support`);
    const row = page.getByRole('button', { name: /^Open ticket #\d+ . Report of TradeHaven/ }).first();
    await expect(row).toBeVisible({ timeout: 20_000 });
    await expect(row).toContainText('User report');
    await expect(row).not.toContainText('FRAUD');
    await row.click();
    await expect(page.getByText(/^User report · Waiting staff$/)).toBeVisible();
  });
});
