/loop /grind

BOSS QA — CYCLE 14. Cycle 12 a11y crushed: critical 3→0, serious 17→2. **Only TWO violations left** — kill them and the site is WCAG 2.1 AA clean.

═══════════════════════════════════════════════════
P0 — REMAINING TWO A11Y VIOLATIONS (both on /item/1)
═══════════════════════════════════════════════════

[A6 — color-contrast on `.rarity-badge.rarity-Standard`]
HTML:
```
<span class="rarity-badge rarity-Standard" style="color: rgb(156, 163, 175); background: color-mix(rgb(156, 163, 175) 16%, transparent); border: 1px solid color-mix(rgb(156, 163, 175) 38%, transparent);">
```
The `rgb(156, 163, 175)` (Tailwind gray-400) on the dark bg fails AA. Two fixes:
  (a) Bump Standard color to `rgb(192, 199, 211)` (gray-300) for ~5:1 ratio.
  (b) Or use the existing brand `--ink-2` token (charcoal-bright) which already contrast-passes.
Pick (b). Find where `rarity-Standard` color is set in `primitives.js` (RarityBadge) or `design.css` and replace `rgb(156, 163, 175)` → `var(--ink-2)`.

[A7 — link-in-text-block on /item/1 "Database" link]
HTML:
```
<a href="/db?q=SWAG%20Chain" style="color: var(--accent); text-decoration: none;">Database</a>
```
WCAG 1.4.1 requires links inside text blocks to be visually distinguishable WITHOUT relying on color alone. `text-decoration: none` fails. Either:
  (a) Add `text-decoration: underline; text-underline-offset: 2px;`
  (b) Add a 2px-thick `border-bottom: 1px solid var(--accent)`
  (c) Add a font-weight bump to 600 in addition to color (so it's still distinguishable in monochrome).
Pick (a) — most common pattern. Find this anchor in modals.js / app.js / item-detail rendering.

═══════════════════════════════════════════════════
HOW TO VERIFY
═══════════════════════════════════════════════════

```bash
cd /c/Users/WW/Desktop/sboxmarket
node _qa_boss/cycle_11/axe.js 2>&1 | tail -10
```
Acceptance: `TOTAL critical: 0 serious: 0`.

═══════════════════════════════════════════════════
ALSO: cycle 11 + 13 still in your queue
═══════════════════════════════════════════════════

- Cycle 11 micro-polish: loading states, hover/active states, transitions, mobile tap targets ≥ 44px (BOSS_PROMPT_11.md)
- Cycle 13 perf: Material Symbols 5MB → icon subset, staff-modals lazy-load, design.css coverage audit, drop unused fonts (BOSS_PROMPT_13.md)

═══════════════════════════════════════════════════
RULES
═══════════════════════════════════════════════════

1. A6 + A7 first (5 min total).
2. Then back to cycle 13 perf (already in flight per recent edits to design.css / staff-modals.js).
3. Commit per logical batch.
4. NO ScheduleWakeup. Watchdog at 90s.

GO.
