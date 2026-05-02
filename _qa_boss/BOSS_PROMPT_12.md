/loop /grind

BOSS QA — CYCLE 12. Boss ran @axe-core/puppeteer across 16 routes — found REAL WCAG violations the worker hasn't seen yet. Full report at `_qa_boss/cycle_11/axe.json`.

═══════════════════════════════════════════════════
P0 — A11Y CRITICAL (3 distinct violations)
═══════════════════════════════════════════════════

[A1 — `/` aria-required-children]
`<div class="csfloat-home-preview-row" role="list">` has child elements that aren't `role="listitem"`. Per WCAG: when an element has `role="list"`, every direct child must be `role="listitem"`. Either:
  (a) Add `role="listitem"` to each `.csfloat-home-preview-card` direct child.
  (b) Remove `role="list"` from the parent and use semantic `<ul><li>...</li></ul>` markup.
Pick (a) — minimal change.

[A2 — `/market` aria-allowed-attr]
`<input class="search-input" type="search" enterkeyhint="search">` has an ARIA attribute that's not allowed on `<input type="search">`. Probably `aria-expanded` or `aria-haspopup` on the bare input (those belong on combobox role). Run:
```
chrome --headless --dump-dom http://localhost:8082/market | grep -oE '<input[^>]*search-input[^>]*>'
```
Strip any aria-* attribute that's not `aria-label`. If you need typeahead semantics, wrap input in `<div role="combobox">` instead.

[A3 — `/settings` select-name]
`<select class="sort-select">` (the currency picker) has no accessible name. Either:
  (a) Wrap in `<label for="...">Currency</label>`.
  (b) Add `aria-label="Currency"`.
Pick (b) for inline-fastest fix.

═══════════════════════════════════════════════════
P0 — A11Y SERIOUS (2 distinct violations × 92+30 nodes)
═══════════════════════════════════════════════════

[A4 — color-contrast (92 nodes across 16 routes)]
Multiple text elements fail WCAG AA contrast ratio (4.5:1 for normal text, 3:1 for large). Likely culprits:
  - `var(--ink-3)` / `var(--ink-4)` against dark backgrounds — too dim.
  - Placeholder text (`color: var(--ink-3)` on inputs).
  - Disabled-button text.
Run a quick fix: bump `--ink-3` from current value (probably `#7a7a7a`-ish) to at least `#a0a0a0` against the dark `--bg-0`. Test with the WebAIM contrast checker or by running the axe scan again — must drop to 0 after the bump.

[A5 — nested-interactive on /db (30 nodes)]
Database table rows have an interactive star/watch button INSIDE a clickable row. axe rejects nested interactive controls because keyboard users can't tab into the inner button without first activating the outer. Either:
  (a) Make the row not interactive (no onClick on `<tr>`); attach the click only to the item-name cell as an `<a href>`.
  (b) Stop-propagate the inner button's click + keep the row clickable, but expose only the row to assistive tech and hide the button from screen readers (`aria-hidden="true"` on the inner star).
Pick (a). The whole-row click was added in D5 — it's nice for sighted users but ARIA-incompatible. Move the click target to a `<a class="db-row-link" href="/item/{id}">` wrapping just the name+thumb cell.

═══════════════════════════════════════════════════
HOW TO VERIFY
═══════════════════════════════════════════════════

After each batch:
```bash
cd /c/Users/WW/Desktop/sboxmarket
node _qa_boss/cycle_11/axe.js 2>&1 | tail -10
```
Acceptance: TOTAL critical = 0, serious = 0 (or only color-contrast on intentionally-low-contrast disabled states).

═══════════════════════════════════════════════════
RULES
═══════════════════════════════════════════════════

1. Order: A1 → A2 → A3 → A4 → A5.
2. Build + restart. Re-run axe per fix.
3. Commit per logical batch (`Boss QA cycle 12: A1+A2+A3 critical a11y fixes`).
4. NO ScheduleWakeup. Watchdog at 90s idle.
5. After cycle 12 closes, do MICRO-POLISH cycle 11 work too (loading states, hover/active states, transitions, mobile tap targets — see BOSS_PROMPT_11.md).

GO.
