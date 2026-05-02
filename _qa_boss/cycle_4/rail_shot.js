const puppeteer = require('puppeteer');
(async () => {
  const browser = await puppeteer.launch({ headless: 'new', args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 1920, height: 1080 });
  await page.goto('http://localhost:8082/', { waitUntil: 'networkidle2', timeout: 25000 });
  await new Promise(r => setTimeout(r, 1200));
  const result = await page.evaluate(() => {
    const tabs = document.querySelector('.csfloat-home-rail-tabs');
    if (!tabs) return { found: false };
    tabs.scrollIntoView({ block: 'center' });
    const active = tabs.querySelector('.csfloat-home-rail-tab.active');
    const inactive = tabs.querySelector('.csfloat-home-rail-tab:not(.active)');
    const styles = (el) => {
      if (!el) return null;
      const s = getComputedStyle(el);
      return { bg: s.backgroundColor, color: s.color, border: s.borderColor };
    };
    return { found: true, active: styles(active), inactive: styles(inactive) };
  });
  console.log(JSON.stringify(result, null, 2));
  const tabs = await page.$('.csfloat-home-rail-tabs');
  if (tabs) await tabs.screenshot({ path: '_qa_boss/cycle_4/P1.1_tabs.png' });
  await browser.close();
})();
