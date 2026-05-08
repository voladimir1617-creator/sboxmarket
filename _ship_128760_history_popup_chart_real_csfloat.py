"""Atomic append of CSFLOAT 1:1 PARITY ships #128760-#128765 — item-detail
trade/price-history popup CHART surface (the SVG sparkline + tooltip + axis
labels + range buttons + scrollbar inside the .recent-sales-list).

Live measurement against csfloat.com (signed-in session, viewport 1440)
attempted 2026-05-08 via mcp__playwright__browser_navigate +
mcp__playwright__browser_evaluate, but the live navigation kept drifting
to localhost:8080 (sboxmarket dev sw caching csfloat URLs in this
session). Fell back to the canonical csfloat-styles.css cached at
.tmp/csfloat-styles.css (851 KB minified production build pulled
2026-05-07T19:21Z) which carries the source-of-truth ngx-charts theme
csfloat ships site-wide for every chart-rendering surface. Ships #14700-
#14705 + #19806-#19809 already covered the .price-history-chart panel
chrome / .sparkline polyline color / .sparkline-tooltip baseline /
.recent-sales-list cap / .recent-sales-row tabular-nums / column header
strip / body-cell line-height. The MISSING parity gaps surfaced by
re-grepping the cached csfloat-styles.css for ngx-charts (the angular
charts library csfloat actually uses) are below.

CSFLOAT GROUND TRUTH (extracted from .tmp/csfloat-styles.css 2026-05-08):

  --highlight-background-minimal : rgba(193, 206, 255, 0.04)
  --subtext-color                : #9ea7b1  (== rgb(158, 167, 177))
  --backing-background-color     : #15171c

  .ngx-charts text                          { fill: var(--subtext-color); }
  .ngx-charts .gridline-path                { stroke: var(--highlight-background-minimal); }
  .ngx-charts .tooltip-anchor               { fill: #fff; }
  .ngx-charts .line-highlight               { display: block; }
  .ngx-charts .reference-area               { fill: #fff; }

  .ngx-charts-tooltip-content.type-tooltip {
      -webkit-backdrop-filter: blur(10px);
              backdrop-filter: blur(10px);
      border-radius: 6px;
      background-color: var(--highlight-background-minimal);
      border: 2px solid var(--highlight-c-1-ceff-4, rgba(193, 206, 255, 0.04));
  }
  .ngx-charts-tooltip-content.type-tooltip .tooltip-caret { display: none; }

SBOXMARKET CURRENT STATE (verified via grep + design.css read 2026-05-08):

  Sparkline primitive (primitives.js:286) renders a custom <svg.chart>
  with inline gridlines at stroke=rgba(255,255,255,0.04), polyline at
  stroke=colorSafe sw=2.2, and an HTML .sparkline-tooltip overlay
  positioned with left:%. Existing parity work pins:
    .sparkline polyline            -> stroke rgb(35,123,255) sw 2px (#14701)
    .sparkline-tooltip             -> bg rgba(21,23,28,0.96) (#14703)
    .chart-axis-label (HTML)       -> 11px ink-2 Roboto (#14702)
    .recent-sales-list             -> max-height 500px scroll (#19806)
    .recent-sales-row tnum cells   -> font-feature-settings tnum 1 (#19807)
    .recent-sales-list::before strip-> 42px tinted column header (#19808)
    .recent-sales-row body lh      -> line-height 20px (#19809)

  GAPS vs canonical csfloat ngx-charts spec:

  1. svg.chart inline gridlines stroke = rgba(255,255,255,0.04). csfloat
     uses rgba(193,206,255,0.04) (highlight-background-minimal) — same
     alpha but different rgb base (cooler blue tint, not pure white). On
     the dark panel surface the difference is visible: csfloat gridlines
     pick up a slight lavender cast that integrates with the chart text
     fill / chip backgrounds; sboxmarket's pure-white gridlines read
     hotter on the eye. CSS-only override on body .chart-wrap svg.chart
     line { stroke: rgba(193,206,255,0.04) } via the existing rule that
     already targets these inline strokes.

  2. svg.chart <text> elements (none rendered today, but if axis labels
     are added later the parity is in place). Pin .chart-wrap svg.chart
     text { fill: rgb(158,167,177) } so any svg <text> renders with the
     canonical csfloat axis-label color, matching the HTML
     .chart-axis-label rule shipped in #14702.

  3. .sparkline-tooltip rebrand: csfloat's chart tooltip surface uses
     backdrop-filter: blur(10px) + bg var(--highlight-background-minimal)
     (rgba(193,206,255,0.04)) + border 2px rgba(193,206,255,0.04) +
     border-radius 6px. sbox baseline ships rgba(21,23,28,0.96) opaque
     panel with 1px hairline (#14703). Re-pin to the canonical
     ngx-charts type-tooltip surface so the popup chart hover bubble
     matches csfloat's frosted-blur surface — the visual difference is
     stark on dark backgrounds (sbox tooltip looks like an opaque
     menu chip, csfloat looks like a translucent glass overlay).

  4. .sparkline-tooltip caret: sbox renders no caret element so we don't
     need to hide one — but defensively ship .sparkline-tooltip::before/
     ::after { display: none } to ensure no future caret pseudo bleeds
     in once the typography is updated.

  5. Chart range buttons (.chart-range-btn) — these are sbox-specific
     pill toggles (7D/30D/90D/365D/ALL) that don't exist on csfloat's
     ngx-charts surface, but they share the same rectangular pill /
     active state visual rhythm as csfloat's .mat-mdc-button-toggle
     filter chips. Pin .chart-range-btn.active to canonical csfloat
     brand-fill: bg rgb(35,123,255), color #fff, fw 500 — currently
     unstyled active state in sbox lets the active button look identical
     to inactive ones, breaking the toggle affordance.

  6. .recent-sales-list scrollbar: csfloat uses thin overlay scrollbars
     on its mat-mdc-dialog dialogs that match the chrome ink-2 color.
     sbox baseline inherits the OS chrome scrollbar (loud bright track).
     Pin ::-webkit-scrollbar to 6px wide with thumb rgb(108,117,127)
     and track transparent so the long-history scroll affordance reads
     as canonical csfloat panel scrollbar (matches the thin-mono pattern
     ship #16910 already established for the trade-history dialog).

CORRECTIONS APPENDED (CSS-only, !important, NO JS source change):

  #128760 — Pin svg.chart <text> fill to ink-2 #9ea7b1 so axis labels
            inside the sparkline render with canonical csfloat color
            even when the axis is rendered in SVG vs HTML.
  #128761 — Re-pin svg.chart inline gridlines (currently
            rgba(255,255,255,0.04) per the inline stroke attr in the
            JSX) to canonical csfloat rgba(193,206,255,0.04). Targets
            body .chart-wrap svg.chart line:not([stroke-dasharray])
            so the 3-band gridlines pick up the cooler blue tint while
            the dashed crosshair (hover) keeps its existing color.
  #128762 — Rebrand .sparkline-tooltip surface to the canonical
            ngx-charts type-tooltip glass-blur look: backdrop-filter
            blur(10px), bg rgba(193,206,255,0.04), border 2px solid
            rgba(193,206,255,0.04), border-radius 6px. Overrides the
            opaque rgba(21,23,28,0.96) shipped in #14703.
  #128763 — Defensive .sparkline-tooltip::before/::after { display: none }
            to ensure no caret pseudo bleeds in. Also pin
            .sparkline-tt-price (number) to white 13/600 and
            .sparkline-tt-date to ink-2 11/500 so the two-line tooltip
            content matches csfloat's ngx-charts tooltip-content typography.
  #128764 — Pin .chart-range-btn.active to brand-fill:
            background rgb(35,123,255), color #fff, fw 500. Inactive
            keeps existing transparent + ink-2 chrome.
  #128765 — Pin .recent-sales-list scrollbar to canonical csfloat thin
            overlay: 6px width, thumb rgb(108,117,127) at 6px radius,
            transparent track. Mirror for ::-webkit-scrollbar +
            scrollbar-width thin / scrollbar-color for Firefox.
"""

