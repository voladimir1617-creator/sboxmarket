"""Atomic append of CSFLOAT 1:1 PARITY ships #128940-#128947 — REAL signed-in
csfloat cart panel chrome (the popover that opens when you click the
shopping-cart icon in the top-right header), measured 2026-05-08 against
csfloat.com via mcp__playwright__browser_navigate +
mcp__playwright__browser_evaluate.

GROUND TRUTH (signed-in, viewport 1440, 2026-05-08):

  /cart                                404 Not Found.
                                       Cart is panel-only. There is NO
                                       standalone cart route on csfloat.
                                       The cart is opened ONLY by clicking
                                       the basket icon in the top-right of
                                       app-header, which renders a CDK
                                       connected-position-overlay
                                       (`.cdk-overlay-popover`) anchored
                                       under the icon. This invalidates the
                                       sboxmarket `.csfloat-cart-drawer`
                                       full-height side-drawer model.
                                       sboxmarket's existing /cart route
                                       page can stay (it's a richer
                                       view), but the cart popover that
                                       appears when the nav icon is
                                       clicked must match the csfloat
                                       popover layout, NOT a side-drawer.

  Cart-trigger geometry              app-header > a.hoverable-btn (no
                                     href, no routerlink, no
                                     matMenuTrigger) carrying the basket
                                     SVG. 28x28 icon at viewport x~1158
                                     y=21 (right-edge of the header,
                                     between the notifications "!" badge
                                     and the help/discord chat icon).

  Cart popover overlay pane            .cdk-overlay-pane inside
                                       .cdk-overlay-popover.
                                       cdk-overlay-connected-position-
                                       bounding-box positioning (offset
                                       below the icon, right-aligned).
                                       max-height: 680px. max-width: 100%.
                                       width: 370px (computed). pane
                                       itself transparent (bg none / shadow
                                       none / br 0 / pad 0). The chrome
                                       lives on the inner `.container`.

  Cart popover .container (chrome)     bg: rgba(21, 23, 28, 0.8)
                                                 (NOT solid bg-1!)
                                       border: 2px solid
                                                 rgba(193, 206, 255, 0.07)
                                                 (bluish-tinted hairline)
                                       border-radius: 12px
                                       padding: 20px (all sides)
                                       box-shadow: NONE
                                       width: 370px
                                       height: ~724px (overflows the 680
                                              max-height pane → scrolls)
                                       ng-trigger: transformOverlay
                                                   (CDK fade-in transform)

  Cart popover .title                  Inner heading at top of container.
                                       Holds e.g. "Your Cart" + count.

  Cart popover .content                Scrollable list of `.item` rows.
                                       padding 0, margin 0, the list of
                                       cart line-items.

  Each .item line-item                 Rows under .content. Built as
                                       `<div class="item">
                                          <div class="main-row">
                                            <div class="main-item">
                                              <app-item-name-row>
                                                <div class="container">
                                                  <div class="icon
                                                              show-border">
                                                    <img>
                                                  </div>
                                                  <div class="name">
                                                    <div class="prefix"/>
                                                    <div class="suffix"/>
                                                  </div>
                                                </div>
                                              </app-item-name-row>
                                            </div>
                                            <div> (spacer)
                                              <div class="remove">
                                                <button class=
                                                  "mdc-icon-button
                                                   mat-mdc-icon-button
                                                   mat-mdc-button-base">
                                                  <mat-icon>
                                                    <svg> (X icon)
                                                </button>
                                              </div>
                                            </div>
                                          </div>
                                          <div class="details-row">
                                            (price + reference chip)
                                          </div>
                                        </div>`

  .item .icon.show-border              Thumbnail tile with a hairline
                                       border. csfloat uses a
                                       transparent bg + 1px hairline
                                       border (matches search-card
                                       thumbs).

  .reference (inside details-row)      bg: rgba(255, 255, 255, 0.04)
                                       br: 20px
                                       padding: 5px 7px
                                       computed w 69, h 26 (slightly
                                              taller floor than the
                                              search-card chip that's
                                              measured at 65x27 — the cart
                                              chip is on a denser row so
                                              csfloat applies 5/7 padding
                                              instead of 4/6).

  .footer                              border-top: 1px solid
                                                  rgba(193, 206, 255, 0.04)
                                       padding: 20px 0 0
                                                  (top-only, no horiz
                                                   because the parent
                                                   `.container` already
                                                   has 20px padding)
                                       width 326px (= 370 - 2*20 - 2*2
                                              border = 326)
                                       height ~151px (CTA row + balance
                                              row + free-trade checkbox)

  .footer mat-checkbox                 mdc-checkbox standard. The empty
                                       state is .mdc-checkbox__background
                                       18x18 with br 5 + 2px border
                                       rgba(255,255,255,0.7).

  .footer button.primary               Primary "Buy Now" / "Proceed"
                                       button.
                                       bg: rgba(35, 123, 255, 0.15)
                                                (translucent brand!
                                                NOT solid rgb(35,123,255))
                                       br: 8px
                                       padding: 0 16px
                                       height: 36px
                                       width: 252px (when full-width row
                                              minus secondary + gap, on a
                                              370 - 40 padding container)
                                       border: 0
                                       (translucent because the button
                                        sits on a darker translucent
                                        panel and csfloat layers a
                                        15%-alpha brand fill rather than
                                        the canonical solid mat-primary
                                        used on /db product pages.)

  .footer button (secondary)           Secondary action (e.g. Clear /
                                       Refresh).
                                       bg: rgba(193, 206, 255, 0.04)
                                       br: 8px
                                       padding: 0 16px
                                       height: 36px
                                       width: 64px (icon-style narrow)
                                       border: 0

DELTAS vs sboxmarket existing `.csfloat-cart-drawer` (full-height
fixed-right side-drawer):

  sboxmarket existing                  csfloat REAL
  ---------------                      ------------
  position fixed top:0 right:0         CDK connected-overlay popover
  bottom:0  (full vh)                  anchored UNDER the cart icon
  width 380px                          width 370px
  bg var(--bg-1) (solid)               bg rgba(21, 23, 28, 0.8) translucent
  border-left 1px var(--line)          border 2px solid
                                              rgba(193, 206, 255, 0.07)
  br 0 (flush right edge)              br 12px (rounded corners all sides)
  box-shadow -16px 0 48px rgba(0,0,0,  NONE
            0.55)
  transform: translateX(100%) → 0      ng-trigger transformOverlay (CDK
            (slide-in)                       fade+scale)
  head pad 16/20, foot pad 14/20/18    container pad 20 (all), foot
                                              pad 20/0/0
  body pad 8 0                         content pad 0
  empty-state custom div               (csfloat empty-cart not measured —
                                              cart had items at capture)

NEW SHIPS APPENDED (CSS-only, !important, NO JS source change):

  #128940 — REAL csfloat cart popover container chrome. The popover that
            appears when the nav cart icon is clicked is a CDK overlay
            popover, NOT a side-drawer. Add a second ruleset that targets
            sboxmarket cart-popover containers (cdk-overlay-popover-style
            overlay pane content) AND the existing side-drawer's container
            so when sboxmarket migrates to a popover or keeps the drawer,
            both surfaces match the csfloat token grammar:
              bg rgba(21, 23, 28, 0.8) — translucent panel
              border 2px solid rgba(193, 206, 255, 0.07)
              border-radius 12px
              padding 20px
              box-shadow none
              width 370px

  #128941 — Cart popover positioning hint. The cdk-overlay-pane that
            wraps a cart popover container should be max-height 680px,
            max-width 100%, with no chrome of its own (the chrome lives
            on the .container inside). Add cdk-overlay-popover-style
            selectors so a cart popover overlay pane in sboxmarket
            (e.g. `.cdk-overlay-popover .cdk-overlay-pane`) inherits
            csfloat geometry.

  #128942 — Cart popover .container width = 370px exact. This is the
            width csfloat uses on a 1440 viewport. Sboxmarket's existing
            drawer is 380px (10px wider) which makes line-items overflow
            differently. Lock width to 370px on both
            .cart-popover-container AND .csfloat-cart-drawer.

  #128943 — Cart popover footer chrome. csfloat uses a 1px top-border
            in rgba(193, 206, 255, 0.04) (slightly less alpha than the
            container border) and a 20px top-only padding (no horizontal
            padding because the parent container already has 20px all
            around). Sboxmarket's foot uses var(--line) which renders
            grayish; the bluish 4%-alpha matches the rest of csfloat's
            section-divider grammar.

  #128944 — Cart popover primary button: TRANSLUCENT brand fill
            rgba(35, 123, 255, 0.15) on a 36-height pill, br 8, pad
            0 16. This DEVIATES from the /db primary CTA which uses
            solid rgb(35, 123, 255) — but cart-popover sits on a
            translucent panel and csfloat layers the button at 15%
            alpha so the button reads as a button-on-glass rather than
            a flat opaque CTA. The previous ship #17406 (which set the
            solid brand) was correct for /cart standalone-page CTAs but
            wrong for the popover. Ship #128944 carves out the
            popover-specific override.

  #128945 — Cart popover secondary button: rgba(193, 206, 255, 0.04)
            fill at 36h, br 8, pad 0 16. This is the same surface fill
            as the container border but at full-fill (not just border)
            so the button reads as a quiet ghost-style next to the
            primary brand button. Width is icon-narrow (~64px) when
            it carries an icon-only label; expand to full-width if it
            carries text.

  #128946 — Cart popover .item line-item structure. Pin `.item` block
            display, `.main-row` flex with center-align + horizontal
            padding 0 (parent container has the 20 already), and
            `.details-row` block below the main row at 8px top-margin.
            This is the row layout csfloat uses inside the popover
            content area; sboxmarket cart-row used a 3-column grid
            (.cart-thumb + .cart-main + remove icon) which works for
            the standalone /cart page but the popover uses a stacked
            main-row + details-row pattern.

  #128947 — Cart popover .reference chip floor 5px 7px padding
            (slightly larger than the search-card chip's 4px 6px from
            ship #128860). The cart popover line-item rows are denser
            and csfloat scales the chip up by 1px each axis to compensate.
            Apply this floor only when the chip is inside a
            cart-popover .details-row context, NOT globally (which would
            regress the search-card chip).
"""

