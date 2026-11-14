// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 121.

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

// Pass 121: Back/Forward focus on routed tablists (Profile, My Stall, Offers)
// the disabled look of aria-disabled .btn Send/Save buttons, and the
// report/mark-sent/catalogue drawers whose fields ran to the panel edges.

const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';
const consent = (page) => page.addInitScript(() => {
  try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {}
});
const devLogin = (page, userId = 1) => page.request.get(`${base}/api/auth/steam/dev-login?userId=${userId}&next=/`);

test.describe('Routed tabs: Back and Forward move focus with the selected tab (real app)', () => {
  const cases = [
    { name: 'Profile', start: '/profile/personal', first: /^Personal Info$/, second: /^Listings$/, nextUrl: /\/profile\/listings$/, backUrl: /\/profile\/personal$/ },
    { name: 'My Stall', start: '/me/stall/active', first: /^Active/, second: /^Sold/, nextUrl: /\/me\/stall\/sold$/, backUrl: /\/me\/stall\/active$/ },
    { name: 'Offers', start: '/offers/incoming', first: /^Incoming/, second: /^Outgoing/, nextUrl: /\/offers\/outgoing$/, backUrl: /\/offers\/incoming$/ },
  ];
  for (const c of cases) {
    test(`${c.name}: Back after an arrow-key tab switch focuses the tab Back selected`, async ({ page }) => {
      await consent(page);
      await devLogin(page);
      await page.goto(`${base}${c.start}`);
      const first = page.getByRole('tab', { name: c.first });
      const second = page.getByRole('tab', { name: c.second });
      await expect(first).toHaveAttribute('aria-selected', 'true');
      await first.focus();
      await page.keyboard.press('ArrowRight');
      await expect(page).toHaveURL(c.nextUrl);
      await expect(second).toBeFocused();
      await page.goBack();
      await expect(page).toHaveURL(c.backUrl);
      await expect(first).toHaveAttribute('aria-selected', 'true');
      await expect(first).toBeFocused();
      await page.goForward();
      await expect(page).toHaveURL(c.nextUrl);
      await expect(second).toHaveAttribute('aria-selected', 'true');
      await expect(second).toBeFocused();
    });
  }
});

test.describe('aria-disabled buttons look disabled (real app)', () => {
  test('stall Contact Send looks disabled while the message is empty', async ({ page }) => {
    await consent(page);
    await devLogin(page);
    await page.goto(`${base}/stall/2`);
    await page.getByRole('button', { name: /Contact/ }).click();
    const send = page.getByRole('button', { name: 'Send', exact: true });
    await expect(send).toHaveAttribute('aria-disabled', 'true');
    await expect(send).toHaveCSS('color', 'rgba(255, 255, 255, 0.38)');
    await expect(send).toHaveCSS('background-color', 'rgba(255, 255, 255, 0.12)');
    await expect(send).toHaveCSS('cursor', 'default');
    await page.getByRole('textbox', { name: /message/i }).fill('Is this still available?');
    await expect(send).not.toHaveAttribute('aria-disabled', 'true');
    await expect(send).toHaveCSS('background-color', 'rgb(35, 123, 255)');
    await expect(send).toHaveCSS('cursor', 'pointer');
  });

  test('Profile Support reply Send looks disabled while the reply is empty', async ({ page }) => {
    await consent(page);
    await devLogin(page, 4);
    await page.goto(`${base}/profile/support`);
    await page.getByRole('button', { name: /New Ticket/ }).click();
    await page.getByRole('textbox', { name: 'Ticket subject' }).fill('Pass121 reply look');
    await page.getByRole('textbox', { name: 'Ticket message body' }).fill('Checking how the reply Send button looks when empty.');
    await page.getByRole('button', { name: 'Submit Ticket' }).click();
    await page.getByRole('button', { name: /^Open ticket #\d+ — Pass121 reply look/ }).first().click();
    const reply = page.getByRole('textbox', { name: 'Reply to support' });
    await expect(reply).toBeVisible();
    const send = page.getByRole('button', { name: 'Send', exact: true });
    await expect(send).toHaveAttribute('aria-disabled', 'true');
    await expect(send).toHaveCSS('color', 'rgba(255, 255, 255, 0.38)');
    await expect(send).toHaveCSS('cursor', 'default');
    await reply.fill('More detail');
    await expect(send).not.toHaveAttribute('aria-disabled', 'true');
    await expect(send).toHaveCSS('cursor', 'pointer');
  });
});

// Every form control in the dialog sits inside the panel's 24px inset, as on Report listing.
async function expectInset(dialog) {
  await expect(dialog).toBeVisible();
  const gaps = await dialog.evaluate((dl) => {
    const pb = dl.getBoundingClientRect();
    return [...dl.querySelectorAll('select, textarea, input, button')]
      .filter((e) => e.getBoundingClientRect().width > 0)
      .map((e) => { const b = e.getBoundingClientRect(); return Math.min(b.left - pb.left, pb.right - b.right); });
  });
  expect(gaps.length).toBeGreaterThan(1);
  for (const g of gaps) expect(g).toBeGreaterThanOrEqual(16);
}

test.describe('Report and Mark sent drawers keep their fields inside the panel', () => {
  for (const vp of [{ width: 1440, height: 900 }, { width: 390, height: 844 }]) {
    test(`stall Report seller at ${vp.width}px (real app)`, async ({ page }) => {
      await page.setViewportSize(vp);
      await consent(page);
      await devLogin(page);
      await page.goto(`${base}/stall/3`);
      await page.getByRole('button', { name: /^Report/ }).first().click();
      await expectInset(page.getByRole('dialog', { name: 'Report seller' }));
    });
  }

  const trade = { id: 501, state: 'PENDING_SELLER_SEND', sellerUserId: ME.id, buyerUserId: 7, counterpartyName: 'dave',
    itemName: 'Police Hat', price: 4.23, listingId: 76, createdAt: Date.now() - 3600e3, updatedAt: Date.now() };

  test('Profile > Trades Mark sent (stubbed)', async ({ page }) => {
    await consent(page);
    await signedIn(page);
    await page.route('**/api/trades', (r) => r.fulfill(json([trade])));
    await page.goto('/profile/trades');
    await page.getByRole('button', { name: /^Mark Sent for/ }).click();
    await expectInset(page.getByRole('dialog', { name: 'Mark trade as sent' }));
  });

  test('Profile > Trades Report counterparty (stubbed)', async ({ page }) => {
    await consent(page);
    await signedIn(page);
    await page.route('**/api/trades', (r) => r.fulfill(json([trade])));
    await page.goto('/profile/trades');
    await page.getByRole('button', { name: /^Report dave on/ }).click();
    await expectInset(page.getByRole('dialog', { name: 'Report counterparty' }));
  });

  test('Admin Catalogue Edit item (stubbed)', async ({ page }) => {
    await consent(page);
    await asAdmin(page);
    await page.route('**/api/auth/steam/me', (r) => r.fulfill(json({ ...ME, role: 'ADMIN' })));
    await page.route('**/api/items?q=**', (r) => r.fulfill(json([{ id: 5, name: 'WW1 Helmet', category: 'Hats', rarity: 'Limited', steamPrice: 50 }])));
    await page.goto('/admin');
    await page.getByRole('tab', { name: /Catalogue/ }).click();
    await page.getByRole('textbox', { name: 'Search items by name' }).fill('WW');
    await page.getByRole('button', { name: /Edit/ }).first().click();
    await expectInset(page.getByRole('dialog', { name: /Edit WW1 Helmet/ }));
  });
});
