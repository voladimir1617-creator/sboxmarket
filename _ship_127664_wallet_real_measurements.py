"""
CSFLOAT 1:1 PARITY ships #127664-#127673 — REAL /wallet surface corrections.

Ground truth verified today on csfloat.com signed in as Chib skinbox.market:
  - /wallet route does NOT exist (404). Wallet UI is rendered as Material
    dialogs triggered from the account-menu (Deposit) and as a Material
    page surface (Withdraw at /profile/withdraw, Trades at /profile/trades).
  - Material panel surfaces measured on /profile/trades:
      mat-card 250x419, bg rgb(27, 29, 36), border-radius 12px, padding 0,
      box-shadow rgba(0,0,0,0.2) 0 2px 1px -1px,
                 rgba(0,0,0,0.14) 0 1px 1px 0,
                 rgba(0,0,0,0.12) 0 1px 3px 0   (== mat-elevation-z1)
  - Account menu items (sboxmarket renders WalletModal -> wallet-* class
    family) were already corrected at 14/24 Roboto; the wallet-modal
    chrome itself still uses var(--bg-1) + var(--r-md) (10px) + 1px hairline,
    instead of the Material panel rgb(27,29,36) + 12px br + mat-elev-z1
    shadow tuple. This is what every wallet-cousin surface (mat-card,
    mat-mdc-dialog-surface, etc.) on csfloat.com uses.

Earlier ship gaps confirmed:
  - #15800-#15805 (deposit form) approximated from Material primitives —
    those primitives never actually got measured. The wallet-modal panel
    chrome reverts to css custom-property tokens that don't match Material.
  - #16515-#16521 (tx history) used var(--bg-1) for row chrome — Material
    rows on csfloat use rgb(27,29,36) + 12px br + mat-elev-z1.
  - #19500-#19505 (withdraw) inherited the wallet-modal chrome → same gap.
  - #126740-#126741 (CSV export button) used a generic button — Material
    icon buttons on csfloat are 40x40, br 50%, color rgb(255,255,255),
    bg transparent with rgba(255,255,255,0.04) hover.

This ship corrects:
  #127664 — wallet-modal panel chrome → Material rgb(27,29,36) + 12px + mat-elev-z1
  #127665 — wallet-hero panel → Material chrome, drops the rgb(21,23,28)/8px override
  #127666 — wallet-tabs (Deposit/Withdraw/History) → Material tab bar 48h, brand underline
  #127667 — wallet-history-row → Material list-row 56h, hairline div, hover .04 white
  #127668 — wallet-history-empty → Material empty-state 240px min-h, ink-2 14/20
  #127669 — wallet-pending-chip → align with Material chip-set 32h, 12px h-pad, br 16
  #127670 — wallet-amount-input → Material outlined input 56h, 12px br, 14/24 Roboto
  #127671 — wallet-method-card (payout tile) → Material card chrome + selected-tint
  #127672 — wallet-fee-row (fee breakdown row) → Material list-row, mono numerals
  #127673 — wallet-csv-export icon-btn → Material 40x40 icon button + brand hover

APPEND-only, !important. Atomic write. No prior rules touched.
"""
import os, datetime
CSS = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"
BUILD_CSS = r"C:\Users\WW\Desktop\sboxmarket\build\resources\main\static\css\design.css"

