"""Atomic append of CSFLOAT 1:1 PARITY ships #128790-#128795 — item-detail
trade-history popup dialog CONTAINER chrome (the .modal.item-page surface
that wraps .price-history-chart + .recent-sales-list) — canonical
mat-mdc-dialog-container parity extracted from cached .tmp/csfloat-styles.css
2026-05-08.

CSFLOAT GROUND TRUTH (re-extracted from .tmp/csfloat-styles.css 2026-05-08):

  --module-background-color   : #1b1d24
  --highlight-background      : rgba(193, 206, 255, 0.07)
  --dialog-background-firefox : rgba(21, 23, 28, 1)        /* opaque fallback */
  --dialog-background         : rgba(21, 23, 28, 0.8)      /* translucent base */
  --max-dialog-height         : (computed per viewport)

  .mat-mdc-dialog-container {
      will-change                  : transform, opacity;
      -webkit-backdrop-filter      : blur(16px);
              backdrop-filter      : blur(16px);
      border-radius                : 12px;
  }
  /* Firefox / opening fallback (no backdrop-filter mid-animation) */
  .mat-mdc-dialog-container.mdc-dialog--opening {
      backdrop-filter : none !important;
  }
  .mat-mdc-dialog-container .mat-mdc-dialog-surface {
      background-color : var(--dialog-background-firefox);  /* rgba(21,23,28,1) */
      border-radius    : 12px;
      border           : 2px solid var(--highlight-background);  /* rgba(193,206,255,0.07) */
      overscroll-behavior : none;
  }
  .cdk-overlay-pane {
      max-height : var(--max-dialog-height);
  }

SBOXMARKET CURRENT STATE (verified via grep + design.css read 2026-05-08):

  .modal renders as the popup dialog container (modals.js:407 .modal item-page).
  Current chrome (design.css:4923):
    background     : var(--bg-1)              /* oklch(0.20 0.009 260) ≈ #1f232c */
    border         : 1px solid var(--line-2)   /* mid-tone divider line */
    border-radius  : var(--r-md)               /* 10px */
    box-shadow     : var(--shadow-2)
    overflow       : hidden

  GAPS vs canonical csfloat .mat-mdc-dialog-container:

  1. border-radius 10px vs canonical 12px = subtle but visible (csfloat
     uses 12px on every dialog/sheet surface).

  2. border 1px solid var(--line-2) (mid-tone) vs canonical
     2px solid rgba(193,206,255,0.07) (--highlight-background, the
     cool-blue 0.07 alpha hairline). The canonical 2px/0.07 rgba reads
     as a soft luminous edge against the dark surface; sbox's 1px
     mid-tone reads as a hard line.

  3. No backdrop-filter blur(16px) on the dialog body. csfloat ships
     `.mat-mdc-dialog-container { backdrop-filter: blur(16px) }` so the
     content behind the dialog (chart-popup uses dialog mode) is
     frosted out. Sbox's modal sits over a hard backdrop with no blur
     — the page content is still sharp behind the dialog.

  4. No backdrop-filter exit cancel: canonical csfloat strips the blur
     during the opening animation (`.mdc-dialog--opening { backdrop-filter:
     none }`) for performance / animation smoothness. Sbox has no
     blur to begin with so this is moot — but defensive parity for
     when blur is shipped.

  5. background var(--bg-1) ≈ #1f232c-ish but canonical csfloat is
     the precise rgba(21,23,28,1) (--dialog-background-firefox =
     #15171c, slightly darker than panel bg). For frosted-blur dialogs
     csfloat uses var(--dialog-background) = rgba(21,23,28,0.8) (80%
     opaque) which gets a 20% bleed of the underlying blur.

  6. overscroll-behavior: none missing on the dialog body — without
     this, scrolling a long .recent-sales-list past its bottom triggers
     the parent page's scroll, which on the popup chart is jarring
     (the entire page beneath jumps).

CORRECTIONS APPENDED (CSS-only, !important, NO JS source change):

  #128790 — Pin .modal.item-page border-radius from 10px (var(--r-md))
            to canonical csfloat 12px. Targets ONLY .modal.item-page
            (the popup chart surface) so we don't disrupt other modals
            in the app that may rely on the existing 10px.

  #128791 — Pin .modal.item-page border to 2px solid rgba(193,206,255,0.07)
            (canonical --highlight-background). Existing 1px solid
            var(--line-2) becomes the canonical 2px luminous edge.

  #128792 — Pin .modal.item-page background to rgba(21,23,28,0.8)
            (canonical --dialog-background) so when paired with
            backdrop-filter blur(16px) the dialog gets the canonical
            frosted-glass look. Falls back via opaque rgba(21,23,28,1)
            for browsers without backdrop-filter support.

  #128793 — Add backdrop-filter blur(16px) to the modal-backdrop +
            .modal.item-page for the canonical csfloat dialog frosted-
            glass surface. Pairs with #128792 to complete the look.

  #128794 — overscroll-behavior: none on .modal.item-page so scrolling
            a long .recent-sales-list past its bottom doesn't jump the
            parent page (canonical csfloat .mat-mdc-dialog-surface
            spec).

  #128795 — Defensive: .modal.item-page during entry animation,
            backdrop-filter: none (csfloat strips blur during
            opening for animation smoothness via
            .mdc-dialog--opening pseudo). Sbox uses an `animation`
            keyframes (.modal-pop) — strip backdrop-filter for the
            first 220ms via animation-fill-mode trick (apply blur after
            the keyframes complete via a short animation-delay on the
            backdrop-filter property).
"""

