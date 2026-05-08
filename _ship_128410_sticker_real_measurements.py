"""Atomic append of CSFLOAT 1:1 parity ships #128410-#128415 — sticker preview row chrome.

Live-measured against csfloat.com/item/972716531467291016 (signed-in session,
viewport 2560x1305) 2026-05-08 via mcp__playwright__browser_navigate +
mcp__playwright__browser_evaluate. The csfloat /item/<id> detail page renders
sticker thumbnails as `<div class="sticker-container">` wrappers around
`<app-sticker-view>` Angular components, each containing a flat `<img>` with
`height` attr only. NO mat-card chrome around individual stickers.

Measured surfaces (computed styles + bounding rects):

  ── List/grid view sticker row (.sticker-container in card) ─
  Container .sticker-container       193.3 x 33  display:block padding:0
                                     bg transparent, border 0, radius 0
                                     no margin (parent .container handles)
  Inside the container, an inline-flow `<app-sticker-view>` host wraps
  4-5 `<div class="mat-mdc-tooltip-trigger sticker">` with each
  `<img height="29">` inside. `<img>` natural=256x192 (4:3),
  rendered=38.7 x 29.0  (height attr: 29; width auto from natural ratio
  256/192 * 29 = 38.666 → 38.7).

  Per-sticker thumb computed:
     width:           38.7  (auto from height + natural ratio)
     height:          29
     filter:          contrast(1)
     no border, no background, no padding, no border-radius
     parent .sticker div: display:inline-block by default flow,
                          NO margin, NO padding, NO bg, NO border
  Pitch between adjacent stickers in the same row was sticker-width
  exact (e.g. 38.7→465.7→504.3→543.0 etc → 38.7 pitch = 0px gap).

  ── Detail view sticker row (.stickers under main item-image) ─
  Container .stickers                holds 4-5 `.sticker-container` flex children
                                     (no card chrome on container itself)
  Each detail-view sticker thumb:
     width:           60.0  (auto from height + natural ratio)
     height:          45.0  (height attr ≈45)
     filter:          contrast(1)
     no border, no background, no padding, no border-radius
  Detail-view positions in row at x=487, 547, 607, 667 → 60px pitch =
  0px gap between adjacent thumbs (img elements packed tight via
  inline flow, NOT flex-gap).

  ── Sticker percentage badge (.sticker-percentage) ─
  Optional element rendered next to thumbs in detail view when wear>0
  display:inline-flex, font:600 9px/1 mono, color: ink-2 #9ea7b1
  bg transparent, no card chrome, sized to text (≈30 x 14)

These ships REPLACE the prior approximations:
  - .csfloat-sticker (16x16, 1:1, with border + bg-2) ← WRONG: real is 38.7x29 4:3
  - .csfloat-sticker-slot (flex 1 1 0 + aspect-ratio 1 + 6px padding +
    1px border + 6px radius + bg-1) ← WRONG: real is naked thumb, no chrome
  - .csfloat-stickers-row (8px gap on detail) ← WRONG: real is 0 gap
  - .csfloat-stickers (12px padding + 1px border + 10px radius) ← WRONG:
    real container has no card chrome around the row itself

Tokens used (matching --cf-* memory and tokens block):
  --cf-panel              rgb(27, 29, 36)
  --cf-brand              rgb(35, 123, 255)
  --cf-ink-2              rgb(158, 167, 177)
  --cf-hair               rgba(255, 255, 255, 0.06)

NO Docker. APPEND-ONLY at end of design.css. Atomic tempfile + os.replace.
Idempotency: first 4096 bytes of file tail checked for ship #128410 marker.
"""

import os, sys, tempfile

