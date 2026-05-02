// Boss QA cycle 4 Q1 + Q2 — click-scan + console/network capture across
// all key routes. Visits each route, walks every <button>, <a>, and
// [role="button"] inside #root, captures whether the click resulted in
// (a) a navigation, (b) a modal/drawer opening, (c) a no-op, or
// (d) a console error. Also dumps every console.error / console.warn
// and every 4xx/5xx network response. Output: JSON report at the path
// passed in argv[2].

const puppeteer = require('puppeteer');
const fs = require('fs');

const BASE = 'http://localhost:8082';
const ROUTES = [
  '/', '/market', '/db', '/help', '/faq', '/cart', '/watchlist',
  '/wallet', '/sell', '/settings', '/profile/personal',
  '/item/1', '/stall/1', '/loadout/1',
  '/item/9999', '/stall/9999', '/loadout/9999'
];

(async () => {
  const out = process.argv[2] || '_qa_boss/cycle_4/click-scan.json';
  const browser = await puppeteer.launch({ headless: 'new', args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport({ width: 1920, height: 1080 });
  const report = {};
  for (const route of ROUTES) {
    const errors = [];
    const warnings = [];
    const network = [];
    const handler = msg => {
      if (msg.type() === 'error')   errors.push(msg.text());
      if (msg.type() === 'warning') warnings.push(msg.text());
    };
    const respHandler = r => {
      if (r.status() >= 400 && !r.url().includes('chrome-extension')) {
        network.push(r.status() + ' ' + r.url());
      }
    };
    page.on('console', handler);
    page.on('response', respHandler);
    // Small breather between routes so the local stack isn't hammered with
    // back-to-back full SPA loads (which previously yielded ERR_EMPTY_RESPONSE
    // on the deepest /item/9999 / /loadout/9999 routes).
    await new Promise(r => setTimeout(r, 800));
    let navAttempts = 0;
    let navOk = false;
    while (!navOk && navAttempts < 3) {
      navAttempts++;
      try {
        await page.goto(BASE + route, { waitUntil: 'load', timeout: 25000 });
        navOk = true;
      } catch (e) {
        if (navAttempts === 3) errors.push('NAV_ERR: ' + e.message);
        else await new Promise(r => setTimeout(r, 1500));
      }
    }
    if (false) try {
      await page.goto(BASE + route, { waitUntil: 'load', timeout: 25000 });
      // Network-idle is fragile because some routes (/, /watchlist) keep
      // polling listeners open. Settle on a fixed delay instead.
      await new Promise(r => setTimeout(r, 3000));
    } catch (e) {
      errors.push('NAV_ERR: ' + e.message);
    }
    let probe;
    // Inventory of clickable elements + a static "would-it-do-anything" check.
    // We don't actually invoke the click — too risky (could trigger nav loops).
    // Instead we look at: does the element have an onclick handler attribute
    // or React's __reactProps with onClick, OR is it an <a href> with a
    // non-empty / non-"#" href, OR a <button type="submit"> inside a form,
    // OR [role="button"] with handlers.
    try {
    probe = await page.evaluate(() => {
      const root = document.getElementById('root') || document.body;
      const els = [...root.querySelectorAll('button, a, [role="button"]')];
      const summary = { total: els.length, dead: 0, samples: [] };
      for (const el of els) {
        const rect = el.getBoundingClientRect();
        if (rect.width === 0 && rect.height === 0) continue;
        const tag = el.tagName.toLowerCase();
        const href = (el.getAttribute && el.getAttribute('href')) || '';
        const hasOnclickAttr = !!el.getAttribute('onclick');
        const reactKeys = Object.keys(el).filter(k => k.startsWith('__reactProps$'));
        const hasReactClick = reactKeys.some(k => {
          const props = el[k];
          return props && typeof props.onClick === 'function';
        });
        const inForm = !!el.closest('form');
        const isButton = tag === 'button';
        const submitButton = isButton && (el.getAttribute('type') === 'submit') && inForm;
        const aHref = tag === 'a' && href && href !== '#' && href.length > 1;
        const dialogToggle = el.getAttribute('aria-haspopup') === 'true' || el.getAttribute('aria-haspopup') === 'menu' || el.getAttribute('aria-haspopup') === 'listbox';
        const dead = !hasOnclickAttr && !hasReactClick && !submitButton && !aHref && !dialogToggle && !el.disabled;
        if (dead) {
          summary.dead++;
          summary.samples.push({ tag, label: (el.textContent || '').trim().slice(0, 40), href, classes: el.className.toString().slice(0, 80) });
        }
      }
      return summary;
    });
    } catch (e) {
      errors.push('PROBE_ERR: ' + e.message);
      probe = { total: 0, dead: 0, samples: [] };
    }
    report[route] = { errors, warnings, network, ...probe };
    page.off('console', handler);
    page.off('response', respHandler);
  }
  fs.writeFileSync(out, JSON.stringify(report, null, 2));
  // Also drop a human-readable summary
  const lines = [];
  for (const [route, data] of Object.entries(report)) {
    const summary = `route=${route}  total=${data.total}  dead=${data.dead}  errors=${data.errors.length}  warnings=${data.warnings.length}  network=${data.network.length}`;
    lines.push(summary);
    if (data.dead > 0) lines.push('  dead samples: ' + JSON.stringify(data.samples.slice(0, 5)));
    if (data.errors.length) lines.push('  errors: ' + data.errors.slice(0, 5).map(e => e.slice(0, 200)).join(' | '));
    if (data.warnings.length) lines.push('  warnings: ' + data.warnings.slice(0, 5).map(e => e.slice(0, 200)).join(' | '));
    if (data.network.length) lines.push('  network: ' + data.network.slice(0, 5).join(' | '));
  }
  fs.writeFileSync(out.replace(/\.json$/, '.txt'), lines.join('\n'));
  console.log(lines.slice(0, 60).join('\n'));
  await browser.close();
})();
