// Print detailed contrast info for each color-contrast violation
const puppeteer = require('puppeteer');
const { AxePuppeteer } = require('@axe-core/puppeteer');

const ROUTES = ['/', '/market', '/db', '/help', '/cart', '/item/1'];

(async () => {
  const browser = await puppeteer.launch({ headless: 'new', args: ['--no-sandbox'] });
  for (const r of ROUTES) {
    const page = await browser.newPage();
    await page.setViewport({ width: 1440, height: 900 });
    await page.goto('http://localhost:8082' + r + '?_qa=1', { waitUntil: 'networkidle2', timeout: 20000 });
    const res = await new AxePuppeteer(page).withRules(['color-contrast']).analyze();
    console.log('=== ' + r + ' ===');
    for (const v of res.violations) {
      for (const n of v.nodes) {
        console.log('  ', n.target[0]);
        console.log('  ', (n.failureSummary || '').replace(/\n/g, ' | '));
      }
    }
    await page.close();
  }
  await browser.close();
})();