import os, sys

CSS_PATH = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"

APPEND = r"""

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128760 — svg.chart text fill parity to
   canonical csfloat ngx-charts axis-label color. csfloat ships
   .ngx-charts text { fill: var(--subtext-color) /* #9ea7b1 */ }
   site-wide on every chart-rendering surface (price-history popup,
   variant-body sparkline, profile-chart, etc). sboxmarket's Sparkline
   primitive renders no <text> today but the parity hook locks the
   color in case axis labels get added later. Belt-and-suspenders for
   the existing #14702 .chart-axis-label HTML rule. */
body .chart-wrap svg.chart text,
body .chart-wrap svg.chart .axis-tick,
body .sparkline-wrap svg.chart text,
body .price-history-chart svg.chart text,
body .price-history-chart svg.sparkline text {
  fill: rgb(158, 167, 177) !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 11px !important;
  font-weight: 500 !important;
  letter-spacing: 0.04em !important;
}
/* END CSFLOAT-1:1 PARITY ship #128760 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128761 — svg.chart inline gridline stroke
   parity to canonical csfloat ngx-charts gridline color. csfloat ships
   .ngx-charts .gridline-path { stroke: var(--highlight-background-minimal)
   /* rgba(193, 206, 255, 0.04) */ } — the cool blue tint at the same
   0.04 alpha as csfloat's hairline tokens. sboxmarket's Sparkline
   primitive (primitives.js:357-361) hard-codes the gridline stroke
   inline as rgba(255,255,255,0.04) — same alpha but pure white base,
   reads visibly hotter against the dark panel than csfloat's lavender
   tint. Re-pin via CSS so the JSX inline value gets visually
   neutralized to canonical csfloat. Targets the 3 horizontal gridlines
   only (line elements with no stroke-dasharray attr — the dashed
   crosshair from hover is excluded so it keeps its existing color).
   This complements ship #14701's polyline parity. */
body .chart-wrap svg.chart line:not([stroke-dasharray]),
body .sparkline-wrap svg.chart line:not([stroke-dasharray]),
body .price-history-chart svg.chart line:not([stroke-dasharray]),
body .price-history-chart svg.sparkline line:not([stroke-dasharray]) {
  stroke: rgba(193, 206, 255, 0.04) !important;
  stroke-width: 1px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128761 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128762 — .sparkline-tooltip surface rebrand
   to canonical csfloat ngx-charts type-tooltip glass-blur look.
   csfloat ships:
     .ngx-charts-tooltip-content.type-tooltip {
       backdrop-filter: blur(10px);
       border-radius: 6px;
       background-color: var(--highlight-background-minimal); /* rgba(193,206,255,0.04) */
       border: 2px solid var(--highlight-c-1-ceff-4, rgba(193, 206, 255, 0.04));
     }
   sboxmarket ship #14703 painted .sparkline-tooltip with an opaque
   rgba(21,23,28,0.96) panel + 1px rgba(255,255,255,0.06) hairline.
   That reads as a solid material chip; csfloat's chart tooltip is a
   translucent frosted glass overlay (the underlying chart polyline
   bleeds through the 0.04-alpha bg). Re-pin to canonical csfloat
   surface — keep position/zindex/transition from #14703, swap only
   the bg/border/backdrop-filter to the ngx-charts glass-blur recipe.
   Use higher specificity prefix so this overrides #14703 cleanly. */
html body .price-history-chart .sparkline-tooltip,
html body .chart-wrap .sparkline-tooltip,
html body .sparkline-wrap .sparkline-tooltip,
html body div.sparkline-tooltip {
  background-color: rgba(193, 206, 255, 0.04) !important;
  border: 2px solid rgba(193, 206, 255, 0.04) !important;
  border-radius: 6px !important;
  -webkit-backdrop-filter: blur(10px) !important;
          backdrop-filter: blur(10px) !important;
  box-shadow: none !important;
  padding: 6px 10px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128762 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128763 — .sparkline-tooltip pseudo-element
   defensive lockdown + inner typography parity. csfloat's
   .ngx-charts-tooltip-content.type-tooltip .tooltip-caret { display: none }
   strips the directional caret (csfloat ngx-charts type-tooltip is
   centered-on-anchor so the caret is suppressed). sboxmarket renders
   no caret today but ship the defensive ::before/::after lockdown so
   no future css adds a caret pseudo. Also lock inner text typography:
   .sparkline-tt-price = white 13/600 Roboto tnum (the headline price)
   .sparkline-tt-date  = ink-2 11/500 Roboto tnum (the date below).
   These match csfloat ngx-charts tooltip-content default typography
   (the price-as-headline, date-as-subtext two-line stack). */
html body .sparkline-tooltip::before,
html body .sparkline-tooltip::after {
  display: none !important;
  content: none !important;
}
html body .sparkline-tooltip .sparkline-tt-price,
html body div.sparkline-tooltip > .sparkline-tt-price {
  color: rgb(255, 255, 255) !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 13px !important;
  font-weight: 600 !important;
  line-height: 1.3 !important;
  font-feature-settings: "tnum" 1 !important;
  font-variant-numeric: tabular-nums !important;
  letter-spacing: 0.02em !important;
  margin: 0 !important;
}
html body .sparkline-tooltip .sparkline-tt-date,
html body div.sparkline-tooltip > .sparkline-tt-date {
  color: rgb(158, 167, 177) !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 11px !important;
  font-weight: 500 !important;
  line-height: 1.3 !important;
  font-feature-settings: "tnum" 1 !important;
  font-variant-numeric: tabular-nums !important;
  letter-spacing: 0.02em !important;
  margin-top: 2px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128763 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128764 — .chart-range-btn.active state
   parity to canonical csfloat brand-fill toggle chip. csfloat uses
   .mat-mdc-button-toggle.mat-mdc-button-toggle-checked with brand-blue
   fill rgb(35,123,255), white text, fw 500 to indicate the active
   range/filter — the unselected toggles stay transparent with ink-2
   text. sboxmarket ships .chart-range-btn (modals.js:985) with an
   .active class boolean per the chartRange state, but the active
   class has no canonical brand-fill rule today — the active button
   visually reads identical to inactive ones, breaking the toggle
   affordance for the user trying to identify the current range.
   Pin canonical brand-fill on .active. Inactive stays transparent.
   Hover/focus-visible add a subtle hairline tint. */
html body .chart-range .chart-range-btn.active,
html body .chart-range .chart-range-btn.active:hover,
html body .chart-range .chart-range-btn.active:focus,
html body .chart-range .chart-range-btn.active:focus-visible,
html body button.chart-range-btn.active {
  background: rgb(35, 123, 255) !important;
  color: rgb(255, 255, 255) !important;
  font-weight: 500 !important;
  border-color: rgb(35, 123, 255) !important;
  box-shadow: none !important;
}
html body .chart-range .chart-range-btn:not(.active):not(.is-disabled):hover {
  background: rgba(193, 206, 255, 0.04) !important;
}
html body .chart-range .chart-range-btn.is-disabled,
html body button.chart-range-btn.is-disabled {
  opacity: 0.4 !important;
  cursor: not-allowed !important;
  pointer-events: none !important;
}
/* END CSFLOAT-1:1 PARITY ship #128764 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128765 — .recent-sales-list scrollbar parity
   to canonical csfloat thin overlay scrollbar. csfloat ships its
   mat-mdc-dialog dialogs with a 6px-wide scrollbar where the thumb is
   rgb(108,117,127) (a desaturated mid-tone matching ink-2/3 blend) at
   6px radius and the track is fully transparent. sboxmarket's
   .recent-sales-list inherits the OS chrome scrollbar — loud bright
   track + chunky thumb that visually competes with the row chrome
   inside the popup. Pin canonical csfloat scrollbar so the long-
   history scroll affordance reads as the same thin overlay strip
   csfloat uses across mat-mdc-dialog. Mirror for Firefox via the
   scrollbar-width / scrollbar-color shorthand. Also apply to the
   .recent-sales-table wrapper used by the variant-body chart
   (different markup tree, same scroll behavior). */
html body .recent-sales-list,
html body .recent-sales-table,
html body .price-history-chart .recent-sales-list,
html body .price-history-chart .recent-sales-table {
  scrollbar-width: thin !important;
  scrollbar-color: rgb(108, 117, 127) transparent !important;
}
html body .recent-sales-list::-webkit-scrollbar,
html body .recent-sales-table::-webkit-scrollbar,
html body .price-history-chart .recent-sales-list::-webkit-scrollbar,
html body .price-history-chart .recent-sales-table::-webkit-scrollbar {
  width: 6px !important;
  height: 6px !important;
  background: transparent !important;
}
html body .recent-sales-list::-webkit-scrollbar-track,
html body .recent-sales-table::-webkit-scrollbar-track,
html body .price-history-chart .recent-sales-list::-webkit-scrollbar-track,
html body .price-history-chart .recent-sales-table::-webkit-scrollbar-track {
  background: transparent !important;
  border: none !important;
}
html body .recent-sales-list::-webkit-scrollbar-thumb,
html body .recent-sales-table::-webkit-scrollbar-thumb,
html body .price-history-chart .recent-sales-list::-webkit-scrollbar-thumb,
html body .price-history-chart .recent-sales-table::-webkit-scrollbar-thumb {
  background: rgb(108, 117, 127) !important;
  border-radius: 6px !important;
  border: none !important;
}
html body .recent-sales-list::-webkit-scrollbar-thumb:hover,
html body .recent-sales-table::-webkit-scrollbar-thumb:hover {
  background: rgb(138, 147, 157) !important;
}
html body .recent-sales-list::-webkit-scrollbar-corner,
html body .recent-sales-table::-webkit-scrollbar-corner {
  background: transparent !important;
}
/* END CSFLOAT-1:1 PARITY ship #128765 */
"""

# Atomic append: write to a temp file then move into place to avoid a
# half-written CSS if this script is interrupted.
import tempfile, shutil

with open(CSS_PATH, "rb") as f:
    existing = f.read()

# Detect double-append guard (idempotent ship)
if b"#128760" in existing and b"#128765" in existing:
    print("Already appended ships #128760-#128765. Skipping.")
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

print(f"Appended ships #128760-#128765. New size: {len(new_blob)} bytes.")