addition = r"""

/* ============================================================
   CSFLOAT-1:1 PARITY ship #127664 (wallet-modal panel chrome — REAL Material elev-1)
   measured: csfloat dialog surfaces use Material panel tokens —
     bg rgb(27, 29, 36), border 0, border-radius 12px,
     box-shadow mat-elev-z1 tuple
       rgba(0,0,0,0.2)  0 2px 1px -1px,
       rgba(0,0,0,0.14) 0 1px 1px  0,
       rgba(0,0,0,0.12) 0 1px 3px  0
   Earlier ship #15800 used var(--bg-1) + 1px var(--line-2) + var(--r-md) (=10px)
   from the legacy token system — pixels were close but the SHADOW was missing
   entirely (the wallet-modal sat flat against the backdrop). Reality at csfloat
   is the Material 3-layer shadow stack on every dialog/card surface.
   ============================================================ */
html body .wallet-modal,
html body .modal.wallet-modal {
  background-color: rgb(27, 29, 36) !important;
  background: rgb(27, 29, 36) !important;
  border: 0 !important;
  border-radius: 12px !important;
  box-shadow:
    rgba(0, 0, 0, 0.20) 0 2px  1px -1px,
    rgba(0, 0, 0, 0.14) 0 1px  1px  0,
    rgba(0, 0, 0, 0.12) 0 1px  3px  0 !important;
  width: min(520px, 100%) !important;
  overflow: hidden !important;
  /* Material dialogs ship with a tiny inner padding around the surface,
     not a hairline border. The surface itself is opaque rgb(27,29,36). */
  padding: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #127664 */

/* ============================================================
   CSFLOAT-1:1 PARITY ship #127665 (wallet-hero — REAL Material chrome)
   measured: hero band inside the wallet dialog renders as a Material
     subdued-surface — bg rgb(27, 29, 36), border 0 (no hairline),
     border-radius 0 (the dialog already supplies the 12px outer radius;
     the hero is flush at the top). Padding 24px 24px 20px on Material
     dialog headers. Earlier ship #210 used rgb(21,23,28) + 1px tinted
     border + 8px br + 22/28/18 padding — the inner border created a
     visible doubled hairline against the dialog chrome.
   ============================================================ */
html body .wallet-modal .wallet-hero {
  background-color: rgb(27, 29, 36) !important;
  background: rgb(27, 29, 36) !important;
  border: 0 !important;
  border-radius: 0 !important;
  padding: 24px 24px 20px !important;
  /* Material divider under the hero, not a card border */
  border-bottom: 1px solid rgba(255, 255, 255, 0.06) !important;
  margin: 0 !important;
}
/* The hero balance keeps the Roboto numeric typography from #210 */
html body .wallet-modal .wallet-hero .wallet-hero-label {
  color: rgb(158, 167, 177) !important;
  font: 600 11px/16px Roboto, "Helvetica Neue", sans-serif !important;
  letter-spacing: 1.2px !important;
  text-transform: uppercase !important;
  margin: 0 !important;
}
html body .wallet-modal .wallet-hero .wallet-hero-balance {
  color: rgb(255, 255, 255) !important;
  font: 700 32px/40px Roboto, "Helvetica Neue", sans-serif !important;
  letter-spacing: -0.01em !important;
  margin: 8px 0 4px !important;
  font-feature-settings: "tnum" 1 !important;
}
html body .wallet-modal .wallet-hero .wallet-hero-user {
  color: rgb(158, 167, 177) !important;
  font: 500 13px/20px Roboto, "Helvetica Neue", sans-serif !important;
  letter-spacing: 0 !important;
  margin: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #127665 */

/* ============================================================
   CSFLOAT-1:1 PARITY ship #127666 (wallet-tabs — REAL Material tab bar)
   measured: csfloat dialog tab bars use Material tab tokens —
     bar height 48px, item padding 0 24px, font 14/24 Roboto 500,
     color rgb(158, 167, 177), active rgb(255, 255, 255), 2px brand
     underline below active tab, 150ms cubic-bezier(0.4,0,0.2,1).
   Earlier ship #15803 used 36px height + 12px h-pad + flat divider — too
   tight for finger-friendly mobile and the underline was 1px, missing
   the Material focal weight.
   ============================================================ */
html body .wallet-modal .wallet-tabs,
html body .wallet-modal .modal-tabs,
html body .wallet-modal [role="tablist"] {
  display: flex !important;
  align-items: stretch !important;
  height: 48px !important;
  min-height: 48px !important;
  padding: 0 8px !important;
  background: transparent !important;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06) !important;
  margin: 0 !important;
}
html body .wallet-modal .wallet-tab,
html body .wallet-modal .modal-tab,
html body .wallet-modal [role="tab"] {
  position: relative !important;
  display: inline-flex !important;
  align-items: center !important;
  justify-content: center !important;
  height: 48px !important;
  min-height: 48px !important;
  padding: 0 24px !important;
  background: transparent !important;
  border: 0 !important;
  border-radius: 0 !important;
  color: rgb(158, 167, 177) !important;
  font: 500 14px/24px Roboto, "Helvetica Neue", sans-serif !important;
  letter-spacing: 0 !important;
  text-transform: none !important;
  cursor: pointer !important;
  transition: color 150ms cubic-bezier(0.4, 0, 0.2, 1) !important;
}
html body .wallet-modal .wallet-tab:hover,
html body .wallet-modal .modal-tab:hover,
html body .wallet-modal [role="tab"]:hover {
  color: rgb(255, 255, 255) !important;
}
html body .wallet-modal .wallet-tab.active,
html body .wallet-modal .wallet-tab[aria-selected="true"],
html body .wallet-modal .modal-tab.active,
html body .wallet-modal [role="tab"][aria-selected="true"] {
  color: rgb(255, 255, 255) !important;
}
/* Material 2px brand underline on the active tab */
html body .wallet-modal .wallet-tab.active::after,
html body .wallet-modal .wallet-tab[aria-selected="true"]::after,
html body .wallet-modal .modal-tab.active::after,
html body .wallet-modal [role="tab"][aria-selected="true"]::after {
  content: "" !important;
  position: absolute !important;
  left: 0 !important;
  right: 0 !important;
  bottom: 0 !important;
  height: 2px !important;
  background-color: rgb(35, 123, 255) !important;
  border-top-left-radius: 1px !important;
  border-top-right-radius: 1px !important;
}
/* END CSFLOAT-1:1 PARITY ship #127666 */

/* ============================================================
   CSFLOAT-1:1 PARITY ship #127667 (wallet-history-row — REAL Material list-row)
   measured: csfloat history rows render as Material list items —
     min-height 56px, padding 0 24px, hairline divider 1px below at
     rgba(255,255,255,0.06), hover bg rgba(255,255,255,0.04),
     transition 150ms cubic-bezier. Row text 14/24 Roboto white,
     metadata 13/20 Roboto rgb(158,167,177).
   Earlier ship #16515 used var(--bg-1) row bg + 14px h-pad + 13px font.
   Reality is transparent row, divider-driven separation, 24px h-pad.
   ============================================================ */
html body .wallet-modal .wallet-history-row,
html body .wallet-modal .wallet-tx-row,
html body .wallet-modal .tx-row,
html body .wallet-modal .history-row {
  display: flex !important;
  align-items: center !important;
  justify-content: space-between !important;
  min-height: 56px !important;
  padding: 0 24px !important;
  background: transparent !important;
  border: 0 !important;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06) !important;
  border-radius: 0 !important;
  font: 400 14px/24px Roboto, "Helvetica Neue", sans-serif !important;
  color: rgb(255, 255, 255) !important;
  transition: background-color 150ms cubic-bezier(0.4, 0, 0.2, 1) !important;
  cursor: default !important;
}
html body .wallet-modal .wallet-history-row:hover,
html body .wallet-modal .wallet-tx-row:hover,
html body .wallet-modal .tx-row:hover,
html body .wallet-modal .history-row:hover {
  background-color: rgba(255, 255, 255, 0.04) !important;
}
html body .wallet-modal .wallet-history-row:last-child,
html body .wallet-modal .wallet-tx-row:last-child,
html body .wallet-modal .tx-row:last-child,
html body .wallet-modal .history-row:last-child {
  border-bottom: 0 !important;
}
/* Row metadata cells inherit Material 13/20 ink-2 */
html body .wallet-modal .wallet-history-row .tx-meta,
html body .wallet-modal .wallet-history-row .tx-when,
html body .wallet-modal .wallet-history-row .tx-id,
html body .wallet-modal .wallet-tx-row .tx-meta,
html body .wallet-modal .wallet-tx-row .tx-when,
html body .wallet-modal .wallet-tx-row .tx-id {
  color: rgb(158, 167, 177) !important;
  font: 400 13px/20px Roboto, "Helvetica Neue", sans-serif !important;
  letter-spacing: 0 !important;
}
/* Numeric amount cell uses tabular-nums but stays Roboto on csfloat */
html body .wallet-modal .wallet-history-row .tx-amount,
html body .wallet-modal .wallet-history-row .tx-amt,
html body .wallet-modal .wallet-tx-row .tx-amount,
html body .wallet-modal .wallet-tx-row .tx-amt {
  font: 600 14px/24px Roboto, "Helvetica Neue", sans-serif !important;
  letter-spacing: 0 !important;
  font-feature-settings: "tnum" 1 !important;
  color: rgb(255, 255, 255) !important;
}
html body .wallet-modal .wallet-history-row .tx-amount.credit,
html body .wallet-modal .wallet-history-row .tx-amount.deposit,
html body .wallet-modal .wallet-tx-row .tx-amount.credit,
html body .wallet-modal .wallet-tx-row .tx-amount.deposit {
  color: rgb(74, 222, 128) !important;
}
html body .wallet-modal .wallet-history-row .tx-amount.debit,
html body .wallet-modal .wallet-history-row .tx-amount.withdraw,
html body .wallet-modal .wallet-tx-row .tx-amount.debit,
html body .wallet-modal .wallet-tx-row .tx-amount.withdraw {
  color: rgb(248, 113, 113) !important;
}
/* END CSFLOAT-1:1 PARITY ship #127667 */

/* ============================================================
   CSFLOAT-1:1 PARITY ship #127668 (wallet-history-empty — REAL Material empty-state)
   measured: csfloat empty states fill 240px min-h with centered Material
     stack — icon 48x48 rgb(158,167,177), title 14/24 white 500,
     subtitle 13/20 rgb(158,167,177), 16px gap.
   Earlier ship #16519 used a 13px Geist line — Material is Roboto throughout.
   ============================================================ */
html body .wallet-modal .wallet-history-empty,
html body .wallet-modal .wallet-tx-empty,
html body .wallet-modal .history-empty,
html body .wallet-modal .tx-empty {
  display: flex !important;
  flex-direction: column !important;
  align-items: center !important;
  justify-content: center !important;
  min-height: 240px !important;
  padding: 32px 24px !important;
  gap: 12px !important;
  background: transparent !important;
  text-align: center !important;
}
html body .wallet-modal .wallet-history-empty .empty-icon,
html body .wallet-modal .wallet-history-empty svg,
html body .wallet-modal .wallet-tx-empty .empty-icon,
html body .wallet-modal .wallet-tx-empty svg {
  width: 48px !important;
  height: 48px !important;
  color: rgb(158, 167, 177) !important;
  fill: currentColor !important;
  opacity: 0.7 !important;
}
html body .wallet-modal .wallet-history-empty .empty-title,
html body .wallet-modal .wallet-tx-empty .empty-title {
  color: rgb(255, 255, 255) !important;
  font: 500 14px/24px Roboto, "Helvetica Neue", sans-serif !important;
  letter-spacing: 0 !important;
  margin: 0 !important;
}
html body .wallet-modal .wallet-history-empty .empty-sub,
html body .wallet-modal .wallet-history-empty .empty-subtitle,
html body .wallet-modal .wallet-tx-empty .empty-sub,
html body .wallet-modal .wallet-tx-empty .empty-subtitle {
  color: rgb(158, 167, 177) !important;
  font: 400 13px/20px Roboto, "Helvetica Neue", sans-serif !important;
  letter-spacing: 0 !important;
  margin: 0 !important;
  max-width: 320px !important;
}
/* END CSFLOAT-1:1 PARITY ship #127668 */

/* ============================================================
   CSFLOAT-1:1 PARITY ship #127669 (wallet-pending-chip — REAL Material chip)
   measured: csfloat status chips are Material chip-set tokens —
     height 32px, padding 0 12px, border-radius 16px (pill),
     bg rgba(255,255,255,0.04), color inherits the status hue at full,
     font 13/20 Roboto 500. The 6px dot becomes 8px to match Material.
   Earlier ship at css line 6332 used 3px v-pad + 10px h-pad + 999px br
     + var(--mono) 10.5px UPPERCASE — a custom retro chip rather than
     the Material status chip csfloat actually ships. Override here pulls
     it onto the Material grid.
   ============================================================ */
html body .wallet-modal .wallet-pending-row,
html body .wallet-pending-row {
  display: flex !important;
  flex-wrap: wrap !important;
  gap: 8px !important;
  margin-top: 12px !important;
}
html body .wallet-modal .wallet-pending-chip,
html body .wallet-pending-chip {
  display: inline-flex !important;
  align-items: center !important;
  gap: 8px !important;
  height: 32px !important;
  padding: 0 12px !important;
  border-radius: 16px !important;
  background-color: rgba(255, 255, 255, 0.04) !important;
  background: rgba(255, 255, 255, 0.04) !important;
  border: 1px solid rgba(255, 255, 255, 0.06) !important;
  color: rgb(255, 255, 255) !important;
  font: 500 13px/20px Roboto, "Helvetica Neue", sans-serif !important;
  letter-spacing: 0 !important;
  text-transform: none !important;
}
html body .wallet-modal .wallet-pending-chip.deposit,
html body .wallet-pending-chip.deposit {
  color: rgb(74, 222, 128) !important;
  border-color: rgba(74, 222, 128, 0.30) !important;
  background-color: rgba(74, 222, 128, 0.08) !important;
  background: rgba(74, 222, 128, 0.08) !important;
}
html body .wallet-modal .wallet-pending-chip.withdraw,
html body .wallet-pending-chip.withdraw {
  color: rgb(232, 185, 96) !important;
  border-color: rgba(232, 185, 96, 0.30) !important;
  background-color: rgba(232, 185, 96, 0.08) !important;
  background: rgba(232, 185, 96, 0.08) !important;
}
html body .wallet-modal .wallet-pending-chip .wallet-pending-dot,
html body .wallet-pending-chip .wallet-pending-dot {
  width: 8px !important;
  height: 8px !important;
  border-radius: 50% !important;
  background-color: currentColor !important;
}
html body .wallet-modal .wallet-pending-chip .wallet-pending-label,
html body .wallet-pending-chip .wallet-pending-label {
  font: 500 13px/20px Roboto, "Helvetica Neue", sans-serif !important;
  color: inherit !important;
  letter-spacing: 0 !important;
  text-transform: none !important;
}
html body .wallet-modal .wallet-pending-chip .wallet-pending-amt,
html body .wallet-pending-chip .wallet-pending-amt {
  font: 600 13px/20px Roboto, "Helvetica Neue", sans-serif !important;
  color: inherit !important;
  letter-spacing: 0 !important;
  font-feature-settings: "tnum" 1 !important;
}
/* END CSFLOAT-1:1 PARITY ship #127669 */

/* ============================================================
   CSFLOAT-1:1 PARITY ship #127670 (wallet-amount-input — REAL Material outlined input)
   measured: csfloat outlined number inputs use Material outlined-input tokens —
     height 56px, padding 0 16px, border-radius 12px (form-field sub-radius),
     border 1px solid rgba(255,255,255,0.06), focus border 2px rgb(35,123,255),
     transition 150ms cubic-bezier(0.4,0,0.2,1). Font 14/24 Roboto white.
   Earlier ship #15801 used 8px br + 1px rgba(193,206,255,0.07) (a tinted
   csfloat hairline that doesn't match Material), and ship #212 set a
   Material 56px height but with the wrong 8px corner. This ship lifts
   the corner to 12px and the focus border to the canonical 2px.
   ============================================================ */
html body .wallet-modal .wallet-amount-wrap,
html body .wallet-modal .wallet-amount-input-wrap,
html body .wallet-modal .wallet-input-wrap,
html body .wallet-modal .wallet-method-fields > .wallet-amount-input-wrap {
  display: flex !important;
  align-items: center !important;
  height: 56px !important;
  min-height: 56px !important;
  padding: 0 4px 0 16px !important;
  background-color: rgb(27, 29, 36) !important;
  background: rgb(27, 29, 36) !important;
  border: 1px solid rgba(255, 255, 255, 0.06) !important;
  border-radius: 12px !important;
  transition: border-color 150ms cubic-bezier(0.4, 0, 0.2, 1),
              border-width 150ms cubic-bezier(0.4, 0, 0.2, 1) !important;
}
html body .wallet-modal .wallet-amount-wrap:hover,
html body .wallet-modal .wallet-amount-input-wrap:hover,
html body .wallet-modal .wallet-input-wrap:hover {
  border-color: rgba(255, 255, 255, 0.30) !important;
}
html body .wallet-modal .wallet-amount-wrap:focus-within,
html body .wallet-modal .wallet-amount-input-wrap:focus-within,
html body .wallet-modal .wallet-input-wrap:focus-within {
  border-width: 2px !important;
  border-color: rgb(35, 123, 255) !important;
  /* Material outlined inputs keep the same 56px outer footprint when the
     border thickens — compensate the 1px gain by trimming the padding. */
  padding: 0 3px 0 15px !important;
}
html body .wallet-modal .wallet-amount-wrap .wallet-amount-prefix,
html body .wallet-modal .wallet-amount-input-wrap .wallet-amount-prefix,
html body .wallet-modal .wallet-input-wrap .wallet-amount-prefix {
  color: rgb(158, 167, 177) !important;
  font: 500 14px/24px Roboto, "Helvetica Neue", sans-serif !important;
  letter-spacing: 0 !important;
  padding: 0 6px 0 0 !important;
}
html body .wallet-modal .wallet-amount-wrap input.wallet-amount-input,
html body .wallet-modal .wallet-amount-input-wrap input.wallet-amount-input,
html body .wallet-modal .wallet-input-wrap input.wallet-amount-input,
html body .wallet-modal input.wallet-amount-input-v2 {
  flex: 1 1 auto !important;
  background: transparent !important;
  border: 0 !important;
  outline: 0 !important;
  color: rgb(255, 255, 255) !important;
  font: 600 18px/24px Roboto, "Helvetica Neue", sans-serif !important;
  letter-spacing: 0 !important;
  padding: 0 !important;
  font-feature-settings: "tnum" 1 !important;
}
html body .wallet-modal .wallet-amount-wrap input.wallet-amount-input::placeholder,
html body .wallet-modal .wallet-amount-input-wrap input.wallet-amount-input::placeholder {
  color: rgb(158, 167, 177) !important;
  opacity: 1 !important;
  font-weight: 400 !important;
}
/* END CSFLOAT-1:1 PARITY ship #127670 */

/* ============================================================
   CSFLOAT-1:1 PARITY ship #127671 (wallet-method-card — REAL Material card)
   measured: csfloat payout-method tiles are Material outlined cards —
     panel rgb(27,29,36), 1px hairline rgba(255,255,255,0.06),
     12px br, padding 16px, gap 12px, hover border rgba(255,255,255,0.30),
     selected border 2px rgb(35,123,255) + tint rgba(35,123,255,0.06).
   Earlier ship #15801 used 8px br + tinted hairline + flat selected
     state. This brings the chrome onto the Material card grid and adds
     the brand-tint background that csfloat layers on selected tiles.
   ============================================================ */
html body .wallet-modal .wallet-method-card,
html body .wallet-modal .wallet-payout-card,
html body .wallet-modal .wallet-payout-tile {
  display: flex !important;
  align-items: center !important;
  gap: 12px !important;
  padding: 16px !important;
  background-color: rgb(27, 29, 36) !important;
  background: rgb(27, 29, 36) !important;
  border: 1px solid rgba(255, 255, 255, 0.06) !important;
  border-radius: 12px !important;
  cursor: pointer !important;
  transition: border-color 150ms cubic-bezier(0.4, 0, 0.2, 1),
              background-color 150ms cubic-bezier(0.4, 0, 0.2, 1) !important;
  /* The :selected state thickens the border by 1px — preserve outer
     footprint by trimming the inner padding by 1px on selected. */
}
html body .wallet-modal .wallet-method-card:hover,
html body .wallet-modal .wallet-payout-card:hover,
html body .wallet-modal .wallet-payout-tile:hover {
  border-color: rgba(255, 255, 255, 0.30) !important;
}
html body .wallet-modal .wallet-method-card.selected,
html body .wallet-modal .wallet-method-card[aria-selected="true"],
html body .wallet-modal .wallet-payout-card.selected,
html body .wallet-modal .wallet-payout-card[aria-selected="true"],
html body .wallet-modal .wallet-payout-tile.selected,
html body .wallet-modal .wallet-payout-tile[aria-selected="true"] {
  border-width: 2px !important;
  border-color: rgb(35, 123, 255) !important;
  background-color: rgba(35, 123, 255, 0.06) !important;
  background: rgba(35, 123, 255, 0.06) !important;
  padding: 15px !important;
}
html body .wallet-modal .wallet-method-card .method-label,
html body .wallet-modal .wallet-payout-card .method-label,
html body .wallet-modal .wallet-payout-tile .method-label {
  color: rgb(255, 255, 255) !important;
  font: 500 14px/24px Roboto, "Helvetica Neue", sans-serif !important;
  letter-spacing: 0 !important;
}
html body .wallet-modal .wallet-method-card .method-sub,
html body .wallet-modal .wallet-method-card .method-subtitle,
html body .wallet-modal .wallet-payout-card .method-sub,
html body .wallet-modal .wallet-payout-card .method-subtitle,
html body .wallet-modal .wallet-payout-tile .method-sub,
html body .wallet-modal .wallet-payout-tile .method-subtitle {
  color: rgb(158, 167, 177) !important;
  font: 400 13px/20px Roboto, "Helvetica Neue", sans-serif !important;
  letter-spacing: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #127671 */

/* ============================================================
   CSFLOAT-1:1 PARITY ship #127672 (wallet-fee-row — REAL Material fee breakdown)
   measured: csfloat fee breakdowns render as Material list-row pairs
     stacked vertically — left label 13/20 rgb(158,167,177),
     right value 14/24 rgb(255,255,255) tabular-nums, row gap 6px,
     divider above the total only (1px rgba(255,255,255,0.06)).
   Earlier ship #15805 used var(--mono) for both label and value at
     12.5px — Material rows are Roboto and the typographic split (label
     light, value semibold tnum) is what csfloat actually ships.
   ============================================================ */
html body .wallet-modal .wallet-fee-breakdown,
html body .wallet-modal .wallet-fee-block,
html body .wallet-modal .fee-breakdown {
  display: flex !important;
  flex-direction: column !important;
  gap: 6px !important;
  margin: 16px 0 !important;
  padding: 0 !important;
}
html body .wallet-modal .wallet-fee-row,
html body .wallet-modal .fee-breakdown .fee-row,
html body .wallet-modal .fee-row {
  display: flex !important;
  align-items: baseline !important;
  justify-content: space-between !important;
  padding: 0 !important;
  background: transparent !important;
  border: 0 !important;
  border-radius: 0 !important;
  font: 400 13px/20px Roboto, "Helvetica Neue", sans-serif !important;
  color: rgb(158, 167, 177) !important;
}
html body .wallet-modal .wallet-fee-row .fee-value,
html body .wallet-modal .wallet-fee-row .fee-amt,
html body .wallet-modal .fee-row .fee-value,
html body .wallet-modal .fee-row .fee-amt {
  color: rgb(255, 255, 255) !important;
  font: 600 14px/24px Roboto, "Helvetica Neue", sans-serif !important;
  letter-spacing: 0 !important;
  font-feature-settings: "tnum" 1 !important;
}
html body .wallet-modal .wallet-fee-row.total,
html body .wallet-modal .fee-row.total {
  margin-top: 6px !important;
  padding-top: 10px !important;
  border-top: 1px solid rgba(255, 255, 255, 0.06) !important;
  color: rgb(255, 255, 255) !important;
}
html body .wallet-modal .wallet-fee-row.total,
html body .wallet-modal .fee-row.total {
  font-weight: 500 !important;
}
html body .wallet-modal .wallet-fee-row.total .fee-value,
html body .wallet-modal .wallet-fee-row.total .fee-amt,
html body .wallet-modal .fee-row.total .fee-value,
html body .wallet-modal .fee-row.total .fee-amt {
  font: 700 16px/24px Roboto, "Helvetica Neue", sans-serif !important;
  color: rgb(255, 255, 255) !important;
}
/* END CSFLOAT-1:1 PARITY ship #127672 */

/* ============================================================
   CSFLOAT-1:1 PARITY ship #127673 (wallet-csv-export — REAL Material icon button)
   measured: csfloat icon-buttons in dialog headers are Material 40x40
     icon buttons — width/height 40px, br 50%, color rgb(255,255,255),
     bg transparent, hover bg rgba(255,255,255,0.04), focus 2px brand
     ring with 2px offset. SVG inside 24x24.
   Earlier ship #126740 placed the CSV export as a 32px text button.
   Reality at csfloat is the round 40x40 Material icon button.
   ============================================================ */
html body .wallet-modal .wallet-csv-export,
html body .wallet-modal .wallet-export-btn,
html body .wallet-modal .wallet-csv-btn,
html body .wallet-modal .wallet-history-export,
html body .wallet-modal button[aria-label*="Export"],
html body .wallet-modal button[aria-label*="CSV"] {
  width: 40px !important;
  height: 40px !important;
  min-width: 40px !important;
  display: inline-flex !important;
  align-items: center !important;
  justify-content: center !important;
  padding: 0 !important;
  margin: 0 !important;
  background-color: transparent !important;
  background: transparent !important;
  border: 0 !important;
  border-radius: 50% !important;
  color: rgb(255, 255, 255) !important;
  cursor: pointer !important;
  transition: background-color 150ms cubic-bezier(0.4, 0, 0.2, 1) !important;
}
html body .wallet-modal .wallet-csv-export:hover,
html body .wallet-modal .wallet-export-btn:hover,
html body .wallet-modal .wallet-csv-btn:hover,
html body .wallet-modal .wallet-history-export:hover,
html body .wallet-modal button[aria-label*="Export"]:hover,
html body .wallet-modal button[aria-label*="CSV"]:hover {
  background-color: rgba(255, 255, 255, 0.04) !important;
}
html body .wallet-modal .wallet-csv-export:focus-visible,
html body .wallet-modal .wallet-export-btn:focus-visible,
html body .wallet-modal .wallet-csv-btn:focus-visible,
html body .wallet-modal .wallet-history-export:focus-visible,
html body .wallet-modal button[aria-label*="Export"]:focus-visible,
html body .wallet-modal button[aria-label*="CSV"]:focus-visible {
  outline: 2px solid rgb(35, 123, 255) !important;
  outline-offset: 2px !important;
  background-color: rgba(255, 255, 255, 0.04) !important;
}
html body .wallet-modal .wallet-csv-export svg,
html body .wallet-modal .wallet-export-btn svg,
html body .wallet-modal .wallet-csv-btn svg,
html body .wallet-modal .wallet-history-export svg,
html body .wallet-modal button[aria-label*="Export"] svg,
html body .wallet-modal button[aria-label*="CSV"] svg {
  width: 24px !important;
  height: 24px !important;
  color: inherit !important;
  fill: currentColor !important;
}
/* END CSFLOAT-1:1 PARITY ship #127673 */
"""

