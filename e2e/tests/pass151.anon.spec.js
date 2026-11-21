// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 151.

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


// Defect pass 151: Help steps and no-match heading levels, the API
// reference back link name, and the support ticket form/list.

const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';

async function consent(page) {
  await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
}

// Every write a real-jar test could reach is refused here, so nothing on the
// server changes.
async function noWrites(page) {
  await page.route('**/api/**', (r) => (r.request().method() === 'GET' ? r.fallback() : r.fulfill(json({ error: 'blocked in test' }, 400))));
}

test.describe('pass151k · Help Center headings (real jar)', () => {
  test('Help: each walkthrough step title is a level-3 heading under its section', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await page.goto(`${base}/help`);
    await expect(page.getByRole('heading', { level: 2, name: /Getting Started/ })).toBeVisible({ timeout: 20_000 });
    // "Sign in with Steam" opens both walkthroughs.
    await expect(page.getByRole('heading', { level: 3, name: 'Sign in with Steam', exact: true })).toHaveCount(2);
    await expect(page.getByRole('heading', { level: 3, name: 'Top up your wallet', exact: true })).toBeVisible();
    const last = page.getByRole('heading', { level: 3, name: 'Wait for a sale', exact: true });
    await last.scrollIntoViewIfNeeded();
    await expect(last).toBeInViewport();
  });

  test('Help: the no-match line sits under the FAQ section as a level-3 heading', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await page.goto(`${base}/help?q=zzqx`);
    const none = page.getByRole('heading', { name: /No FAQs match .zzqx./ });
    await expect(none).toBeVisible({ timeout: 20_000 });
    await expect(none).toHaveAttribute('aria-level', '3');
    await expect(page.getByRole('heading', { level: 2, name: /Frequently Asked Questions/ })).toBeVisible();
  });

  test('API reference: the back link is named without its arrow glyph', async ({ page }) => {
    await consent(page);
    await page.goto(`${base}/api-reference.html`);
    const back = page.getByRole('main').getByRole('link', { name: 'Back to SkinBox', exact: true });
    await expect(back).toBeVisible({ timeout: 20_000 });
    await expect(back).toHaveAttribute('href', '/');
  });
});

test.describe('pass151k · Support ticket form and list (real jar)', () => {
  test('Support: Subject and Message are announced as required', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await page.request.get(`${base}/api/auth/steam/dev-login?userId=2&next=/`);
    // User 2 has no tickets on the seeded jar; keep it that way for the read too.
    await page.route('**/api/support/tickets', (r) => (r.request().method() === 'GET' ? r.fulfill(json([])) : r.fallback()));
    await page.goto(`${base}/support`);
    await page.getByRole('button', { name: /New Ticket/ }).first().click({ timeout: 20_000 });
    const subject = page.getByRole('textbox', { name: 'Ticket subject' });
    const body = page.getByRole('textbox', { name: 'Ticket message body' });
    await expect(subject).toBeFocused();
    await expect(subject).toHaveAttribute('aria-required', 'true');
    await expect(body).toHaveAttribute('aria-required', 'true');
    await expect(page.getByRole('combobox', { name: 'Ticket category' })).not.toHaveAttribute('aria-required', 'true');
  });

  test('Support: an empty status filter says so as a heading', async ({ page }) => {
    await consent(page);
    await noWrites(page);
    await page.request.get(`${base}/api/auth/steam/dev-login?userId=2&next=/`);
    const t = { id: 41, subject: 'Old payout question', category: 'PAYMENT', status: 'RESOLVED', createdAt: Date.now() - 86_400_000, updatedAt: Date.now() - 3_600_000 };
    await page.route('**/api/support/tickets', (r) => (r.request().method() === 'GET' ? r.fulfill(json([t])) : r.fallback()));
    await page.goto(`${base}/support`);
    const group = page.getByRole('group', { name: 'Ticket status' });
    await expect(group.getByRole('button', { name: /^Open · 0$/ })).toHaveAttribute('aria-pressed', 'true', { timeout: 20_000 });
    await expect(page.getByRole('heading', { name: /No tickets in this filter/ })).toBeVisible();
    await group.getByRole('button', { name: /^Resolved · 1$/ }).click();
    await expect(page.getByRole('heading', { name: /No tickets in this filter/ })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /Open ticket #41/ })).toBeVisible();
  });
});

test.describe('pass151k · Support form, stubbed', () => {
  test('Support: blank Submit marks the required Subject and keeps the draft write stubbed', async ({ page }) => {
    await consent(page);
    await signedIn(page);
    let posted = 0;
    await page.route('**/api/support/tickets', (r) => {
      if (r.request().method() === 'POST') { posted++; return r.fulfill(json({ error: 'Subject is required' }, 400)); }
      return r.fulfill(json([]));
    });
    await page.goto('/support');
    await expect(page.getByRole('button', { name: new RegExp('User menu') })).toBeVisible({ timeout: 20_000 });
    await page.getByRole('button', { name: /New Ticket/ }).first().click();
    const subject = page.getByRole('textbox', { name: 'Ticket subject' });
    await page.getByRole('textbox', { name: 'Ticket message body' }).fill(`Hello from ${ME.displayName}`);
    await page.getByRole('button', { name: 'Submit Ticket' }).click();
    await expect(subject).toBeFocused();
    await expect(subject).toHaveAttribute('aria-invalid', 'true');
    await expect(subject).toHaveAttribute('aria-required', 'true');
    expect(posted).toBe(0);
  });

  test('Help (phone): walkthrough steps are headings at 390px', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await consent(page);
    await signedIn(page);
    await page.goto('/help');
    const h = page.getByRole('heading', { level: 3, name: 'Send the item to our bot', exact: true });
    await h.scrollIntoViewIfNeeded();
    await expect(h).toBeInViewport({ timeout: 20_000 });
  });
});
