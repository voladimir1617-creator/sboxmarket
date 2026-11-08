// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 98.

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


// Defect pass 98: seller auctions, stall follows, database, support, settings, keyboard header.

const now = Date.now();

test.describe('pass 98: support tickets for a first-time user', () => {
  const T1 = { id: 41, subject: 'Where is my item', category: 'TRADE', status: 'OPEN', createdAt: now, updatedAt: now };
  test('submitting the first ticket leaves focus on a control, not the page', async ({ page }) => {
    await signedIn(page);
    let created = false;
    await page.route('**/api/support/tickets', async (r) => {
      if (r.request().method() === 'POST') { created = true; return r.fulfill(json({ id: 41 })); }
      if (!created) return r.fulfill(json([]));
      await new Promise((ok) => setTimeout(ok, 600));
      return r.fulfill(json([T1]));
    });
    await page.goto('/support');
    await page.getByRole('button', { name: /New Ticket/ }).click({ timeout: 15_000 });
    await page.getByRole('textbox', { name: 'Ticket subject' }).fill('Where is my item');
    await page.getByRole('textbox', { name: 'Ticket message body' }).fill('It never arrived');
    await page.getByRole('button', { name: 'Submit Ticket' }).focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('button', { name: /Open ticket #41/ })).toBeVisible();
    await expect(page.getByText(/No support tickets yet/)).toHaveCount(0);
    await page.waitForTimeout(300);
    expect(await page.evaluate(() => document.activeElement && document.activeElement.tagName)).not.toBe('BODY');
  });
  test('a refused ticket keeps focus on Submit Ticket', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/support/tickets', async (r) => {
      if (r.request().method() === 'POST') {
        await new Promise((ok) => setTimeout(ok, 500));
        return r.fulfill(json({ error: 'Too many open tickets', code: 'TOO_MANY' }, 429));
      }
      return r.fulfill(json([]));
    });
    await page.goto('/support');
    await page.getByRole('button', { name: /New Ticket/ }).click({ timeout: 15_000 });
    await page.getByRole('textbox', { name: 'Ticket subject' }).fill('Where is my item');
    await page.getByRole('textbox', { name: 'Ticket message body' }).fill('It never arrived');
    const submit = page.getByRole('button', { name: /Submit Ticket|Submitting/ });
    await submit.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('button', { name: 'Submit Ticket' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Submit Ticket' })).toBeFocused();
  });
});

test.describe('pass 98: 2FA enrolment by keyboard', () => {
  async function stub(page) {
    await signedIn(page);
    await page.route('**/api/profile/me', (r) => r.fulfill(json({ user: { ...ME, twoFactorEnabled: false }, twoFactorEnabled: false })));
    await page.route('**/api/profile/2fa/enroll', (r) => r.fulfill(json({ secret: 'JBSWY3DPEHPK3PXP', otpauthUrl: 'otpauth://totp/SkinBox:carol?secret=JBSWY3DPEHPK3PXP' })));
    await page.route('**/api/profile/2fa/cancel', (r) => r.fulfill(json({ ok: true })));
  }
  test('Enable 2FA moves focus to the code box; Cancel hands it back', async ({ page }) => {
    await stub(page);
    await page.goto('/profile/personal');
    const enable = page.getByRole('button', { name: 'Enable 2FA' });
    await enable.focus({ timeout: 15_000 });
    await page.keyboard.press('Enter');
    const code = page.getByRole('textbox', { name: /6-digit code from your authenticator app/ });
    await expect(code).toBeVisible();
    await expect(code).toBeFocused();
    await page.keyboard.press('Shift+Tab');
    await page.getByRole('button', { name: 'Cancel' }).last().focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('button', { name: 'Enable 2FA' })).toBeFocused();
  });
});

test.describe('pass 98: header keyboard', () => {
  test('ArrowDown on the open bell moves into its menu, like the user menu', async ({ page }) => {
    await signedIn(page);
    await page.route((u) => u.pathname === '/api/notifications', (r) => r.fulfill(json({ items: [
      { id: 1, kind: 'TRADE_SOLD', title: 'Item sold', body: 'Police Hat sold', read: false, createdAt: now - 60000 },
      { id: 2, kind: 'TRADE_SOLD', title: 'Item sold', body: 'Chef Hat sold', read: false, createdAt: now - 90000 },
    ], unread: 2 })));
    await page.goto('/market');
    const bell = page.getByRole('button', { name: /^Notifications/ });
    await bell.focus({ timeout: 15_000 });
    await page.keyboard.press('Enter');
    await expect(page.getByRole('menuitem', { name: /Chef Hat sold/ })).toBeVisible();
    await expect(bell).toBeFocused();
    await page.keyboard.press('ArrowDown');
    await expect(page.getByRole('menuitem', { name: /Police Hat sold/ })).toBeFocused();
  });
});

