/loop /grind

BOSS QA — CYCLE 8 / SHIP MODE.

Boss verified at HEAD a3481c4: 0 dead links, 0 unlabeled buttons, 0 console errors, 0 4xx/5xx, 0 cookie banners, 0 "coming soon", 0 "Loadout not found", 0 floatbar — across all 24 public routes (when stable).

Latest closes:
✅ N2 — /changelog.html now has the same SkinBox chrome (`<nav class="nav">` + `<footer class="site-footer">`) as the SPA. Confirmed via curl + screenshot.
✅ P1.1 — Top Deals tab confirmed as charcoal-pill segmented control (Top Deals / Newest Items / Unique Items active state crisp).
✅ M2m — mobile stall H1 fits at 390×852 with 18px breakpoint.

═══════════════════════════════════════════════════
TIGHTEN-UP LIST (close these and we ship)
═══════════════════════════════════════════════════

[N3 — /affiliate Requirements polish]
The 5-platform requirement row (YouTube 5,000 / X 5,000 / TikTok 5,000 / Instagram 5,000 / Website 5,000 PMU) is a flat unlabeled row. Promote each to a labeled stat card using the existing `.stall-stat-grid` pattern: icon top, bold value middle, tracked-out caps label bottom. Make it look intentional, not like leftover form fields.

[F3 — /home hero subhead too long]
Current copy:
> "SkinBox is the home for s&box skin trading — a fast, secure marketplace built on non-custodial Steam trades, with stalls, auctions, watchlists, and instant cash-out."
That's ~180 chars and wraps to 4-5 lines. Replace with:
> "The non-custodial s&box marketplace — verified sellers, escrowed trades, instant cash-out."
Updates: `app.js` `csfloat-home-hero-subtitle` text node. Verify ≤ 100 chars.

[F4 — Rarity badge color treatment]
Current cards all show "Standard" tag in the same gray. When other rarities exist (Scarce / Rare / Legendary), they need distinct color treatment to be a useful signal:
  - Standard → gray (current)
  - Scarce → amber `#d4a418`
  - Rare → blue `#1ea5ff`
  - Legendary → purple `#8b5cf6`
Update `RarityBadge` in `primitives.js`. If only "Standard" is seeded so far, code in support for the other tiers so future seeds render correctly.

[F5 — /db scrollbar styling]
Add minimal scrollbar styling to the `.db-table-scroll` parent (or whatever wraps the catalogue):
```css
scrollbar-width: thin;
scrollbar-color: var(--line) transparent;
```
Plus on Chrome:
```css
.db-table-scroll::-webkit-scrollbar { width: 8px; height: 8px; }
.db-table-scroll::-webkit-scrollbar-thumb { background: var(--line); border-radius: 8px; }
.db-table-scroll::-webkit-scrollbar-track { background: transparent; }
```

[Q3 — Mobile horizontal-overflow check]
Run a small puppeteer script that for each route at 390×852 evaluates `document.documentElement.scrollWidth - window.innerWidth`. Save to `_qa_boss/cycle_8/mobile-overflow.json`. Acceptance: all routes ≤ 4. Fix any > 4 by adding `overflow-x: hidden` to `body` or constraining the offending child.

[CHECKLIST — production_checklist.md update]
Open `C:\Users\WW\.claude\projects\c--Users-WW-Desktop-sboxmarket\memory\production_checklist.md`:
- Move all closed boss-QA items from "Still open" → "Closed this lap (2026-05-02)"
- Drop entries older than 7 days (or move to git log reference)
- Keep file under 300 lines
- Add a new short entry at top: "Boss-QA full sweep landed 2026-05-02 — site visually launch-ready. See git log a3481c4 ↓."

═══════════════════════════════════════════════════
FRESH WALK (after the above lands)
═══════════════════════════════════════════════════

Walk the whole site one more time at desktop AND mobile. For each route, ask: would Amazon ship this exact pixel? If yes, move on. If no, find the gap (typography weight, micro-interaction, hover state, focus ring, copy clarity, image quality) and ship it. Bar = "better than Amazon, looks like $10M / 10 years built".

═══════════════════════════════════════════════════
RULES
═══════════════════════════════════════════════════

1. Order: N3 → F3 → F4 → F5 → Q3 → CHECKLIST → fresh walk.
2. Build + restart per batch. After-shot to `_qa_boss/cycle_8/<id>.png`.
3. DOM-grep verify when applicable.
4. Commit per batch.
5. NO ScheduleWakeup. Watchdog re-pokes if you idle past 120s.
6. Once cycle 8 list is empty, walk fresh and find 5 more issues yourself, ship them. Cycle 9.

GO.
