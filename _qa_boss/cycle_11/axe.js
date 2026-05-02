const puppeteer = require('puppeteer');
const { AxePuppeteer } = require('@axe-core/puppeteer');
const fs = require('fs');

const ROUTES = ['/', '/market', '/db', '/help', '/faq', '/cart', '/watchlist', '/wallet', '/sell', '/settings',
  '/profile/personal', '/item/1', '/stall/1', '/loadout/1', '/item/missing', '/stall/missing'];

(async () => {
  const browser = await puppeteer.launch({ headless: 'new', args: ['--no-sandbox'] });
  const out = {};
  let totalCritical = 0, totalSerious = 0;
  for (const r of ROUTES) {
    const page = await browser.newPage();
    await page.setViewport({ width: 1920, height: 1080 });
    try {
      await page.goto('http://localhost:8082' + r + '?_qa=1', { waitUntil: 'networkidle2', timeout: 12000 });
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
    }
    await page.close();
  }
  await browser.close();
  fs.writeFileSync('_qa_boss/cycle_11/axe.json', JSON.stringify(out, null, 2));
  console.log('---');
  console.log(`TOTAL critical: ${totalCritical} serious: ${totalSerious}`);
})();
