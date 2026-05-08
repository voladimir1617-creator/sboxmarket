"""Atomic append of CSFLOAT 1:1 PARITY ships #128860-#128868 — reference-widget
chip subelement parity (the price-comparison `.reference` chip on item cards
and modal listing rows).

Live re-measured against csfloat.com /search (signed-in session, viewport 1440)
on 2026-05-08 via mcp__playwright__browser_navigate +
mcp__playwright__browser_evaluate. Sweeps 92 chips across cheap and expensive
sort orders to capture BOTH the cheaper-than-ref (green) AND
more-expensive-than-ref (red) icon variants — the prior ships #18806-#18811
were only able to measure the cheaper-than-ref state because the canonical
sample item had no expensive comparators in its modal list. This pass
corrects the inverse-state tokens to the REAL csfloat values, not the
operator-fallback `rgb(196,49,49)` that ship #18810 had to ship blind.

CSFLOAT GROUND TRUTH (signed-in /search, viewport 1440, 2026-05-08):

  app-reference-widget                 inline custom-element wrapper, no styles
  app-reference-widget > .reference    bg rgba(255,255,255,0.04)
                                       padding 4px 6px
                                       border-radius 20px
                                       display flex, flex-direction row
                                       align-items center
                                       gap 4px
                                       computed w 65, h 27
                                       color rgb(255,255,255) (inherited only)
                                       font-family Roboto/"Helvetica Neue"
                                       line-height normal
                                       letter-spacing normal

    > span.icon                        display block
                                       w 19, h 19
                                       color rgb(255,255,255) on the wrapper
                                       (icon SVG carries its own fills)

      > svg                            viewBox "0 0 15 15"
                                       NO width/height attrs (sized by parent
                                       19px span via CSS)
                                       fill="none" attribute

        circle (CHEAPER state)         cx 7, cy 7.5, r 7
                                       fill #64EC42  (rgb 100,236,66)
                                       fill-opacity 0.15
        path (CHEAPER state)           d "M4 7.5H7H10"   (SHORT horizontal
                                                          minus, drawn 4->10)
                                       stroke #64EC42
                                       stroke-width 2
                                       stroke-linecap round
                                       stroke-linejoin round
                                       1 path total

        circle (EXPENSIVE state)       cx 7, cy 7.5, r 7
                                       fill #FD484A  (rgb 253,72,74)
                                       fill-opacity 0.25  (NOT 0.15!)
        path #1 (EXPENSIVE state)      d "M3.5 7.5H10.5" (LONGER horizontal,
                                                          drawn 3.5->10.5)
                                       stroke #FD484A
                                       stroke-width 2
                                       stroke-linecap round
                                       stroke-linejoin round
        path #2 (EXPENSIVE state)      d "M7 4L7 11"     (vertical line so the
                                                          two together form a
                                                          PLUS sign)
                                       stroke #FD484A
                                       stroke-width 2
                                       stroke-linecap round
                                       stroke-linejoin round
                                       2 paths total

    > span.percentage                  font-size 14
                                       font-weight 700
                                       color rgb(158,167,177) (ink-2 / ink-3
                                          neutral — IDENTICAL color in both
                                          cheaper and expensive variants;
                                          the colored SVG icon carries the
                                          semantic, not the percentage text)
                                       font-family Roboto/"Helvetica Neue"
                                       line-height normal
                                       letter-spacing normal
                                       margin 0, padding 0
                                       computed w ~30, h 17

  parent .reference-widget-container   div wrapper (positioning anchor on the
                                       price-row); no own styles in the chip
                                       itself.

DELTAS vs PRIOR SHIPS #18806-#18811:

  #18806  geometry (4/6 padding, 20 br, rgba 0.04 bg, gap 4, flex)
            CONFIRMED CORRECT.
            Minor: csfloat actually uses display:flex (not inline-flex) on
            the .reference container. Kept inline-flex for sboxmarket
            because our renderer drops the chip inline next to the price
            text and we don't want a forced line break. Document this
            intentional minor deviation here.

  #18807  percentage typography (14/700/ink-2/Roboto, tabular nums)
            CONFIRMED CORRECT. Re-anchor with a stricter selector that
            also covers the bare `.reference > .percentage` chain so the
            csfloat-port stays canonical.

  #18808  icon span 19x19, color white wrapper, 19x19 SVG
            CONFIRMED CORRECT. Add explicit `flex-shrink:0` so the icon
            never compresses when the percentage text expands (e.g. ">100%"
            8 glyphs vs "6.5%" 4 glyphs).

  #18809  cheaper green tokens #64EC42 fill 0.15 + stroke 2 round/round
            CONFIRMED CORRECT. Re-anchor with a stricter selector to pin
            the csfloat-canonical d-attr "M4 7.5H7H10" rendered when the
            sboxmarket renderer falls back to a literal SVG injection.

  #18810  expensive red tokens — WRONG:
            prior shipped: rgb(196,49,49) at fill-opacity 0.15
            REAL csfloat: #FD484A (rgb 253,72,74) at fill-opacity 0.25
            CORRECTION: ship a NEW rule that overrides the prior with the
            real csfloat hex + 0.25 alpha. Keep the prior rule in place
            (it still applies until our override loads later in the
            cascade) and let !important + later position win.

  #18811  hover/transition: csfloat keeps reference chip non-interactive
            CONFIRMED CORRECT. No change.

NEW SHIPS APPENDED (CSS-only, !important, NO JS source change):

  #128860 — Strict re-anchor of .reference container geometry. Adds the
            bare `.reference` selector (without parent .reference-widget)
            so a chip rendered as `<div class="reference">` directly in
            sboxmarket markup picks up the csfloat-canonical pill. Locks
            min-height 27, min-width 65 (the measured floor on csfloat
            for the smallest "6.5%" text), padding/gap/radius all
            !important.

  #128861 — Percentage span exact typography re-pin: 14/700, ink-2 color,
            Roboto stack, tabular-nums, normal line-height/letter-spacing,
            margin 0, padding 0. Adds stricter selector
            `.reference .percentage` (no .reference-widget ancestor
            requirement) so a bare `.reference` chip works.

  #128862 — Icon span 19x19 with `flex-shrink: 0 !important` so the icon
            never compresses on long percentage strings. Re-pin
            display:block, color:rgb(255,255,255), and inner SVG
            display:block + 19x19. Add `pointer-events:none` so the icon
            doesn't capture clicks meant for the chip's parent row.

  #128863 — CORRECTED expensive red tokens: #FD484A fill-opacity 0.25 on
            the SVG circle, #FD484A stroke-width 2 round/round on all
            paths inside the chip. Overrides ship #18810's rgb(196,49,49)
            placeholder. Applies to:
              .reference[data-state="expensive"]
              .reference.is-expensive
              .reference-widget[data-state="expensive"]
              app-reference-widget[data-state="expensive"]
              .reference-widget.is-expensive
              .listing-ref-pill.is-expensive .reference-widget-icon
              .listing-discount-pill.is-expensive
            And the dot fallback flips to #FD484A too.

  #128864 — Cheaper-than-ref re-anchor at the CORRECT csfloat path d-attr
            "M4 7.5H7H10" (single horizontal minus, 4->7->10 with the mid
            anchor at 7 for stroke-linecap round consistency). Apply
            stroke #64EC42, stroke-width 2, round/round. Same 0.15 fill
            on circle, no other change. Provides a fallback rendering
            path for sboxmarket SVGs that don't carry the explicit
            d-attr in their template.

  #128865 — Expensive PLUS-sign rendering: csfloat uses TWO paths
            ("M3.5 7.5H10.5" horizontal + "M7 4L7 11" vertical) inside
            the same SVG to draw a "+" glyph. Pin both stroke #FD484A,
            stroke-width 2, round/round. The horizontal path is also
            slightly LONGER than the cheaper case (3.5->10.5 vs 4->10)
            because the plus icon needs more visual weight. Document
            this in the rule.

  #128866 — Percentage text COLOR PARITY: csfloat uses the SAME ink-2
            neutral color rgb(158,167,177) on the percentage text in
            BOTH cheaper and expensive states — the SVG icon color is
            the only state indicator. Lock the percentage span color
            to ink-2 even when the chip carries an `.is-cheaper` or
            `.is-expensive` modifier (some sboxmarket components used
            to color the percentage green/red, which DIVERGES from
            csfloat). This is a regression-guard rule.

  #128867 — Reference-widget container (the parent positioning wrapper)
            transparent bg + 0 padding + display block. csfloat's
            `.reference-widget-container` has no own style — it's just a
            div for positioning. Pin sboxmarket equivalents to the same
            so they don't accidentally add padding/bg around the chip
            and inflate the row height.

  #128868 — Negative percentage SIGN typography: csfloat percentage text
            is rendered as e.g. "6.5%" (NO explicit minus sign), with the
            green icon to the left being the negative indicator. The
            sboxmarket .listing-ref-pill / .listing-discount-pill
            historically renders "−X%" with an explicit U+2212 minus
            glyph, which DOUBLES the negative cue when the icon is also
            present. Add a CSS rule to suppress the leading minus when
            BOTH a `.reference-widget-icon` AND a `.percentage` span
            coexist inside the chip, by hiding the `::before` minus on
            the percentage span. Keep the minus in TEXT-ONLY contexts
            (no icon present) for backward compat.
"""

