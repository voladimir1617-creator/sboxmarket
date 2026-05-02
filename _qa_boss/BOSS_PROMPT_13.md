/loop /grind

BOSS QA — CYCLE 13. Perf check found the heavy hitters. After cycle 12 a11y, address these.

═══════════════════════════════════════════════════
P0 — PERF (boss perf scan results in `_qa_boss/cycle_11/perf.json` / `transfer.js`)
═══════════════════════════════════════════════════

Per-route transfer on /home (uncompressed): ~10MB. Top 5 hits:
1. **5174KB Material Symbols Rounded font** (single woff2 with all axes)
2. **3210KB design.css** (120K lines, uncompressed)
3. **737KB modals.js**
4. **443KB app.js**
5. **198KB staff-modals.js** (loaded everywhere, only needed for staff routes)

This is fine for localhost; production via Cloudflare gets gzip+brotli, dropping design.css to ~300KB. But the Material Symbols 5MB hit and staff-modals.js loading-everywhere are real issues.

[P1 — Material Symbols Rounded: 5MB → use the ICON SUBSET API]
Current import in `index.html` head:
```
<link href="https://fonts.googleapis.com/css2?family=Material+Symbols+Rounded:opsz,wght,FILL,GRAD@20..48,300..700,0..1,-50..200&display=swap" rel="stylesheet">
```
Loads the FULL variable font with all axis combinations. Replace with the static-subset API + the actual icon set you use:
```
<link href="https://fonts.googleapis.com/css2?family=Material+Symbols+Rounded:opsz,wght,FILL,GRAD@24,400,0,0&icon_names=lock,inbox,shopping_cart,notifications,share,heart,star,...&display=swap" rel="stylesheet">
```
Inventory which icon names you ACTUALLY USE first:
```
grep -oE '<span class="material-symbols-rounded[^"]*"[^>]*aria-hidden="true"[^>]*>([^<]*)</span>' src/main/resources/static -r | grep -oE '>[a-z_]+<' | sort -u
```
Then build the `icon_names=` list from that. Acceptance: `chrome --headless --dump-dom http://localhost:8082/ | grep -oE 'material-symbols-rounded' | wc -l` unchanged, AND first-page transfer drops from 10MB → ~6MB (saves 4MB by not pulling all 2700 unused icons).

[P2 — staff-modals.js (198KB) loaded on every route]
Move `staff-modals.js` to be loaded ONLY when `me?.role === 'STAFF'`. Either:
  (a) Lazy-import via dynamic `import()` when staff panel is opened.
  (b) Conditional `<script>` injection in main.js based on `me.role`.
Pick (a). Drop a `<script>` tag in `index.html` and instead load `staff-modals.js` from `csr-modals.js` and `admin-modals.js` (those are the only callers).

[P3 — design.css 3.2MB → audit unused selectors]
120k-line CSS is fine if all rules render somewhere, but likely 30-40% is dead. Use `purgecss` or chrome devtools coverage:
```
chrome --headless --dump-dom http://localhost:8082/ + /market + /db + /item/1 + /stall/1 + /loadout/1 + /help + /faq | extract used selectors | diff vs design.css
```
Don't actually delete anything aggressively (high regression risk). Just produce `_qa_boss/cycle_13/css-coverage.json` with the unused-selector list and the site coverage %. Boss reviews and approves a deletion batch.

[P4 — Skip /chrome /staff fonts on public routes]
The Inter font + JetBrains Mono are imported in `index.html` for ALL routes. Public routes use Geist + Fraunces. Remove Inter / JetBrains Mono from index.html if Fraunces / Geist already cover the use cases — saves 47KB woff2 per page. Verify nothing in design.css references `font-family: Inter` first.

═══════════════════════════════════════════════════
ALSO CHEW THROUGH CYCLE 11 + CYCLE 12 if you haven't
═══════════════════════════════════════════════════

- Cycle 11 micro-polish (BOSS_PROMPT_11.md): loading states, hover/active states, transitions, mobile tap targets ≥ 44px
- Cycle 12 a11y (BOSS_PROMPT_12.md): A1 (aria-required-children on /home preview row) → A2 (aria-allowed-attr on /market search-input) → A3 (select-name on /settings currency picker) → A4 (color-contrast 92 nodes) → A5 (nested-interactive 30 nodes on /db rows)

═══════════════════════════════════════════════════
RULES
═══════════════════════════════════════════════════

1. Cycle 12 a11y FIRST (P0 user-impact), then cycle 13 perf, then cycle 11 micro-polish.
2. After perf changes: re-run `node _qa_boss/cycle_11/perf.js` to verify transfer drops on each route.
3. After a11y changes: re-run `node _qa_boss/cycle_11/axe.js` to verify critical=0, serious=0.
4. Commit per logical batch.
5. NO ScheduleWakeup. Watchdog at 90s.

GO.
