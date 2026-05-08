"""Atomic append of CSFLOAT 1:1 PARITY ships #129020-#129070 — cart-popover
EMPTY-state inside the CDK-overlay popover container (NOT the standalone /cart
page empty state, which is already covered by ships #1600 / #1604, AND NOT
the populated popover chrome which is covered by ships #128940-#128947).

GROUND TRUTH:

  Reach status (2026-05-08):                csfloat empty-cart popover could
                                            not be measured live in this
                                            session — the browser session
                                            was anonymous on csfloat.com (no
                                            saved cookie cart available to
                                            empty), and the cart popover
                                            component is only rendered into
                                            the DOM after the user is
                                            signed in. /cart returned 404
                                            (popover-only feature, no
                                            standalone route, confirmed by
                                            ship #128940). The previous
                                            cart-page-real agent recorded
                                            "csfloat empty-cart not
                                            measured — cart had items at
                                            capture" in the ship #128940
                                            ground-truth block, so the
                                            empty-state pixel values below
                                            are derived from:

                                              (a) ships #128940-#128947
                                                  (populated popover chrome
                                                  CONFIRMED on csfloat.com
                                                  signed-in session
                                                  2026-05-08)
                                              (b) ships #1600 / #1604 (cart
                                                  EMPTY-state typography
                                                  rhythm CONFIRMED on
                                                  csfloat.com /cart
                                                  standalone empty page)
                                              (c) the contract that the
                                                  popover empty-state
                                                  swaps the .content list
                                                  for an empty-block but
                                                  keeps the same .container
                                                  chrome, .title, .footer
                                                  envelope.

                                            All NEW ships in this batch are
                                            tagged TENTATIVE — they apply
                                            only when the empty-state markup
                                            is nested inside the cart-popover
                                            container, and they reuse the
                                            exact CONFIRMED tokens from the
                                            populated popover chrome so they
                                            cannot regress that chrome. When
                                            a future agent reaches the
                                            signed-in csfloat empty popover
                                            and measures the actual values,
                                            ANY deviating numbers below
                                            should be APPENDED-as-corrections
                                            (do not edit these blocks).

  Cart popover (CONFIRMED #128940)        bg rgba(21,23,28,0.8)
                                          border 2px rgba(193,206,255,0.07)
                                          br 12, pad 20, w 370, max-h 680
                                          (chrome unchanged when empty).

  Cart popover empty BODY (DERIVED)       The .content node that normally
                                          holds .item rows is replaced with
                                          a centered empty-block containing
                                          (top→bottom):
                                            1. a circular icon tile
                                               (csfloat empty-state idiom
                                               width=64, br=999, bg
                                               rgba(255,255,255,0.04),
                                               1px border
                                               rgba(255,255,255,0.06)),
                                               icon inside is the basket
                                               glyph at 28-32px,
                                               color rgba(255,255,255,0.55)
                                            2. a 12-14px gap
                                            3. headline "Your cart is empty"
                                               14px/600 white (matches the
                                               .title typography of the
                                               populated popover header
                                               which already uses 14/600)
                                            4. a 6-8px gap
                                            5. sub copy "Browse the market
                                               to add items" 12.5px/400
                                               in ink-2 rgb(158,167,177)
                                               line-height 1.55
                                            6. a 16-20px gap
                                            7. "Browse market" CTA — uses
                                               the popover-primary
                                               translucent brand pill
                                               (ship #128944) at 36h, br 8,
                                               pad 0 16, but on the empty
                                               state it stretches to a
                                               narrower fixed width
                                               (~180px) and centers under
                                               the copy block instead of
                                               filling the footer row.

  .footer when empty                      Hidden — csfloat removes the
                                          checkout/balance/free-trade
                                          checkbox row when the cart is
                                          empty. The empty-state CTA above
                                          replaces the footer's primary
                                          button. The .footer's hairline
                                          top-border and 20-0-0 padding
                                          (ship #128943) collapse to 0 so
                                          the empty-block is the only thing
                                          below the .title.

DELTAS vs sboxmarket existing rules:

  sboxmarket existing                     csfloat REAL (DERIVED)
  ---------------                         ----------------------
  .csfloat-cart-empty pad 56/16           padding scoped to popover ctx:
                                          24/0 (parent .container has 20)
  .csfloat-cart-empty-icon 48 br 999      64 br 999 + 1px hairline border
  bg var(--bg-2)                          bg rgba(255,255,255,0.04)
  color var(--ink-4)                      color rgba(255,255,255,0.55)
  font-size 18px                          icon glyph 28px (svg 28x28)
  .csfloat-cart-empty-msg 14/500 ink-2    14/600 white headline
  .csfloat-cart-empty-sub 12/400 ink-4    12.5/400 ink-2, line-height 1.55
  .empty-inline standalone (#1600)        nested under .cart-popover only
  .info-modal-panel:has CTA 38h 13/600    36h 14/500 (popover-primary
                                          ship #128944 carve-out at 36h)

NEW SHIPS APPENDED (CSS-only, !important, NO JS source change):

  #129020 — Cart-popover empty-state container. When the empty-state markup
            is nested inside .cart-popover / .csfloat-cart-drawer-body /
            .cart-popover-container, pin display flex column align center,
            padding 24px 0 (parent .container already has 20 padding so the
            empty-block adds an extra 24 vertical breathing without
            re-doubling horizontal padding), gap 12px between icon /
            headline / sub / CTA. This MUST NOT cascade to .empty-inline
            (the standalone /cart page) which is already canonical via
            ship #1600 — the popover selector is more specific.

  #129030 — Cart-popover empty-state ICON tile. 64x64 circle, br 999,
            bg rgba(255,255,255,0.04), 1px border rgba(255,255,255,0.06),
            margin 0 auto 12px (the next gap is bridged by container's gap,
            but margin-bottom keeps a visible breath even if the parent
            isn't a flex with gap). Inner svg/glyph 28x28 colored
            rgba(255,255,255,0.55). Reuses the same tile geometry csfloat
            uses on EVERY empty-state across the app (search-no-results,
            offers-empty, watchlist-empty all use the same 64-circle
            hairline bg-04 border-06 grammar — CONFIRMED via ship #1600
            empty-inline).

  #129040 — Cart-popover empty-state HEADLINE typography. "Your cart is
            empty" rendered at 14px/600 in white, letter-spacing -0.005em
            (csfloat's standard heading microadjust), margin 0. Matches the
            .title typography of the populated popover header so the
            heading stays visually consistent whether the cart has items or
            is empty.

  #129050 — Cart-popover empty-state SUB copy typography. "Browse the
            market to add items" rendered at 12.5px/400 in ink-2
            rgb(158,167,177), line-height 1.55, margin 0, max-width 240px
            centered (so the sub copy wraps tidily inside the 326px content
            width = 370 - 2*20 padding - 2*2 border).

  #129060 — Cart-popover empty-state CTA. "Browse market" pill: same
            translucent brand fill as the populated popover primary button
            (ship #128944 — rgba(35,123,255,0.15) at 36h, br 8, pad 0 16,
            font 14/500), but width LOCKED to 180px (NOT full-width) and
            margin 4px auto 0 so it centers under the copy block rather
            than filling the footer row. On hover it lifts to
            rgba(35,123,255,0.22) — same hover delta as ship #128944 so the
            interaction feel is identical to the populated state's primary
            button.

  #129070 — Cart-popover empty-state FOOTER collapse. When the popover is
            in empty-state, the .footer (which normally carries the
            balance + free-trade checkbox + buy-now row, ship #128943)
            collapses to display none — csfloat removes the entire footer
            block when the cart is empty because there's nothing to check
            out. The empty-state CTA from #129060 replaces it. Use
            :has() so the rule only fires when an empty-state marker
            (.csfloat-cart-empty / .empty-inline / [data-empty]) is
            present inside the popover — this prevents accidentally
            collapsing the footer when the cart is populated.
"""

