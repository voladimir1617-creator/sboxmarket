#!/usr/bin/env python3
"""
Ships #127930-#127935 — Profile /buyorders tab parity.

Source-of-truth measurements taken from csfloat.com/profile signed in as Chib
(user has 2 active buy orders). Surface = the Material `mat-button-toggle`
intra-page tab on /profile (NOT a /profile/buy-orders sub-route — that 404s).

Component path on csfloat:
  app-user-orders > div.slimmed-table > table.buy-order-profile-table
  Columns: Price | Quantity | Expression | (edit) | (remove)
  Plus mat-paginator at the bottom ("Items per page", "1-2 of 2")

Sboxmarket equivalents:
  ProfileBuyOrdersTab > .buyorder-list > .buyorder-row
  Existing ships #2900-#2903 already paint .buyorder-row as a flat stacked
  row, but the typography / column rhythm / paginator are unmeasured.
  Existing ships #16900-#16906 paint the CREATE modal — different scope.

These 6 ships pin the LIST surface to csfloat's exact Material data-table.
APPEND-only. !important everywhere.
"""
import os, sys, time

CSS_PATH = r"c:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"

PATCH = r"""

/* ─────────────────────────────────────────────────────────────────────────
   CSFLOAT-1:1 PARITY ships #127930-#127935 — /profile Buy Orders tab list
   surface (csfloat .buy-order-profile-table). Measured 2026-05-08 against
   csfloat.com/profile → Buy Orders intra-page Material tab while signed
   in as Chib (skinbox.market) with 2 active orders. The csfloat surface
   is a Material data-table inside a profile-card panel, NOT a stack of
   custom rows. Existing ship #2900 paints .buyorder-row as a flat row;
   these ships pin the surrounding table chrome / typography / paginator
   to csfloat's exact Material grid, scoped to .full-page-mode .buyorder-list
   so the modal-mode list (BuyOrdersModal) is unaffected.
   ───────────────────────────────────────────────────────────────────── */

/* CSFLOAT-1:1 PARITY ship #127930 — .buyorder-list panel chrome
   csfloat wraps the data-table inside a .container.profile-card with
   bg rgb(27,29,36), border-radius 12px, padding 30px, no border, no
   shadow. sbox .buyorder-list inside ProfileBuyOrdersTab is the inner
   list region; pin its OUTER profile-panel container to csfloat's panel
   tokens so the list reads as a flat Material card, not a popup. */
html body .full-page-mode .profile-panel:has(.buyorder-list),
html body.full-page-mode .profile-panel:has(.buyorder-list) {
  background: rgb(27, 29, 36) !important;
  border: 0 !important;
  border-radius: 12px !important;
  padding: 30px !important;
  box-shadow: none !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  color: rgb(255, 255, 255) !important;
}
/* END CSFLOAT-1:1 PARITY ship #127930 */

/* CSFLOAT-1:1 PARITY ship #127931 — .buyorder-list as Material data-table
   csfloat's <table.buy-order-profile-table> is bg transparent, color
   white, font Roboto/Helvetica Neue, no border-radius, no border. The
   header THs sit on a tinted strip rgba(193,206,255,0.04) (col-line
   sentinel). Apply the same surface to the .buyorder-list outer
   container so the rows-stack reads as a Material data-table body. */
html body .full-page-mode .buyorder-list {
  background: transparent !important;
  border: 0 !important;
  border-radius: 0 !important;
  padding: 0 !important;
  margin: 0 !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  color: rgb(255, 255, 255) !important;
  font-size: 14px !important;
  font-weight: 400 !important;
  line-height: 20px !important;
  letter-spacing: normal !important;
  display: flex !important;
  flex-direction: column !important;
  gap: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #127931 */

/* CSFLOAT-1:1 PARITY ship #127932 — .buyorder-row as Material table row
   csfloat <tr> rows render at 52px height (measured) with bg transparent,
   no border-bottom, no padding (cells own padding). The row reads as a
   horizontal flex grid with the same column rhythm as the THs:
   100px Price | 100px Qty | flex Expression | 60px Edit | 60px Remove.
   sbox .buyorder-row was a generic stacked row; pin it to csfloat exact
   row geometry. Tap-target stays at 52px so it matches Material density. */
html body .full-page-mode .buyorder-list > .buyorder-row,
html body .full-page-mode .buyorder-list > .buy-order-row,
html body .full-page-mode .buyorder-list > .bo-row {
  display: flex !important;
  flex-direction: row !important;
  align-items: center !important;
  min-height: 52px !important;
  padding: 0 !important;
  margin: 0 !important;
  background: transparent !important;
  border: 0 !important;
  border-bottom: 0 !important;
  border-radius: 0 !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 14px !important;
  font-weight: 400 !important;
  line-height: 20px !important;
  color: rgb(255, 255, 255) !important;
  letter-spacing: normal !important;
  transition: background-color 150ms cubic-bezier(0.4, 0, 0.2, 1) !important;
}
html body .full-page-mode .buyorder-list > .buyorder-row:hover,
html body .full-page-mode .buyorder-list > .buy-order-row:hover,
html body .full-page-mode .buyorder-list > .bo-row:hover {
  background: rgba(255, 255, 255, 0.04) !important;
}
/* END CSFLOAT-1:1 PARITY ship #127932 */

/* CSFLOAT-1:1 PARITY ship #127933 — .buyorder-title / .buyorder-sub typography
   csfloat doesn't stack item-name + meta — it puts each in its own column
   ("Price", "Quantity", "Expression"). sbox renders them stacked, so we
   keep the stack but pin the typography to csfloat's exact Roboto sizes:
   .buyorder-title 14px / 400 / white (matches td.cdk-column-expression
   text), .buyorder-sub 12px / 400 / rgb(158,167,177) (matches the
   "Items per page" muted ink). .buyorder-cap (max-price) right-side ink
   stays white 14px / 500 to read as the row's anchor numeric. */
html body .full-page-mode .buyorder-list > .buyorder-row .buyorder-title {
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 14px !important;
  font-weight: 400 !important;
  line-height: 20px !important;
  color: rgb(255, 255, 255) !important;
  letter-spacing: normal !important;
  margin: 0 !important;
}
html body .full-page-mode .buyorder-list > .buyorder-row .buyorder-sub {
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 12px !important;
  font-weight: 400 !important;
  line-height: 16px !important;
  color: rgb(158, 167, 177) !important;
  letter-spacing: normal !important;
  margin: 2px 0 0 0 !important;
}
html body .full-page-mode .buyorder-list > .buyorder-row .buyorder-sub strong {
  color: rgb(255, 255, 255) !important;
  font-weight: 500 !important;
}
html body .full-page-mode .buyorder-list > .buyorder-row .buyorder-cap {
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 14px !important;
  font-weight: 500 !important;
  line-height: 20px !important;
  color: rgb(255, 255, 255) !important;
  letter-spacing: 0.42px !important;
  text-align: right !important;
}
/* END CSFLOAT-1:1 PARITY ship #127933 */

/* CSFLOAT-1:1 PARITY ship #127934 — wallet-tx-filter-row (status chip strip)
   reused on /buyorders. csfloat's intra-tab filter strip uses 12px Roboto
   500 ink rgb(158,167,177) on a transparent background, with the active
   chip flipping to white text on a 4% accent-tint pill. The strip itself
   is a horizontal flex row with 8px gap, sitting above the data-table
   with 16px bottom margin (matches the gap between csfloat's
   mat-button-toggle-group.profile-tabs and the table). */
html body .full-page-mode .profile-panel .wallet-tx-filter-row {
  display: flex !important;
  flex-direction: row !important;
  flex-wrap: wrap !important;
  gap: 8px !important;
  align-items: center !important;
  margin-bottom: 16px !important;
  padding: 0 !important;
  background: transparent !important;
  border: 0 !important;
}
html body .full-page-mode .profile-panel .wallet-tx-filter-row .wallet-tx-filter-chip {
  display: inline-flex !important;
  align-items: center !important;
  height: 30px !important;
  padding: 0 12px !important;
  border-radius: 6px !important;
  border: 0 !important;
  background: rgba(193, 206, 255, 0.04) !important;
  color: rgb(158, 167, 177) !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 12px !important;
  font-weight: 500 !important;
  line-height: 28px !important;
  letter-spacing: 0.42px !important;
  text-transform: none !important;
  cursor: pointer !important;
  transition: background-color 150ms cubic-bezier(0.4, 0, 0.2, 1), color 150ms cubic-bezier(0.4, 0, 0.2, 1) !important;
}
html body .full-page-mode .profile-panel .wallet-tx-filter-row .wallet-tx-filter-chip:hover {
  background: rgba(193, 206, 255, 0.08) !important;
  color: rgb(255, 255, 255) !important;
}
html body .full-page-mode .profile-panel .wallet-tx-filter-row .wallet-tx-filter-chip.active,
html body .full-page-mode .profile-panel .wallet-tx-filter-row .wallet-tx-filter-chip[aria-pressed="true"] {
  background: rgba(35, 123, 255, 0.16) !important;
  color: rgb(255, 255, 255) !important;
  font-weight: 500 !important;
}
/* END CSFLOAT-1:1 PARITY ship #127934 */

/* CSFLOAT-1:1 PARITY ship #127935 — .empty-inline (empty-state) for /buyorders
   csfloat shows an empty-state when the buy-order table has 0 rows: 56px
   tall mat-paginator above a centered "No buy orders" message inside the
   profile-card panel. sbox renders .empty-inline with a small icon + 15px
   600 ink headline + 13px secondary line + accent CTA. Pin the typography
   to csfloat's empty-state ink stack so the layout reads inside the same
   profile-card chrome (already painted by ship #127930). 56px paginator
   shape stays for parity with csfloat's mat-paginator height. */
html body .full-page-mode .profile-panel .empty-inline {
  background: transparent !important;
  border: 0 !important;
  border-radius: 0 !important;
  padding: 40px 24px !important;
  margin: 0 auto !important;
  max-width: 480px !important;
  text-align: center !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  color: rgb(255, 255, 255) !important;
  display: flex !important;
  flex-direction: column !important;
  align-items: center !important;
  justify-content: center !important;
  gap: 0 !important;
}
html body .full-page-mode .profile-panel .empty-inline .empty-icon {
  width: 56px !important;
  height: 56px !important;
  border-radius: 50% !important;
  background: rgba(193, 206, 255, 0.04) !important;
  display: inline-flex !important;
  align-items: center !important;
  justify-content: center !important;
  color: rgb(158, 167, 177) !important;
  margin-bottom: 14px !important;
}
html body .full-page-mode .profile-panel .empty-inline > div:not(.empty-icon) {
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
}
/* END CSFLOAT-1:1 PARITY ship #127935 */

/* END CSFLOAT-1:1 PARITY ships #127930-#127935 */
"""

def main():
    if not os.path.exists(CSS_PATH):
        print(f"ERROR: {CSS_PATH} missing", file=sys.stderr); sys.exit(1)
    # Atomic append: open in a/b mode then close. UTF-8 with no BOM.
    with open(CSS_PATH, "ab") as f:
        f.write(PATCH.encode("utf-8"))
    sz = os.path.getsize(CSS_PATH)
    print(f"OK appended {len(PATCH)} bytes -> total {sz} bytes")

if __name__ == "__main__":
    main()
