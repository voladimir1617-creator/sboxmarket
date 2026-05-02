const puppeteer = require('puppeteer');
const fs = require('fs');

const ROUTES = ['/', '/market', '/db', '/help', '/faq', '/cart', '/watchlist', '/wallet', '/sell', '/settings',
  '/profile/personal', '/item/1', '/stall/1', '/loadout/1', '/changelog.html'];

(async () => {
  const browser = await puppeteer.launch({ headless: 'new', args: ['--no-sandbox'] });
  const out = {};
  for (const r of ROUTES) {
    const page = await browser.newPage();
    await page.setViewport({ width: 390, height: 852, isMobile: true, deviceScaleFactor: 2 });
    await page.setUserAgent('Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1');
    try {
      await page.goto('http://localhost:8082' + r + '?_qa=1', { waitUntil: 'networkidle2', timeout: 12000 });
      const data = await page.evaluate(() => {
        return {
          innerW: window.innerWidth,
          docScrollW: document.documentElement.scrollWidth,
          horizontalOverflow: document.documentElement.scrollWidth - window.innerWidth,
          buttonCount: document.querySelectorAll('button, [role="button"]').length,
          unlabeled: Array.from(document.querySelectorAll('button, [role="button"]')).filter(b => !((b.textContent && b.textContent.trim()) || b.getAttribute('aria-label') || b.getAttribute('title'))).length,
        };
      });
      const safe = r.replace(/\//g, '_').replace(/^_/, '').replace(/\?.*$/, '') || 'root';
      await page.screenshot({ path: `_qa_boss/cycle_10/mtrue_${safe}.png` });
      out[r] = data;
    } catch (e) {
      out[r] = { error: e.message };
    }
    await page.close();
  }
  await browser.close();
  fs.writeFileSync('_qa_boss/cycle_10/mtrue.json', JSON.stringify(out, null, 2));
  let total = 0, overflow = 0, unlabeled = 0;
  for (const r in out) {
    if (out[r].horizontalOverflow > 4) overflow++;
    if (out[r].unlabeled > 0) unlabeled++;
    total++;
  }
  console.log(`Routes: ${total}  H-overflow > 4: ${overflow}  Unlabeled buttons: ${unlabeled}`);
})();