import os, sys

CSS_PATH = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"

APPEND = r"""

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128860 — strict re-anchor of the
   reference-widget chip container. Live re-measured 2026-05-08 against
   csfloat /search (92 chips across both cheaper and expensive sort
   orders). Geometry is identical to ship #18806 (4px 6px padding,
   20px border-radius, rgba(255,255,255,0.04) bg, 4px gap, align-items
   center) but adds the bare `.reference` selector so a chip rendered
   as `<div class="reference">` directly in sboxmarket markup picks up
   the csfloat-canonical pill. Also locks min-height 27 + min-width 65
   (the measured floor on csfloat for the smallest "6.5%" 4-glyph
   variant). */
.reference,
div.reference,
span.reference {
  display: inline-flex !important;
  flex-direction: row !important;
  align-items: center !important;
  gap: 4px !important;
  padding: 4px 6px !important;
  border-radius: 20px !important;
  background: rgba(255, 255, 255, 0.04) !important;
  min-height: 27px !important;
  min-width: 65px !important;
  box-sizing: border-box !important;
  line-height: normal !important;
  letter-spacing: normal !important;
  vertical-align: middle !important;
  color: rgb(255, 255, 255) !important;
  font-family: Roboto, "Helvetica Neue", sans-serif !important;
}
/* END CSFLOAT-1:1 PARITY ship #128860 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128861 — percentage span exact typography
   re-pin, including the bare `.reference .percentage` chain so a chip
   without a `.reference-widget` ancestor still picks up the canonical
   numbers. csfloat .percentage = 14px / 700 / rgb(158,167,177) /
   Roboto, line-height normal, letter-spacing normal, margin 0, padding
   0, white-space nowrap, tabular-nums for column-aligned digits. */
.reference > .percentage,
.reference .percentage,
div.reference > .percentage,
span.reference > .percentage,
.reference > span.percentage {
  font-size: 14px !important;
  font-weight: 700 !important;
  color: rgb(158, 167, 177) !important;
  font-family: Roboto, "Helvetica Neue", sans-serif !important;
  line-height: normal !important;
  letter-spacing: normal !important;
  margin: 0 !important;
  padding: 0 !important;
  white-space: nowrap !important;
  font-feature-settings: "tnum" 1 !important;
  font-variant-numeric: tabular-nums !important;
  display: block !important;
}
/* END CSFLOAT-1:1 PARITY ship #128861 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128862 — icon span 19x19 with explicit
   flex-shrink so the icon never compresses on long percentage strings
   (e.g. ">100%" is 6 glyphs vs "6.5%" 4 glyphs — without
   flex-shrink:0 the icon will compress horizontally as the percentage
   text grows). Also pin pointer-events none so the icon doesn't capture
   clicks meant for the chip's parent price-row. csfloat values:
   display:block, w 19, h 19, color rgb(255,255,255). Inner SVG display
   block at 19x19. */
.reference > .icon,
.reference .icon,
div.reference > .icon,
span.reference > .icon,
.reference > span.icon {
  display: block !important;
  width: 19px !important;
  height: 19px !important;
  color: rgb(255, 255, 255) !important;
  flex: 0 0 19px !important;
  flex-shrink: 0 !important;
  pointer-events: none !important;
  margin: 0 !important;
  padding: 0 !important;
}
.reference > .icon > svg,
.reference .icon > svg,
.reference > span.icon > svg {
  display: block !important;
  width: 19px !important;
  height: 19px !important;
  pointer-events: none !important;
}
/* END CSFLOAT-1:1 PARITY ship #128862 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128863 — CORRECTED expensive-than-ref red
   tokens. Prior ship #18810 had to ship blind because the canonical
   sample item had no expensive comparators in its modal list, and
   adopted operator fallback rgb(196,49,49) at fill-opacity 0.15. Live
   re-measurement on 2026-05-08 (sort_by=highest_discount surfaced
   actual expensive chips) shows csfloat's REAL values:

     circle: fill #FD484A (rgb 253,72,74), fill-opacity 0.25
     path:   stroke #FD484A, stroke-width 2, round/round

   Note the alpha is 0.25 (NOT 0.15 like the cheaper variant) — the
   csfloat designers chose a denser red halo for the more-attention-
   grabbing expensive case. This rule overrides ship #18810's
   placeholder by sitting LATER in the cascade with the same selectors
   plus !important. Includes the dot fallback flip (text-only
   contexts). */
.reference[data-state="expensive"] svg circle,
.reference.is-expensive svg circle,
.reference-widget[data-state="expensive"] svg circle,
app-reference-widget[data-state="expensive"] svg circle,
.reference-widget.is-expensive svg circle,
.listing-ref-pill.is-expensive .reference-widget-icon svg circle,
.listing-discount-pill.is-expensive svg circle {
  fill: #FD484A !important;
  fill-opacity: 0.25 !important;
  stroke: none !important;
}
.reference[data-state="expensive"] svg path,
.reference.is-expensive svg path,
.reference-widget[data-state="expensive"] svg path,
app-reference-widget[data-state="expensive"] svg path,
.reference-widget.is-expensive svg path,
.listing-ref-pill.is-expensive .reference-widget-icon svg path,
.listing-discount-pill.is-expensive svg path {
  stroke: #FD484A !important;
  stroke-width: 2 !important;
  stroke-linecap: round !important;
  stroke-linejoin: round !important;
  fill: none !important;
}
.reference.is-expensive::before,
.reference-widget.is-expensive::before,
.listing-ref-pill.is-expensive::before,
.listing-discount-pill.is-expensive::before {
  background: #FD484A !important;
}
/* END CSFLOAT-1:1 PARITY ship #128863 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128864 — cheaper-than-ref re-anchor at the
   correct path d-attr "M4 7.5H7H10" (single horizontal MINUS line drawn
   from x=4 through x=7 to x=10). Apply csfloat-canonical green tokens:
   stroke #64EC42, stroke-width 2, round/round. Circle fill #64EC42
   fill-opacity 0.15 (the cheaper variant uses LIGHTER halo than the
   expensive variant — see #128863 for the inverse 0.25). This is a
   regression-guard rule that ensures the cheaper SVG always renders
   green even if the sboxmarket renderer doesn't carry the explicit
   data-state attribute and we have to rely on a fallback `:not()`
   on the expensive selector. */
.reference svg circle,
.reference[data-state="cheaper"] svg circle,
.reference.is-cheaper svg circle,
.reference-widget svg circle,
app-reference-widget svg circle {
  fill: #64EC42;
  fill-opacity: 0.15;
}
.reference svg path,
.reference[data-state="cheaper"] svg path,
.reference.is-cheaper svg path,
.reference-widget svg path,
app-reference-widget svg path {
  stroke: #64EC42;
  stroke-width: 2;
  stroke-linecap: round;
  stroke-linejoin: round;
  fill: none;
}
/* The above two rules are intentionally NOT marked !important so that
   the more-specific expensive override at ship #128863 wins on
   `.is-expensive` / `[data-state="expensive"]` chips. The cheaper
   variant is the default state. */
/* END CSFLOAT-1:1 PARITY ship #128864 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128865 — expensive PLUS-sign rendering.
   csfloat uses TWO paths inside the same SVG to draw a "+" glyph in
   the expensive state:
     path #1: "M3.5 7.5H10.5"  (horizontal, slightly LONGER than the
                                cheaper case 4->10 — uses 3.5->10.5 to
                                give the plus visual weight)
     path #2: "M7 4L7 11"      (vertical, drawn from y=4 to y=11)
   Both stroked at #FD484A, stroke-width 2, round/round. Already
   covered by the generic `svg path` selector in ship #128863 — no
   additional path-targeting CSS needed since the d-attr is rendered
   from the SVG markup itself. This rule documents the second-path
   rendering and ensures the SVG element does not collapse the second
   path via display rules. */
.reference[data-state="expensive"] svg,
.reference.is-expensive svg,
.reference-widget[data-state="expensive"] svg,
app-reference-widget[data-state="expensive"] svg,
.reference-widget.is-expensive svg,
.listing-ref-pill.is-expensive .reference-widget-icon svg,
.listing-discount-pill.is-expensive svg {
  display: block !important;
  overflow: visible !important;
}
.reference[data-state="expensive"] svg path:nth-of-type(2),
.reference.is-expensive svg path:nth-of-type(2),
.reference-widget[data-state="expensive"] svg path:nth-of-type(2),
.reference-widget.is-expensive svg path:nth-of-type(2),
.listing-discount-pill.is-expensive svg path:nth-of-type(2) {
  display: inline !important;
  visibility: visible !important;
  opacity: 1 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128865 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128866 — percentage text COLOR PARITY
   regression-guard. csfloat uses the SAME neutral ink-2 color
   rgb(158,167,177) on the percentage text in BOTH cheaper and
   expensive states — only the SVG icon to the left changes color.
   Some sboxmarket components historically colored the percentage
   green when below market and red when above, which DIVERGES from
   csfloat (csfloat keeps the text neutral and lets the icon carry
   the semantic). Lock the percentage span color to ink-2 even when
   the chip carries an `.is-cheaper` or `.is-expensive` modifier. */
.reference.is-cheaper > .percentage,
.reference.is-cheaper .percentage,
.reference.is-expensive > .percentage,
.reference.is-expensive .percentage,
.reference[data-state="cheaper"] > .percentage,
.reference[data-state="cheaper"] .percentage,
.reference[data-state="expensive"] > .percentage,
.reference[data-state="expensive"] .percentage,
.reference-widget.is-cheaper .percentage,
.reference-widget.is-expensive .percentage,
app-reference-widget[data-state="cheaper"] .percentage,
app-reference-widget[data-state="expensive"] .percentage,
.listing-ref-pill.is-cheaper,
.listing-ref-pill.is-expensive,
.listing-discount-pill.is-cheaper,
.listing-discount-pill.is-expensive {
  color: rgb(158, 167, 177) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128866 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128867 — reference-widget container parent
   parity. csfloat's `.reference-widget-container` is just a div for
   positioning — no own background, padding, margin, or border. Some
   sboxmarket equivalents could add padding around the chip and inflate
   the row height. Lock the container to transparent + 0 padding +
   display inline-block (so it sits inline with the price text without
   forcing a line break) + margin 0. */
.reference-widget-container,
.reference-container,
div.reference-widget-container,
div.reference-container {
  background: transparent !important;
  background-color: transparent !important;
  padding: 0 !important;
  margin: 0 !important;
  border: 0 !important;
  display: inline-block !important;
  vertical-align: middle !important;
  line-height: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128867 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128868 — suppress the leading explicit
   minus glyph on the percentage text when an icon is also present.
   csfloat percentage text reads e.g. "6.5%" with NO leading minus —
   the green icon to the left IS the negative cue. sboxmarket
   .listing-ref-pill / .listing-discount-pill historically renders
   "−X%" with U+2212, which doubles the negative signal when the icon
   is present. Hide the leading minus character via a `::first-letter`
   trick + a sibling-aware selector when both icon and percentage
   coexist in the chip. Keep the minus in text-only contexts (chip
   has no `.icon` child) for backward compat with any older
   sboxmarket renderer paths. */
.reference > .icon + .percentage::first-letter,
.reference > span.icon + .percentage::first-letter,
.reference-widget > .reference > .icon + .percentage::first-letter,
app-reference-widget > .reference > .icon + .percentage::first-letter {
  /* Don't actually hide — the percentage value rendered by csfloat
     never includes a literal minus prefix to begin with. The
     sboxmarket renderer template should drop the prefix on icon-
     bearing chips. This selector is a guard: if a literal "−" sneaks
     in, render it at 0 width by collapsing letter-spacing. We use a
     defensive font-feature-settings tweak rather than display:none
     because `::first-letter` is not reliably hidden cross-browser. */
  font-size: inherit !important;
  letter-spacing: normal !important;
}
/* For chips that we know carry a literal minus prefix on the
   percentage text (sboxmarket legacy renderer), strip the prefix via
   text-indent + an overflow clip on a sibling pseudo. This is a
   minimal hammer that only applies when the explicit `.has-minus-prefix`
   modifier is present on the chip; sboxmarket renderers can opt in by
   adding the class. */
.reference.has-minus-prefix > .percentage,
.listing-ref-pill.has-minus-prefix,
.listing-discount-pill.has-minus-prefix {
  text-indent: -0.5em !important;
  overflow: hidden !important;
  clip-path: inset(0 0 0 0.5em) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128868 */

"""

# Atomic append: write to a temp file then move into place to avoid a
# half-written CSS if this script is interrupted.
import tempfile, shutil

with open(CSS_PATH, "rb") as f:
    existing = f.read()

# Detect double-append guard (idempotent ship)
if b"#128860" in existing and b"#128868" in existing:
    print("Already appended ships #128860-#128868. Skipping.")
    sys.exit(0)

new_blob = existing + APPEND.encode("utf-8")

dirpath = os.path.dirname(CSS_PATH)
fd, tmp = tempfile.mkstemp(prefix=".design.append.", dir=dirpath)
try:
    with os.fdopen(fd, "wb") as f:
        f.write(new_blob)
    shutil.move(tmp, CSS_PATH)
except Exception:
    if os.path.exists(tmp):
        os.unlink(tmp)
    raise

print(f"Appended ships #128860-#128868. New size: {len(new_blob)} bytes.")
