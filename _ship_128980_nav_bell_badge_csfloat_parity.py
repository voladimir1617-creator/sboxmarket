"""
CSFLOAT-1:1 PARITY ships #128980-#128985 — header notification-bell .nav-icon-badge
re-measure-and-correct. Lane is the small unread-count circle that sits over the
bell mat-icon in the top-right toolbar. Earlier ships #127660-#127663 (nav signed-in
agent batch) only noted "mat-badge dot uses brand blue rgb(35,123,255) at 16x16
600/9px" as a one-line summary — never compared to the LOCAL .nav-icon-badge rules
in design.css that actually paint sboxmarket's bell. This batch closes that gap.

Live csfloat measurements (May 2026, playwright getComputedStyle on a synthetic
.mat-badge.mat-badge-small.mat-badge-active span injected into the live csfloat
page so it inherits ALL real csfloat tokens — the bell only renders for signed-in
users so we can't read it directly from the public DOM, but the synthetic picks
up the EXACT --mat-badge-* CSS custom properties csfloat ships):

  Badge dot (no count):   16x16   bg rgb(35,123,255) / color rgb(255,255,255)
                          font 9 / 16 / 600 Roboto, "Helvetica Neue", sans-serif
                          border-radius 50% (full circle, --mat-badge-container-shape)
                          position absolute, top:0 right:0 with margin:-8px
                          (overlap offset = -50% of the 16px container)
                          NO border, NO box-shadow, NO horizontal padding.
                          host: position:relative; overflow:visible
                          hidden state: transform: scale(0.6)
                          active state: transform: none
  Badge with count "3":   SAME 16x16 — text just sits inside, font-size stays 9
                          line-height 16 vertically centers the digit. min-width 6
                          (would shrink for a dot, capped at 16 by container size).

sboxmarket .nav-icon-badge (LIVE measured via playwright on localhost:8082, badge
appended to a real .nav-icon-btn so it inherits the ancestor selectors):

  width 16 height 16 (matches!)  ✓
  font-size 10px        ← csfloat is 9px (off by 1)
  font-weight 600       (matches via ship #16358-style override) ✓
  font-family Arial,...  ← csfloat is Roboto, "Helvetica Neue", sans-serif
                          (sbox uses --mono token; csfloat bell uses the system
                          primary type stack, NOT a mono digit font)
  line-height 14px      ← csfloat is 16px (digit not vertically centered)
  bg rgb(59,130,246) (#3b82f6, sbox's --cta tailwind-blue-500)
                        ← csfloat is rgb(35,123,255) (#237bff, csfloat's brand
                          --mat-badge-background-color). Visibly different blue
                          (csfloat is more saturated + slightly darker).
  border 2px solid rgb(20,22,28) (sbox --bg)
                        ← csfloat has NO border. Sbox uses the bg-coloured
                          border to fake a separator ring against the bell glyph,
                          but it's actually painted INSIDE the 16px box, so the
                          inner badge surface shrinks to ~12x12 — making the
                          bg-fill visually smaller than csfloat's full 16x16.
  box-shadow 0 0 0 2px sbox-bg + 0 0 12px cta@50%
                        ← csfloat has NO box-shadow. The 2px ring + 12px glow
                          (from ship #13742) is sbox's added "look at me" halo;
                          csfloat ships a flat, calm dot.
  padding 0 4px         ← csfloat has padding 0 (no horizontal pad — the digit
                          is centered by line-height + the 16px square box).
                          The 0 4px on sbox makes the visible rect bulge to
                          ~28x20 for a 1-char count — should stay 16x16.
  position absolute top:-2px right:-2px
                        ← csfloat is top:0 right:0 with margin:-8px (overlap-50%).
                          Sbox's -2px offset clips through the bell SVG corner;
                          csfloat anchors the badge cleanly off the upper-right
                          quadrant with no clip.
  outer rect 28x20      ← csfloat outer rect 16x16 (the border + horizontal pad
                          balloon sbox by ~12px wide and ~4px tall — directly
                          measurable diff in side-by-side header screenshot).

Corrections appended below as 6 separate !important rules so the file's existing
.nav-icon-badge stack at L3142 / L10049 / L13613 / L13742 / L16358 is OUT-CASCADED
without editing in-place (per APPEND-only rule). Each rule is keyed off a more
specific selector chain (`body nav .nav-icon-btn .nav-icon-badge`) so it wins
against the prior `.nav-icon-badge` and `.nav-icon-badge:not(:empty):not([data-count="0"])`
declarations regardless of source-order. brand blue = exact rgb(35,123,255), NOT
--cta (sbox --cta is #3b82f6 which is the wrong tailwind-blue-500 shade).
"""
from __future__ import annotations
from pathlib import Path
import os, tempfile

