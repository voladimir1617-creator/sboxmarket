"""
Ship #128800 — month-filter dropdown PANEL chrome (open state).

Real csfloat measurement (live, signed-in 1440 viewport, /loadout > Discover):
  Trigger click on .month-filter-container .mat-mdc-select-trigger spawns
  a .cdk-overlay-pane > .mat-mdc-select-panel positioned beneath the select.
  Measured panel:
     w: 169.7px, h: 157.6px, max-height: 275px, min-width: auto
     background-color: rgba(21, 23, 28, 0.8)
     border:           2px solid rgba(193, 206, 255, 0.07)
     border-radius:    0 0 15px 15px   (squared top, rounded bottom)
     box-shadow:       0 5px 5px -3px rgba(0,0,0,.2),
                       0 8px 10px 1px rgba(0,0,0,.14),
                       0 3px 14px 2px rgba(0,0,0,.12)
     padding:          8px top, 8px bottom

  Each .mat-mdc-option (mat-option) inside:
     min-height:  48px
     padding:     0 16px (L/R)
     color:       rgb(255,255,255)
     font:        16px / 400  (Roboto)
     bg (idle):   transparent
     bg (selected/checked): color(srgb 1 1 1 / 0.12)  ~ rgba(255,255,255,0.12)

Ships #128800 (panel surface), #128801 (border-radius split), #128802 (shadow stack),
       #128803 (option idle), #128804 (option selected/active), #128805 (option font).
Append-only, !important on every rule.
"""

import os, sys, time

CSS = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"

