/loop /grind

BOSS QA — CYCLE 5. Verified-closed:

✅ **B1** — /loadout/1 fallback works: "Cardboard Connoisseur" with 8 item slots, copy reads "Loadout #1 doesn't exist — showing the lowest-id public loadout instead. Browse all public loadouts →". Clean.
✅ **S1+S2+S3** — stall page filled with TRUST SIGNALS / REVIEWS / Browse all listings; stat-card grid labeled (Joined / Last seen / Lifetime sales / Last 30D / Last listed / Last sale); no (?) placeholder.
✅ **G6+G8** — settings + wallet show proper sign-in gates for anonymous.
✅ Mobile stall stat-card grid is great — vertical stack, all labeled.

═══════════════════════════════════════════════════
P0 — STILL OPEN (FOURTH LAP, BOSS GETTING IMPATIENT)
═══════════════════════════════════════════════════

[M1m — /item/1 MOBILE: PRICE BLOCK + LISTINGS COMPLETELY MISSING — STILL UNFIXED]
Mobile shot at `_qa_boss/cycle_5/m04-item.png` STILL shows ONLY: image → breadcrumb → "Out of Stock" + "Sign in to make offer" + Steam + heart. NO PRICE. NO PRICE HISTORY. NO LISTINGS. NO SIMILAR ITEMS. This is the actual product page; without price + listings it's a dead page.

Diagnostic steps:
```bash
cd /c/Users/WW/Desktop/sboxmarket
# 1. Find the modal-stats CSS rule (likely hiding on mobile)
grep -nE "modal-stats|modal-stat-box" src/main/resources/static/css/design.css | head -30
# 2. Look for media-query rules wrapping it
grep -nE "@media.*max-width|@media.*<=" src/main/resources/static/css/design.css | head -50
# 3. Inspect the JSX
grep -nE "modal-stats|modal-stat-box|Floor Price|FLOOR PRICE" src/main/resources/static/js/modals.js src/main/resources/static/js/app.js src/main/resources/static/js/cards.js | head -40
```

Likely causes:
  (a) `@media (max-width: 600px) { .modal-stats { display: none } }` (or similar) too aggressive.
  (b) JSX renders only the image + actions in mobile mode via a conditional.
  (c) Layout uses CSS grid that puts the stats column off-screen with `grid-template-columns` not adapting at narrow widths.

Fix: ensure on mobile the layout stacks: image → STAT BLOCK ($17.45 / Floor / Steam / 30D / Supply) → tabs → price-history chart → active-listings → similar items → action buttons. NO content hidden, just stacked vertically.

Verify: `curl http://localhost:8082/item/1 | grep -c "FLOOR PRICE"` — must be > 0 in served HTML, AND mobile screenshot must visibly show the price block.

[M3m — /db MOBILE: TABLE STILL SHOWS ONLY ROW NUMBERS — STILL UNFIXED]
`_qa_boss/cycle_5/m03-database.png` shows `#1 / #2 / #3 / #4 (Standard)` — item NAME, category, supply, price all off-screen. Switch to a stacked CARD layout when viewport ≤ 600px:
```css
@media (max-width: 600px) {
  .db-table thead { display: none }
  .db-table tbody tr { display: grid; grid-template-columns: 48px 1fr auto; gap: 12px;
    padding: 12px; border-bottom: 1px solid var(--line); }
  .db-table tbody td { display: contents }
  .db-thumb { grid-row: span 2; width: 48px; height: 48px }
  .db-name-cell { font-weight: 600 }
  .db-meta-cell { font-size: 12px; color: var(--ink-3) }
  .db-price-cell { align-self: center; font-weight: 600 }
}
```
Adapt to the actual class names. Verify by mobile shot: each row must show thumbnail + item name + category meta + price clearly.

[M2m — /stall/1 MOBILE: H1 STILL TOUCHES RIGHT EDGE]
Cycle 5 mobile shot shows "Chib skinbox.market's Stall" reaching right edge. The owner's username can be longer in real cases (`StoneColdKillerXX-2026`...). Add to `.stall-page-h1`:
```css
font-size: clamp(20px, 6vw, 30px);
word-break: break-word;
overflow-wrap: anywhere;
hyphens: auto;
```

