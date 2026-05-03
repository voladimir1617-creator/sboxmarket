# Dead CSS Scan — sboxmarket frontend

## Heuristic

1. Extract all unique CSS class selectors from `src/main/resources/static/css/design.css`
   (`.[A-Za-z_][A-Za-z0-9_-]*`).
2. Build a set of every word-boundary token in every `.html`/`.js` file under
   `src/main/resources/static/` (excluding `/css/`).
3. A class is **referenced** if its exact name is in that token set (whole-word match).
4. **Dynamic-pattern awareness** (excluded from "dead"):
   - Template-literal prefixes: `` `foo-${var}` `` extracts `foo-`; any class starting
     with that prefix is treated as possibly dynamic.
   - Concat prefixes: `'foo-' + state` and `state + 'foo-'` extract `foo-`.
   - Prefix-family: if any sibling class sharing the first two dash-segments is referenced,
     all members of that family are kept.
5. Detected 42 dynamic prefixes including `bo-`, `card-`, `del-`, `ec-`, `ends-`, `follow-`,
   `fs-`, `grad-`, `help-faq-panel-`, `hot-`, `info-modal-h-`, `item-`, `nav-picker-panel-`,
   `nf-rv-`, `pos-`, `preview-`, `rarity-`, `review-report-drawer-`, `rs-`, `rv-empty-`,
   `rv-wl-`, `sev-`, `skinbox-2fa-backup-codes-`, `skinbox-api-key-`, `sort-picker-panel-`,
   `td-`, `trade-`.

## Conservative Estimate

| Bucket                                                         | Count | %     |
| -------------------------------------------------------------- | ----- | ----- |
| Total unique class selectors                                   | 10636 | 100.0 |
| Whole-word matched in `.html`/`.js`                            | 1213  | 11.4  |
| Maybe-dynamic (prefix family has at least one matched member)  | 248   | 2.3   |
| **Conservative dead (zero match, no dynamic siblings)**        | 9175  | **86.3** |

The prior heuristic's 88.9% upper bound drops to **~86.3%** once dynamic patterns and
prefix-family inheritance are considered. The CSS file is overwhelmingly dead weight —
much of it appears to be design-system scaffolding (CS:Float clone classes) that was
authored but never wired into JS.

## Lines Deleted

**66 rules removed, 480 lines deleted (122251 -> 121771).** All deletions:

- Have 4+ dash-segments (highly unique names, almost certainly never dynamic)
- Have zero whole-word match anywhere under `static/`
- Do NOT contain `!important`
- Are NOT pseudo-element-only / hover/focus-only rules
- Are NOT in the recently-touched class set (last 6h commits)
- Do NOT appear as substring inside any other token in source
- Rule body < 800 chars (skips animation blocks)

Deleted selector list saved to `_qa_boss/dead_css_deleted.txt`.

### Sample deleted selectors (first 20)

- `.csfloat-cart-checkout-btn`
- `.csfloat-cart-drawer-backdrop`
- `.csfloat-cart-drawer-body`
- `.csfloat-cart-drawer-close`
- `.csfloat-cart-drawer-count`
- `.csfloat-cart-drawer-footer`
- `.csfloat-cart-drawer-header`
- `.csfloat-cart-drawer-title`
- `.csfloat-cart-empty-cta`
- `.csfloat-cart-empty-desc`
- `.csfloat-cart-empty-icon`
- `.csfloat-cart-empty-title`
- `.csfloat-cart-row-img`
- `.csfloat-cart-row-info`
- `.csfloat-cart-row-meta`
- `.csfloat-cart-row-price`
- `.csfloat-cart-row-remove`
- `.csfloat-cart-row-title`
- `.csfloat-cart-totals-row`
- `.csfloat-chart-range-btn`

Brace count after deletion: 20633 open / 20633 close — balanced.

## Top 20 Flagged-but-Kept (might be dynamic)

These appear in CSS but not in JS/HTML as whole words. They were NOT deleted because their
prefix family has at least one referenced sibling, suggesting they may be assembled
dynamically:

1. `.admin-stats`
2. `.auction-countdown-time`
3. `.cart-summary-checkout-btn`
4. `.csfloat-band-action`
5. `.csfloat-band-card-meta`
6. `.csfloat-band-card-metarow`
7. `.csfloat-band-card-pricerow`
8. `.csfloat-band-card-rarity`
9. `.csfloat-band-card-usd`
10. `.csfloat-band-header`
11. `.csfloat-band-tabs-wrap`
12. `.csfloat-band-title`
13. `.csfloat-band-title-kicker`
14. `.csfloat-band-track`
15. `.csfloat-db-categories`
16. `.csfloat-db-category-chip`
17. `.csfloat-db-category-chip-count`
18. `.csfloat-db-grid`
19. `.csfloat-db-hero-inner`
20. `.csfloat-db-hero-stat-label`

## Tooling

The scanner script lives at `_qa_boss/dead_css_scan.py` and is reproducible. Re-running
after future feature work will regenerate `/tmp/dead_css.txt` and `_qa_boss/recent_css_classes.txt`.
