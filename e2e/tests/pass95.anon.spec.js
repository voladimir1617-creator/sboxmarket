// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 95.

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

test.describe('defect pass 95 · a11y', () => {

  // Pass 95c: accessibility items 4, 6, 7, 8 and 1 (minus the wallet line) from
  // research/improve-loop/sboxmarket/a11y-2026-10-29-pass3.md. None changes the look.

  const TRADE = { id: 41, state: 'PENDING_SELLER_SEND', itemName: 'Red Beanie', itemId: 11, price: 5, buyerUserId: 3, sellerUserId: 9, counterpartyName: 'dave', createdAt: Date.now() - 3600e3, updatedAt: Date.now() - 600e3 };
  const ITEM = { id: 4901, name: 'Offer Hat', lowestPrice: 2, rarity: 'Standard', category: 'Hats', isListed: true };
  const LISTING = (i) => ({ id: 100 + i, price: 3 + i, listingType: 'BUY_NOW', status: 'ACTIVE', sellerUserId: 900 + i, sellerName: 'dave', listedAt: Date.now() - 3600e3, item: { id: 10 + i, name: 'Hat ' + i, rarity: 'Standard', category: 'Hats', lowestPrice: 3, isListed: true } });

  test.describe('a11y pass 95c', () => {

    test('(4) trade stepper marks the current step with aria-current=step', async ({ page }) => {
      await signedIn(page);
      await page.route('**/api/trades', (r) => r.fulfill(json([TRADE])));
      await page.route('**/api/profile/me', (r) => r.fulfill(json({ user: { ...ME }, accountStanding: { level: 'GOOD' } })));
      await page.goto('/profile/trades');
      const phases = page.locator('#trade-41 .trade-phase');
      await expect(phases).toHaveCount(6);
      const current = page.locator('#trade-41 [aria-current="step"]');
      await expect(current).toHaveCount(1);
      await expect(current).toHaveClass(/\bcurrent\b/);
      await expect(current).toContainText('Sends');
    });

    test('(6) admin Announce fields have names of their own', async ({ page }) => {
      await asAdmin(page);
      await page.route('**/api/admin/announcements', (r) => r.fulfill(json([])));
      await page.goto('/admin?tab=announce');
      await expect(page.getByRole('textbox', { name: 'Banner message', exact: true })).toBeVisible({ timeout: 20_000 });
      await expect(page.getByRole('spinbutton', { name: 'Auto-expire in hours', exact: true })).toBeVisible();
      await expect(page.getByRole('textbox', { name: 'Broadcast title', exact: true })).toBeVisible();
      await expect(page.getByRole('textbox', { name: 'Broadcast body', exact: true })).toBeVisible();
      await expect(page.getByRole('textbox', { name: 'Deep-link path', exact: true })).toBeVisible();
      // The placeholders the old name came from are still shown.
      await expect(page.getByPlaceholder(/Title \(required/)).toBeVisible();
    });

    test('(6) admin catalogue Edit drawer fields are named by their captions', async ({ page }) => {
      await asAdmin(page);
      await page.route(/\/api\/items\?q=/, (r) => r.fulfill(json([{ id: 11, name: 'Red Beanie', category: 'Hats', rarity: 'Standard', lowestPrice: 3, steamPrice: 2, imageUrl: '' }])));
      await page.goto('/admin?tab=catalogue');
      await page.getByPlaceholder('Search items by name…').fill('Red');
      await page.getByRole('button', { name: '✎ Edit' }).click();
      const dlg = page.getByRole('dialog', { name: /Edit Red Beanie/ });
      await expect(dlg.getByLabel('Steam price', { exact: true })).toBeVisible();
      await expect(dlg.getByLabel('Image URL (https)', { exact: true })).toBeVisible();
      await expect(dlg.getByLabel('Accent colour (#hex)', { exact: true })).toBeVisible();
    });

    test('(7) the staff access-denied card is a named modal dialog', async ({ page }) => {
      await signedIn(page);
      await page.goto('/admin');
      const dlg = page.getByRole('dialog', { name: 'Staff access only' });
      await expect(dlg).toBeVisible({ timeout: 20_000 });
      await expect(dlg).toHaveAttribute('aria-modal', 'true');
      await expect(dlg.getByRole('link', { name: 'Back to marketplace' })).toBeVisible();
    });

    test('(8) /market: a focused card link is not left under the sticky category bar', async ({ page }) => {
      await signedIn(page);
      await page.route(/\/api\/listings\?/, (r) => r.fulfill(json(Array.from({ length: 60 }, (_, i) => LISTING(i)))));
      await page.goto('/market');
      const link = page.locator('.grid-card-link').nth(20);
      await expect(link).toBeAttached({ timeout: 20_000 });
      // Park the link just under the bottom edge of the sticky bar, above the
      // old 80px scroll padding, then Tab onto it from the control before it.
      // The page scrolls smoothly, so wait for each scroll to come to rest.
      const settle = () => page.waitForFunction(() => new Promise((ok) => {
        let last = scrollY, still = 0;
        const tick = () => { if (scrollY === last) still++; else { still = 0; last = scrollY; } still >= 10 ? ok(true) : requestAnimationFrame(tick); };
        requestAnimationFrame(tick);
      }));
      await link.evaluate((el) => {
        const all = [...document.querySelectorAll('a[href],button,input,select,textarea,[tabindex]')].filter((e) => e.tabIndex >= 0 && e.offsetParent);
        all[all.indexOf(el) - 1].setAttribute('data-prev', '1');
      });
      await page.locator('[data-prev]').focus();
      await settle();
      await link.evaluate((el) => {
        const bar = document.querySelector('.csfloat-subnav').getBoundingClientRect().bottom;
        window.scrollTo({ top: el.getBoundingClientRect().top + scrollY - (bar - 6), behavior: 'instant' });
      });
      await page.keyboard.press('Tab');
      await expect(link).toBeFocused();
      await settle();
      const gap = await link.evaluate((el) => Math.round(el.getBoundingClientRect().top - document.querySelector('.csfloat-subnav').getBoundingClientRect().bottom));
      expect(gap, 'focused link top edge sits below the sticky category bar').toBeGreaterThanOrEqual(0);
    });

    test('(1) a refused Make Offer is announced as an alert', async ({ page }) => {
      await signedIn(page);
      await page.route('**/api/items/4901', (r) => r.fulfill(json(ITEM)));
      await page.route('**/api/listings/item/4901*', (r) => r.fulfill(json([
        { id: 4949, price: 2, listingType: 'BUY_NOW', status: 'ACTIVE', sellerUserId: 9, sellerName: 'Seller', item: ITEM },
      ])));
      await page.route('**/api/offers', (r) => r.request().method() === 'POST'
        ? r.fulfill(json({ error: 'OFFER_REFUSED', message: 'Seller is not taking offers right now' }, 400))
        : r.fulfill(json([])));
      await page.goto('/item/4901');
      await page.getByTestId('listing-offer-btn').first().click({ timeout: 20_000 });
      const input = page.getByRole('textbox', { name: 'Offer amount in USD' });
      await input.fill('1.50');
      await input.press('Enter');
      await expect(page.getByText('Seller is not taking offers right now')).toBeVisible();
      await expect(page.getByRole('alert').filter({ hasText: 'Seller is not taking offers right now' })).toBeVisible();
    });

    test('(1) PreSigninModal error line is announced as an alert', async ({ page }) => {
      // PreSigninModal is exported from app.js but no route renders it today,
      // so mount it straight from the module the page already loaded.
      await page.route('**/api/**', (r) => r.fulfill(json({})));
      await page.goto('/market');
      await page.waitForFunction(() => performance.getEntriesByType('resource').some((e) => /\/js\/app\.js/.test(e.name)));
      await page.evaluate(async () => {
        const url = performance.getEntriesByType('resource').map((e) => e.name).find((n) => /\/js\/app\.js/.test(n));
        const m = await import(url);
        const host = document.createElement('div');
        document.body.appendChild(host);
        ReactDOM.createRoot(host).render(React.createElement(m.PreSigninModal, { onClose() {}, onAccept() {} }));
      });
      const dlg = page.getByRole('dialog', { name: /Welcome to/ });
      await expect(dlg).toBeVisible();
      await dlg.getByRole('button', { name: /Continue to Steam/ }).click();
      await expect(dlg.getByText('Please enter a valid email address')).toBeVisible();
      await expect(dlg.getByRole('alert')).toHaveText('Please enter a valid email address');
    });
  });
});
