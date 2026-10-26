// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 79.

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

test.describe('defect pass 79 · profile', () => {

  test('support: a failed list refresh after opening a ticket says so, not "No support tickets yet"', async ({ page }) => {
    await signedIn(page);
    let created = false, failed = 0;
    await page.route('**/api/support/tickets', (r) => {
      if (r.request().method() === 'POST') { created = true; return r.fulfill(json({ id: 41, subject: 'Trade stuck', status: 'WAITING_STAFF' })); }
      if (!created) return r.fulfill(json([]));
      failed++; return r.fulfill(json({ error: 'boom' }, 500));
    });
    await page.goto('/support');
    await page.getByRole('button', { name: '+ New Ticket' }).click();
    await page.getByLabel('Ticket subject').fill('Trade stuck');
    await page.getByLabel('Ticket message body').fill('My trade has not arrived');
    await page.getByRole('button', { name: 'Submit Ticket' }).click();
    await expect(page.getByText(/Ticket #41 opened/)).toBeVisible();
    await expect.poll(() => failed).toBeGreaterThan(0);
    await expect(page.getByRole('alert').filter({ hasText: /Could not load your support tickets/ })).toBeVisible();
    await expect(page.getByText(/No support tickets yet/)).toHaveCount(0);
  });

  test('developers: a failed key list refresh after minting a key says so, not "No API keys yet"', async ({ page }) => {
    await signedIn(page);
    let created = false, failed = 0;
    await page.route('**/api/api-keys', (r) => {
      if (r.request().method() === 'POST') { created = true; return r.fulfill(json({ id: 7, label: 'my-bot', token: 'sbx_live_secret', scope: 'RW', createdAt: Date.now() })); }
      if (!created) return r.fulfill(json([]));
      failed++; return r.fulfill(json({ error: 'boom' }, 500));
    });
    await page.goto('/profile/developers');
    await expect(page.getByText(/No API keys yet/)).toBeVisible();
    await page.getByLabel('API key label').fill('my-bot');
    await page.getByRole('button', { name: '+ New Key' }).click();
    await expect(page.getByText('sbx_live_secret')).toBeVisible();
    await expect.poll(() => failed).toBeGreaterThan(0);
    await expect(page.getByRole('alert').filter({ hasText: /Could not load your API keys/ })).toBeVisible();
    await expect(page.getByText(/No API keys yet/)).toHaveCount(0);
  });
});

test.describe('defect pass 79 · notifications', () => {
  const now = Date.now();
  const row = (id, title, kind = 'OFFER_RECEIVED') => ({ id, kind, title, body: 'details', read: true, createdAt: now - id * 60000 });

  test('notifications: the search box stays while a search is active after a delete drops the list to 5', async ({ page }) => {
    await signedIn(page);
    let items = [row(1, 'Steam trade sent'), row(2, 'Steam trade accepted'), row(3, 'Offer one'), row(4, 'Offer two'), row(5, 'Offer three'), row(6, 'Offer four')];
    await page.route((u) => u.pathname === '/api/notifications', (r) => r.fulfill(json({ items, unread: 0 })));
    await page.route('**/api/notifications/1', (r) => { items = items.filter((n) => n.id !== 1); return r.fulfill(json({ ok: true })); });
    await page.goto('/notifications');
    const box = page.getByLabel('Search notifications');
    await box.fill('steam');
    await expect(page.getByRole('button', { name: /Open notification: Offer/ })).toHaveCount(0);
    await page.getByRole('button', { name: 'Delete this notification' }).first().click();
    await expect(page.getByRole('button', { name: /Open notification: Steam trade sent/ })).toHaveCount(0);
    // Only "Steam trade accepted" is listed: the filter that hides the four
    // offers must stay on screen so it can be seen and cleared.
    await expect(page.getByRole('button', { name: /Open notification: Steam trade accepted/ })).toBeVisible();
    await expect(box).toBeVisible();
    await expect(box).toHaveValue('steam');
  });

  test('notifications: the type chips stay while a type filter is active after a delete drops the list to 3', async ({ page }) => {
    await signedIn(page);
    let items = [row(1, 'Trade one', 'TRADE_SENT'), row(2, 'Trade two', 'TRADE_SENT'), row(3, 'Offer one'), row(4, 'Offer two')];
    await page.route((u) => u.pathname === '/api/notifications', (r) => r.fulfill(json({ items, unread: 0 })));
    await page.route('**/api/notifications/1', (r) => { items = items.filter((n) => n.id !== 1); return r.fulfill(json({ ok: true })); });
    await page.goto('/notifications');
    await page.getByRole('button', { name: /^Trades · 2$/ }).click();
    await page.getByRole('button', { name: 'Delete this notification' }).first().click();
    await expect(page.getByRole('button', { name: /Open notification: Trade one/ })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /Open notification: Trade two/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /^Trades · 1$/ })).toHaveAttribute('aria-pressed', 'true');
  });
});

