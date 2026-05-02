// Render a route, dump HTML+screenshot, capture console+network errors.
const puppeteer = require('puppeteer');
(async () => {
  // Accept either a full URL or a path; the path may be a literal (e.g. "loadout/1")
  // since Git Bash auto-converts a leading slash into a Windows path.
  let arg = process.argv[2] || 'loadout/1';
  let path = arg.startsWith('http') ? arg : ('/' + arg.replace(/^\/+/, ''));
  const out  = process.argv[3] || '_qa_boss/cycle_4/_dom.html';
  const shot = process.argv[4] || '';
  const mode = process.argv[5] || 'desktop';
  const viewport = mode === 'mobile'
    ? { width: 393, height: 852, isMobile: true, hasTouch: true, deviceScaleFactor: 2 }
    : { width: 1920, height: 1080 };
  const browser = await puppeteer.launch({
    headless: 'new',
    args: ['--no-sandbox','--disable-setuid-sandbox','--disable-dev-shm-usage']
  });
  const page = await browser.newPage();
  await page.setViewport(viewport);
  if (mode === 'mobile') {
    await page.setUserAgent('Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1');
  }
  const errors = [];
  const warnings = [];
  const network = [];
  page.on('console', msg => {
    if (msg.type() === 'error')   errors.push(msg.text());
    if (msg.type() === 'warning') warnings.push(msg.text());
  });
  page.on('response', r => {
    const u = r.url();
    if (r.status() >= 400 && !u.includes('chrome-extension')) network.push(r.status() + ' ' + u);
  });
  try {
    const url = path.startsWith('http') ? path : ('http://localhost:8082' + path);
    await page.goto(url, { waitUntil: 'networkidle2', timeout: 25000 });
    await new Promise(r => setTimeout(r, 1200));
  } catch (e) { console.error('NAV_ERR', path, e.message); }
  const html = await page.content();
  const fs = require('fs');
  fs.writeFileSync(out, html);
  if (shot) await page.screenshot({ path: shot, fullPage: false });
  // Probe: returns useful per-route info
  const probe = await page.evaluate(() => {
    const root = document.getElementById('root');
    const body = (root?.innerText || '').slice(0, 1500);
    return {
      title: document.title,
      headings: [...document.querySelectorAll('h1, h2')].map(e => e.textContent.trim()).filter(Boolean).slice(0, 20),
      scrollW: document.documentElement.scrollWidth,
      windowW: window.innerWidth,
      hOverflow: document.documentElement.scrollWidth > window.innerWidth,
      body
    };
  });
  console.log(JSON.stringify({ path, errors, warnings, network, ...probe }, null, 2));
  await browser.close();
})();
