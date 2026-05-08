"""Atomic append of CSFLOAT 1:1 PARITY ships #128770-#128775 — CORRECTION
to ship #128764 (chart-range button group active state) + extension to
the inactive/disabled/hover states + slim height + label typography
parity.

CONTEXT: Ship #128764 (just-shipped 2026-05-08 in commit fe2bf5e + the
parallel commit faadfe6) painted .chart-range-btn.active with the
sboxmarket brand-blue rgb(35,123,255) on the assumption that csfloat's
mat-button-toggle-checked uses brand-fill. RE-MEASURED via the cached
.tmp/csfloat-styles.css ngx-charts theme spec: csfloat's actual canonical
mat-button-toggle CSS variables are:

  --mat-button-toggle-background-color           : rgba(0, 0, 0, 0)
  --mat-button-toggle-selected-state-background-color
                                                  : var(--highlight-background-minimal)
                                                    /* rgba(193, 206, 255, 0.04) */
  --mat-button-toggle-height                      : 32px (default), 24px (slim)
  --mat-button-toggle-shape                       : 4px (radius)
  --mat-button-toggle-hover-state-layer-opacity   : 0.04
  --mat-button-toggle-focus-state-layer-opacity   : 0.12
  --mat-button-toggle-disabled-state-text-color   : color-mix(rgba(0,0,0,.87) 38%, transparent)

  And the per-rule CSS:
  .mat-button-toggle-group .mat-button-toggle { margin: 3px; border-radius: 4px; }
  .mat-button-toggle-group .mat-button-toggle .mat-button-toggle-button { border: none; }
  .mat-button-toggle-group .mat-button-toggle .mat-button-toggle-label-content {
      color: var(--subtext-color);   /* #9ea7b1 — INACTIVE label */
      font-weight: 500;
      letter-spacing: 0.03em;
      font-size: 14px;
  }
  .mat-button-toggle-checked .mat-button-toggle-button .mat-button-toggle-label-content {
      color: var(--primary-text-color);  /* #ffffff — ACTIVE label */
  }
  .mat-button-toggle-group.slim {
      --mat-button-toggle-height: 24px;
  }
  .mat-button-toggle-disabled { opacity: 0.35; }

ERROR vs canonical: ship #128764's brand-blue active fill does NOT match
csfloat's actual rgba(193,206,255,0.04) translucent fill — the visual
read is night-and-day (saturated brand chip vs subtle hairline-tinted
chip). This ship CORRECTS the active state to the translucent fill and
ALSO ships the missing inactive/disabled/hover/geometry/typography
parity that #128764 didn't cover.

CSFLOAT GROUND TRUTH (re-extracted from .tmp/csfloat-styles.css 2026-05-08):

  Slim mat-button-toggle (the chart-range / range-picker variant):
    height            : 24px (--mat-button-toggle-height: 24px)
    border-radius     : 4px (--mat-button-toggle-shape: 4px)
    margin            : 3px (between toggles in the group)
    inner button border: none

  Inactive label:
    color           : #9ea7b1 (ink-2 / subtext-color)
    font-weight     : 500
    letter-spacing  : 0.03em
    font-size       : 14px
    font-family     : Roboto, sans-serif

  Active (mat-button-toggle-checked):
    background      : rgba(193, 206, 255, 0.04)
    label color     : #ffffff (primary-text-color)

  Hover:
    state layer opacity 0.04 over base bg
    => effective ~rgba(193, 206, 255, 0.04) on transparent base
    (i.e. inactive hover = active-looking translucent fill)

  Disabled:
    opacity         : 0.35

CORRECTIONS APPENDED (CSS-only, !important, NO JS source change):

  #128770 — OVERRIDE ship #128764's brand-blue .chart-range-btn.active
            fill with canonical csfloat translucent rgba(193,206,255,0.04).
            Border-color also drops to translucent. Color stays #fff.
            Higher specificity prefix to override the previous ship.

  #128771 — Pin .chart-range-btn (inactive) typography to canonical:
            14px Roboto / 500 / 0.03em letter-spacing / color #9ea7b1.
            Currently sbox uses smaller fz / different lh — promote to
            canonical csfloat mat-button-toggle-label-content spec.

  #128772 — Pin .chart-range geometry (slim variant):
            min-height 24px, border-radius 4px, padding 0 12px,
            inner buttons margin 3px between (gap on parent), no border
            on the underlying button element. Also lock parent
            .chart-range to display: inline-flex with align-items center
            and gap 0 (margin is on the buttons themselves per Material).

  #128773 — Hover state for inactive .chart-range-btn:
            background rgba(193, 206, 255, 0.04) (the same translucent
            fill the active state uses, mimicking the Material hover
            state-layer at 0.04 opacity over the transparent base).

  #128774 — Focus-visible state: 1.5px outline rgb(35, 123, 255) at 1px
            offset (canonical csfloat focus halo for filter chips/toggles).
            Important: the focus halo is the brand-blue ring, NOT a fill —
            the fill stays translucent.

  #128775 — Disabled state explicit: opacity 0.35 (was 0.4 in ship #128764).
            Already pinned but now correct value.
"""

