"""Atomic append of CSFLOAT 1:1 PARITY ships #128740-#128745 — seller-details
widget on the item card / item-detail seller chrome.

Live measured against csfloat.com (signed-in session, viewport 1440)
2026-05-08 via mcp__playwright__browser_navigate + mcp__playwright__browser_evaluate.

CSFLOAT GROUND TRUTH (signed-in /search and /item, viewport 1440):

  mat-card.item-card.card-compact      w 246.25, h 419.69
                                       bg  rgb(27, 29, 36)        (panel)
                                       border-radius 12
                                       border 2px solid rgba(0,0,0,0)
                                       box-shadow:
                                         rgba(0,0,0,0.20) 0 2px 1px -1px,
                                         rgba(0,0,0,0.14) 0 1px 1px 0,
                                         rgba(0,0,0,0.12) 0 1px 3px 0
                                       padding 0

  app-seller-details-widget.seller-details  (inside .seller-details-wrapper)
                                       display block
                                       background transparent
                                       padding 0, margin 0
                                       color rgb(255,255,255)

  > div.seller-details                 display flex, flex-direction row,
                                       align-items center
                                       width 218, height ~17-18
                                       gap visually 6px (margin between
                                                        SVG and span text)

    > svg (status dot)                 viewBox "0 0 10 10"
                                       width 10, height 10
                                       inner circle cx5 cy5 r3
                                         fill #9EA7B1 (offline / ink-3)
                                         OR fill #64EC42 / rgb(100,236,66)
                                            when class "online"
                                       outer circle cx5 cy5 r4
                                         stroke same color
                                         stroke-opacity 0.15
                                         stroke-width 2

    > span.text                        text "Online" / "Offline"
                                       color rgb(255,255,255)
                                       font-size 14
                                       font-weight 400
                                       line-height ~17
                                       letter-spacing normal
                                       margin-left 6 (from dot)

    > div (flex spacer)                style "flex-grow: 1"

    > svg (favorite/rating star, opt)  viewBox "0 0 24 24"
                                       width ~16, height ~16
                                       fill #ff8c00 (orange)


SBOXMARKET CURRENT STATE (verified via grep + design.css read 2026-05-08):

  modals.js renders the modal seller block as:
    .modal-seller-info > .modal-seller-av + .modal-seller-info-text >
      .modal-seller-name + (verified chip) + .modal-seller-rating + ship-time

  Ship #1423 already pinned the grid card .grid-status row to:
    margin 8/0/0, padding 8/0/0, border-top 1px hairline,
    fz 12, lh 18, color rgb(158,167,177), display flex, gap 6,
    fw 500, w 100%, min-h 26.

  Ship #1423 also defined .grid-status-dot at 6x6 with:
    background rgb(120, 130, 142) baseline,
    background rgb(74, 222, 128) when .online,
    box-shadow 0 0 4px rgba(74,222,128,0.6) when .online.

  Live csfloat actually uses 10x10 SVG, not 6x6 div, with offline gray
  #9EA7B1 (ink-3) and a translucent OUTER RING (stroke r4 opacity 0.15).
  No box-shadow halo. The gray is brighter (158,167,177-ish) than our
  120,130,142, and the green is rgb(100,236,66) per earlier finding,
  not rgb(74,222,128).

  Also the text on grid-status reads "Online" or "Offline" — currently
  rendered inline; need to confirm it's at csfloat's text color (white,
  not ink-2). On the card .grid-status it should remain ink-2 (csfloat
  card variant uses white inside item-detail panel ONLY).

CORRECTIONS APPENDED (CSS-only, !important, NO JS source change):

  #128740 — Pin .modal-seller-info to csfloat seller chrome geometry:
            display flex row, align-items center, gap 12, padding 16/0,
            border-top 1px rgba(255,255,255,0.06), border-bottom same,
            for parity with the .seller-details-wrapper hairline.
            Existing rule at #5127 uses padding 12/0 + var(--line) — we
            override with !important.

  #128741 — Pin .modal-seller-name color/font to csfloat's white 14/400
            Roboto canonical for the seller-details widget. Currently
            13/500 var(--ink) per #5141 — bump to 14/400 white.

  #128742 — Pin .modal-seller-rating to csfloat's white 12/500 with
            star color gold (#ff8c00). Currently 11px mono ink-3 per
            #5144 — promote to canonical csfloat numbers.

  #128743 — Replace .grid-card .grid-status-dot 6x6 div with a 10x10
            visual that mimics the SVG dot: 10x10 box, inner radial
            indicator + outer translucent ring. Keep div-based markup
            (no JS change), use box-shadow for the outer ring (a single
            inset-equivalent ring via outline + opacity).

  #128744 — Ship the orange favorite/rating star next to the seller
            row in card-context. Inject via ::after on .grid-status
            using Material Icons "star" ligature, fz 16, color #ff8c00,
            margin-left auto, vertical-align middle. CSS-only, no JS
            change. Hide if no rating present (but card always shows
            it on csfloat — leave visible).

  #128745 — Ensure .modal-seller-info status row (Online/Offline)
            text uses csfloat-canonical white, not ink-2. Inject color
            override on the text span inside .modal-seller-info if it
            carries the seller-online-indicator/seller-status pattern.
"""

