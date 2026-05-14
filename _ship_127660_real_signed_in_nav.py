"""
CSFLOAT 1:1 PARITY ships #127660-#127663
Real signed-in nav measurements from live csfloat.com (user "Chib skinbox.market" auth).
Atomic append to design.css. APPEND-only, !important. No prior rules touched.

REAL MEASUREMENTS (csfloat.com home, signed in, viewport 1440x900):
  Header: app-header 1320 wide x 70h, transparent bg.
  Wallet chip .normal: 61x40 at x=941 y=15. bg rgba(193,206,255,0.04), radius 6px,
    padding 0 10px, gap normal. .balance-text inside: 41x40 white 700 15px Roboto.
  USD selector .app-currency-selector mat-select: 45x25 at x=1027 y=23. value-text 700 14px white,
    arrow rgba(255,255,255,0.7) 10x5. Wrapper 25px gap from wallet chip.
  EN selector .app-language-selector: 36x25 at x=1097 y=23. Same arrow style.
  Bell .hoverable-btn (1st of 3): 28x28 at x=1158 y=21, white svg.
  Alert .hoverable-btn (2nd): 28x28 at x=1211 y=21.
  Ellipsis-more .hoverable-btn (3rd): 28x28 at x=1264 y=21.
  Avatar button.user: 48x48 at x=1317 y=11, radius 8px. img.avatar inside 40x40 radius 6px.
  All right-side widgets: 25px gap between adjacent siblings.

  Account menu div.account-menu (real, NOT what ship #15500 had):
    170x503 panel at x=1210 y=74. bg rgba(21,23,28,0.8), radius 15px,
    border 2px solid rgba(193,206,255,0.07), mat-elev shadow stack.
    Items 166x48 padding 0 16px, font 14px Roboto white.
    REAL items in order: Profile, Deposit, Withdraw, Trades, Sell Items,
    My Stall, Offers, Watchlist, Support, Logout.
    Earlier ship #15500 had wrong items (Profile/My Stall/Watchlist/Wallet/Settings/Sign Out).
"""
import os, datetime
CSS = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"