import os, sys

CSS_PATH = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"

APPEND = r"""

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128770 — OVERRIDE ship #128764 (brand-blue
   .chart-range-btn.active fill) with canonical csfloat translucent
   rgba(193,206,255,0.04) fill. Re-measured against
   .tmp/csfloat-styles.css mat-button-toggle CSS vars (2026-05-08):
     --mat-button-toggle-selected-state-background-color
       : var(--highlight-background-minimal)  /* rgba(193,206,255,0.04) */
   csfloat's mat-button-toggle-checked label color goes to
   var(--primary-text-color) #ffffff but the BACKGROUND is the same
   subtle translucent tint as inactive-hover, NOT a saturated brand
   blue. Ship #128764 painted the active state brand-blue under the
   incorrect assumption that csfloat used brand-fill for active toggles
   — the canonical design uses a hairline-tinted fill paired with white
   text to indicate selection (much subtler than the sboxmarket version
   shipped in #128764). Override with `html body html body` specificity
   prefix to win the cascade against the previous ship. */
html body html body .chart-range .chart-range-btn.active,
html body html body .chart-range .chart-range-btn.active:hover,
html body html body .chart-range .chart-range-btn.active:focus,
html body html body .chart-range .chart-range-btn.active:focus-visible,
html body html body button.chart-range-btn.active {
  background: rgba(193, 206, 255, 0.04) !important;
  background-color: rgba(193, 206, 255, 0.04) !important;
  color: rgb(255, 255, 255) !important;
  font-weight: 500 !important;
  border-color: transparent !important;
  border: none !important;
  box-shadow: none !important;
}
/* END CSFLOAT-1:1 PARITY ship #128770 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128771 — .chart-range-btn (inactive state)
   typography parity to canonical csfloat
   .mat-button-toggle-label-content spec:
     color           : var(--subtext-color)  /* #9ea7b1 — ink-2 */
     font-weight     : 500
     letter-spacing  : 0.03em
     font-size       : 14px
     font-family     : Roboto, "Helvetica Neue", sans-serif
   sboxmarket .chart-range-btn renders today without the canonical
   letter-spacing or fixed font-size on the inactive label, so the
   range pills read with default body type rhythm rather than the
   slightly-tracked Material toggle-label rhythm csfloat ships. Pin
   the canonical label spec on the base (non-active) state — active
   state inherits the typography but overrides color via #128770. */
html body .chart-range .chart-range-btn:not(.active),
html body button.chart-range-btn:not(.active) {
  color: rgb(158, 167, 177) !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-weight: 500 !important;
  letter-spacing: 0.03em !important;
  font-size: 14px !important;
  line-height: 1.4 !important;
  background: transparent !important;
}
/* END CSFLOAT-1:1 PARITY ship #128771 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128772 — .chart-range geometry parity to
   canonical csfloat .mat-button-toggle-group.slim spec:
     --mat-button-toggle-height : 24px
     --mat-button-toggle-shape  : 4px (border-radius)
     .mat-button-toggle margin  : 3px (between toggles)
     .mat-button-toggle-button border : none
   The chart-range pill row sits in the popup chart panel above the
   sparkline; csfloat uses the slim 24px-height variant of mat-button-
   toggle-group for compact filter strips (range pickers, density
   toggles, etc). Pin sboxmarket .chart-range to canonical slim:
     parent .chart-range : inline-flex, align-items center, gap 0
     each .chart-range-btn : min-height 24px, padding 0 12px,
                             border-radius 4px, margin 3px (Material
                             puts the spacing on the toggle, not the
                             group, so a single 3px margin = 6px gap
                             between adjacent toggles), border none. */
html body .chart-range {
  display: inline-flex !important;
  align-items: center !important;
  gap: 0 !important;
  padding: 0 !important;
  margin: 0 !important;
  background: transparent !important;
}
html body .chart-range .chart-range-btn,
html body button.chart-range-btn {
  min-height: 24px !important;
  height: 24px !important;
  padding: 0 12px !important;
  border-radius: 4px !important;
  margin: 3px !important;
  border: none !important;
  outline: none !important;
  display: inline-flex !important;
  align-items: center !important;
  justify-content: center !important;
  cursor: pointer !important;
}
/* END CSFLOAT-1:1 PARITY ship #128772 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128773 — .chart-range-btn:not(.active):hover
   state-layer parity to canonical csfloat Material spec:
     --mat-button-toggle-hover-state-layer-opacity : 0.04
   Effectively renders as rgba(193, 206, 255, 0.04) over the transparent
   base — i.e. the inactive-hover fill matches the active fill (the
   active state then differentiates itself with the white label color
   per #128770/#128771). This pattern is canonical Material: hover
   previews what selection would look like, then selection commits the
   text color to the primary palette. */
html body .chart-range .chart-range-btn:not(.active):not(.is-disabled):hover,
html body .chart-range .chart-range-btn:not(.active):not(.is-disabled):focus-visible,
html body button.chart-range-btn:not(.active):not(.is-disabled):hover {
  background: rgba(193, 206, 255, 0.04) !important;
  background-color: rgba(193, 206, 255, 0.04) !important;
  color: rgb(255, 255, 255) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128773 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128774 — .chart-range-btn:focus-visible
   focus halo parity to canonical csfloat focus state for filter chips
   / toggles. csfloat uses a brand-blue 1.5px outline at 1px offset
   (the same focus ring shipped on .price-suggest-chip:focus-visible
   per #19805 and on auto-bid input focus per #20100). The halo is
   layered ON TOP of the translucent fill — the fill itself stays
   the canonical hover/active translucent, the brand-blue is the
   focus-only visual cue. */
html body .chart-range .chart-range-btn:focus-visible,
html body button.chart-range-btn:focus-visible {
  outline: 1.5px solid rgb(35, 123, 255) !important;
  outline-offset: 1px !important;
  box-shadow: none !important;
}
/* END CSFLOAT-1:1 PARITY ship #128774 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128775 — .chart-range-btn.is-disabled
   opacity correction. csfloat .mat-button-toggle-disabled spec is
   `opacity: 0.35` (per the cached mat-button-toggle CSS in
   .tmp/csfloat-styles.css). Ship #128764 used opacity: 0.4 which is
   visually close but NOT canonical — pin to exact csfloat 0.35.
   Pointer-events stay none so the user can't click a disabled range. */
html body html body .chart-range .chart-range-btn.is-disabled,
html body html body button.chart-range-btn.is-disabled {
  opacity: 0.35 !important;
  cursor: not-allowed !important;
  pointer-events: none !important;
  background: transparent !important;
}
/* END CSFLOAT-1:1 PARITY ship #128775 */
"""

import tempfile, shutil

with open(CSS_PATH, "rb") as f:
    existing = f.read()

if b"#128770" in existing and b"#128775" in existing:
    print("Already appended ships #128770-#128775. Skipping.")
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

print(f"Appended ships #128770-#128775. New size: {len(new_blob)} bytes.")
