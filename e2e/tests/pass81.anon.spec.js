// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 81.

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

test.describe('defect pass 81 · profile', () => {
  const PROFILE = { user: { id: 3, displayName: 'carol', steamId64: '7656', tradeUrl: ME.tradeUrl, email: 'c@example.com', emailVerified: true }, twoFactorEnabled: true };

  test('reviews: a failed Given load keeps the tab strip so the user can go back', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/reviews/user/**', (r) => r.fulfill(json([])));
    await page.route('**/api/reviews/mine', (r) => r.fulfill(json({ error: 'boom' }, 500)));
    await page.goto('/profile/reviews');
    await page.getByRole('tab', { name: /Given/ }).click();
    await expect(page.getByText(/Couldn.t load the reviews you.ve written/)).toBeVisible();
    const received = page.getByRole('tab', { name: /Received/ });
    await expect(received).toBeVisible();
    await received.click();
    await expect(page.getByText(/Couldn.t load the reviews you.ve written/)).toHaveCount(0);
  });

  test('reviews: a failed Received load is announced and keeps the tab strip', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/reviews/user/**', (r) => r.fulfill(json({ error: 'boom' }, 500)));
    await page.route('**/api/reviews/mine', (r) => r.fulfill(json([])));
    await page.goto('/profile/reviews');
    await expect(page.getByRole('alert').filter({ hasText: /Couldn.t load your reviews/ })).toBeVisible();
    await expect(page.getByRole('tab', { name: /Given/ })).toBeVisible();
  });

});

test.describe('defect pass 81 · buyorders', () => {
  const ORDERS = [
    { id: 11, itemId: 1, itemName: 'Wizard Hat', maxPrice: 5, quantity: 1, originalQuantity: 1, status: 'ACTIVE', createdAt: Date.now() - 3600e3, updatedAt: Date.now() - 3600e3 },
    { id: 12, itemId: 2, itemName: 'Red Scarf', maxPrice: 3, quantity: 2, originalQuantity: 2, status: 'ACTIVE', createdAt: Date.now() - 3600e3, updatedAt: Date.now() - 3600e3 },
  ];

  for (const how of ['Escape', 'Cancel edit']) {
    test(`profile buy orders: closing the inline editor with ${how} returns focus to Edit`, async ({ page }) => {
      await signedIn(page);
      await page.route('**/api/buy-orders', (r) => r.fulfill(json(ORDERS)));
      await page.goto('/profile/buyorders');
      const edit = page.getByRole('button', { name: /Edit/ }).first();
      await edit.scrollIntoViewIfNeeded();
      await edit.click();
      const box = page.getByRole('textbox', { name: 'New max price per item in USD' });
      await expect(box).toBeFocused();
      if (how === 'Escape') await box.press('Escape');
      else await page.getByRole('button', { name: 'Cancel edit' }).click();
      await expect(box).toHaveCount(0);
      await expect(page.getByRole('button', { name: /Edit/ }).first()).toBeFocused();
    });
  }
});

test.describe('defect pass 81 · loaderr', () => {

  async function failing(page) {
    await page.route('**/api/**', (r) => r.fulfill(json({ error: 'boom' }, 500)));
    await page.route('**/api/auth/steam/me', (r) => r.fulfill(json(ME)));
  }

  for (const [url, text] of [
    ['/profile/listings', /Couldn.t load your listings/],
    ['/profile/buyorders', /Couldn.t load your buy orders/],
    ['/profile/trades', /Couldn.t load your trades/],
    ['/profile/offers', /Couldn.t load your offers/],
    ['/offers', /Couldn.t load your offers/],
    ['/me/stall', /Couldn.t load your stall/],
  ]) {
    test(`failed load on ${url} is announced as an alert`, async ({ page }) => {
      await failing(page);
      await page.goto(url);
      await expect(page.getByText(text)).toBeVisible();
      await expect(page.getByRole('alert').filter({ hasText: text })).toBeVisible();
    });
  }

  test('failed sold-history load on /me/stall/sold is announced as an alert', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings/my-stall/sold**', (r) => r.fulfill(json({ error: 'boom' }, 500)));
    await page.goto('/me/stall/sold');
    await expect(page.getByRole('alert').filter({ hasText: /Couldn.t load your sold history/ })).toBeVisible();
  });
});

