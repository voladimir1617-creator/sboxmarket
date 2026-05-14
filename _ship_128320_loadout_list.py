"""Atomic append of CSFLOAT 1:1 parity ships #128320-#128327 — /loadout listing chrome
scoped to .loadout-list (NOT .loadout-grid which is the OPENED-loadout slot grid).

Live measured against csfloat.com/loadout/overview?mode=created&sort_by=date-desc
2026-05-08 via mcp__playwright__browser_navigate (clicked Loadout link from /search
to evade auto-redirect, app-loadout-overview mounted with 2 entries). Viewport at
2440px wide reported the page geometry as:

  app-loadout-overview .header — display GRID, gtc 163.125px 345.109px 1612.45px
    128.141px 151.172px (5 cols: title|nav-tabs|filter-search|months-chip|create-pill),
    gap 10px, height 63px, align-items center.
  .header h2 ("Loadout Lab") — 20/700 Roboto white, margin 16.6px 0px (Material h2
    default), w 111h 24.
  app-loadout-card (per-card mini-tile inside an opened entry) — 221x146 transparent
    block at host level (chrome lives inside the inner item-card pattern).
  app-overview-entry — 232px tall block, transparent host, NO border/radius.
  .loadout-entry-header — 40px tall flex space-between row, padding 2px 10px,
    margin-bottom 4, align-items center.
  .loadout-entry-header-left — flex-row gap 16px (h3 + favorite glyph + count).
  .loadout-entry-header-right — 172w 24h flex-row gap 16px, hosts ".X empty slots"
    (14/600 ink-2 rgb(158,167,177)) + price ($0.00 in ink-2 rgb(158,167,177) when
    empty, or white when populated — measured: both 14/600).
  app-overview-entry h3 — 18.72/600 Roboto white, NO margin, w 84 h 23.
  Create-Loadout button (bottom-right of header) — text "add Create Loadout"
    (mat-icon "add" prefix + label), 151w x 36h, bg rgb(35,123,255), white text,
    14/600, br 8px, padding 12px 16px, classes "mat-mdc-button-base
    create-loadout-button mdc-button--raised mat-mdc-raised-button" — confirms
    a brand-blue solid raised pill is the canonical CTA.

sboxmarket /loadout (LoadoutPage in csfloat-modals.js) renders InfoModal-shell with:
  - .loadout-tabs > button.offer-tab discover/mine/favorites — pinned by ships
    #128211/#128212.
  - search input + Create button when me — pinned by #128213 already as brand-blue.
  - .loadout-list is the root listing wrapper hosting per-loadout .loadout-card
    children (each card has bg + border + radius + per-card chrome).
  - .loadout-card-name (serif 18/360 -0.015em), .loadout-card-meta (mono 10.5
    ink-4 with ❤+favorites and price), .loadout-card-owner ("by NAME"),
    .loadout-card-share (own panel-bottom block with border-top + bg + ink-3
    mono caption, holds the content_copy glyph button).

The PRIOR ship batch #128210-#128217 keyed all the entry-card overrides off
.loadout-grid which is the WRONG selector — that's the slot grid inside an
opened loadout (not the listing root). The selectors reach the listing only
when the user opens an individual loadout. These ships re-scope the listing
chrome to .loadout-list directly + add APP-overview-style row chrome to match
csfloat's flat 232h entry-row pattern.

Ship-number selection: HEAD is at csfloat-1:1 ships #128110-#128116 (commit 52e3842).
Files have ships up to #128310 appended (uncommitted). This batch lands #128320-#128327
to leave room for any in-flight parallel work in the #128310-#128319 range.

NO Docker. APPEND-ONLY at end of design.css. Atomic tempfile + os.replace.
"""

import os, sys, tempfile

