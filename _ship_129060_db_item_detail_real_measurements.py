"""Atomic append of CSFLOAT 1:1 parity ships #129060-#129066 — per-item
detail page chrome (csfloat /item/<id> = the 'db page' you reach by drilling
into a /db grid tile).

Live-measured against csfloat.com/item/921281424596796604 (anon-accessible
sticker page, viewport 1440) 2026-05-08 via mcp__playwright__browser_navigate
+ mcp__playwright__browser_evaluate. Earlier ships #14700-#14705 (db-variant
body, recent-sales row, float-rank ladder) approximated; these ships REPLACE
the approximations with the exact bg/border/radius/font/gap tokens read from
the live csfloat per-item detail surface.

CSFLOAT per-item detail anatomy (one-pass, signed-out, vp 1440):

  ── body ────────────────────────────────────────────────
  bg                      rgb(21, 23, 28)
  padding                 0 60px (gutters)
  color                   #ffffff
  font                    16/400 Roboto

  ── app-item-container (content column) ────────────────
  width                   955 (centered between 425..1380)
  display                 block, no own bg/border

  ── app-skin-bar (carousel breadcrumb strip) ───────────
  full-bleed              ~1320 wide (vp 1440 minus rail) x 38h
  bg                      transparent
  inner                   mat-button-toggle-group + arrows (chevrons)

  ── item-detail mat-card (the BIG panel) ───────────────
  size                    494 x 569 (max-width 494)
  bg                      rgb(27, 29, 36)         ← --cf-panel
  border                  0 (transparent)
  border-radius           12px
  box-shadow MD-1         0 2px 1px -1px rgba(0,0,0,0.2),
                          0 1px 1px 0 rgba(0,0,0,0.14),
                          0 1px 3px 0 rgba(0,0,0,0.12)
  display                 flex, flex-direction column
  ┌─ .item-grid ─ width 494 x 569 (carries header / image / footer)
  │  ┌─ .header (top title row) ──────────────
  │  │   height          36, padding 0, display flex, gap 10
  │  │   .item-name      16/500 #ffffff (or rare-tier color)
  │  │   .subtext        14/500 #9ea7b1 (ink-2), margin-top 6
  │  ├─ .image (main image area) ─────────────
  │  │   494 x 378, no padding
  │  │   inner img.item-img full-bleed
  │  └─ .footer (price / actions block) ──────
  │      494 x 129, padding 12, display flex column, gap 10
  │      .price-row      470 x 27, display flex, gap 6
  │        .price        22/500 #ffffff
  │        app-reference-widget   65 x 24 chip
  │      .action-buttons 470 x 40 (.actions same)

  ── item-history-chart (price/volume chart) ─────────────
  width                   994, height 252
  canvas                  898 x 170 inside
  range toggle group      205 x 31, bg rgba(193,206,255,0.04),
                          border-radius 7  (mat-button-toggle-group)

  ── item-float-bar (float ladder) ──────────────────────
  width                   200 x 40 — one-line ladder

  ── app-order-table (Material table — "Buy Orders" sidebar) ─
  width                   250 x 510 (left rail at x=217)
  inner                   <table mat-table class="slimmed-table">
                          <thead> "Price" / "Quantity" cols
                          <tbody> .mat-mdc-row 42h, 14/500
                          NO own bg, transparent panel chrome
  Header row              .mat-mdc-header-row 14/500 #9ea7b1 (ink-2)

  ── app-similar-items (right rail / related grid) ──────
  positioned right of mat-card (x=1001)
  inner                   .container with item-cards (when populated)

  ── app-reference-widget (price chip in price-row) ─────
  ~65 x 24 chip, bg rgba(255,255,255,0.04) (--cf-hair),
  padding 4 6, border-radius 20, mat-icon 19x19 #9ea7b1

  ── app-sticker-view (sticker pill row) ────────────────
  ~155 x 19 inline pill (5x at most)

These ships pin the existing sboxmarket .modal.item-page surface AND the
Angular tag selectors (item-detail, item-float-bar, item-history-chart,
app-order-table, app-similar-items, app-reference-widget, app-sticker-view,
app-skin-bar) so the same parity tokens hit both the in-app modal mounted
detail and any future mat-card-rendered route.

PRIOR coverage: ship #14700-#14705 approximated db-variant body + recent-
sales hover, ship #15300+ approximated chip backgrounds. These ships pin
the EXACT tokens read live with !important + html-body specificity to win
against earlier approximations.

Tokens used:
  --cf-panel       rgb(27, 29, 36)              ← mat-card bg
  --cf-brand       rgb(35, 123, 255)            ← spec brand
  --cf-ink-2       rgb(158, 167, 177)           ← subtext / chip text
  --cf-hair        rgba(255, 255, 255, 0.06)    ← reference chip bg
  --cf-row-hover   rgba(193, 206, 255, 0.04)    ← row hover / toggle bg
  --cf-card-shadow MD-1 3-stop elevation
  --cf-radius-card 12px
  --cf-radius-tog  7px

NO Docker. APPEND-ONLY at end of design.css. Atomic tempfile + os.replace.
Idempotency: first 4096 bytes of file tail checked for ship #129060 marker.
"""