ROOT = Path(__file__).resolve().parent
CSS = ROOT / "src" / "main" / "resources" / "static" / "css" / "design.css"

PAYLOAD = r"""
/* =====================================================================
   CSFLOAT-1:1 PARITY ships #128980-#128985 — header notification-bell
   .nav-icon-badge live re-measurement vs csfloat's mat-badge tokens.
   Out-cascades the prior .nav-icon-badge declarations at L3142, L10049,
   L13613, L13742, L13749, L15387, L16358 (which collectively painted a
   2px-bordered + 12px-glowing #3b82f6 dot at 10/14 Arial). csfloat ships
   a calm 16x16 #237bff flat dot at 9/16 Roboto with NO border / NO ring
   / NO glow / NO horizontal pad. The current sbox stack inflates the
   outer rect to 28x20 for a 1-char count vs csfloat's 16x16 — directly
   measurable in any side-by-side toolbar screenshot.

   Specificity: `body nav .nav-icon-btn .nav-icon-badge` = 0,3,3
   beats every prior `.nav-icon-badge[...]` (max 0,3,1) regardless of
   source-order, even before the `!important` flag.
   ===================================================================== */

/* CSFLOAT-1:1 PARITY ship #128980 — bg COLOUR. Sbox painted with
   `var(--cta)` which resolves to #3b82f6 (rgb 59,130,246, tailwind
   blue-500). csfloat's --mat-badge-background-color is #237bff
   (rgb 35,123,255) — measurably different hue: csfloat is +24 R,
   -7 G, +9 B saturating warmer-toward-true-blue. Lock to csfloat's
   exact rgb so the badge matches the rest of the csfloat header
   accent palette (Sign-In button, route-button selected gradient
   ellipse, all share rgb 35,123,255). */
body nav .nav-icon-btn .nav-icon-badge,
body .site-root nav .nav-icon-btn .nav-icon-badge {
  background: rgb(35, 123, 255) !important;
  background-color: rgb(35, 123, 255) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128980 */

/* CSFLOAT-1:1 PARITY ship #128981 — REMOVE the 2px border-ring +
   the box-shadow halo. design.css L3149 paints `border: 2px solid
   var(--bg)` and L13744 paints `box-shadow: 0 0 0 2px var(--bg),
   0 0 12px color-mix(...)`. csfloat ships a FLAT dot — no border,
   no inset ring, no outer glow. The sbox border is painted INSIDE
   the 16px box (per CSS box-model), shrinking the visible bg-fill
   to ~12x12. The 12px outer glow adds a 24x24 visual footprint
   that csfloat doesn't have. Strip both so the badge reads as a
   clean solid 16x16 disc against the bell glyph. */
body nav .nav-icon-btn .nav-icon-badge,
body .site-root nav .nav-icon-btn .nav-icon-badge {
  border: none !important;
  box-shadow: none !important;
}
/* END CSFLOAT-1:1 PARITY ship #128981 */

/* CSFLOAT-1:1 PARITY ship #128982 — TYPOGRAPHY (font-size, weight,
   family, line-height). Sbox baseline at L3147 sets `font-family:
   var(--mono); font-size: 9px; font-weight: 600; line-height: 1`,
   then L16360 bumps `font-size: 10px; font-weight: 700` for non-empty
   non-zero badges. csfloat's --mat-badge-text-* tokens computed:
     font-family: Roboto, "Helvetica Neue", sans-serif
     font-size:   9px   (--mat-badge-small-size-text-size)
     line-height: 16px  (--mat-badge-small-size-line-height,
                         matches the 16px container so digit centers)
     font-weight: 600   (--mat-badge-text-weight)
   The 1px font surplus + 100 weight surplus + mono font stack on
   sbox makes the digit look chunkier + more pixel-grid-aligned than
   csfloat's softer Roboto rendering. Lock to csfloat tokens exact. */
body nav .nav-icon-btn .nav-icon-badge,
body .site-root nav .nav-icon-btn .nav-icon-badge {
  font-family: Roboto, "Helvetica Neue", sans-serif !important;
  font-size: 9px !important;
  font-weight: 600 !important;
  line-height: 16px !important;
  letter-spacing: normal !important;
}
/* END CSFLOAT-1:1 PARITY ship #128982 */

/* CSFLOAT-1:1 PARITY ship #128983 — PADDING + BOX SIZING. Sbox
   L3144 sets `min-width: 16px; height: 16px; padding: 0 4px` so a
   1-char count grows the visible rect to ~28px wide (the 4px pad
   each side + 16px min + 2px border each side = 28). csfloat ships
   `min-width: 6px; min-height: 6px; padding: 0` — the 16x16 box is
   FIXED at 16x16 regardless of content because the container-size
   var caps it. For multi-digit counts csfloat lets the container
   grow but for 1-char (the common case for unread bells) it stays
   16x16 perfectly square. Lock dimensions + zero the padding. */
body nav .nav-icon-btn .nav-icon-badge,
body .site-root nav .nav-icon-btn .nav-icon-badge {
  width: 16px !important;
  min-width: 16px !important;
  height: 16px !important;
  min-height: 16px !important;
  padding: 0 !important;
  box-sizing: border-box !important;
  /* csfloat container-shape: 50% — keep the disc round even if
     content forces width >16 (multi-digit fallback). The 999px
     radius at L3145 is functionally equivalent but lock to 50%
     so it visibly matches csfloat's --mat-badge-container-shape
     token value. */
  border-radius: 50% !important;
}
/* END CSFLOAT-1:1 PARITY ship #128983 */

/* CSFLOAT-1:1 PARITY ship #128984 — POSITION + ANCHOR. Sbox L3143
   sets `top: 6px; right: 6px` (positioning the badge 6px INSIDE the
   button corner — clipping it through the bell SVG). csfloat anchors
   with the standard mat-badge-overlap pattern: `top: 0; right: 0`
   on the badge content + `margin: -8px` (which is -50% of the 16px
   container, the --mat-badge-small-size-container-overlap-offset
   token). The result places the badge's CENTER at the button's
   top-right CORNER — half-on, half-off the button bounds. Sbox's
   `top: 6px right: 6px` parks the badge entirely INSIDE the button,
   overlapping the bell glyph's upper-right wedge. Switch to csfloat's
   overlap pattern so the badge sits cleanly off the corner. */
body nav .nav-icon-btn .nav-icon-badge,
body .site-root nav .nav-icon-btn .nav-icon-badge {
  position: absolute !important;
  top: 0 !important;
  right: 0 !important;
  bottom: auto !important;
  left: auto !important;
  margin: -8px !important;
  /* Center the digit since we removed horizontal padding + the box
     is fixed 16x16. flex centering doubles up with the line-height
     vertical centering for sub-pixel-perfect alignment. */
  display: inline-flex !important;
  align-items: center !important;
  justify-content: center !important;
  text-align: center !important;
  /* `place-items: center` from L3148 is grid syntax — drop it via
     the inline-flex override above (grid + flex on same element
     makes the layout undefined). */
}
/* The host button MUST keep `position: relative; overflow: visible`
   so the negative-margin badge can stick out past the button's
   bounds (otherwise the badge corner clips). */
body nav .nav-icon-btn,
body .site-root nav .nav-icon-btn {
  position: relative !important;
  overflow: visible !important;
}
/* END CSFLOAT-1:1 PARITY ship #128984 */

/* CSFLOAT-1:1 PARITY ship #128985 — NEUTRALIZE prior shake / pop /
   bell-shake animations + the ::before "phantom dot" pseudo-element
   at L14769-14777 that was painting an 8x8 cta-coloured circle 4px
   from the top-right corner whenever the badge was non-empty
   (presumably a fallback for browsers without the badge selector
   support, but it was always rendering BEHIND the real badge as a
   second visible dot). csfloat has ZERO such phantom dot — the badge
   IS the only dot. Hide the pseudo-element so we don't ship a
   double-dot artifact. Also reassert the badge-pop animation from
   L13614 with the calmer csfloat-style fade so the 320ms pop doesn't
   visibly overshoot when the count flips from 0 -> 1 (csfloat just
   fades in via the mat-badge transform: scale(0.6) -> none, no
   bounce). */
body nav .nav-icon-btn:has(.nav-icon-badge:not(:empty))::before,
body .site-root nav .nav-icon-btn:has(.nav-icon-badge:not(:empty))::before {
  display: none !important;
  content: none !important;
}
body nav .nav-icon-btn .nav-icon-badge,
body .site-root nav .nav-icon-btn .nav-icon-badge {
  /* csfloat's mat-badge-content uses `transition: transform 200ms
     ease-in-out` for the scale(0.6) <-> none flip. Override the
     L13614 `animation: badge-pop 320ms cubic-bezier(0.2,0.8,0.2,1)`
     with the same calm scale fade. */
  animation: none !important;
  transition: transform 200ms ease-in-out !important;
  transform: none !important;
}
/* The bell-shake from L14052 fires once on the bell SVG when the
   badge is non-empty. csfloat does NOT shake the bell — the badge
   appears via the mat-badge scale fade and the bell glyph stays
   still. Suppress so we don't ship a 600ms shake animation csfloat
   doesn't have. */
body nav .nav-icon-btn:has(.nav-icon-badge:not(:empty):not([data-count="0"])) .material-symbols-rounded,
body .site-root nav .nav-icon-btn:has(.nav-icon-badge:not(:empty):not([data-count="0"])) .material-symbols-rounded {
  animation: none !important;
}
/* END CSFLOAT-1:1 PARITY ship #128985 */

/* =====================================================================
   END CSFLOAT-1:1 PARITY ships #128980-#128985
   ===================================================================== */
"""

def atomic_append(path: Path, text: str) -> None:
    """Append text to file via temp-file write + rename so a crash mid-write
    can't half-corrupt the source. tempfile in same dir -> same volume -> rename
    is atomic on Windows + POSIX."""
    if not path.exists():
        raise FileNotFoundError(path)
    body = path.read_text(encoding="utf-8")
    if "CSFLOAT-1:1 PARITY ships #128980" in body:
        print(f"[skip] ship #128980-#128985 already present in {path.name}")
        return
    new = body + text
    fd, tmp_path = tempfile.mkstemp(prefix=path.name + ".", suffix=".tmp", dir=str(path.parent))
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(new)
        os.replace(tmp_path, path)
        added = len(text.splitlines())
        print(f"[ok] appended {added} lines to {path.name}")
    finally:
        if os.path.exists(tmp_path):
            os.unlink(tmp_path)

if __name__ == "__main__":
    atomic_append(CSS, PAYLOAD)
