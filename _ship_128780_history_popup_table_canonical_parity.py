"""Atomic append of CSFLOAT 1:1 PARITY ships #128780-#128785 — item-detail
trade-history popup data-table chrome (recent-sales-list / recent-sales-row
inside the .price-history-chart popup) — canonical mat-mdc-table parity
extracted from cached .tmp/csfloat-styles.css 2026-05-08.

CSFLOAT GROUND TRUTH (re-extracted from .tmp/csfloat-styles.css 2026-05-08):

  --header-border-radius            : 10px
  --highlight-background-minimal    : rgba(193, 206, 255, 0.04)
  --subtext-color                   : #9ea7b1
  --backing-background-color        : #15171c

  .mat-mdc-header-row {
      display       : flex;
      flex-wrap     : wrap;
      height        : auto;
      position      : sticky;
      top           : 0;
      z-index       : 2;
  }
  .mat-mdc-header-row th {
      background       : var(--highlight-background-minimal);  /* rgba(193,206,255,0.04) */
      color            : var(--subtext-color);                  /* #9ea7b1 */
      letter-spacing   : 0.03em;
  }
  .mat-mdc-header-row th:first-child {
      border-top-left-radius    : var(--header-border-radius);  /* 10px */
      border-bottom-left-radius : var(--header-border-radius);
  }
  .mat-mdc-header-row th:last-child {
      border-top-right-radius    : var(--header-border-radius);
      border-bottom-right-radius : var(--header-border-radius);
  }
  .mat-mdc-header-row th.mat-mdc-header-cell {
      display          : flex;
      flex             : 1;
      align-items      : center;
      padding          : 1rem 0.5rem;  /* 16px 8px */
      justify-content  : space-around;
  }
  .mat-mdc-cell {
      border-color : var(--highlight-background-minimal);  /* rgba(193,206,255,0.04) */
  }
  .mat-mdc-row:last-of-type td:last-of-type {
      border : none;
  }

SBOXMARKET CURRENT STATE (verified via grep + design.css read 2026-05-08):

  Ship #19808 (item-detail trade-history popup column-header strip)
  shipped a .recent-sales-list::before pseudo at:
    height          : 42px
    background      : rgba(193, 206, 255, 0.04)
    border-bottom   : 1px solid rgba(193, 206, 255, 0.04)
    position        : sticky
    top             : 0
    z-index         : 1                  <-- BELOW canonical csfloat 2

  Ship #19806 set .recent-sales-list { max-height: 500px; overflow-y: auto }.
  No corner radius on the header strip — csfloat applies 10px to first/last
  corners of the header row. Sboxmarket's pseudo-strip has no rounded
  corners, so the top of the popup table reads as a flat 42px band rather
  than the rounded shoulder csfloat ships.

  Ship #1423 / #14704 set .recent-sales-row border-bottom to a hairline
  rgba(255,255,255,0.06) — csfloat uses rgba(193,206,255,0.04). Different
  alpha base = visibly hotter divider on dark panels.

  No ship pins .recent-sales-row:last-child { border-bottom: none } so
  the bottom of the table renders with a hairline divider that visually
  competes with the popup panel's bottom border.

  No ship pins .recent-sales-row hover state. csfloat .mat-mdc-row:hover
  inherits the Material .mat-mdc-row hover state-layer at 0.08 opacity
  over the highlight tint = effectively rgba(193,206,255,0.04) bg on
  hover. Without a hover hint the user can't anchor which row they're
  inspecting in a 50-row long-history scroll.

CORRECTIONS APPENDED (CSS-only, !important, NO JS source change):

  #128780 — Re-pin .recent-sales-list::before sticky z-index from 1 to 2
            to match canonical csfloat .mat-mdc-header-row z-index. At
            z-index 1 the strip can be visually overlapped by hover/focus
            state-layer pseudos on the rows below (z-index 1+ on the
            inner row :hover) — csfloat sits the header at z-index 2 to
            float above any row state-layer.

  #128781 — Add canonical 10px border-radius to .recent-sales-list::before
            top-left + top-right corners (the strip is a single full-width
            element so it carries both corners). csfloat applies the
            radius to first/last <th>; sbox has no <th> tree so we apply
            to the strip itself. This rounds the shoulder of the popup
            table.

  #128782 — Re-pin .recent-sales-row border-bottom from
            rgba(255,255,255,0.06) (per #1423/#14704 baseline) to
            canonical rgba(193,206,255,0.04). Same alpha but cool-blue
            base for visual integration with the chip/header tint
            already shipped in #19808.

  #128783 — .recent-sales-row:last-child / :last-of-type border-bottom
            none, so the bottom of the table doesn't double-stroke
            against the popup panel's bottom hairline. Canonical csfloat
            .mat-mdc-row:last-of-type td:last-of-type spec.

  #128784 — .recent-sales-row:hover background tint to canonical csfloat
            Material row hover state-layer: rgba(193,206,255,0.04). Pairs
            with the existing tnum/lh shipped in #19807/#19809 so the
            row anchors visibly under the cursor without changing the
            text rhythm. Cursor: default (these are display rows, not
            clickable).

  #128785 — .recent-sales-row text wrapping: explicit `white-space: nowrap`
            on the price + time + type cells (canonical csfloat data-table
            cells don't wrap on desktop). Belt-and-suspenders for the
            grid-template-columns 1fr/1fr/1fr/24px shipped in #14704 — if
            a long string lands in the price column it should ellipsis
            rather than wrap and break the row height.
"""

import os, sys

CSS_PATH = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"

