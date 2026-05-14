"""Atomic append of CSFLOAT 1:1 parity ships #128260-#128266 — nav dropdown menu chrome.

Live measured against csfloat.com (signed-in session, viewport 1440) 2026-05-08 via
mcp__playwright__browser_navigate + mcp__playwright__browser_evaluate. The csfloat
top-nav "Tools" item is an `<a class="mat-mdc-menu-trigger">Tools expand_more</a>`
that opens an Angular Material menu panel with class
`.mat-mdc-menu-panel.mat-menu-after.mat-menu-below.themed-menu`. Panel hosts 4
menu rows (App / Trade Up Calculator / Float Checker / Extension), no internal
mat-divider elements.

Measured panel chrome (computed styles on the open panel):
  - panelRect:         208w x 212h (4 rows x 48h + 8/8 inner padding)
  - background:        rgba(21, 23, 28, 0.8)
  - backdrop-filter:   blur(10px)        ← required for the translucent look
  - border:            2px solid rgba(193, 206, 255, 0.07)
  - border-radius:     15px
  - box-shadow:        rgba(0,0,0,0.2) 0 5px 5px -3px,
                       rgba(0,0,0,0.14) 0 8px 10px 1px,
                       rgba(0,0,0,0.12) 0 3px 14px 2px       (MD-3 menu elevation)
  - min-width:         112px
  - max-width:         280px
  - inner padding:     8px 0  (rows extend to edges, vertical gutter only)

Measured row chrome (.mat-mdc-menu-item):
  - h:                 48px
  - padding:           0 16px
  - font:              14px / 24px / 400, Roboto, "Helvetica Neue", sans-serif
  - color:             rgb(255, 255, 255)
  - icon (mat-icon):   24x24, color rgb(255, 255, 255), margin-right: 16px

NO internal divider strip (App / Trade Up Calculator / Float Checker / Extension
are flush) — sbox baseline ships .user-menu-divider as a 1px hr-line and the
parity surface should drop those between groups (csfloat groups Tools by panel
rather than by intra-panel divider).

sboxmarket .user-menu baseline (design.css lines 3203-3228):
  - bg var(--bg-1) opaque (oklch ≈ #2a2c33)        → wrong: needs translucent + blur
  - 1px solid var(--line-2)                         → wrong: needs 2px @ rgba(193,206,255,0.07)
  - var(--r-md) ≈ 10px                              → wrong: needs 15px
  - var(--shadow-2) (1-stop big drop)               → wrong: needs MD-3 3-stop elevation
  - padding 6px (gutter every side)                 → wrong: needs 8px 0 (no horiz gutter)
  - min-width 240px                                 → wrong: needs 112-280 range
  - .user-menu-item: 10/12 padding + 13/500 ink-2   → wrong: needs 48h + 0 16 + 14/24/400 white
  - .user-menu-item svg: 16x16 ink-3, gap 10        → wrong: needs 24x24 white, mr 16
  - .user-menu-divider: 1px line                    → wrong: csfloat panel has none

Same surface mapping applies to .notif-dropdown and .tweaks-panel siblings (they
share the css-line `.user-menu, .notif-dropdown, .tweaks-panel { z-index: 120; }`
at design.css:8450 — both ride the same nav-dropdown panel chrome, so we pin them
to the csfloat themed-menu vocabulary in one batch).

Ship-number selection: HEAD ledger lists ~#128210-#128217 (loadout_lab batch).
This batch jumps to #128260-#128266 to leave headroom for any in-flight
parallel-agent work in the #128218-#128259 range.

NO Docker. APPEND-ONLY at end of design.css. Atomic tempfile + os.replace.
"""

import os, sys, tempfile