[N1 — Footer "Discord — coming soon" + "X / Twitter — coming soon" — STILL UNFIXED]
Boss said pick: wire them up OR delete. You haven't done either after 3 laps. Just **DELETE** the two `<button class="site-footer-social" disabled>` elements. Remove from the footer JSX, remove their CSS rules. Boss will re-introduce them when real accounts exist.

═══════════════════════════════════════════════════
P0 — DEEP QA (Q1-Q4 from cycle 4 — boss expects these now)
═══════════════════════════════════════════════════

[Q1 — CLICK SCAN]
Use puppeteer (already in node_modules per `ls /c/Users/WW/Desktop/sboxmarket/node_modules/puppeteer 2>/dev/null`):
```js
// _qa_boss/cycle_5/click-scan.js
const puppeteer = require('puppeteer');
(async () => {
  const browser = await puppeteer.launch({ headless: 'new' });
  const routes = ['/','/market','/db','/help','/faq','/cart','/watchlist','/wallet','/sell','/settings','/profile/personal','/item/1','/stall/1','/loadout/1','/item/missing','/stall/missing','/loadout/missing'];
  const findings = [];
  for (const r of routes) {
    const page = await browser.newPage();
    await page.setViewport({ width: 1920, height: 1080 });
    const errors = [];
    page.on('pageerror', e => errors.push({ route: r, type: 'pageerror', msg: e.message }));
    page.on('console', m => { if (m.type() === 'error') errors.push({ route: r, type: 'console', msg: m.text() }); });
    page.on('response', resp => { if (resp.status() >= 400 && !resp.url().includes('/api/users/me')) errors.push({ route: r, type: 'http', status: resp.status(), url: resp.url() }); });
    await page.goto('http://localhost:8082' + r, { waitUntil: 'networkidle2', timeout: 15000 });
    const buttons = await page.$$eval('button, [role="button"]', els =>
      els.map(el => ({ text: (el.textContent||'').trim().slice(0,40), label: el.getAttribute('aria-label')||'', disabled: el.disabled||false })));
    findings.push({ route: r, buttons: buttons.length, errors });
    await page.close();
  }
  await browser.close();
  require('fs').writeFileSync('/c/Users/WW/Desktop/sboxmarket/_qa_boss/cycle_5/click-scan.json', JSON.stringify(findings, null, 2));
  console.log('Total errors:', findings.reduce((a,f) => a + f.errors.length, 0));
})();
```
Run: `node _qa_boss/cycle_5/click-scan.js`. Acceptance: TOTAL ERRORS == 0 across all 17 routes.

[Q4 — KEYBOARD FOCUS]
Add to `design.css` if not present:
```css
:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; border-radius: 4px; }
button:focus-visible, a:focus-visible, input:focus-visible, select:focus-visible, textarea:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
```
Verify by tabbing through `/market` — every focusable control shows the ring.

═══════════════════════════════════════════════════
P1 — POLISH (boss eyeballed cycle-5 shots)
═══════════════════════════════════════════════════

[P1.1 — /home rail tabs may NOT have segmented-control treatment]
Cycle-3 home shot still shows generic underlined tabs. Verify in dev tools that `.csfloat-home-rail-tab.active` has `background: var(--ink); color: var(--bg)`. If not, the rule didn't take. Promote with selector specificity (`.csfloat-home-rail .csfloat-home-rail-tab.active`) or `!important`. After: home rail tabs must look like a pill segmented control, not text underlines.

[P1.2 — /home hero parallax stack may not be visible]
Cycle-3 shows ONLY the front Cardboard King card. The 80%/60%/40% opacity stack should be visible behind it. Bump back-card opacity to 50%/35%/20% with `filter: blur(2px)` so they read as deliberate parallax cards.

[P1.5 — /faq inline preview rows: contrast]
F2 shipped, but the inline preview rows in /help "Frequently Asked Questions" must have stronger contrast. Verify `.help-faq-preview-row` text color is ≥ `var(--ink-2)`.

═══════════════════════════════════════════════════
COMPLETE FRESH WALK (when above is done)
═══════════════════════════════════════════════════

After this list is empty, walk all 17 routes desktop AND 14 mobile in fresh QA mode. Find the next 10 issues yourself. Bar = "better than Amazon". Ship them. Cycle 6.

GO.
