"""Atomic append of CSFLOAT 1:1 parity ships #128660-#128669 — wallet/
deposit modal chrome refined against the REAL deposit dialog rendered on
csfloat.com via the user-menu Deposit action (`<app-deposit-dialog>`).

Live measured 2026-05-08 against csfloat.com (signed-in session, viewport
1440x900, 1050px dialog) via mcp__playwright__browser_evaluate
(getBoundingClientRect + getComputedStyle on every nested surface).

CSFLOAT GROUND TRUTH — the deposit dialog is the canonical wallet-modal
shape on csfloat (the only money-flow modal users actually open). Earlier
ships #127740-#127749 hand-modeled this surface from second-hand notes;
several core values diverged from the real Material chrome:

  .mat-mdc-dialog-surface (the modal panel itself)
    * sboxmarket #127740 paints a flat rgb(27,29,36) panel with NO border.
    * REAL csfloat: bg rgba(21,23,28,0.8) (translucent over backdrop blur)
      + border-top: 2px solid rgba(193,206,255,0.07) (the canonical
      blue-tinted highlight stripe Material adds to dark-scheme dialogs).
    * border-radius: 12px (matches), no shadow (Material 0 elev — backdrop
      blur supplies the depth, NOT a shadow stack).
    * The shadow stack in #127740 was wrong; csfloat uses backdrop-filter
      to separate the panel from the page, not box-shadow.

  [mat-dialog-content] (the scroll container inside the panel)
    * padding: 20px 24px (sboxmarket #127740 stripped this to 0 — wrong;
      the inner-scroll content NEEDS the 20/24 inset).

  .t-header (the dialog header row, icon + title + sub-text)
    * gap: 20px between icon and text block (no existing rule).

  .t-icon (the colored icon tile in dialog headers)
    * 60x60 square box, padding: 10px, bg: rgba(35,123,255,0.15) (15%
      brand tint), border-radius: 10px (no existing rule — this tile is
      the visual anchor of every csfloat money-flow dialog).

  .t-text .title (the modal title text)
    * font: 28px/500 line-height:24px Roboto white, no transform.
    * sboxmarket .wallet-hero-balance was 32px/40px — that's the BALANCE
      number, not the dialog title; needs its own rule.

  .sub-text (the secondary line under the title)
    * 14px/400 line-height:24px Roboto rgb(158,167,177).
    * sboxmarket .wallet-hero-user was 13/20 — different element.

  .t-gap (the divider strip BELOW the header band)
    * 1px tall, bg rgba(193,206,255,0.04) (BLUE-TINTED hairline, NOT the
      0.06-white that #127740 used), margin: 20px 0.
    * Important — csfloat universally uses the rgba(193,206,255,0.04)
      hairline (see #16516, #128642). The wallet-modal divider must
      match.

  .stepper (the vertical step indicator on the LEFT of each step)
    * gap: 20px between rows (no existing rule).

  .circle (a numbered step pip)
    * 36x36 round, bg rgba(193,206,255,0.04), 2px solid rgb(35,123,255)
      border (brand color outlined ring), color rgb(158,167,177) for the
      number text. No existing rule.

  .line (the vertical connector between two .circle pips)
    * 5px wide, bg rgba(193,206,255,0.04). No existing rule.

  .container.amount (the right-side step content block beside the stepper)
    * gap: 10px between input title + the input wrap.

  .input .title (the field label "Enter an amount of funds")
    * 14px/500 line-height:24px Roboto white, margin-bottom 15px.

  .mdc-text-field (the outlined input wrapper for the amount)
    * height: 48px (sboxmarket #127746 had 56px — too tall by 8px),
    * border-radius: 6px (sboxmarket #127746 had 12px — too round),
    * background: rgba(193,206,255,0.04) (sboxmarket #127746 had
      rgb(27,29,36) — the BLUE-TINTED bg is what csfloat actually uses
      to inset the input from the surrounding panel),
    * padding: 0 16px (matches),
    * NO outer border in the resting state — the bg tint is the only
      visual chrome (sboxmarket #127746 had a 1px border + 2px focus,
      neither of which csfloat ships on this filled-input variant).

These ten ships re-pin the wallet-modal chrome against the REAL deposit
dialog. Earlier ships #127740-#127749 remain on disk; these append after
them in the cascade and override only the surfaces that diverge.
"""

