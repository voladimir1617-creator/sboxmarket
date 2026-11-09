// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 100.

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


async function anon(page) {
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json({ signedIn: false })));
}
const HAT = { id: 31, name: 'Offer Main Hat', rarity: 'Standard', category: 'Hats', lowestPrice: 3, isListed: true, supply: 1 };
async function itemStubs(page) {
  await anon(page);
  await page.route('**/api/items/31', (r) => r.fulfill(json(HAT)));
  await page.route('**/api/listings/item/31*', (r) => r.fulfill(json([
    { id: 310, price: 3, listingType: 'BUY_NOW', status: 'ACTIVE', sellerName: 'railseller', sellerUserId: 7, listedAt: Date.now(), item: HAT },
  ])));
  await page.route('**/api/listings/seller/7/other*', (r) => r.fulfill(json([
    { id: 55, price: 2, listingType: 'BUY_NOW', status: 'ACTIVE', sellerUserId: 7, item: { id: 3, name: 'Rail Hat', rarity: 'Standard', category: 'Hats' } },
  ])));
  await page.route('**/api/items/31/similar*', (r) => r.fulfill(json([
    { id: 32, name: 'Busy Hat', rarity: 'Standard', category: 'Hats', lowestPrice: 0.59, isListed: true, supply: 4 },
  ])));
}

async function skipToMain(page) {
  const skip = page.getByRole('link', { name: /Skip to main content/ });
  await skip.focus();
  await skip.press('Enter');
  await page.keyboard.press('Tab');
  return page.evaluate(() => !!document.activeElement.closest('main, [role="main"]'));
}

test.describe('Item page', () => {
  test('Skip to main content lands inside the item page', async ({ page }) => {
    await itemStubs(page);
    await page.goto('/item/31');
    await expect(page.getByRole('heading', { level: 1, name: 'Offer Main Hat' })).toBeVisible({ timeout: 45000 });
    expect(await skipToMain(page)).toBe(true);
  });

  test('Skip to main content lands inside the Item not found page', async ({ page }) => {
    await anon(page);
    await page.route('**/api/items/99999', (r) => r.fulfill(json({ error: 'not found' }, 404)));
    await page.goto('/item/99999');
    await expect(page.getByTestId('item-not-found')).toBeVisible({ timeout: 45000 });
    await expect(page.getByRole('main', { name: /Item not found/ })).toBeVisible();
    expect(await skipToMain(page)).toBe(true);
  });

  test('rail cards name the item once, with price and offer count apart', async ({ page }) => {
    await itemStubs(page);
    await page.goto('/item/31');
    const similar = page.getByTestId('similar-offers');
    await similar.scrollIntoViewIfNeeded({ timeout: 45000 });
    await expect(similar.getByRole('link', { name: /^Busy Hat, \$0\.59, 4 offers$/ })).toBeVisible();
    await expect(page.getByRole('link', { name: /^Rail Hat, \$2\.00$/ })).toBeVisible();
  });
});

