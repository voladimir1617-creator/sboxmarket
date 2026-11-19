// @ts-check
const { test, expect } = require('@playwright/test');

// Defect pass 144.

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


// Defect pass 144. Real-app tests take the server from JAR / E2E_BASE_URL
// and stub every write: a real-jar test must never create or change server data.

const base = process.env.JAR || process.env.E2E_BASE_URL || 'http://localhost:8082';

async function noWrites(page) {
  await page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
  await page.route('**/api/**', (r) => (['POST', 'PUT', 'DELETE', 'PATCH'].includes(r.request().method()) ? r.fulfill(json({})) : r.fallback()));
}

async function devLogin(page, userId = 3) {
  await page.request.get(`${base}/api/auth/steam/dev-login?userId=${userId}&next=/`);
}

test.describe('Personal Info editors (real app)', () => {
  test('Escape on the trade URL Save or Cancel button closes the editor and returns focus to Add', async ({ page }) => {
    await noWrites(page);
    await devLogin(page);
    await page.goto(`${base}/profile/personal`);
    // Wait for the profile read, so the row's button keeps its name.
    await expect(page.getByText('Account Created')).toBeVisible({ timeout: 20_000 });
    const add = page.getByRole('button', { name: /^(Add|Edit) Steam trade URL$/ });
    await add.click({ timeout: 20_000 });
    const box = page.getByRole('textbox', { name: 'Steam trade URL' });
    await expect(box).toBeFocused();
    await page.getByRole('button', { name: 'Save', exact: true }).focus();
    await page.keyboard.press('Escape');
    await expect(box).toHaveCount(0);
    await expect(add).toBeFocused();
    await add.click();
    await page.getByRole('button', { name: 'Cancel', exact: true }).focus();
    await page.keyboard.press('Escape');
    await expect(box).toHaveCount(0);
    await expect(add).toBeFocused();
  });

  test('Escape on the email Save or Cancel button closes the editor and returns focus to Edit', async ({ page }) => {
    await noWrites(page);
    await devLogin(page);
    await page.goto(`${base}/profile/personal`);
    const edit = page.getByRole('button', { name: 'Edit email address' });
    await edit.click({ timeout: 20_000 });
    const box = page.getByRole('textbox', { name: 'Email address' });
    await expect(box).toBeFocused();
    await page.getByRole('button', { name: 'Save', exact: true }).focus();
    await page.keyboard.press('Escape');
    await expect(box).toHaveCount(0);
    await expect(edit).toBeFocused();
    await edit.click();
    await page.getByRole('button', { name: 'Cancel email edit' }).focus();
    await page.keyboard.press('Escape');
    await expect(box).toHaveCount(0);
    await expect(edit).toBeFocused();
  });
});

const consent = (page) => page.addInitScript(() => { try { localStorage.setItem('sb_cookie_consent', 'all'); } catch (_) {} });
const now = Date.now();

test.describe('Personal Info 2FA drawers (stubbed)', () => {
  async function twofa(page) {
    await consent(page);
    await signedIn(page);
    await page.route('**/api/profile/me', (r) => r.fulfill(json({ user: { ...ME, email: 'c@x.io', emailVerified: true }, twoFactorEnabled: true })));
    await page.route('**/api/profile/2fa/recovery-status', (r) => r.fulfill(json({ remainingCodes: 8 })));
    await page.goto('/profile/personal');
  }
  test('Escape on the Disable 2FA drawer Cancel button closes the drawer', async ({ page }) => {
    await twofa(page);
    await page.getByRole('button', { name: 'Disable', exact: true }).click({ timeout: 20_000 });
    const box = page.getByLabel('6-digit authenticator code or backup code');
    await expect(box).toBeFocused();
    await page.getByRole('button', { name: 'Cancel', exact: true }).focus();
    await page.keyboard.press('Escape');
    await expect(box).toHaveCount(0);
  });
  test('Escape on the Regenerate codes drawer Cancel button closes the drawer', async ({ page }) => {
    await twofa(page);
    await page.getByRole('button', { name: 'Regenerate codes' }).click({ timeout: 20_000 });
    const box = page.getByLabel('6-digit authenticator code', { exact: true });
    await expect(box).toBeFocused();
    await page.getByRole('button', { name: 'Cancel', exact: true }).focus();
    await page.keyboard.press('Escape');
    await expect(box).toHaveCount(0);
  });
});

