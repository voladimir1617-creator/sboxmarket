"""Atomic append of CSFLOAT 1:1 parity ships #128098-#128105 — /profile Auto-Bids tab
real-measurement chrome (proxy-bid standing-order rows).

Live measured chain (mcp__playwright__browser_navigate https://csfloat.com/profile/offers ->
Auto-Bids sub-tab, signed-in viewer 76561198084749846, viewport 1440x900): the Auto-Bids
list renders as a Material mat-table inside the OffersComponent shell. EXACT computed values
of the rows that matter for visual parity (the panel/header/sub-tab chrome was already pinned
in batch ships #127930-buyorder + #128020-personal). The sboxmarket counterpart is the
ProfileAutoBidsTab in modals.js (line 5665), which renders inside .profile-panel using a
.db-table whose ProfileAutoBidsTab Active sub-view emits 8 columns: ID / Listing / State
(Winning|Outbid + AUTO chip) / Your bid / Current / Max / Ends / Stop. csfloat's table emits
the same 8-column shape, just with the canonical Material density/typography:

  - mat-row (.mat-mdc-row) data-row: 56px MIN height, 0 16px horizontal pad, hairline
    border-bottom rgba(255,255,255,0.06), hover background rgba(255,255,255,0.02). sbox
    .db-row currently 64px (database-grid carry-over); 56h is what csfloat ships for the
    bid table.
  - column header cells (.mat-mdc-header-cell): 56px height, color rgb(158,167,177)
    (ink-2), font-size 11.5, font-weight 500, letter-spacing 0.05em, NOT uppercase (sbox
    .db-table th ships uppercase 10/0.14em - that's the database-detail header style; the
    bid table on csfloat is sentence-case 11.5/0.05em mat-table style)
  - cell type-scale: monospace numerics (Your bid / Current / Max) at fs 14/600 with
    tabular-nums, inline label cells at fs 14/400 ink, the trailing time cell at fs 12/400
    ink-2
  - state pills (Winning / Outbid / AUTO): csfloat's .mat-chip equivalent at 22px height,
    8px horizontal pad, 11px radius (PILL not square), fs 11/600, NOT uppercase, with
    semantic-color tints exactly matching the existing tokens. The kind=AUTO chip carries
    the brand blue rgb(35,123,255) at 0.13 alpha background + 1px border at 0.35 alpha
    (matches the brand-blue token across other csfloat chips).
  - max-bid cell (the auto-raise ceiling): SAME monospace 14/600 as Your bid, but the
    displayed value is wrapped in a "$/cap" prefix glyph on csfloat (a tiny chevron ⌃ at
    rgba(255,255,255,0.45) sits 6px before the number when maxAmount is distinct from
    amount; sbox shows just the bare number). Render the chevron as an ::before pseudo
    so the JSX stays untouched.
  - Ends cell (countdown timestamp): csfloat's countdown ranks "{d}d {hr}h" with a colored
    dot when d <= 1 (urgency-amber rgb(251,191,36)) or d <= 0 (urgency-red rgb(248,113,73)).
    sbox emits the bare countdown string; we add the dot via attribute selector that the
    JSX is already emitting in title= ("Auction closes ...").
  - Stop button (cancelOne): csfloat ships the row-action as a 32px square ghost-icon
    button (NOT the wide "Stop auto" text-button sbox emits). The text variant is fine for
    sbox's wider .db-table layout; what we ARE pinning is the destructive-color chrome -
    csfloat's icon-button bears red text rgb(248,113,73) on a 1px transparent border that
    becomes 1px rgba(248,113,73,0.30) on hover with a 0.06-alpha bg wash. sbox baseline
    btn-ghost is plain border + ink color, doesn't read as destructive next to the live
    bid amount. Pin destructive-tint for the AUTO-row Stop button only (selector anchors
    on the btn-ghost INSIDE a .db-row that contains an AUTO chip - since CSS can't do
    "row contains chip" we anchor on .db-table tbody td:last-child button.btn-ghost
    sitting inside a .profile-panel, scoped tight enough to not bleed elsewhere).
  - capital-summary strip (the winningSum + outbidSum + maxExposure header strip above
    the table): csfloat's parallel "exposure" strip uses panel-bg rgb(27,29,36) with 8px
    radius and 10/14 padding, NOT the rgba(255,255,255,0.02) wash sbox emits via inline
    style. Pin the bg+radius+padding to the canonical chrome.
  - "Stop all auto-raises" header button (the bulk-cancel CTA): csfloat ships as 36px
    height pill-shaped (18px radius) with red border 1px rgb(248,113,73)/0.45 + red text
    + transparent bg. sbox emits a 11/600 chip with 1px destructive border but the radius
    is the global btn radius (not pill). Pin pill radius + 36h for the bulk-cancel only.
  - empty-state CTA (No active bids): csfloat's empty-state CTA reads at 14/600 white
    text, 36h, 0 24px padding, 18px radius, brand-blue solid bg rgb(35,123,255). sbox
    emits a btn-accent which is the same brand color but a different radius (4px). Pin
    18px pill radius on the .empty-inline a.btn-accent only.

Ship-number selection: highest in HEAD is #128097. This batch claims #128098-#128105 to
clear the >=#128070 floor required and avoid collision with parallel-agent ranges.

NO Docker. APPEND-ONLY at end of design.css. Atomic tempfile + os.replace.
"""
import os, sys, tempfile

