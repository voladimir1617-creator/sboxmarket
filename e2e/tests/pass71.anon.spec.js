// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 71.

const ME = { id: 3, displayName: 'carol', role: 'USER', signedIn: true, sessionEpoch: 0 };
const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });
const slow = (body, ms = 800) => (r) => new Promise((ok) => setTimeout(ok, ms)).then(() => r.fulfill(json(body)));

async function signedIn(page) {
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json(ME)));
}
const ticket = (status, subject = 'Where is my item') => ({ id: 7, subject, category: 'TRADE', status, createdAt: Date.now() - 3_600_000, updatedAt: Date.now() });
async function support(page, subject) {
  let status = 'WAITING_USER';
  await page.route(/\/api\/support\/tickets(\?.*)?$/, (r) => r.fulfill(json([ticket(status, subject)])));
  await page.route('**/api/support/tickets/7', (r) => r.fulfill(json({
    ticket: ticket(status, subject),
    messages: [{ id: 1, body: 'Can you check?', fromStaff: false, createdAt: Date.now() - 3_600_000 }],
  })));
  await page.route('**/api/support/tickets/7/reply', (r) => { status = 'WAITING_STAFF'; return r.fulfill(json({})); });
}

test.describe('defect pass 71', () => {
  test('replying to a ticket updates its status in the ticket list', async ({ page }) => {
    await signedIn(page);
    await support(page);
    await page.goto('/support');
    await page.getByText('Where is my item').first().click({ timeout: 20_000 });
    await page.getByLabel('Reply to support').fill('Any news?');
    await page.getByRole('button', { name: 'Send', exact: true }).click();
    await expect(page.getByText(/Waiting staff/i).first()).toBeVisible();
    await page.getByRole('button', { name: '← Tickets' }).click();
    await expect(page.getByRole('button', { name: /Open ticket #7 .*\(Waiting staff\)/ })).toBeVisible();
  });

  test('the support reply box stays usable on a phone, with a long subject', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await signedIn(page);
    await support(page, 'Refund for https://sboxmarket.example/item/12345?listing=67890');
    await page.goto('/support');
    await page.getByText(/Refund for/).first().click({ timeout: 20_000 });
    const box = await page.getByLabel('Reply to support').boundingBox();
    expect(box && box.width).toBeGreaterThan(150);
    const resolve = await page.getByRole('button', { name: 'Mark Resolved' }).boundingBox();
    expect(resolve && resolve.x + resolve.width).toBeLessThanOrEqual(390);
  });

  test('revoking all keys takes down the new key\'s one-time token panel', async ({ page }) => {
    page.on('dialog', (d) => d.accept());
    await signedIn(page);
    let keys = [{ id: 1, label: 'old', publicPrefix: 'sbk_old', scope: 'RW', status: 'ACTIVE', createdAt: Date.now() - 86_400_000 }];
    await page.route(/\/api\/api-keys$/, (r) => {
      const m = r.request().method();
      if (m === 'POST') {
        const k = { id: 2, label: 'bot', token: 'sbk_secret_token_value', publicPrefix: 'sbk_new', scope: 'RW', createdAt: Date.now() };
        keys = [...keys, { ...k, status: 'ACTIVE' }];
        return r.fulfill(json(k));
      }
      if (m === 'DELETE') { keys = keys.map((k) => ({ ...k, status: 'REVOKED' })); return r.fulfill(json({ revoked: 2 })); }
      return r.fulfill(json(keys));
    });
    await page.goto('/profile/developers');
    await page.getByRole('button', { name: '+ New Key' }).click({ timeout: 20_000 });
    await expect(page.getByText('sbk_secret_token_value')).toBeVisible();
    await page.getByRole('button', { name: /Revoke all active keys/ }).click();
    await expect(page.getByText('sbk_secret_token_value')).toHaveCount(0);
  });

  test('the new key\'s Dismiss button fits a phone screen', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await signedIn(page);
    await page.route(/\/api\/api-keys$/, (r) => r.fulfill(r.request().method() === 'POST'
      ? json({ id: 2, label: 'bot', token: 'sbk_secret_token_value', publicPrefix: 'sbk_new', scope: 'RW', createdAt: Date.now() })
      : json([])));
    await page.goto('/profile/developers');
    await page.getByRole('button', { name: '+ New Key' }).click({ timeout: 20_000 });
    const b = await page.getByRole('button', { name: 'Dismiss' }).boundingBox();
    expect(b && b.x + b.width).toBeLessThanOrEqual(390);
  });

  test('a failed accept on an offer that is no longer pending refreshes the row', async ({ page }) => {
    await signedIn(page);
    let status = 'PENDING';
    await page.route('**/api/offers/incoming*', (r) => r.fulfill(json([
      { id: 41, listingId: 9, itemId: 5, itemName: 'Red Hat', amount: '8.00', askingPrice: '10.00', status, author: 'BUYER', createdAt: Date.now() },
    ])));
    await page.route('**/api/offers/outgoing*', (r) => r.fulfill(json([])));
    await page.route('**/api/offers/41/accept', (r) => { status = 'CANCELLED'; return r.fulfill(json({ code: 'OFFER_NOT_PENDING', message: 'This offer is no longer pending (CANCELLED)' }, 409)); });
    await page.goto('/profile/offers');
    const accept = page.getByRole('button', { name: /Accept/ }).first();
    await accept.click({ timeout: 20_000 });
    await expect(page.getByText(/no longer pending/).first()).toBeVisible();
    await expect(page.getByRole('button', { name: /Accept/ })).toHaveCount(0);
  });

  test('deleting one written review only marks that review as deleting', async ({ page }) => {
    page.on('dialog', (d) => d.accept());
    await signedIn(page);
    const row = (id, sellerName) => ({ id, tradeId: id, rating: 4, comment: 'ok', sellerUserId: id, sellerName, createdAt: Date.now() - 3_600_000, mine: true });
    await page.route('**/api/reviews/mine*', (r) => r.fulfill(json([row(21, 'bob'), row(22, 'dave')])));
    await page.route('**/api/reviews/pending*', (r) => r.fulfill(json({ count: 0, items: [] })));
    await page.route('**/api/reviews/21', slow({}, 1500));
    await page.goto('/profile/reviews');
    await page.getByRole('tab', { name: /^Given/ }).click({ timeout: 20_000 });
    await page.getByRole('button', { name: 'Delete', exact: true }).first().click();
    await expect(page.getByRole('button', { name: 'Deleting…' })).toHaveCount(1);
  });
});
