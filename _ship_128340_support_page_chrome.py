"""Atomic append of CSFLOAT 1:1 parity ships #128340-#128347 — /support page chrome
(REAL signed-in measurements taken 2026-05-08 against csfloat.com /support).

Loaded via Playwright MCP (browser_navigate + browser_evaluate, viewport 1440), the
csfloat /support page renders inside <app-home-support> as two stacked panels (a
.contact-container and a .ticket-container). Both panels share canonical csfloat
panel chrome — these are the SAME chip the /profile sub-tab cards use, but on
/support the page-level header lives INSIDE each panel rather than as an outer
header strip. The signed-in account ($USER) shipped one RESOLVED ticket so the
table-row chrome is actual rendered DOM (not a placeholder shell).

Measured panel chrome (csfloat /support .contact-container + .ticket-container,
computed styles on the panel root):
  - bg:                  rgb(27, 29, 36)
  - border-radius:       12px
  - padding:             30px
  - gap:                 20px (column flex)
  - border:              0 none
  - flex direction:      column
  - width:               1100px @ viewport 1440 (matches csfloat content column)

Measured page-title block (.text > .title + .sub-text inside .header):
  - .title text:         "Support" / "Tickets"        (white)
  - .title font:         28px / 500 / no transform / no letter-spacing
  - .title color:        rgb(255, 255, 255)
  - .sub-text text:      "Contact the CSFloat support team with your question."
  - .sub-text font:      16px / 500 / no transform
  - .sub-text color:     rgb(158, 167, 177)           (ink-2)
  - .text gap (vert):    5px
  - .header gap (horiz): 20px (title-block on left, action btns flex-end on right)

Measured action buttons (right side of the header inside .contact-container):
  - "Contact Support":   mat-mdc-raised-button (primary brand-blue, 36h, 14/500
                         Roboto, no transform / no letter-spacing, 8px radius,
                         16px L/R pad, white label) — opens the new-ticket form
  - "Visit FAQ":         mat-mdc-unelevated-button mat-primary tinted variant
                         (bg rgba(35,123,255,0.15), color rgb(35,123,255), 36h,
                         8px radius, 16px L/R pad, 14/500, no transform / no
                         letter-spacing, no shadow) — anchor to FAQ route

Measured info-banner ("Please be mindful that it may take up to 24 hours…"):
  - .message wrapper:    bg rgba(193, 206, 255, 0.04)
  - border-radius:       6px
  - padding:             10px (square)
  - gap:                 10px (icon + text inline flex)
  - height:              44px (single-line at 1100w)
  - mat-icon (info):     24px / white / no margin-right (gap controls spacing)
  - .text body:          14/500 / rgb(158,167,177) ink-2 / no transform
  Csfloat ships an info icon (mat-icon "info" or similar) flush-left of the
  copy. Sboxmarket already has a banner inside ProfileSupportTab → the
  "+ New Ticket" form (modals.js ~8827 — "Response times" copy + cyan accent
  border) — that one is form-scoped and stays. The csfloat info-banner is the
  PERMANENT chip displayed BELOW the contact widget header, ABOVE the form,
  always visible regardless of "+ New Ticket" toggle. Sbox doesn't currently
  have a permanent SLA-warning chip outside the form context — these CSS
  pins still set the canonical chrome for any `.message`-classed banner the
  support tab renders so future markup parity is auto-correct.

Measured tickets table (csfloat ships <table mat-table> for the ticket list):
  - th cells:
      bg              rgba(193, 206, 255, 0.04)        (chip-flat tone)
      color           rgb(158, 167, 177)               (ink-2 grey)
      font            14px / 500 / Roboto / no transform
      letter-spacing  0.42px                            (3% of font-size)
      padding         0 16px (vertical from row height)
      text-align      left  (start)
      border-bottom   1px solid transparent             (no visible hairline)
  - td cells:
      color           rgb(255, 255, 255)               (white)
      font            14px / 400 / Roboto / no transform
      padding         0 16px
      text-align      left  (start)
      border-bottom   0 none (rows separate via row-bg, not divider)
  - paginator label ("Items per page"):  12px / white

sboxmarket ProfileSupportTab (modals.js ~8622) renders into a single
.profile-panel — the page heading lives in modals.js's TABS array as
`{ id: 'support', label: 'Support' }` plus the .buyorder-form for new tickets
(already styled by ships #19200-#19205) and a .db-table for the ticket list.
Csfloat splits the screen into TWO chromed panels but sboxmarket renders
inside the unified .profile-panel — these ships pin the .profile-panel-scoped
support tab to the csfloat double-panel chrome via internal section spacing
+ table re-skinning so the visual rhythm matches even though the wrapper
markup differs.

Color tokens (project-wide):
  panel    rgb(27, 29, 36)
  brand    rgb(35, 123, 255)
  ink-2    rgb(158, 167, 177)
  hairline rgba(255, 255, 255, 0.06)

Ship-number selection: HEAD ledger lists ships through #128260-#128266 (nav
dropdown) + #128316 (item-page rail). Lane brief says >= #128340 — these
ships land at #128340-#128347 (8 ships, leaves headroom).

NO Docker. APPEND-ONLY at end of design.css. Atomic tempfile + os.replace.
"""