import os, sys, tempfile

CSS = """
/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #129060 — item-detail mat-card panel canonical.
   Measured csfloat /item/<id> central panel (item-detail > mat-card):
     width:            494px (max-width 494)
     height:           569px (auto, content-driven)
     bg:               rgb(27, 29, 36)  ← --cf-panel token
     border:           0 (transparent shell)
     border-radius:    12px
     box-shadow MD-1:  0 2px 1px -1px rgba(0,0,0,0.2),
                       0 1px 1px 0 rgba(0,0,0,0.14),
                       0 1px 3px 0 rgba(0,0,0,0.12)
     display:          flex, flex-direction: column
   Sboxmarket exposes the same surface as `.modal.item-page` plus the new
   csfloat-mounted Angular tag selectors `item-detail mat-card` (when the
   detail surface is rendered as a route, not a modal). Pin both flavours
   to the canonical csfloat panel chrome so the central card reads
   identical regardless of mount path.
*/
html body item-detail > mat-card,
html body item-detail mat-card.mat-mdc-card,
html body .csfloat-item-detail > .item-detail-card,
html body .modal.item-page > .item-detail-card,
html body .modal.item-page .item-detail-panel {
  background: rgb(27, 29, 36) !important;
  border: 0 !important;
  border-radius: 12px !important;
  box-shadow:
    0 2px 1px -1px rgba(0, 0, 0, 0.2),
    0 1px 1px 0 rgba(0, 0, 0, 0.14),
    0 1px 3px 0 rgba(0, 0, 0, 0.12) !important;
  display: flex !important;
  flex-direction: column !important;
  max-width: 494px !important;
  padding: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #129060 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #129061 — item-detail .header (title row).
   Measured csfloat item-detail .header:
     padding:          0
     display:          flex, flex-direction: row, gap: 10
     height:           ~36 (content driven)
   Inside the header:
     .item-name:       16/500 #ffffff (or rarity-tier color)
     .subtext:         14/500 #9ea7b1 (ink-2), margin-top 6
   Sboxmarket .modal.item-page header used .modal-header / .modal-title
   shape; pin both the header row and the inner item-name/subtext typo
   tokens so the title block matches csfloat 1:1.
*/
html body item-detail .header,
html body .modal.item-page .item-detail-header,
html body .csfloat-item-detail .item-detail-header {
  padding: 0 !important;
  display: flex !important;
  flex-direction: row !important;
  gap: 10px !important;
  align-items: center !important;
  background: transparent !important;
  border: 0 !important;
}
html body item-detail .item-name,
html body .modal.item-page .item-detail-header .item-name,
html body .modal.item-page .modal-title,
html body .csfloat-item-detail .item-name {
  font-size: 16px !important;
  font-weight: 500 !important;
  line-height: 1.2 !important;
  color: rgb(255, 255, 255) !important;
  margin: 0 !important;
}
html body item-detail .subtext,
html body .modal.item-page .item-detail-header .subtext,
html body .modal.item-page .modal-subtitle,
html body .csfloat-item-detail .subtext {
  font-size: 14px !important;
  font-weight: 500 !important;
  line-height: 1.2 !important;
  color: rgb(158, 167, 177) !important;
  margin: 6px 0 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #129061 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #129062 — item-detail .image area.
   Measured csfloat item-detail .image:
     width:            494
     height:           378
     padding:          0
     display:          flex (full-bleed image inside)
   Inner img.item-img fills the area edge-to-edge; the panel border-
   radius (12px) clips the top corners. Pin both the image area and the
   inner img to the canonical chrome so the central image fills the
   panel exactly the way csfloat presents it. .modal-preview is the
   sboxmarket-side analogue of the .image area.
*/
html body item-detail .image,
html body .modal.item-page .modal-preview,
html body .modal.item-page .item-detail-image,
html body .csfloat-item-detail .item-detail-image {
  width: 494px !important;
  max-width: 494px !important;
  height: 378px !important;
  padding: 0 !important;
  display: flex !important;
  align-items: center !important;
  justify-content: center !important;
  background: transparent !important;
  overflow: hidden !important;
}
html body item-detail .image > img,
html body item-detail .image .item-img,
html body .modal.item-page .modal-preview > img,
html body .modal.item-page .item-detail-image > img {
  width: 100% !important;
  height: auto !important;
  max-height: 378px !important;
  object-fit: contain !important;
  display: block !important;
}
/* END CSFLOAT-1:1 PARITY ship #129062 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #129063 — item-detail .footer + .price-row +
   .price + .action-buttons. Measured csfloat item-detail .footer:
     width:            494, height: 129
     padding:          12px (all sides)
     display:          flex, flex-direction: column, gap: 10
   Inside .footer:
     .price-row:       470 x 27, display flex, gap: 6
       .price:         22/500 #ffffff (left)
       app-reference-widget: 65 x 24 chip on right
     .action-buttons:  470 x 40 (alias .actions)
   Sboxmarket maps the same shape onto .modal.item-page > .modal-actions
   and the .item-rail-actions-* button rail. Pin both flavours so the
   bottom block reads canonical csfloat regardless of layout source.
*/
html body item-detail .footer,
html body .modal.item-page .modal-actions,
html body .modal.item-page .item-detail-footer,
html body .csfloat-item-detail .item-detail-footer {
  padding: 12px !important;
  display: flex !important;
  flex-direction: column !important;
  gap: 10px !important;
  background: transparent !important;
  border: 0 !important;
  width: 100% !important;
  max-width: 494px !important;
  box-sizing: border-box !important;
}
html body item-detail .price-row,
html body .modal.item-page .modal-price-row,
html body .modal.item-page .item-detail-price-row,
html body .csfloat-item-detail .price-row {
  display: flex !important;
  flex-direction: row !important;
  align-items: center !important;
  gap: 6px !important;
  width: 100% !important;
  height: 27px !important;
  background: transparent !important;
}
html body item-detail .price-row .price,
html body item-detail .footer .price,
html body .modal.item-page .item-detail-price,
html body .modal.item-page .modal-price-row .price {
  font-size: 22px !important;
  font-weight: 500 !important;
  line-height: 27px !important;
  color: rgb(255, 255, 255) !important;
  margin: 0 !important;
}
html body item-detail .action-buttons,
html body item-detail .footer .actions,
html body .modal.item-page .item-rail-actions,
html body .modal.item-page .modal-actions-row {
  display: flex !important;
  flex-direction: row !important;
  gap: 8px !important;
  width: 100% !important;
  height: 40px !important;
  background: transparent !important;
}
/* END CSFLOAT-1:1 PARITY ship #129063 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #129064 — app-order-table Material table chrome.
   Measured csfloat /item/<id> left rail (app-order-table):
     width:            250 (rail), height: 510
     inner:            table.mat-mdc-table.slimmed-table
       <thead> .mat-mdc-header-row  → 14/500 ink-2 column titles
                                       columns: "Price" / "Quantity"
       <tbody> .mat-mdc-row         → 42h, 14/500 white
     bg:               transparent (no own panel chrome)
   Sboxmarket exposes the same surface inside the item-page modal as the
   .buy-orders-table / .modal-orders-table / .recent-sales-table block.
   Pin both the Material table and the sboxmarket-side selectors so the
   left-rail order table reads canonical csfloat.
*/
html body app-order-table,
html body app-order-table .container,
html body .modal.item-page .buy-orders-table,
html body .modal.item-page .modal-orders-table {
  background: transparent !important;
  border: 0 !important;
  padding: 0 !important;
  width: 250px !important;
  max-width: 250px !important;
}
html body app-order-table .mat-mdc-table,
html body app-order-table table,
html body .modal.item-page .buy-orders-table table,
html body .modal.item-page .modal-orders-table table {
  background: transparent !important;
  width: 100% !important;
  border-collapse: collapse !important;
  font-size: 14px !important;
}
html body app-order-table .mat-mdc-header-row,
html body app-order-table thead tr,
html body .modal.item-page .buy-orders-table thead tr,
html body .modal.item-page .modal-orders-table thead tr {
  height: 36px !important;
  background: transparent !important;
  font-size: 14px !important;
  font-weight: 500 !important;
  color: rgb(158, 167, 177) !important;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06) !important;
}
html body app-order-table .mat-mdc-row,
html body app-order-table tbody tr,
html body .modal.item-page .buy-orders-table tbody tr,
html body .modal.item-page .modal-orders-table tbody tr {
  height: 42px !important;
  background: transparent !important;
  font-size: 14px !important;
  font-weight: 500 !important;
  color: rgb(255, 255, 255) !important;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06) !important;
  transition: background-color 120ms cubic-bezier(0.4, 0, 0.2, 1) !important;
}
html body app-order-table .mat-mdc-row:hover,
html body app-order-table tbody tr:hover,
html body .modal.item-page .buy-orders-table tbody tr:hover,
html body .modal.item-page .modal-orders-table tbody tr:hover {
  background-color: rgba(193, 206, 255, 0.04) !important;
}
html body app-order-table .mat-mdc-row:last-child,
html body app-order-table tbody tr:last-child,
html body .modal.item-page .buy-orders-table tbody tr:last-child,
html body .modal.item-page .modal-orders-table tbody tr:last-child {
  border-bottom: 0 !important;
}
html body app-order-table .mat-mdc-cell,
html body app-order-table td {
  padding: 0 12px !important;
  white-space: nowrap !important;
  text-overflow: ellipsis !important;
  overflow: hidden !important;
}
/* END CSFLOAT-1:1 PARITY ship #129064 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #129065 — item-history-chart range toggle group.
   Measured csfloat item-history-chart range chip:
     width:            205, height: 31
     bg:               rgba(193, 206, 255, 0.04)  ← --cf-row-hover
     border-radius:    7px
     display:          inline-flex
   The mat-button-toggle-group is the small "1d / 7d / 30d / 90d / All"
   strip below the chart canvas. Sboxmarket exposes the same surface as
   .chart-range-row / .chart-range-btn — pin the wrapper to the canonical
   csfloat 31h pill at radius 7 with the Material toggle bg, and pin the
   inner buttons to the same 14/500 typography csfloat uses. Active state
   gets the brand fill ring measured on csfloat.
*/
html body item-history-chart mat-button-toggle-group,
html body item-history-chart .range,
html body .modal.item-page .chart-range-group,
html body .modal.item-page .chart-range-row {
  height: 31px !important;
  background: rgba(193, 206, 255, 0.04) !important;
  border-radius: 7px !important;
  display: inline-flex !important;
  align-items: center !important;
  border: 0 !important;
  padding: 0 !important;
  overflow: hidden !important;
}
html body item-history-chart mat-button-toggle,
html body item-history-chart .range > .mat-button-toggle,
html body .modal.item-page .chart-range-btn {
  height: 31px !important;
  padding: 0 12px !important;
  background: transparent !important;
  border: 0 !important;
  border-radius: 0 !important;
  font-size: 14px !important;
  font-weight: 500 !important;
  line-height: 31px !important;
  color: rgb(255, 255, 255) !important;
  display: inline-flex !important;
  align-items: center !important;
  cursor: pointer !important;
}
html body item-history-chart mat-button-toggle:hover,
html body .modal.item-page .chart-range-btn:hover {
  background: rgba(255, 255, 255, 0.04) !important;
}
html body item-history-chart mat-button-toggle.mat-button-toggle-checked,
html body item-history-chart mat-button-toggle[aria-pressed="true"],
html body .modal.item-page .chart-range-btn.active,
html body .modal.item-page .chart-range-btn[aria-pressed="true"] {
  background: rgba(35, 123, 255, 0.16) !important;
  color: rgb(35, 123, 255) !important;
}
/* END CSFLOAT-1:1 PARITY ship #129065 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #129066 — app-skin-bar / app-similar-items /
   app-reference-widget / app-sticker-view baseline chrome.
   Measured csfloat /item/<id> ancillary surfaces:
     app-skin-bar:           full-bleed strip, height 38, transparent bg
                             (carousel chevrons + mat-button-toggle-group
                             of category names — same vocabulary as the
                             db-page category carousel measured in #128406)
     app-similar-items:      right rail container (x=1001), no own panel
                             chrome — relies on item-card panels inside.
                             The empty-state container has no bg/border.
     app-reference-widget:   65 x 24 chip in the price-row
                             bg rgba(255,255,255,0.04)  (--cf-hair),
                             padding 4 6, border-radius 20,
                             inner mat-icon 19x19 ink-2
     app-sticker-view:       155 x 19 inline pill row, transparent bg,
                             displays sticker chips in a horizontal row
   Pin all four to the canonical csfloat tokens so the per-item detail
   ancillary chrome reads identical regardless of mount path.
*/
html body app-skin-bar,
html body app-skin-bar > .container,
html body .modal.item-page .item-skin-bar,
html body .modal.item-page .item-detail-skin-bar {
  height: 38px !important;
  background: transparent !important;
  border: 0 !important;
  display: flex !important;
  align-items: center !important;
  padding: 0 !important;
  margin: 0 0 12px !important;
}
html body app-similar-items,
html body app-similar-items > .container,
html body .modal.item-page .similar-items,
html body .modal.item-page .related-items-rail {
  background: transparent !important;
  border: 0 !important;
  padding: 0 !important;
  display: flex !important;
  flex-direction: column !important;
  gap: 12px !important;
}
html body app-reference-widget,
html body app-reference-widget > .reference,
html body app-reference-widget > div,
html body .modal.item-page .reference-widget,
html body .modal.item-page .item-reference-widget {
  display: inline-flex !important;
  align-items: center !important;
  gap: 4px !important;
  padding: 4px 6px !important;
  background: rgba(255, 255, 255, 0.04) !important;
  border: 0 !important;
  border-radius: 20px !important;
  color: rgb(158, 167, 177) !important;
  font-size: 14px !important;
  line-height: 1 !important;
  height: 24px !important;
  box-sizing: border-box !important;
  white-space: nowrap !important;
}
html body app-reference-widget mat-icon,
html body .modal.item-page .reference-widget mat-icon,
html body .modal.item-page .item-reference-widget .icon {
  width: 19px !important;
  height: 19px !important;
  font-size: 19px !important;
  line-height: 19px !important;
  color: rgb(158, 167, 177) !important;
}
html body app-sticker-view,
html body .modal.item-page .sticker-row,
html body .modal.item-page .item-detail-stickers {
  display: inline-flex !important;
  align-items: center !important;
  gap: 6px !important;
  height: 19px !important;
  background: transparent !important;
  border: 0 !important;
  padding: 0 !important;
  margin: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #129066 */
"""

target = r"C:\\Users\\WW\\Desktop\\sboxmarket\\src\\main\\resources\\static\\css\\design.css"
with open(target, 'rb') as f:
    f.seek(-4096, os.SEEK_END)
    tail = f.read().decode('utf-8', errors='replace')
if 'ship #129060' in tail:
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
    print('APPENDED', len(CSS), 'bytes -> ships #129060-#129066')
except Exception as e:
    if os.path.exists(tmpname):
        try: os.remove(tmpname)
        except: pass
    raise
