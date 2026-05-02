// Cycle 14 — full mobile walk. Take screenshots of every route at 390x852
// to verify the cycle-11 axe sweep + cycle-12 mobile tap fixes hold up
// visually under iOS Safari emulation.
const puppeteer = require('puppeteer');
const fs = require('fs'); fs.mkdirSync('_qa_boss/cycle_14', { recursive: true });

const ROUTES = [
  ['/',                'home'],
  ['/market',          'market'],
  ['/db',              'database'],
  ['/help',            'help'],
  ['/cart',            'cart'],
  ['/watchlist',       'watchlist'],
  ['/wallet',          'wallet'],
  ['/sell',            'sell'],
  ['/settings',        'settings'],
  ['/item/1',          'item-1'],
  ['/stall/1',         'stall-1'],
  ['/loadout/1',       'loadout-1'],
  ['/buy-orders',      'buy-orders'],
  ['/affiliate',       'affiliate'],
  ['/support',         'support']
];

(async () => {
  const browser = await puppeteer.launch({ headless: 'new', args: ['--no-sandbox'] });
  for (const [r, slug] of ROUTES) {
    const page = await browser.newPage();
    await page.setViewport({ width: 390, height: 852, isMobile: true, deviceScaleFactor: 2 });
    await page.setUserAgent('Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148');
    try {
      await page.goto('http://localhost:8082' + r + '?_qa=1', { waitUntil: 'networkidle2', timeout: 20000 });
      await new Promise(r => setTimeout(r, 600));
      await page.screenshot({ path: `_qa_boss/cycle_14/m_${slug}.png` });
    } catch (e) {
      console.log(`  ${r} fail: ${e.message}`);
    }
    await page.close();
    process.stdout.write('.');
    await new Promise(r => setTimeout(r, 250));
  }
  console.log('');
  await browser.close();
})();
