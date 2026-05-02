/loop /grind

BOSS QA — CYCLE 9. Final-stretch findings.

Boss-verified-closed since cycle 8 push:
✅ C3-2 — /db mobile rank-cell overlap fixed: real item names + #N + category + price now visible per row
✅ C3-3 — /item mobile sticky-actions no longer covering price block
✅ C3-5 — Cookie pill moved bottom-RIGHT (no longer covering Settings High Contrast)
✅ F3 — Home subhead now reads "The non-custodial s&box marketplace — verified sellers, escrowed trades, instant cash-out."

═══════════════════════════════════════════════════
P0 — NEW REAL BUG (Q2 caught it)
═══════════════════════════════════════════════════

[CSP1 — /changelog.html inline `<script>` triggers CSP violation]
After you added the SkinBox `<nav>` + `<footer>` chrome (N2), changelog.html now contains an inline script:
```
src/main/resources/static/changelog.html:378
<script>document.write('© ' + new Date().getFullYear() + ' SkinBox · Not affiliated with Facepunch Studios...');</script>
```
This violates the CSP `script-src 'self' https://unpkg.com https://static.cloudflareinsights.com`. Browser refuses to execute it; copyright year falls back to whatever was hard-coded.

Fix: replace with a static "© 2026 SkinBox · ..." text node (no JS needed for the year). OR externalize the script into a tiny `/js/changelog-footer.js` file that sets the text on DOMContentLoaded. Pick the static text — simpler and one less bundle hit.

Verify: curl + grep for inline `<script>` in changelog.html (must return 0 inline scripts), and re-run Q2 console scan — must report 0 CSP violations.

═══════════════════════════════════════════════════
P1 — REMAINING POLISH
═══════════════════════════════════════════════════

[N3 — /affiliate Requirements polish DEFERRED]
The 5-platform stat row shipped as compact chips, but the boss asked for the full `.stall-stat-grid` style (icon top, bold value, tracked-out caps label below). Promote the platform requirement chips to that pattern. Optional, but it's the visual upgrade the boss requested.

[F4 — Rarity badge color treatment]
Standard / Scarce / Rare / Legendary need distinct color treatment. Add to RarityBadge in primitives.js — even if only Standard is currently seeded, ship the colour map so future tiers render correctly:
  Standard → #6b7280 (gray)
  Scarce → #d4a418 (amber)
  Rare → #1ea5ff (cta blue)
  Legendary → #8b5cf6 (purple)

[F5 — /db scrollbar polish]
`.db-table-scroll` should have:
```css
scrollbar-width: thin;
scrollbar-color: var(--line) transparent;
```

[CHECKLIST — production_checklist.md update]
Move all closed boss-QA items from "Still open" → "Closed this lap (2026-05-02)". Trim entries older than 7 days. Keep file under 300 lines.

═══════════════════════════════════════════════════
FRESH WALK
═══════════════════════════════════════════════════

After CSP1 + N3 + F4 + F5 + CHECKLIST land, walk every route at desktop AND mobile one final time. Find 3 more issues. Bar = "better than Amazon, looks like $10M".

═══════════════════════════════════════════════════
RULES
═══════════════════════════════════════════════════

1. Order: CSP1 → N3 → F4 → F5 → CHECKLIST → fresh walk.
2. Build + restart. After-shot to `_qa_boss/cycle_9/<id>.png`.
3. DOM-grep verify CSP1 (0 inline scripts in changelog.html).
4. Commit per batch.
5. NO ScheduleWakeup. Watchdog re-pokes if you idle past 120s.

GO.
