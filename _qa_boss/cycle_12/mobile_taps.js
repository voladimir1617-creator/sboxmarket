// Cycle 12 — measure tap targets at MOBILE viewport. Boss prompt: "minimum
// 44×44px per Apple HIG. Check the burger menu, currency dropdown, language
// dropdown, watch-star, cart-plus, sort-toggle." Filter to controls that
// actually need to clear 44 (skip dense table rows, inline links inside
// paragraphs).
const puppeteer = require('puppeteer');

const ROUTES = ['/', '/market', '/db', '/cart', '/watchlist', '/item/1', '/stall/1'];

// Selectors that boss prompt explicitly called out — focus on these.
const PRIORITY = [
  '.nav-burger', '.burger', '[aria-label*="menu"]', '[aria-label*="Menu"]',
  '.sort-select', '.currency', '[aria-label*="currency"]',
  '[aria-label*="language"]', '.lang-select',
  '.grid-star', '.star-btn', '.btn-wishlist', '.watch-star',
  '.grid-cart-btn', '.cart-add', '.cart-plus',
  '.sort-toggle', '.view-btns button', '.toolbar button',
  '.modal-close', '.search-clear',
  '.nav-icon-btn', '.nav-bell', '.nav-cart', '.nav-help'
];

(async () => {
  const browser = await puppeteer.launch({ headless: 'new', args: ['--no-sandbox'] });
  const findings = {};
  for (const r of ROUTES) {
    const page = await browser.newPage();
    await page.setViewport({ width: 390, height: 852, isMobile: true, deviceScaleFactor: 2 });
    await page.setUserAgent('Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148');
    try {
      await page.goto('http://localhost:8082' + r + '?_qa=1', { waitUntil: 'networkidle2', timeout: 20000 });

      const out = await page.evaluate((PRIORITY) => {
        const seen = new Set();
        const found = [];
        for (const sel of PRIORITY) {
          document.querySelectorAll(sel).forEach(el => {
            const r = el.getBoundingClientRect();
            if (!r.width || !r.height || r.width < 4 || r.height < 4) return;
            if (r.top < 0 || r.top > 9999) return;
            const key = sel + '|' + Math.round(r.x) + ',' + Math.round(r.y);
            if (seen.has(key)) return;
            seen.add(key);
            const text = (el.textContent || '').trim().slice(0, 30);
            const aria = el.getAttribute('aria-label') || '';
            if (r.width < 44 || r.height < 44) {
              found.push({
                selector: sel,
                tag: el.tagName,
                cls: (el.className || '').toString().slice(0, 50),
                w: Math.round(r.width), h: Math.round(r.height),
                label: aria || text || '?'
              });
            }
          });
        }
        return found;
      }, PRIORITY);

      findings[r] = out;
    } catch (e) {
      findings[r] = { error: e.message };
    }
    await page.close();
    await new Promise(r => setTimeout(r, 250));
  }
  await browser.close();
  console.log(JSON.stringify(findings, null, 2));
})().catch(e => { console.error(e); process.exit(1); });
