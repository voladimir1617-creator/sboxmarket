const puppeteer = require('puppeteer');
const SHOTS = [['/market','C12-m-market'], ['/cart','C12-m-cart'], ['/db','C12-m-db'], ['/item/1','C12-m-item']];
(async () => {
  const browser = await puppeteer.launch({ headless: 'new', args: ['--no-sandbox'] });
  for (const [r, slug] of SHOTS) {
    const page = await browser.newPage();
    await page.setViewport({ width: 390, height: 852, isMobile: true, deviceScaleFactor: 2 });
    await page.setUserAgent('Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148');
    await page.goto('http://localhost:8082' + r + '?_qa=1', { waitUntil: 'networkidle2', timeout: 20000 });
    await new Promise(r => setTimeout(r, 600));
    await page.screenshot({ path: `_qa_boss/cycle_12/${slug}.png`, fullPage: false });
    await page.close();
  }
  await browser.close();
})();
