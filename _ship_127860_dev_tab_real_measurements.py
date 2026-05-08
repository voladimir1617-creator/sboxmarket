"""
csfloat-1:1 ship #127860-#127866 — REAL /profile Developers tab measurements
APPEND-ONLY atomic write to design.css.

Source: live measurement via mcp__playwright__browser_navigate
        https://csfloat.com/profile (Chib skinbox.market session, viewport 1440x900).
        The /profile page renders a horizontal mat-button-toggle-group with
        8 cells: Personal Info, Transactions, Buy Orders, Auto-Bids, Trades,
        Offers, Notifications, Developers. The Developers cell is the
        rightmost intra-page tab; no separate URL — switching the toggle
        replaces the sub-component below the strip in-place.

Measured chain (getComputedStyle on signed-in /profile @ 1440x900):

  mat-button-toggle-group (the strip itself):
    rect:           1236w x 38h, x=60 y=577
    background:     rgba(193, 206, 255, 0.04)        ← csfloat panel-tint
    border:         0px none
    border-radius:  7px
    display:        flex
    transition:     all
    color:          rgb(255, 255, 255)
    font:           16px Roboto, "Helvetica Neue", sans-serif
    padding:        0px

  mat-button-toggle (each cell):
    rect:           ~167w x 32h
    margin:         3px (renders as 6px gap between cells via collapse)
    background:     rgba(0, 0, 0, 0)                  ← transparent inactive
    border:         0px none
    border-radius:  4px
    display:        block

  mat-button-toggle.mat-button-toggle-checked (active cell):
    background:     rgba(193, 206, 255, 0.04)         ← matches strip bg
    border-radius:  4px
    classList:      adds .mat-button-toggle-checked

  .mat-button-toggle-label-content (the visible text):
    font:           500 14px / 32px Roboto
    letter-spacing: 0.42px (i.e. 0.03em ≈ 0.42 at 14px font)
    padding:        0px 12px
    color (inactive):  rgb(158, 167, 177)             ← ink-2
    color (active):    rgb(255, 255, 255)             ← white
    text-transform:    none (sentence case "Developers" — NOT uppercase)

  inner button (.mat-button-toggle-button):
    transition:     padding 0.15s cubic-bezier(0.4, 0, 0.2, 1) 0.045s
    border-radius:  0px
    background:     transparent

DIFF VS sboxmarket — ApiKeysSection (modals.js:8957) renders a
.profile-panel that is ALWAYS displayed without a tab strip wrapping
it. The earlier ships #126720-#126726 styled the inner content of the
panel (api-key-new banner, db-table rows, scope chips, revoke + copy
buttons, generate-new CTA) but never added the OUTER tab-strip chrome
that csfloat surfaces above the API key list — a horizontal Material
button-toggle group whose 8 cells switch between Personal Info,
Transactions, Buy Orders, Auto-Bids, Trades, Offers, Notifications,
Developers. sboxmarket's profile flow does not yet render an
equivalent strip (each tab is opened as its own modal), so the strip
ship targets the .info-modal-tabstrip + .info-modal-tab pattern that
modals.js uses for sister tab-switcher chrome (e.g. the listing-type
strip on the SellModal / Trade History strip on the Trades modal),
pinning that primitive to the same Material toggle-group geometry the
real csfloat /profile uses. This way the moment the API keys panel is
re-mounted in a multi-tab modal (planned), the strip already matches.

Additional refinements anchor on the inner panel chrome we already
ship to fix three remaining gaps:

  - api-key-new banner heading "COPY THIS NOW…" is currently 11px
    yellow var(--yellow). csfloat's fresh-secret callout heading is
    12/600/0.32em-tracking white (uppercase eyebrow). #127862.

  - api-key-new wrapper margin-bottom is 14px; csfloat's secret
    callout uses 20px below it before the keys table (more rhythm).
    #127863.

  - profile-panel intro helper text (api info + "How to use the API →"
    link) currently uses a flat var(--accent-dim) bg. csfloat's
    equivalent helper uses a 1px hairline-only outline (no fill) at
    rgba(255,255,255,0.06) with 12px ink-2 text. #127864.

  - db-table thead currently shows uppercase 11px. csfloat's
    /profile/* tables use 12px sentence-case Roboto 500 with 0.5px
    tracking (matches the toggle label tracking for visual rhythm).
    #127865.

  - "Revoke all active keys" row currently has no top divider —
    csfloat separates dangerous bulk actions from the table by a
    1px hairline above the row. #127866.

All overrides scope to .profile-panel + .info-modal contexts so they
do not leak to other surfaces. !important on every cascade entry per
the lane brief. APPEND-only.
"""

