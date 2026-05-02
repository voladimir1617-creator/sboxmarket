const puppeteer = require('puppeteer');
const path = require('path');
const fs = require('fs');

const URLS = (process.argv[2] || 'https://skinbox.market/,https://skinbox.market/market,https://skinbox.market/item/3,https://skinbox.market/wallet').split(',');
const OUTDIR = process.argv[3] || path.join(__dirname, 'cycle_31');
const VIEWPORT = process.argv[4] === 'mobile' ? { w: 390, h: 852, isMobile: true, dsr: 2 } : { w: 1920, h: 1080, isMobile: false, dsr: 1 };

fs.mkdirSync(OUTDIR, { recursive: true });

(async () => {
  const browser = await puppeteer.launch({
    headless: 'new',
    executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
    args: ['--no-sandbox', '--disable-dev-shm-usage'],
  });
  const page = await browser.newPage();
  await page.setViewport({ width: VIEWPORT.w, height: VIEWPORT.h, isMobile: VIEWPORT.isMobile, deviceScaleFactor: VIEWPORT.dsr });

  for (const url of URLS) {
    const slug = url.replace(/https?:\/\/[^/]+/, '').replace(/[^a-z0-9]+/gi, '_').replace(/^_|_$/g, '') || 'home';
    const file = path.join(OUTDIR, `${VIEWPORT.isMobile ? 'mobile' : 'desktop'}_${slug}.png`);
    try {
      await page.goto(url + (url.includes('?') ? '&' : '?') + '_qa=1', { waitUntil: 'networkidle2', timeout: 30000 });
      await new Promise(r => setTimeout(r, 2000));
      await page.screenshot({ path: file, fullPage: false });
      console.log('OK', file);
    } catch (e) {
      console.error('FAIL', url, e.message);
    }
  }
  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