CSS = """
/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128320 — /loadout LISTING root .loadout-list
   csfloat measure: app-loadout-overview .grid-of-loadout-entries hosts
   <app-overview-entry> children stacked flush, with no panel-card chrome
   on the wrapper itself (transparent flex-column container). The per-row
   chrome is optional (csfloat ships flat rows without per-card surround);
   sboxmarket ships .loadout-list with bg var(--bg-1) + 1px var(--line) +
   r-md radius (panel-card outer chrome). Strip the outer panel chrome
   when on the dedicated /loadout page so the list reads flush like
   csfloat's .grid-of-loadout-entries instead of as a panel-in-panel.
   Modal-mode listings (LoadoutModal trigger from a non-page surface) and
   the .loadout-picker (separate selector) keep their existing chrome via
   the negative scope below.
*/
body .full-page-mode .loadout-list,
body .info-modal-body .loadout-list {
  background: transparent !important;
  border: 0 !important;
  border-radius: 0 !important;
  overflow: visible !important;
  padding: 0 !important;
  display: flex !important;
  flex-direction: column !important;
  gap: 16px !important;
  width: 100% !important;
}
/* END CSFLOAT-1:1 PARITY ship #128320 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128321 — .loadout-list > .loadout-card row
   chrome (csfloat app-overview-entry pattern). Measured csfloat:
   app-overview-entry is a 232px-tall transparent block — NO border, NO
   radius, NO padding at host level — internal .loadout-entry-header at
   2px 10px padding + cards-row beneath that. Sbox .loadout-card ships
   bg var(--bg-1) + 1px var(--line) + r-md radius + transparent on
   children — that's the per-card "tile" pattern, NOT csfloat's flat
   232h row. Pin .loadout-card inside .loadout-list to a flat 232px-min
   transparent surface that hosts a single brand-blue hairline left
   accent on hover (csfloat's "hover state" — sbox's transform lift gets
   replaced with a left brand stripe so hover reads as "selected" not as
   "lifted"). Padding moved to 16px so the inner header + cards-row fit
   csfloat's 2/10 + cards-row layout cleanly.
*/
body .full-page-mode .loadout-list > .loadout-card,
body .info-modal-body .loadout-list > .loadout-card {
  background: rgb(27, 29, 36) !important;
  border: 1px solid rgba(255, 255, 255, 0.06) !important;
  border-radius: 12px !important;
  padding: 16px !important;
  min-height: 232px !important;
  width: 100% !important;
  box-shadow: none !important;
  position: relative !important;
  overflow: hidden !important;
  display: flex !important;
  flex-direction: column !important;
  gap: 12px !important;
  transition: background 150ms cubic-bezier(0.4,0,0.2,1),
              border-color 150ms cubic-bezier(0.4,0,0.2,1) !important;
}
body .full-page-mode .loadout-list > .loadout-card:hover,
body .info-modal-body .loadout-list > .loadout-card:hover {
  background: rgb(31, 33, 41) !important;
  border-color: rgba(255, 255, 255, 0.10) !important;
  transform: none !important;
}
body .full-page-mode .loadout-list > .loadout-card:focus-visible,
body .info-modal-body .loadout-list > .loadout-card:focus-visible {
  outline: 2px solid rgb(35, 123, 255) !important;
  outline-offset: 2px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128321 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128322 — .loadout-card-name csfloat h3 typography
   Measured csfloat app-overview-entry h3: font-size 18.72px (Material h3
   1.17em from base 16), font-weight 600, color rgb(255,255,255), Roboto
   sans, NO margin, NO padding, NO line-height override. Sbox ships
   .loadout-card-name in serif (var(--serif)) at 18/360 -0.015em with
   font-variation opsz 144 — editorial look that breaks parity. Pin to
   Roboto sans 18.72/600 white inside .loadout-list. Padding 0 since
   the parent .loadout-card now owns 16px padding.
*/
body .full-page-mode .loadout-list .loadout-card-name,
body .info-modal-body .loadout-list .loadout-card-name {
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 18.72px !important;
  font-weight: 600 !important;
  letter-spacing: 0 !important;
  line-height: 1.3 !important;
  color: rgb(255, 255, 255) !important;
  font-variation-settings: normal !important;
  font-style: normal !important;
  padding: 0 !important;
  margin: 0 !important;
  display: flex !important;
  align-items: center !important;
  gap: 16px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128322 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128323 — .loadout-card-meta csfloat right-rail
   Measured csfloat .loadout-entry-header-right: 172w x 24h flex-row +
   16px gap, hosts "X empty slots" (14/600 rgb(158,167,177) ink-2) + price
   "$0.00" (14/600 rgb(158,167,177) ink-2 when 0/empty, white otherwise).
   Sbox ships .loadout-card-meta as bottom-padded mono ink-4 caption with
   ❤ favorites + price both 10.5px mono ink-4. Pin to csfloat's Roboto
   sans 14/600 + ink-2 default; price (last span) gets white when value > 0
   via :nth-last-child trick — actually since meta has just two spans
   (favorites + price), pin the second to white as canonical loadout
   value highlight.
*/
body .full-page-mode .loadout-list .loadout-card-meta,
body .info-modal-body .loadout-list .loadout-card-meta {
  display: flex !important;
  flex-direction: row !important;
  align-items: center !important;
  justify-content: flex-start !important;
  gap: 16px !important;
  flex-wrap: nowrap !important;
  padding: 0 !important;
  margin: 0 !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 14px !important;
  font-weight: 600 !important;
  color: rgb(158, 167, 177) !important;
  letter-spacing: 0 !important;
}
body .full-page-mode .loadout-list .loadout-card-meta > span,
body .info-modal-body .loadout-list .loadout-card-meta > span {
  display: inline-flex !important;
  align-items: center !important;
  gap: 4px !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 14px !important;
  font-weight: 600 !important;
  color: rgb(158, 167, 177) !important;
  letter-spacing: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128323 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128324 — .loadout-card-owner "by NAME" caption
   Measured csfloat: app-overview-entry has no per-card owner caption
   (the page-level attribution lives in the search-bar context above),
   but sboxmarket emits .loadout-card-owner inline for the discover tab
   (see csfloat-modals.js line 1610: "by ", highlightMatch(l.ownerName)).
   Pin this row to ink-2 14/400 Roboto so it reads as a sub-caption under
   the h3 instead of mono ink-4 caption typography that mismatched the
   header treatment.
*/
body .full-page-mode .loadout-list .loadout-card-owner,
body .info-modal-body .loadout-list .loadout-card-owner {
  display: block !important;
  padding: 0 !important;
  margin: 0 !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 13px !important;
  font-weight: 400 !important;
  color: rgb(158, 167, 177) !important;
  letter-spacing: 0 !important;
  line-height: 1.45 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128324 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128325 — .loadout-card-share copy-link glyph
   csfloat ships the "Copy link" affordance as a small icon button only
   (no panel chrome, no border-top, no caption). sboxmarket .loadout-
   card-share emits a footer panel with border-top + bg-darker + flex
   space-between + ink-3 mono caption + glyph. Strip the panel chrome
   so the button floats top-right of the card as csfloat does, with
   minimal hover-blue chrome. The button content is a content_copy
   MaterialIcon at size 16, no caption text.
*/
body .full-page-mode .loadout-list .loadout-card-share,
body .info-modal-body .loadout-list .loadout-card-share {
  position: absolute !important;
  top: 12px !important;
  right: 12px !important;
  width: 32px !important;
  height: 32px !important;
  padding: 0 !important;
  margin: 0 !important;
  display: inline-flex !important;
  align-items: center !important;
  justify-content: center !important;
  background: transparent !important;
  border: 0 !important;
  border-top: 0 !important;
  border-radius: 6px !important;
  box-shadow: none !important;
  color: rgb(158, 167, 177) !important;
  cursor: pointer !important;
  transition: background 120ms ease, color 120ms ease !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 0 !important;
}
body .full-page-mode .loadout-list .loadout-card-share:hover,
body .info-modal-body .loadout-list .loadout-card-share:hover {
  background: rgba(35, 123, 255, 0.10) !important;
  color: rgb(35, 123, 255) !important;
}
body .full-page-mode .loadout-list .loadout-card-share:focus-visible,
body .info-modal-body .loadout-list .loadout-card-share:focus-visible {
  outline: 2px solid rgb(35, 123, 255) !important;
  outline-offset: 2px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128325 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128326 — .loadout-card highlightMatch <mark>
   csfloat-modals.js wraps the search-matched fragments in <mark> via
   highlightMatch() — both for loadout name (.loadout-card-name) and
   owner (.loadout-card-owner). Default browser <mark> bg is bright
   yellow which clashes with the dark panel + brand-blue palette. Pin
   to a brand-blue tint chip pattern matching csfloat's measured search-
   highlight color (rgba(35,123,255,0.20) bg + brand-blue 100% text +
   inherit weight + transparent border).
*/
body .full-page-mode .loadout-list .loadout-card-name mark,
body .full-page-mode .loadout-list .loadout-card-owner mark,
body .info-modal-body .loadout-list .loadout-card-name mark,
body .info-modal-body .loadout-list .loadout-card-owner mark {
  background: rgba(35, 123, 255, 0.20) !important;
  color: rgb(120, 175, 255) !important;
  font-weight: inherit !important;
  border-radius: 2px !important;
  padding: 0 1px !important;
  margin: 0 !important;
  text-decoration: none !important;
}
/* END CSFLOAT-1:1 PARITY ship #128326 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128327 — Loadout Lab page header GRID +
   tabs + create-button alignment, measured against csfloat's 5-column
   header layout (163px title | 345px nav-tabs | 1612px filter | 128px
   months-chip | 151px create-pill, 10px gap, 63px h, align center).
   Sbox InfoModal renders the LoadoutPage cluster (h1 "Loadout Lab" +
   .loadout-tabs row + Create button + search input) stacked vertically.
   Pin the .loadout-tabs row (which holds tabs + flex spacer + create
   button via the flex:1 spacer span) to flex-row align-center so all
   three groups sit on one line; pin its margin-bottom to 16px to clear
   the search input which lands underneath. Search input itself gets
   width 100% in the original ship — preserve that.
*/
body .full-page-mode .info-modal-body > .loadout-tabs,
body .info-modal-body > .loadout-tabs {
  display: flex !important;
  flex-direction: row !important;
  align-items: center !important;
  gap: 16px !important;
  flex-wrap: nowrap !important;
  margin: 0 0 16px 0 !important;
  width: 100% !important;
}
/* The trailing flex:1 spacer div between tabs and the +Create button —
   pin to a real growing column even when the modal-mode parent renames
   it. Selector keys off the inline style flex:1 since the JS emits a
   raw <div style={{flex:1}}> spacer between tabs and CTA. */
body .full-page-mode .info-modal-body > .loadout-tabs > div[style*="flex"],
body .info-modal-body > .loadout-tabs > div[style*="flex"] {
  flex: 1 1 0% !important;
  min-width: 0 !important;
}
/* The .btn.btn-accent + Create button at the end of .loadout-tabs gets
   pushed to the right by the flex spacer above. Pin it to the canonical
   csfloat brand-blue solid 36-tall pill so it visually matches the
   create-loadout button measured at the right edge of csfloat's header
   strip (sbox uses .btn.btn-accent which already approximates this). */
body .full-page-mode .info-modal-body > .loadout-tabs > .btn.btn-accent,
body .info-modal-body > .loadout-tabs > .btn.btn-accent {
  height: 36px !important;
  padding: 0 16px !important;
  background: rgb(35, 123, 255) !important;
  color: rgb(255, 255, 255) !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 14px !important;
  font-weight: 600 !important;
  border-radius: 8px !important;
  border: 0 !important;
  letter-spacing: 0 !important;
  text-transform: none !important;
  flex-shrink: 0 !important;
  align-self: center !important;
}
body .full-page-mode .info-modal-body > .loadout-tabs > .btn.btn-accent:hover,
body .info-modal-body > .loadout-tabs > .btn.btn-accent:hover {
  background: rgb(58, 137, 255) !important;
  color: rgb(255, 255, 255) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128327 */
"""

target = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"
with open(target, 'rb') as f:
    f.seek(-3072, os.SEEK_END)
    tail = f.read().decode('utf-8', errors='replace')
if 'ship #128320' in tail:
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
    print('APPENDED', len(CSS), 'bytes -> ships #128320-#128327')
except Exception as e:
    if os.path.exists(tmpname):
        try: os.remove(tmpname)
        except: pass
    raise
