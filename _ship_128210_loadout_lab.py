"""Atomic append of CSFLOAT 1:1 parity ships #128210-#128217 — /loadout Lab page chrome.

Live measured against csfloat.com/loadout?mode=discover (signed-in session, viewport 1440)
2026-05-08 via mcp__playwright__browser_navigate + mcp__playwright__browser_evaluate. The
csfloat /loadout surface — labelled "Loadout Lab" and reached from the top nav "Loadout" link
(active link color rgb(255,255,255) on transparent bg, 15/500, 8px br, 67/36 cell) — renders
<app-loadout-overview> hosting:

  - .header strip: display GRID with grid-template-columns 163px 345px 1891px 0 0 / 10px gap /
    63px h, ROW1 hosting (a) .title-container 163w x 36h with H2 "Loadout Lab" 20/700 white
    margin-bottom 16.6px (b) <app-nav-tabs> rendering inline anchor links (articleCreated /
    favoriteFavorites / exploreDiscover) each ~104-113w x 34h, 16/400 white text, NO segmented
    pill background, NO border-bottom on the strip itself — the active tab is marked by a
    BRAND-BLUE 2px ::after underline + 16/400 white label vs inactive ink-2 rgb(158,167,177)
    label, padding 0 8px on each link (c) <app-loadout-overview-filter> .filter-container at
    grid-template-columns 300px 1031px 182px 329px / 16px gap / 40px h hosting search input +
    sort_by chip strip (trending_up Popular / casino Random / update Newest) + months chip
    + brand-blue "add Create Loadout" pill (151w x 36h, bg rgb(35,123,255), 14/600, 8px br,
    0 16px padding).
  - <app-overview-entry> per loadout: 232px h flat-row card hosting .loadout-entry-header
    (40h, mb 4) with .loadout-entry-header-left (h3 18.72/600 white loadout name + favorite
    glyph + favorite count) and .loadout-entry-header-right ("X empty slots" ink-2 + price
    14/600 right-aligned), then a row of <app-loadout-card> mini-tiles (221w x 146h, flat,
    transparent bg in their host element — the inner card chrome ships as the .item-card
    pattern at rgb(27,29,36) bg + 12px br + ~150x405 vertical aspect when shown stand-alone
    on /search).

sboxmarket /loadout (Loadout Lab) renders at http://localhost:8082/loadout via SPA route
(LoadoutPage in modals.js / app.js):
  - h1 "Loadout Lab" 20/700 white mb 17 (good — matches csfloat's H2)
  - .loadout-tabs row WRAPPED IN A SEGMENTED PILL: bg rgba(193,206,255,0.04) + 4px padding +
    rounded — VS csfloat ships flat inline links with NO pill background and NO box. The
    active tab on sbox is colored brand-blue text + brand-blue border + brand-tint bg, VS
    csfloat's "white text + brand-blue 2px underline ::after" treatment.
  - .loadout-add-btn ships as a DASHED ink-2 outline button (8/14 padding, 13/500), VS
    csfloat's brand-blue solid pill (151x36, 14/600, rgb(35,123,255), 0 16px, 8px br).
  - .loadout-grid uses repeat(auto-fill, minmax(280px,1fr)) — VS csfloat's 232px-tall flat
    rows that stretch to full container width with the .loadout-entry-header / cards-row
    interior layout.
  - The .loadout-card name ships in serif 18/360 with -0.015em tracking (mismatched header
    typography) — VS csfloat's loadout-entry h3 18.72/600 sans Roboto white.
  - No .loadout-page header GRID layout (sbox stacks tabs/tools below H1, csfloat columnizes
    title|tabs|filter into one row).
  - sbox .loadout-card-share ships its own footer panel chrome — csfloat embeds the price +
    empty-slot count INSIDE the loadout-entry-header-right at the row level.
  - sbox .loadout-card overall border-radius is r-md (~8px) — csfloat's panel is 12px br on
    the rgb(27,29,36) bg.

Ship-number selection: HEAD is at csfloat-1:1 ships #128114-#128121 (commit da20e12).
This batch jumps to #128210-#128217 to leave headroom for any in-flight parallel-agent
work in the #128130-#128199 range.

NO Docker. APPEND-ONLY at end of design.css. Atomic tempfile + os.replace.
"""

