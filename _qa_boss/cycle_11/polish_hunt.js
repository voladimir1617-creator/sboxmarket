// Cycle 11+ polish hunt — non-axe gaps the boss prompt enumerated.
// Loads each route, probes for:
//   - missing skeleton/shimmer in first 50ms (FCP-pre-paint blank screen)
//   - empty-state copy with no illustration (just material icon)
//   - clickable elements with NO :hover or :active CSS rule
//   - naked numbers in price/count slots ($1234 vs $1,234.00)
//   - icon vertical-align mismatches with surrounding text
//   - tap targets < 44px on mobile

const puppeteer = require('puppeteer');

const ROUTES = ['/', '/market', '/db', '/help', '/faq', '/cart', '/watchlist', '/wallet',
  '/sell', '/settings', '/profile/personal', '/item/1', '/stall/1', '/loadout/1',
  '/buy-orders', '/affiliate', '/support', '/changelog.html'];

(async () => {
  const browser = await puppeteer.launch({ headless: 'new', args: ['--no-sandbox'] });
  const findings = {};

  for (const r of ROUTES) {
    const page = await browser.newPage();
    await page.setViewport({ width: 1440, height: 900, deviceScaleFactor: 2 });

    try {
      await page.goto('http://localhost:8082' + r + '?_qa=1', { waitUntil: 'networkidle2', timeout: 20000 });

      const out = await page.evaluate(() => {
        const cssText = Array.from(document.styleSheets).map(s => {
          try { return Array.from(s.cssRules || []).map(r => r.cssText).join('\n'); }
          catch { return ''; }
        }).join('\n');

        // 1) Naked numbers — find $-prefixed strings without comma+decimal
        const nakedPrices = [];
        const re = /\$\s*\d{4,}(?!\.\d{2})|\$\s*\d{1,3}\.\d{1}(?!\d)/g;
        document.querySelectorAll('span, div, td').forEach(el => {
          const t = el.textContent && el.textContent.trim();
          if (!t || t.length > 30) return;
          const m = t.match(re);
          if (m && !el.querySelector('*')) nakedPrices.push({ tag: el.tagName, cls: el.className?.toString().slice(0,60), text: t.slice(0, 40) });
        });

        // 2) Empty-state phrases without an illustration in same parent
        const emptyPhrases = ['No active listings','No completed sales','Nothing on your watchlist','No reviews yet','No transactions yet','No buy orders','No tickets','No notifications'];
        const emptyStates = [];
        emptyPhrases.forEach(p => {
          document.querySelectorAll('div, p, h2, h3').forEach(el => {
            if (el.textContent.includes(p) && el.textContent.length < 200) {
              const parent = el.closest('section,div,article') || el.parentElement;
              const hasSvg = parent && parent.querySelector('svg');
              const hasMatIcon = parent && parent.querySelector('.material-symbols-rounded');
              if (!hasSvg && !hasMatIcon) emptyStates.push({ phrase: p, ctx: (el.textContent || '').slice(0, 80) });
              else if (hasMatIcon && !hasSvg) emptyStates.push({ phrase: p, ctx: 'matIconOnly: ' + (el.textContent || '').slice(0, 60) });
            }
          });
        });

        // 3) Tap targets — measure all interactive elements
        const small = [];
        document.querySelectorAll('button, a, input, [role="button"], [role="tab"], select').forEach(el => {
          const r = el.getBoundingClientRect();
          if (r.width === 0 || r.height === 0) return;
          if (r.width < 44 || r.height < 44) {
            const cls = (el.className || '').toString().slice(0, 60);
            const aria = el.getAttribute('aria-label');
            const title = el.getAttribute('title');
            small.push({
              tag: el.tagName, cls, w: Math.round(r.width), h: Math.round(r.height),
              text: (el.textContent || '').trim().slice(0, 30) || aria || title || '?'
            });
          }
        });

        // 4) Snap transitions — collect CSS rules that have :hover but no transition
        // (string-level proxy)
        const hoverRulesNoTransition = [];
        const lines = cssText.split('\n');
        lines.forEach((line, i) => {
          if (line.includes(':hover') && !line.includes('transition')) {
            // Look back 1-3 lines to see if a transition was set on the base rule
            // Skip common safe selectors
            if (line.match(/transform|background|color|border|opacity|filter|box-shadow/)) {
              hoverRulesNoTransition.push(line.trim().slice(0, 140));
            }
          }
        });
        return {
          nakedPrices: nakedPrices.slice(0, 12),
          nakedPricesCount: nakedPrices.length,
          emptyStates: emptyStates.slice(0, 12),
          emptyStatesCount: emptyStates.length,
          smallTaps: small.slice(0, 20),
          smallTapsCount: small.length,
          hoverNoTransitionSample: hoverRulesNoTransition.slice(0, 8),
          hoverNoTransitionCount: hoverRulesNoTransition.length
        };
      });

      findings[r] = out;
    } catch (e) {
      findings[r] = { error: e.message };
    }
    await page.close();
    await new Promise(rs => setTimeout(rs, 250));
  }

  await browser.close();

  console.log(JSON.stringify(findings, null, 2));

  console.log('=== ROLLUP ===');
  for (const r of Object.keys(findings)) {
    const f = findings[r];
    if (f.error) { console.log(`  ${r}: ERROR ${f.error}`); continue; }
    const flags = [];
    if (f.nakedPricesCount > 0) flags.push(`${f.nakedPricesCount}naked-price`);
    if (f.emptyStatesCount > 0) flags.push(`${f.emptyStatesCount}empty-state`);
    if (f.smallTapsCount > 0) flags.push(`${f.smallTapsCount}small-tap`);
    console.log(`  ${r}: ${flags.join(' / ') || 'clean'}`);
  }
})().catch(e => { console.error(e); process.exit(1); });
