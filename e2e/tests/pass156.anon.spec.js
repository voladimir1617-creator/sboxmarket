// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 156.

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


// Defect pass 156: Settings opened in two tabs writes stale mutes over each
// other (and the Notifications page ignores a mute made in another tab);
// search suggestions, grid thumbnails and home cards still say "Off-Market"
// where every other surface says "Scarce"; a clicked suggestion is not kept
// in Recent searches (Enter keeps it); the Personal Info sign-in / security
// lists and the Profile and stall review lists have no list semantics; the
// item page "sold" / "views" chips read their glyphs out; support thread
// messages only say "1d ago" with no exact time.

const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';

async function consent(target) {
  await target.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
}

// Every write a real-jar test could reach is refused, so nothing on the
// server changes.
async function noWrites(target) {
  await target.route('**/api/**', (r) => (r.request().method() === 'GET' ? r.fallback() : r.fulfill(json({ error: 'blocked in test' }, 400))));
}

async function devLogin(page, userId) {
  await page.request.get(`${base}/api/auth/steam/dev-login?userId=${userId}&next=/`);
}

const now = Date.now();

test.describe('pass156k · Settings in two tabs (real jar)', () => {
  test('a mute made in another tab shows here, and a press here keeps it', async ({ page, context }) => {
    await consent(context);
    await noWrites(context);
    await devLogin(page, 2);
    const other = await context.newPage();
    await page.goto(`${base}/settings`);
    await other.goto(`${base}/settings`);
    const group = page.getByRole('group', { name: 'Mute notification types' });
    const otherGroup = other.getByRole('group', { name: 'Mute notification types' });
    await otherGroup.scrollIntoViewIfNeeded({ timeout: 20_000 });
    await group.scrollIntoViewIfNeeded({ timeout: 20_000 });
    await otherGroup.getByRole('button', { name: /^Trades$/ }).click();
    await expect(group.getByRole('button', { name: /Muted · Trades/ })).toHaveAttribute('aria-pressed', 'true');
    await group.getByRole('button', { name: /^Auctions$/ }).click();
    await expect.poll(() => page.evaluate(() => JSON.parse(localStorage.getItem('sb_mute_kinds') || '[]').sort()))
      .toEqual(['AUCTIONS', 'TRADES']);
  });

  test('a switch flipped in another tab shows here', async ({ page, context }) => {
    await consent(context);
    await noWrites(context);
    await devLogin(page, 2);
    const other = await context.newPage();
    await page.goto(`${base}/settings`);
    await other.goto(`${base}/settings`);
    const sw = page.getByRole('switch', { name: 'Reduce motion' });
    await expect(sw).toHaveAttribute('aria-checked', 'false', { timeout: 20_000 });
    await other.getByRole('switch', { name: 'Reduce motion' }).click({ timeout: 20_000 });
    await expect(sw).toHaveAttribute('aria-checked', 'true');
  });

  test('the Notifications page hides a type muted from Settings in another tab', async ({ page, context }) => {
    await consent(context);
    await noWrites(context);
    await devLogin(page, 2);
    const items = [
      { id: 1, kind: 'TRADE_COMPLETED', title: 'Trade complete', body: 'Wizard Hat delivered', read: false, createdAt: now - 60e3 },
      { id: 2, kind: 'OFFER_RECEIVED', title: 'New offer', body: 'Someone offered $4.00', read: false, createdAt: now - 120e3 },
    ];
    await context.route('**/api/notifications', (r) => (r.request().method() === 'GET'
      ? r.fulfill(json({ items, unread: 2 })) : r.fallback()));
    await page.goto(`${base}/notifications`);
    await expect(page.getByRole('button', { name: /Open notification: New offer/ })).toBeVisible({ timeout: 20_000 });
    const other = await context.newPage();
    await other.goto(`${base}/settings`);
    const otherGroup = other.getByRole('group', { name: 'Mute notification types' });
    await otherGroup.scrollIntoViewIfNeeded({ timeout: 20_000 });
    await otherGroup.getByRole('button', { name: /^Offers$/ }).click();
    await page.bringToFront();
    await expect(page.getByRole('button', { name: /Open notification: New offer/ })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /Open notification: Trade complete/ })).toBeVisible();
  });
});

