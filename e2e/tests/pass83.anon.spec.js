// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 83.

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

test.describe('defect pass 83 · sell', () => {
  const STEAM = { items: [
    { assetId: 'a1', name: 'Red Beanie', rarity: 'Standard', tradable: true, itemId: 11 },
  ], count: 1, lastSyncedAt: Date.now() };

  test('sell form: a rejected listing error is announced as an alert', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/steam/inventory', (r) => r.fulfill(json(STEAM)));
    await page.route('**/api/listings/inventory', (r) => r.fulfill(json([])));
    await page.route('**/api/steam/list', (r) => r.fulfill(json({ code: 'STEAM_DOWN', message: 'Steam inventory service is unavailable' }, 503)));
    await page.goto('/sell');
    await page.getByText('Red Beanie').first().click();
    await page.getByRole('spinbutton').first().fill('5');
    await page.getByRole('button', { name: 'List for Sale' }).click();
    await expect(page.getByText(/Steam inventory service is unavailable/)).toBeVisible();
    await expect(page.getByRole('alert').filter({ hasText: /Steam inventory service is unavailable/ })).toBeVisible();
  });
});

test.describe('defect pass 83 · stall-reply', () => {
  const STALL = { seller: { id: 3, displayName: 'carol', soldCount: 4 }, count: 0, rating: { count: 1, average: 5 }, listings: [] };
  const REVIEW = { id: 5, rating: 5, comment: 'fast and friendly', createdAt: Date.now() - 3600e3, fromDisplayName: 'erin', sellerReply: 'Thanks!' };

  async function ownStall(page) {
    await signedIn(page);
    await page.route('**/api/listings/stall/3', (r) => r.fulfill(json(STALL)));
    await page.route('**/api/reviews/user/3', (r) => r.fulfill(json([REVIEW])));
    await page.route('**/api/reviews/5/reply', (r) => r.fulfill(json({ error: 'Reply service is down' }, 500)));
  }

  test('stall review: failed seller reply is announced as an alert', async ({ page }) => {
    await ownStall(page);
    await page.goto('/stall/3');
    await page.getByRole('button', { name: /Edit reply/ }).click();
    await page.getByRole('textbox', { name: 'Public reply to this review' }).fill('Thanks again');
    await page.getByRole('button', { name: 'Post reply' }).click();
    await expect(page.getByText('Reply service is down')).toBeVisible();
    await expect(page.getByRole('alert').filter({ hasText: /Reply service is down/ })).toBeVisible();
  });

  test('stall review: failed Remove reply is announced as an alert', async ({ page }) => {
    await ownStall(page);
    await page.goto('/stall/3');
    await page.getByRole('button', { name: 'Remove reply' }).click();
    await expect(page.getByText('Reply service is down')).toBeVisible();
    await expect(page.getByRole('alert').filter({ hasText: /Reply service is down/ })).toBeVisible();
  });

  test('stall bio: failed save is announced as an alert', async ({ page }) => {
    await ownStall(page);
    await page.route('**/api/profile/stall-bio', (r) => r.fulfill(json({ error: 'Bio contains a blocked link' }, 400)));
    await page.goto('/stall/3');
    await page.getByRole('button', { name: '+ Add a bio' }).click();
    await page.getByRole('textbox', { name: 'Stall bio' }).fill('Fast trades');
    await page.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(page.getByText('Bio contains a blocked link')).toBeVisible();
    await expect(page.getByRole('alert').filter({ hasText: /Bio contains a blocked link/ })).toBeVisible();
  });

  test('stall bio: focus returns to the bio Edit button after a save', async ({ page }) => {
    await ownStall(page);
    let saved = false;
    await page.route('**/api/listings/stall/3', (r) => r.fulfill(json(saved ? { ...STALL, seller: { ...STALL.seller, stallBio: 'Fast trades' } } : STALL)));
    await page.route('**/api/profile/stall-bio', (r) => { saved = true; return r.fulfill(json({ ok: true })); });
    await page.goto('/stall/3');
    await page.getByRole('button', { name: '+ Add a bio' }).click();
    await page.getByRole('textbox', { name: 'Stall bio' }).fill('Fast trades');
    await page.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(page.getByText('Fast trades')).toBeVisible();
    await expect(page.getByRole('button', { name: /Edit$/ })).toBeFocused();
  });
});

