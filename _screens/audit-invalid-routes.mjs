import { chromium } from 'file:///C:/Users/WW/AppData/Local/npm-cache/_npx/9833c18b2d85bc59/node_modules/playwright/index.mjs';

const ROUTES = [
  '/stall/99999999',
  '/stall/abc',
  '/loadout/99999999',
  '/item/99999999',
];

const results = {};

const browser = await chromium.launch();
for (const route of ROUTES) {
  const ctx = await browser.newContext();
  const page = await ctx.newPage();

  const consoleMsgs = [];
  const failedRequests = [];
  page.on('console', (m) => {
    consoleMsgs.push({ type: m.type(), text: m.text() });
  });
  page.on('pageerror', (e) => {
    consoleMsgs.push({ type: 'pageerror', text: String(e) });
  });
  page.on('response', (resp) => {
    if (resp.status() >= 400) {
      failedRequests.push({ url: resp.url(), status: resp.status() });
    }
  });

  await page.goto('http://localhost:8082' + route, { waitUntil: 'networkidle', timeout: 15000 });
  // small wait for any deferred fetches
  await page.waitForTimeout(800);

  const h1Texts = await page.$$eval('h1', (els) => els.map((e) => e.textContent.trim()));
  const h2Texts = await page.$$eval('h2', (els) => els.map((e) => e.textContent.trim()));
  const title = await page.title();
  const bodyChars = (await page.textContent('body')).length;

  results[route] = {
    title,
    h1: h1Texts,
    h2: h2Texts.slice(0, 5),
    bodyChars,
    consoleErrorsAndPageerrors: consoleMsgs.filter((m) => m.type === 'error' || m.type === 'pageerror'),
    consoleWarnings: consoleMsgs.filter((m) => m.type === 'warning').slice(0, 8),
    failedRequests: failedRequests.filter((r) => !r.url.endsWith('.png') && !r.url.endsWith('.ico')),
  };

  await ctx.close();
}
await browser.close();

console.log(JSON.stringify(results, null, 2));