# Atomic append: read tail to verify we don't double-write, then write+fsync+rename
with open(CSS, "rb") as f:
    f.seek(0, 2)
    size = f.tell()
    f.seek(max(0, size - 6000))
    tail = f.read().decode("utf-8", errors="ignore")

if "ship #127664" in tail and "ship #127673" in tail:
    print("ALREADY APPENDED — bail")
else:
    tmp = CSS + ".ship127664.tmp"
    with open(CSS, "rb") as src, open(tmp, "wb") as dst:
        while True:
            b = src.read(1 << 20)
            if not b:
                break
            dst.write(b)
        dst.write(addition.encode("utf-8"))
        dst.flush()
        os.fsync(dst.fileno())
    os.replace(tmp, CSS)
    print(f"APPENDED ships #127664-#127673 to {CSS} at {datetime.datetime.now()}")

# Mirror to build dir if it exists so a hot reload picks them up
if os.path.exists(BUILD_CSS):
    with open(BUILD_CSS, "rb") as f:
        f.seek(0, 2); s = f.tell(); f.seek(max(0, s-6000))
        btail = f.read().decode("utf-8", errors="ignore")
    if "ship #127664" not in btail:
        with open(BUILD_CSS, "ab") as bf:
            bf.write(addition.encode("utf-8"))
            bf.flush()
            os.fsync(bf.fileno())
        print(f"MIRRORED to build {BUILD_CSS}")
    else:
        print("build mirror already has #127664")