test.describe('pass156k · market search suggestions (real jar)', () => {
  test('a Scarce item reads Scarce in the suggestions, not Off-Market', async ({ page }) => {
    await consent(page);
    await page.goto(`${base}/market`);
    const box = page.getByRole('combobox', { name: 'Search listings' });
    await box.fill('ball gown', { timeout: 20_000 });
    const list = page.getByRole('listbox', { name: 'Search suggestions' });
    await expect(list.getByRole('option', { name: /Grand Ball Gown .* Scarce/ })).toBeVisible();
    await expect(list.getByRole('option', { name: /Off-Market/ })).toHaveCount(0);
  });

  test('clicking a suggestion keeps the search in Recent searches, as Enter does', async ({ page }) => {
    await consent(page);
    await page.goto(`${base}/market`);
    const box = page.getByRole('combobox', { name: 'Search listings' });
    await box.fill('ball gown', { timeout: 20_000 });
    await page.getByRole('listbox', { name: 'Search suggestions' }).getByRole('option', { name: /Grand Ball Gown/ }).click();
    await expect(page).toHaveURL(/\/item\/\d+/);
    await page.goto(`${base}/market`);
    await page.getByRole('combobox', { name: 'Search listings' }).focus({ timeout: 20_000 });
    await expect(page.getByRole('listbox', { name: 'Recent searches' }).getByRole('option', { name: 'ball gown', exact: true })).toBeVisible();
  });

  test('home grid thumbnails name the Scarce tier in their tooltip', async ({ page }) => {
    await consent(page);
    await page.goto(`${base}/`);
    const thumbs = page.locator('.grid-thumb[title]');
    await expect(thumbs.first()).toBeAttached({ timeout: 20_000 });
    await expect(page.locator('.grid-thumb[title="Scarce rarity"]').first()).toBeAttached();
    await expect(page.locator('.grid-thumb[title="Off-Market rarity"]')).toHaveCount(0);
  });
});

test.describe('pass156k · Personal Info activity lists (real jar)', () => {
  test('Recent sign-ins is a list of sign-ins', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await devLogin(page, 2);
    await page.route('**/api/profile/sign-in-history', (r) => r.fulfill(json([
      { id: 1, createdAt: now - 60e3, ipAddress: '10.0.0.1', userAgent: 'Mozilla/5.0 Chrome/120' },
      { id: 2, createdAt: now - 864e5, ipAddress: '10.0.0.2', userAgent: 'Mozilla/5.0 Firefox/121' },
    ])));
    await page.goto(`${base}/profile`);
    await page.getByRole('button', { name: 'Show last 20 sign-ins' }).click({ timeout: 20_000 });
    const list = page.getByRole('list', { name: 'Recent sign-ins' });
    await expect(list.getByRole('listitem')).toHaveCount(2);
    await expect(list.getByRole('listitem').first()).toContainText('10.0.0.1');
  });

  test('Security activity is a list, with the event glyphs hidden', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await devLogin(page, 2);
    await page.route('**/api/profile/security-activity', (r) => r.fulfill(json([
      { id: 1, eventType: 'API_KEY_MINTED', summary: 'Key sb_abc', createdAt: now - 60e3 },
      { id: 2, eventType: 'TWOFA_RESET', summary: '', createdAt: now - 864e5 },
    ])));
    await page.goto(`${base}/profile`);
    await page.getByRole('button', { name: 'Show recent security events' }).click({ timeout: 20_000 });
    const list = page.getByRole('list', { name: 'Security activity' });
    await expect(list.getByRole('listitem')).toHaveCount(2);
    await expect(list.getByRole('listitem').first()).toMatchAriaSnapshot('- listitem: /^API key minted Key sb_abc/');
  });
});

const REVIEWS = [
  { id: 41, rating: 5, comment: 'fast trade', fromDisplayName: 'dana', createdAt: now - 3600e3 },
  { id: 42, rating: 4, comment: 'fine', fromDisplayName: 'eli', createdAt: now - 7200e3 },
];

