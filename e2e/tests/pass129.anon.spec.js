// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 129.

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


// Defect pass 129. Real-jar tests take the server from JAR / E2E_BASE_URL;
// stubbed tests use the Playwright baseURL (the static copy).
const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';

async function consent(page) {
  await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
}
const REVIEWS = [5, 5, 4, 1, 3].map((rating, i) => ({
  id: 9100 + i, rating, comment: `Pass129 review ${i}`, createdAt: Date.now() - i * 86_400_000,
  fromDisplayName: `buyer${i}`, helpfulCount: i,
}));

test.describe('pass129 · stall Reviews filter and section headings (real jar)', () => {
  test('stall Reviews: the star filter survives opening a listing and coming Back', async ({ page }) => {
    await consent(page);
    await page.route('**/api/reviews/user/1*', (r) => r.fulfill(json(REVIEWS)));
    await page.goto(`${base}/stall/1`);
    const one = page.getByRole('button', { name: 'Show 1-star reviews' });
    await one.scrollIntoViewIfNeeded({ timeout: 20_000 });
    await one.click();
    await expect(one).toHaveAttribute('aria-pressed', 'true');
    await expect(page.getByText('Pass129 review 3')).toBeVisible();
    await expect(page.getByText('Pass129 review 0')).toHaveCount(0);
    await page.locator('#main a[href^="/item/"]').first().click();
    await expect(page).toHaveURL(/\/item\/\d+/);
    await page.goBack();
    await expect(page).toHaveURL(/\/stall\/1/);
    await expect(one).toHaveAttribute('aria-pressed', 'true', { timeout: 15_000 });
    await expect(page.getByText('Pass129 review 0')).toHaveCount(0);
    await expect(page.getByText('Pass129 review 3')).toBeVisible();
  });

  test('stall: Recent reviews and the side cards are headings a screen reader can jump to', async ({ page }) => {
    await consent(page);
    await page.route('**/api/reviews/user/1*', (r) => r.fulfill(json(REVIEWS)));
    await page.goto(`${base}/stall/1`);
    await expect(page.getByRole('button', { name: 'Show 1-star reviews' })).toBeAttached({ timeout: 20_000 });
    await expect(page.getByRole('heading', { level: 2, name: /^Recent reviews \(5\)$/ })).toBeAttached();
    await expect(page.getByRole('heading', { level: 2, name: 'Trust signals' })).toBeAttached();
    await expect(page.getByRole('heading', { level: 2, name: 'Keep browsing' })).toBeAttached();
  });
});

test.describe('pass129 · Profile Reviews star filter (stubbed)', () => {
  test('Profile > Reviews > Given: the star filter survives a stall link and Back', async ({ page }) => {
    await consent(page);
    await signedIn(page);
    // A stubbed {} stall has no seller and crashes the stall page; answer 404 (Stall not found).
    await page.route(/\/api\/listings\/stall\/41(\?|$)/, (r) => r.fulfill(json({ message: 'Not found' }, 404)));
    await page.route('**/api/reviews/mine*', (r) => r.fulfill(json(REVIEWS.map((x) => ({ ...x, toUserId: 40 + x.rating, toUserName: `seller${x.rating}` })))));
    await page.goto('/profile/reviews?view=given');
    const one = page.getByRole('button', { name: 'Show 1-star reviews' });
    await one.click({ timeout: 20_000 });
    await expect(one).toHaveAttribute('aria-pressed', 'true');
    await page.getByRole('link', { name: /about seller1/ }).click();
    await expect(page).toHaveURL(/\/stall\/41/);
    await page.goBack();
    await expect(page).toHaveURL(/\/profile\/reviews/);
    await expect(one).toHaveAttribute('aria-pressed', 'true', { timeout: 15_000 });
    await expect(page.getByRole('link', { name: /about seller5/ })).toHaveCount(0);
  });
});

test.describe('pass129 · Status page (real jar)', () => {
  test('status.html: the overall result is a heading in a polite live region, rows are headings', async ({ page }) => {
    await page.goto(`${base}/status.html`);
    const live = page.getByRole('status');
    await expect(live).toHaveCount(1);
    await expect(live).toContainText(/All systems operational|Degraded performance|Partial outage/, { timeout: 15_000 });
    await expect(live.getByRole('heading', { level: 2 })).toHaveText(/All systems operational|Degraded performance|Partial outage/);
    await expect(page.getByRole('heading', { level: 3, name: 'Database' })).toBeVisible();
    await expect(page.getByRole('heading', { level: 3 })).toHaveCount(7);
  });

  test('status.html: a 30-second re-check with no change does not rewrite (and re-announce) the live region', async ({ page }) => {
    await page.goto(`${base}/status.html`);
    await expect(page.locator('#headline')).toContainText(/All systems operational|Degraded performance|Partial outage/, { timeout: 15_000 });
    await page.evaluate(() => {
      window.__pass129 = 0;
      new MutationObserver((m) => { window.__pass129 += m.length; }).observe(document.getElementById('hero'), { subtree: true, childList: true, characterData: true });
    });
    // Same answers as the first pass: freeze the clock-dependent sub-lines' source.
    await page.evaluate(() => window.runChecks());
    await page.waitForTimeout(1500);
    expect(await page.evaluate(() => window.__pass129)).toBe(0);
  });
});
