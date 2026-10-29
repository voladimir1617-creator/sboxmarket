// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 80.

const ME = { id: 3, displayName: 'carol', role: 'USER', signedIn: true, sessionEpoch: 0, tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc' };
const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });

async function signedIn(page) {
  await page.addInitScript(() => { window.confirm = () => true; });
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json(ME)));
}
async function asAdmin(page) {
  await signedIn(page);
  await page.route('**/api/admin/check', (r) => r.fulfill(json({ admin: true })));
}

test.describe('defect pass 80 · personal', () => {
  const PROFILE = { user: { id: 3, displayName: 'carol', steamId64: '7656', tradeUrl: ME.tradeUrl, email: 'c@example.com', emailVerified: true } };

  async function personal(page) {
    await signedIn(page);
    await page.route('**/api/profile/me', (r) => r.fulfill(json(PROFILE)));
    await page.goto('/profile/personal');
  }

  for (const how of ['Escape', 'Cancel']) {
    test(`personal: closing the trade URL editor with ${how} returns focus to Edit`, async ({ page }) => {
      await personal(page);
      const edit = page.getByRole('button', { name: 'Edit Steam trade URL' });
      await edit.scrollIntoViewIfNeeded();
      await edit.click();
      const box = page.getByRole('textbox', { name: 'Steam trade URL' });
      await expect(box).toBeFocused();
      if (how === 'Escape') await box.press('Escape');
      else await page.getByRole('button', { name: 'Cancel', exact: true }).click();
      await expect(box).toHaveCount(0);
      await expect(edit).toBeFocused();
    });
    test(`personal: closing the email editor with ${how} returns focus to Edit`, async ({ page }) => {
      await personal(page);
      const edit = page.getByRole('button', { name: 'Edit email address' });
      await edit.scrollIntoViewIfNeeded();
      await edit.click();
      const box = page.getByRole('textbox', { name: 'Email address' });
      await expect(box).toBeFocused();
      if (how === 'Escape') await box.press('Escape');
      else await page.getByRole('button', { name: 'Cancel email edit' }).click();
      await expect(box).toHaveCount(0);
      await expect(edit).toBeFocused();
    });
  }

  test('personal: a failed trade URL save is announced as an alert', async ({ page }) => {
    await personal(page);
    await page.route('**/api/profile/trade-url', (r) => r.fulfill(json({ error: 'Trade URL rejected by server' }, 400)));
    const edit = page.getByRole('button', { name: 'Edit Steam trade URL' });
    await edit.scrollIntoViewIfNeeded();
    await edit.click();
    await page.getByRole('textbox', { name: 'Steam trade URL' }).fill('https://steamcommunity.com/tradeoffer/new/?partner=2&token=xyz');
    await page.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(page.getByRole('alert').filter({ hasText: /Trade URL rejected by server/ })).toBeVisible();
  });

  for (const [label, api] of [[/Show last 20 sign-ins/, '**/api/profile/sign-in-history**'], [/Show recent security events/, '**/api/profile/security-activity**']]) {
    test(`personal: the ${label.source} toggle reports its expanded state`, async ({ page }) => {
      await personal(page);
      await page.route(api, (r) => r.fulfill(json([])));
      const btn = page.getByRole('button', { name: label });
      await btn.scrollIntoViewIfNeeded();
      await expect(btn).toHaveAttribute('aria-expanded', 'false');
      await btn.click();
      const hide = page.getByRole('button', { name: 'Hide', exact: true });
      await expect(hide).toHaveAttribute('aria-expanded', 'true');
    });
  }

  test('personal: a rejected email address is announced as an alert', async ({ page }) => {
    await personal(page);
    const edit = page.getByRole('button', { name: 'Edit email address' });
    await edit.scrollIntoViewIfNeeded();
    await edit.click();
    await page.getByRole('textbox', { name: 'Email address' }).fill('not-an-email');
    await page.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(page.getByRole('alert').filter({ hasText: /doesn.t look like a valid email address/ })).toBeVisible();
  });
});

