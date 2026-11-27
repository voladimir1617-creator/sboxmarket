// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 166.

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


// Defect pass 166. Real-app tests take the server from JAR / E2E_BASE_URL and
// stub every write: a real-jar test must never create or change server data.

const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';

async function noWrites(page) {
  await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
  await page.route('**/api/**', (r) => (['POST', 'PUT', 'DELETE', 'PATCH'].includes(r.request().method()) ? r.fulfill(json({})) : r.fallback()));
}
async function devLogin(page, userId) {
  await page.request.get(`${base}/api/auth/steam/dev-login?userId=${userId}&next=/`);
}
// Ctrl+Enter as an IME sends it while a word is still being composed.
async function composingCtrlEnter(box) {
  await box.evaluate((el) => {
    el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', ctrlKey: true, isComposing: true, bubbles: true, cancelable: true }));
  });
}

test.describe('pass 166 · Help Center answer kept across Back (real app)', () => {
  test('Back from Contact Support reopens the answer that was open', async ({ page }) => {
    await noWrites(page);
    await page.goto(`${base}/help`);
    const q = page.getByRole('button', { name: /^How do I audit my own account activity/ });
    await q.scrollIntoViewIfNeeded();
    await q.click();
    await expect(q).toHaveAttribute('aria-expanded', 'true');
    await page.getByRole('link', { name: 'Contact Support' }).click();
    await expect(page).toHaveURL(/\/support$/);
    await page.goBack();
    await expect(page).toHaveURL(/\/help$/);
    await expect(q).toHaveAttribute('aria-expanded', 'true', { timeout: 20_000 });
    await expect(q).toBeInViewport();
    await expect(page.getByRole('button', { name: 'What is SkinBox?', exact: true })).toHaveAttribute('aria-expanded', 'false');
  });

  test('Back from the status page link in an answer reopens that answer', async ({ page }) => {
    await noWrites(page);
    await page.goto(`${base}/help`);
    const q = page.getByRole('button', { name: /^Is there a system status page\?/ });
    await q.scrollIntoViewIfNeeded();
    await q.click();
    const answer = page.getByRole('region', { name: /^Is there a system status page\?/ });
    await answer.getByRole('link', { name: '/status.html' }).click();
    await expect(page).toHaveURL(/\/status\.html$/);
    await page.goBack();
    await expect(page).toHaveURL(/\/help$/);
    await expect(q).toHaveAttribute('aria-expanded', 'true', { timeout: 20_000 });
    await expect(answer.getByRole('link', { name: '/status.html' })).toBeVisible();
  });
});

test.describe('pass 166 · Ctrl+Enter while an IME is composing does not send (real app)', () => {
  test('stall bio: the composing Ctrl+Enter keeps the editor open; the real one saves the whole bio', async ({ page }) => {
    await noWrites(page);
    const sent = [];
    await page.route('**/api/profile/stall-bio', (r) => { sent.push(r.request().postDataJSON()); return r.fulfill(json({ ok: true, stallBio: r.request().postDataJSON().bio })); });
    await devLogin(page, 2);
    await page.goto(`${base}/stall/2`);
    await page.getByRole('button', { name: /^(\+ Add a bio|✎ Edit)$/ }).click({ timeout: 20_000 });
    const box = page.getByRole('textbox', { name: 'Stall bio' });
    await box.fill('Fast trades 速い');
    await composingCtrlEnter(box);
    await expect(box).toBeVisible();
    await box.press('End');
    await box.pressSequentially(' daily');
    await box.press('Control+Enter');
    await expect.poll(() => sent.length).toBeGreaterThan(0);
    expect(sent[0].bio).toBe('Fast trades 速い daily');
  });

  test('stall Contact: the composing Ctrl+Enter does not file the ticket', async ({ page }) => {
    await noWrites(page);
    const sent = [];
    await page.route('**/api/support/tickets', (r) => {
      if (r.request().method() !== 'POST') return r.fallback();
      sent.push(r.request().postDataJSON());
      return r.fulfill(json({ id: 1, status: 'OPEN' }));
    });
    await devLogin(page, 2);
    await page.goto(`${base}/stall/3`);
    await page.getByRole('button', { name: /Contact/ }).first().click({ timeout: 20_000 });
    const box = page.getByRole('textbox', { name: 'Message to support about this seller' });
    await box.fill('Is the hat still available');
    await composingCtrlEnter(box);
    await expect(box).toBeVisible();
    await box.pressSequentially('?');
    await box.press('Control+Enter');
    await expect.poll(() => sent.length).toBeGreaterThan(0);
    expect(JSON.stringify(sent[0])).toContain('Is the hat still available?');
  });
});

test.describe('pass 166 · Help Center answer kept on reload (stubbed)', () => {
  test('a reload keeps the answer that was open', async ({ page }) => {
    await signedIn(page);
    expect(ME.id).toBe(3);
    await page.goto('/help');
    const q = page.getByRole('button', { name: /^How do I audit my own account activity/ });
    await q.scrollIntoViewIfNeeded();
    await q.click();
    await page.reload();
    await expect(q).toHaveAttribute('aria-expanded', 'true', { timeout: 20_000 });
    await expect(q).toBeInViewport();
  });
});
