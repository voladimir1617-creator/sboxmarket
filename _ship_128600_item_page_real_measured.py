"""Atomic append of CSFLOAT 1:1 parity ships #128600-#128606 — /item/<id> page
header + main image area, REAL signed-in measurements taken 2026-05-08 against
csfloat.com /item/972716531467291016 (StatTrak Desert Eagle Naga, Battle-Scarred).

CONTEXT — earlier ships #128310-#128316 approximated these surfaces because the
csfloat browser extension was intercepting /item navigation and bouncing it to
/loadout, /profile, /search, /404, or /. This run defeated the bounce by
issuing page.goto(..., waitUntil:'commit') (so the eval landed BEFORE the
extension's nav-rewrite handler could fire) and then read computed styles
during the brief window the real /item DOM was live. The numbers below are
computed from getBoundingClientRect / getComputedStyle on the real signed-in
session, not estimated from screenshots.

Measured root container (the page max-width host, .container @ /item):
  - max-width:           1337px (locked, computed)
  - padding:             0
  - margin:              0
  - x offset:            612 @ viewport ~2560 (centered host)

Measured app-item-name (the page-level item title block):
  - .header (wrapper):   x 1047 / w 506 / h 62
  - app-item-name:       x 1057 / w 486 / h 42 (10px L+R inset from .header)
  - inner .container:    same w 486 / h 42
  - line 1 (.item-name): "Desert Eagle | Naga" — h 19 — color rgb(255,120,44)
                         (StatTrak orange tint on the weapon segment)
  - typography:          16px / 500 / Roboto / line-height normal / no
                         letter-spacing on app-item-name root
  - sub line stack:      "StatTrak™ Battle-Scarred" wraps below as a 13/500
                         ink-2-toned subtext (matches small variant on cards)

Earlier ship #128310 over-shot the heading at 18px / 600 + ellipsis-truncated
single-line + 36px min-height. Real surface is 16px / 500, the wrapper is 62px
tall (not 36) because the block stacks the weapon name (line 1, brand-orange)
on top of the StatTrak/wear segment (line 2, ink-2). #128600 corrects to the
measured numbers and removes the white-space:nowrap so the wrap works.

Measured main image (the big steam economy image at the top of the column):
  - host:                <app-item-image-actions>  (transparent, no border)
  - .container (img wr): w 506 / h 390           (10px taller than the img)
  - <img>:               w 506 / h 380           (no border-radius, no border,
                                                  no padding, transparent bg)
  - aspect ratio:        1.33 : 1                 (506/380)
  - host x:              1047 (flush with header above; same column gutter)

Earlier ship #128313 was approximated. Real numbers: image is 506x380 inside
a 506x390 container (the extra 10px is the action-row gap below the image).
No corner radius. No border. No padding. Just a flat image flush against a
transparent host. #128601 pins this exact geometry so the column's natural
flow lines up with the header above and the action-row below.

Measured action-row icon button (the History button + sibling Share button
in the action row directly below the main image):
  - host:                <app-item-image-actions> (renders the action row at
                         y 578, so the row sits 10px below the 380h image)
  - <button> (history):  w 37 / h 40, padding 0 10px
  - bg at rest:          rgba(193, 206, 255, 0.04)  (NOT transparent — the
                         pill has a permanent very-faint tint baseline)
  - border-radius:       8px (NOT 50%; the row is small pills, not circles)
  - color:               rgb(255, 255, 255)
  - inner mat-icon:      18px (font-size + width + height all 18)
  - icon color:          rgb(255, 255, 255)
  - row gap:             10px between buttons (matches .actions gap)

Earlier ship #128312 over-shot these as 36x36 + border-radius 50% +
transparent rest bg + 20px icon + ink-2 rest color. Real surface is a
SQUARED-PILL chip (8px radius, NOT a circle), 37x40 with a baked-in
rgba(193,206,255,0.04) tint at rest, white icon (NOT ink-2). #128602
corrects each of these.

Measured share button (sibling of history; same chip family):
  - same dimensions:     w 37 / h 40
  - same padding:        0 10px
  - same radius:         8px
  - same rest bg:        rgba(193, 206, 255, 0.04)
  - same icon size:      18px mat-icon
  - icon color:          rgb(255, 255, 255)

The share button is the SAME chip as history; the only delta is the icon
glyph. #128603 pins the share specifically so the .header-actions row
inside .item-page-header gets the chip-style baseline rather than the
incorrect ship-#128312 circle.

Measured action-row gap + alignment (.actions inside the image host):
  - display:             flex / row
  - gap:                 10px
  - justify-content:     starts flush-left under the image
  - margin-top:          10px (the image-to-action-row gutter)

#128604 pins the action-row geometry so the row docks 10px under the image
and the chips space at 10px between siblings (matches the measured
.actions { gap: 10px; padding: 0; margin: 0 } from the live .actions
parent at /item).

Measured page-column max-width (the .container host):
  - max-width:           1337px
  - padding:             0
  - margin:              0 auto (centered)

Earlier ships assumed the page max-width tracked the global content max
(some ships used 1100px from /support, others 1200px). The /item page
specifically uses 1337px (a wider grid because /item lays out a left
column for the image+actions and a right column for the ladder + buy
panel). #128605 pins the /item page-level max-width.

Measured app-item-image-actions (the column wrapper for image + action row):
  - host:                <app-item-image-actions>
  - background:          transparent
  - border:              0 (none)
  - border-radius:       0
  - width:               506px (matches the image+row width)
  - no padding, no margin

#128606 pins the host so it never grows borders, radii, or backgrounds
that other generic chrome rules elsewhere in the cascade might paint
onto the column. Matches measurement: pure pass-through wrapper.

ALL APPENDS ARE !important + idempotent (guarded by 'ship #128600' tail
sniff). NO Docker. NO markup changes — CSS-only corrections against the
real measured pixel values.
"""
import os, sys, tempfile

