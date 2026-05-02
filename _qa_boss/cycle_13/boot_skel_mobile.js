const puppeteer = require('puppeteer');
(async () => {
  const browser = await puppeteer.launch({ headless: 'new', args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 390, height: 852, isMobile: true, deviceScaleFactor: 2 });
  await page.setUserAgent('Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148');
  const client = await page.createCDPSession();
  await client.send('Network.enable');
  await client.send('Network.emulateNetworkConditions', { offline: false, latency: 500, downloadThroughput: 50 * 1024, uploadThroughput: 50 * 1024 });
  const navP = page.goto('http://localhost:8082/?_qa=1', { waitUntil: 'load', timeout: 30000 }).catch(() => {});
  await new Promise(r => setTimeout(r, 1000));
  await page.screenshot({ path: '_qa_boss/cycle_13/boot_skel_mobile_1000ms.png' });
  await navP;
  await browser.close();
  console.log('done');
})();
