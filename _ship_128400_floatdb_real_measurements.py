"""Atomic append of CSFLOAT 1:1 parity ships #128400-#128406 — FloatDB (csfloat /db) chrome.

Live-measured against csfloat.com/db (signed-in session, viewport 2560)
2026-05-08 via mcp__playwright__browser_navigate + mcp__playwright__browser_evaluate.
The csfloat /db (FloatDB) page renders an Angular Material-cards GRID layout,
NOT a table. Each tile is an `<item-card>` whose first child is a
`<mat-card class="mat-mdc-card mdc-card item-card card-compact hover-enabled">`.

Measured surfaces (computed styles + bounding rects):

  ── Page-level body ─────────────────────────────────
  body                      bg #15171c (rgb(21,23,28))
                            padding 0 60px (gutters)
                            color #ffffff, font 16/400

  ── FloatDB grid (.content-wrapper) ─────────────────
  display:flex, flex-wrap, gap:15px, padding:0
  Each item-card                   246w x 420h
  flex-item                        no own bg/border, layout only
  inner mat-card                   bg #1b1d24 (rgb(27,29,36)) ← panel token
                                   border: 2px solid rgba(0,0,0,0)  (transparent shell)
                                   border-radius: 12px
                                   padding: 0 (children handle gutters)
                                   box-shadow MD-1: 0 2px 1px -1px rgba(0,0,0,0.2),
                                                    0 1px 1px 0 rgba(0,0,0,0.14),
                                                    0 1px 3px 0 rgba(0,0,0,0.12)
                                   display: flex (column via item-grid child)

  ── item-card inner (.item-grid) ─────────────────────
  width 242 (after 2px border) x 416, transparent
  ┌─ .header (242 x 64, padding 10px) ────────────────
  │   .container (222 x 44) — name+subtext stack
  │     .item-name        16/500 #ff782c (Doppler/rare orange)
  │     .subtext          14/500 #9ea7b1 (ink-2)
  │       .ng-star-inserted spans (StatTrak™ token) inherit rare orange
  ┌─ .image (242 x 189, container 242x192) ───────────
  │   img.item-img        242 x 182 (full bleed)
  │   .badge-container    30 x 30 chip (top-left), img inside r:8px
  │   .top-right-container 58 x 30
  │   .top-right-container .action  bg rgba(30,32,38,0.55)
  │                                 padding 4 8, border-radius 20
  │                                 ink-2 #9ea7b1, font 14
  │                                 .icon 20 x 20 inside, .count 14
  │   .detail-buttons     38 wide column, .action 38x30 inspect-link
  │                                 same chip vocabulary as top-right
  └─ .footer (242 x 163, padding 12px 12px 0px) ──────
      .price-row          218 x 27
        .price            22/500 white
        .reference-widget-container 129 x 27
          .reference div  bg rgba(255,255,255,0.04) ← hairline token
                          padding 4 6, border-radius 20
          mat-icon        19 x 19 ink-2 #9ea7b1

  ── Category carousel (.category-carousel) ──────────
  height 38, mat-button-toggle-group strip
  Items: Rifles | Pistols | SMGs | Heavy | Knives | Gloves | Agents
         Containers | Stickers | Keychains | Patches | Collectibles | Music Kits
  navigate_before / navigate_next chevrons on the ends
  Element labels render as 14/500, button toggle strip inherits rgb(255,255,255)

  ── Filter sidebar (collapsed/lazy on this viewport) ─
  Body innertext walked: Price (From / To, chips <$10 / $10-$50 / $50-$250 / >$250),
  Wear (Min / Max, chips FN/MW/FT/WW/BS), Special (StatTrak™ / Souvenir),
  Highlight (Normal), Patterns (Paint Seed, Min/Max Fade %, Min/Max Blue %),
  Stickers (5x Any Slot rows), Charms (Min/Max Pattern), Rarity (Any),
  Collection (Any), Listing (All / Buy Now / Auction), Reset All Filters,
  refresh icon. Tabs: All Items | Sticker Combos | Unique Items.
  Section header: "Best Deals".

These ships pin the existing sboxmarket DatabaseModal (.db-table, .db-controls,
.db-meta, .db-row, .db-thumb chrome) and the .csfloat-db-grid card-row sweep
toward the csfloat panel/grid vocabulary measured above. PRIOR ships #14700-
#14705 / #15300+ approximated body bg, panel bg, and chip radii — these
ships REPLACE the approximations with the exact rgb()/rgba() tokens read from
the live page.

Tokens used:
  --cf-panel              rgb(27, 29, 36)        ← mat-card bg
  --cf-brand              rgb(35, 123, 255)      ← spec brand
  --cf-ink-2              rgb(158, 167, 177)     ← subtext / chip text
  --cf-hair               rgba(255, 255, 255, 0.06)  ← reference div + spec hairline
  --cf-chip-bg            rgba(30, 32, 38, 0.55) ← image-area action chip
  --cf-card-shadow        MD-1 3-stop elevation

NO Docker. APPEND-ONLY at end of design.css. Atomic tempfile + os.replace.
Idempotency: first 4096 bytes of file tail checked for ship #128400 marker.
"""

