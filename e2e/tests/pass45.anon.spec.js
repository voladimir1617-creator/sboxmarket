// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 45.

const ME = {
  id: 1,
  steamId64: '76561202255232729',
  displayName: 'Pass Tester',
  signedIn: true,
  sessionEpoch: 0,
};

const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });

async function signedIn(page) {
  await page.route('**/api/**', (r) => r.fulfill(json({})));
  await page.route('**/api/auth/steam/me', (r) => r.fulfill(json(ME)));
}

test.describe('defect pass 45', () => {
  test('the currency picker is named on its button and keeps focus after a choice or Escape', async ({ page }) => {
    await page.route('**/api/**', (r) => r.fulfill(json({})));
    await page.route('**/api/auth/steam/me', (r) => r.fulfill(json({ signedIn: false })));
    await page.goto('/market');
    const chip = page.getByRole('button', { name: /^Currency selector: / });
    await expect(chip).toBeVisible({ timeout: 20_000 });
    await chip.focus();
    await page.keyboard.press('Enter');
    const panel = page.locator('.nav-picker-panel');
    await expect(panel).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(panel).toHaveCount(0);
    await expect(chip).toBeFocused();
    await page.keyboard.press('Enter');
    const usd = panel.getByRole('option', { name: /USD/ });
    await usd.focus();
    await page.keyboard.press('Enter');
    await expect(panel).toHaveCount(0);
    await expect(chip).toBeFocused();
  });

  test('arrow keys on the Offers tabs move focus and the address', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/offers/incoming*', (r) => r.fulfill(json([])));
    await page.route('**/api/offers/outgoing*', (r) => r.fulfill(json([])));
    await page.goto('/offers/incoming');
    const incoming = page.locator('[role="tablist"][aria-label="Offer direction"] [role="tab"]').first();
    await expect(incoming).toBeVisible({ timeout: 20_000 });
    await incoming.focus();
    await page.keyboard.press('ArrowRight');
    const outgoing = page.locator('[role="tablist"][aria-label="Offer direction"] [role="tab"]').nth(1);
    await expect(outgoing).toHaveAttribute('aria-selected', 'true');
    await expect(outgoing).toBeFocused();
    await expect(page).toHaveURL(/\/offers\/outgoing$/);
  });

  test('the Contact seller box only takes what the ticket can keep', async ({ page }) => {
    await signedIn(page);
    await page.route('**/api/listings/stall/77*', (r) => r.fulfill(json({
      seller: { id: 77, displayName: 'Asker' }, listings: [], blockedByViewer: false,
    })));
    await page.goto('/stall/77');
    const contact = page.locator('.stall-share-btn', { hasText: 'Contact' });
    await expect(contact).toBeVisible({ timeout: 20_000 });
    await contact.click();
    const box = page.getByLabel('Message to support about this seller');
    // "Seller stall: /stall/77\n\n" is 25 characters of the server's 2000.
    await expect(box).toHaveAttribute('maxlength', '1975');
    await expect(page.getByText('0/1975 · Ctrl+Enter to send')).toBeVisible();
  });
});