import os, sys, tempfile

CSS = """
/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128210 — /loadout page header GRID layout
   Measured csfloat .header (app-loadout-overview > div.header.ng-star-inserted):
   display grid + grid-template-columns 163px 345px 1891px 0 0 / 10px gap / 63px h.
   Sbox /loadout currently stacks H1 above .loadout-tabs above .loadout-add-btn —
   pin the wrapper around the H1+tabs+add-btn cluster to a single GRID row so the
   page-title sits LEFT, the tabs sit MIDDLE, and the create-pill+search/sort
   filter stretch RIGHT. Anchor: the .full-page-mode wrapper that hosts
   .loadout-tabs immediately after an h1.
*/
body .full-page-mode .loadout-page-header,
body .full-page-mode > h1 + .loadout-tabs,
body .info-modal-body > h1 + .loadout-tabs {
  display: grid !important;
  grid-template-columns: auto auto 1fr !important;
  align-items: center !important;
  gap: 24px !important;
  margin-bottom: 18px !important;
  width: 100% !important;
}
/* When page renders the H1 + tabs + add as siblings (no wrapper), pin the H1
   to row-1-col-1 inside the grid. */
body .full-page-mode > h1.csfloat-h1 + .loadout-tabs ~ .loadout-add-btn,
body .info-modal-body > h1 + .loadout-tabs ~ .loadout-add-btn {
  grid-column: 3 !important;
  justify-self: end !important;
}
/* END CSFLOAT-1:1 PARITY ship #128210 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128211 — .loadout-tabs flat inline strip (kill pill)
   Measured csfloat <app-nav-tabs> children: 3 inline anchor links rendered FLAT
   on transparent bg (NO segmented pill bg, NO 4px padding, NO border-radius).
   Sbox baseline emits .loadout-tabs as a SEGMENTED PILL (bg rgba(193,206,255,0.04)
   + 4px padding + 14px mb) which doesn't match. Strip the pill chrome so .loadout-
   tabs ships as inline links with 28px gap between them (csfloat-equivalent
   anchor spacing).
*/
body .loadout-tabs {
  background: transparent !important;
  padding: 0 !important;
  border: 0 !important;
  border-radius: 0 !important;
  gap: 28px !important;
  margin-bottom: 0 !important;
  flex-wrap: nowrap !important;
}
/* END CSFLOAT-1:1 PARITY ship #128211 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128212 — .loadout-tabs > button/anchor link parity
   Measured csfloat anchor link computed: 16/400 Roboto, color ink-2
   rgb(158,167,177) at rest, white rgb(255,255,255) when active, padding 0 8px,
   34px h, NO border, NO bg, NO border-radius (flat link chrome). Active state
   gets a 2px brand-blue ::after underline pinned to bottom -8px so the
   underline sits below the strip baseline. Sbox ships these as 32x88 buttons
   with bg+border+radius — strip the chrome and pin to the inline-link style.
*/
body .loadout-tabs > button,
body .loadout-tabs > a {
  background: transparent !important;
  border: 0 !important;
  border-radius: 0 !important;
  padding: 0 8px !important;
  height: 34px !important;
  min-width: 0 !important;
  width: auto !important;
  color: rgb(158, 167, 177) !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 16px !important;
  font-weight: 400 !important;
  letter-spacing: 0 !important;
  text-transform: none !important;
  position: relative !important;
  cursor: pointer !important;
  transition: color 140ms ease !important;
  box-shadow: none !important;
}
body .loadout-tabs > button:hover,
body .loadout-tabs > a:hover {
  color: rgb(220, 226, 233) !important;
  background: transparent !important;
}
body .loadout-tabs > button.active,
body .loadout-tabs > button.is-active,
body .loadout-tabs > button[aria-current="page"],
body .loadout-tabs > button[aria-pressed="true"],
body .loadout-tabs > a.active,
body .loadout-tabs > a.is-active,
body .loadout-tabs > a[aria-current="page"] {
  color: rgb(255, 255, 255) !important;
  background: transparent !important;
  border: 0 !important;
  font-weight: 500 !important;
}
body .loadout-tabs > button.active::after,
body .loadout-tabs > button.is-active::after,
body .loadout-tabs > button[aria-current="page"]::after,
body .loadout-tabs > button[aria-pressed="true"]::after,
body .loadout-tabs > a.active::after,
body .loadout-tabs > a.is-active::after,
body .loadout-tabs > a[aria-current="page"]::after {
  content: "" !important;
  position: absolute !important;
  left: 8px !important;
  right: 8px !important;
  bottom: -6px !important;
  height: 2px !important;
  background: rgb(35, 123, 255) !important;
  border-radius: 1px !important;
  pointer-events: none !important;
}
/* END CSFLOAT-1:1 PARITY ship #128212 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128213 — .loadout-add-btn brand-blue solid pill
   Measured csfloat "add Create Loadout" button at 151w x 36h, bg rgb(35,123,255),
   color white, font 14/600 Roboto with letter-spacing 0, border-radius 8px,
   padding 0 16px, NO border, NO box-shadow, hover lift to rgb(58,137,255).
   Sbox ships .loadout-add-btn as a DASHED ink-2 outline button (var(--bg-1)
   bg + dashed line-2 border + 13/500 ink-2 text). Strip the dashed chrome and
   pin to csfloat's brand-primary CTA pill.
*/
body .loadout-add-btn {
  display: inline-flex !important;
  align-items: center !important;
  justify-content: center !important;
  gap: 6px !important;
  height: 36px !important;
  min-width: 0 !important;
  padding: 0 16px !important;
  background: rgb(35, 123, 255) !important;
  border: 0 !important;
  border-radius: 8px !important;
  color: rgb(255, 255, 255) !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 14px !important;
  font-weight: 600 !important;
  letter-spacing: 0 !important;
  text-transform: none !important;
  box-shadow: none !important;
  cursor: pointer !important;
  transition: background 140ms ease, transform 140ms ease !important;
}
body .loadout-add-btn:hover {
  background: rgb(58, 137, 255) !important;
  color: rgb(255, 255, 255) !important;
  border: 0 !important;
  transform: translateY(-1px) !important;
}
body .loadout-add-btn:active {
  background: rgb(28, 105, 220) !important;
  transform: translateY(0) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128213 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128214 — .loadout-grid flat full-width entry rows
   Measured csfloat .grid-of-loadout-entries: app-overview-entry rows are 232h
   each, flat full-width (no per-row card chrome), bg transparent, no border,
   no padding at the host level (the inner content paints on the page bg
   directly). Sbox .loadout-grid ships repeat(auto-fill, minmax(280px,1fr))
   tiled cards — that's the wrong layout for the /loadout-overview pattern.
   When inside .full-page-mode (the dedicated /loadout route), pin the grid
   to a single column of full-width 232h-min entry rows with 16px row gap.
   Modal-mode loadout pickers (LoadoutModal at 380x...) keep the existing
   minmax tile chrome via the negative scope below.
*/
body .full-page-mode .loadout-grid {
  display: flex !important;
  flex-direction: column !important;
  grid-template-columns: 1fr !important;
  gap: 16px !important;
  width: 100% !important;
}
body .full-page-mode .loadout-grid > .loadout-card,
body .full-page-mode .loadout-grid > .loadout-entry,
body .full-page-mode .loadout-grid > app-overview-entry,
body .full-page-mode .loadout-grid > .csfloat-loadout-card {
  width: 100% !important;
  min-height: 232px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128214 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128215 — .loadout-card panel chrome (page mode)
   Measured csfloat per-entry chrome (when the entry expands to its own panel
   on /loadout/<id>): bg rgb(27,29,36) panel + 12px border-radius + no border
   + no shadow + 18px padding. The /loadout-overview list version paints the
   row-card with the SAME panel-token bg + 12px br but a SLIGHTLY tighter
   16px padding to keep the 232h fit. Sbox .loadout-card uses var(--bg-1)
   + var(--line) 1px border + r-md (~8px) — replace with the canonical
   rgb(27,29,36) panel + 12px br + hairline rgba(255,255,255,0.06) border
   (lane brief tokens) + 16px padding for entry rows.
*/
body .full-page-mode .loadout-card,
body .full-page-mode .loadout-entry,
body .full-page-mode app-overview-entry,
body .full-page-mode .csfloat-loadout-card {
  background: rgb(27, 29, 36) !important;
  border: 1px solid rgba(255, 255, 255, 0.06) !important;
  border-radius: 12px !important;
  padding: 16px !important;
  box-shadow: none !important;
  overflow: hidden !important;
}
body .full-page-mode .loadout-card:hover,
body .full-page-mode .loadout-entry:hover,
body .full-page-mode app-overview-entry:hover {
  background: rgb(31, 33, 41) !important;
  border-color: rgba(255, 255, 255, 0.10) !important;
  transform: none !important;
}
/* END CSFLOAT-1:1 PARITY ship #128215 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128216 — .loadout-card-name h3 typography parity
   Measured csfloat .loadout-entry-header h3: font-size 18.72px (Material h3
   default at 1.17em from base 16), font-weight 600, color rgb(255,255,255),
   font-family Roboto, letter-spacing normal, 25px line-height. Sbox ships the
   loadout-card-name in serif (var(--serif)) at 18/360 with -0.015em tracking
   and font-variation-settings opsz 144 — that's the editorial/elegant look
   from the design system but it BREAKS pixel parity with csfloat's clean
   sans h3. Pin to Roboto sans 18.72/600 white when on /loadout page.
*/
body .full-page-mode .loadout-card-name,
body .full-page-mode .loadout-entry-name,
body .full-page-mode .csfloat-loadout-card-title,
body .full-page-mode app-overview-entry h3 {
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 18.72px !important;
  font-weight: 600 !important;
  letter-spacing: 0 !important;
  line-height: 25px !important;
  color: rgb(255, 255, 255) !important;
  font-variation-settings: normal !important;
  font-style: normal !important;
  padding: 0 0 4px 0 !important;
  margin: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128216 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128217 — .loadout-card-meta + price right-rail
   Measured csfloat .loadout-entry-header-right: 180w x 24h, holds "X empty
   slots" ink-2 + price 14/600 white (e.g. "$729.76") right-aligned with
   8-12px gap. Sbox baseline ships .loadout-card-meta as bottom-padded mono
   ink-4 caption text and .loadout-card-share as a separate footer panel
   with own border-top + bg + ink-3 mono. Strip the share-footer chrome and
   pin meta to a flex-row that hosts ink-2 captions (left side, mono 11px)
   + a brand-white 14/600 price chip (right side, Roboto sans) so the row
   reads exactly like csfloat's loadout-entry-header-right.
*/
body .full-page-mode .loadout-card-meta {
  display: flex !important;
  flex-direction: row !important;
  align-items: center !important;
  justify-content: space-between !important;
  gap: 12px !important;
  padding: 8px 0 0 0 !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 13px !important;
  color: rgb(158, 167, 177) !important;
  letter-spacing: 0 !important;
}
body .full-page-mode .loadout-card-meta .loadout-card-price,
body .full-page-mode .loadout-card-meta .csfloat-loadout-card-value,
body .full-page-mode .loadout-card-meta strong,
body .full-page-mode .loadout-card-meta .price {
  color: rgb(255, 255, 255) !important;
  font-weight: 600 !important;
  font-size: 14px !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  letter-spacing: 0 !important;
  margin-left: auto !important;
}
body .full-page-mode .loadout-card-share {
  background: transparent !important;
  border-top: 0 !important;
  padding: 6px 0 0 0 !important;
  margin: 0 !important;
  color: rgb(158, 167, 177) !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 12px !important;
  font-weight: 400 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128217 */
"""

target = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"
with open(target, 'rb') as f:
    f.seek(-3072, os.SEEK_END)
    tail = f.read().decode('utf-8', errors='replace')
if 'ship #128210' in tail:
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
    print('APPENDED', len(CSS), 'bytes -> ships #128210-#128217')
except Exception as e:
    if os.path.exists(tmpname):
        try: os.remove(tmpname)
        except: pass
    raise
