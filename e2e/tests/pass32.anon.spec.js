// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 32: the fee calculator reads its box in the display currency,
// and opening/closing a support ticket keeps keyboard focus in the panel.

const ME = {
  id: 1,
  steamId64: '76561202255232729',
  displayName: 'Ticket Tester',
  signedIn: true,
  sessionEpoch: 0,
};

async function signedIn(page) {
  await page.route('**/api/**', (r) => r.fulfill({
    status: 200, contentType: 'application/json', body: '{}',
  }));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify(ME),
  }));
}

test.describe('defect pass 32', () => {
  test('the fee calculator takes the amount in the currency it is labelled in', async ({ page }) => {
    // Labelled "Sale Amount (€)" but read as dollars: a typed €100 came back
    // as "You receive €90.16". At a flat 2% the answer is €98.00.
    await page.addInitScript(() => { try { localStorage.setItem('sb_currency', 'EUR'); } catch (_) {} });
    await page.goto('/market');
    const input = page.locator('.fee-calc-input');
    await expect(input).toBeVisible({ timeout: 15_000 });
    await input.fill('100');
    // About €98 (the fee is rounded to the cent in USD, so a cent either way).
    await expect(page.locator('.fee-calc-line.total')).toContainText(/€(97\.99|98\.0[0-2])/);
  });

  test('opening a ticket moves focus to it, and going back returns focus to its row', async ({ page }) => {
    await signedIn(page);
    const ticket = { id: 77, subject: 'Trade stuck', category: 'TRADE', status: 'OPEN', updatedAt: Date.now(), createdAt: Date.now() };
    await page.route('**/api/support/tickets', (r) => r.fulfill({
      status: 200, contentType: 'application/json', body: JSON.stringify([ticket]),
    }));
    await page.route('**/api/support/tickets/77', (r) => r.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ ticket, messages: [{ id: 1, author: 'USER', body: 'Help', createdAt: Date.now() }] }),
    }));

    await page.goto('/support');
    const row = page.locator('tr[data-ticket-id="77"]');
    await expect(row).toBeVisible({ timeout: 20_000 });
    await row.focus();
    await page.keyboard.press('Enter');

    const heading = page.getByRole('heading', { name: /#77 · Trade stuck/ });
    await expect(heading).toBeFocused();

    await page.getByRole('button', { name: '← Tickets' }).click();
    await expect(page.locator('tr[data-ticket-id="77"]')).toBeFocused();
  });
});