import os, sys, tempfile

CSS = """
/* =====================================================================
   CSFLOAT-1:1 PARITY ships #128660-#128669 — wallet-modal chrome refined
   against the REAL csfloat deposit dialog (`<app-deposit-dialog>`).
   Measured 2026-05-08 against csfloat.com signed-in session via
   getBoundingClientRect + getComputedStyle on every nested surface of
   the deposit dialog opened via the user-menu Deposit action.
   These override ONLY the values that diverge from earlier ships
   #127740-#127749.
   ===================================================================== */

/* CSFLOAT-1:1 PARITY ship #128660 — wallet-modal panel surface chrome.
   measured: .mat-mdc-dialog-surface inside csfloat dialogs has
     bg: rgba(21, 23, 28, 0.80) (translucent — backdrop-filter does the
        rest of the depth work),
     border-radius: 12px (matches earlier ship),
     border-top: 2px solid rgba(193, 206, 255, 0.07) (the blue-tinted
        highlight stripe Material adds to dark dialogs — earlier ship
        #127740 stripped this to border:0).
   Earlier ship #127740 painted a flat rgb(27, 29, 36) panel and added
   a 3-layer Material elevation shadow stack to fake depth. csfloat
   actually relies on backdrop-filter blur — we drop the shadow and
   pin the translucent surface + brand-tinted top stripe.
*/
html body .wallet-modal,
html body .modal.wallet-modal {
  background-color: rgba(21, 23, 28, 0.80) !important;
  background: rgba(21, 23, 28, 0.80) !important;
  border: 0 !important;
  border-top: 2px solid rgba(193, 206, 255, 0.07) !important;
  border-radius: 12px !important;
  box-shadow: none !important;
  backdrop-filter: blur(20px) !important;
  -webkit-backdrop-filter: blur(20px) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128660 */

/* CSFLOAT-1:1 PARITY ship #128661 — wallet-modal inner scroll padding.
   measured: [mat-dialog-content] inside the panel has padding 20px 24px
   (top/bottom 20, left/right 24). Earlier ship #127740 stripped the
   wallet-modal padding to 0 so child rules could supply their own —
   that left the deposit modal content flush against the panel edge,
   missing the 20/24 inset csfloat ships on every dialog scroll body.
*/
html body .wallet-modal .wallet-modal-body,
html body .wallet-modal [mat-dialog-content],
html body .wallet-modal .wallet-dialog-content {
  padding: 20px 24px !important;
  margin: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128661 */

/* CSFLOAT-1:1 PARITY ship #128662 — wallet-modal header row chrome.
   measured: .t-header is a flex row, gap 20px, no padding (its parent
   [mat-dialog-content] supplies the 20/24 inset). Header height varies
   with content but renders ~60px tall on the deposit dialog (icon 60 +
   text block stacked 24+24). Earlier ships had no rule for this row.
*/
html body .wallet-modal .wallet-modal-header,
html body .wallet-modal .t-header {
  display: flex !important;
  align-items: center !important;
  gap: 20px !important;
  padding: 0 !important;
  margin: 0 !important;
  background: transparent !important;
  border: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128662 */

/* CSFLOAT-1:1 PARITY ship #128663 — wallet-modal header icon tile.
   measured: .t-icon is a 60x60 box with 10px padding, bg
   rgba(35, 123, 255, 0.15) (15% brand-blue tint), border-radius 10px.
   This is the colored anchor tile every csfloat money-flow dialog
   carries to the left of the title. No earlier rule existed.
*/
html body .wallet-modal .wallet-modal-header-icon,
html body .wallet-modal .t-icon {
  display: inline-flex !important;
  align-items: center !important;
  justify-content: center !important;
  width: 60px !important;
  height: 60px !important;
  min-width: 60px !important;
  padding: 10px !important;
  background-color: rgba(35, 123, 255, 0.15) !important;
  background: rgba(35, 123, 255, 0.15) !important;
  border-radius: 10px !important;
  border: 0 !important;
  color: rgb(35, 123, 255) !important;
}
html body .wallet-modal .wallet-modal-header-icon svg,
html body .wallet-modal .t-icon svg {
  width: 100% !important;
  height: 100% !important;
  color: inherit !important;
}
/* END CSFLOAT-1:1 PARITY ship #128663 */

/* CSFLOAT-1:1 PARITY ship #128664 — wallet-modal title text typography.
   measured: .t-text .title renders 28px Roboto weight-500, line-height
   24px, color rgb(255,255,255), no letter-spacing/transform. Sboxmarket
   .wallet-hero-balance ship #127741 used 32/40 700 — that's the LARGE
   BALANCE NUMBER, a different element. The dialog TITLE itself was
   missing a rule.
*/
html body .wallet-modal .wallet-modal-title,
html body .wallet-modal .t-text .title,
html body .wallet-modal .t-header .title {
  color: rgb(255, 255, 255) !important;
  font: 500 28px/24px Roboto, "Helvetica Neue", sans-serif !important;
  letter-spacing: 0 !important;
  text-transform: none !important;
  margin: 0 !important;
  padding: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128664 */

/* CSFLOAT-1:1 PARITY ship #128665 — wallet-modal sub-text typography.
   measured: .sub-text renders 14px Roboto weight-400, line-height
   24px, color rgb(158,167,177) (the canonical ink-2 token). Sboxmarket
   .wallet-hero-user ship #127741 used 13/20 weight-500 — different
   element, different metrics; the modal sub-line under the title was
   missing a rule.
*/
html body .wallet-modal .wallet-modal-subtitle,
html body .wallet-modal .t-text .sub-text,
html body .wallet-modal .sub-text {
  color: rgb(158, 167, 177) !important;
  font: 400 14px/24px Roboto, "Helvetica Neue", sans-serif !important;
  letter-spacing: 0 !important;
  margin: 0 !important;
  padding: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128665 */

/* CSFLOAT-1:1 PARITY ship #128666 — wallet-modal header divider strip.
   measured: .t-gap is a 1px-tall horizontal strip with bg
   rgba(193, 206, 255, 0.04) (the BLUE-TINTED hairline csfloat uses
   on every signed-in surface — see #16516 + #128642), with margin
   20px 0 separating the header from the body content. Earlier ship
   #127741 used a solid border-bottom on .wallet-hero with the wrong
   white-only rgba(255,255,255,0.06) hairline.
*/
html body .wallet-modal .wallet-modal-header-divider,
html body .wallet-modal .t-gap {
  display: block !important;
  height: 1px !important;
  background-color: rgba(193, 206, 255, 0.04) !important;
  background: rgba(193, 206, 255, 0.04) !important;
  border: 0 !important;
  margin: 20px 0 !important;
  padding: 0 !important;
  width: 100% !important;
}
/* END CSFLOAT-1:1 PARITY ship #128666 */

/* CSFLOAT-1:1 PARITY ship #128667 — wallet-modal stepper chrome.
   measured: .stepper renders as a flex row (or column when stacked)
   with gap 20px between row pieces. Inside, .circle is the numbered
   pip — a 36x36 perfect round, bg rgba(193,206,255,0.04) (blue-tinted
   token), with a 2px solid rgb(35,123,255) brand-color outlined ring,
   color rgb(158,167,177) for the digit. Between pips, .line is a 5px-
   wide vertical bar with the same blue-tinted bg connecting steps.
   No earlier rule existed for the stepper component.
*/
html body .wallet-modal .wallet-stepper,
html body .wallet-modal .stepper {
  display: flex !important;
  align-items: stretch !important;
  gap: 20px !important;
  padding: 0 !important;
  margin: 0 !important;
  background: transparent !important;
}
html body .wallet-modal .wallet-stepper .step,
html body .wallet-modal .stepper .step {
  display: flex !important;
  flex-direction: column !important;
  align-items: center !important;
  gap: 0 !important;
  padding: 0 !important;
  margin: 0 !important;
}
html body .wallet-modal .wallet-stepper-circle,
html body .wallet-modal .stepper .circle {
  display: inline-flex !important;
  align-items: center !important;
  justify-content: center !important;
  width: 36px !important;
  height: 36px !important;
  min-width: 36px !important;
  border-radius: 50% !important;
  border: 2px solid rgb(35, 123, 255) !important;
  background-color: rgba(193, 206, 255, 0.04) !important;
  background: rgba(193, 206, 255, 0.04) !important;
  color: rgb(158, 167, 177) !important;
  font: 500 14px/24px Roboto, "Helvetica Neue", sans-serif !important;
  letter-spacing: 0 !important;
  padding: 0 !important;
  margin: 0 !important;
}
html body .wallet-modal .wallet-stepper-line,
html body .wallet-modal .stepper .line {
  display: block !important;
  width: 5px !important;
  flex: 1 1 auto !important;
  min-height: 24px !important;
  background-color: rgba(193, 206, 255, 0.04) !important;
  background: rgba(193, 206, 255, 0.04) !important;
  border: 0 !important;
  border-radius: 0 !important;
  margin: 0 auto !important;
}
/* END CSFLOAT-1:1 PARITY ship #128667 */

/* CSFLOAT-1:1 PARITY ship #128668 — wallet-modal field label typography.
   measured: .input .title (the inline label "Enter an amount of funds"
   above each field) renders 14px Roboto weight-500, line-height 24px,
   color rgb(255,255,255), margin-bottom 15px (the gap before the input
   wrap). No earlier rule existed.
*/
html body .wallet-modal .wallet-field-label,
html body .wallet-modal .input > .title,
html body .wallet-modal .container.amount .input .title {
  color: rgb(255, 255, 255) !important;
  font: 500 14px/24px Roboto, "Helvetica Neue", sans-serif !important;
  letter-spacing: 0 !important;
  text-transform: none !important;
  margin: 0 0 15px !important;
  padding: 0 !important;
  display: block !important;
}
/* END CSFLOAT-1:1 PARITY ship #128668 */

/* CSFLOAT-1:1 PARITY ship #128669 — wallet-modal amount input REAL chrome.
   measured: .mdc-text-field (the outlined input wrap for the amount in
   the deposit dialog) is a Material FILLED input — NOT outlined —
     height: 48px (NOT 56px),
     border-radius: 6px (NOT 12px),
     background: rgba(193, 206, 255, 0.04) (BLUE-TINTED token, NOT the
       rgb(27,29,36) panel-bg the earlier ship used),
     padding: 0 16px,
     no resting border, no hover border, focus uses the underline ripple
     (.mdc-line-ripple) NOT a 2px outline.
   Earlier ship #127746 modeled this as a Material OUTLINED input at
   56px / 12px br / panel bg / 1px border + 2px focus border — every
   value diverged from the real csfloat chrome. Pin the FILLED variant.
   The 18px input font weight from #127746 was also wrong; csfloat input
   text renders 16/24 weight-400 white.
*/
html body .wallet-modal .wallet-amount-wrap,
html body .wallet-modal .wallet-amount-input-wrap,
html body .wallet-modal .wallet-input-wrap,
html body .wallet-modal .wallet-method-fields > .wallet-amount-input-wrap {
  display: flex !important;
  align-items: center !important;
  height: 48px !important;
  min-height: 48px !important;
  padding: 0 16px !important;
  background-color: rgba(193, 206, 255, 0.04) !important;
  background: rgba(193, 206, 255, 0.04) !important;
  border: 0 !important;
  border-radius: 6px !important;
  transition: background-color 150ms cubic-bezier(0.4, 0, 0.2, 1) !important;
  position: relative !important;
}
html body .wallet-modal .wallet-amount-wrap:hover,
html body .wallet-modal .wallet-amount-input-wrap:hover,
html body .wallet-modal .wallet-input-wrap:hover {
  background-color: rgba(193, 206, 255, 0.06) !important;
  border: 0 !important;
}
html body .wallet-modal .wallet-amount-wrap:focus-within,
html body .wallet-modal .wallet-amount-input-wrap:focus-within,
html body .wallet-modal .wallet-input-wrap:focus-within {
  background-color: rgba(193, 206, 255, 0.06) !important;
  border: 0 !important;
  padding: 0 16px !important;
}
html body .wallet-modal .wallet-amount-wrap .wallet-amount-prefix,
html body .wallet-modal .wallet-amount-input-wrap .wallet-amount-prefix,
html body .wallet-modal .wallet-input-wrap .wallet-amount-prefix {
  color: rgb(255, 255, 255) !important;
  font: 400 16px/24px Roboto, "Helvetica Neue", sans-serif !important;
  letter-spacing: 0 !important;
  padding: 0 4px 0 0 !important;
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
  font: 400 16px/24px Roboto, "Helvetica Neue", sans-serif !important;
  letter-spacing: 0 !important;
  padding: 0 !important;
  font-feature-settings: "tnum" 1 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128669 */

/* csfloat-1:1 ships #128660-#128669 — wallet-modal chrome refined against
   the REAL csfloat deposit dialog (`<app-deposit-dialog>` opened via the
   user-menu Deposit action). Earlier ships #127740-#127749 hand-modeled
   this surface from second-hand notes; these ten ships re-pin the values
   that diverged when measured live:
     - panel surface bg/border-top/no-shadow (#128660)
     - inner scroll padding 20/24 (#128661)
     - header row gap 20 (#128662)
     - header icon tile 60x60 / 10 pad / 15% brand-tint / 10 br (#128663)
     - title 28/500/24 Roboto white (#128664)
     - sub-text 14/400/24 Roboto ink-2 (#128665)
     - header divider 1px BLUE-TINTED hairline (#128666)
     - stepper circle 36 / 2px brand outline / blue-tinted bg (#128667)
     - field label 14/500/24 Roboto white (#128668)
     - amount input FILLED 48px / 6px br / blue-tinted bg, NOT outlined
       56px / 12px br / panel-bg as #127746 modeled (#128669)
*/
"""

target = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"
mirror = r"C:\Users\WW\Desktop\sboxmarket\build\resources\main\static\css\design.css"

with open(target, 'rb') as f:
    f.seek(-3072, os.SEEK_END)
    tail = f.read().decode('utf-8', errors='replace')
if 'ship #128660' in tail:
    print('ALREADY APPENDED - ABORT')
    sys.exit(0)


def atomic_append(path, payload):
    tmpfd, tmpname = tempfile.mkstemp(suffix='.append', dir=os.path.dirname(path))
    try:
        with open(path, 'rb') as orig, os.fdopen(tmpfd, 'wb') as out:
            while True:
                buf = orig.read(1 << 20)
                if not buf:
                    break
                out.write(buf)
            out.write(payload.encode('utf-8'))
        os.replace(tmpname, path)
    except Exception:
        if os.path.exists(tmpname):
            try: os.remove(tmpname)
            except: pass
        raise


atomic_append(target, CSS)
print('APPENDED', len(CSS), 'bytes -> src/main/.../design.css')

if os.path.exists(mirror):
    atomic_append(mirror, CSS)
    print('MIRRORED', len(CSS), 'bytes -> build/resources/main/.../design.css')

print('SHIPS #128660-#128669 LANDED')
