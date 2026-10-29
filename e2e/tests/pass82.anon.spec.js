// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 82.

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

test.describe('defect pass 82 · report', () => {
  const ITEM = { id: 5, name: 'Black Watch', category: 'Hats', rarity: 'Common', lowestPrice: 10, imageUrl: '' };
  const LISTING = { id: 11, price: 10, sellerUserId: 9, sellerName: 'bob', status: 'ACTIVE', item: ITEM };

  async function itemPage(page) {
    await signedIn(page);
    await page.route('**/api/listings/item/5**', (r) => r.fulfill(json([LISTING])));
    await page.route('**/api/items/5', (r) => r.fulfill(json(ITEM)));
    await page.route('**/api/listings/report-reasons', (r) => r.fulfill(json(['Suspicious pricing', 'Other'])));
  }

  test('failed listing report error is announced as an alert', async ({ page }) => {
    await itemPage(page);
    await page.route('**/api/listings/11/report', (r) => r.fulfill(json({ error: 'Report service down' }, 500)));
    await page.goto('/item/5');
    await page.getByRole('button', { name: 'Report listing 11' }).click();
    const dlg = page.getByRole('dialog', { name: 'Report listing' });
    await dlg.getByRole('button', { name: 'Submit report' }).click();
    await expect(dlg.getByText(/Report service down/)).toBeVisible();
    await expect(dlg.getByRole('alert').filter({ hasText: /Report service down/ })).toBeVisible();
  });

  test('Escape does not close the listing report drawer while the report is submitting', async ({ page }) => {
    await itemPage(page);
    await page.route('**/api/listings/11/report', slow({ thanks: 'Report received.' }, 1500));
    await page.goto('/item/5');
    await page.getByRole('button', { name: 'Report listing 11' }).click();
    const dlg = page.getByRole('dialog', { name: 'Report listing' });
    await dlg.getByRole('button', { name: 'Submit report' }).click();
    await expect(dlg.getByRole('button', { name: /Submitting/ })).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(dlg).toBeVisible();
    await expect(dlg.getByText(/Report received/)).toBeVisible();
  });
});

test.describe('defect pass 82 · trade-report', () => {
  const TRADE = { id: 71, state: 'PENDING_SELLER_SEND', buyerUserId: ME.id, sellerUserId: 9, itemName: 'Black Watch', price: 10, counterpartyName: 'bob', createdAt: Date.now(), updatedAt: Date.now() };

  async function tradesPage(page, report) {
    await signedIn(page);
    await page.route('**/api/support/report-user/9', report);
    await page.route('**/api/trades', (r) => r.fulfill(json([TRADE])));
    await page.goto('/profile/trades');
    await page.getByRole('button', { name: /^Report bob on / }).click();
    return page.getByRole('dialog', { name: 'Report counterparty' });
  }

  test('failed counterparty report error is announced as an alert', async ({ page }) => {
    const dlg = await tradesPage(page, (r) => r.fulfill(json({ error: 'Report desk unavailable' }, 500)));
    await dlg.getByRole('button', { name: /File report/ }).click();
    await expect(dlg.getByText(/Report desk unavailable/)).toBeVisible();
    await expect(dlg.getByRole('alert').filter({ hasText: /Report desk unavailable/ })).toBeVisible();
  });

  test('Escape does not close the counterparty report drawer while the report is filing', async ({ page }) => {
    const dlg = await tradesPage(page, slow({ error: 'Report desk unavailable' }, 1500));
    await dlg.getByRole('button', { name: /File report/ }).click();
    await expect(dlg.getByRole('button', { name: /Filing|Submitting/ })).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(dlg).toBeVisible();
    await expect(dlg.getByText(/Report desk unavailable/)).toBeVisible();
  });
});

test.describe('defect pass 82 · stall-report', () => {
  test('failed seller report error on a stall is announced as an alert', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings/stall/9', (r) => r.fulfill(json({ seller: { id: 9, displayName: 'bob' }, listings: [] })));
    await page.route('**/api/support/report-user/9', (r) => r.fulfill(json({ error: 'Report desk unavailable' }, 500)));
    await page.goto('/stall/9');
    await page.getByRole('button', { name: 'Report', exact: true }).click();
    const dlg = page.getByRole('dialog', { name: 'Report seller' });
    await dlg.getByRole('button', { name: 'File report' }).click();
    await expect(dlg.getByText(/Report desk unavailable/)).toBeVisible();
    await expect(dlg.getByRole('alert').filter({ hasText: /Report desk unavailable/ })).toBeVisible();
  });
});

test.describe('defect pass 82 · twofa', () => {
  const PROFILE = { user: { ...ME, email: 'c@example.com', emailVerified: true }, twoFactorEnabled: true };

  test('wrong code when disabling 2FA is announced as an alert', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/profile/me', (r) => r.fulfill(json(PROFILE)));
    await page.route('**/api/profile/2fa/disable', (r) => r.fulfill(json({ error: 'That code is not valid' }, 400)));
    await page.goto('/profile/personal');
    await page.getByRole('button', { name: 'Disable', exact: true }).click();
    await page.getByRole('textbox', { name: /authenticator code or backup code/ }).fill('backup-1234');
    await page.getByRole('button', { name: 'Disable 2FA' }).click();
    await expect(page.getByText(/That code is not valid/)).toBeVisible();
    await expect(page.getByRole('alert').filter({ hasText: /That code is not valid/ })).toBeVisible();
  });

  test('wrong code when regenerating 2FA backup codes is announced as an alert', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/profile/me', (r) => r.fulfill(json(PROFILE)));
    await page.route('**/api/profile/2fa/regenerate-codes', (r) => r.fulfill(json({ error: 'That code is not valid' }, 400)));
    await page.goto('/profile/personal');
    await page.getByRole('button', { name: 'Regenerate codes' }).click();
    await page.getByRole('textbox', { name: '6-digit authenticator code' }).fill('123456');
    await expect(page.getByText(/That code is not valid/)).toBeVisible();
    await expect(page.getByRole('alert').filter({ hasText: /That code is not valid/ })).toBeVisible();
  });

  test('failed Enable 2FA start shows its error instead of doing nothing', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/profile/me', (r) => r.fulfill(json({ ...PROFILE, twoFactorEnabled: false })));
    await page.route('**/api/profile/2fa/enroll', (r) => r.fulfill(json({ error: 'Two-factor setup is unavailable' }, 503)));
    await page.goto('/profile/personal');
    await page.getByRole('button', { name: 'Enable 2FA' }).click();
    await expect(page.getByRole('alert').filter({ hasText: /Two-factor setup is unavailable/ })).toBeVisible();
  });

  test('wrong code when confirming 2FA setup is announced as an alert', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/profile/me', (r) => r.fulfill(json({ ...PROFILE, twoFactorEnabled: false })));
    await page.route('**/api/profile/2fa/enroll', (r) => r.fulfill(json({ secret: 'ABCDEF', otpauthUrl: 'otpauth://totp/x?secret=ABCDEF' })));
    await page.route('**/api/profile/2fa/confirm', (r) => r.fulfill(json({ error: 'That code is not valid' }, 400)));
    await page.goto('/profile/personal');
    await page.getByRole('button', { name: 'Enable 2FA' }).click();
    await page.getByRole('button', { name: 'Confirm & Enable' }).waitFor();
    const box = page.getByRole('textbox', { name: /6-digit/ }).first();
    await box.fill('123456');
    await expect(page.getByText(/That code is not valid/)).toBeVisible();
    await expect(page.getByRole('alert').filter({ hasText: /That code is not valid/ })).toBeVisible();
  });
});