test.describe('Item page listings', () => {
  test('signed-out Make offer buttons name their listing', async ({ page }) => {
    await itemStubs(page);
    await page.route('**/api/listings/item/31*', (r) => r.fulfill(json([
      { id: 310, price: 3, listingType: 'BUY_NOW', status: 'ACTIVE', sellerName: 'railseller', sellerUserId: 7, listedAt: Date.now(), item: HAT },
      { id: 311, price: 4, listingType: 'BUY_NOW', status: 'ACTIVE', sellerName: 'other', sellerUserId: 8, listedAt: Date.now(), item: HAT },
    ])));
    await page.goto('/item/31');
    const second = page.getByRole('button', { name: /^Sign in with Steam to make an offer on listing #311$/ });
    await second.scrollIntoViewIfNeeded({ timeout: 45000 });
    await expect(second).toBeVisible();
    await expect(page.getByRole('button', { name: /^Sign in with Steam to make an offer on listing #310$/ })).toBeVisible();
  });
});

test.describe('Help Center and FAQ', () => {
  test('a top-question preview moves focus to the question it opens', async ({ page }) => {
    await signedIn(page);
    await page.goto('/help');
    const preview = page.getByRole('group', { name: 'Top FAQ questions' }).getByRole('button', { name: /How do I sign in/ });
    await preview.scrollIntoViewIfNeeded({ timeout: 45000 });
    await preview.focus();
    await preview.press('Enter');
    const q = page.locator('.help-faq').getByRole('button', { name: /^How do I sign in\?$/ });
    await expect(q).toHaveAttribute('aria-expanded', 'true');
    await expect(q).toBeFocused();
  });

  test('Help Center no-match title is a heading', async ({ page }) => {
    await signedIn(page);
    await page.goto('/help');
    const box = page.getByRole('searchbox', { name: /Search frequently asked questions/ });
    await box.scrollIntoViewIfNeeded({ timeout: 45000 });
    await box.fill('zzqqxx');
    await expect(page.getByRole('heading', { name: /No FAQs match .zzqqxx./ })).toBeVisible();
  });

  test('FAQ no-match title is a heading', async ({ page }) => {
    await signedIn(page);
    await page.goto('/faq');
    await page.getByRole('textbox', { name: 'Search FAQ' }).fill('zzqqxx');
    await expect(page.getByRole('heading', { name: /No FAQs match .zzqqxx./ })).toBeVisible({ timeout: 45000 });
  });
});

test.describe('Notification settings', () => {
  test('Settings mute chips are grouped under their row name', async ({ page }) => {
    await signedIn(page);
    await page.goto('/settings');
    const group = page.getByRole('group', { name: 'Mute notification types' });
    await group.scrollIntoViewIfNeeded({ timeout: 45000 });
    await expect(group.getByRole('button', { name: /^Trades$/ })).toHaveAttribute('aria-pressed', 'false');
    await group.getByRole('button', { name: /^Trades$/ }).click();
    await expect(group.getByRole('button', { name: /Muted · Trades/ })).toHaveAttribute('aria-pressed', 'true');
  });

  test('email categories Retry keeps keyboard focus once they load', async ({ page }) => {
    await signedIn(page);
    let calls = 0;
    await page.route('**/api/profile/email-mutes', (r) => (++calls === 1
      ? r.fulfill(json({ error: 'boom' }, 500))
      : r.fulfill(json({ available: ['TRADES', 'AUCTIONS'], muted: [] }))));
    await page.goto('/profile/personal');
    const retry = page.getByRole('alert').filter({ hasText: /Email categories failed to load/ }).getByRole('button', { name: 'Retry' });
    await retry.scrollIntoViewIfNeeded({ timeout: 45000 });
    await retry.focus();
    await retry.press('Enter');
    const box = page.getByRole('checkbox', { name: /Trade activity/ });
    await expect(box).toBeVisible();
    await expect(box).toBeFocused();
  });
});

test.describe('Admin read-only views', () => {
  test('user detail Audit opens the Audit Log filtered on that user', async ({ page }) => {
    await asAdmin(page);
    await page.route('**/api/admin/users?**', (r) => r.fulfill(json([{ id: 42, displayName: 'auditee', steamId64: '7656119', role: 'USER', banned: false }])));
    await page.route('**/api/admin/users', (r) => r.fulfill(json([{ id: 42, displayName: 'auditee', steamId64: '7656119', role: 'USER', banned: false }])));
    await page.route('**/api/admin/audit?**', (r) => r.fulfill(json([])));
    await page.goto('/admin?tab=users');
    await page.getByRole('button', { name: /View user detail for auditee/ }).click({ timeout: 45000 });
    const audit = page.getByRole('dialog', { name: 'auditee' }).getByRole('link', { name: /Audit/ });
    await expect(audit).toHaveAttribute('href', '/admin?tab=audit&subject=42');
    await audit.click();
    await expect(page.getByRole('tab', { name: /Audit Log/ })).toHaveAttribute('aria-selected', 'true');
    await expect(page.getByRole('textbox', { name: 'Subject user #id' })).toHaveValue('42');
    await expect(page.getByRole('tab', { name: /Audit Log/ })).toBeFocused();
  });
});