addition = r"""

/* ============================================================
   CSFLOAT-1:1 PARITY ship #127660 (signed-in wallet chip — REAL measurement)
   measured: csfloat.com home signed in as "Chib skinbox.market".
   .normal wallet container: 61x40, padding 0 10px, br 6px,
     bg rgba(193,206,255,0.04), white text 700 15px Roboto.
   .balance-text span: 41x40, white 700 15px Roboto.
   Earlier ship #126560 used Arial 28px min-height; reality is Roboto 40px.
   This ship corrects font-family + min-height + restates token tuple.
   ============================================================ */
body app-header .normal,
body app-header .balance-container,
body app-header .balance-pill,
body app-header .nav-balance-pill,
body app-header .wallet-pill {
  background-color: rgba(193, 206, 255, 0.04) !important;
  background: rgba(193, 206, 255, 0.04) !important;
  padding: 0 10px !important;
  border: 0 !important;
  border-radius: 6px !important;
  min-height: 40px !important;
  height: 40px !important;
  display: flex !important;
  align-items: center !important;
  gap: 0 !important;
  color: rgb(255, 255, 255) !important;
  font: 700 15px Roboto, "Helvetica Neue", sans-serif !important;
}
body app-header .balance-text,
body app-header .nav-balance,
body app-header .wallet-balance,
body app-header .balance-display {
  color: rgb(255, 255, 255) !important;
  font: 700 15px Roboto, "Helvetica Neue", sans-serif !important;
  letter-spacing: 0 !important;
  display: flex !important;
  align-items: center !important;
  height: 40px !important;
  font-feature-settings: "tnum" 1 !important;
}
/* END CSFLOAT-1:1 PARITY ship #127660 */

/* ============================================================
   CSFLOAT-1:1 PARITY ship #127661 (signed-in nav icon row — REAL measurement)
   measured: bell, alert, ellipsis-more = three a.hoverable-btn elements,
     each 28x28 at y=21 (vertically centered against 70px header,
     11px top margin around the 48px avatar = 21px for 28px icon).
     White svg fill (rgb 255 255 255), no badge background.
   Earlier ship #16000 placed bell as 32x32 button. Reality is 28x28 anchor.
   ============================================================ */
body app-header .hoverable-btn {
  width: 28px !important;
  height: 28px !important;
  min-width: 28px !important;
  display: inline-flex !important;
  align-items: center !important;
  justify-content: center !important;
  color: rgb(255, 255, 255) !important;
  background: transparent !important;
  border: 0 !important;
  border-radius: 6px !important;
  padding: 0 !important;
  margin: 0 !important;
  cursor: pointer !important;
  transition: opacity 120ms ease-out, color 120ms ease-out !important;
}
body app-header .hoverable-btn svg {
  width: 28px !important;
  height: 28px !important;
  color: rgb(255, 255, 255) !important;
  fill: currentColor !important;
}
body app-header .hoverable-btn:hover {
  opacity: 0.8 !important;
}
/* mat-badge dot styling per signed-in csfloat — small overlap above-after */
body app-header .mat-badge.mat-badge-small .mat-badge-content {
  font: 600 9px Roboto, "Helvetica Neue", sans-serif !important;
  width: 16px !important;
  height: 16px !important;
  line-height: 16px !important;
  background-color: rgb(35, 123, 255) !important;
  color: rgb(255, 255, 255) !important;
  border-radius: 50% !important;
  text-align: center !important;
}
/* END CSFLOAT-1:1 PARITY ship #127661 */

/* ============================================================
   CSFLOAT-1:1 PARITY ship #127662 (account-menu panel chrome — REAL measurement)
   measured: div.mat-mdc-menu-panel.account-menu rendered as 170x503 box
     at x=1210 y=74 (just under the 70px header, 4px gap).
     bg rgba(21, 23, 28, 0.8), border-radius 15px,
     border 2px solid rgba(193, 206, 255, 0.07),
     mat-elevation-z8 multi-shadow tuple.
   Earlier ship #15500 used 14px br + 1px hairline border. Reality is 15px + 2px tinted.
   ============================================================ */
body .mat-mdc-menu-panel.account-menu,
body div.account-menu {
  background-color: rgba(21, 23, 28, 0.8) !important;
  background: rgba(21, 23, 28, 0.8) !important;
  border-radius: 15px !important;
  border: 2px solid rgba(193, 206, 255, 0.07) !important;
  box-shadow:
    rgba(0, 0, 0, 0.2) 0px 5px 5px -3px,
    rgba(0, 0, 0, 0.14) 0px 8px 10px 1px,
    rgba(0, 0, 0, 0.12) 0px 3px 14px 2px !important;
  padding: 0 !important;
  min-width: 170px !important;
  width: 170px !important;
  backdrop-filter: blur(6px) !important;
  -webkit-backdrop-filter: blur(6px) !important;
  overflow: hidden !important;
}
/* END CSFLOAT-1:1 PARITY ship #127662 */

/* ============================================================
   CSFLOAT-1:1 PARITY ship #127663 (account-menu items — REAL measurement + REAL labels)
   measured: each .mat-mdc-menu-item rendered as 166x48 at padding 0 16px,
     font 14px/24px Roboto white. Items in order:
       Profile / Deposit / Withdraw / Trades(badge) / Sell Items
       / My Stall / Offers / Watchlist / Support / Logout
     (Earlier ship #15500 listed only 6 items with wrong labels e.g. "Sign Out".
      Real csfloat menu has 10 items and uses "Logout".)
   ============================================================ */
body .mat-mdc-menu-panel.account-menu .mat-mdc-menu-item,
body div.account-menu .mat-mdc-menu-item {
  height: 48px !important;
  min-height: 48px !important;
  padding: 0 16px !important;
  color: rgb(255, 255, 255) !important;
  font: 14px / 24px Roboto, "Helvetica Neue", sans-serif !important;
  display: flex !important;
  align-items: center !important;
  background: transparent !important;
  border: 0 !important;
  border-radius: 0 !important;
  width: 166px !important;
  cursor: pointer !important;
  text-align: left !important;
  letter-spacing: 0 !important;
  transition: background-color 100ms ease-out !important;
}
body .mat-mdc-menu-panel.account-menu .mat-mdc-menu-item:hover,
body div.account-menu .mat-mdc-menu-item:hover {
  background-color: rgba(255, 255, 255, 0.04) !important;
}
body .mat-mdc-menu-panel.account-menu .mat-mdc-menu-item.selected-route,
body div.account-menu .mat-mdc-menu-item.selected-route {
  color: rgb(35, 123, 255) !important;
}
body .mat-mdc-menu-panel.account-menu .mat-mdc-menu-item .mat-mdc-menu-item-text {
  font: 14px / 24px Roboto, "Helvetica Neue", sans-serif !important;
  color: inherit !important;
}
/* avatar button.user matches REAL 48x48 with 8px outer radius and 40x40 6px inner img */
body app-header button.mat-mdc-menu-trigger.user {
  width: 48px !important;
  height: 48px !important;
  min-width: 48px !important;
  border-radius: 8px !important;
  padding: 0 !important;
  margin: 0 !important;
  display: flex !important;
  align-items: center !important;
  justify-content: center !important;
  background: transparent !important;
  border: 0 !important;
}
body app-header button.mat-mdc-menu-trigger.user img.avatar {
  width: 40px !important;
  height: 40px !important;
  border-radius: 6px !important;
  object-fit: cover !important;
  display: block !important;
}
/* END CSFLOAT-1:1 PARITY ship #127663 */
"""

# Atomic append: read tail to make sure we don't double-write, then write+fsync
with open(CSS, "rb") as f:
    f.seek(0, 2)
    size = f.tell()
    f.seek(max(0, size - 4000))
    tail = f.read().decode("utf-8", errors="ignore")

if "ship #127660" in tail and "ship #127663" in tail:
    print("ALREADY APPENDED — bail")
else:
    tmp = CSS + ".ship127660.tmp"
    with open(CSS, "rb") as src, open(tmp, "wb") as dst:
        # copy
        while True:
            b = src.read(1 << 20)
            if not b: break
            dst.write(b)
        dst.write(addition.encode("utf-8"))
        dst.flush()
        os.fsync(dst.fileno())
    # rename atomically
    os.replace(tmp, CSS)
    print(f"APPENDED ships #127660-#127663 to {CSS} at {datetime.datetime.now()}")

# Also mirror to build resources so a hot reload picks them up if no rebuild happens
build_css = r"C:\Users\WW\Desktop\sboxmarket\build\resources\main\static\css\design.css"
if os.path.exists(build_css):
    with open(build_css, "rb") as f:
        f.seek(0, 2); s = f.tell(); f.seek(max(0, s-4000))
        btail = f.read().decode("utf-8", errors="ignore")
    if "ship #127660" not in btail:
        with open(build_css, "ab") as bf:
            bf.write(addition.encode("utf-8"))
            bf.flush()
            os.fsync(bf.fileno())
        print(f"MIRRORED to build {build_css}")
    else:
        print("build mirror already has #127660")