import os, sys, tempfile

CSS = """
/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128400 — FloatDB body + variant background
   Measured csfloat body: bg rgb(21,23,28), padding 0 60px, color white.
   Sboxmarket DatabaseModal currently inherits the standard modal body
   bg (--bg-0 ≈ #1a1c20). When the modal is mounted as a /database route
   variant body it should pin to the csfloat raw rgb(21,23,28). Pin both
   the .db-page wrapper and the .db-page-variant root used by the
   route-mounted (non-modal) database surface so the floor of the page
   matches csfloat 1:1.
*/
body .db-page,
body .db-page-variant,
body .csfloat-db,
body .csfloat-db-page,
body .info-modal-body[data-route="/database"],
body .info-modal-body[data-route="/db"] {
  background: rgb(21, 23, 28) !important;
  color: rgb(255, 255, 255) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128400 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128401 — FloatDB item-card panel (mat-card)
   Measured csfloat item-card .mat-card computed:
     bg:               rgb(27, 29, 36)
     border:           2px solid rgba(0, 0, 0, 0)  (transparent shell)
     border-radius:    12px
     box-shadow MD-1:  0 2px 1px -1px rgba(0,0,0,0.2),
                       0 1px 1px 0 rgba(0,0,0,0.14),
                       0 1px 3px 0 rgba(0,0,0,0.12)
     padding:          0 (children handle gutters)
     display:          flex
     dimensions:       246 x 420 (whole tile)
   Sboxmarket .csfloat-db-grid > .csfloat-db-card and the table-row
   db-thumb container chrome currently use --bg-1 + --r-md (~10px).
   Pin the panel rgb + 12px radius + MD-1 elevation 1:1 to csfloat.
*/
body .csfloat-db-card,
body .csfloat-db-grid > .csfloat-db-card,
body .csfloat-db-grid > .item-card,
body .floatdb-card,
body .floatdb-tile,
body .db-card-tile {
  background: rgb(27, 29, 36) !important;
  border: 2px solid rgba(0, 0, 0, 0) !important;
  border-radius: 12px !important;
  padding: 0 !important;
  box-shadow:
    0 2px 1px -1px rgba(0, 0, 0, 0.2),
    0 1px 1px 0 rgba(0, 0, 0, 0.14),
    0 1px 3px 0 rgba(0, 0, 0, 0.12) !important;
  display: flex !important;
  flex-direction: column !important;
  width: 246px !important;
  min-height: 420px !important;
  box-sizing: border-box !important;
  overflow: hidden !important;
}
/* END CSFLOAT-1:1 PARITY ship #128401 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128402 — FloatDB grid layout (flex 15px gap)
   Measured csfloat .content-wrapper computed:
     display:    flex
     flex-wrap:  wrap   (cards reflow on narrow viewports)
     gap:        15px
     padding:    0 (parent .container handles gutters)
   Sboxmarket .csfloat-db-grid currently uses CSS grid with
   grid-template-columns: repeat(auto-fill, minmax(...)). Pin to flex
   wrap with 15px gap so card spacing reads identically to csfloat.
*/
body .csfloat-db-grid,
body .floatdb-grid,
body .db-card-grid,
body .csfloat-db .content-wrapper,
body .db-page .content-wrapper {
  display: flex !important;
  flex-direction: row !important;
  flex-wrap: wrap !important;
  gap: 15px !important;
  padding: 0 !important;
  margin: 0 !important;
  background: transparent !important;
  align-items: flex-start !important;
  justify-content: flex-start !important;
}
/* END CSFLOAT-1:1 PARITY ship #128402 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128403 — FloatDB image-area action chip
   Measured csfloat .top-right-container .action and .detail-buttons
   .action computed:
     bg:               rgba(30, 32, 38, 0.55)   (translucent dark chip)
     padding:          4px 8px
     border-radius:    20px
     color:            rgb(158, 167, 177)       (ink-2)
     icon:             20 x 20  ink-2
     count text:       14px / 400
     chip dims:        58 x 30 (count chip), 38 x 30 (icon-only chip)
   Sboxmarket .db-card-action / .db-thumb-pill currently ships an opaque
   --bg-1 chip with 8px radius. Pin to the translucent rgba(30,32,38,0.55)
   chip + 20px pill radius + ink-2 color to match csfloat's overlay vocab.
*/
body .csfloat-db-card .db-card-action,
body .csfloat-db-card .db-thumb-pill,
body .csfloat-db-card .top-right-container > .action,
body .csfloat-db-card .detail-buttons > .action,
body .floatdb-card .action,
body .floatdb-card .inspect-link,
body .floatdb-tile .action,
body .db-card-tile .action {
  background: rgba(30, 32, 38, 0.55) !important;
  -webkit-backdrop-filter: blur(2px) !important;
  backdrop-filter: blur(2px) !important;
  padding: 4px 8px !important;
  border-radius: 20px !important;
  color: rgb(158, 167, 177) !important;
  font-size: 14px !important;
  font-weight: 400 !important;
  display: inline-flex !important;
  align-items: center !important;
  gap: 4px !important;
  border: 0 !important;
  min-height: 30px !important;
  box-sizing: border-box !important;
}
body .csfloat-db-card .db-card-action .icon,
body .csfloat-db-card .top-right-container > .action .icon,
body .csfloat-db-card .detail-buttons > .action .icon,
body .floatdb-card .action .icon,
body .floatdb-card .inspect-link .icon {
  width: 20px !important;
  height: 20px !important;
  font-size: 20px !important;
  color: rgb(158, 167, 177) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128403 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128404 — FloatDB footer reference pill
   Measured csfloat .footer .price-row .reference-widget-container
   .reference (the small price-comparison chip next to the price):
     bg:               rgba(255, 255, 255, 0.04)   (hairline token)
     padding:          4px 6px
     border-radius:    20px
     dims:             31 x 27 (icon-only) / wider with text
     mat-icon:         19 x 19   color rgb(158, 167, 177)
     parent .footer:   padding 12px 12px 0px (no bottom)
     parent .price-row: flex, height 27px
   Sboxmarket .db-row .db-floor / .db-card-price-row baseline lacks a
   reference chip — pin .csfloat-db-card .reference + footer geometry
   to the measured spec.
*/
body .csfloat-db-card .footer,
body .csfloat-db-card .db-card-footer,
body .floatdb-card .footer,
body .db-card-tile .footer {
  padding: 12px 12px 0 !important;
  background: transparent !important;
  border-top: 0 !important;
}
body .csfloat-db-card .price-row,
body .csfloat-db-card .db-card-price-row,
body .floatdb-card .price-row,
body .db-card-tile .price-row {
  display: flex !important;
  align-items: center !important;
  justify-content: space-between !important;
  height: 27px !important;
  margin: 0 !important;
  gap: 8px !important;
}
body .csfloat-db-card .price,
body .csfloat-db-card .db-card-price,
body .floatdb-card .price,
body .db-card-tile .price {
  font-size: 22px !important;
  font-weight: 500 !important;
  color: rgb(255, 255, 255) !important;
  line-height: 27px !important;
}
body .csfloat-db-card .reference,
body .csfloat-db-card .reference-widget-container > .reference,
body .floatdb-card .reference,
body .db-card-tile .reference {
  background: rgba(255, 255, 255, 0.04) !important;
  padding: 4px 6px !important;
  border-radius: 20px !important;
  display: inline-flex !important;
  align-items: center !important;
  gap: 4px !important;
  min-height: 27px !important;
  color: rgb(158, 167, 177) !important;
  font-size: 14px !important;
  border: 0 !important;
}
body .csfloat-db-card .reference .mat-icon,
body .csfloat-db-card .reference mat-icon,
body .floatdb-card .reference .mat-icon {
  width: 19px !important;
  height: 19px !important;
  font-size: 19px !important;
  color: rgb(158, 167, 177) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128404 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128405 — FloatDB header (item-name + subtext)
   Measured csfloat .header inside item-card:
     padding:          10px
     dims:             242 x 64
     .item-name        16/500   color rgb(255,120,44)  (rare-orange for Doppler)
                                truncates with ellipsis at 222px width
     .subtext          14/500   color rgb(158,167,177) (ink-2)
     .subtext .ng-star-inserted (StatTrak™ chip) inherits rare color
   Sboxmarket card baseline ships 12-14px padding and uses --text-secondary
   instead of the measured ink-2 #9ea7b1. Pin the header geometry, the
   ink-2 subtext color, and the 16/14 typography to csfloat.
*/
body .csfloat-db-card .header,
body .csfloat-db-card .db-card-header,
body .floatdb-card .header,
body .db-card-tile .header {
  padding: 10px !important;
  display: block !important;
  height: 64px !important;
  box-sizing: border-box !important;
  background: transparent !important;
  border-bottom: 0 !important;
}
body .csfloat-db-card .header .container,
body .csfloat-db-card .item-name-container,
body .floatdb-card .item-name-container {
  display: block !important;
  width: 222px !important;
  max-width: 222px !important;
  height: 44px !important;
  margin: 0 !important;
  padding: 0 !important;
}
body .csfloat-db-card .item-name,
body .csfloat-db-card .db-card-name,
body .floatdb-card .item-name {
  font-size: 16px !important;
  font-weight: 500 !important;
  line-height: 21px !important;
  color: rgb(255, 255, 255) !important;
  white-space: nowrap !important;
  overflow: hidden !important;
  text-overflow: ellipsis !important;
  margin: 0 !important;
}
body .csfloat-db-card .subtext,
body .csfloat-db-card .db-card-subtext,
body .floatdb-card .subtext {
  font-size: 14px !important;
  font-weight: 500 !important;
  line-height: 17px !important;
  color: rgb(158, 167, 177) !important;
  margin: 0 !important;
  white-space: nowrap !important;
  overflow: hidden !important;
  text-overflow: ellipsis !important;
}
/* END CSFLOAT-1:1 PARITY ship #128405 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128406 — FloatDB category carousel strip
   Measured csfloat .category-carousel .wrapper (the horizontally-scrolling
   weapon-class chip strip directly above the grid):
     wrapper height:   38px
     .scroll-content   2440px wide (overflows, internal scroll)
     navigate_before / navigate_next chevrons on either side (mat-icon)
     .container > mat-button-toggle-group.state hides single selection indicator
     mat-button-toggle items: Rifles, Pistols, SMGs, Heavy, Knives, Gloves,
       Agents, Containers, Stickers, Keychains, Patches, Collectibles, Music Kits
     Each chip: standard Material button-toggle vocabulary
       label: 14/500 white, gap 4 between text + chevron icon
       inactive: transparent bg, hover lightens to rgba(255,255,255,0.06)
       active: brand fill rgb(35,123,255) at 0.16 alpha + brand text
   Sboxmarket .csfloat-db-cats / .db-cat-strip baseline ships 32px tall
   chips with --bg-1 backgrounds and --r-sm (6px). Pin to 38px row height,
   transparent inactive bg, hairline-on-hover, brand fill on active, and
   the 14/500 typography measured.
*/
body .csfloat-db-cats,
body .db-cat-strip,
body .floatdb-category-carousel,
body .csfloat-db .category-carousel,
body .db-page .category-carousel {
  height: 38px !important;
  display: flex !important;
  align-items: center !important;
  gap: 0 !important;
  background: transparent !important;
  border: 0 !important;
  margin: 0 0 12px !important;
  padding: 0 !important;
  overflow: hidden !important;
}
body .csfloat-db-cats > .cat,
body .csfloat-db-cats > button,
body .db-cat-strip > .cat,
body .db-cat-strip > button,
body .floatdb-category-carousel .mat-button-toggle,
body .csfloat-db .category-carousel .mat-button-toggle {
  height: 38px !important;
  padding: 0 12px !important;
  background: transparent !important;
  border: 0 !important;
  border-radius: 0 !important;
  color: rgb(255, 255, 255) !important;
  font-size: 14px !important;
  font-weight: 500 !important;
  line-height: 38px !important;
  display: inline-flex !important;
  align-items: center !important;
  gap: 4px !important;
  cursor: pointer !important;
  white-space: nowrap !important;
}
body .csfloat-db-cats > .cat:hover,
body .db-cat-strip > .cat:hover,
body .floatdb-category-carousel .mat-button-toggle:hover {
  background: rgba(255, 255, 255, 0.06) !important;
}
body .csfloat-db-cats > .cat.active,
body .csfloat-db-cats > .cat.is-active,
body .csfloat-db-cats > .cat[aria-pressed="true"],
body .db-cat-strip > .cat.active,
body .floatdb-category-carousel .mat-button-toggle.mat-button-toggle-checked,
body .floatdb-category-carousel .mat-button-toggle[aria-checked="true"] {
  background: rgba(35, 123, 255, 0.16) !important;
  color: rgb(35, 123, 255) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128406 */
"""

target = r"C:\\Users\\WW\\Desktop\\sboxmarket\\src\\main\\resources\\static\\css\\design.css"
with open(target, 'rb') as f:
    f.seek(-4096, os.SEEK_END)
    tail = f.read().decode('utf-8', errors='replace')
if 'ship #128400' in tail:
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
    print('APPENDED', len(CSS), 'bytes -> ships #128400-#128406')
except Exception as e:
    if os.path.exists(tmpname):
        try: os.remove(tmpname)
        except: pass
    raise