test.describe('pass156k · review lists (real jar)', () => {
  test('Profile Reviews received is a list with one item per review', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await devLogin(page, 2);
    await page.route('**/api/reviews/user/2', (r) => r.fulfill(json(REVIEWS)));
    await page.goto(`${base}/profile/reviews`);
    const panel = page.getByRole('tabpanel');
    await expect(panel.getByText('fast trade')).toBeVisible({ timeout: 20_000 });
    await expect(panel.getByRole('list').getByRole('listitem')).toHaveCount(2);
    await expect(panel.getByRole('listitem').first()).toContainText('dana');
  });

  test('Profile Reviews given is a list too', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await devLogin(page, 2);
    await page.route('**/api/reviews/mine', (r) => r.fulfill(json([
      { id: 51, rating: 3, comment: 'slow', toUserId: 4, toUserName: 'GhostlyDeals', createdAt: now - 3600e3 },
    ])));
    await page.goto(`${base}/profile/reviews?view=given`);
    const panel = page.getByRole('tabpanel');
    await expect(panel.getByText('slow')).toBeVisible({ timeout: 20_000 });
    await expect(panel.getByRole('list').getByRole('listitem')).toHaveCount(1);
  });

  test('stall Recent reviews is a list with one item per review', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await page.route('**/api/reviews/user/4', (r) => r.fulfill(json(REVIEWS)));
    await page.goto(`${base}/stall/4`);
    const reviews = page.locator('.stall-reviews').filter({ hasText: 'Recent reviews' });
    await expect(reviews.getByText('fast trade')).toBeVisible({ timeout: 20_000 });
    await expect(reviews.getByRole('list').getByRole('listitem')).toHaveCount(2);
  });
});

test.describe('pass156k · item page chips (real jar)', () => {
  test('the sold and views chips read their numbers, not a check mark or a dash', async ({ page }) => {
    await consent(page);
    // The page takes the item from its listings; raise the view count so
    // the views chip shows (it is hidden under 10 views).
    await page.route('**/api/listings/item/38', async (r) => {
      const res = await r.fetch();
      const rows = await res.json();
      await r.fulfill({ response: res, json: rows.map((l) => ({ ...l, item: { ...l.item, viewCount: 25 } })) });
    });
    await page.goto(`${base}/item/38`);
    await expect(page.getByRole('heading', { level: 1, name: 'Grand Ball Gown' })).toBeVisible({ timeout: 20_000 });
    const sold = page.locator('.modal-demand-chip').filter({ hasText: /\d sold$/ }).first();
    const views = page.locator('.modal-demand-chip').filter({ hasText: /views$/ }).first();
    await expect(sold).toBeVisible();
    await expect(views).toBeVisible();
    await expect(sold).toMatchAriaSnapshot('- text: /^\\d[\\d,]* sold$/');
    await expect(views).toMatchAriaSnapshot('- text: /^\\d[\\d,]* views$/');
  });
});

test.describe('pass156k · support ticket thread (stubbed)', () => {
  test('each message carries its exact sent time, not only "1d ago"', async ({ page }) => {
    await consent(page);
    await signedIn(page);
    const t = { id: 7, subject: 'Missing item', category: 'TRADE', status: 'WAITING_USER', updatedAt: now - 6e4, createdAt: now - 864e5 };
    const sent = now - 864e5;
    await page.route('**/api/support/tickets', (r) => r.fulfill(json([t])));
    await page.route('**/api/support/tickets/7', (r) => r.fulfill(json({ ticket: t, messages: [
      { id: 1, author: 'USER', authorName: ME.displayName, body: 'My item never arrived', createdAt: sent },
      { id: 2, author: 'STAFF', authorName: 'Sam', body: 'Can you share the trade id?', createdAt: now - 36e5 },
    ] })));
    await page.goto('/support');
    await page.getByRole('button', { name: /Open ticket #7/ }).click({ timeout: 20_000 });
    const log = page.getByRole('log', { name: 'Messages in ticket #7' });
    await expect(log).toContainText('My item never arrived');
    const stamp = log.locator('time').first();
    await expect(stamp).toHaveText(/ago$/);
    await expect(stamp).toHaveAttribute('datetime', new Date(sent).toISOString());
    await expect(stamp).toHaveAttribute('title', /^Sent /);
  });
});