import os, sys, tempfile, shutil

CSS_PATH = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"

APPEND = r"""

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128940 — REAL csfloat cart popover container
   chrome. Live measured 2026-05-08 against csfloat.com signed-in cart
   icon click → CDK overlay popover. The cart panel that opens from the
   nav cart icon is a CDK connected-position popover (cdk-overlay-pane
   inside cdk-overlay-popover), NOT a fixed-position side-drawer like
   sboxmarket's existing .csfloat-cart-drawer. The chrome lives on the
   inner `.container` element: bg rgba(21,23,28,0.8) (translucent panel,
   NOT solid), border 2px solid rgba(193,206,255,0.07) (bluish hairline,
   NOT gray var(--line)), border-radius 12px, padding 20px all sides,
   NO box-shadow at all (sboxmarket's drawer used -16px 0 48px rgba 0.55
   which read as a heavy floating overlay rather than the flush
   translucent glass csfloat ships). Apply to the existing drawer AND to
   any cart-popover container migration target so both surfaces share
   the canonical csfloat tokens. */
.cart-popover .container,
.cart-popover-container,
.cart-overlay-popover .container,
body .csfloat-cart-drawer,
body .csfloat-cart-popover-container {
  background: rgba(21, 23, 28, 0.8) !important;
  border: 2px solid rgba(193, 206, 255, 0.07) !important;
  border-radius: 12px !important;
  padding: 20px !important;
  box-shadow: none !important;
  box-sizing: border-box !important;
  width: 370px !important;
  max-width: 100% !important;
  max-height: 680px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128940 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128941 — Cart popover overlay-pane geometry.
   On csfloat the .cdk-overlay-pane that wraps the cart popover container
   has max-height 680, max-width 100%, transparent chrome (no bg, no
   shadow, no border, no padding) — all visual chrome lives on the inner
   .container. This rule pins any sboxmarket cart-popover overlay pane
   to the same chromeless wrapper so the panel's translucent bg + bluish
   border + 12px br are not double-stacked with a wrapper bg. */
.cart-popover .cdk-overlay-pane,
.cart-overlay-popover .cdk-overlay-pane,
.cart-popover-pane.cdk-overlay-pane {
  background: transparent !important;
  border: 0 !important;
  border-radius: 0 !important;
  padding: 0 !important;
  box-shadow: none !important;
  max-height: 680px !important;
  max-width: 100% !important;
}
/* END CSFLOAT-1:1 PARITY ship #128941 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128942 — Cart popover .container exact
   width. csfloat measures 370px on a 1440 viewport — sboxmarket's
   existing .csfloat-cart-drawer was 380px (10px wider) which made
   line-items overflow differently and the right-side gap visible
   under the popover differ by 10. Lock width to 370 on both popover
   and drawer surfaces so the content area between the 20px padding
   sides is the canonical 326px csfloat ships. */
body .csfloat-cart-drawer,
body .cart-popover-container,
body .csfloat-cart-popover-container {
  width: 370px !important;
}
@media (max-width: 480px) {
  /* On narrow viewports csfloat allows the popover to fill 100% width
     minus a small inset; preserve the same behavior on sboxmarket. */
  body .csfloat-cart-drawer,
  body .cart-popover-container,
  body .csfloat-cart-popover-container {
    width: 100vw !important;
    max-width: 100vw !important;
  }
}
/* END CSFLOAT-1:1 PARITY ship #128942 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128943 — Cart popover .footer chrome:
   1px top-border rgba(193,206,255,0.04) (slightly less alpha than
   the container border for a softer section break), padding 20px 0 0
   (top-only because the parent .container already has 20 all-around).
   Sboxmarket's foot used var(--line) which renders grayish; the
   bluish 4%-alpha matches the rest of csfloat's section-divider
   grammar (same color used on offer-row dividers, settings panel
   row dividers, etc.). */
body .csfloat-cart-drawer-foot,
body .cart-popover .footer,
body .cart-popover-container .footer,
body .csfloat-cart-popover-container .footer {
  border-top: 1px solid rgba(193, 206, 255, 0.04) !important;
  padding: 20px 0 0 !important;
  background: transparent !important;
  display: flex !important;
  flex-direction: column !important;
  gap: 12px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128943 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128944 — Cart popover PRIMARY button:
   TRANSLUCENT brand fill rgba(35,123,255,0.15) on a 36h pill,
   border-radius 8, padding 0 16. This INTENTIONALLY DEVIATES from
   the /db page primary CTA (ship #17406) which uses solid
   rgb(35,123,255) at 40h — but the cart popover sits on a
   translucent rgba(21,23,28,0.8) panel and csfloat layers its primary
   button at 15% alpha so the button reads as a button-on-glass
   rather than a flat opaque CTA against the translucent backdrop.
   Carving the popover-specific override out from the generic CTA
   token keeps both contexts canonical. */
body .csfloat-cart-drawer-foot button.primary,
body .csfloat-cart-drawer-foot .btn-accent,
body .cart-popover .footer button.primary,
body .cart-popover-container .footer button.primary,
body .csfloat-cart-popover-container .footer button.primary,
body .cart-popover .footer .btn-accent,
body .cart-popover-container .footer .btn-accent {
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
  box-shadow: none !important;
}
body .csfloat-cart-drawer-foot button.primary:hover,
body .cart-popover .footer button.primary:hover,
body .cart-popover-container .footer button.primary:hover,
body .csfloat-cart-popover-container .footer button.primary:hover {
  background: rgba(35, 123, 255, 0.22) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128944 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128945 — Cart popover SECONDARY button:
   bg rgba(193,206,255,0.04) (same surface fill as the container
   border but applied as a flat fill rather than a stroke) at
   36h pill, br 8, pad 0 16, border 0. Reads as a quiet ghost-style
   next to the primary brand button. The fill is bluish-neutral
   (matches the section-divider hairline color) so it doesn't compete
   visually with the brand-blue primary CTA. */
body .csfloat-cart-drawer-foot button:not(.primary):not(.btn-accent),
body .csfloat-cart-drawer-foot .btn-ghost,
body .cart-popover .footer button:not(.primary):not(.btn-accent),
body .cart-popover-container .footer button:not(.primary):not(.btn-accent),
body .csfloat-cart-popover-container .footer button:not(.primary):not(.btn-accent),
body .cart-popover .footer .btn-ghost {
  background: rgba(193, 206, 255, 0.04) !important;
  color: rgb(255, 255, 255) !important;
  border: 0 !important;
  border-radius: 8px !important;
  padding: 0 16px !important;
  height: 36px !important;
  min-height: 36px !important;
  font-size: 14px !important;
  font-weight: 500 !important;
  line-height: 36px !important;
  box-shadow: none !important;
}
body .csfloat-cart-drawer-foot button:not(.primary):not(.btn-accent):hover,
body .cart-popover .footer button:not(.primary):not(.btn-accent):hover,
body .cart-popover-container .footer button:not(.primary):not(.btn-accent):hover {
  background: rgba(193, 206, 255, 0.08) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128945 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128946 — Cart popover .item line-item
   structure. csfloat uses a stacked main-row + details-row pattern
   inside the popover content area:
     .item {
       .main-row { display: flex; align-items: center;
                   justify-content: space-between; padding: 0;
                   gap: 8px; }
       .details-row { margin-top: 8px; display: flex;
                      align-items: center; justify-content: space-between;
                      gap: 6px; }
     }
   Sboxmarket's standalone /cart page used a 3-column grid which works
   for the dedicated page but the popover stacks main + details. This
   ship pins the stacked layout for line-items inside cart-popover /
   csfloat-cart-drawer-body, keeping the standalone /cart page's
   .cart-row 3-column grid untouched (different selector). */
body .csfloat-cart-drawer-body .item,
body .cart-popover .item,
body .cart-popover-container .item,
body .csfloat-cart-popover-container .item {
  display: block !important;
  padding: 12px 0 !important;
  border-bottom: 1px solid rgba(193, 206, 255, 0.04) !important;
}
body .csfloat-cart-drawer-body .item:last-child,
body .cart-popover .item:last-child {
  border-bottom: 0 !important;
}
body .csfloat-cart-drawer-body .item .main-row,
body .cart-popover .item .main-row,
body .cart-popover-container .item .main-row,
body .csfloat-cart-popover-container .item .main-row {
  display: flex !important;
  align-items: center !important;
  justify-content: space-between !important;
  padding: 0 !important;
  gap: 8px !important;
}
body .csfloat-cart-drawer-body .item .details-row,
body .cart-popover .item .details-row,
body .cart-popover-container .item .details-row,
body .csfloat-cart-popover-container .item .details-row {
  margin-top: 8px !important;
  display: flex !important;
  align-items: center !important;
  justify-content: space-between !important;
  gap: 6px !important;
}
body .csfloat-cart-drawer-body .item .remove,
body .cart-popover .item .remove,
body .cart-popover-container .item .remove,
body .csfloat-cart-popover-container .item .remove {
  flex: 0 0 auto !important;
  display: flex !important;
  align-items: center !important;
  justify-content: center !important;
}
body .csfloat-cart-drawer-body .item .remove .mdc-icon-button,
body .cart-popover .item .remove .mdc-icon-button,
body .cart-popover-container .item .remove .mdc-icon-button,
body .csfloat-cart-popover-container .item .remove .mdc-icon-button {
  width: 40px !important;
  height: 40px !important;
  padding: 8px !important;
  border-radius: 999px !important;
  background: transparent !important;
  color: rgb(158, 167, 177) !important;
  border: 0 !important;
}
body .csfloat-cart-drawer-body .item .remove .mdc-icon-button:hover,
body .cart-popover .item .remove .mdc-icon-button:hover,
body .cart-popover-container .item .remove .mdc-icon-button:hover {
  background: rgba(193, 206, 255, 0.08) !important;
  color: rgb(255, 255, 255) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128946 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128947 — Cart popover .reference chip
   floor padding 5px 7px. The cart-popover details-row chip is
   slightly larger than the search-card chip (which is 4px 6px from
   ship #128860) because the popover row is denser visually and
   csfloat scales the chip up by 1px each axis. Measured at
   computed w 69, h 26 inside the popover (vs w 65, h 27 on a
   search card).

   Apply ONLY when the chip is nested inside a cart-popover
   .details-row context — do NOT widen the global .reference rule
   from #128860 (which would regress every chip on /search). */
body .csfloat-cart-drawer-body .item .details-row .reference,
body .cart-popover .item .details-row .reference,
body .cart-popover-container .item .details-row .reference,
body .csfloat-cart-popover-container .item .details-row .reference {
  padding: 5px 7px !important;
  min-height: 26px !important;
  min-width: 69px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128947 */

"""

with open(CSS_PATH, "rb") as f:
    existing = f.read()

# Idempotency guard
if b"#128940" in existing and b"#128947" in existing:
    print("Already appended ships #128940-#128947. Skipping.")
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

print(f"Appended ships #128940-#128947. New size: {len(new_blob)} bytes.")
