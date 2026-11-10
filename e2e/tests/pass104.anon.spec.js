// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 104.

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
// Defect pass 104: headings, tab sets, result counts and form field errors.

test.describe('pass 104: page headings', () => {
  test('the Affiliate Program page has one level-1 heading', async ({ page }) => {
    await page.goto('/affiliate');
    await expect(page.getByRole('heading', { level: 1, name: /Affiliate Program/ })).toBeVisible({ timeout: 20_000 });
    await expect(page.getByRole('heading', { level: 1 })).toHaveCount(1);
  });

  test('FAQ questions sit one level under the page title, not two', async ({ page }) => {
    await page.goto('/faq');
    await expect(page.getByRole('heading', { level: 1, name: /FAQ/ })).toBeVisible({ timeout: 20_000 });
    const q = page.getByRole('heading', { level: 2, name: /How do I sign in\?/ });
    await q.scrollIntoViewIfNeeded();
    await expect(q).toBeInViewport();
    await expect(page.locator('.info-modal-body h3')).toHaveCount(0);
  });
});

test.describe('pass 104: tab sets', () => {
  test('Loadout Lab tablist holds only tabs; + Create sits beside it', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/loadouts**', (r) => r.fulfill(json([])));
    await page.goto('/loadout');
    const list = page.getByRole('tablist', { name: 'Loadout sections' });
    await expect(list.getByRole('tab', { name: 'Discover' })).toBeVisible({ timeout: 20_000 });
    await expect(list.getByRole('tab')).toHaveCount(3);
    await expect(page.getByRole('button', { name: /\+ Create/ })).toBeVisible();
    await expect(list.getByRole('button')).toHaveCount(0);
    // Arrow keys still move between the tabs.
    await list.getByRole('tab', { name: 'Discover' }).focus();
    await page.keyboard.press('ArrowRight');
    await expect(list.getByRole('tab', { name: 'My Loadouts' })).toBeFocused();
    await expect(list.getByRole('tab', { name: 'My Loadouts' })).toHaveAttribute('aria-selected', 'true');
  });
});

test.describe('pass 104: result counts', () => {
  test('the Database result count is a status region that follows the filter', async ({ page }) => {
    await page.route('**/api/database?*', (r) => {
      const hats = new URL(r.request().url()).searchParams.get('category') === 'Hats';
      const items = hats
        ? [{ id: 1, name: 'Top Hat', category: 'Hats', rarity: 'Standard', supply: 5, lowestPrice: 2 }]
        : [{ id: 1, name: 'Top Hat', category: 'Hats', rarity: 'Standard', supply: 5, lowestPrice: 2 },
           { id: 2, name: 'Blue Jacket', category: 'Jackets', rarity: 'Standard', supply: 3, lowestPrice: 4 }];
      return r.fulfill(json({ items, total: items.length, indexed: 2 }));
    });
    await page.goto('/db');
    const status = page.getByRole('status').filter({ hasText: /results? · page/ });
    await expect(status).toHaveText(/2 results · page 1 \/ 1/, { timeout: 20_000 });
    await page.getByRole('combobox', { name: 'Filter catalogue by category' }).selectOption('Hats');
    await expect(status).toHaveText(/1 result · page 1 \/ 1/);
  });
});

test.describe('pass 104: form field errors', () => {
  test('Support: an empty New Ticket marks the blank fields and focuses the first', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/support/tickets', (r) => r.fulfill(json([])));
    await page.goto('/support');
    await page.getByRole('button', { name: /New Ticket/ }).first().click({ timeout: 20_000 });
    const subject = page.getByRole('textbox', { name: 'Ticket subject' });
    const body = page.getByRole('textbox', { name: 'Ticket message body' });
    await body.fill('It never arrived');
    await page.getByRole('button', { name: 'Submit Ticket' }).click();
    await expect(page.getByText(/Add a subject and a message first/)).toBeVisible();
    await expect(subject).toHaveAttribute('aria-invalid', 'true');
    await expect(subject).toBeFocused();
    await expect(body).not.toHaveAttribute('aria-invalid', 'true');
    await subject.fill('Where is my item');
    await expect(subject).not.toHaveAttribute('aria-invalid', 'true');
  });

  test('Sell: a bad Auto-accept value marks that field, not the asking price', async ({ page }) => {
    await signedIn(page);
    const row = {
      assetId: '104001', assetIds: ['104001'], quantity: 1, name: 'Pct Hat', type: 'Hat', iconUrl: null,
      tradable: true, marketable: true, category: 'Hats', rarity: 'Standard', catalogueId: 1041,
      suggestedPrice: 6, steamPrice: null, listedCount: 0, listableQuantity: 1, unlistableReason: null,
    };
    await page.route('**/api/steam/inventory', (r) => r.fulfill(json({
      items: [row], count: 1, assetCount: 1, lastSyncedAt: Date.now(), steamId64: '7656',
    })));
    await page.route('**/api/items/*/recent-sales*', (r) => r.fulfill(json([])));
    await page.route('**/api/listings/item/*', (r) => r.fulfill(json([])));
    await page.goto('/sell');
    await page.locator('.inventory-item').first().click({ timeout: 20_000 });
    await page.getByRole('textbox', { name: 'Asking price' }).fill('5');
    const pct = page.getByRole('spinbutton', { name: 'Auto-accept discount percent' });
    await pct.fill('60');
    await page.getByRole('button', { name: /List for Sale/ }).click();
    await expect(page.getByRole('alert').filter({ hasText: /whole number from 1% to 50%/ })).toBeVisible();
    await expect(pct).toHaveAttribute('aria-invalid', 'true');
    await expect(pct).toHaveAttribute('aria-describedby', 'sell-error-msg');
    await expect(pct).toBeFocused();
    await expect(page.getByRole('textbox', { name: 'Asking price' })).not.toHaveAttribute('aria-invalid', 'true');
  });

  const PROFILE = { user: { id: 3, displayName: 'carol', steamId64: '7656', tradeUrl: ME.tradeUrl, email: 'c@example.com', emailVerified: true } };

  test('Profile: a malformed trade URL is tied to its box and focus returns there', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/profile/me', (r) => r.fulfill(json(PROFILE)));
    await page.goto('/profile/personal');
    const edit = page.getByRole('button', { name: 'Edit Steam trade URL' });
    await edit.scrollIntoViewIfNeeded();
    await edit.click();
    const box = page.getByRole('textbox', { name: 'Steam trade URL' });
    await box.fill('https://example.com/not-a-trade-url');
    await page.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(page.getByRole('alert').filter({ hasText: /doesn.t look like a Steam trade URL/ })).toBeVisible();
    await expect(box).toHaveAttribute('aria-invalid', 'true');
    await expect(box).toHaveAccessibleDescription(/doesn.t look like a Steam trade URL/);
    await expect(box).toBeFocused();
  });

  test('Profile: a malformed email is tied to its box and focus returns there', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/profile/me', (r) => r.fulfill(json(PROFILE)));
    await page.goto('/profile/personal');
    const edit = page.getByRole('button', { name: 'Edit email address' });
    await edit.scrollIntoViewIfNeeded();
    await edit.click();
    const box = page.getByRole('textbox', { name: 'Email address' });
    await box.fill('not-an-email');
    await page.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(page.getByRole('alert').filter({ hasText: /doesn.t look like a valid email address/ })).toBeVisible();
    await expect(box).toHaveAttribute('aria-invalid', 'true');
    await expect(box).toHaveAccessibleDescription(/doesn.t look like a valid email address/);
    await expect(box).toBeFocused();
  });
});
