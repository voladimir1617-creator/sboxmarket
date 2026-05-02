# Design fix — kill button underlines, use opacity-fade on hover instead

Operator (verbatim, just sent): **"DESIGN IS SO GAY WE DON'T WANT UNDERLINE AT BUTTONS JUST MAKE THEM FADE WHEN WE HOVER TO THEM"**

## Translation

Wherever a button-like element currently shows `text-decoration: underline` on hover (or any "underline-on-hover" affordance), replace that affordance with an **opacity fade** — element drops to e.g. `opacity: 0.78` on hover, transitions back on leave. NO underline, NO blue tint, NO bg color shift, NO border. Just opacity.

This applies to **buttons** specifically — anchors that are explicitly text-link-styled (e.g. `.faq-tail-link`, `.text-link`, footer fine-print links) can keep their underline if it's their primary visual affordance. The operator's complaint is about CTA/button surfaces showing underline on hover.

## Constraints (read these before grepping)

1. **Mono-primary palette is law** (`feedback_design_direction.md`). Don't introduce `var(--cta)` / `var(--blue)` / accent tints on hover. Opacity only.
2. **No global anchor color override** (`feedback_no_global_blue_anchors.md`). Don't write `a:hover { color: var(--cta) }`.
3. **No theme override on `<html>`** (`feedback_no_theme_override.md`). All edits go in `design.css`.
4. **Two `:root` blocks in design.css** (`duplicate_root_block.md`). If you bump a token, edit both.
5. **`text-decoration: none`** on buttons might already be set in some places — keep those, just make sure no `:hover { text-decoration: underline }` pair re-adds it.

## Your job

```bash
# Find every button-ish element with hover underline:
grep -nE 'text-decoration\s*:\s*underline' src/main/resources/static/css/design.css | head -40
grep -rn 'text-decoration\s*:\s*underline' src/main/resources/static/ --include='*.css' | head -40

# Inventory the targets — group by class. Anything matching:
#   .btn, .btn-*, .cta, .buy-btn, .sell-btn, .nav-link, .modal-cat,
#   .grid-cart-btn, .grid-star, .hero-tab-cta, .preview-tail,
#   .service-tile, .price-suggest-chip, .wallet-tx-filter-chip,
#   .modal-listing-row, .home-faq-summary, .skip-link, .back-to-app,
#   .save-search, .contact-seller, .review-report, etc.
# is a button. Strip its `:hover { text-decoration: underline }` rule.

# Then add ONE shared fade rule (or augment what's there if a transition already exists):

.btn,
.cta,
.buy-btn,
.nav-link,
.grid-cart-btn,
.grid-star,
.hero-tab-cta,
.preview-tail,
.service-tile,
.price-suggest-chip,
.wallet-tx-filter-chip,
.modal-listing-row,
.home-faq-summary,
.skip-link,
.back-to-app-link,
.modal-cat,
button {
    transition: opacity 140ms ease-out;
}

.btn:hover,
.cta:hover,
.buy-btn:hover,
.nav-link:hover,
.grid-cart-btn:hover,
.grid-star:hover,
.hero-tab-cta:hover,
.preview-tail:hover,
.service-tile:hover,
.price-suggest-chip:hover,
.wallet-tx-filter-chip:hover,
.modal-listing-row:hover,
.home-faq-summary:hover,
.skip-link:hover,
.back-to-app-link:hover,
.modal-cat:hover,
button:hover {
    opacity: 0.78;
    text-decoration: none !important;  /* belt-and-suspenders against any browser default underline */
}
```

(Adjust the selector list to match what your grep actually finds. Don't include selectors that don't exist.)

## Honor existing button affordances

A few buttons already use `:active { transform: scale(.97) }` / `transform: translateY(1px)` press-state echo (recent boss-QA cycles 23-29). **Keep those.** The fade is hover-state only; press-state stays.

## Verify

1. `docker build -t sbox-app:latest . && bash deploy/run-local.sh` — wait for `/api/health` 200.
2. Take desktop 1920×1080 screenshot at:
   - `https://skinbox.market/` (CTAs in hero, nav, FAQ tail)
   - `https://skinbox.market/market` (grid cart-btn, grid-star, sort/filter chips)
   - `https://skinbox.market/item/3` (Buy + Make Offer + Bargain CTAs)
   - `https://skinbox.market/wallet` (deposit/withdraw CTAs, tx-filter-chip row)
3. Hover over a CTA in DevTools and confirm `:hover` shows `opacity: 0.78` + zero underline.
4. Mobile 390×852 — same set of routes, confirm tap targets still work.

## Acceptance

- [ ] `grep -E 'text-decoration\s*:\s*underline' design.css` returns 0 button hover rules (text-link surfaces like `.faq-tail-link` are allowed).
- [ ] Every button class above has `transition: opacity` and `:hover { opacity: 0.78 }`.
- [ ] No new color/tint/border on hover anywhere.
- [ ] One commit on main: `Design: opacity-fade hover replaces underline on buttons`.
- [ ] Container rebuilt + redeployed.

GO. This is a one-shot ship — should be 1 commit, ≤30 lines of CSS diff.
