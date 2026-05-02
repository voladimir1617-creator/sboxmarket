// Cycle 12 — first-paint skeleton check. Boss prompt: "On slow 3G the
// first paint should NOT be a blank dark screen." Throttle the network
// to "Slow 3G", load each route, and screenshot the first paint @ 50ms,
// 200ms, 1000ms.
const puppeteer = require('puppeteer');

const ROUTES = [
  ['/', 'home'],
  ['/market', 'market'],
  ['/db', 'database'],
  ['/item/1', 'item-1'],
  ['/stall/1', 'stall-1'],
  ['/watchlist', 'watchlist'],
  ['/wallet', 'wallet']
];

(async () => {
  const browser = await puppeteer.launch({ headless: 'new', args: ['--no-sandbox'] });

  for (const [route, slug] of ROUTES) {
    const page = await browser.newPage();
    await page.setViewport({ width: 1440, height: 900, deviceScaleFactor: 1 });

    // Emulate Slow 3G (Lighthouse profile)
    const client = await page.createCDPSession();
    await client.send('Network.enable');
    await client.send('Network.emulateNetworkConditions', {
      offline: false,
      latency: 500,
      downloadThroughput: 50 * 1024,    // 50KB/s
      uploadThroughput: 50 * 1024
    });

    const start = Date.now();
    try {
      // Don't await full load — race screenshots against the load
      const navP = page.goto('http://localhost:8082' + route + '?_qa=1', { waitUntil: 'load', timeout: 30000 }).catch(() => {});
      await new Promise(r => setTimeout(r, 200));
      await page.screenshot({ path: `_qa_boss/cycle_12/skel_${slug}_200ms.png` });
      await new Promise(r => setTimeout(r, 800));
      await page.screenshot({ path: `_qa_boss/cycle_12/skel_${slug}_1000ms.png` });
      await navP;
      console.log(`  ${route}: captured at 200ms + 1000ms (full load ${Date.now() - start}ms)`);
    } catch (e) {
      console.log(`  ${route}: ${e.message}`);
    }
    await page.close();
  }
  await browser.close();
})();
