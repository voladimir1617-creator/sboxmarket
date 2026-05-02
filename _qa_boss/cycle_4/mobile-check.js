// Boss QA cycle 4 Q3 — mobile deep check across every route.
// Per route, captures: horizontal-overflow, burger-nav state if any,
// top-bar (currency/lang) layout overflow, cookie-pill collisions.

const puppeteer = require('puppeteer');
const fs = require('fs');

const BASE = 'http://localhost:8082';
const ROUTES = [
  '/', '/market', '/db', '/help', '/faq', '/cart', '/watchlist',
  '/wallet', '/sell', '/settings', '/profile/personal',
  '/item/1', '/stall/1', '/loadout/1'
];

(async () => {
  const out = process.argv[2] || '_qa_boss/cycle_4/mobile-check.json';
  const browser = await puppeteer.launch({ headless: 'new', args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 393, height: 852, isMobile: true, hasTouch: true, deviceScaleFactor: 2 });
  await page.setUserAgent('Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1');
  const report = {};
  for (const route of ROUTES) {
    let navAttempts = 0;
    let navOk = false;
    while (!navOk && navAttempts < 3) {
      navAttempts++;
      try {
        await page.goto(BASE + route, { waitUntil: 'load', timeout: 25000 });
        navOk = true;
      } catch (e) {
        if (navAttempts === 3) report[route] = { navError: e.message };
        else await new Promise(r => setTimeout(r, 1500));
      }
    }
    if (!navOk) continue;
    await new Promise(r => setTimeout(r, 2500));
    const probe = await page.evaluate(() => {
      const out = {};
      // (a) Scroll to bottom and verify no horizontal overflow
      window.scrollTo(0, document.body.scrollHeight);
      out.scrollW = document.documentElement.scrollWidth;
      out.viewportW = window.innerWidth;
      out.hOverflow = out.scrollW > out.viewportW;
      out.bodyHeight = document.body.scrollHeight;
      // (b) Burger nav presence + state
      const burger = document.querySelector('button[aria-label*="menu" i], button.burger, button.nav-burger, button[aria-haspopup="menu"]');
      out.burgerFound = !!burger;
      // (c) Top-bar currency/lang switchers — check their right edge fits
      const pickers = [...document.querySelectorAll('.nav-picker')];
      out.pickerCount = pickers.length;
      out.pickersRightEdges = pickers.map(p => Math.round(p.getBoundingClientRect().right));
      out.pickerOverflow = pickers.some(p => p.getBoundingClientRect().right > window.innerWidth);
      // (d) Cookie pill collision with any sticky bottom CTA
      const cookiePill = document.querySelector('[aria-label="Cookie preferences"], [title="Cookie preferences"]');
      const cookieBox = cookiePill ? cookiePill.getBoundingClientRect() : null;
      const ctas = [...document.querySelectorAll('.btn-accent, .item-rail-actions-buy, .floating-cta, .cart-cta, button[type="submit"]')]
        .filter(el => {
          const r = el.getBoundingClientRect();
          // Only check sticky/bottom-positioned CTAs that are visible
          return r.bottom > window.innerHeight - 200 && r.height > 0 && r.width > 0;
        });
      let collides = false;
      const collisions = [];
      if (cookieBox) {
        for (const cta of ctas) {
          const r = cta.getBoundingClientRect();
          const overlap = !(r.right < cookieBox.left || r.left > cookieBox.right || r.bottom < cookieBox.top || r.top > cookieBox.bottom);
          if (overlap) {
            collides = true;
            collisions.push({ tag: cta.tagName, label: (cta.textContent || '').trim().slice(0, 30) });
          }
        }
      }
      out.cookiePill = !!cookiePill;
      out.cookiePillRect = cookieBox ? { x: cookieBox.x, y: cookieBox.y, w: cookieBox.width, h: cookieBox.height } : null;
      out.cookieCollidesWithCta = collides;
      out.cookieCollisions = collisions;
      return out;
    });
    report[route] = probe;
  }
  fs.writeFileSync(out, JSON.stringify(report, null, 2));
  const lines = [];
  for (const [route, data] of Object.entries(report)) {
    if (data.navError) {
      lines.push(`${route}: NAV_ERR ${data.navError}`);
      continue;
    }
    const flags = [];
    if (data.hOverflow) flags.push('H-OVERFLOW(' + data.scrollW + '>' + data.viewportW + ')');
    if (data.pickerOverflow) flags.push('PICKER-OVERFLOW(' + data.pickersRightEdges.join(',') + ')');
    if (data.cookieCollidesWithCta) flags.push('COOKIE-CTA-COLLISION');
    lines.push(`${route}: ${flags.length ? flags.join(' · ') : 'CLEAN'}  burger=${data.burgerFound}  pickers=${data.pickerCount}  cookie=${data.cookiePill}`);
  }
  fs.writeFileSync(out.replace(/\.json$/, '.txt'), lines.join('\n'));
  console.log(lines.join('\n'));
  await browser.close();
})();
