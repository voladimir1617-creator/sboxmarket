const puppeteer = require('puppeteer');

const SHOTS = [
  ['/watchlist',          'F1-watchlist-empty'],
  ['/item/1',             'F2-item-empty-listings'],
  ['/stall/1',            'F3-stall-no-reviews'],
  ['/',                   'F4-home-after'],
  ['/market',             'F5-market-after'],
  ['/db',                 'F6-db-after'],
  ['/changelog.html',     'F7-changelog-after'],
];

(async () => {
  const browser = await puppeteer.launch({ headless: 'new', args: ['--no-sandbox'] });
  for (const [r, slug] of SHOTS) {
    const page = await browser.newPage();
    await page.setViewport({ width: 1440, height: 900, deviceScaleFactor: 2 });
    await page.goto('http://localhost:8082' + r + '?_qa=1', { waitUntil: 'networkidle2', timeout: 20000 });
    await new Promise(r => setTimeout(r, 600));
    if (r === '/stall/1') {
      // Scroll to reviews section
      await page.evaluate(() => {
        const el = document.querySelector('[id*="review"], h2, h3');
        if (el) el.scrollIntoView({ block: 'center' });
      });
      await new Promise(r => setTimeout(r, 400));
    }
    await page.screenshot({ path: `_qa_boss/cycle_11/${slug}.png`, fullPage: false });
    await page.close();
    process.stdout.write('.');
  }
  console.log('');
  await browser.close();
})();
