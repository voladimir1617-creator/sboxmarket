// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 36.

const ME = {
  id: 1,
  steamId64: '76561199000000001',
  displayName: 'Pass Tester',
  signedIn: true,
  sessionEpoch: 0,
};

async function signedIn(page) {
  await page.route('**/api/**', (r) => r.fulfill({
    status: 200, contentType: 'application/json', body: '{}',
  }));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify(ME),
  }));
}

test.describe('defect pass 36', () => {
  test('a watchlist whose item lookup fails says so with a Retry, not "Nothing on your watchlist"', async ({ page }) => {
    await page.addInitScript(() => localStorage.setItem('sb_watchlist', JSON.stringify([999001])));
    let calls = 0;
    await page.route('**/api/items/batch**', (r) => {
      calls += 1;
      if (calls === 1) return r.fulfill({ status: 500, contentType: 'application/json', body: '{}' });
      return r.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify([{ id: 999001, name: 'Lookup Hat', lowestPrice: 0, rarity: 'Standard', category: 'Hats' }]) });
    });
    await page.goto('/watchlist');
    await expect(page.getByTestId('watchlist-lookup-failed')).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText('Nothing on your watchlist')).toHaveCount(0);
    await page.getByTestId('watchlist-lookup-failed').getByRole('button', { name: 'Retry' }).click();
    await expect(page.getByText('Lookup Hat').first()).toBeVisible();
    await expect(page.getByTestId('watchlist-lookup-failed')).toHaveCount(0);
  });

  test('a watchlist still loading shows Loading, not the empty state', async ({ page }) => {
    await page.addInitScript(() => localStorage.setItem('sb_watchlist', JSON.stringify([999002])));
    await page.route('**/api/items/batch**', () => { /* never answers */ });
    await page.goto('/watchlist');
    await expect(page.getByTestId('watchlist-loading')).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText('Nothing on your watchlist')).toHaveCount(0);
  });

  test('a notification\'s Delete button is not nested inside its open button', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/notifications**', (r) => r.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ unread: 0, items: [{ id: 3, kind: 'SYSTEM', title: 'Hello there', body: 'Body text', read: true, createdAt: Date.now() - 60_000 }] }),
    }));
    await page.goto('/notifications');
    const openBtn = page.getByRole('button', { name: /Open notification: Hello there/ });
    await expect(openBtn).toBeVisible({ timeout: 20_000 });
    await expect(openBtn.getByRole('button')).toHaveCount(0);
    await expect(openBtn.locator('button')).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Delete this notification' })).toBeVisible();
  });

  test('"Auctions ending soonest" puts an item\'s soon-ending auction first even when a cheaper Buy Now exists', async ({ page }) => {
    let auctionItemName = null;
    await page.route(/\/api\/listings\?/, async (r) => {
      const res = await r.fetch();
      const data = await res.json();
      const rows = Array.isArray(data) ? data : data.items;
      const distinct = [];
      for (const l of rows) {
        if (l.item && !distinct.find(d => d.item.id === l.item.id)) distinct.push(l);
        if (distinct.length === 3) break;
      }
      const [a, b, c] = distinct;
      auctionItemName = a.item.name;
      const auction = { ...a, id: 990001, listingType: 'AUCTION', price: 50, currentBid: 50, bidCount: 1, expiresAt: Date.now() + 120_000 };
      const cheap = { ...a, id: 990002, listingType: 'BUY_NOW', price: 0.5, expiresAt: null };
      const out = [auction, b, c, cheap];
      await r.fulfill({ response: res, json: Array.isArray(data) ? out : { ...data, items: out, total: out.length } });
    });
    await page.goto('/market?sort=ending_soon');
    const first = page.locator('.grid-card').first();
    await expect(first).toBeVisible({ timeout: 20_000 });
    await expect(first).toContainText(auctionItemName);
    await expect(first).toHaveClass(/is-auction/);
  });

  test('the "<$2" and ">$10" price chips do not overlap their neighbours', async ({ page }) => {
    await page.goto('/market');
    const chip = page.locator('.price-chip').first();
    await expect(chip).toBeVisible({ timeout: 20_000 });
    await chip.click();
    await expect(page.getByLabel('Maximum price filter (USD-anchored)')).toHaveValue('1.99');
    await page.locator('.price-chip').last().click();
    await expect(page.getByLabel('Minimum price filter (USD-anchored)')).toHaveValue('10.01');
  });

  test('the help center describes anti-snipe, Generate and buy-order money as the server does', async ({ page }) => {
    await page.goto('/help');
    const auction = page.getByRole('button', { name: /How do I run an auction \(as a seller\)\?/ });
    await expect(auction).toBeVisible({ timeout: 20_000 });
    await auction.click();
    await expect(page.locator('body')).toContainText('moves the close to 30 seconds after that bid, up to 20 times per auction');
    await page.getByRole('button', { name: /Why did my buy order auto-expire\?/ }).click();
    await expect(page.locator('body')).toContainText('your wallet is charged only when a listing matches');
    await expect(page.locator('body')).not.toContainText('wallet hold is released');
  });

  test('the Loadout Lab slot picker offers items that are not in the cheapest page of listings', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/database**', (r) => r.continue());
    await page.route('**/api/listings**', (r) => r.continue());
    await page.route('**/api/loadouts/7', (r) => r.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ loadout: { id: 7, ownerUserId: 1, name: 'Mine', visibility: 'PRIVATE', totalPrice: 0 }, slots: [], favorited: false }),
    }));
    const db = await (await page.request.get('/api/database?limit=500&sort=most_traded')).json();
    const listed = await (await page.request.get('/api/listings')).json();
    const listedIds = new Set((Array.isArray(listed) ? listed : listed.items).map(l => l.item && l.item.id));
    const target = db.items.find(it => !listedIds.has(it.id));
    expect(target, 'a catalogue item outside the first listings page').toBeTruthy();
    await page.goto('/loadout/7');
    const add = page.locator('.loadout-add-btn', { hasText: '+ Add' }).last();
    await expect(add).toBeVisible({ timeout: 20_000 });
    await add.click();
    await page.getByLabel('Filter Wild items').fill(target.name);
    await expect(page.locator('.loadout-picker-item', { hasText: target.name }).first()).toBeVisible();
  });
});

