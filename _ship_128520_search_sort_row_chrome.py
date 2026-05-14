"""Atomic append of CSFLOAT 1:1 parity ships #128520-#128526 — search
results-page sort row chrome (the strip immediately above the listing grid
on /market that holds the search field, view/refresh controls, and the
sort dropdown).

Live measured against csfloat.com/search (signed-in session, viewport 1440)
2026-05-08 via mcp__playwright__browser_navigate + mcp__playwright__browser_evaluate.

CSFLOAT GROUND TRUTH — `app-search-bar > .container` (the row above the
listing grid):

  .container                  display:flex, flex-direction:row, align-items:center,
                              gap:10, jc:normal, padding:0, height 38

  Children, in DOM order (some hidden via display:none on desktop):
    .filter-btn              (display:none on desktop ≥ md)
    .drill-down              (display:none on desktop ≥ md)
    .gap                     spacer that flexes to push controls right
    .preset                  38x36 — wraps a mat-flat-button "Apply Saved Filters"
                               button: bg rgba(193,206,255,0.04), color #fff,
                                       border-radius 8, padding 0 10, min-width 36
                               icon (mat-icon): 18x18, fill+color #fff, font 18
    .refresh                 38x36 — wraps a mat-flat-button "Refresh Results"
                               (same chrome as .preset's button)
    app-search-filter-toggle-group  342x38 — segmented mat-button-toggle-group
                               group: bg rgba(193,206,255,0.04), border-radius 7,
                                      border 0, padding 0
                               toggle buttons inside: height 32, font 14/500,
                                      padding 0 12, color #fff
                               checked toggle: bg rgba(193,206,255,0.04)
                                      (same surface — the "checked" state has
                                       a brighter fill than unchecked which is
                                       transparent)
                               unchecked toggle: bg transparent
    .sort                    170x38 — wraps a mat-form-field with mat-select
                               (label "Sort By", value e.g. "Best Deals")
                               wrapper: bg rgba(193,206,255,0.04),
                                        padding 0 16, border-radius 6
                               label height 20, value font-size 16/Roboto

  Bottom of /search page: there is NO paginator. CSFloat uses infinite
  scroll (load-on-scroll). No `mat-paginator`, no `.pagination`, no
  load-more button rendered.

SBOXMARKET CURRENT STATE (live measured 2026-05-07 against
http://localhost:8080/market):

  .toolbar (around the sort row)
                              padding 10 12 ✗ csfloat is 0 (the row floats raw)
                              border 1px solid var(--line) ✗ csfloat: none
                              border-radius 10 ✗ csfloat: 0
                              background var(--bg-1) ✗ csfloat: transparent
                              gap 8 ✗ csfloat is 10
                              margin-bottom 12 — keep (visual separation)
  .sort-picker-chip
                              min-width 200 ✗ csfloat is 170
                              padding 8 10 8 12 ✗ csfloat is 0 16
                              border 1px solid var(--line) ✗ csfloat: none
                              background var(--bg-1) ✗ csfloat: rgba(193,206,255,0.04)
                              border-radius 8 ✗ csfloat is 6
                              font-size 12 ✗ csfloat is 14 (Roboto, but we keep
                                            Geist — only the SIZE & weight are
                                            CSFloat parity, the family is sbox)
  .toolbar-refresh
                              w/h 36 ✗ csfloat is 38x36 (rectangle, not square)
                              border-radius 999 ✗ csfloat is 8 (rounded square)
                              border 1px solid var(--line) ✗ csfloat: none
                              background var(--bg-1) ✗ csfloat: rgba(193,206,255,0.04)
  .results-meta
                              padding 14 0, fs 13, color var(--ink-3) — keep
                              (csfloat /search has NO results-count line on the
                               sort row — but sbox's "N listings found" copy is
                               useful UX so we keep the line, just push it under
                               the sort row instead of mixing with controls)

This batch APPENDS corrections that align the .toolbar wrapper + .sort-picker
chip + .toolbar-refresh button to CSFloat's measured geometry. The CSFloat
panel/brand/ink/hairline tokens are reused from the existing :root vars
(--bg-1, --ink, --ink-2, --line). No new tokens.

CRITICAL legal-safety: only geometry, padding, border-radius, background
opacity, font-size & font-weight are aligned. Visual content (icons, copy,
brand mark) is sboxmarket-original. We do NOT swap CSFloat's mat-icon
glyphs in — the existing sbox refresh/sort SVGs stay. The toggle group
on sboxmarket is existing dealsOnly/listingType chips (separate from this
ship); we only touch the WRAPPER chrome that hosts them.

Ship numbers #128520-#128526 land above the prior #128500-#128505 range.

NO Docker. APPEND-ONLY at end of design.css. Atomic tempfile + os.replace.
Mirror to build/ so a running gradle bootRun picks up without rebuild.
"""

