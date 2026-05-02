const puppeteer = require('puppeteer');
const { AxePuppeteer } = require('@axe-core/puppeteer');
const fs = require('fs');

const ROUTES = ['/', '/market', '/db', '/help', '/faq', '/cart', '/watchlist', '/wallet', '/sell', '/settings',
  '/profile/personal', '/item/1', '/stall/1', '/loadout/1', '/item/missing', '/stall/missing'];

(async () => {
  const out = {};
  let totalCritical = 0, totalSerious = 0;
  // Boss QA cycle 12 — relaunch the browser per route. The single-browser
  // variant was bringing the SBox JVM to its knees around route 4
  // (ERR_EMPTY_RESPONSE on /help+); close-and-relaunch keeps memory and
  // socket pressure predictable. domcontentloaded + 600ms settle replaces
  // networkidle2 because some routes never go fully idle (sticky polling
  // for live presence / sync badge), causing 12s timeouts that left the
  // browser holding open connections.
  for (const r of ROUTES) {
    const browser = await puppeteer.launch({ headless: 'new', args: ['--no-sandbox'] });
    const page = await browser.newPage();
    await page.setViewport({ width: 1920, height: 1080 });
    try {
      await page.goto('http://localhost:8082' + r + '?_qa=1', { waitUntil: 'domcontentloaded', timeout: 20000 });
      await new Promise(res => setTimeout(res, 600));
      const results = await new AxePuppeteer(page)
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze();
      const violations = results.violations.map(v => ({
        id: v.id,
        impact: v.impact,
        help: v.help,
        nodes: v.nodes.length,
        firstNodeHTML: v.nodes[0]?.html?.slice(0, 200) || '',
      }));
      const critical = violations.filter(v => v.impact === 'critical').length;
      const serious = violations.filter(v => v.impact === 'serious').length;
      totalCritical += critical;
      totalSerious += serious;
      out[r] = { critical, serious, violations };
      console.log(`${r.padEnd(28)} crit=${critical} serious=${serious} all=${violations.length}`);
    } catch (e) {
      out[r] = { error: e.message };
      console.log(`${r.padEnd(28)} ERROR ${(e.message || '').slice(0, 60)}`);
    }
    try { await page.close(); } catch (_) {}
    try { await browser.close(); } catch (_) {}
  }
  fs.writeFileSync('_qa_boss/cycle_11/axe.json', JSON.stringify(out, null, 2));
  console.log('---');
  console.log(`TOTAL critical: ${totalCritical} serious: ${totalSerious}`);
})();
