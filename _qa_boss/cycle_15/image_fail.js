// Cycle 15 — image-fail fallback test. Boss prompt: "every Steam-CDN image
// has a fallback skeleton if the load fails." Block all steamcommunity-a
// + steamstatic + akamaihd image URLs and capture how the marketplace
// renders.
const puppeteer = require('puppeteer');
const fs = require('fs'); fs.mkdirSync('_qa_boss/cycle_15', { recursive: true });

(async () => {
  const browser = await puppeteer.launch({ headless: 'new', args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 1440, height: 900, deviceScaleFactor: 2 });
  await page.setRequestInterception(true);
  page.on('request', req => {
    const url = req.url();
    if (url.includes('steamcommunity-a.akamaihd') || url.includes('steamstatic.com') || url.includes('akamaihd.net') || url.match(/\/images\/.*\.(png|jpg|jpeg|webp)/i)) {
      req.abort();
    } else {
      req.continue();
    }
  });
  await page.goto('http://localhost:8082/market?_qa=1', { waitUntil: 'networkidle2', timeout: 30000 });
  await new Promise(r => setTimeout(r, 1500));
  await page.screenshot({ path: '_qa_boss/cycle_15/market_no_images.png' });

  await page.goto('http://localhost:8082/?_qa=1', { waitUntil: 'networkidle2', timeout: 30000 });
  await new Promise(r => setTimeout(r, 1500));
  await page.screenshot({ path: '_qa_boss/cycle_15/home_no_images.png' });

  await page.goto('http://localhost:8082/item/1?_qa=1', { waitUntil: 'networkidle2', timeout: 30000 });
  await new Promise(r => setTimeout(r, 1500));
  await page.screenshot({ path: '_qa_boss/cycle_15/item_no_images.png' });

  await browser.close();
  console.log('done');
})();