CSS = """
/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128410 — sticker thumb dimensions (LIST view)
   Measured csfloat /item/<id> list-view sticker (img inside .sticker
   under .sticker-container in item-card row):
     width:    38.7 (auto from height attr 29 + natural 256x192 = 4:3)
     height:   29.0
     filter:   contrast(1)
     NO border, NO bg, NO padding, NO border-radius
   Sboxmarket .csfloat-sticker (line 31523) currently renders 16x16 1:1
   square with --bg-2 background and 1px --line border + 3px radius +
   1px image padding. PIN to the naked 4:3 thumb measured live.
*/
body .csfloat-sticker,
body .csfloat-stickers-row > .csfloat-sticker,
body .csfloat-card .csfloat-sticker,
body .csfloat-grid-card .csfloat-sticker,
body .item-card .csfloat-sticker,
body .csfloat-card-stickers > .csfloat-sticker {
  width: 38.7px !important;
  height: 29px !important;
  background: transparent !important;
  border: 0 !important;
  border-radius: 0 !important;
  padding: 0 !important;
  display: inline-block !important;
  flex-shrink: 0 !important;
  vertical-align: middle !important;
  box-sizing: content-box !important;
}
body .csfloat-sticker > img,
body .csfloat-stickers-row > .csfloat-sticker > img,
body .csfloat-card .csfloat-sticker > img,
body .csfloat-grid-card .csfloat-sticker > img {
  width: 38.7px !important;
  height: 29px !important;
  padding: 0 !important;
  object-fit: contain !important;
  filter: contrast(1) !important;
  display: block !important;
}
/* END CSFLOAT-1:1 PARITY ship #128410 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128411 — sticker row layout (LIST view)
   Measured csfloat .sticker-container (parent of stickers in card row):
     display:    block (children inline-flow via app-sticker-view)
     gap:        normal (NO flex-gap, stickers packed tight)
     pitch:      38.7px between adjacent thumbs = 0px gap
     padding:    0
     bg:         transparent
     border:     0
     radius:     0
     dims:       193.3 x 33  (4-5 thumbs at 38.7w each, 33h provides
                              4px vertical breathing for the 29h img)
   Sboxmarket .csfloat-stickers-row (line 31517) currently uses
   display:inline-flex with 3px gap and align-items:center + 4px margin.
   PIN to display:flex (or inline-flex) with 0 gap and the 33h container.
*/
body .csfloat-stickers-row,
body .csfloat-card .csfloat-stickers-row,
body .csfloat-grid-card .csfloat-stickers-row,
body .item-card .csfloat-stickers-row {
  display: inline-flex !important;
  flex-direction: row !important;
  align-items: center !important;
  gap: 0 !important;
  padding: 0 !important;
  margin: 0 !important;
  background: transparent !important;
  border: 0 !important;
  border-radius: 0 !important;
  height: 33px !important;
  min-width: 0 !important;
  flex-wrap: nowrap !important;
  overflow: hidden !important;
}
/* END CSFLOAT-1:1 PARITY ship #128411 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128412 — sticker slot (DETAIL view)
   Measured csfloat /item/<id> detail-view sticker thumb:
     width:    60.0 (auto from height ≈45 + natural ratio 256x192)
     height:   45.0
     filter:   contrast(1)
     NO border, NO bg, NO padding, NO border-radius
     positions at x=487, 547, 607, 667 → 60px pitch = 0px gap
   Sboxmarket .csfloat-sticker-slot (line 70556) currently uses flex
   1 1 0 + aspect-ratio 1 (square) + 6px padding + 1px --line border +
   6px radius + --bg background. REPLACE with the naked 60x45 4:3 thumb.
*/
body .csfloat-sticker-slot,
body .csfloat-stickers-row > .csfloat-sticker-slot,
body .csfloat-stickers .csfloat-sticker-slot,
body .csfloat-stickers-grid-cell {
  flex: 0 0 auto !important;
  width: 60px !important;
  height: 45px !important;
  aspect-ratio: 4 / 3 !important;
  padding: 0 !important;
  background: transparent !important;
  border: 0 !important;
  border-radius: 0 !important;
  display: inline-block !important;
  vertical-align: middle !important;
  min-width: 0 !important;
  flex-shrink: 0 !important;
  box-sizing: content-box !important;
}
body .csfloat-sticker-slot.is-empty,
body .csfloat-stickers-grid-cell.is-empty {
  opacity: 0.45 !important;
  border: 0 !important;
}
body .csfloat-sticker-slot > .csfloat-sticker-slot-img,
body .csfloat-sticker-slot > img,
body .csfloat-stickers-grid-cell > .csfloat-stickers-grid-cell-img,
body .csfloat-stickers-grid-cell > img {
  width: 60px !important;
  height: 45px !important;
  aspect-ratio: 4 / 3 !important;
  padding: 0 !important;
  background: transparent !important;
  border: 0 !important;
  border-radius: 0 !important;
  display: block !important;
  object-fit: contain !important;
  filter: contrast(1) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128412 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128413 — sticker container (DETAIL view)
   Measured csfloat .stickers / .sticker-container around detail-view
   thumb row:
     display:    block (or inline flow from app-sticker-view children)
     gap:        normal (NO flex gap, 0 px pitch overlap-tight)
     padding:    0
     bg:         transparent
     border:     0
     radius:     0
   Sboxmarket .csfloat-stickers (line 70526) currently uses flex column
   with 8px gap, --bg-1 background, 1px --line border, 10px radius and
   12px padding. REPLACE with naked container that lets the row breathe
   only via the parent surface chrome (already pinned by detail card).
*/
body .csfloat-stickers,
body .csfloat-stickers-grid {
  display: flex !important;
  flex-direction: column !important;
  gap: 6px !important;
  background: transparent !important;
  border: 0 !important;
  border-radius: 0 !important;
  padding: 0 !important;
}
body .csfloat-stickers > .csfloat-stickers-row,
body .csfloat-stickers-grid > .csfloat-stickers-grid-row {
  display: flex !important;
  flex-direction: row !important;
  align-items: center !important;
  gap: 0 !important;
  padding: 0 !important;
  margin: 0 !important;
  background: transparent !important;
  border: 0 !important;
  border-radius: 0 !important;
  flex-wrap: nowrap !important;
  height: auto !important;
  min-height: 45px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128413 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128414 — sticker tooltip-trigger affordance
   Measured csfloat .mat-mdc-tooltip-trigger.sticker (parent div of img):
     display:        inline (default)
     cursor:         pointer (mat-mdc-tooltip-trigger default)
     bg:             transparent
     border:         0
     padding:        0
     touch-action:   manipulation (mat tooltip default)
   Sboxmarket has no equivalent class in the sticker card chrome — the
   .csfloat-sticker host div should adopt the same vocabulary so live
   tooltip / hover affordances read the same. Pin a generic hover state
   (filter brightness 1.1) matching the live mat-tooltip hover behavior
   without adding a card border (which csfloat does NOT use).
*/
body .csfloat-sticker,
body .csfloat-sticker-slot,
body .csfloat-stickers-grid-cell,
body .csfloat-sticker-tooltip-trigger {
  cursor: pointer !important;
  -webkit-tap-highlight-color: transparent !important;
  touch-action: manipulation !important;
  user-select: none !important;
  position: relative !important;
}
body .csfloat-sticker:hover > img,
body .csfloat-sticker-slot:hover > img,
body .csfloat-sticker-slot:hover > .csfloat-sticker-slot-img > img,
body .csfloat-stickers-grid-cell:hover > img {
  filter: contrast(1) brightness(1.1) !important;
  transition: filter 120ms cubic-bezier(0.2, 0.8, 0.2, 1) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128414 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128415 — sticker percentage badge + name label
   Measured csfloat .sticker-percentage (rendered when wear>0):
     display:        inline-flex
     font:           600 9px / 1 mono
     color:          rgb(158, 167, 177)  (ink-2)
     bg:             transparent
     padding:        0
     border:         0
     letter-spacing: 0.02em
     font-feature-settings: "tnum"
   The sticker NAME is rendered only inside the mat-tooltip hover
   bubble — there is NO inline text under each thumb in the live
   csfloat /item/<id> detail card. Sboxmarket .csfloat-sticker-slot-name
   (line 70587) currently renders an inline 10px name label under each
   thumb — REMOVE it from the live row to match csfloat (the tooltip
   takes over). Hide the name via display:none and pin the percentage
   to the live mono 9/600 ink-2 vocabulary.
*/
body .csfloat-sticker-slot-name,
body .csfloat-stickers-grid-cell-name {
  display: none !important;
}
body .csfloat-sticker-slot-wear,
body .csfloat-sticker-percentage,
body .csfloat-sticker-slot-pct {
  font-family: var(--mono, ui-monospace, "SFMono-Regular", monospace) !important;
  font-size: 9px !important;
  font-weight: 600 !important;
  line-height: 1 !important;
  letter-spacing: 0.02em !important;
  color: rgb(158, 167, 177) !important;
  background: transparent !important;
  padding: 0 !important;
  border: 0 !important;
  border-radius: 0 !important;
  font-feature-settings: "tnum" !important;
  display: inline-flex !important;
  align-items: center !important;
}
body .csfloat-sticker-slot-pct {
  height: 2px !important;
  width: 60px !important;
  background: rgba(255, 255, 255, 0.06) !important;
  border-radius: 1px !important;
  overflow: hidden !important;
  margin: 2px 0 0 !important;
}
body .csfloat-sticker-slot-pct > .csfloat-sticker-slot-pct-fill {
  height: 100% !important;
  background: rgb(35, 123, 255) !important;
  transition: width 280ms cubic-bezier(0.2, 0.8, 0.2, 1) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128415 */
"""

target = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"
with open(target, 'rb') as f:
    f.seek(-4096, os.SEEK_END)
    tail = f.read().decode('utf-8', errors='replace')
if 'ship #128410' in tail:
    print('ALREADY APPENDED - ABORT')
    sys.exit(0)

tmpfd, tmpname = tempfile.mkstemp(suffix='.append', dir=os.path.dirname(target))
try:
    with open(target, 'rb') as orig, os.fdopen(tmpfd, 'wb') as out:
        while True:
            buf = orig.read(1 << 20)
            if not buf:
                break
            out.write(buf)
        out.write(CSS.encode('utf-8'))
    os.replace(tmpname, target)
    print('APPENDED', len(CSS), 'bytes -> ships #128410-#128415')
except Exception as e:
    if os.path.exists(tmpname):
        try: os.remove(tmpname)
        except: pass
    raise
