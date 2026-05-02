const puppeteer = require('puppeteer');
const path = require('path');

const URL = process.argv[2] || 'https://skinbox.market/';
const OUT = process.argv[3] || path.join(__dirname, 'cycle_31', 'hover_demo.png');

(async () => {
  const browser = await puppeteer.launch({
    headless: 'new',
    executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
    args: ['--no-sandbox', '--disable-dev-shm-usage'],
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1920, height: 1080 });
  await page.goto(URL + '?_qa=1', { waitUntil: 'networkidle2', timeout: 30000 });
  await page.waitForSelector('.steam-btn, .nav-icon-btn, .nav-picker-chip', { timeout: 15000 });
  await new Promise(r => setTimeout(r, 1500));

  // Force :hover on every button-ish target via JS so the fade is captured
  // in the static screenshot. Static screenshots don't normally capture
  // pseudo-states without the cursor over the element — we paint an inline
  // opacity style to demo the post-hover state.
  await page.evaluate(() => {
    const sel = '.steam-btn, .nav-icon-btn, .nav-picker-chip, .deals-chip, .btn-primary, .market-cta, .hero-cta, .buy-btn';
    document.querySelectorAll(sel).forEach((el, i) => {
      // simulate hover state on every other one so the fade is visually
      // obvious side-by-side: even index = normal, odd = faded.
      if (i % 2 === 1) el.style.opacity = '0.78';
    });
  });

  await page.screenshot({ path: OUT, fullPage: false });
  console.log('OK', OUT);
  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