test.describe('pass 98: stall followers for a visitor', () => {
  const SELLER = { id: 7, displayName: 'dave', joinedAt: now - 90 * 86400000, soldCount: 3, followerCount: 0, completedTrades: 3 };
  test('the Followers stat still matches the Follow button after leaving and coming back', async ({ page }) => {
    await signedIn(page);
    let following = false;
    // The stall read is browser-cached for 30s, so a return visit gets the
    // copy from before the follow; the follow status is never cached.
    await page.route('**/api/listings/stall/7', (r) => r.fulfill(json({ seller: SELLER, listings: [] })));
    await page.route('**/api/follows/status/7', (r) => r.fulfill(json({ sellerUserId: 7, following, followerCount: following ? 1 : 0 })));
    await page.route('**/api/follows/7', (r) => { following = r.request().method() === 'POST'; return r.fulfill(json({ sellerUserId: 7, following })); });
    await page.goto('/stall/7');
    await page.getByRole('button', { name: /^\+ ?Follow$/ }).click({ timeout: 15_000 });
    const stat = page.locator('.stall-stat').filter({ hasText: /Followers/i });
    await expect(stat).toContainText('1');
    await page.goto('/market');
    await page.goBack();
    await expect(page.getByRole('button', { name: /Following/ })).toBeVisible({ timeout: 15_000 });
    await expect(stat).toContainText('1');
  });
});

test.describe('pass 98: database page filters', () => {
  const row = (id, name, price) => ({ id, name, category: 'Hats', rarity: 'Standard', supply: 4, totalSold: 2, viewCount: 1, lowestPrice: price, iconEmoji: '■' });
  test('Back from an item keeps the price range, like category and sort', async ({ page }) => {
    await signedIn(page);
    const seen = [];
    await page.route('**/api/database**', (r) => {
      const u = new URL(r.request().url());
      seen.push(u.search);
      const max = u.searchParams.get('maxPrice');
      const items = max ? [row(1, 'Police Hat', 0.8)] : [row(1, 'Police Hat', 0.8), row(2, 'Crown', 40)];
      return r.fulfill(json({ items, total: items.length, indexed: 2, limit: 30, offset: 0 }));
    });
    await page.route('**/api/items/1', (r) => r.fulfill(json(row(1, 'Police Hat', 0.8))));
    await page.route('**/api/listings/item/1', (r) => r.fulfill(json([])));
    await page.goto('/database');
    await page.getByRole('combobox', { name: 'Filter catalogue by category' }).selectOption('Hats', { timeout: 15_000 });
    await page.getByRole('textbox', { name: 'Maximum floor price' }).fill('1');
    await expect(page.getByRole('link', { name: /Open Crown detail/ })).toHaveCount(0);
    await page.getByRole('link', { name: /Open Police Hat detail/ }).click();
    await expect(page).toHaveURL(/\/item\/1/);
    await page.goBack();
    await expect(page.getByRole('combobox', { name: 'Filter catalogue by category' })).toHaveValue('Hats', { timeout: 15_000 });
    await expect(page.getByRole('textbox', { name: 'Maximum floor price' })).toHaveValue('1');
    await expect(page.getByRole('link', { name: /Open Police Hat detail/ })).toBeVisible();
    await expect(page.getByRole('link', { name: /Open Crown detail/ })).toHaveCount(0);
  });
});

test.describe('pass 98: settings, API keys', () => {
  const KEYS = [
    { id: 7, label: 'my-bot', scope: 'RW', publicPrefix: 'sbx_ab12', createdAt: now - 86400000, lastUsedAt: null, revoked: false },
    { id: 8, label: 'price-watcher', scope: 'RO', publicPrefix: 'sbx_cd34', createdAt: now - 86400000, lastUsedAt: null, revoked: false },
  ];
  test('each Revoke button names its key', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/api-keys', (r) => r.fulfill(json(KEYS)));
    await page.goto('/profile/developers');
    await expect(page.getByRole('button', { name: /^Revoke API key my-bot$/ })).toBeVisible({ timeout: 15_000 });
    await expect(page.getByRole('button', { name: /^Revoke API key price-watcher$/ })).toBeVisible();
  });
  test('revoking a key keeps keyboard focus on the page.s controls', async ({ page }) => {
    await signedIn(page);
    let revoked = false;
    await page.route('**/api/api-keys', (r) => r.fulfill(json(revoked ? [{ ...KEYS[0], revoked: true }, KEYS[1]] : KEYS)));
    await page.route('**/api/api-keys/7', (r) => { revoked = true; return r.fulfill(json({ ok: true })); });
    await page.goto('/profile/developers');
    const btn = page.locator('tr', { hasText: 'my-bot' }).getByRole('button', { name: /Revoke/ });
    await btn.focus({ timeout: 15_000 });
    await page.keyboard.press('Enter');
    await expect(page.locator('tr', { hasText: 'my-bot' })).toContainText('REVOKED');
    await expect(page.getByRole('button', { name: /New Key/ })).toBeFocused();
  });
});

