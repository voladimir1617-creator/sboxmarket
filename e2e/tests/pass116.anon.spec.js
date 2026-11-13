// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 116.

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

// Defect pass 116: Help and FAQ search when a link reopens the same page,
// and keyboard focus after the item page's "N listings" chip.

const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';

async function consent(page) {
  await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
}

test.describe('pass116 · Help and FAQ search follow the URL', () => {
  test('Help: clicking the header Help link while a search is open clears the search with the ?q=', async ({ page }) => {
    await consent(page);
    await page.goto(`${base}/help?q=2fa`);
    const box = page.getByRole('searchbox', { name: 'Search frequently asked questions' });
    await expect(box).toHaveValue('2fa', { timeout: 20_000 });
    await expect(page.getByRole('button', { name: 'What is SkinBox?', exact: true })).toHaveCount(0);
    await page.getByRole('navigation').getByRole('link', { name: /^Help$/ }).first().click();
    await expect(page).toHaveURL(/\/help$/);
    await expect(box).toHaveValue('');
    await expect(page.getByRole('button', { name: 'What is SkinBox?', exact: true })).toBeVisible();
  });

  test('Help: an in-app link to /help?q=API while Help is open shows the API search', async ({ page }) => {
    await consent(page);
    await page.goto(`${base}/help?q=2fa`);
    const box = page.getByRole('searchbox', { name: 'Search frequently asked questions' });
    await expect(box).toHaveValue('2fa', { timeout: 20_000 });
    // The same router call a link makes (Profile > Developers links /help?q=API).
    await page.evaluate(() => { history.pushState({}, '', '/help?q=API'); window.dispatchEvent(new PopStateEvent('popstate')); });
    await expect(box).toHaveValue('API');
    await expect(page.getByRole('button', { name: /use the API from a bot/ })).toBeVisible();
  });

  test('FAQ: following the footer FAQ link while a search is open clears the search with the ?q=', async ({ page }) => {
    await consent(page);
    await page.goto(`${base}/faq?q=zzzqx`);
    await expect(page.getByRole('heading', { name: /No FAQs match "zzzqx"/ })).toBeVisible({ timeout: 20_000 });
    const link = page.getByRole('contentinfo').getByRole('link', { name: /^FAQ$/ });
    await link.focus();
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(/\/faq$/);
    await expect(page.getByRole('heading', { name: /No FAQs match/ })).toHaveCount(0);
    await expect(page.getByRole('heading', { name: /^How do I sign in\?/ })).toBeAttached();
  });
});

test.describe('pass116 · item page listings chip and keyboard focus', () => {
  test('real app: Enter on the "N listings" chip moves focus to Active Listings, so Tab continues in the listings', async ({ page }) => {
    await consent(page);
    await page.goto(`${base}/item/12`);
    const chip = page.getByRole('button', { name: /^\d+ listings?$/ });
    await expect(chip).toBeVisible({ timeout: 20_000 });
    await chip.focus();
    await page.keyboard.press('Enter');
    const heading = page.getByRole('heading', { name: /^Active Listings \(\d+\)/ });
    await expect(heading).toBeFocused();
    await expect(heading).toBeInViewport();
    await page.keyboard.press('Tab');
    const next = await page.evaluate(() => !!document.activeElement.closest('.modal-listings'));
    expect(next).toBe(true);
  });

  test('stubbed phone: the chip moves focus to Active Listings too', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await consent(page);
    const item = { id: 1, name: 'Wizard Hat', category: 'Hats', rarity: 'Standard', lowestPrice: 1.5, supply: 2, isListed: true };
    const listing = (id) => ({ id, item, price: 1.5 + id, sellerName: 'Seller' + id, sellerUserId: 10 + id, status: 'ACTIVE', listingType: 'BUY_NOW', listedAt: Date.now() - 3600e3 });
    await page.route('**/api/items/1', (r) => r.fulfill(json(item)));
    await page.route('**/api/listings/item/1', (r) => r.fulfill(json([listing(1), listing(2)])));
    await page.route('**/api/items/1/history', (r) => r.fulfill(json([])));
    await page.goto(`${base}/item/1`);
    const chip = page.getByRole('button', { name: '2 listings' });
    await chip.scrollIntoViewIfNeeded({ timeout: 20_000 });
    await chip.focus();
    await page.keyboard.press('Enter');
    const heading = page.getByRole('heading', { name: /^Active Listings \(2\)/ });
    await expect(heading).toBeFocused();
  });
});