test.describe('defect pass 83 · mark-sent', () => {
  const now = Date.now();
  const TRADE = {
    id: 77, listingId: 1, itemId: 1, itemName: 'Red Beanie', buyerUserId: 8, sellerUserId: 3,
    price: 4, state: 'PENDING_SELLER_SEND', createdAt: now - 3600e3, updatedAt: now - 600e3,
    expiresAt: now + 86400e3, unreadCount: 0, counterpartyName: 'bob',
    counterpartyTradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=2&token=x',
  };

  test('mark-sent drawer: rejected offer URL is announced as an alert', async ({ page }) => {
    await signedIn(page);
    await page.route(/\/api\/trades(\?.*)?$/, (r) => r.fulfill({ ...json([TRADE]), headers: { 'X-Total-Count': '1' } }));
    await page.goto('/profile/trades');
    await page.getByRole('button', { name: 'Mark Sent', exact: true }).click();
    const dlg = page.getByRole('dialog', { name: 'Mark trade as sent' });
    await dlg.getByRole('textbox', { name: 'Steam offer URL' }).fill('https://example.com/not-steam');
    await dlg.getByRole('textbox', { name: 'Steam offer URL' }).press('Enter');
    await expect(dlg.getByText(/doesn.t look like a Steam trade-offer URL/)).toBeVisible();
    await expect(dlg.getByRole('alert').filter({ hasText: /doesn.t look like a Steam trade-offer URL/ })).toBeVisible();
  });

  test('review modal: failed review submit is announced as an alert', async ({ page }) => {
    await signedIn(page);
    const done = { ...TRADE, id: 78, state: 'VERIFIED', buyerUserId: 3, sellerUserId: 9, counterpartyName: 'dave', settledAt: now - 3600e3 };
    await page.route(/\/api\/trades(\?.*)?$/, (r) => r.fulfill({ ...json([done]), headers: { 'X-Total-Count': '1' } }));
    await page.route(/\/api\/reviews$/, (r) => r.request().method() === 'POST'
      ? r.fulfill(json({ error: 'Review service is down' }, 500)) : r.fulfill(json([])));
    await page.goto('/profile/trades');
    await page.getByRole('button', { name: 'Leave Review', exact: true }).click();
    const dlg = page.getByRole('dialog', { name: 'Leave a review' });
    await dlg.getByRole('button', { name: 'Submit review' }).click();
    await expect(dlg.getByText('Review service is down')).toBeVisible();
    await expect(dlg.getByRole('alert').filter({ hasText: /Review service is down/ })).toBeVisible();
  });
});

test.describe('defect pass 83 · notifications', () => {
  const now = Date.now();
  const N = [
    { id: 1, kind: 'AUCTION_OUTBID', title: 'Outbid on Red Beanie', body: 'Someone bid higher', read: true, createdAt: now - 60e3 },
    { id: 2, kind: 'OFFER_RECEIVED', title: 'New offer on Blue Jacket', body: 'bob offered', read: true, createdAt: now - 120e3 },
  ];

  test('notifications page: each row.s delete / mark-unread button names its notification', async ({ page }) => {
    await signedIn(page);
    await page.route(/\/api\/notifications(\?.*)?$/, (r) => r.fulfill(json({ items: N, unread: 0 })));
    await page.goto('/notifications');
    await expect(page.getByText('Outbid on Red Beanie')).toBeVisible();
    await expect(page.getByRole('button', { name: /Delete this notification/ })).toHaveCount(2);
    await expect(page.getByRole('button', { name: /Delete this notification.*Outbid on Red Beanie/ })).toHaveCount(1);
    await expect(page.getByRole('button', { name: /Mark as unread.*New offer on Blue Jacket/ })).toHaveCount(1);
  });
});

test.describe('defect pass 83 · admin', () => {
  test('admin fraud tab: failed signal load is announced as an alert', async ({ page }) => {
    await asAdmin(page);
    await page.route('**/api/admin/fraud', (r) => r.fulfill(json({ error: 'boom' }, 500)));
    await page.goto('/admin?tab=fraud');
    await expect(page.getByText(/Fraud signals failed to load/)).toBeVisible();
    await expect(page.getByRole('alert').filter({ hasText: /Fraud signals failed to load/ })).toBeVisible();
  });

  const ITEM = { id: 11, name: 'Red Beanie', category: 'Hats', rarity: 'Standard', lowestPrice: 3, steamPrice: 2, imageUrl: '' };
  async function catalogue(page) {
    await asAdmin(page);
    await page.route(/\/api\/items\?q=/, (r) => r.fulfill(json([ITEM])));
    await page.route('**/api/admin/items/11', (r) => r.fulfill(json({ message: 'Accent colour must be a #hex value' }, 400)));
    await page.goto('/admin?tab=catalogue');
    await page.getByPlaceholder('Search items by name…').fill('Red');
    await page.getByRole('button', { name: '✎ Edit' }).click();
  }

  test('admin catalogue: the Edit drawer is a named modal dialog', async ({ page }) => {
    await catalogue(page);
    const dlg = page.getByRole('dialog', { name: /Edit Red Beanie/ });
    await expect(dlg).toBeVisible();
    await expect(dlg).toHaveAttribute('aria-modal', 'true');
    // Focus moves into the modal, and back to Edit when it closes.
    await expect(dlg.getByRole('spinbutton')).toBeFocused();
    await dlg.getByRole('button', { name: 'Cancel' }).click();
    await expect(dlg).toHaveCount(0);
    await expect(page.getByRole('button', { name: '✎ Edit' })).toBeFocused();
  });

  test('admin catalogue: a rejected catalogue edit is announced as an alert', async ({ page }) => {
    await catalogue(page);
    await page.getByRole('button', { name: 'Save changes' }).click();
    await expect(page.getByText('Accent colour must be a #hex value')).toBeVisible();
    await expect(page.getByRole('alert').filter({ hasText: /Accent colour must be a #hex value/ })).toBeVisible();
  });
});