PAYLOAD = """

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128800 — .mat-mdc-select-panel surface for
   the .month-filter-container open dropdown panel. Real csfloat measure
   (live signed-in /loadout > Discover, trigger click on month dropdown):
     panel background-color rgba(21, 23, 28, 0.8) (smoked panel-1)
     panel min-width auto, max-height 275px
     panel padding 8px top + 8px bottom (mat-mdc default for select panel)
   sboxmarket previously left the open select panel at the Material default
   (#1e1e1e opaque) which prints brighter than the csfloat smoked surface.
   Force the smoked rgba bg + 8px vertical padding when the panel is hosted
   under .month-filter-container (or its sibling sort container should it
   ever switch from a toggle-group to a select). */
.cdk-overlay-pane .mat-mdc-select-panel,
.cdk-overlay-pane > div > .mat-mdc-select-panel,
body .mat-mdc-select-panel {
  background-color: rgba(21, 23, 28, 0.8) !important;
  padding-top: 8px !important;
  padding-bottom: 8px !important;
  min-width: auto !important;
  max-height: 275px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128800 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128801 — open-panel border + radius split.
   Real csfloat measure (same panel): border 2px solid rgba(193,206,255,0.07)
   (cool-white hairline at very low opacity), border-radius 0 0 15px 15px.
   The squared top edge tells the eye the panel is "attached" to the
   trigger's bottom corner; the rounded 15px bottom fills the open card.
   sboxmarket's default Material panel paints uniform 4px radius which
   reads as a free-floating chip, not a flap. Override here so any open
   select panel under loadout discover (only place csfloat ships this
   chrome) gets the asymmetric radius + cool hairline. */
body .mat-mdc-select-panel {
  border: 2px solid rgba(193, 206, 255, 0.07) !important;
  border-top-left-radius: 0 !important;
  border-top-right-radius: 0 !important;
  border-bottom-left-radius: 15px !important;
  border-bottom-right-radius: 15px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128801 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128802 — open-panel shadow stack. Real
   csfloat measure (same panel): the panel paints the standard MDC menu
   surface 3-layer drop shadow:
     0 5px 5px -3px rgba(0,0,0,0.2),
     0 8px 10px 1px rgba(0,0,0,0.14),
     0 3px 14px 2px rgba(0,0,0,0.12).
   sboxmarket may have stripped this earlier when normalising shadows for
   the panel-2 cards; this rule re-asserts the MDC stack so the open
   dropdown lifts off the page exactly as csfloat's does. NOTE: s&box
   CSS parser supports multi-layer box-shadow on web here (this repo is
   the standalone web client, not the s&box overlay), so the comma list
   is fine. */
body .mat-mdc-select-panel {
  box-shadow:
    0 5px 5px -3px rgba(0, 0, 0, 0.2),
    0 8px 10px 1px rgba(0, 0, 0, 0.14),
    0 3px 14px 2px rgba(0, 0, 0, 0.12) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128802 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128803 — .mat-mdc-option idle row. Real
   csfloat measure (Last 1 Month / Last 3 Months / All Time options):
     min-height 48px, padding 0 16px, background transparent, color #fff,
     row width fills panel - 4px (panel hairline = 2 each side).
   sboxmarket should render the option rows with the same bare row
   geometry — no inner background, no extra inset, just the centred 16px
   text within a 48px row. */
body .mat-mdc-select-panel .mat-mdc-option,
body .mat-mdc-select-panel mat-option {
  min-height: 48px !important;
  padding-left: 16px !important;
  padding-right: 16px !important;
  background-color: transparent !important;
  color: rgb(255, 255, 255) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128803 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128804 — selected/active option highlight.
   Real csfloat measure: the currently-selected option (e.g. "Last 1
   Month" in this view) paints background color(srgb 1 1 1 / 0.12) which
   resolves to rgba(255,255,255,0.12) — a low-opacity white wash, not the
   brand blue. sboxmarket previously used the Material primary (brand
   blue rgb(35,123,255)) tint for selected select rows which clashes
   with csfloat's quieter palette. Override the selected/active states
   for any mat-option inside an open mat-mdc-select-panel to match. */
body .mat-mdc-select-panel .mat-mdc-option.mdc-list-item--selected,
body .mat-mdc-select-panel .mat-mdc-option[aria-selected="true"],
body .mat-mdc-select-panel mat-option.mat-selected,
body .mat-mdc-select-panel mat-option[aria-selected="true"] {
  background-color: rgba(255, 255, 255, 0.12) !important;
  color: rgb(255, 255, 255) !important;
}
body .mat-mdc-select-panel .mat-mdc-option.mdc-list-item--selected
  .mdc-list-item__primary-text,
body .mat-mdc-select-panel mat-option.mat-selected
  .mdc-list-item__primary-text {
  color: rgb(255, 255, 255) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128804 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128805 — option text typography. Real
   csfloat measure: .mdc-list-item__primary-text inside each option
   renders 16px / 400 / Roboto, white, normal letter-spacing. The
   sboxmarket default for list-item primary text is 14px / 500 which
   shrinks the dropdown rows and bolds them — wrong for csfloat parity.
   Pin the dropdown text to 16/400 here. */
body .mat-mdc-select-panel .mat-mdc-option .mdc-list-item__primary-text,
body .mat-mdc-select-panel mat-option .mdc-list-item__primary-text {
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 16px !important;
  font-weight: 400 !important;
  letter-spacing: normal !important;
  color: rgb(255, 255, 255) !important;
  line-height: 1.5 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128805 */
"""


def main():
    # Atomic append: open in append-binary, write payload as utf-8 bytes,
    # flush + fsync, then verify length grew. No temp files; append-only
    # can never corrupt prior bytes.
    payload_bytes = PAYLOAD.encode("utf-8")
    before = os.path.getsize(CSS)
    with open(CSS, "ab") as f:
        f.write(payload_bytes)
        f.flush()
        try:
            os.fsync(f.fileno())
        except OSError:
            pass
    after = os.path.getsize(CSS)
    grew = after - before
    print(f"design.css: {before} -> {after} (+{grew} bytes)")
    if grew != len(payload_bytes):
        print(f"WARN expected +{len(payload_bytes)} bytes but got +{grew}")
        sys.exit(2)
    print("OK ships #128800-#128805 appended.")


if __name__ == "__main__":
    main()