import os, sys, tempfile

CSS = """
/* =====================================================================
   CSFLOAT-1:1 PARITY ships #128520-#128526 — SEARCH SORT ROW CHROME
   csfloat.com/search sort row (live measured 2026-05-08 viewport 1440):
     .container          flex row, gap 10, ai center, padding 0
     .preset/.refresh    38x36 mat-flat-buttons, bg rgba(193,206,255,0.04),
                         border-radius 8, padding 0 10, min-width 36,
                         icon 18x18, color #fff
     .toggle-group       bg rgba(193,206,255,0.04), border-radius 7,
                         items 32h, font 14/500, padding 0 12
     .sort               170x38 mat-form-field, bg rgba(193,206,255,0.04),
                         padding 0 16, border-radius 6
     paginator           NONE (infinite scroll)
   Sboxmarket .toolbar / .sort-picker-chip / .toolbar-refresh diverge on
   surface, radius, padding, dimensions; this batch APPENDS corrections
   to land the row at CSFloat's measured geometry while keeping sbox's
   own brand rhythm where divergence is intentional (results-meta copy
   below the row, Geist font family preserved).
   ===================================================================== */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128520 — strip .toolbar wrapper chrome
   csfloat .search-bar .container has padding:0, no background, no border,
   no border-radius — the row floats raw above the grid with the controls
   themselves carrying the visual weight (each control has its own
   panel-tinted bg). Sbox .toolbar wraps in a panel card (bg var(--bg-1),
   border, radius 10, padding 10/12) which double-stacks the panel
   surface. Strip the wrapper to a transparent flex shell so the
   individual chips read as the surface, like csfloat.
*/
.market-toolbar.toolbar,
.toolbar:not(nav .toolbar):not(.csfloat-subnav .toolbar):not(.market-stats-strip .toolbar) {
  background: transparent !important;
  border: 0 !important;
  border-radius: 0 !important;
  padding: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128520 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128521 — toolbar flex gap to 10
   csfloat .container uses gap:10 between flex children. Sbox .toolbar
   used gap:8 which collapsed the controls 2px tighter than csfloat's
   measured rhythm. Pin gap to 10 for parity with csfloat's row spacing.
*/
.market-toolbar.toolbar,
.toolbar:not(nav .toolbar):not(.csfloat-subnav .toolbar):not(.market-stats-strip .toolbar) {
  gap: 10px !important;
  align-items: center !important;
}
/* END CSFLOAT-1:1 PARITY ship #128521 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128522 — sort-picker chip surface
   csfloat .sort > mat-form-field uses bg rgba(193,206,255,0.04) — the
   panel-tint that all sort-row chips share. Sbox .sort-picker-chip used
   var(--bg-1) (a solid panel) with a 1px hairline border. Strip the
   border and replace the bg with the CSFloat panel-tint so the chip
   reads as inset on the page rather than as an overlay panel.
*/
.sort-picker-chip {
  background: rgba(193, 206, 255, 0.04) !important;
  border: 0 !important;
}
.sort-picker-chip:hover,
.sort-picker.open .sort-picker-chip {
  background: rgba(193, 206, 255, 0.08) !important;
  border: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128522 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128523 — sort-picker chip geometry
   csfloat .sort wrapper measures 170x38 with padding 0 16 and
   border-radius 6. Sbox .sort-picker-chip measured min-width 200
   (too wide), padding 8 10 8 12 (asymmetric), border-radius 8.
   Pin to csfloat's measured geometry so the chip's silhouette and
   padding rhythm match the rest of the row (preset/refresh use 8,
   toggle-group uses 7, sort uses 6 — the sort field is intentionally
   the lowest radius to read as a form-field rather than a button).
*/
.sort-picker-chip {
  min-width: 170px !important;
  height: 38px !important;
  padding: 0 16px !important;
  border-radius: 6px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128523 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128524 — sort-picker chip typography
   csfloat .sort > mat-select value is font-size 16 (Roboto) — but the
   row's mat-button-toggle labels which sit immediately to its left use
   font-size 14/500. The two co-exist because csfloat's sort field uses
   the Material default while the toggle uses a smaller label. For sbox
   we keep our Geist family but bump the chip label from 12 to 14 to
   match the toggle group's label weight + size — the larger text reads
   correctly against the new 38h chip from ship #128523.
*/
.sort-picker-chip,
.sort-picker-label {
  font-size: 14px !important;
  font-weight: 500 !important;
  color: rgb(255, 255, 255) !important;
}
.sort-picker-caret {
  font-size: 11px !important;
  color: rgb(158, 167, 177) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128524 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128525 — toolbar-refresh button geometry
   csfloat .refresh button measures 38x36 with bg rgba(193,206,255,0.04),
   border-radius 8, padding 0 10, min-width 36 — a rounded RECTANGLE,
   not a circle. Sbox .toolbar-refresh was 36x36 with border-radius 999
   (full circle) and a 1px hairline border on var(--bg-1). Pin to
   csfloat's measured rectangle so the refresh button's silhouette
   matches the preset button (also 38x36) and the row's overall rhythm.
   Drop the hairline since the panel-tint bg carries the affordance.
*/
.toolbar-refresh {
  width: 38px !important;
  height: 36px !important;
  min-width: 36px !important;
  padding: 0 10px !important;
  background: rgba(193, 206, 255, 0.04) !important;
  border: 0 !important;
  border-radius: 8px !important;
  color: rgb(255, 255, 255) !important;
}
.toolbar-refresh:hover {
  background: rgba(193, 206, 255, 0.10) !important;
  border: 0 !important;
}
.toolbar-refresh:focus-visible {
  outline: none;
  border: 0 !important;
  box-shadow: 0 0 0 2px rgba(35, 123, 255, 0.55) !important;
}
/* The refresh icon inside lands at csfloat's 18px metric */
.toolbar-refresh svg,
.toolbar-refresh .material-icon,
.toolbar-refresh .material-symbols-rounded {
  width: 18px !important;
  height: 18px !important;
  font-size: 18px !important;
  color: rgb(255, 255, 255) !important;
  fill: rgb(255, 255, 255) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128525 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128526 — results-meta line below the row
   csfloat /search does NOT print a "X listings found" line on its sort
   row — the toggle group ("All Items / Sticker Combos / Unique Items")
   carries that semantic load. Sbox keeps the textual count for clarity
   (it's a useful filter-feedback signal) but should drop the count line
   into a quieter typographic position so it reads as PAGE METADATA
   instead of competing with the sort controls. Pin .results-meta to
   13/500, ink-3 color, padding 12 0 6 (tighter top, room above the
   grid), and remove the flex-between justification so the count
   anchors flush-left under the sort row instead of stretching across
   the panel.
*/
.results-meta {
  padding: 12px 0 6px !important;
  font-size: 13px !important;
  font-weight: 500 !important;
  color: rgb(158, 167, 177) !important;
  justify-content: flex-start !important;
  gap: 6px !important;
}
.results-meta b,
.results-meta strong {
  color: rgb(255, 255, 255) !important;
  font-weight: 600 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128526 */
"""

target = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"
mirror = r"C:\Users\WW\Desktop\sboxmarket\build\resources\main\static\css\design.css"

with open(target, 'rb') as f:
    f.seek(-3072, os.SEEK_END)
    tail = f.read().decode('utf-8', errors='replace')
if 'ship #128520' in tail:
    print('ALREADY APPENDED - ABORT')
    sys.exit(0)

def atomic_append(path, payload):
    tmpfd, tmpname = tempfile.mkstemp(suffix='.append', dir=os.path.dirname(path))
    try:
        with open(path, 'rb') as orig, os.fdopen(tmpfd, 'wb') as out:
            while True:
                buf = orig.read(1 << 20)
                if not buf:
                    break
                out.write(buf)
            out.write(payload.encode('utf-8'))
        os.replace(tmpname, path)
    except Exception:
        if os.path.exists(tmpname):
            try: os.remove(tmpname)
            except: pass
        raise

atomic_append(target, CSS)
print('APPENDED', len(CSS), 'bytes -> src/main/.../design.css')

if os.path.exists(mirror):
    atomic_append(mirror, CSS)
    print('MIRRORED', len(CSS), 'bytes -> build/resources/main/.../design.css')

print('SHIPS #128520-#128526 LANDED')
