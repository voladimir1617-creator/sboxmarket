const puppeteer = require('puppeteer');
(async () => {
  const browser = await puppeteer.launch({ headless: 'new', args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 390, height: 852, isMobile: true, deviceScaleFactor: 2 });
  await page.setUserAgent('Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1');
  await page.goto('http://localhost:8082/changelog.html?_qa=1', { waitUntil: 'networkidle2' });
  const info = await page.evaluate(() => {
    const text = document.querySelector('.back-to-app-text');
    const arrow = document.querySelector('.back-to-app-arrow');
    const btn = text ? text.closest('a') : null;
    return {
      innerWidth: window.innerWidth,
      textDisplay: text ? getComputedStyle(text).display : null,
      arrowDisplay: arrow ? getComputedStyle(arrow).display : null,
      btnRect: btn ? btn.getBoundingClientRect() : null,
      docScrollW: document.documentElement.scrollWidth,
    };
  });
  console.log(JSON.stringify(info, null, 2));
  await page.screenshot({ path: '_qa_boss/cycle_10/m_test.png', fullPage: false });
  await browser.close();
})();
