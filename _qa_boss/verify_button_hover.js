const puppeteer = require('puppeteer');

const URL = process.argv[2] || 'https://skinbox.market/';
const SELECTOR = process.argv[3] || '.btn, .cta, .buy-btn';

(async () => {
  const browser = await puppeteer.launch({
    headless: 'new',
    executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
    args: ['--no-sandbox', '--disable-dev-shm-usage'],
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1920, height: 1080 });
  await page.goto(URL, { waitUntil: 'networkidle2', timeout: 30000 });
  // React hydration — wait until at least one button actually renders.
  try { await page.waitForSelector(SELECTOR, { timeout: 15000 }); }
  catch (_) { /* fall through to "no buttons matched" */ }
  await new Promise(r => setTimeout(r, 1500));

  const elements = await page.$$(SELECTOR);
  if (!elements.length) {
    console.log(JSON.stringify({ url: URL, selector: SELECTOR, found: 0, note: 'no buttons matched' }));
    await browser.close();
    process.exit(2);
  }

  const results = [];
  for (let i = 0; i < Math.min(elements.length, 5); i++) {
    const el = elements[i];
    const before = await page.evaluate(e => {
      const cs = getComputedStyle(e);
      return {
        opacity: cs.opacity,
        transition: cs.transition,
        textDecorationLine: cs.textDecorationLine,
      };
    }, el);

    await el.hover();
    await new Promise(r => setTimeout(r, 250));

    const after = await page.evaluate(e => {
      const cs = getComputedStyle(e);
      return {
        opacity: cs.opacity,
        textDecorationLine: cs.textDecorationLine,
        textContent: (e.innerText || '').trim().slice(0, 40),
        className: e.className,
        tagName: e.tagName,
      };
    }, el);

    results.push({ index: i, before, after });
  }

  console.log(JSON.stringify({ url: URL, selector: SELECTOR, totalFound: elements.length, sampled: results }, null, 2));
  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