test.describe('pass 98: stall reviews after posting one', () => {
  // page.route turns Chromium's HTTP cache off, so stand in for it: the
  // stall (30s) and reviews (60s) reads are cacheable. A no-store read
  // bypasses the cache without updating it; a reload read refreshes it.
  async function httpCache(page) {
    await page.addInitScript(() => {
      const real = window.fetch.bind(window);
      const store = new Map();
      window.fetch = async (input, init) => {
        const url = typeof input === 'string' ? input : input.url;
        const method = (init && init.method) || 'GET';
        if (method !== 'GET' || !/\/api\/(reviews\/user\/\d+|listings\/stall\/\d+)$/.test(new URL(url, location.href).pathname)) return real(input, init);
        const mode = (init && init.cache) || 'default';
        const hit = store.get(url);
        if (mode === 'default' && hit && Date.now() - hit.at < 30000) return hit.res.clone();
        const res = await real(input, init);
        if (mode !== 'no-store' && res.ok) store.set(url, { at: Date.now(), res: res.clone() });
        return res;
      };
    });
  }
  const SELLER = { id: 7, displayName: 'dave', joinedAt: now - 90 * 86400000, soldCount: 3, followerCount: 0, completedTrades: 3 };
  test('a review the visitor just posted is still there when they come back', async ({ page }) => {
    await httpCache(page);
    await signedIn(page);
    let posted = false;
    const REV = { id: 501, rating: 4, comment: 'Quick trade, thanks', fromDisplayName: 'carol', itemName: 'Police Hat', createdAt: now, mine: true, helpfulCount: 0 };
    await page.route('**/api/listings/stall/7', (r) => r.fulfill(json({ seller: { ...SELLER, reviewCount: posted ? 1 : 0 }, listings: [] })));
    await page.route('**/api/reviews/user/7', (r) => r.fulfill(json(posted ? [REV] : [])));
    await page.route('**/api/reviews/eligible/7', (r) => r.fulfill(json([{ tradeId: 31, itemName: 'Police Hat', price: 4, settledAt: now - 3600000, reviewed: posted }])));
    await page.route('**/api/reviews', (r) => { posted = true; return r.fulfill(json({ id: 501 })); });
    await page.goto('/stall/7');
    await page.getByRole('button', { name: 'Write review' }).click({ timeout: 15_000 });
    await page.getByRole('textbox', { name: 'Review comment (optional)' }).fill('Quick trade, thanks');
    await page.getByRole('button', { name: 'Submit review' }).click();
    await expect(page.getByText('Quick trade, thanks')).toBeVisible();
    // Leave inside the app (the cache lives for the tab) and come back.
    await page.evaluate(() => { history.pushState({}, '', '/help'); dispatchEvent(new PopStateEvent('popstate')); });
    await expect(page.getByText('Quick trade, thanks')).toHaveCount(0);
    await page.goBack();
    await expect(page.getByText(/Leave a review/)).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText('Quick trade, thanks')).toBeVisible();
  });
});

test.describe('pass 98: a seller.s auctions on My Stall', () => {
  const auc = (id, name, o = {}) => ({ id, price: 5, listingType: 'AUCTION', status: 'ACTIVE', listedAt: now - 3600000, expiresAt: now + 6 * 3600000, bidCount: 2, currentBid: 6, sellerUserId: ME.id,
    item: { id: 100 + id, name, category: 'Hats', rarity: 'Standard', iconEmoji: '■' }, ...o });
  test('the locked controls on auctions with bids say which auction they belong to', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings/my-stall', (r) => r.fulfill(json([auc(1, 'Police Hat'), auc(2, 'Chef Hat')])));
    await page.goto('/me/stall');
    await expect(page.getByText('Police Hat').first()).toBeVisible({ timeout: 15_000 });
    await expect(page.getByRole('button', { name: /Price locked.*Police Hat|Police Hat.*price.*locked/i })).toHaveCount(1);
    await expect(page.getByRole('button', { name: /Price locked.*Chef Hat|Chef Hat.*price.*locked/i })).toHaveCount(1);
    await expect(page.getByRole('button', { name: /Auction locked.*Police Hat|Police Hat.*auction.*locked/i })).toHaveCount(1);
    await expect(page.getByRole('button', { name: /Auction locked.*Chef Hat|Chef Hat.*auction.*locked/i })).toHaveCount(1);
  });
});