test.describe('My Stall return-date picker (stubbed)', () => {
  test('Escape on the picker Cancel button closes it and returns focus to Schedule return', async ({ page }) => {
    await consent(page);
    await signedIn(page);
    await page.route('**/api/listings/away', (r) => r.fulfill(json({ hidden: true, until: null })));
    await page.goto('/me/stall');
    const schedule = page.getByRole('button', { name: 'Schedule return' });
    await schedule.click({ timeout: 20_000 });
    const date = page.getByLabel('Stall return date');
    await expect(date).toBeFocused();
    await page.getByRole('button', { name: 'Cancel', exact: true }).focus();
    await page.keyboard.press('Escape');
    await expect(date).toHaveCount(0);
    await expect(schedule).toBeFocused();
  });
});

test.describe('Review editors: Escape from their buttons (stubbed)', () => {
  test('Profile Reviews Given: Escape on Cancel closes the edit and keeps the review', async ({ page }) => {
    await consent(page);
    await signedIn(page);
    await page.route('**/api/reviews/mine', (r) => r.fulfill(json([
      { id: 41, rating: 4, comment: 'okay trade', toUserId: 8, toUserName: 'hank', createdAt: now - 1000 },
    ])));
    await page.goto('/profile/reviews?view=given');
    await page.getByRole('button', { name: /^Edit your review of hank/ }).click({ timeout: 20_000 });
    const box = page.getByRole('textbox', { name: 'Edit your review comment' });
    await expect(box).toBeFocused();
    await page.getByRole('button', { name: 'Cancel', exact: true }).focus();
    await page.keyboard.press('Escape');
    await expect(box).toHaveCount(0);
    await expect(page.getByText('okay trade')).toBeVisible();
  });

  test('Profile Reviews Received: Escape on Cancel closes the reply box', async ({ page }) => {
    await consent(page);
    await signedIn(page);
    await page.route(`**/api/reviews/user/${ME.id}`, (r) => r.fulfill(json([
      { id: 31, rating: 5, comment: 'fast', fromDisplayName: 'gus', createdAt: now - 1000 },
    ])));
    await page.route(`**/api/reviews/user/${ME.id}/summary`, (r) => r.fulfill(json({ count: 1, average: 5 })));
    await page.goto('/profile/reviews');
    await page.getByRole('button', { name: /^Reply to gus/ }).click({ timeout: 20_000 });
    const box = page.getByRole('textbox', { name: 'Seller reply to review' });
    await expect(box).toBeFocused();
    await page.getByRole('button', { name: 'Cancel', exact: true }).focus();
    await page.keyboard.press('Escape');
    await expect(box).toHaveCount(0);
  });

  test('stall: Escape on the reply box Cancel button closes it and returns focus to Reply', async ({ page }) => {
    await consent(page);
    await signedIn(page);
    await page.route(`**/api/listings/stall/${ME.id}`, (r) => r.fulfill(json({ seller: { id: ME.id, displayName: ME.displayName, soldCount: 1 }, count: 0, listings: [] })));
    await page.route(`**/api/reviews/user/${ME.id}`, (r) => r.fulfill(json([
      { id: 12, rating: 4, comment: 'fine', fromDisplayName: 'eli', createdAt: now - 2000 },
    ])));
    await page.goto(`/stall/${ME.id}`);
    const reply = page.getByRole('button', { name: /^Reply to eli/ });
    await reply.click({ timeout: 20_000 });
    const box = page.getByRole('textbox', { name: 'Public reply to this review' });
    await expect(box).toBeFocused();
    await page.getByRole('button', { name: 'Cancel', exact: true }).focus();
    await page.keyboard.press('Escape');
    await expect(box).toHaveCount(0);
    await expect(reply).toBeFocused();
  });

  test('stall: Escape on a star of the Leave a review form closes the form', async ({ page }) => {
    await consent(page);
    await signedIn(page);
    await page.route('**/api/listings/stall/9', (r) => r.fulfill(json({ seller: { id: 9, displayName: 'dave', completedTrades: 3 }, listings: [], count: 0 })));
    await page.route('**/api/reviews/eligible/9', (r) => r.fulfill(json([{ tradeId: 5, itemName: 'Top Hat', price: 2, settledAt: now, reviewed: false }])));
    await page.route('**/api/reviews/user/9**', (r) => r.fulfill(json([])));
    await page.route('**/api/listings/stall/9/recent-sales**', (r) => r.fulfill(json([])));
    await page.goto('/stall/9');
    await page.getByRole('button', { name: 'Write review' }).click({ timeout: 20_000 });
    const box = page.getByRole('textbox', { name: 'Review comment (optional)' });
    await expect(box).toBeFocused();
    await page.getByRole('button', { name: '4 stars' }).focus();
    await page.keyboard.press('Escape');
    await expect(box).toHaveCount(0);
  });
});

