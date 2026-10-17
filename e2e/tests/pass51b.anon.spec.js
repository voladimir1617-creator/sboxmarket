// @ts-check
const { test, expect } = require('@playwright/test');

const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });

// Developer API docs copy: each step names the tab that actually has the control.

test('the account-audit answer sends "Revoke all active keys" to the Developers tab', async ({ page }) => {
  await page.goto('/help');
  const q = page.getByRole('button', { name: /How do I audit my own account activity\?/ });
  await expect(q).toBeVisible({ timeout: 20_000 });
  await q.click();
  const body = page.locator('body');
  await expect(body).toContainText("click 'Revoke all active keys' in Profile → Developers");
  await expect(body).not.toContainText("'Revoke all active keys' on the same tab");
});

test('the API answer says the mint alert goes to a verified email', async ({ page }) => {
  await page.goto('/help');
  const q = page.getByRole('button', { name: /How do I use the API from a bot or browser extension\?/ });
  await expect(q).toBeVisible({ timeout: 20_000 });
  await q.click();
  await expect(page.locator('body')).toContainText('security-alert email to the verified email on file');
});