import os
import time

DESIGN_CSS = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"

PAYLOAD = r"""

/* =====================================================================
   CSFLOAT-1:1 PARITY ships #127860-#127866 — REAL /profile Developers
   tab measurements (mcp__playwright__browser_navigate https://csfloat.com/profile,
   Chib skinbox.market signed-in session, viewport 1440x900).

   The Developers tab is the rightmost cell in csfloat's profile-page
   horizontal mat-button-toggle-group (8 cells: Personal Info,
   Transactions, Buy Orders, Auto-Bids, Trades, Offers, Notifications,
   Developers). Switching to it replaces the sub-component below the
   strip in-place — no URL change.

   Measured tokens (live getComputedStyle):

     STRIP:    1236w x 38h, bg rgba(193,206,255,0.04), border 0,
               border-radius 7px, display flex, padding 0
     CELL:     ~167w x 32h, margin 3px, bg transparent (inactive)
               or rgba(193,206,255,0.04) (active .mat-button-toggle-checked),
               border-radius 4px
     LABEL:    500 14/32 Roboto, letter-spacing 0.42px,
               padding 0 12px, color ink-2 (inactive) / white (active),
               text-transform none (sentence case)
     INNER:    transition padding 150ms cubic-bezier(0.4,0,0.2,1) .045s

   sboxmarket's profile surfaces are rendered as separate modals (no
   shared tab strip yet), so the strip ship targets the sister tab-
   switcher primitive used in SellModal / Trades modal so the moment
   the API keys panel is re-mounted in a tabbed profile modal, the
   strip already matches.

   The remaining 5 ships address inner panel gaps the earlier
   #126720-#126726 round did not cover:

     #127862 — api-key-new "COPY THIS NOW…" eyebrow heading
                (11/yellow → 12/600/0.32em white uppercase eyebrow)
     #127863 — api-key-new wrapper margin-bottom 14 → 20 (rhythm)
     #127864 — .profile-panel intro helper bg → outline-only hairline
     #127865 — .profile-panel .db-table thead density (11/upper →
                12/500/0.5px sentence-case Roboto)
     #127866 — bulk Revoke-all row 1px hairline divider above

   ===================================================================== */

/* csfloat-1:1 ship #127860 — settings api-key tab-strip wrapper: 38px h + 7px radius + panel-tint bg + flex (csfloat /profile mat-button-toggle-group canonical density for intra-page profile tabs Personal Info/Transactions/Buy Orders/Auto-Bids/Trades/Offers/Notifications/Developers) */
html body .info-modal .info-modal-tabstrip,
html body .info-modal-body .info-modal-tabstrip {
  display: flex !important;
  align-items: stretch !important;
  flex-wrap: wrap !important;
  gap: 0 !important;
  height: 38px !important;
  min-height: 38px !important;
  margin: 0 0 16px !important;
  padding: 0 !important;
  background: rgba(193, 206, 255, 0.04) !important;
  border: 0 !important;
  border-radius: 7px !important;
  color: rgb(255, 255, 255) !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  transition: background-color 180ms cubic-bezier(0.4, 0, 0.2, 1) !important;
}
/* END CSFLOAT-1:1 PARITY ship #127860 */

/* csfloat-1:1 ship #127861 — settings api-key tab-strip cell: 32px h + 3px margin + 4px radius + ink-2 inactive label + white active (csfloat /profile mat-button-toggle canonical inactive/checked geometry) */
html body .info-modal .info-modal-tabstrip > .info-modal-tab,
html body .info-modal .info-modal-tabstrip > button,
html body .info-modal .info-modal-tabstrip > [role="tab"],
html body .info-modal-body .info-modal-tabstrip > .info-modal-tab,
html body .info-modal-body .info-modal-tabstrip > button {
  display: inline-flex !important;
  align-items: center !important;
  justify-content: center !important;
  height: 32px !important;
  min-height: 32px !important;
  margin: 3px !important;
  padding: 0 12px !important;
  background: transparent !important;
  border: 0 !important;
  border-radius: 4px !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 14px !important;
  font-weight: 500 !important;
  line-height: 32px !important;
  letter-spacing: 0.42px !important;
  color: rgb(158, 167, 177) !important;
  text-transform: none !important;
  cursor: pointer !important;
  outline: none !important;
  transition: background-color 150ms cubic-bezier(0.4, 0, 0.2, 1), color 150ms cubic-bezier(0.4, 0, 0.2, 1) !important;
}
html body .info-modal .info-modal-tabstrip > .info-modal-tab:hover,
html body .info-modal .info-modal-tabstrip > button:hover,
html body .info-modal-body .info-modal-tabstrip > .info-modal-tab:hover,
html body .info-modal-body .info-modal-tabstrip > button:hover {
  background: rgba(255, 255, 255, 0.04) !important;
  color: rgb(220, 226, 233) !important;
}
html body .info-modal .info-modal-tabstrip > .info-modal-tab.active,
html body .info-modal .info-modal-tabstrip > .info-modal-tab[aria-selected="true"],
html body .info-modal .info-modal-tabstrip > button.active,
html body .info-modal .info-modal-tabstrip > button[aria-selected="true"],
html body .info-modal-body .info-modal-tabstrip > .info-modal-tab.active,
html body .info-modal-body .info-modal-tabstrip > .info-modal-tab[aria-selected="true"],
html body .info-modal-body .info-modal-tabstrip > button.active,
html body .info-modal-body .info-modal-tabstrip > button[aria-selected="true"] {
  background: rgba(193, 206, 255, 0.04) !important;
  color: rgb(255, 255, 255) !important;
}
/* END CSFLOAT-1:1 PARITY ship #127861 */

/* csfloat-1:1 ship #127862 — settings api-key-new "COPY THIS NOW…" eyebrow heading: 12/600 white uppercase + 0.32em tracking (csfloat fresh-secret callout-heading canonical, replaces 11/yellow flat label that conflicts with the brand-tint left border below it) */
html body .profile-panel .api-key-new > div:first-child {
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 12px !important;
  font-weight: 600 !important;
  line-height: 16px !important;
  letter-spacing: 0.32em !important;
  color: rgb(255, 255, 255) !important;
  text-transform: uppercase !important;
  margin-bottom: 10px !important;
  opacity: 0.92 !important;
}
/* END CSFLOAT-1:1 PARITY ship #127862 */

/* csfloat-1:1 ship #127863 — settings api-key-new wrapper margin-bottom 14 → 20 (csfloat fresh-secret callout vertical-rhythm canonical — extra space before the keys table so the new key reveal does not crowd the row list below) */
html body .profile-panel .api-key-new {
  margin-bottom: 20px !important;
  padding: 16px !important;
}
/* END CSFLOAT-1:1 PARITY ship #127863 */

/* csfloat-1:1 ship #127864 — settings api-key intro helper: outline-only hairline + 12px ink-2 + .info-modal panel-tint surface (csfloat /profile help-callout canonical chrome — replaces flat var(--accent-dim) brand-fill that pulls focus from the keys table) */
html body .profile-panel > div:first-child[style*="accent-dim"],
html body .profile-panel > div[style*="background: var(--accent-dim)"],
html body .profile-panel > div[style*="background:var(--accent-dim)"] {
  background: rgb(27, 29, 36) !important;
  border: 1px solid rgba(255, 255, 255, 0.06) !important;
  border-radius: 8px !important;
  padding: 12px 14px !important;
  margin-bottom: 16px !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 12px !important;
  line-height: 1.5 !important;
  color: rgb(158, 167, 177) !important;
  letter-spacing: 0.1px !important;
}
html body .profile-panel > div:first-child[style*="accent-dim"] a,
html body .profile-panel > div[style*="background: var(--accent-dim)"] a,
html body .profile-panel > div[style*="background:var(--accent-dim)"] a {
  color: rgb(96, 158, 255) !important;
  text-decoration: none !important;
  font-weight: 500 !important;
  transition: color 150ms cubic-bezier(0.4, 0, 0.2, 1) !important;
}
html body .profile-panel > div:first-child[style*="accent-dim"] a:hover,
html body .profile-panel > div[style*="background: var(--accent-dim)"] a:hover,
html body .profile-panel > div[style*="background:var(--accent-dim)"] a:hover {
  color: rgb(35, 123, 255) !important;
  text-decoration: underline !important;
}
/* END CSFLOAT-1:1 PARITY ship #127864 */

/* csfloat-1:1 ship #127865 — settings api-key db-table thead: 12/500 sentence-case + 0.5px tracking + ink-2 (csfloat /profile table-head canonical density — matches the 0.42px toggle-label tracking for vertical rhythm, replaces uppercase 11px) */
html body .profile-panel .db-table thead tr th {
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 12px !important;
  font-weight: 500 !important;
  line-height: 16px !important;
  letter-spacing: 0.5px !important;
  color: rgb(158, 167, 177) !important;
  text-transform: none !important;
  padding: 10px 14px !important;
  background: transparent !important;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06) !important;
  text-align: left !important;
}
html body .profile-panel .db-table thead tr th.right {
  text-align: right !important;
}
/* END CSFLOAT-1:1 PARITY ship #127865 */

/* csfloat-1:1 ship #127866 — settings api-key bulk Revoke-all row: 1px hairline divider above + 10px top padding (csfloat /profile danger-action separator canonical — visually segregates the destructive bulk action from the table that owns the per-row revokes) */
html body .profile-panel > div > div[style*="justify-content: flex-end"]:has(> button.btn-ghost[title*="Security panic"]),
html body .profile-panel > div > div[style*="justifyContent: flex-end"]:has(> button.btn-ghost[title*="Security panic"]),
html body .profile-panel > div > div[style*="justify-content: flex-end"]:has(> button.btn-ghost[title*="panic"]) {
  border-top: 1px solid rgba(255, 255, 255, 0.06) !important;
  padding-top: 12px !important;
  margin-top: 8px !important;
  margin-bottom: 14px !important;
}
/* Fallback when :has() is not honored — still pin the spacing geometry so the row reads as a separate cluster from the table below it */
html body .profile-panel .btn-ghost[title*="Security panic"] {
  height: 30px !important;
  padding: 0 14px !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 11px !important;
  font-weight: 600 !important;
  letter-spacing: 0.3px !important;
  line-height: 28px !important;
  border-radius: 6px !important;
  color: rgb(235, 75, 76) !important;
  background: transparent !important;
  border: 1px solid rgba(235, 75, 76, 0.32) !important;
  transition: background-color 150ms cubic-bezier(0.4, 0, 0.2, 1), border-color 150ms cubic-bezier(0.4, 0, 0.2, 1), color 150ms cubic-bezier(0.4, 0, 0.2, 1) !important;
  cursor: pointer !important;
}
html body .profile-panel .btn-ghost[title*="Security panic"]:hover {
  background: rgba(235, 75, 76, 0.10) !important;
  border-color: rgba(235, 75, 76, 0.55) !important;
  color: rgb(243, 105, 106) !important;
}
/* END CSFLOAT-1:1 PARITY ship #127866 */

/* END CSFLOAT-1:1 PARITY ships #127860-#127866 */
"""

def main():
    # Atomic append: write to a temp tail then concatenate via os.rename
    if not os.path.exists(DESIGN_CSS):
        raise SystemExit(f"design.css not found at {DESIGN_CSS}")

    # Read final char to ensure newline boundary
    with open(DESIGN_CSS, "rb") as f:
        f.seek(-1, os.SEEK_END)
        last = f.read(1)
    prefix = b"" if last in (b"\n",) else b"\n"

    # Atomic append using O_APPEND
    fd = os.open(DESIGN_CSS, os.O_WRONLY | os.O_APPEND)
    try:
        os.write(fd, prefix + PAYLOAD.encode("utf-8"))
        os.fsync(fd)
    finally:
        os.close(fd)

    print(f"[ship 127860-127866] Appended {len(PAYLOAD)} bytes to design.css")
    sz = os.path.getsize(DESIGN_CSS)
    print(f"[ship 127860-127866] design.css size now: {sz} bytes")

if __name__ == "__main__":
    main()
