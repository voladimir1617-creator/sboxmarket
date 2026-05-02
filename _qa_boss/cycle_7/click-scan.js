// Click scan: visit each route, count buttons + check key clickables don't crash
const puppeteer = require('puppeteer');
const fs = require('fs');

const ROUTES = ['/', '/market', '/db', '/help', '/faq', '/cart', '/watchlist', '/wallet', '/sell', '/settings',
  '/profile/personal', '/item/1', '/stall/1', '/loadout/1', '/item/missing', '/stall/missing', '/loadout/missing',
  '/affiliate', '/buy-orders', '/me/stall', '/offers', '/notifications', '/changelog.html', '/status.html'];

(async () => {
  const browser = await puppeteer.launch({ headless: 'new', args: ['--no-sandbox'] });
  const findings = [];
  for (const r of ROUTES) {
    const page = await browser.newPage();
    await page.setViewport({ width: 1920, height: 1080 });
    const errors = [];
    page.on('pageerror', e => errors.push(`pageerror: ${e.message}`));
    page.on('console', m => { if (m.type() === 'error') errors.push(`console.error: ${m.text()}`); });
    try {
      await page.goto('http://localhost:8082' + r, { waitUntil: 'networkidle2', timeout: 12000 });
      const data = await page.evaluate(() => {
        const buttons = document.querySelectorAll('button, [role="button"]');
        const links = document.querySelectorAll('a[href]');
        const labeledBtns = Array.from(buttons).filter(b => (b.textContent && b.textContent.trim()) || b.getAttribute('aria-label') || b.getAttribute('title')).length;
        const unlabeledBtns = buttons.length - labeledBtns;
        const deadLinks = Array.from(links).filter(a => {
          const h = a.getAttribute('href');
          return h === '#' || h === 'javascript:void(0)' || h === '';
        }).length;
        const inlineOnclick = document.querySelectorAll('[onclick]').length;
        const focusableNoTabindex = document.querySelectorAll('div[role="button"]:not([tabindex])').length;
        return { buttons: buttons.length, links: links.length, unlabeledBtns, deadLinks, inlineOnclick, focusableNoTabindex };
      });
      findings.push({ route: r, ...data, errors: errors.length });
    } catch (e) {
      findings.push({ route: r, error: e.message });
    }
    await page.close();
  }
  await browser.close();
  fs.writeFileSync('_qa_boss/cycle_7/click-scan.json', JSON.stringify(findings, null, 2));
  console.log(JSON.stringify(findings.map(f => ({ route: f.route, btns: f.buttons, lbld: f.buttons-f.unlabeledBtns, ulbl: f.unlabeledBtns, lnks: f.links, dead: f.deadLinks, oncl: f.inlineOnclick, errs: f.errors })), null, 2));
})();
