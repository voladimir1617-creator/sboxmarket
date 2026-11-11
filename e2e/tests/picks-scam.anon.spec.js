// @ts-check
const { test, expect } = require('@playwright/test');

// Scam-defense pass 4 (2026-11-08): the offer code alone can be copied by a
// scammer who holds the user's Steam Web API key, so the code now comes with
// our bot's own Steam account, and the advice says to check both.

const ME = { id: 3, displayName: 'carol', role: 'USER', signedIn: true, sessionEpoch: 0, tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc' };
const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });
const BOT_ID = '76561198000000042';
const NOTE = "Our bot's trade offer message ends with this code, and the offer comes from our bot's Steam account shown here. Decline any offer for this item that fails either check.";
const NOTE_NO_ACCOUNT = "Our bot's trade offer message ends with this code, and the offer comes from our bot's Steam account. Decline any offer for this item that fails either check.";

async function signedIn(page) {
  await page.addInitScript(() => { window.confirm = () => true; });
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json(ME)));
}

function tradeRow(extra) {
  const now = Date.now();
  return Object.assign({
    id: 990001, listingId: 990001, itemId: 12, itemName: 'Vintage Design Crop Top',
    buyerUserId: ME.id, sellerUserId: ME.id + 1000, price: 15.0, feeAmount: 0.3,
    state: 'PENDING_BUYER_CONFIRM', createdAt: now - 3600_000, updatedAt: now - 600_000,
    sentAt: now - 600_000, expiresAt: now + 7 * 86400_000, unreadCount: 0,
    deliveryCode: 'K7Q2', counterpartyName: 'GhostlyDeals'
  }, extra);
}

test.describe('bot account next to the offer code', () => {
  test('a buyer trade row links our bot\'s Steam profile, by name, in a new tab', async ({ page }) => {
    await signedIn(page);
    await page.route(/\/api\/trades(\?.*)?$/, (r) => r.fulfill(json([tradeRow({ botSteamId: BOT_ID, botName: 'SkinBox Bot 1' })])));
    await page.goto('/profile/trades');
    await expect(page.locator('.bot-code-value')).toHaveText('K7Q2');
    const link = page.getByTestId('bot-code-account');
    await expect(link).toHaveText('From SkinBox Bot 1 ↗');
    await expect(link).toHaveAttribute('href', `https://steamcommunity.com/profiles/${BOT_ID}`);
    await expect(link).toHaveAttribute('target', '_blank');
    await expect(link).toHaveAttribute('rel', /noopener/);
    await expect(page.getByTestId('bot-code-note')).toHaveText(NOTE);
  });

  test('with no bot name the link still names "our bot"', async ({ page }) => {
    await signedIn(page);
    await page.route(/\/api\/trades(\?.*)?$/, (r) => r.fulfill(json([tradeRow({ botSteamId: BOT_ID })])));
    await page.goto('/profile/trades');
    await expect(page.getByTestId('bot-code-account')).toHaveText('From our bot ↗');
  });

  test('when the bot account is unknown (bot off / demo) no link shows and the note does not point at one', async ({ page }) => {
    await signedIn(page);
    await page.route(/\/api\/trades(\?.*)?$/, (r) => r.fulfill(json([tradeRow({ botSteamId: 'nope' })])));
    await page.goto('/profile/trades');
    await expect(page.locator('.bot-code-value')).toHaveText('K7Q2');
    await expect(page.getByTestId('bot-code-account')).toHaveCount(0);
    await expect(page.getByTestId('bot-code-note')).toHaveText(NOTE_NO_ACCOUNT);
  });

  test('a held deposit in My Stall shows the bot account inside the offer code box', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings/my-stall/pending-escrow', (r) => r.fulfill(json([{
      listing: { id: 501, price: 9.5, status: 'PENDING_ESCROW', item: { id: 12, name: 'Wizard Hat' } },
      escrow: { custodyState: 'PENDING_DEPOSIT', offerSent: true, securityCode: 'Q1W2',
        message: 'Our bot sent you a deposit offer.', botSteamId: BOT_ID, botName: 'SkinBox Bot 1' }
    }])));
    await page.goto('/me/stall');
    const box = page.locator('.bot-code').filter({ hasText: 'Q1W2' });
    await expect(box).toBeVisible();
    await expect(box.getByTestId('bot-code-account')).toHaveAttribute('href', `https://steamcommunity.com/profiles/${BOT_ID}`);
    await expect(page.locator('.bot-offer-tip').first()).toContainText("comes from our bot's Steam account");
  });
});

test('the scam FAQ says to check the code AND our bot account, and to revoke unknown API keys', async ({ page }) => {
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.goto('/help');
  await page.getByRole('button', { name: /How do I avoid scams/ }).click();
  const answer = page.locator('.help-faq-a');
  await expect(answer).toContainText("AND it comes from our bot's Steam account");
  await expect(answer).toContainText('steamcommunity.com/dev/apikey');
  await expect(answer).toContainText('never asks for your Steam password');
});
