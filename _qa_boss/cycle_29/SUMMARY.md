# Boss QA cycles 11-29 — polish sweep summary

This single `/loop /grind` turn shipped 19 polish cycles + 34 git commits since HEAD 061de41 (the cycle-10 baseline).

## Headline numbers

| Metric                                  | Before (HEAD 061de41) | After (HEAD 3026141) |
|-----------------------------------------|-----------------------|----------------------|
| Axe critical violations (18 routes)     | 3                     | **0**                |
| Axe serious violations (18 routes)      | 20                    | **0**                |
| Mobile tap-target violations (390×852)  | 104                   | **2**                |
| Routes with DOM nodes > 1500            | 0                     | 0                    |
| Routes with FCP > 2s on localhost       | 0                     | 0                    |

Remaining 2 mobile-tap violations are secondary controls (Save search at 36px height, item-detail wishlist btn at 36px) — both visually deliberate and not on the boss-prompt priority list.

## Cycle-by-cycle ledger

- **C11 axe sweep** — aria-required-children on home preview row, aria-allowed-attr on /market search-input (added role=combobox), select-name on /settings currency, color-contrast token bumps (--ink-3 → 0.74, --ink-4 → 0.66 in BOTH :root blocks; steam-btn gradient darkened; .btn-accent → --cta-d; rarity-Standard → gray-300; .chart-range button.active → --cta-d), /db nested-interactive (dropped tr role=button, added a.db-name), /changelog link-in-text-block underlines + code styling. **3 critical / 20 serious → 0 / 0.**
- **C11 empty-state SVGs** — custom branded line-drawings for /watchlist (heart-on-card), /item/1 active-listings (price-tag), /stall/1 reviews (star-quote-bubble). Replaced shopping-cart and Material 'inbox' fallbacks.
- **C11 press states** — :active translateY on .grid-card + .csfloat-home-preview-card + .filter-chip + .btn-accent.
- **C11 cart-row hover** — 140ms tint on dense cart rows.
- **C12 mobile tap-targets** — 44×44 on .grid-star + .grid-cart-btn, 44h on .sort-select / .discount-select / .toolbar-select, 44h on .type-toggle-btn / .deals-chip / .view-btns / .toolbar-refresh / .sort-picker-chip. End-of-file !important block to win the cascade against late-file resets.
- **C12 boot skeleton** — inline critical CSS in <head> + dark editorial chrome inside #root with logo + nav + content shimmer lines, so slow-3G first paint isn't UA-default white.
- **C13** — evidence + boot-skel mobile verification.
- **C14 nav-picker mobile 44px** — currency + language chips bumped from ~26px to 44px on touch.
- **C15** — image-fail fallback verified (geometric category glyphs render cleanly when Steam CDN is blocked).
- **C16 boot-skel React fade-in** — 220ms ease-out animation on every direct child of #root that isn't .boot-skel, so the React mount swap doesn't snap.
- **C17 H2 typography unified** — .csfloat-home-faq-title 28px → 32px to match .csfloat-home-journey-title.
- **C18 skip-link focus ring** — bumped from muted --ink-4 to --accent + 4px halo so the FIRST control a keyboard user reaches gets a strong on-brand outline.
- **C19-29 press-state matrix** — :active feedback on .cart-row, .modal-listing-row, .price-suggest-chip, .hero-tab, .hero-tab-cta, .csfloat-home-preview-tail, .csfloat-home-tile, .csfloat-home-faq-q, .wallet-tx-filter-chip, .grid-star, .grid-cart-btn. Every interactive surface now gives an 80ms tap echo before its functional state transition.
- **C20 scroll-padding-top** — html { scroll-padding-top: var(--nav-h) + 16px } so anchor jumps clear the sticky nav.

## Files touched

- `src/main/resources/static/css/design.css` — primary surface (token bumps + state coverage + mobile breakpoints)
- `src/main/resources/static/index.html` — boot skeleton + critical CSS + React fade-in
- `src/main/resources/static/js/app.js` — preview-row a11y + search-input combobox + footer version contrast
- `src/main/resources/static/js/csfloat-modals.js` — /db row a11y refactor (drop role=button, add a.db-name)
- `src/main/resources/static/js/modals.js` — currency select aria-label + 3 empty-state SVG illustrations
- `src/main/resources/static/js/primitives.js` — RARITY_COLORS Standard → gray-300 (axe contrast)
- `src/main/resources/static/js/help-modal.js` — empty-state contrast (token bump)
- `src/main/resources/static/changelog.html` — body-link underlines + code styling

## Memory updated

- Added `duplicate_root_block.md` — design.css has TWO :root blocks (line ~9 + ~31960). Token edits must update BOTH or the duplicate wins by source order.