APPEND = r"""

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128780 — .recent-sales-list::before sticky
   z-index correction. Ship #19808 set the strip's z-index to 1, but
   canonical csfloat .mat-mdc-header-row sits at z-index 2 (per
   .tmp/csfloat-styles.css 2026-05-08). At z-index 1 a row's
   :hover/:focus state-layer pseudo (which inherits z-index from its
   row's stacking context, often z-index >= 1 in Material spec) can
   visually overlap the sticky strip when scrolled into view. Bump to
   2 so the header strip stays float-above any row state-layer. */
html body html body .recent-sales-list::before {
  z-index: 2 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128780 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128781 — .recent-sales-list::before corner
   radius parity to canonical csfloat .mat-mdc-header-row first/last
   <th> spec:
     th:first-child { border-top-left-radius: 10px (--header-border-radius) }
     th:last-child  { border-top-right-radius: 10px }
   sboxmarket renders the header strip as a single full-width pseudo
   (no <th> tree to anchor first/last corners onto), so we apply both
   top corners to the strip itself. Bottom corners stay square — the
   strip is followed immediately by the body rows so the bottom edge
   blends into the row hairline divider. This rounds the shoulder of
   the popup table to match the canonical 10px radius csfloat ships. */
html body html body .recent-sales-list::before {
  border-top-left-radius: 10px !important;
  border-top-right-radius: 10px !important;
  border-bottom-left-radius: 0 !important;
  border-bottom-right-radius: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128781 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128782 — .recent-sales-row border-bottom
   alpha-base parity. Shipped baseline (#1423 / #14704) used
   rgba(255,255,255,0.06) — pure white at 0.06 alpha. Canonical csfloat
   .mat-mdc-cell { border-color: var(--highlight-background-minimal) }
   resolves to rgba(193,206,255,0.04) — cool blue at 0.04 alpha. The
   per-row hairline divider should match the header strip tint shipped
   in #19808 (which already uses rgba(193,206,255,0.04)) so the table
   reads as a single tonal family rather than mixed warm/cool dividers.
   Override with higher specificity to win against the prior ships. */
html body html body .recent-sales-table .recent-sales-row,
html body html body .recent-sales-list .recent-sales-row,
html body html body .price-history-chart .recent-sales-row {
  border-bottom: 1px solid rgba(193, 206, 255, 0.04) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128782 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128783 — .recent-sales-row last-child
   border-bottom: none parity to canonical csfloat
   .mat-mdc-row:last-of-type td:last-of-type { border: none }. Without
   this, the bottom of the table double-strokes against the
   .price-history-chart panel's own bottom hairline (shipped in #14700)
   — two parallel hairlines visually thicken the bottom edge. Strip the
   last row's bottom border so the popup table closes cleanly. */
html body html body .recent-sales-table .recent-sales-row:last-child,
html body html body .recent-sales-table .recent-sales-row:last-of-type,
html body html body .recent-sales-list .recent-sales-row:last-child,
html body html body .recent-sales-list .recent-sales-row:last-of-type,
html body html body .price-history-chart .recent-sales-row:last-child,
html body html body .price-history-chart .recent-sales-row:last-of-type {
  border-bottom: none !important;
}
/* END CSFLOAT-1:1 PARITY ship #128783 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128784 — .recent-sales-row:hover background
   tint parity to canonical csfloat Material .mat-mdc-row hover
   state-layer. csfloat applies rgba(193,206,255,0.04) (the same tint as
   the header strip + chip backgrounds) on row hover so the user can
   anchor which row they're inspecting in a long scroll without changing
   text rhythm or row height. sboxmarket .recent-sales-row had no hover
   state today — rows blend together visually under the cursor. Add the
   canonical hover tint. Cursor stays default (these are display rows,
   not interactive). Transition for smooth hover-on/off rhythm. */
html body html body .recent-sales-table .recent-sales-row:hover,
html body html body .recent-sales-list .recent-sales-row:hover,
html body html body .price-history-chart .recent-sales-row:hover {
  background-color: rgba(193, 206, 255, 0.04) !important;
  cursor: default !important;
  transition: background-color 120ms cubic-bezier(0.4, 0, 0.2, 1) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128784 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128785 — .recent-sales-row cell text-wrap
   defensive parity. Canonical csfloat .mat-mdc-cell on desktop renders
   with `word-break: break-word` BUT the per-column 1fr widths in the
   trade-history popup mean a long string in any column would force the
   row to grow vertically and break the canonical 52px row height
   shipped in #19809. Pin `white-space: nowrap` + `text-overflow: ellipsis`
   + `overflow: hidden` on each cell so an unusually long value
   ellipsises rather than wraps. This is a belt-and-suspenders for the
   grid-template-columns 1fr/1fr/1fr/24px shipped in #14704 — without
   the nowrap declaration a 25-character buyer-name string could break
   the column rhythm. */
html body .recent-sales-row .recent-sales-price,
html body .recent-sales-row .recent-sales-time,
html body .recent-sales-row .recent-sales-type,
html body .recent-sales-table .rs-date,
html body .recent-sales-table .rs-float,
html body .recent-sales-table .rs-price {
  white-space: nowrap !important;
  text-overflow: ellipsis !important;
  overflow: hidden !important;
  min-width: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128785 */
"""

import tempfile, shutil

with open(CSS_PATH, "rb") as f:
    existing = f.read()

if b"#128780" in existing and b"#128785" in existing:
    print("Already appended ships #128780-#128785. Skipping.")
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

print(f"Appended ships #128780-#128785. New size: {len(new_blob)} bytes.")