test.describe('defect pass 81 · support', () => {

  test('support: cancelling a new ticket with no tickets keeps focus on New Ticket', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/support/tickets**', (r) => r.fulfill(json([])));
    await page.goto('/profile/support');
    await page.getByRole('button', { name: '+ New Ticket' }).click();
    await expect(page.getByRole('textbox', { name: 'Ticket subject' })).toBeFocused();
    await page.getByRole('button', { name: 'Cancel', exact: true }).click();
    await expect(page.getByRole('textbox', { name: 'Ticket subject' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '+ New Ticket' })).toBeFocused();
  });

  test('developers: dismissing the new key panel returns focus to New Key', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/api-keys**', (r) => r.request().method() === 'POST'
      ? r.fulfill(json({ id: 9, label: 'bot', token: 'sbx_abc', createdAt: Date.now() }))
      : r.fulfill(json([])));
    await page.goto('/profile/developers');
    await page.getByRole('button', { name: '+ New Key' }).click();
    await page.getByRole('button', { name: 'Dismiss' }).click();
    await expect(page.getByText(/COPY THIS NOW/)).toHaveCount(0);
    await expect(page.getByRole('button', { name: '+ New Key' })).toBeFocused();
  });

  test('support: a slow earlier ticket open does not replace the ticket opened last', async ({ page }) => {
    await signedIn(page);
    const T = (id, subject) => ({ id, subject, status: 'WAITING_STAFF', category: 'OTHER', updatedAt: Date.now() });
    const detail = (id, subject) => ({ ticket: T(id, subject), messages: [{ id: id * 10, author: 'USER', authorName: 'carol', body: 'Body of ' + subject, createdAt: Date.now() }] });
    await page.route('**/api/support/tickets', (r) => r.fulfill(json([T(1, 'Slow ticket'), T(2, 'Fast ticket')])));
    await page.route('**/api/support/tickets/1', (r) => new Promise((ok) => setTimeout(ok, 1500)).then(() => r.fulfill(json(detail(1, 'Slow ticket')))));
    await page.route('**/api/support/tickets/2', (r) => r.fulfill(json(detail(2, 'Fast ticket'))));
    await page.goto('/profile/support');
    await page.getByRole('button', { name: /Open ticket #1/ }).click();
    await page.getByRole('button', { name: /Open ticket #2/ }).click();
    await expect(page.getByText('Body of Fast ticket')).toBeVisible();
    await page.waitForTimeout(2000);
    await expect(page.getByText('Body of Fast ticket')).toBeVisible();
    await expect(page.getByText('Body of Slow ticket')).toHaveCount(0);
  });
});

test.describe('defect pass 81 · staff', () => {

  test('admin announce: a rejected banner post is announced as an alert', async ({ page }) => {
    await asAdmin(page);
    await page.route('**/api/admin/announcements', (r) => r.request().method() === 'POST'
      ? r.fulfill(json({ message: 'Banner text rejected by server' }, 400))
      : r.fulfill(json([])));
    await page.goto('/admin?tab=announce');
    await page.getByPlaceholder(/Message \(3/).fill('Maintenance tonight');
    await page.getByRole('button', { name: 'Post banner' }).click();
    await expect(page.getByRole('alert').filter({ hasText: /Banner text rejected by server/ })).toBeVisible();
  });

  test('admin announce: a failed broadcast is announced as an alert', async ({ page }) => {
    await asAdmin(page);
    await page.route('**/api/admin/announcements', (r) => r.fulfill(json([])));
    await page.route('**/api/admin/broadcast**', (r) => r.fulfill(json({ message: 'Broadcast refused by server' }, 400)));
    await page.goto('/admin?tab=announce');
    await page.getByPlaceholder(/Title \(required/).fill('New feature');
    await page.getByRole('button', { name: /Send broadcast/ }).click();
    await expect(page.getByRole('alert').filter({ hasText: /Broadcast refused by server/ })).toBeVisible();
  });

  const T = (id, subject) => ({ id, subject, status: 'WAITING_STAFF', category: 'OTHER', userId: 40 + id, username: 'user' + id, updatedAt: Date.now() });
  const detail = (id, subject) => ({ ticket: T(id, subject), messages: [{ id: id * 10, author: 'USER', authorName: 'user' + id, body: 'Body of ' + subject, createdAt: Date.now() }] });
  for (const [who, url, base, setup] of [
    ['admin', '/admin?tab=tickets', 'admin', asAdmin],
  ]) {
    test(`${who} tickets: a slow earlier ticket open does not replace the ticket opened last`, async ({ page }) => {
      await setup(page);
      await page.route(`**/api/${base}/tickets?**`, (r) => r.fulfill(json([T(1, 'Slow ticket'), T(2, 'Fast ticket')])));
      await page.route(`**/api/${base}/tickets`, (r) => r.fulfill(json([T(1, 'Slow ticket'), T(2, 'Fast ticket')])));
      await page.route(`**/api/${base}/tickets/1`, (r) => new Promise((ok) => setTimeout(ok, 1500)).then(() => r.fulfill(json(detail(1, 'Slow ticket')))));
      await page.route(`**/api/${base}/tickets/2`, (r) => r.fulfill(json(detail(2, 'Fast ticket'))));
      await page.goto(url);
      if (who === 'CSR') await page.getByRole('tab', { name: /Tickets/ }).click();
      await page.getByRole('button', { name: /Open ticket #1/ }).click();
      await page.getByRole('button', { name: /Open ticket #2/ }).click();
      await expect(page.getByText('#2 · Fast ticket')).toBeVisible();
      await page.waitForTimeout(2000);
      await expect(page.getByText('#2 · Fast ticket')).toBeVisible();
      await expect(page.getByText('#1 · Slow ticket')).toHaveCount(0);
    });
  }
});