test.describe('defect pass 80 · staff', () => {

  async function asCsr(page) {
    await signedIn(page);
    await page.route('**/api/csr/check', (r) => r.fulfill(json({ csr: true })));
  }

  for (const ok of [false, true]) {
    test(`csr: flag listing ${ok ? 'success' : 'failure'} is announced to screen readers`, async ({ page }) => {
      await asCsr(page);
      await page.route('**/api/csr/listings/42/flag', (r) => ok
        ? r.fulfill(json({ id: 42 }))
        : r.fulfill(json({ error: 'Listing 42 is not active' }, 409)));
      await page.goto('/csr');
      await page.getByRole('tab', { name: 'Flag Listing' }).click();
      await page.getByPlaceholder('Listing ID').fill('42');
      await page.getByRole('combobox', { name: 'Flag reason' }).selectOption({ index: 1 });
      await page.getByRole('button', { name: /Flag listing/ }).click();
      const role = ok ? 'status' : 'alert';
      const text = ok ? /Listing #42 added to the admin review queue/ : /Listing 42 is not active/;
      await expect(page.getByRole(role).filter({ hasText: text })).toBeVisible();
    });
  }

  test('csr: ticket queue filter buttons say which one is selected', async ({ page }) => {
    await asCsr(page);
    await page.route('**/api/csr/tickets**', (r) => r.fulfill(json([])));
    await page.goto('/csr');
    await page.getByRole('tab', { name: 'Tickets' }).click();
    await expect(page.getByRole('button', { name: 'Waiting on us', exact: true })).toHaveAttribute('aria-pressed', 'true');
    await page.getByRole('button', { name: 'All', exact: true }).click();
    await expect(page.getByRole('button', { name: 'All', exact: true })).toHaveAttribute('aria-pressed', 'true');
    await expect(page.getByRole('button', { name: 'Waiting on us', exact: true })).toHaveAttribute('aria-pressed', 'false');
  });

  test('admin: ticket queue filter buttons say which one is selected', async ({ page }) => {
    await asAdmin(page);
    await page.route('**/api/admin/tickets**', (r) => r.fulfill(json([])));
    await page.goto('/admin?tab=tickets');
    await expect(page.getByRole('button', { name: 'All', exact: true })).toHaveAttribute('aria-pressed', 'true');
    await page.getByRole('button', { name: 'Resolved', exact: true }).click();
    await expect(page.getByRole('button', { name: 'Resolved', exact: true })).toHaveAttribute('aria-pressed', 'true');
    await expect(page.getByRole('button', { name: 'All', exact: true })).toHaveAttribute('aria-pressed', 'false');
  });

  test('admin: trade state filter buttons say which one is selected', async ({ page }) => {
    await asAdmin(page);
    await page.route('**/api/admin/trades**', (r) => r.fulfill(json([])));
    await page.goto('/admin?tab=trades');
    const chips = page.locator('button.offer-tab');
    await expect(chips.first()).toBeVisible();
    await expect(page.locator('button.offer-tab.active')).toHaveAttribute('aria-pressed', 'true');
    const other = page.locator('button.offer-tab:not(.active)').first();
    const name = (await other.innerText()).trim();
    await other.click();
    await expect(page.getByRole('button', { name, exact: true })).toHaveAttribute('aria-pressed', 'true');
  });
});

test.describe('defect pass 80 · nav', () => {

  test('header: the Clear search button puts focus back in the search box', async ({ page }) => {
    await signedIn(page);
    await page.goto('/market');
    const box = page.getByRole('combobox', { name: 'Search listings' });
    await box.fill('hat');
    await page.getByRole('button', { name: 'Clear search' }).click();
    await expect(box).toHaveValue('');
    await expect(box).toBeFocused();
  });

  for (const [route, label] of [['/faq', 'Search FAQ'], ['/help', 'Search frequently asked questions']]) {
    test(`${route}: Clear search on a no-match search puts focus back in the search box`, async ({ page }) => {
      await signedIn(page);
      await page.goto(route);
      const box = page.getByRole(route === '/help' ? 'searchbox' : 'textbox', { name: label });
      await box.scrollIntoViewIfNeeded();
      await box.fill('zzqqxx');
      await expect(page.getByText(/No FAQs match/)).toBeVisible();
      await page.getByRole('button', { name: 'Clear search' }).click();
      await expect(box).toHaveValue('');
      await expect(box).toBeFocused();
    });
  }
});

test.describe('defect pass 80 · item', () => {
  const ITEM = { id: 11, name: 'Red Beanie', category: 'Hats', rarity: 'Standard', lowestPrice: 5, isListed: true, supply: 8 };
  const LS = Array.from({ length: 8 }, (_, i) => ({ id: 101 + i, price: 5 + i, listingType: 'BUY_NOW', status: 'ACTIVE', item: ITEM, sellerUserId: 20 + i, sellerName: 'seller' + i, listedAt: Date.now() }));

  test('item: the Show all listings expander reports its expanded state', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/items/11', (r) => r.fulfill(json(ITEM)));
    await page.route('**/api/listings/item/11', (r) => r.fulfill(json(LS)));
    await page.goto('/item/11');
    const btn = page.getByRole('button', { name: /Show all 8 listings/ });
    await btn.scrollIntoViewIfNeeded();
    await expect(btn).toHaveAttribute('aria-expanded', 'false');
    await btn.click();
    await expect(page.getByRole('button', { name: /Collapse to top 6/ })).toHaveAttribute('aria-expanded', 'true');
  });
});