CSS = r"""

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128600 — item-detail page header typography
   CORRECTION (was approximated in #128310 — measured 16px/500 here, not
   18px/600; wrapper 62h, not 36h; allow line wrap, do not ellipsis).
   --------------------------------------------------------------------
   REAL surface: csfloat /item/<id> page header. Lane-spec measurements
   (signed-in session, /item route, StatTrak Desert Eagle | Naga
   Battle-Scarred). Header `.header` is 506w / 62h. Inner
   `app-item-name` is 486w / 42h (10px L+R inset). Two stacked text
   lines: weapon-name in StatTrak orange (rgb(255,120,44)), then the
   StatTrak/wear segment in white. No ellipsis on the name; the block
   wraps to two lines and the wrapper grows to 62h.
   ---------------------------------------------------------------- */
body .item-page-header,
body app-item-page .item-page-header,
body .item-page > .header,
body app-item-image-actions ~ .header,
body .item-modal .item-header,
body .item-modal > .header {
  display: flex !important;
  flex-direction: row !important;
  align-items: center !important;
  justify-content: space-between !important;
  gap: 12px !important;
  width: 100% !important;
  padding: 0 0 12px 0 !important;
  margin: 0 !important;
  background: transparent !important;
  border: 0 !important;
  min-height: 62px !important;
  box-sizing: border-box !important;
}
body .item-page-header > .item-name,
body .item-page-header > .name,
body app-item-page .item-page-header > .item-name,
body .item-modal .item-header > .item-name,
body .item-modal .item-header > .name,
body app-item-name,
body app-item-name > .container {
  flex: 1 1 auto !important;
  min-width: 0 !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 16px !important;
  font-weight: 500 !important;
  line-height: normal !important;
  color: rgb(255, 255, 255) !important;
  letter-spacing: 0 !important;
  white-space: normal !important;
  overflow: visible !important;
  text-overflow: clip !important;
  margin: 0 !important;
  padding: 0 !important;
}
body app-item-name .item-name {
  font-size: 16px !important;
  font-weight: 500 !important;
  line-height: normal !important;
  color: rgb(255, 120, 44) !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
}
body app-item-name .subtext,
body app-item-name .sub-text,
body app-item-name .sub {
  font-size: 13px !important;
  font-weight: 500 !important;
  line-height: normal !important;
  color: rgb(158, 167, 177) !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  letter-spacing: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128600 */


/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128601 — item-detail main image geometry
   CORRECTION (#128313 was approximated; real geometry is 506x380 inside
   a 506x390 container, no border, no border-radius, no padding).
   --------------------------------------------------------------------
   REAL surface: csfloat /item the big steam economy image is rendered
   FLAT — no border, no radius, no padding — inside a transparent
   container. The container is 10px taller than the image to leave
   room for the action-row directly below at 10px gutter (#128604).
   Aspect ratio of the image is exactly 506/380 = 1.33:1.
   ---------------------------------------------------------------- */
body app-item-image-actions .container,
body app-item-image-actions > .container,
body app-item-page app-item-image-actions .container,
body .item-page app-item-image-actions .container {
  width: 506px !important;
  height: 390px !important;
  background: transparent !important;
  border: 0 !important;
  border-radius: 0 !important;
  padding: 0 !important;
  margin: 0 !important;
  box-sizing: border-box !important;
  position: relative !important;
}
body app-item-image-actions .container > img,
body app-item-image-actions > .container > img,
body app-item-page app-item-image-actions img,
body .item-page app-item-image-actions img {
  width: 506px !important;
  height: 380px !important;
  border: 0 !important;
  border-radius: 0 !important;
  padding: 0 !important;
  margin: 0 !important;
  background: transparent !important;
  display: block !important;
  object-fit: contain !important;
}
/* END CSFLOAT-1:1 PARITY ship #128601 */


/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128602 — item-detail history button chip
   CORRECTION (#128312 was approximated as 36x36 / circle / transparent;
   real is 37x40 / 8px radius / rgba(193,206,255,0.04) baked-in tint).
   --------------------------------------------------------------------
   REAL surface: csfloat /item action-row history button. Squared pill
   (8px radius, NOT a circle), 37 wide × 40 tall, padding 0 10px,
   permanent rgba(193,206,255,0.04) tint at rest (NOT transparent),
   white icon (NOT ink-2). Inner mat-icon is 18px, NOT 20px. This is
   the SAME chip used for share, content_copy, and any other action-row
   button — the chip family is "small squared pill", not "circular icon
   button".
   ---------------------------------------------------------------- */
body .item-page-header .header-actions > button,
body .item-page-header .header-actions > a,
body .item-page-header .actions > button,
body .item-page-header .actions > a,
body app-item-image-actions .actions > button,
body app-item-image-actions .actions > a,
body app-item-image-actions button[mat-icon-button],
body app-item-image-actions button.history-btn,
body app-item-image-actions button.share-btn,
body .item-modal .item-header .header-actions > button,
body .item-modal .item-header .actions > button {
  display: inline-flex !important;
  align-items: center !important;
  justify-content: center !important;
  width: 37px !important;
  height: 40px !important;
  min-width: 37px !important;
  min-height: 40px !important;
  padding: 0 10px !important;
  margin: 0 !important;
  border: 0 !important;
  border-radius: 8px !important;
  background-color: rgba(193, 206, 255, 0.04) !important;
  color: rgb(255, 255, 255) !important;
  cursor: pointer !important;
  box-shadow: none !important;
  text-decoration: none !important;
  flex-shrink: 0 !important;
  box-sizing: border-box !important;
  font-size: 14px !important;
  font-weight: 500 !important;
  line-height: 1 !important;
  letter-spacing: 0 !important;
  transition: background-color 200ms cubic-bezier(0.4, 0, 0.2, 1),
              color 200ms cubic-bezier(0.4, 0, 0.2, 1) !important;
}
body .item-page-header .header-actions > button:hover,
body .item-page-header .header-actions > button:focus-visible,
body .item-page-header .header-actions > a:hover,
body app-item-image-actions .actions > button:hover,
body app-item-image-actions .actions > button:focus-visible,
body app-item-image-actions button[mat-icon-button]:hover,
body .item-modal .item-header .header-actions > button:hover {
  background-color: rgba(193, 206, 255, 0.10) !important;
  color: rgb(255, 255, 255) !important;
  outline: 0 !important;
}
body .item-page-header .header-actions > button:active,
body .item-page-header .header-actions > a:active,
body app-item-image-actions .actions > button:active,
body .item-modal .item-header .header-actions > button:active {
  background-color: rgba(193, 206, 255, 0.16) !important;
}
body .item-page-header .header-actions > button > mat-icon,
body .item-page-header .header-actions > a > mat-icon,
body .item-page-header .actions > button > mat-icon,
body app-item-image-actions .actions > button > mat-icon,
body app-item-image-actions button[mat-icon-button] > mat-icon,
body .item-modal .item-header .header-actions > button > mat-icon {
  width: 18px !important;
  height: 18px !important;
  font-size: 18px !important;
  line-height: 1 !important;
  fill: currentColor !important;
  color: inherit !important;
  flex-shrink: 0 !important;
  margin: 0 !important;
  padding: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128602 */


/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128603 — item-detail share button chip
   The Share chip is the SAME chip as History (#128602) — same 37x40,
   same 8px radius, same rgba(193,206,255,0.04) tint, same 18px icon,
   same white color. Pin the .share-btn / share-button selectors so any
   markup that names the share button explicitly inherits the chip
   chrome instead of the prior circular ship-#128312 style.
   ---------------------------------------------------------------- */
body app-item-image-actions button.share-btn,
body app-item-image-actions a.share-btn,
body app-item-image-actions [data-action="share"],
body app-item-image-actions button[aria-label*="hare" i],
body app-item-image-actions a[aria-label*="hare" i],
body .item-page-header .header-actions > button.share-btn,
body .item-page-header .header-actions > a.share-btn,
body .item-modal .item-header .header-actions > button.share-btn {
  width: 37px !important;
  height: 40px !important;
  min-width: 37px !important;
  min-height: 40px !important;
  padding: 0 10px !important;
  border-radius: 8px !important;
  background-color: rgba(193, 206, 255, 0.04) !important;
  color: rgb(255, 255, 255) !important;
  border: 0 !important;
  box-shadow: none !important;
  display: inline-flex !important;
  align-items: center !important;
  justify-content: center !important;
  flex-shrink: 0 !important;
  box-sizing: border-box !important;
}
body app-item-image-actions button.share-btn > mat-icon,
body app-item-image-actions a.share-btn > mat-icon,
body app-item-image-actions [data-action="share"] > mat-icon,
body .item-page-header .header-actions > button.share-btn > mat-icon {
  width: 18px !important;
  height: 18px !important;
  font-size: 18px !important;
  line-height: 1 !important;
  color: rgb(255, 255, 255) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128603 */


/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128604 — item-detail action-row geometry
   The .actions row inside <app-item-image-actions> sits 10px BELOW the
   main image (image is 380h, container is 390h, action-row docks at the
   bottom 10px). Real measurement: display flex / row, gap 10px,
   padding 0, margin 0, justify-content flex-start. Pin so the chips
   line up flush under the image's left edge with consistent 10px
   intra-chip spacing.
   ---------------------------------------------------------------- */
body app-item-image-actions .actions,
body app-item-image-actions > .actions,
body app-item-page app-item-image-actions .actions,
body .item-page app-item-image-actions .actions {
  display: flex !important;
  flex-direction: row !important;
  align-items: center !important;
  justify-content: flex-start !important;
  gap: 10px !important;
  padding: 0 !important;
  margin: 10px 0 0 0 !important;
  width: 100% !important;
  min-height: 40px !important;
  box-sizing: border-box !important;
  background: transparent !important;
  border: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128604 */


/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128605 — item-detail page-column max-width
   The /item route's centered .container host computes max-width 1337px
   (wider than /support's 1100 because /item splits a left image+actions
   column and a right ladder+buy column). Pin so the /item page never
   inherits the narrower 1100/1200 max-width set elsewhere by /profile
   or /support page rules.
   ---------------------------------------------------------------- */
body app-item-page > .container,
body app-item-page .container,
body app-listing-page > .container,
body app-listing-page .container,
body .item-page > .container,
body .item-page .container {
  max-width: 1337px !important;
  width: 100% !important;
  margin-left: auto !important;
  margin-right: auto !important;
  padding: 0 !important;
  box-sizing: border-box !important;
}
/* END CSFLOAT-1:1 PARITY ship #128605 */


/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128606 — item-detail image-actions host
   The <app-item-image-actions> host is a pure pass-through wrapper —
   transparent, no border, no border-radius, no padding, no margin,
   width tracks the inner .container (506px). Pin to defeat any
   generic .panel / .card chrome elsewhere in the cascade that might
   paint a border or background on this host.
   ---------------------------------------------------------------- */
body app-item-image-actions,
body app-item-page app-item-image-actions,
body .item-page app-item-image-actions {
  display: block !important;
  width: 506px !important;
  background: transparent !important;
  border: 0 !important;
  border-radius: 0 !important;
  padding: 0 !important;
  margin: 0 !important;
  box-shadow: none !important;
  box-sizing: border-box !important;
}
/* END CSFLOAT-1:1 PARITY ship #128606 */
"""

target = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"
with open(target, 'rb') as f:
    f.seek(-4096, os.SEEK_END)
    tail = f.read().decode('utf-8', errors='replace')
if 'ship #128600' in tail:
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
    print('APPENDED', len(CSS), 'bytes -> ships #128600-#128606')
except Exception as e:
    if os.path.exists(tmpname):
        try: os.remove(tmpname)
        except: pass
    raise
