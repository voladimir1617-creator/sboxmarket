/* Boss QA cycle 13 P3 — chrome devtools coverage scan against design.css.
   Walks the routes the boss listed, records each rule's hit/miss across
   the union of pages, and emits the unused selectors + global coverage %.

   Run: `node _qa_boss/cycle_13/css-coverage.js` (needs the app on :8082).
   Don't delete on the strength of this report alone — many "unused" rules
   gate on logged-in state, modal open, or rare empty/error UI. The boss
   reviews and approves a deletion batch separately. */

const puppeteer = require('puppeteer');
const fs = require('fs');

const ROUTES = ['/', '/market', '/db', '/item/1', '/stall/1', '/loadout/1', '/help', '/faq', '/changelog'];

(async () => {
  const browser = await puppeteer.launch({ headless: 'new', args: ['--no-sandbox'] });
  /* Aggregate coverage: keys are byte ranges expressed as "start-end" against
     the design.css file. We OR-merge across routes — a selector counts as
     used if ANY route exercises it. */
  let designUrl = null;
  let designText = null;
  const usedRanges = [];
  const perRoute = {};

  for (const r of ROUTES) {
    const page = await browser.newPage();
    await page.coverage.startCSSCoverage();
    try {
      await page.goto('http://localhost:8082' + r + '?_qa=1', { waitUntil: 'networkidle2', timeout: 20000 });
      // Give modal-route work a beat to mount its CSS-using subtree.
      await new Promise(res => setTimeout(res, 600));
    } catch (e) {
      perRoute[r] = { error: e.message };
      await page.close();
      continue;
    }
    const cov = await page.coverage.stopCSSCoverage();
    const design = cov.find(c => /design\.css/.test(c.url || ''));
    if (design) {
      if (!designUrl) { designUrl = design.url; designText = design.text; }
      const used = design.ranges.reduce((a, x) => a + (x.end - x.start), 0);
      perRoute[r] = { total: design.text.length, used, pct: +(100 * used / design.text.length).toFixed(2) };
      usedRanges.push(...design.ranges);
    } else {
      perRoute[r] = { error: 'design.css not found in coverage payload' };
    }
    await page.close();
  }

  await browser.close();

  if (!designText) {
    console.error('design.css never loaded across the listed routes');
    process.exit(1);
  }

  // Merge overlapping ranges so the union covers each byte at most once.
  usedRanges.sort((a, b) => a.start - b.start);
  const merged = [];
  for (const r of usedRanges) {
    const last = merged[merged.length - 1];
    if (last && r.start <= last.end) last.end = Math.max(last.end, r.end);
    else merged.push({ ...r });
  }

  const totalBytes = designText.length;
  const usedBytes = merged.reduce((a, r) => a + (r.end - r.start), 0);
  const unusedBytes = totalBytes - usedBytes;

  // Split unused regions into selector-anchored chunks. Walk the CSS source,
  // pull selectors that lie entirely outside the merged ranges, and emit
  // the first ~4000 to keep the JSON readable.
  const unusedChunks = [];
  const ruleRe = /([^{};/]+)\{[^}]*\}/g;
  let m;
  while ((m = ruleRe.exec(designText)) !== null) {
    const selStart = m.index;
    const selEnd = selStart + m[0].length;
    const covered = merged.find(r => r.start <= selStart && r.end >= selEnd);
    if (!covered) {
      const sel = m[1].trim().split('\n').map(s => s.trim()).join(' ').slice(0, 240);
      if (sel && sel.length > 0 && !sel.startsWith('@')) {
        unusedChunks.push({ start: selStart, end: selEnd, selector: sel });
        if (unusedChunks.length >= 4000) break;
      }
    }
  }

  const out = {
    file: designUrl,
    bytes: { total: totalBytes, used: usedBytes, unused: unusedBytes,
             coveragePct: +(100 * usedBytes / totalBytes).toFixed(2) },
    perRoute,
    unusedSelectorCount: unusedChunks.length,
    unusedSelectorsSample: unusedChunks.slice(0, 200).map(c => c.selector),
  };
  fs.writeFileSync('_qa_boss/cycle_13/css-coverage.json', JSON.stringify(out, null, 2));
  console.log(`design.css: ${(totalBytes/1024).toFixed(0)}KB, used ${out.bytes.coveragePct}% across ${ROUTES.length} routes`);
  console.log(`${unusedChunks.length} unused-selector candidates → cycle_13/css-coverage.json`);
})();
