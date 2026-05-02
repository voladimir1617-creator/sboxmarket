/loop /grind

BOSS QA — CYCLE 7 / FINAL POLISH. The site is approaching launch quality. Boss verified all of these CLOSED:

✅ All G1-G10 / H1-H5 / M1-M4 / I1-I8 / S1-S5 / D1-D5 / F1-F2 / B1 / N1 / M1m / M3m / Q1 / Q2 / Q4

Boss ran headless puppeteer scans across 24 routes:
  • Q1 click-scan: **0 unlabeled buttons, 0 dead links (`#` / `javascript:void(0)`), 0 inline onclick** site-wide
  • Q2 console scan: **0 console errors, 0 console warnings, 0 4xx/5xx** (besides /api/users/me 401 for anon)

NICE WORK. The remaining items are all polish — but the bar is "better than Amazon", so we finish them.

═══════════════════════════════════════════════════
P1 — REMAINING POLISH
═══════════════════════════════════════════════════

[M2m — /stall/1 MOBILE: H1 still touches right edge]
At 390×852, "Chib skinbox.market's Stall" stretches to the viewport's right edge. Add to `.stall-page-h1` (or whatever the H1 class is):
```css
font-size: clamp(20px, 5.5vw, 28px);
word-break: break-word;
overflow-wrap: anywhere;
hyphens: auto;
padding-right: 14px;
```
A long username like `StoneColdKillerXX-2026's Stall` would also wrap nicely.

[N2 — /changelog.html lacks SkinBox top-nav + footer chrome]
The standalone server-rendered changelog page shows ONLY "← BACK TO SKINBOX" + Changelog content. A premium site has CONSISTENT chrome on EVERY page. Either:
  (a) Wrap the body in the same `<nav class="nav">…</nav>` and `<footer class="site-footer">…</footer>` markup as the SPA — copy from the served `index.html`. Make sure to load the same CSS bundle.
  (b) Convert to an SPA route `/changelog` that mounts inside the App.
Pick (a) for less risk. File: `src/main/resources/static/changelog.html`.

[N3 — /affiliate "Requirements" section: stat-card grid hierarchy]
The 5-platform requirement row (YouTube 5,000 / X 5,000 / TikTok 5,000 / Instagram 5,000 / Website 5,000 PMU) is a flat unlabeled row. Promote each platform to a labeled stat card (icon + bold number + tracked-out label). Use the same `.stall-stat-grid` pattern from /stall/1.

[P1.1 — /home rail tab segmented control (verify visually)]
CSS at `design.css:118039-118093` says active tab should be `background: var(--ink); color: var(--bg)` with `!important`. Verify in headless screenshot at `_qa_boss/cycle_7/01-home.png` that the active tab actually shows the charcoal pill. If it still reads as text-with-underline, double-check the `.csfloat-home-rail-tab.active` class is being toggled by the React component (and not, e.g., `.is-active` or `[aria-pressed="true"]`).

═══════════════════════════════════════════════════
NEW FRESH WALK FINDS (boss eyeballed cycle-7 shots)
═══════════════════════════════════════════════════

[F3 — /home: subhead under hero is truncated]
"SkinBox is the home for s&box skin trading — a fast, secure marketplace built on non-custodial Steam trades, with stalls, auctions, watchlists, and instant cash-out." This wraps to 4-5 lines on desktop. A tighter pitch:
> "The non-custodial s&box marketplace — verified sellers, escrowed trades, instant cash-out."
Keep under 100 chars. Update in `app.js` or wherever `csfloat-home-hero-subtitle` is rendered.

[F4 — /home: cards in the rail show "Standard" rarity badge in same lockup style across all rarities]
Currently every card shows a small "Standard" tag below name. When other rarities exist (Scarce / Rare etc), they need distinct color treatment to be a useful signal. Check `RarityBadge` in `primitives.js`.

[F5 — /db: scrollbar visible by default on desktop]
Look at `_qa_boss/cycle_3/04-database.png` — there's an obvious right scrollbar. Premium sites hide the scrollbar until hover (`scrollbar-gutter: stable`, custom CSS scrollbar styling). Add to `.db-table-scroll` or its parent:
```css
scrollbar-width: thin;
scrollbar-color: var(--line) transparent;
```

═══════════════════════════════════════════════════
DEEP CHECK
═══════════════════════════════════════════════════

[Q3 — Mobile horizontal overflow check]
For each mobile route, evaluate in headless: `document.documentElement.scrollWidth - window.innerWidth` — must be 0 or ≤ 4. Save report `_qa_boss/cycle_7/mobile-overflow.json`. Fix any > 4.

═══════════════════════════════════════════════════
PRODUCTION CHECKLIST UPDATE
═══════════════════════════════════════════════════

Update `C:\Users\WW\.claude\projects\c--Users-WW-Desktop-sboxmarket\memory\production_checklist.md`:
  • Move all closed boss-QA items from "Still open" → "Closed this lap (2026-05-02)"
  • Trim entries older than 7 days
  • Keep file under 300 lines

═══════════════════════════════════════════════════
RULES
═══════════════════════════════════════════════════

1. M2m → N2 → N3 → P1.1 → F3 → F4 → F5 → Q3 → checklist update.
2. Build + restart per batch.
3. After-shot to `_qa_boss/cycle_7/<id>.png`.
4. Commit per batch.
5. NO ScheduleWakeup. Boss asleep. Watchdog auto-pokes if idle past 120s.
6. After this list, FRESH WALK desktop + mobile, find next 5 issues yourself, ship them. Cycle 8.

GO.