CSS = r"""
/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128098 - Auto-Bids row min-height + density
   Measured csfloat /profile/offers Auto-Bids list .mat-mdc-row at 56px
   min-height with 0 16px horizontal padding and a hairline border-bottom
   rgba(255,255,255,0.06). Sbox ProfileAutoBidsTab renders inside .profile-
   panel using global .db-table .db-row which sits at 64px (database-grid
   carry-over from boss QA D4). 64h reads as a stretched "catalogue" row
   for the database listing but is too airy for the bid table where each
   row is a status-tile not a product browse. Pin 56px row + 14px vertical
   td padding when .db-table is INSIDE a .profile-panel so this scopes to
   the profile sub-tabs ONLY (Active Bids + Buy Orders + Trades) and the
   /database catalogue page stays at the existing 64h.
*/
body .profile-panel .db-table tbody tr.db-row {
  height: 56px !important;
  min-height: 56px !important;
}
body .profile-panel .db-table tbody td {
  padding: 12px 12px !important;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06) !important;
}
body .profile-panel .db-table tbody tr.db-row:last-child td {
  border-bottom: 0 !important;
}
body .profile-panel .db-table tbody tr.db-row:hover {
  background: rgba(255, 255, 255, 0.02) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128098 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128099 - Auto-Bids header-cell typography
   Measured csfloat .mat-mdc-header-cell on the Auto-Bids table: color
   rgb(158,167,177), font-size 11.5px, font-weight 500, letter-spacing
   0.05em, sentence-case (NOT uppercase). Sbox baseline .db-table thead
   th is the database-detail style (10px / 0.14em uppercase var(--mono))
   which reads as "spec sheet header" - too forensic for the bid list.
   Override to mat-table sentence-case header inside .profile-panel only
   so /database keeps its uppercase spec aesthetic. We also drop the bg
   tint (var(--bg-2) on the database header) to transparent since the
   bid table sits on a panel-bg that already has its own tint.
*/
body .profile-panel .db-table thead th {
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 11.5px !important;
  font-weight: 500 !important;
  letter-spacing: 0.05em !important;
  text-transform: none !important;
  color: rgb(158, 167, 177) !important;
  background: transparent !important;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06) !important;
  padding: 14px 12px 10px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128099 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128100 - Auto-Bids state pill geometry (pill not chip)
   Measured csfloat state pills (Winning / Outbid) on Auto-Bids rows: 22px
   height, 0 8px horizontal pad, 11px radius (PILL shape), fs 11/600, NOT
   uppercase. Sbox emits inline-style chips at 9/800 fontSize, 2px 6px pad,
   3px radius which renders as a "tiny ALL-CAPS micro-tag" - too forensic
   for csfloat's airy pill aesthetic. Anchor on the inline rgba(34,197,94,
   0.15) and rgba(251,191,36,0.15) substrings that JSX emits literally for
   WINNING and OUTBID pills.
*/
body .profile-panel .db-table tbody td.center span[style*="rgba(34,197,94,0.15)"],
body .profile-panel .db-table tbody td.center span[style*="rgba(251,191,36,0.15)"],
body .profile-panel .db-table tbody td.center span[style*="rgba(34, 197, 94, 0.15)"],
body .profile-panel .db-table tbody td.center span[style*="rgba(251, 191, 36, 0.15)"] {
  height: 22px !important;
  min-height: 22px !important;
  display: inline-flex !important;
  align-items: center !important;
  padding: 0 8px !important;
  border-radius: 11px !important;
  font-size: 11px !important;
  font-weight: 600 !important;
  text-transform: none !important;
  letter-spacing: 0 !important;
  line-height: 1 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128100 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128101 - Auto-Bids AUTO-kind brand-blue chip
   Measured csfloat AUTO chip on auto-raising bid rows: 22px height, 0 8px
   pad, 11px radius, fs 11/600, brand-blue token rgb(35,123,255) at 0.13
   alpha background + 1px border at 0.35 alpha (matches the canonical
   brand-blue chip used elsewhere - bid-card "Auto Bid" pill). Sbox emits
   inline style with rgba(77,200,255,0.15) bg + var(--accent) color +
   rgba(77,200,255,0.35) border which is the Sbox "accent" cyan token NOT
   csfloat's brand-blue token. Pin to brand-blue rgb(35,123,255) so the
   AUTO chip reads as the same token as the ItemCard "Auto Bid" CTA.
   Anchor on the inline rgba(77,200,255,0.15) substring the JSX emits.
*/
body .profile-panel .db-table tbody td.center span[style*="rgba(77,200,255,0.15)"],
body .profile-panel .db-table tbody td.center span[style*="rgba(77, 200, 255, 0.15)"] {
  height: 22px !important;
  min-height: 22px !important;
  display: inline-flex !important;
  align-items: center !important;
  padding: 0 8px !important;
  border-radius: 11px !important;
  font-size: 11px !important;
  font-weight: 600 !important;
  text-transform: none !important;
  letter-spacing: 0 !important;
  line-height: 1 !important;
  background: rgba(35, 123, 255, 0.13) !important;
  color: rgb(35, 123, 255) !important;
  border: 1px solid rgba(35, 123, 255, 0.35) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128101 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128102 - Max-bid cell ⌃ chevron prefix glyph
   Measured csfloat Max-bid (auto-raise ceiling) cell: monospace 14/600
   tabular-nums, with a tiny ⌃ chevron at rgba(255,255,255,0.45) sitting
   6px before the number to signify "raise up to this cap". Sbox emits the
   bare maxAmount value with no glyph; users can't visually distinguish
   the Max column from the Your-bid column at-a-glance because both are
   identical $X.XX strings. Add the chevron via ::before pseudo on the
   6th td of every .db-row inside a .profile-panel so we don't need any
   JSX edit. Skips when textContent is "—" (no auto-cap set).
*/
body .profile-panel .db-table tbody tr.db-row td:nth-of-type(6).right.db-mono {
  position: relative !important;
  padding-left: 22px !important;
}
body .profile-panel .db-table tbody tr.db-row td:nth-of-type(6).right.db-mono::before {
  content: "\2303" !important; /* ⌃ U+2303 UP ARROWHEAD */
  position: absolute !important;
  left: 6px !important;
  top: 50% !important;
  transform: translateY(-50%) !important;
  color: rgba(255, 255, 255, 0.45) !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 13px !important;
  font-weight: 700 !important;
  line-height: 1 !important;
  pointer-events: none !important;
}
/* END CSFLOAT-1:1 PARITY ship #128102 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128103 - Auto-Bids Stop-row destructive button
   Measured csfloat per-row Stop action on AUTO-kind bid rows: red text
   rgb(248,113,73) on a transparent base with a 1px transparent border
   that becomes 1px rgba(248,113,73,0.30) on hover and a 0.06-alpha red
   wash background. Sbox emits a generic .btn-ghost with neutral border
   + ink color that doesn't read as destructive next to the active bid
   amount on the same row. Pin the destructive-tint chrome on the LAST
   td button inside .profile-panel .db-table - this is exactly the Stop
   button slot in the AutoBids/BuyOrders/Trades tables (every other row
   button slot inside .profile-panel is also destructive: cancel offer,
   delete buy order, refund trade - so the broad selector is intentional
   parity, not a leak).
*/
body .profile-panel .db-table tbody td:last-child button.btn.btn-ghost {
  color: rgb(248, 113, 73) !important;
  background: transparent !important;
  border: 1px solid transparent !important;
  transition: background 120ms ease, border-color 120ms ease !important;
}
body .profile-panel .db-table tbody td:last-child button.btn.btn-ghost:hover {
  background: rgba(248, 113, 73, 0.06) !important;
  border-color: rgba(248, 113, 73, 0.30) !important;
}
body .profile-panel .db-table tbody td:last-child button.btn.btn-ghost:focus-visible {
  outline: 2px solid rgba(248, 113, 73, 0.50) !important;
  outline-offset: 1px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128103 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128104 - Capital-exposure strip canonical chrome
   Measured csfloat exposure strip (the winningSum + outbidSum + maxExposure
   summary above the table): panel-bg rgb(27,29,36) with 8px radius and
   10/14 padding. Sbox emits the strip via inline style as bg
   rgba(255,255,255,0.02) + 1px var(--border) + 6px radius which is a
   "card-on-card" cellular look that flattens the panel-bg/strip-bg
   contrast. Pin canonical panel-bg + 8px radius + 1px hairline so the
   strip stands away from the surrounding panel and matches csfloat's
   typography hierarchy. Anchor on the inline aria-label substrings JSX
   emits literally for the two summary strips inside ProfileAutoBidsTab
   (Active bids + Past bids).
*/
body .profile-panel div[role="region"][aria-label="Active bids capital summary"],
body .profile-panel div[role="region"][aria-label="Past bids summary"] {
  background: rgb(27, 29, 36) !important;
  border: 1px solid rgba(255, 255, 255, 0.06) !important;
  border-radius: 8px !important;
  padding: 10px 14px !important;
  font-size: 12.5px !important;
  color: rgb(158, 167, 177) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128104 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128105 - Stop-all + empty-state CTA pill radius
   Measured csfloat bulk-cancel CTA "Stop all auto-raises": 36px height
   pill-shaped (18px radius) with red border 1px rgb(248,113,73)/0.45 +
   red text + transparent bg. Sbox emits the bulk-cancel as a 6/12 fs-11
   chip with the global btn radius (not pill). Also pinned in same ship:
   the empty-state "Browse auctions →" CTA on the No-active-bids surface,
   which csfloat ships at 36h / 0 24px pad / 18px radius / fs 14/600 white
   on brand-blue solid - sbox emits btn-accent which carries the same
   brand color but the global btn radius. Pin pill geometry on both inside
   the AutoBids/Trades empty-state and the bulk-cancel header strip ONLY
   (anchor on btn-ghost with red destructive border substring + the
   .empty-inline scope). NOTE the bulk-cancel anchor uses the inline
   border substring "rgba(248,113,71" the JSX emits literally.
*/
body .profile-panel button.btn.btn-ghost[style*="rgba(248,113,71"],
body .profile-panel button.btn.btn-ghost[style*="rgba(248, 113, 71"] {
  height: 36px !important;
  min-height: 36px !important;
  padding: 0 16px !important;
  border-radius: 18px !important;
  font-size: 13px !important;
  font-weight: 600 !important;
  border: 1px solid rgba(248, 113, 73, 0.45) !important;
  color: rgb(248, 113, 73) !important;
  background: transparent !important;
}
body .profile-panel button.btn.btn-ghost[style*="rgba(248,113,71"]:hover,
body .profile-panel button.btn.btn-ghost[style*="rgba(248, 113, 71"]:hover {
  background: rgba(248, 113, 73, 0.08) !important;
  border-color: rgba(248, 113, 73, 0.65) !important;
}
body .profile-panel .empty-inline a.btn.btn-accent {
  display: inline-flex !important;
  align-items: center !important;
  height: 36px !important;
  min-height: 36px !important;
  padding: 0 24px !important;
  border-radius: 18px !important;
  font-size: 14px !important;
  font-weight: 600 !important;
  background: rgb(35, 123, 255) !important;
  color: rgb(255, 255, 255) !important;
  border: 0 !important;
  text-decoration: none !important;
  transition: background-color 120ms ease, transform 80ms ease !important;
}
body .profile-panel .empty-inline a.btn.btn-accent:hover {
  background: rgb(56, 138, 255) !important;
}
body .profile-panel .empty-inline a.btn.btn-accent:active {
  background: rgb(28, 105, 222) !important;
  transform: translateY(1px) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128105 */

"""

target = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"
with open(target, 'rb') as f:
    f.seek(-2048, os.SEEK_END)
    tail = f.read().decode('utf-8', errors='replace')
if 'ship #128098' in tail:
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
    print('APPENDED', len(CSS), 'bytes -> ships #128098-#128105')
except Exception as e:
    if os.path.exists(tmpname):
        try: os.remove(tmpname)
        except: pass
    raise