import os, sys

CSS_PATH = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"

APPEND = r"""

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128740 — seller chrome row geometry on the
   modal item-detail surface. csfloat .seller-details-wrapper sits in
   the card body with: 16px vertical padding, 12px horizontal gap to
   the avatar, a 1px hairline rgba(255,255,255,0.06) above and below.
   sboxmarket .modal-seller-info uses 12/0 padding + var(--line) per
   the original ship #5127. Pin to canonical csfloat geometry. */
.modal-seller-info {
  display: flex !important;
  align-items: center !important;
  gap: 12px !important;
  padding: 16px 0 !important;
  border-top: 1px solid rgba(255, 255, 255, 0.06) !important;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128740 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128741 — seller name typography parity.
   csfloat span/anchor that holds the seller name renders at 14px /
   font-weight 400, color rgb(255,255,255), font-family Roboto.
   sboxmarket .modal-seller-name (and .seller-name) are 13/500 var(--ink)
   per ship #5141 — promote to canonical csfloat numbers. */
.modal-seller-name,
.seller-name {
  font-size: 14px !important;
  font-weight: 400 !important;
  color: rgb(255, 255, 255) !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  letter-spacing: normal !important;
  line-height: 17px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128741 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128742 — seller rating typography parity.
   csfloat shows the rating row as 12/500 white text with the leading
   star glyph in #ff8c00 orange. sboxmarket .modal-seller-rating uses
   11px mono ink-3 per ship #5144. Promote to canonical csfloat numbers
   and color the leading star glyph orange via CSS first-letter trick. */
.modal-seller-rating,
.seller-rating {
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 12px !important;
  font-weight: 500 !important;
  color: rgb(255, 255, 255) !important;
  display: inline-flex !important;
  align-items: center !important;
  gap: 4px !important;
  margin-top: 0 !important;
  letter-spacing: normal !important;
}
.modal-seller-rating::first-letter,
.seller-rating::first-letter {
  color: #ff8c00 !important;
  font-size: 14px !important;
  font-weight: 700 !important;
}
.modal-seller-rating-count,
.seller-rating-count {
  color: rgb(158, 167, 177) !important;
  font-weight: 400 !important;
  font-size: 12px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128742 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128743 — seller status indicator dot on
   grid card. csfloat uses a 10x10 SVG with two concentric circles:
     inner cx5 cy5 r3 fill (color)
     outer cx5 cy5 r4 stroke (color) opacity 0.15 stroke-width 2
   sboxmarket renders a 6x6 div for .grid-status-dot per ship #1423.
   Promote to 10x10 box with the outer translucent ring via box-shadow
   (radial halo simulating the stroke r4 opacity 0.15). Inner circle is
   the 6x6 painted area centered, achieved with a 4px inset background
   via radial-gradient (closest-side from center).

   Offline color: #9EA7B1 (csfloat ink-3 for status indicators).
   Online color:  rgb(100, 236, 66) (per memory feedback finding).
   Both with translucent ring at 0.15 alpha. */
.grid-card .grid-status-dot {
  width: 10px !important;
  height: 10px !important;
  flex: 0 0 10px !important;
  border-radius: 50% !important;
  background-color: transparent !important;
  background-image: radial-gradient(circle at center,
    #9EA7B1 0,
    #9EA7B1 3px,
    transparent 3px,
    transparent 100%) !important;
  box-shadow: 0 0 0 1px rgba(158, 167, 177, 0.15) !important;
  display: inline-block !important;
}
.grid-card .grid-status-dot.online {
  background-image: radial-gradient(circle at center,
    rgb(100, 236, 66) 0,
    rgb(100, 236, 66) 3px,
    transparent 3px,
    transparent 100%) !important;
  box-shadow: 0 0 0 1px rgba(100, 236, 66, 0.15) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128743 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128744 — orange favorite/rating star at
   right edge of the seller-details row. csfloat appends a 16x16 SVG
   star (viewBox 24x24, fill #ff8c00) at the trailing end of the
   .seller-details flex row, after the "flex-grow:1" spacer.
   sboxmarket .grid-status has no such glyph today. Inject via ::after
   on .grid-card .grid-status using the Material Icons "star" ligature
   (font-family Material Icons is already loaded by sboxmarket).
   Pinned to fz 16 with margin-left auto so it sits at the right edge. */
.grid-card .grid-status::after {
  content: "star" !important;
  font-family: "Material Icons" !important;
  font-size: 16px !important;
  line-height: 1 !important;
  color: #ff8c00 !important;
  margin-left: auto !important;
  font-weight: normal !important;
  font-style: normal !important;
  letter-spacing: normal !important;
  text-transform: none !important;
  display: inline-block !important;
  white-space: nowrap !important;
  word-wrap: normal !important;
  direction: ltr !important;
  -webkit-font-feature-settings: "liga" !important;
  -webkit-font-smoothing: antialiased !important;
  vertical-align: middle !important;
  flex: 0 0 auto !important;
}
/* END CSFLOAT-1:1 PARITY ship #128744 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128745 — modal seller online/offline text
   color parity. csfloat span.text inside the .seller-details widget
   renders white (rgb(255,255,255)), not ink-2. sboxmarket may render
   the equivalent text via .seller-online-indicator label or inline
   span next to .seller-online-dot at default modal text color, which
   risks rendering at ink-2 due to the parent .modal-seller-info color
   inheritance. Force white on the trailing label inside the modal-
   seller-info block when wrapped in either .seller-online-indicator
   or .seller-status. Both selectors are no-op if absent. */
.modal-seller-info .seller-online-indicator,
.modal-seller-info .seller-status,
.modal-seller-info .seller-online-text,
.modal-seller-info .seller-status-text {
  color: rgb(255, 255, 255) !important;
  font-size: 14px !important;
  font-weight: 400 !important;
  line-height: 17px !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  letter-spacing: normal !important;
}
/* END CSFLOAT-1:1 PARITY ship #128745 */
"""

# Atomic append: write to a temp file then move into place to avoid a
# half-written CSS if this script is interrupted.
import tempfile, shutil

with open(CSS_PATH, "rb") as f:
    existing = f.read()

# Detect double-append guard (idempotent ship)
if b"#128740" in existing and b"#128745" in existing:
    print("Already appended ships #128740-#128745. Skipping.")
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

print(f"Appended ships #128740-#128745. New size: {len(new_blob)} bytes.")