test.describe('defect pass 79 · database', () => {
  const items = [1, 2, 3, 4, 5].map((i) => ({ id: i, name: 'Cap ' + i, category: 'Hats', rarity: 'Standard', supply: 3, totalSold: 1, viewCount: 2, lowestPrice: 4 }));

  test('database: a ?page= past the last page lands on the last page, not an empty table', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/database?**', (r) => {
      const off = Number(new URL(r.request().url()).searchParams.get('offset') || 0);
      return r.fulfill(json({ items: off === 0 ? items : [], total: 5, indexed: 5 }));
    });
    await page.goto('/db?page=3');
    await expect(page.getByText(/5 results · page/)).toBeVisible();
    await expect(page.getByRole('link', { name: /Open Cap 1 detail/ })).toBeVisible();
    await expect(page).not.toHaveURL(/page=3/);
  });
});

test.describe('defect pass 79 · staff', () => {

  test('admin health: an SMTP test that cannot reach the server says so in words, not "TypeError"', async ({ page }) => {
    await asAdmin(page);
    await page.route('**/api/admin/health', (r) => r.fulfill(json({ status: 'UP', priceSync: {} })));
    await page.route('**/api/admin/test-email', (r) => r.abort('connectionrefused'));
    await page.goto('/admin?tab=health');
    await page.getByPlaceholder('ops@example.com').fill('ops@example.com');
    await page.getByRole('button', { name: 'Send test' }).click();
    await expect(page.getByText(/Could not reach the server/)).toBeVisible();
    await expect(page.getByText(/TypeError|Failed to fetch/)).toHaveCount(0);
  });
});

test.describe('defect pass 79 · stall', () => {
  const ITEM = { id: 11, name: 'Red Beanie', category: 'Hats', rarity: 'Standard', lowestPrice: 5, isListed: true, supply: 1 };
  const STALL = { seller: { id: 9, displayName: 'dave' }, count: 1, listings: [{ id: 101, price: 5, listingType: 'BUY_NOW', status: 'ACTIVE', item: ITEM, sellerUserId: 9, sellerName: 'dave', listedAt: Date.now() }] };

  for (const how of ['Escape', 'Cancel']) {
    test(`stall: closing the Contact drawer with ${how} puts focus back on Contact`, async ({ page }) => {
      await signedIn(page);
      await page.route('**/api/listings/stall/9', (r) => r.fulfill(json(STALL)));
      await page.goto('/stall/9');
      const contact = page.getByRole('button', { name: 'Contact' });
      await contact.click();
      const box = page.getByLabel('Message to support about this seller');
      await expect(box).toBeFocused();
      if (how === 'Escape') await page.keyboard.press('Escape');
      else await page.getByRole('dialog', { name: /Contact @dave/ }).getByRole('button', { name: 'Cancel' }).click();
      await expect(box).toHaveCount(0);
      await expect(contact).toBeFocused();
    });
  }

  const OWN = { seller: { id: 3, displayName: 'carol', stallBio: 'Fast trades, evenings EU.' }, count: 1, listings: [{ ...STALL.listings[0], sellerUserId: 3, sellerName: 'carol' }] };
  for (const how of ['Escape', 'Cancel']) {
    test(`stall: leaving the bio editor with ${how} puts focus back on Edit`, async ({ page }) => {
      await signedIn(page);
      await page.route('**/api/listings/stall/3', (r) => r.fulfill(json(OWN)));
      await page.goto('/stall/3');
      await page.getByRole('button', { name: '✎ Edit' }).click();
      const box = page.getByLabel('Stall bio');
      await expect(box).toBeFocused();
      if (how === 'Escape') await box.press('Escape');
      else await page.getByRole('button', { name: 'Cancel', exact: true }).click();
      await expect(box).toHaveCount(0);
      await expect(page.getByRole('button', { name: '✎ Edit' })).toBeFocused();
    });
  }
});
