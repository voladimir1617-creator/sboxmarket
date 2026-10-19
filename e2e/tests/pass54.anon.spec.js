// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 54.

const ME = {
  id: 1,
  steamId64: '76561199000000001',
  displayName: 'Pass Tester',
  signedIn: true,
  sessionEpoch: 0,
  tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc',
};

const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });

async function signedIn(page) {
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json(ME)));
}

async function myStall(page, away) {
  await signedIn(page);
  const item = { id: 5401, name: 'Stall Hat', rarity: 'Standard', category: 'Hats' };
  await page.route('**/api/listings/my-stall', (r) => r.fulfill(json([
    { id: 5402, price: 3, status: 'ACTIVE', listingType: 'BUY_NOW', itemId: 5401, item, sellerUserId: 1 },
  ])));
  await page.route('**/api/listings/my-stall/**', (r) => r.fulfill(json([])));
  const posts = [];
  await page.route('**/api/listings/away', (r) => {
    if (r.request().method() === 'POST') { posts.push(r.request().postDataJSON()); return r.fulfill(json({})); }
    return r.fulfill(json(away));
  });
  await page.goto('/me/stall');
  return posts;
}

async function reviews(page, { received = [], given = [] }) {
  await signedIn(page);
  await page.route('**/api/reviews/user/1', (r) => r.fulfill(json(received)));
  await page.route('**/api/reviews/user/1/summary', (r) => r.fulfill(json({ count: received.length, average: 5 })));
  await page.route('**/api/reviews/mine', (r) => r.fulfill(json(given)));
  await page.route('**/api/reviews/pending', (r) => r.fulfill(json([])));
}

test.describe('defect pass 54', () => {
  test('the stall Away Mode toggle is a named switch that reports its state', async ({ page }) => {
    await myStall(page, { hidden: false });
    const sw = page.getByRole('switch', { name: 'Away mode (hide all listings)' });
    await expect(sw).toHaveAttribute('aria-checked', 'false', { timeout: 20_000 });
  });

  test('the stall return date sets on Enter and Escape cancels', async ({ page }) => {
    const posts = await myStall(page, { hidden: true });
    const open = page.getByRole('button', { name: 'Schedule return' });
    await open.click({ timeout: 20_000 });
    const date = page.getByLabel('Stall return date');
    await date.press('Escape');
    await expect(date).toHaveCount(0);
    await open.click();
    const d = new Date(Date.now() + 5 * 86400000);
    const ymd = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
    await date.fill(ymd);
    await date.press('Enter');
    await expect.poll(() => posts.length).toBe(1);
  });

  test('the email notifications switch is named, focusable and toggles on Space', async ({ page }) => {
    await signedIn(page);
    let puts = 0;
    await page.route('**/api/profile/email-notifications', (r) => { puts += 1; return r.fulfill(json({ enabled: false })); });
    await page.goto('/profile');
    const sw = page.getByRole('switch', { name: 'Email notifications' });
    await expect(sw).toHaveAttribute('aria-checked', 'true', { timeout: 20_000 });
    await expect(sw).toHaveAttribute('tabindex', '0');
    await sw.press(' ');
    await expect(sw).toHaveAttribute('aria-checked', 'false');
    await expect.poll(() => puts).toBe(1);
  });

  test('the trade URL edit saves on Enter and Escape cancels', async ({ page }) => {
    await signedIn(page);
    let puts = 0;
    await page.route('**/api/profile/trade-url', (r) => { puts += 1; return r.fulfill(json({ tradeUrl: ME.tradeUrl })); });
    await page.goto('/profile');
    const row = page.locator('.profile-row', { hasText: 'Steam trade URL' }).first();
    const edit = row.getByRole('button', { name: /^(Add|Edit) Steam trade URL$/ });
    await edit.click({ timeout: 20_000 });
    const box = page.getByRole('textbox', { name: 'Steam trade URL' });
    await box.press('Escape');
    await expect(box).toHaveCount(0);
    await edit.click();
    await box.fill(ME.tradeUrl);
    await box.press('Enter');
    await expect.poll(() => puts).toBe(1);
  });

  test('editing a review you wrote: stars are named and pressed, Escape cancels', async ({ page }) => {
    await reviews(page, { given: [{ id: 11, rating: 4, comment: 'ok', toUserId: 9, toUserName: 'bob', toDisplayName: 'bob', tradeId: 9, createdAt: Date.now() }] });
    await page.goto('/profile/reviews');
    await page.getByRole('tab', { name: /^Given/ }).first().click({ timeout: 20_000 });
    await page.getByRole('button', { name: '✎ Edit' }).click();
    await expect(page.getByRole('button', { name: '4 stars' })).toHaveAttribute('aria-pressed', 'true');
    await page.getByRole('button', { name: '2 stars' }).click();
    await expect(page.getByRole('button', { name: '2 stars' })).toHaveAttribute('aria-pressed', 'true');
    await expect(page.getByRole('button', { name: '4 stars' })).toHaveAttribute('aria-pressed', 'false');
    const box = page.getByRole('textbox', { name: 'Edit your review comment' });
    await box.press('Escape');
    await expect(box).toHaveCount(0);
  });

  test('a reply draft closes on Escape, and Remove reply sends once on a double-click', async ({ page }) => {
    await reviews(page, { received: [{ id: 5, rating: 5, comment: 'great', fromDisplayName: 'amy', sellerReply: 'thanks', createdAt: Date.now() }] });
    let posts = 0;
    await page.route('**/api/reviews/5/reply', async (r) => {
      posts += 1;
      await new Promise((ok) => setTimeout(ok, 400));
      return r.fulfill(json({ id: 5 }));
    });
    await page.goto('/profile/reviews');
    await page.getByRole('button', { name: '✎ Edit reply' }).click({ timeout: 20_000 });
    const box = page.getByRole('textbox', { name: 'Seller reply to review' });
    await box.press('Escape');
    await expect(box).toHaveCount(0);
    await page.getByRole('button', { name: 'Remove reply' }).dblclick();
    await page.waitForTimeout(900);
    expect(posts).toBe(1);
  });

  test('the offer drawer quotes the asking price in dollars, like its hint', async ({ page }) => {
    await page.addInitScript(() => localStorage.setItem('sb_currency', 'EUR'));
    await signedIn(page);
    const item = { id: 5410, name: 'Offer Hat', lowestPrice: 10, rarity: 'Standard', category: 'Hats', isListed: true };
    await page.route('**/api/items/5410', (r) => r.fulfill(json(item)));
    await page.route('**/api/listings/item/5410*', (r) => r.fulfill(json([
      { id: 5411, price: 10, listingType: 'BUY_NOW', status: 'ACTIVE', sellerUserId: 9, sellerName: 'Seller', item },
    ])));
    await page.goto('/item/5410');
    await page.getByTestId('listing-offer-btn').first().click({ timeout: 20_000 });
    await expect(page.locator('.offer-drawer-ask')).toHaveText(' · must be below the $10.00 asking price');
  });
});