import os, sys, tempfile, shutil

CSS_PATH = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"

APPEND = r"""

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #129020 — cart-popover empty-state CONTAINER.
   When the empty-state markup is nested inside the cart-popover (NOT the
   standalone /cart page, which ship #1600 already covers via .empty-inline),
   pin display flex column align center, padding 24px 0 (parent .container
   already has 20 padding so this adds vertical breathing without re-doubling
   horizontal padding), gap 12px between icon / headline / sub / CTA.

   TENTATIVE — cart popover empty-state could not be measured live in this
   session (anonymous browser session, popover requires sign-in). Values
   derived from CONFIRMED ships #128940-#128947 (populated popover chrome)
   and #1600 / #1604 (standalone /cart empty-state typography). When a future
   agent reaches the signed-in csfloat empty popover, append corrections —
   do NOT edit this block. */
body .cart-popover .csfloat-cart-empty,
body .cart-popover-container .csfloat-cart-empty,
body .csfloat-cart-popover-container .csfloat-cart-empty,
body .csfloat-cart-drawer-body .csfloat-cart-empty,
body .cart-popover .empty-inline,
body .cart-popover-container .empty-inline,
body .csfloat-cart-popover-container .empty-inline,
body .csfloat-cart-drawer-body .empty-inline {
  display: flex !important;
  flex-direction: column !important;
  align-items: center !important;
  justify-content: center !important;
  padding: 24px 0 !important;
  gap: 12px !important;
  text-align: center !important;
  background: transparent !important;
  border: 0 !important;
  box-shadow: none !important;
}
/* END CSFLOAT-1:1 PARITY ship #129020 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #129030 — cart-popover empty-state ICON tile.
   64x64 circle, border-radius 999, bg rgba(255,255,255,0.04), 1px border
   rgba(255,255,255,0.06), margin 0 auto 12px so a visible breath is kept
   even when parent isn't flex-gap. Inner svg/glyph 28x28 colored
   rgba(255,255,255,0.55) — the same icon tile geometry csfloat uses on
   EVERY empty-state (search-no-results, offers-empty, watchlist-empty
   all share this 64-circle hairline grammar). TENTATIVE — see #129020. */
body .cart-popover .csfloat-cart-empty-icon,
body .cart-popover-container .csfloat-cart-empty-icon,
body .csfloat-cart-popover-container .csfloat-cart-empty-icon,
body .csfloat-cart-drawer-body .csfloat-cart-empty-icon,
body .cart-popover .empty-inline .empty-icon,
body .cart-popover-container .empty-inline .empty-icon,
body .csfloat-cart-popover-container .empty-inline .empty-icon,
body .csfloat-cart-drawer-body .empty-inline .empty-icon {
  width: 64px !important;
  height: 64px !important;
  border-radius: 999px !important;
  background: rgba(255, 255, 255, 0.04) !important;
  border: 1px solid rgba(255, 255, 255, 0.06) !important;
  color: rgba(255, 255, 255, 0.55) !important;
  display: flex !important;
  align-items: center !important;
  justify-content: center !important;
  margin: 0 auto 12px !important;
  font-size: 28px !important;
  line-height: 1 !important;
  flex: 0 0 auto !important;
}
body .cart-popover .csfloat-cart-empty-icon svg,
body .cart-popover-container .csfloat-cart-empty-icon svg,
body .csfloat-cart-popover-container .csfloat-cart-empty-icon svg,
body .csfloat-cart-drawer-body .csfloat-cart-empty-icon svg,
body .cart-popover .empty-inline .empty-icon svg,
body .cart-popover-container .empty-inline .empty-icon svg,
body .csfloat-cart-popover-container .empty-inline .empty-icon svg,
body .csfloat-cart-drawer-body .empty-inline .empty-icon svg {
  width: 28px !important;
  height: 28px !important;
}
/* END CSFLOAT-1:1 PARITY ship #129030 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #129040 — cart-popover empty-state HEADLINE.
   "Your cart is empty" rendered at 14px/600 in white, letter-spacing
   -0.005em, margin 0. Matches the .title typography of the populated
   popover header so the heading stays visually consistent whether the
   cart has items or is empty. TENTATIVE — see #129020. */
body .cart-popover .csfloat-cart-empty-msg,
body .cart-popover-container .csfloat-cart-empty-msg,
body .csfloat-cart-popover-container .csfloat-cart-empty-msg,
body .csfloat-cart-drawer-body .csfloat-cart-empty-msg,
body .cart-popover .empty-inline .empty-title,
body .cart-popover-container .empty-inline .empty-title,
body .csfloat-cart-popover-container .empty-inline .empty-title,
body .csfloat-cart-drawer-body .empty-inline .empty-title,
body .cart-popover .empty-inline h2,
body .cart-popover-container .empty-inline h2 {
  font-size: 14px !important;
  font-weight: 600 !important;
  line-height: 1.4 !important;
  color: rgb(255, 255, 255) !important;
  letter-spacing: -0.005em !important;
  margin: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #129040 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #129050 — cart-popover empty-state SUB copy.
   "Browse the market to add items" at 12.5px/400, line-height 1.55, color
   ink-2 rgb(158,167,177), margin 0, max-width 240px centered so the copy
   wraps tidily inside the 326px content width (370 - 2*20 padding -
   2*2 border). TENTATIVE — see #129020. */
body .cart-popover .csfloat-cart-empty-sub,
body .cart-popover-container .csfloat-cart-empty-sub,
body .csfloat-cart-popover-container .csfloat-cart-empty-sub,
body .csfloat-cart-drawer-body .csfloat-cart-empty-sub,
body .cart-popover .empty-inline .empty-sub,
body .cart-popover-container .empty-inline .empty-sub,
body .csfloat-cart-popover-container .empty-inline .empty-sub,
body .csfloat-cart-drawer-body .empty-inline .empty-sub,
body .cart-popover .empty-inline p,
body .cart-popover-container .empty-inline p {
  font-size: 12.5px !important;
  font-weight: 400 !important;
  line-height: 1.55 !important;
  color: rgb(158, 167, 177) !important;
  margin: 0 auto !important;
  max-width: 240px !important;
}
/* END CSFLOAT-1:1 PARITY ship #129050 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #129060 — cart-popover empty-state CTA.
   "Browse market" pill: same translucent brand fill as the populated
   popover primary button (ship #128944, rgba(35,123,255,0.15) at 36h,
   br 8, pad 0 16, font 14/500) but width LOCKED to 180px (NOT
   full-width) and margin 4px auto 0 so it centers under the copy
   block rather than filling the footer row. Hover lifts to
   rgba(35,123,255,0.22) — same hover delta as ship #128944 so the
   interaction feel is identical to the populated state's primary
   button. TENTATIVE — see #129020. */
body .cart-popover .csfloat-cart-empty-cta,
body .cart-popover-container .csfloat-cart-empty-cta,
body .csfloat-cart-popover-container .csfloat-cart-empty-cta,
body .csfloat-cart-drawer-body .csfloat-cart-empty-cta,
body .cart-popover .empty-inline .btn,
body .cart-popover-container .empty-inline .btn,
body .csfloat-cart-popover-container .empty-inline .btn,
body .csfloat-cart-drawer-body .empty-inline .btn,
body .cart-popover .empty-inline a.btn-primary,
body .cart-popover-container .empty-inline a.btn-primary {
  background: rgba(35, 123, 255, 0.15) !important;
  color: rgb(255, 255, 255) !important;
  border: 0 !important;
  border-radius: 8px !important;
  padding: 0 16px !important;
  height: 36px !important;
  min-height: 36px !important;
  font-size: 14px !important;
  font-weight: 500 !important;
  line-height: 36px !important;
  letter-spacing: 0 !important;
  width: 180px !important;
  max-width: 180px !important;
  margin: 4px auto 0 !important;
  display: inline-flex !important;
  align-items: center !important;
  justify-content: center !important;
  text-align: center !important;
  text-decoration: none !important;
  box-shadow: none !important;
  cursor: pointer !important;
  transition: background 140ms ease !important;
}
body .cart-popover .csfloat-cart-empty-cta:hover,
body .cart-popover-container .csfloat-cart-empty-cta:hover,
body .csfloat-cart-popover-container .csfloat-cart-empty-cta:hover,
body .csfloat-cart-drawer-body .csfloat-cart-empty-cta:hover,
body .cart-popover .empty-inline .btn:hover,
body .cart-popover-container .empty-inline .btn:hover,
body .csfloat-cart-popover-container .empty-inline .btn:hover,
body .csfloat-cart-drawer-body .empty-inline .btn:hover {
  background: rgba(35, 123, 255, 0.22) !important;
  border-color: transparent !important;
  color: rgb(255, 255, 255) !important;
}
/* END CSFLOAT-1:1 PARITY ship #129060 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #129070 — cart-popover empty-state FOOTER
   collapse. When the popover is in empty-state, the .footer (which
   normally carries the balance + free-trade checkbox + buy-now row from
   ship #128943) collapses to display none — csfloat removes the entire
   footer block when the cart is empty because there's nothing to check
   out. The empty-state CTA from #129060 replaces it.

   Use :has() so the rule fires ONLY when an empty-state marker
   (.csfloat-cart-empty, .empty-inline, [data-empty]) is present inside
   the popover — this prevents accidentally collapsing the footer when
   the cart is populated.

   TENTATIVE — see #129020. */
body .cart-popover:has(.csfloat-cart-empty) .footer,
body .cart-popover-container:has(.csfloat-cart-empty) .footer,
body .csfloat-cart-popover-container:has(.csfloat-cart-empty) .footer,
body .cart-popover:has(.empty-inline) .footer,
body .cart-popover-container:has(.empty-inline) .footer,
body .csfloat-cart-popover-container:has(.empty-inline) .footer,
body .csfloat-cart-drawer:has(.csfloat-cart-empty) .csfloat-cart-drawer-foot,
body .csfloat-cart-drawer:has(.empty-inline) .csfloat-cart-drawer-foot,
body .csfloat-cart-drawer:has([data-empty="true"]) .csfloat-cart-drawer-foot {
  display: none !important;
  border-top: 0 !important;
  padding: 0 !important;
  margin: 0 !important;
  height: 0 !important;
  min-height: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #129070 */

"""

with open(CSS_PATH, "rb") as f:
    existing = f.read()

# Idempotency guard
if b"#129020" in existing and b"#129070" in existing:
    print("Already appended ships #129020-#129070. Skipping.")
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

print(f"Appended ships #129020-#129070. New size: {len(new_blob)} bytes.")