test.describe('Stall bio editor (real app)', () => {
  test('Escape on the bio Cancel button closes the editor and returns focus to Add a bio', async ({ page }) => {
    await noWrites(page);
    await devLogin(page);
    await page.goto(`${base}/stall/3`);
    const add = page.getByRole('button', { name: /^\+ Add a bio$|^Edit bio/ });
    await add.click({ timeout: 20_000 });
    const box = page.getByRole('textbox', { name: 'Stall bio' });
    await expect(box).toBeFocused();
    await page.getByRole('button', { name: 'Cancel', exact: true }).focus();
    await page.keyboard.press('Escape');
    await expect(box).toHaveCount(0);
    await expect(add).toBeFocused();
  });
});

test.describe('Profile Listings hidden card name (real app)', () => {
  test('a hidden listing.s card link says it is hidden', async ({ page }) => {
    await noWrites(page);
    // The seed has no hidden listings, so mark the newest as hidden on the way back.
    await page.route(`${base}/api/listings/my-stall`, async (r) => {
      const res = await r.fetch();
      const rows = (await res.json()).slice(0, 2).map((l, i) => ({ ...l, hidden: i === 0 }));
      await r.fulfill({ ...json(rows), headers: { 'X-Total-Count': '2' } });
    });
    await devLogin(page);
    await page.goto(`${base}/profile/listings`);
    const links = page.locator('.listing-grid .grid-card-link');
    await expect(links).toHaveCount(2, { timeout: 20_000 });
    await expect(links.nth(0)).toHaveAccessibleName(/, hidden from the market,/);
    await expect(links.nth(1)).not.toHaveAccessibleName(/hidden/);
  });
});

test.describe('Personal Info trade URL help (real app)', () => {
  test('the trade URL box is described by the line that says where to find the URL', async ({ page }) => {
    await noWrites(page);
    await devLogin(page);
    await page.goto(`${base}/profile/personal`);
    await expect(page.getByText('Account Created')).toBeVisible({ timeout: 20_000 });
    await page.getByRole('button', { name: /^(Add|Edit) Steam trade URL$/ }).click();
    const box = page.getByRole('textbox', { name: 'Steam trade URL' });
    await expect(box).toHaveAccessibleDescription(/Get it at Steam . Trade offers . Who can send me offers/);
    // A refused URL adds the error and keeps the help.
    await box.fill('not a url');
    await box.press('Enter');
    await expect(box).toHaveAccessibleDescription(/look like a Steam trade URL.*Who can send me offers/);
  });
});

test.describe('API reference tables (real app)', () => {
  test('each table is named by the heading above it', async ({ page }) => {
    await page.goto(`${base}/api-reference.html`);
    for (const name of ['Key scopes', 'Market data', 'Buying', 'Buy orders', 'Selling', 'Trades, wallet and alerts', 'Errors']) {
      await expect(page.getByRole('table', { name, exact: true })).toBeVisible();
    }
    await expect(page.getByRole('table')).toHaveCount(7);
  });
});