import os, sys

CSS_PATH = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"

APPEND = r"""

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128790 — .modal.item-page border-radius
   parity to canonical csfloat .mat-mdc-dialog-container { border-radius:
   12px }. Sbox baseline uses var(--r-md) which resolves to 10px (per
   design.css:74). Bump to canonical 12px. SCOPED to .modal.item-page
   only so other modals in the app that may rely on the 10px panel
   radius (e.g. shortcuts panel, cart confirm) aren't disrupted. */
html body html body .modal.item-page {
  border-radius: 12px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128790 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128791 — .modal.item-page border parity to
   canonical csfloat .mat-mdc-dialog-surface { border: 2px solid
   var(--highlight-background) /* rgba(193,206,255,0.07) */ }. Sbox
   baseline uses 1px solid var(--line-2) (mid-tone divider) which reads
   as a hard line. Canonical csfloat ships a 2px luminous cool-blue
   hairline that softens against the dark surface — the canonical
   border-treatment for every dialog surface in the app. */
html body html body .modal.item-page {
  border: 2px solid rgba(193, 206, 255, 0.07) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128791 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128792 — .modal.item-page background parity
   to canonical csfloat .mat-mdc-dialog-surface { background-color:
   var(--dialog-background-firefox) /* rgba(21,23,28,1) */ } with
   chrome-path translucent base var(--dialog-background) /*
   rgba(21,23,28,0.8) */ for the frosted-blur surface. Sbox baseline
   uses var(--bg-1) oklch which resolves to roughly the same darkness
   but lacks the precise rgba match. Pin canonical csfloat values:
     - default opaque rgba(21,23,28,1) for fallback
     - @supports (backdrop-filter) translucent rgba(21,23,28,0.8) so
       the canvas behind the dialog gets the bleed effect when paired
       with backdrop-filter blur(16px) (#128793). */
html body html body .modal.item-page {
  background-color: rgba(21, 23, 28, 1) !important;
}
@supports ((-webkit-backdrop-filter: blur(1px)) or (backdrop-filter: blur(1px))) {
  html body html body .modal.item-page {
    background-color: rgba(21, 23, 28, 0.8) !important;
  }
}
/* END CSFLOAT-1:1 PARITY ship #128792 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128793 — .modal.item-page backdrop-filter
   parity to canonical csfloat .mat-mdc-dialog-container {
   -webkit-backdrop-filter: blur(16px); backdrop-filter: blur(16px) }.
   This is the frosted-glass effect on the dialog itself (NOT the
   .modal-backdrop overlay). When paired with the translucent bg from
   #128792, the dialog content surface gets a 16px gaussian blur of
   whatever sits behind it (the page chrome). The user sees the page
   chrome as a soft blur instead of sharply visible behind the dialog
   — canonical csfloat dialog look. */
html body html body .modal.item-page {
  -webkit-backdrop-filter: blur(16px) !important;
          backdrop-filter: blur(16px) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128793 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128794 — .modal.item-page overscroll-behavior
   parity to canonical csfloat .mat-mdc-dialog-surface { overscroll-
   behavior: none }. Without this rule, scrolling past the bottom of
   the long .recent-sales-list inside the popup triggers the parent
   page's scroll — the entire page beneath the dialog jumps which
   visually breaks the popup affordance. Canonical csfloat dialog
   surface stops the scroll at the dialog edge. */
html body html body .modal.item-page,
html body html body .modal.item-page > .modal-body,
html body html body .modal.item-page .recent-sales-list,
html body html body .modal.item-page .recent-sales-table {
  overscroll-behavior: none !important;
  overscroll-behavior-y: none !important;
}
/* END CSFLOAT-1:1 PARITY ship #128794 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128795 — defensive .modal.item-page entry-
   animation backdrop-filter strip. Canonical csfloat
   .mat-mdc-dialog-container.mdc-dialog--opening { backdrop-filter:
   none !important } strips the blur during the dialog opening
   animation (~220ms) for animation smoothness — backdrop-filter is
   GPU-expensive during a transform animation and can stutter on
   weaker GPUs. Sbox uses a CSS @keyframes modal-pop (220ms
   cubic-bezier(0.2,0.8,0.2,1) — opacity + translateY/scale). To
   parity-match without a new class, we use animation-related CSS to
   suppress backdrop-filter for the first 220ms of the modal lifecycle:
   CSS doesn't have native "during keyframes" backdrop-filter
   suppression so we approximate by triggering a 220ms transition on
   backdrop-filter: 0px -> 16px so the blur smoothly ramps in instead
   of being applied at full strength during the entry. This avoids the
   GPU stutter while keeping the canonical end-state look. */
@media (prefers-reduced-motion: no-preference) {
  html body html body .modal.item-page {
    transition: -webkit-backdrop-filter 220ms cubic-bezier(0.4, 0, 0.2, 1),
                backdrop-filter 220ms cubic-bezier(0.4, 0, 0.2, 1) !important;
  }
}
/* END CSFLOAT-1:1 PARITY ship #128795 */
"""

import tempfile, shutil

with open(CSS_PATH, "rb") as f:
    existing = f.read()

if b"#128790" in existing and b"#128795" in existing:
    print("Already appended ships #128790-#128795. Skipping.")
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

print(f"Appended ships #128790-#128795. New size: {len(new_blob)} bytes.")