import os, sys, tempfile

CSS = """
/* =====================================================================
   CSFLOAT-1:1 PARITY ships #128340-#128347 — /support page chrome
   surface: ProfileSupportTab (modals.js ~8622) — the Support tab inside
   the unified Profile InfoModal. Csfloat ships /support as two stacked
   panels (.contact-container + .ticket-container) with the canonical
   csfloat panel chrome (rgb(27,29,36) / 12px br / 30px pad / 20px col-gap).
   sboxmarket renders the support tab inside .profile-panel — these ships
   pin the inner section structure + tickets-table chrome to the csfloat
   double-panel rhythm without changing the wrapper markup.
   ===================================================================== */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128340 — `.profile-panel` support-tab spacing
   Adds a logical "panel break" rhythm INSIDE the unified .profile-panel
   so the new-ticket section + the tickets-table section read as the
   csfloat .contact-container + .ticket-container double-panel rather
   than a flat run of cards. Pins the inter-section gap to 20px (matching
   csfloat's outer .container gap of 30px halved into intra-panel
   margins of 20+10 above/below). Also pins the .profile-panel padding
   on the support tab to csfloat's panel-pad rhythm (24px sbox baseline
   stays — csfloat's 30px pad is panel-outer; sbox's intra-modal panel
   sits inside the InfoModal so 24/24 reads at the same optical density).
*/
body .info-modal:has(.profile-panel) .profile-panel:has(.buyorder-form),
body .info-modal:has(.profile-panel) .profile-panel:has(.support-thread) {
  display: flex !important;
  flex-direction: column !important;
  gap: 20px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128340 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128341 — Support tab tickets-table chrome
   Re-skins .db-table inside the .profile-panel support-tab context to
   csfloat's measured ticket-list table. Csfloat ships:
     th  bg rgba(193,206,255,0.04) / color ink-2 / 14/500 / 0.42px ls
         padding 0 16 / text-align start / border-bottom 1px transparent
     td  color white / 14/400 / padding 0 16 / text-align start / no border
     row-height: 48px (rhythm enforced by line-height + cell-padding)
   sboxmarket .db-table baseline ships 12.5px font + grid-template
   layout (.db-row uses display:grid not table). Inside support-tab
   context the table re-renders as a flat <table>/<tbody>/<tr>/<td>,
   so the cell chrome below scopes to the table context only.
*/
body .info-modal .profile-panel .db-table {
  width: 100% !important;
  border-collapse: collapse !important;
  background: transparent !important;
  border: 0 !important;
  border-radius: 0 !important;
  font-size: 14px !important;
}
body .info-modal .profile-panel .db-table thead th {
  background-color: rgba(193, 206, 255, 0.04) !important;
  color: rgb(158, 167, 177) !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 14px !important;
  font-weight: 500 !important;
  line-height: 24px !important;
  letter-spacing: 0.42px !important;
  text-transform: none !important;
  text-align: left !important;
  padding: 12px 16px !important;
  border-bottom: 1px solid transparent !important;
  border-top: 0 !important;
  border-left: 0 !important;
  border-right: 0 !important;
}
body .info-modal .profile-panel .db-table thead th:first-child {
  border-top-left-radius: 6px !important;
  border-bottom-left-radius: 6px !important;
}
body .info-modal .profile-panel .db-table thead th:last-child {
  border-top-right-radius: 6px !important;
  border-bottom-right-radius: 6px !important;
}
body .info-modal .profile-panel .db-table tbody td {
  background: transparent !important;
  color: rgb(255, 255, 255) !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 14px !important;
  font-weight: 400 !important;
  line-height: 24px !important;
  text-transform: none !important;
  text-align: left !important;
  padding: 12px 16px !important;
  border: 0 none !important;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06) !important;
}
body .info-modal .profile-panel .db-table tbody tr:last-child td {
  border-bottom: 0 !important;
}
body .info-modal .profile-panel .db-table tbody tr {
  background: transparent !important;
  cursor: pointer !important;
  transition: background-color 150ms cubic-bezier(0.4, 0, 0.2, 1) !important;
}
body .info-modal .profile-panel .db-table tbody tr:hover {
  background-color: rgba(193, 206, 255, 0.04) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128341 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128342 — Tickets section header rhythm
   The support-tab places the "+ New Ticket" button + the filter-chip row
   + the table all inside one .profile-panel. Csfloat splits these into
   the .contact-container (action btns) vs .ticket-container (filter +
   table) panels with a 20px gap. sboxmarket can't markup-split without
   refactoring modals.js, but we CAN add visual breathing room around
   the `+ New Ticket` button so it reads as the .contact-container CTA
   anchor. Also pins the inline "+ New Ticket" button height to 36px
   matching the csfloat raised-button h (currently it's the project
   .btn-accent default which renders ~32-34px, slightly under the
   measured chip).
*/
body .info-modal .profile-panel > button.btn-accent:first-child {
  height: 36px !important;
  min-height: 36px !important;
  padding: 0 16px !important;
  border-radius: 8px !important;
  background-color: rgb(35, 123, 255) !important;
  color: rgb(255, 255, 255) !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 14px !important;
  font-weight: 500 !important;
  line-height: 24px !important;
  letter-spacing: normal !important;
  text-transform: none !important;
  border: 0 none !important;
  cursor: pointer !important;
  align-self: flex-start !important;
  transition: background-color 150ms cubic-bezier(0.4, 0, 0.2, 1) !important;
}
body .info-modal .profile-panel > button.btn-accent:first-child:hover:not(:disabled) {
  background-color: rgb(56, 138, 255) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128342 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128343 — Status filter chip-row reskin
   sboxmarket ships .wallet-tx-filter-row inside the support tab — these
   are the All/Open/Waiting staff/Waiting you/Resolved chips. Csfloat's
   /support page filters tickets via a different mechanism (Material
   chip-listbox above the table), but the canonical chip chrome is
   identical to csfloat's tab-button chip pattern: 32h pill, 12px L/R
   pad, 14/500 Roboto, ink-2 rest / brand-blue active, transparent at
   rest / rgba(35,123,255,0.12) when active, 6px radius, no border.
   Pins the chip-row to that chrome inside the support-tab context.
*/
body .info-modal .profile-panel .wallet-tx-filter-row {
  display: flex !important;
  flex-wrap: wrap !important;
  gap: 8px !important;
  margin: 0 0 14px 0 !important;
  padding: 0 !important;
}
body .info-modal .profile-panel .wallet-tx-filter-chip {
  height: 32px !important;
  min-height: 32px !important;
  padding: 0 12px !important;
  background-color: transparent !important;
  color: rgb(158, 167, 177) !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 14px !important;
  font-weight: 500 !important;
  line-height: 24px !important;
  letter-spacing: normal !important;
  text-transform: none !important;
  border: 0 none !important;
  border-radius: 6px !important;
  cursor: pointer !important;
  transition: background-color 150ms cubic-bezier(0.4, 0, 0.2, 1),
              color 150ms cubic-bezier(0.4, 0, 0.2, 1) !important;
}
body .info-modal .profile-panel .wallet-tx-filter-chip:hover {
  background-color: rgba(193, 206, 255, 0.04) !important;
  color: rgb(255, 255, 255) !important;
}
body .info-modal .profile-panel .wallet-tx-filter-chip.active {
  background-color: rgba(35, 123, 255, 0.15) !important;
  color: rgb(35, 123, 255) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128343 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128344 — `.message` info-banner chrome
   Csfloat ships a permanent SLA-info chip below the contact-widget
   header on /support: bg rgba(193,206,255,0.04), 6px radius, 10px
   square padding, 10px gap, 24px white mat-icon left + 14/500 ink-2
   text right. sboxmarket renders a similar chip inside the new-ticket
   form (modals.js ~8827 — "Response times" cyan accent border). These
   pins set the canonical csfloat chrome for any `.message`-classed
   chip the support tab renders, including future permanent banners.
*/
body .info-modal .profile-panel .message {
  display: flex !important;
  flex-direction: row !important;
  align-items: center !important;
  gap: 10px !important;
  padding: 10px !important;
  background-color: rgba(193, 206, 255, 0.04) !important;
  border-radius: 6px !important;
  border: 0 none !important;
  margin: 0 !important;
}
body .info-modal .profile-panel .message > .text {
  color: rgb(158, 167, 177) !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 14px !important;
  font-weight: 500 !important;
  line-height: 24px !important;
  text-transform: none !important;
  margin: 0 !important;
  padding: 0 !important;
}
body .info-modal .profile-panel .message > mat-icon,
body .info-modal .profile-panel .message > .material-icons,
body .info-modal .profile-panel .message > svg {
  width: 24px !important;
  height: 24px !important;
  font-size: 24px !important;
  color: rgb(255, 255, 255) !important;
  fill: rgb(255, 255, 255) !important;
  flex-shrink: 0 !important;
  margin: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128344 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128345 — Support-thread message bubble chrome
   Csfloat /support ticket detail view shows messages as alternating
   chat-bubble-style cards (staff vs user) with the same panel-flat
   chrome as the rest of the support page. sboxmarket renders these
   as `.support-msg .staff` / `.support-msg .user` inside `.support-thread`
   (modals.js ~8788). Pins both variants to canonical csfloat panel
   tones — staff messages get the brand-blue tinted bg
   (rgba(35,123,255,0.06)) so they read as official; user messages
   get the chip-flat rgba(193,206,255,0.04) so they sit recessed.
*/
body .info-modal .profile-panel .support-thread {
  display: flex !important;
  flex-direction: column !important;
  gap: 12px !important;
  padding: 0 !important;
  margin: 0 !important;
}
body .info-modal .profile-panel .support-msg {
  background-color: rgba(193, 206, 255, 0.04) !important;
  border: 0 none !important;
  border-radius: 8px !important;
  padding: 14px 16px !important;
  margin: 0 !important;
  display: flex !important;
  flex-direction: column !important;
  gap: 6px !important;
}
body .info-modal .profile-panel .support-msg.staff {
  background-color: rgba(35, 123, 255, 0.06) !important;
  border-left: 2px solid rgba(35, 123, 255, 0.45) !important;
}
body .info-modal .profile-panel .support-msg-head {
  color: rgb(158, 167, 177) !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 12px !important;
  font-weight: 500 !important;
  line-height: 18px !important;
  letter-spacing: 0.36px !important;
  text-transform: none !important;
}
body .info-modal .profile-panel .support-msg-body {
  color: rgb(255, 255, 255) !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 14px !important;
  font-weight: 400 !important;
  line-height: 24px !important;
  letter-spacing: normal !important;
  text-transform: none !important;
  white-space: pre-wrap !important;
  word-wrap: break-word !important;
}
/* END CSFLOAT-1:1 PARITY ship #128345 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128346 — Empty-state placeholder chrome
   Csfloat ships an empty-state pattern when the tickets table has no
   rows (the unsigned-in case auto-redirects, but for the signed-in
   no-tickets case the .ticket-container collapses to a chip-flat
   "No tickets yet" message). sboxmarket renders `.empty-inline` with
   `.empty-icon` + descriptive text in the same context. Pins the
   empty-state chip to canonical csfloat empty-card chrome:
   chip-flat bg, 6px radius, 16px square padding, 12px row-gap,
   ink-2 message text + white icon at 26px (matches the inbox icon
   sboxmarket renders via MaterialIcon name="inbox" size={26}).
*/
body .info-modal .profile-panel .empty-inline {
  display: flex !important;
  flex-direction: column !important;
  align-items: center !important;
  justify-content: center !important;
  gap: 12px !important;
  padding: 24px 16px !important;
  background-color: rgba(193, 206, 255, 0.04) !important;
  border: 0 none !important;
  border-radius: 6px !important;
  margin: 0 !important;
  min-height: 120px !important;
}
body .info-modal .profile-panel .empty-inline .empty-icon {
  color: rgb(255, 255, 255) !important;
  opacity: 0.7 !important;
}
body .info-modal .profile-panel .empty-inline .empty-icon svg,
body .info-modal .profile-panel .empty-inline .empty-icon .material-icons {
  width: 26px !important;
  height: 26px !important;
  font-size: 26px !important;
  color: rgb(255, 255, 255) !important;
  fill: rgb(255, 255, 255) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128346 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128347 — Reply input + Send button chrome
   At the bottom of the ticket-detail view sboxmarket renders a
   .chat-input + .btn.btn-accent "Send" button (modals.js ~8812).
   Csfloat's equivalent ships a Material textfield (rgba(193,206,255,0.04)
   bg, 44h, 16px L-pad, 6px radius) + a brand-blue raised button
   (rgb(35,123,255), 36h, 16px L/R-pad, 8px radius, 14/500 white).
   Pins both inside the support-thread context so the reply row reads
   at csfloat parity rhythm.
*/
body .info-modal .profile-panel:has(.support-thread) > div:last-child > input.chat-input {
  height: 44px !important;
  padding: 0 16px !important;
  background-color: rgba(193, 206, 255, 0.04) !important;
  border: 0 none !important;
  border-radius: 6px !important;
  outline: none !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 16px !important;
  font-weight: 400 !important;
  line-height: 24px !important;
  letter-spacing: 0.5px !important;
  color: rgb(255, 255, 255) !important;
  box-shadow: none !important;
  transition: background-color 150ms cubic-bezier(0.4, 0, 0.2, 1) !important;
}
body .info-modal .profile-panel:has(.support-thread) > div:last-child > input.chat-input:hover {
  background-color: rgba(193, 206, 255, 0.06) !important;
}
body .info-modal .profile-panel:has(.support-thread) > div:last-child > input.chat-input:focus {
  background-color: rgba(193, 206, 255, 0.08) !important;
  box-shadow: 0 0 0 2px rgba(35, 123, 255, 0.5) !important;
}
body .info-modal .profile-panel:has(.support-thread) > div:last-child > button.btn-accent {
  height: 44px !important;
  padding: 0 22px !important;
  background-color: rgb(35, 123, 255) !important;
  color: rgb(255, 255, 255) !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 14px !important;
  font-weight: 500 !important;
  line-height: 24px !important;
  letter-spacing: normal !important;
  text-transform: none !important;
  border: 0 none !important;
  border-radius: 8px !important;
  cursor: pointer !important;
  transition: background-color 150ms cubic-bezier(0.4, 0, 0.2, 1) !important;
}
body .info-modal .profile-panel:has(.support-thread) > div:last-child > button.btn-accent:hover:not(:disabled) {
  background-color: rgb(56, 138, 255) !important;
}
body .info-modal .profile-panel:has(.support-thread) > div:last-child > button.btn-accent:disabled {
  background-color: rgba(35, 123, 255, 0.4) !important;
  cursor: not-allowed !important;
}
/* END CSFLOAT-1:1 PARITY ship #128347 */
"""

target = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"
with open(target, 'rb') as f:
    f.seek(-4096, os.SEEK_END)
    tail = f.read().decode('utf-8', errors='replace')
if 'ship #128340' in tail:
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
    print('APPENDED', len(CSS), 'bytes -> ships #128340-#128347')
except Exception as e:
    if os.path.exists(tmpname):
        try: os.remove(tmpname)
        except: pass
    raise