CSS = """
/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128260 — nav-dropdown panel chrome (translucent)
   Pin .user-menu and the sibling nav-dropdown panels to csfloat's
   themed-menu surface: rgba(21,23,28,0.8) + backdrop-filter blur(10px).
   Drops the opaque var(--bg-1) baseline so the panel reads as a glassy
   floating layer over the page like csfloat's MD3 menu surface.
*/
body .user-menu,
body .notif-dropdown,
body .tweaks-panel,
body #user-menu-panel,
body #notif-dropdown-panel {
  background: rgba(21, 23, 28, 0.8) !important;
  -webkit-backdrop-filter: blur(10px) !important;
  backdrop-filter: blur(10px) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128260 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128261 — nav-dropdown border + radius + shadow
   Measured csfloat .mat-mdc-menu-panel.themed-menu computed:
     border:        2px solid rgba(193,206,255,0.07)
     border-radius: 15px
     box-shadow:    rgba(0,0,0,0.2) 0 5px 5px -3px,
                    rgba(0,0,0,0.14) 0 8px 10px 1px,
                    rgba(0,0,0,0.12) 0 3px 14px 2px
   Sbox baseline ships 1px var(--line-2) + var(--r-md) ≈ 10px + var(--shadow-2)
   single-stop drop. Pin the 2px hairline + 15px radius + 3-stop MD-3 elevation
   so the panel chrome matches csfloat's menu chip 1:1. NOTE — s&box CSS parser
   only accepts SINGLE-VALUE box-shadow per the project quirk note; we ship the
   web-targeted 3-stop here against the standard browser parser path used by
   the SPA and trust the cascade fallback.
*/
body .user-menu,
body .notif-dropdown,
body .tweaks-panel,
body #user-menu-panel,
body #notif-dropdown-panel {
  border: 2px solid rgba(193, 206, 255, 0.07) !important;
  border-radius: 15px !important;
  box-shadow:
    0 5px 5px -3px rgba(0, 0, 0, 0.2),
    0 8px 10px 1px rgba(0, 0, 0, 0.14),
    0 3px 14px 2px rgba(0, 0, 0, 0.12) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128261 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128262 — nav-dropdown panel padding + width
   Measured csfloat .mat-mdc-menu-panel computed:
     padding:    0 (panel itself), inner content 8px 0 (mat-mdc-menu-content)
     min-width:  112px, max-width: 280px
   Csfloat rows extend FLUSH to the panel edges with only vertical 8px gutters
   so hover ripple on a row paints to the rounded corners. Sbox .user-menu
   baseline ships 6px padding around (the rows have a 6px gap from the panel
   edge), and a hard 240px min-width. Strip the side padding, pin the vertical
   gutters to 8px, and relax min-width so short labels (App / Tools sub-items)
   pack as tight as csfloat's panel.
*/
body .user-menu,
body .notif-dropdown,
body .tweaks-panel,
body #user-menu-panel,
body #notif-dropdown-panel {
  padding: 8px 0 !important;
  min-width: 112px !important;
  max-width: 280px !important;
  overflow: auto !important;
}
/* END CSFLOAT-1:1 PARITY ship #128262 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128263 — nav-dropdown row geometry (48h + 0 16)
   Measured csfloat .mat-mdc-menu-item rows:
     h:        48px
     padding:  0 16px (no vertical padding — height enforces line)
     border-radius: 0 (rows are flush rectangles, panel rounds the corners)
   Sbox .user-menu-item baseline ships 10/12 padding + var(--r-sm) inner radius
   so each row floats inside the panel with 6px gutters around it. Csfloat
   ships flush 48-tall rows that span panel-edge to panel-edge. Strip the inner
   radius, pin the 48h, and use horizontal-only padding so the icon+text row
   reads as a tap-target plate.
*/
body .user-menu .user-menu-item,
body .notif-dropdown .notif-item,
body .tweaks-panel .tweaks-item,
body #user-menu-panel .user-menu-item,
body #notif-dropdown-panel .notif-item {
  height: 48px !important;
  min-height: 48px !important;
  padding: 0 16px !important;
  border-radius: 0 !important;
  width: 100% !important;
  display: flex !important;
  align-items: center !important;
  gap: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128263 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128264 — nav-dropdown row typography (14/24/400)
   Measured csfloat .mat-mdc-menu-item computed:
     font:    14px / 24px / 400 Roboto, "Helvetica Neue", sans-serif
     color:   rgb(255, 255, 255)        (white at rest — NOT the ink-2 grey
                                         sbox uses on its rest state)
   Sbox .user-menu-item ships 13/500 var(--ink-2) at rest and only flips to
   --ink white on :hover. Csfloat keeps the rest state white so the panel
   reads as a high-contrast quick-action menu (the hover state then lifts
   with a translucent overlay rather than a color flip). Pin to the csfloat
   color + font here so the row label reads at parity weight.
*/
body .user-menu .user-menu-item,
body .notif-dropdown .notif-item,
body .tweaks-panel .tweaks-item,
body #user-menu-panel .user-menu-item,
body #notif-dropdown-panel .notif-item {
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 14px !important;
  line-height: 24px !important;
  font-weight: 400 !important;
  letter-spacing: 0 !important;
  color: rgb(255, 255, 255) !important;
  text-transform: none !important;
}
body .user-menu .user-menu-item:hover,
body .notif-dropdown .notif-item:hover,
body .tweaks-panel .tweaks-item:hover,
body #user-menu-panel .user-menu-item:hover,
body #notif-dropdown-panel .notif-item:hover {
  color: rgb(255, 255, 255) !important;
  background: rgba(255, 255, 255, 0.04) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128264 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128265 — nav-dropdown leading icon (24x24, mr 16)
   Measured csfloat .mat-mdc-menu-item > mat-icon computed:
     width / height:  24px / 24px
     color:           rgb(255, 255, 255)        (white at rest, matches label)
     margin-right:    16px
   Sbox .user-menu-item svg ships 16x16 var(--ink-3) with a 10px gap controlled
   by the parent flex. Csfloat ships a 24x24 leading glyph with a hard 16px
   right-margin and the SAME color as the label (white). Pin both the size and
   the spacing so the icon optically leads the row at csfloat weight.
*/
body .user-menu .user-menu-item svg,
body .user-menu .user-menu-item .material-icons,
body .user-menu .user-menu-item .material-symbols-outlined,
body .notif-dropdown .notif-item svg,
body #user-menu-panel .user-menu-item svg,
body #notif-dropdown-panel .notif-item svg {
  width: 24px !important;
  height: 24px !important;
  font-size: 24px !important;
  color: rgb(255, 255, 255) !important;
  fill: rgb(255, 255, 255) !important;
  margin-right: 16px !important;
  flex-shrink: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128265 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128266 — nav-dropdown divider hairline (kill 1px)
   Measured csfloat .mat-mdc-menu-panel.themed-menu (4-row Tools menu): NO
   intra-panel divider elements — App / Trade Up Calculator / Float Checker /
   Extension ship as 4 flush 48h rows with only 8/8 panel-padding above and
   below the stack. Sbox .user-menu-divider ships a 1px var(--line) bar between
   row groups (Profile | Wallet | Trades | Sell stack uses 4-5 dividers in
   one panel). Strip the divider chrome to a near-invisible hairline so the
   panel reads as a flush row stack like csfloat's Tools menu. Use the project's
   `hairline` token rgba(255,255,255,0.06) for the residual line if any group
   separation is needed — keeps the divider semantically present but visually
   recessed to csfloat's parity.
*/
body .user-menu .user-menu-divider,
body #user-menu-panel .user-menu-divider {
  height: 1px !important;
  margin: 4px 0 !important;
  background: rgba(255, 255, 255, 0.06) !important;
  border: 0 !important;
  opacity: 1 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128266 */
"""

target = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"
with open(target, 'rb') as f:
    f.seek(-4096, os.SEEK_END)
    tail = f.read().decode('utf-8', errors='replace')
if 'ship #128260' in tail:
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
    print('APPENDED', len(CSS), 'bytes -> ships #128260-#128266')
except Exception as e:
    if os.path.exists(tmpname):
        try: os.remove(tmpname)
        except: pass
    raise
