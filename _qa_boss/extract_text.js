const puppeteer = require('puppeteer');
const URL = process.argv[2];
(async () => {
  const browser = await puppeteer.launch({
    headless: 'new',
    executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
    args: ['--no-sandbox', '--disable-dev-shm-usage'],
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1920, height: 1080 });
  await page.goto(URL + '?_qa=1', { waitUntil: 'networkidle2', timeout: 30000 });
  await new Promise(r => setTimeout(r, 2500));
  const text = await page.evaluate(() => document.body.innerText);
  console.log(text);
  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
