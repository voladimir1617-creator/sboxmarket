// @ts-check
const { test, expect } = require('@playwright/test');

// Money boxes read "1,70" as $1.70 in a decimal-comma locale. They were
// <input type=number>, and a bid typed as "1,70" went out as $170. They are
// now text boxes read by the shared parseMoneyInput (utils.js): one dot or
// comma is the decimal point, and "1,700" (thousands or cents?) is refused
// with a message instead of guessed. Shown here on the Make Offer drawer.

const ME = {
  id: 1,
  steamId64: '76561202255232729',
  displayName: 'Comma Tester',
  signedIn: true,
  sessionEpoch: 0,
  tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc',
};

const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });

const item = { id: 4991, name: 'Comma Hat', lowestPrice: 15, rarity: 'Standard', category: 'Hats', isListed: true };

async function offerDrawer(page) {
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json(ME)));
  await page.route('**/api/items/4991', (r) => r.fulfill(json(item)));
  await page.route('**/api/listings/item/4991*', (r) => r.fulfill(json([
    { id: 4999, price: 15, listingType: 'BUY_NOW', status: 'ACTIVE', sellerUserId: 9, sellerName: 'Seller', item },
  ])));
  const posted = [];
  await page.route('**/api/offers', (r) => {
    if (r.request().method() !== 'POST') return r.fulfill(json([]));
    const body = r.request().postDataJSON();
    posted.push(body);
    return r.fulfill(json({ code: 'TEST_STOP', message: 'stopped by test' }, 400));
  });
  await page.goto('/item/4991');
  await page.getByTestId('listing-offer-btn').first().click({ timeout: 20_000 });
  const box = page.getByRole('textbox', { name: 'Offer amount in USD' });
  await expect(box).toHaveAttribute('inputmode', 'decimal');
  const send = async (text) => {
    await box.fill('');
    await box.pressSequentially(text);
    await page.locator('[data-offer-send]').click();
  };
  return { posted, send };
}

for (const locale of ['de-DE', 'fr-FR']) {
  test.describe(`money input in ${locale}`, () => {
    test.use({ locale });

    test('"1,70" and "1.70" both send 1.70, "12" sends 12', async ({ page }) => {
      const { posted, send } = await offerDrawer(page);
      await send('1,70');
      await expect.poll(() => posted.length).toBe(1);
      expect(posted[0].amount).toBe(1.7);
      await send('1.70');
      await expect.poll(() => posted.length).toBe(2);
      expect(posted[1].amount).toBe(1.7);
      await send('12');
      await expect.poll(() => posted.length).toBe(3);
      expect(posted[2].amount).toBe(12);
    });

    test('"1,700" is refused with a message and nothing is sent', async ({ page }) => {
      const { posted, send } = await offerDrawer(page);
      await send('1,700');
      await expect(page.getByRole('alert').filter({ hasText: /one dot or comma for decimals/ })).toBeVisible();
      await send('1.234,56');
      await expect(page.getByRole('alert').filter({ hasText: /one dot or comma for decimals/ })).toBeVisible();
      expect(posted).toHaveLength(0);
    });
  });
}
