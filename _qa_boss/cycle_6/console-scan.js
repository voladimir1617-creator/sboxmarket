// Quick console + network error scan via puppeteer
const puppeteer = require('puppeteer');
const fs = require('fs');

const ROUTES = [
  '/', '/market', '/db', '/help', '/faq',
  '/cart', '/watchlist', '/wallet', '/sell', '/settings',
  '/profile/personal', '/item/1', '/stall/1', '/loadout/1',
  '/item/missing', '/stall/missing', '/loadout/missing',
  '/affiliate', '/buy-orders', '/me/stall', '/offers', '/notifications',
  '/changelog.html', '/status.html'
];

(async () => {
  const browser = await puppeteer.launch({ headless: 'new', args: ['--no-sandbox'] });
  const all = [];
  for (const r of ROUTES) {
    const page = await browser.newPage();
    await page.setViewport({ width: 1920, height: 1080 });
    const errors = [];
    page.on('pageerror', e => errors.push({ type: 'pageerror', msg: e.message }));
    page.on('console', m => {
      const type = m.type();
      if (type === 'error') errors.push({ type: 'console.error', msg: m.text() });
      else if (type === 'warning') errors.push({ type: 'console.warn', msg: m.text() });
    });
    page.on('response', resp => {
      const status = resp.status();
      const url = resp.url();
      // ignore expected anon /api/users/me 401
      if (status >= 400 && !(url.includes('/api/users/me') && status === 401)) {
        errors.push({ type: 'http', status, url: url.replace('http://localhost:8082', '') });
      }
    });
    try {
      await page.goto('http://localhost:8082' + r, { waitUntil: 'networkidle2', timeout: 12000 });
    } catch (e) {
      errors.push({ type: 'goto', msg: e.message });
    }
    all.push({ route: r, errors });
    await page.close();
  }
  await browser.close();
  fs.writeFileSync('_qa_boss/cycle_6/console-network.json', JSON.stringify(all, null, 2));
  // Summary
  const total = all.reduce((a, x) => a + x.errors.length, 0);
  console.log('TOTAL_ERRORS=' + total);
  for (const r of all) {
    if (r.errors.length > 0) {
      console.log(`  ${r.route}: ${r.errors.length}`);
      for (const e of r.errors.slice(0, 5)) console.log(`    [${e.type}] ${e.status||''} ${e.url||e.msg||''}`.trim().slice(0, 200));
    }
  }
})();
