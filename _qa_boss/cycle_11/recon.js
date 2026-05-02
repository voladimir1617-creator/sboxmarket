// Cycle 11 recon: axe-core a11y + perf snapshots + desktop+mobile screenshots
const puppeteer = require('puppeteer');
const { AxePuppeteer } = require('@axe-core/puppeteer');
const fs = require('fs');
const path = require('path');

const BASE = process.env.BASE || 'http://localhost:8082';
const OUT = '_qa_boss/cycle_11';

const ROUTES = [
  ['/',                     'home'],
  ['/market',               'market'],
  ['/db',                   'database'],
  ['/help',                 'help'],
  ['/faq',                  'faq'],
  ['/cart',                 'cart'],
  ['/watchlist',            'watchlist'],
  ['/wallet',               'wallet'],
  ['/sell',                 'sell'],
  ['/settings',             'settings'],
  ['/profile/personal',     'profile'],
  ['/item/1',               'item-1'],
  ['/stall/1',              'stall-1'],
  ['/loadout/1',            'loadout-1'],
  ['/buy-orders',           'buyorders'],
  ['/affiliate',            'affiliate'],
  ['/support',              'support'],
  ['/changelog.html',       'changelog']
];

(async () => {
  const browser = await puppeteer.launch({ headless: 'new', args: ['--no-sandbox', '--disable-dev-shm-usage'] });
  const axeOut = {};
  const perfOut = {};

  for (const [route, slug] of ROUTES) {
    // Desktop pass
    const page = await browser.newPage();
    await page.setViewport({ width: 1440, height: 900, deviceScaleFactor: 2 });
    let bytesIn = 0;
    page.on('response', async resp => {
      try {
        const len = resp.headers()['content-length'];
        if (len) bytesIn += parseInt(len, 10) || 0;
      } catch {}
    });
    try {
      const t0 = Date.now();
      await page.goto(BASE + route + '?_qa=1', { waitUntil: 'networkidle2', timeout: 20000 });
      const loaded = Date.now() - t0;

      const perf = await page.evaluate(() => {
        const paints = performance.getEntriesByType('paint');
        const fcp = paints.find(p => p.name === 'first-contentful-paint')?.startTime || null;
        const fp  = paints.find(p => p.name === 'first-paint')?.startTime || null;
        const nav = performance.getEntriesByType('navigation')[0] || {};
        return {
          domNodes: document.getElementsByTagName('*').length,
          fcpMs: fcp,
          fpMs: fp,
          domContentLoadedMs: nav.domContentLoadedEventEnd || null,
          loadEventMs: nav.loadEventEnd || null,
          transferSize: nav.transferSize || null,
          encodedBodySize: nav.encodedBodySize || null,
          pageBytesAprox: nav.transferSize || null
        };
      });
      perf.bytesObserved = bytesIn;
      perf.wallClockMs = loaded;

      // Axe scan
      try {
        const axeRes = await new AxePuppeteer(page).analyze();
        axeOut[route] = {
          critical:  axeRes.violations.filter(v => v.impact === 'critical').length,
          serious:   axeRes.violations.filter(v => v.impact === 'serious').length,
          moderate:  axeRes.violations.filter(v => v.impact === 'moderate').length,
          minor:     axeRes.violations.filter(v => v.impact === 'minor').length,
          violations: axeRes.violations.filter(v => v.impact === 'critical' || v.impact === 'serious').map(v => ({
            id: v.id, impact: v.impact, help: v.help,
            nodes: v.nodes.slice(0, 3).map(n => ({ html: n.html.slice(0, 200), target: n.target }))
          }))
        };
      } catch (e) {
        axeOut[route] = { error: e.message };
      }

      perfOut[route] = perf;

      // After-shot desktop
      await page.screenshot({ path: path.join(OUT, `d_${slug}.png`), fullPage: false });
    } catch (e) {
      perfOut[route] = { error: e.message };
      axeOut[route] = { error: e.message };
    }
    await page.close();

    // Mobile pass — screenshot only (axe is route-level, run once per route)
    const mp = await browser.newPage();
    await mp.setViewport({ width: 390, height: 852, isMobile: true, deviceScaleFactor: 2 });
    await mp.setUserAgent('Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148 Safari/604.1');
    try {
      await mp.goto(BASE + route + '?_qa=1', { waitUntil: 'networkidle2', timeout: 20000 });
      await mp.screenshot({ path: path.join(OUT, `m_${slug}.png`), fullPage: false });
    } catch (e) {
      perfOut[route] = { ...(perfOut[route] || {}), mobile_error: e.message };
    }
    await mp.close();
    process.stdout.write('.');
  }
  console.log('');
  await browser.close();

  fs.writeFileSync(path.join(OUT, 'axe.json'), JSON.stringify(axeOut, null, 2));
  fs.writeFileSync(path.join(OUT, 'perf.json'), JSON.stringify(perfOut, null, 2));

  // Print rollup
  let critTotal = 0, serTotal = 0;
  console.log('A11Y violations (critical / serious) per route:');
  for (const r of Object.keys(axeOut)) {
    const a = axeOut[r];
    if (a.error) { console.log(`  ${r}: ERROR ${a.error}`); continue; }
    const tag = (a.critical || a.serious) ? '!! ' : '   ';
    if (a.critical || a.serious) console.log(`${tag}${r}: ${a.critical}c ${a.serious}s`);
    critTotal += a.critical || 0; serTotal += a.serious || 0;
  }
  console.log(`Total: ${critTotal} critical + ${serTotal} serious`);
  console.log('');
  console.log('Perf — DOM nodes / FCP / wallClock:');
  for (const r of Object.keys(perfOut)) {
    const p = perfOut[r];
    if (p.error) { console.log(`  ${r}: ERROR ${p.error}`); continue; }
    const flag = (p.domNodes > 1500 || (p.fcpMs && p.fcpMs > 2000)) ? '!!' : '  ';
    console.log(`${flag} ${r}: dom=${p.domNodes} fcp=${p.fcpMs ? p.fcpMs.toFixed(0) : 'na'}ms wall=${p.wallClockMs}ms`);
  }
})().catch(e => { console.error(e); process.exit(1); });
