// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 93.

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

test.describe('defect pass 93 · a', () => {

  const notBody = (page) => expect.poll(() => page.evaluate(() => document.activeElement.tagName)).not.toBe('BODY');
  const LO = { id: 5, name: 'Beach day', public: true, ownerName: 'dave', userId: 9, slots: [], favoriteCount: 0, totalPrice: 3 };
  const LISTINGS = Array.from({ length: 12 }, (_, i) => ({ id: 100 + i, price: 2 + i, listingType: 'BUY_NOW', status: 'ACTIVE', listedAt: Date.now() - i * 1000, item: { id: 10 + i, name: 'Hat ' + i, category: 'Hats', rarity: 'Standard', imageUrl: '' } }));

  test('profile Reviews: arrowing onto the Pending tab keeps keyboard focus on the tabs', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/profile/me', (r) => r.fulfill(json({ user: { ...ME }, accountStanding: { level: 'GOOD' } })));
    await page.route('**/api/reviews/user/3', (r) => r.fulfill(json([{ id: 5, rating: 5, comment: 'great', fromDisplayName: 'dave', createdAt: Date.now() }])));
    await page.route('**/api/reviews/mine', (r) => r.fulfill(json([])));
    await page.route('**/api/reviews/pending', (r) => r.fulfill(json({ items: [{ tradeId: 9, sellerUserId: 7, sellerName: 'eve', itemName: 'Blue Hat', settledAt: Date.now(), price: 3 }] })));
    await page.goto('/profile/reviews');
    const given = page.getByRole('tab', { name: /^Given/ });
    const pending = page.getByRole('tab', { name: /^Pending/ });
    await given.click();
    await page.keyboard.press('ArrowRight');
    await expect(pending).toHaveAttribute('aria-selected', 'true');
    await notBody(page);
    await expect(pending).toBeFocused();
    await page.keyboard.press('ArrowLeft');
    await expect(given).toHaveAttribute('aria-selected', 'true');
    await expect(given).toBeFocused();
  });

  test('profile Blocked list: Unblock moves focus to the next Unblock button', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/profile/me', (r) => r.fulfill(json({ user: { ...ME }, accountStanding: { level: 'GOOD' } })));
    await page.route('**/api/profile/blocks?**', (r) => r.fulfill(json({ count: 2, items: [{ blockedUserId: 7, displayName: 'eve' }, { blockedUserId: 8, displayName: 'mallory' }] })));
    await page.route('**/api/profile/blocks/7', (r) => r.fulfill(json({ ok: true })));
    await page.goto('/profile/personal');
    const eve = page.getByRole('button', { name: 'Unblock eve' });
    await eve.scrollIntoViewIfNeeded();
    await eve.focus();
    await page.keyboard.press('Enter');
    await expect(eve).toHaveCount(0);
    await notBody(page);
    await expect(page.getByRole('button', { name: 'Unblock mallory' })).toBeFocused();
  });

  test('admin Announce: Post banner keeps focus while posting and after the form clears', async ({ page }) => {
    await asAdmin(page);
    let rows = [];
    await page.route('**/api/admin/announcements', async (r) => {
      if (r.request().method() === 'POST') {
        await new Promise((ok) => setTimeout(ok, 500));
        rows = [{ id: 1, message: 'Maintenance at 5pm', severity: 'INFO', active: true, createdAt: Date.now() }];
        return r.fulfill(json(rows[0]));
      }
      return r.fulfill(json(rows));
    });
    await page.goto('/admin?tab=announce');
    await page.getByPlaceholder(/Message \(3/).fill('Maintenance at 5pm');
    const post = page.getByRole('button', { name: /^(Post banner|Posting)/ });
    await post.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByText('LIVE NOW')).toBeVisible();
    await notBody(page);
    await expect(post).toBeFocused();
  });

  test('admin Announce: Send broadcast keeps focus after the broadcast goes out', async ({ page }) => {
    await asAdmin(page);
    await page.route('**/api/admin/announcements', (r) => r.fulfill(json([])));
    await page.route('**/api/admin/broadcast', async (r) => { await new Promise((ok) => setTimeout(ok, 500)); r.fulfill(json({ sent: 4, batches: 1 })); });
    await page.goto('/admin?tab=announce');
    await page.getByPlaceholder(/Title \(required/).fill('New feature');
    const send = page.getByRole('button', { name: /(Send broadcast|Broadcasting)/ });
    await send.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('status').filter({ hasText: /Sent to 4 users/ })).toBeVisible();
    await notBody(page);
    await expect(send).toBeFocused();
  });

  test('admin Announce: stopping the last live banner moves focus to the message box', async ({ page }) => {
    await asAdmin(page);
    let rows = [{ id: 1, message: 'Maintenance at 5pm', severity: 'INFO', active: true, createdAt: Date.now() }];
    await page.route('**/api/admin/announcements', (r) => r.fulfill(json(rows)));
    await page.route('**/api/admin/announcements/1', (r) => { rows = [{ ...rows[0], active: false }]; r.fulfill(json({ ok: true })); });
    await page.goto('/admin?tab=announce');
    const stop = page.getByRole('button', { name: /^Stop banner: Maintenance/ });
    await stop.focus();
    await page.keyboard.press('Enter');
    await expect(stop).toHaveCount(0);
    await notBody(page);
    await expect(page.getByPlaceholder(/Message \(3/)).toBeFocused();
  });

  test('Loadout Lab: Clear search on a no-match Discover search keeps focus in the search box', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/loadouts/discover**', (r) => r.fulfill(json(r.request().url().includes('search=') ? [] : [LO])));
    await page.goto('/loadout');
    const box = page.getByRole('textbox', { name: 'Search public loadouts' });
    await box.fill('zzz');
    const clear = page.getByRole('button', { name: 'Clear search' });
    await clear.click();
    await expect(clear).toHaveCount(0);
    await notBody(page);
    await expect(box).toBeFocused();
  });

  test('Loadout Lab: empty-tab Browse buttons move focus to the Discover tab', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/loadouts/discover**', (r) => r.fulfill(json([LO])));
    await page.route('**/api/loadouts/mine', (r) => r.fulfill(json([])));
    await page.route('**/api/loadouts/favorites', (r) => r.fulfill(json([])));
    await page.goto('/loadout');
    const discover = page.getByRole('tab', { name: 'Discover' });
    await page.getByRole('tab', { name: 'My Loadouts' }).click();
    await page.getByRole('button', { name: 'Browse Discover' }).click();
    await expect(discover).toHaveAttribute('aria-selected', 'true');
    await notBody(page);
    await expect(discover).toBeFocused();
    await page.getByRole('tab', { name: 'Favorites' }).click();
    await page.getByRole('button', { name: /^Browse public loadouts/ }).click();
    await expect(discover).toHaveAttribute('aria-selected', 'true');
    await notBody(page);
    await expect(discover).toBeFocused();
  });

  test('stall page: Clear search on a no-match filter keeps focus in the filter box', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings/stall/9', (r) => r.fulfill(json({ seller: { id: 9, displayName: 'bob' }, listings: LISTINGS })));
    await page.route('**/api/listings/stall/9/recent-sales**', (r) => r.fulfill(json([])));
    await page.goto('/stall/9');
    const box = page.getByRole('searchbox', { name: 'Filter stall by item name or category' });
    await box.scrollIntoViewIfNeeded();
    await box.fill('zzz');
    await expect(page.getByText(/No listings match "zzz" in this stall/)).toBeVisible();
    const clear = page.getByRole('button', { name: 'Clear search' });
    await clear.click();
    await expect(clear).toHaveCount(0);
    await notBody(page);
    await expect(box).toBeFocused();
    await expect(page.getByText('Hat 11')).toBeVisible();
  });
});
