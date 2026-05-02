const puppeteer = require('puppeteer');
const fs = require('fs');

const ROUTES = ['/', '/market', '/db', '/item/1', '/stall/1', '/loadout/1'];

(async () => {
  const browser = await puppeteer.launch({ headless: 'new', args: ['--no-sandbox'] });
  const out = {};
  for (const r of ROUTES) {
    const page = await browser.newPage();
    await page.setViewport({ width: 1920, height: 1080 });
    let bytes = 0;
    page.on('response', async resp => {
      try { const buf = await resp.buffer(); bytes += buf.length; } catch (e) {}
    });
    try {
      await page.goto('http://localhost:8082' + r + '?_qa=1', { waitUntil: 'networkidle2', timeout: 15000 });
      const m = await page.evaluate(() => ({
        domNodes: document.querySelectorAll('*').length,
        paint: performance.getEntriesByType('paint').reduce((a,p) => ({ ...a, [p.name]: Math.round(p.startTime) }), {}),
        navTiming: performance.getEntriesByType('navigation')[0] ? {
          domLoaded: Math.round(performance.getEntriesByType('navigation')[0].domContentLoadedEventEnd),
          loadEnd: Math.round(performance.getEntriesByType('navigation')[0].loadEventEnd),
          tti: Math.round(performance.getEntriesByType('navigation')[0].domInteractive),
        } : null,
      }));
      out[r] = { ...m, totalBytes: bytes };
      console.log(`${r.padEnd(28)} dom=${m.domNodes}  fcp=${m.paint['first-contentful-paint']||'?'}ms  load=${m.navTiming?.loadEnd}ms  bytes=${(bytes/1024).toFixed(0)}KB`);
    } catch (e) {
      out[r] = { error: e.message };
    }
    await page.close();
  }
  await browser.close();
  fs.writeFileSync('_qa_boss/cycle_11/perf.json', JSON.stringify(out, null, 2));
})();
