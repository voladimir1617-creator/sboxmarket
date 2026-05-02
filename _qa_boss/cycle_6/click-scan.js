// Q1 (cycle 5/6) — pageerror + console.error + 4xx/5xx scan across the
// full route surface. Acceptance gate: TOTAL ERRORS == 0. Output dumped
// to _qa_boss/cycle_6/click-scan.json + summary printed to stdout.
const puppeteer = require('puppeteer');

(async () => {
  const browser = await puppeteer.launch({ headless: 'new', args: ['--no-sandbox'] });
  const routes = [
    '/', '/market', '/db', '/help', '/faq', '/cart', '/watchlist', '/wallet',
    '/sell', '/settings', '/profile/personal',
    '/item/1', '/stall/1', '/loadout/1',
    '/item/missing', '/stall/missing', '/loadout/missing'
  ];
  const findings = [];
  for (const r of routes) {
    const page = await browser.newPage();
    await page.setViewport({ width: 1920, height: 1080 });
    const errors = [];
    page.on('pageerror', e => errors.push({ route: r, type: 'pageerror', msg: e.message }));
    page.on('console', m => {
      if (m.type() === 'error') {
        const text = m.text();
        // /api/users/me 401 is expected for anon visitors and routinely
        // logged by the app — exclude it so the gate isn't dirty by design.
        if (text.includes('users/me') || text.includes('Failed to load resource')) return;
        errors.push({ route: r, type: 'console', msg: text.slice(0, 300) });
      }
    });
    page.on('response', resp => {
      const url = resp.url();
      if (resp.status() >= 400 && !url.includes('/api/users/me') && !url.includes('/favicon')) {
        errors.push({ route: r, type: 'http', status: resp.status(), url });
      }
    });
    try {
      await page.goto('http://localhost:8082' + r, { waitUntil: 'domcontentloaded', timeout: 20000 });
      // Give React a tick to mount + hydrate the route
      await new Promise(res => setTimeout(res, 1500));
      const buttons = await page.$$eval('button, [role="button"]', els =>
        els.map(el => ({
          text: (el.textContent || '').trim().slice(0, 40),
          label: el.getAttribute('aria-label') || '',
          disabled: el.disabled || false
        })));
      findings.push({ route: r, buttons: buttons.length, errors });
    } catch (e) {
      findings.push({ route: r, buttons: 0, errors: [{ route: r, type: 'nav', msg: e.message }] });
    }
    await page.close();
  }
  await browser.close();
  require('fs').writeFileSync(
    'C:\\Users\\WW\\Desktop\\sboxmarket\\_qa_boss\\cycle_6\\click-scan.json',
    JSON.stringify(findings, null, 2)
  );
  const total = findings.reduce((a, f) => a + f.errors.length, 0);
  console.log('routes scanned:', findings.length);
  console.log('total errors :', total);
  for (const f of findings) {
    if (f.errors.length) {
      console.log(' ', f.route, '→', f.errors.length, 'err');
      for (const e of f.errors.slice(0, 3)) {
        console.log('     [' + e.type + ']', (e.msg || e.url || '').slice(0, 200));
      }
    }
  }
})();
