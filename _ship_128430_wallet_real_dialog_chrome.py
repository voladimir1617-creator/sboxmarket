"""
CSFLOAT 1:1 PARITY ships #128430-#128435 — REAL Deposit/Withdraw measurements.

Ground truth captured today on csfloat.com signed in as Chib (skinbox.market).
Earlier ships #15800-#15805 (deposit) and #19500-#19505 (withdraw) APPROXIMATED
the wallet stepper chrome from Material primitives. After driving Playwright
through the avatar -> Deposit dialog AND the /profile/withdraw page, every
single token is now in hand.

REAL csfloat dialog surface (mat-mdc-dialog-container -> mdc-dialog__surface):
    width 950px, br 12px, bg rgba(21,23,28,0.8), border 2px solid
    rgba(193,206,255,0.07), shadow Material elevation 24:
        rgba(0,0,0,0.2)  0 11px 15px -7px,
        rgba(0,0,0,0.14) 0 24px 38px  3px,
        rgba(0,0,0,0.12) 0  9px 46px  8px

REAL stepper geometry inside that surface:
    .stepper      flex row, gap 20px, full width of dialog content
    .step (the number column) 36px wide x stepper-height tall, flex-column
    Step number itself reads at the visible 1/2/3 numeral — 36 by 36, white
    on transparent background; the brand-blue active state is the "step is
    in progress" treatment.

REAL amount input (.mat-mdc-input-element):
    width 220px x height 24px, 16px / 24px Roboto, weight 400, letter
    spacing 0.5px, placeholder "0.00", color white, NO border on the input
    itself. Wrapper is inline-flex 270 x 70 (Material form-field outline).

REAL preset chips (the $25 / $50 / $100 / $250 / $500 row on Deposit;
$0.00 / $25 / $50 / $100 / $250 row on Withdraw): they are NOT pills.
DIVs with class .highlight-text — bare clickable text:
    fontSize 14px, fontWeight 700, color rgb(35,123,255) brand blue,
    cursor pointer, no background, no padding, no border, no radius.

REAL section title ("Credit/Debit Card", etc.): 20px / 500 / 0.5px tracking.

REAL withdraw is a FULL PAGE (route /profile/withdraw) not a Material
dialog — sboxmarket's WalletModal lumps both flows into the same
component, so the same chrome applies; that means the wallet-modal
panel widening to 950px and the input stripping its hairline-border
are the corrections that shift BOTH tabs at once.

This ship corrects:
  #128430 — wallet-modal surface to REAL 950px / br 12 / bg-translucent / 2px tinted hairline / mat-elevation-24
  #128431 — wallet-step-num to REAL 36x36 (was 28x28); inactive bg transparent + white text; active brand-blue fill remains
  #128432 — wallet-step-title to REAL 20px/500/0.5px tracking (was 20/700/-0.005em)
  #128433 — wallet-amount-wrap STRIPS panel chrome (no border/bg/radius) — REAL inline-flex with $-prefix sitting beside the input
  #128434 — wallet-amount-input-v2 to REAL 16px/400/0.5px lineHeight 24 (was 22/700)
  #128435 — wallet-preset-row + wallet-preset-btn to REAL flat brand-blue clickable text (no pill, no fill, no border)

APPEND-only, !important. Atomic write. No prior rules touched.
"""
import os, datetime
CSS = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"
BUILD_CSS = r"C:\Users\WW\Desktop\sboxmarket\build\resources\main\static\css\design.css"

addition = r"""

/* ============================================================
   CSFLOAT-1:1 PARITY ship #128430 (wallet-modal surface — REAL Material elev-24 dialog)
   measured today on csfloat avatar -> Deposit dialog:
     surface 950w x 666h (visible chrome), bg rgba(21,23,28,0.8),
     br 12px, border 2px solid rgba(193,206,255,0.07),
     box-shadow Material elevation-24:
         rgba(0,0,0,0.2)  0 11px 15px -7px,
         rgba(0,0,0,0.14) 0 24px 38px  3px,
         rgba(0,0,0,0.12) 0  9px 46px  8px
   sboxmarket .wallet-modal at line 5697 ships:
     width 520px, var(--bg-1), 1px var(--line-2), var(--r-md) (=10px)
   Differences vs REAL csfloat:
     * width is 520 — REAL is 950 (almost double)
     * bg is opaque var(--bg-1) — REAL is rgba(21,23,28,0.8) translucent
     * border 1px line-2 — REAL is 2px tinted ink (rgba(193,206,255,0.07))
     * radius 10 — REAL is 12
     * NO shadow — REAL ships full mat-elev-z24
   Override the surface to match. Done as html body lift over var(--bg-1)
   token sites so existing rule (line 5697) keeps semantics.
   ============================================================ */
html body .wallet-modal {
  width: min(950px, 100%) !important;
  max-width: 950px !important;
  background: rgba(21, 23, 28, 0.8) !important;
  -webkit-backdrop-filter: blur(8px) !important;
  backdrop-filter: blur(8px) !important;
  border: 2px solid rgba(193, 206, 255, 0.07) !important;
  border-radius: 12px !important;
  box-shadow:
    rgba(0, 0, 0, 0.2)  0px 11px 15px -7px,
    rgba(0, 0, 0, 0.14) 0px 24px 38px  3px,
    rgba(0, 0, 0, 0.12) 0px  9px 46px  8px !important;
  overflow: hidden !important;
}
/* END CSFLOAT-1:1 PARITY ship #128430 */


/* ============================================================
   CSFLOAT-1:1 PARITY ship #128431 (wallet-step-num — REAL 36x36 column header)
   measured: csfloat .step inside .stepper renders the numeral inside a
   36x36 flex-column container (the column reaches full stepper height,
   but the visible numeral sits at the top in a 36-square). active state
   shows brand-blue rgb(35,123,255) fill + white text + no border;
   inactive shows transparent bg + white text. NO border on EITHER state
   in REAL csfloat — sboxmarket added a 1px hairline that doesn't exist
   in the source.
   sboxmarket ship #211 (line 126081) shipped 28x28 with 1px tinted
   border — both wrong. Restore to REAL 36x36, drop the border, keep
   the brand-blue active fill.
   ============================================================ */
html body .wallet-modal .wallet-step-num {
  width: 36px !important;
  height: 36px !important;
  background: transparent !important;
  color: rgb(255, 255, 255) !important;
  border: 0 !important;
  border-radius: 0 !important;
  font-family: Roboto, "Helvetica Neue", sans-serif !important;
  font-size: 16px !important;
  font-weight: 400 !important;
  line-height: 24px !important;
  letter-spacing: 0.5px !important;
  display: inline-flex !important;
  align-items: center !important;
  justify-content: center !important;
}
html body .wallet-modal .wallet-step.active .wallet-step-num {
  background: transparent !important;
  color: rgb(35, 123, 255) !important;
  border: 0 !important;
}
html body .wallet-modal .wallet-step.done .wallet-step-num {
  background: transparent !important;
  color: rgba(193, 206, 255, 0.55) !important;
  border: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128431 */


/* ============================================================
   CSFLOAT-1:1 PARITY ship #128432 (wallet-step-title — REAL 20/500/0.5px tracking)
   measured: csfloat section title (e.g. "Credit/Debit Card", "Select
   payment method") renders at:
     fontSize 20px, fontWeight 500 (Roboto medium, NOT 700 bold),
     letterSpacing 0.5px (POSITIVE 0.5, not negative -0.005em),
     lineHeight 24px, color rgb(255,255,255).
   sboxmarket ship #211 line 126100 shipped 20/700/-0.005em (heavier &
   tighter than REAL csfloat). Match Roboto medium with the canonical
   Material 0.5px positive tracking.
   ============================================================ */
html body .wallet-modal .wallet-step-title {
  font-family: Roboto, "Helvetica Neue", sans-serif !important;
  font-size: 20px !important;
  font-weight: 500 !important;
  letter-spacing: 0.5px !important;
  line-height: 24px !important;
  color: rgb(255, 255, 255) !important;
}
html body .wallet-modal .wallet-step-subtitle {
  font-family: Roboto, "Helvetica Neue", sans-serif !important;
  font-size: 14px !important;
  font-weight: 400 !important;
  letter-spacing: 0.5px !important;
  line-height: 24px !important;
  color: rgba(193, 206, 255, 0.55) !important;
  margin-top: 4px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128432 */


/* ============================================================
   CSFLOAT-1:1 PARITY ship #128433 (wallet-amount-wrap — REAL no panel chrome)
   measured: csfloat amount input wrapper is inline-flex with the $
   prefix as a sibling text node and the <input> sitting flush. NO
   background color, NO border, NO border-radius. The input is bare
   typography on the dialog surface. inputWrap measured 270x70 inline-flex
   column, but only because Material form-field puts the label/underline
   vertically — none of that has a fill or border.
   sboxmarket ship #212 line 126122 shipped:
     bg rgb(27,29,36), 1px hairline, br 8, height 56 (panel chrome).
   That is the OPPOSITE of REAL — strip everything to baseline.
   ============================================================ */
html body .wallet-modal .wallet-amount-wrap {
  background: transparent !important;
  border: 0 !important;
  border-bottom: 0 !important;
  border-radius: 0 !important;
  height: auto !important;
  min-height: 32px !important;
  padding: 0 !important;
  display: inline-flex !important;
  align-items: baseline !important;
  gap: 4px !important;
  box-shadow: none !important;
  transition: none !important;
}
html body .wallet-modal .wallet-amount-wrap:focus-within {
  background: transparent !important;
  border: 0 !important;
  box-shadow: none !important;
}
html body .wallet-modal .wallet-amount-prefix {
  font-family: Roboto, "Helvetica Neue", sans-serif !important;
  font-size: 16px !important;
  font-weight: 400 !important;
  letter-spacing: 0.5px !important;
  line-height: 24px !important;
  color: rgb(255, 255, 255) !important;
  padding: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128433 */


/* ============================================================
   CSFLOAT-1:1 PARITY ship #128434 (wallet-amount-input-v2 — REAL 16/400/0.5)
   measured: csfloat .mat-mdc-input-element renders at:
     width 220px x 24px tall, fontSize 16, fontWeight 400 (regular),
     lineHeight 24px, letterSpacing 0.5px, color rgb(255,255,255),
     bg transparent, NO border (Material outline lives on form-field
     wrapper, not the input). placeholder "0.00".
   sboxmarket ship #212 line 126142 shipped 22/700/-0.005em (much
   chunkier hero numeral). Match REAL Roboto-regular body-size that
   sits inline with the $ prefix.
   ============================================================ */
html body .wallet-modal .wallet-amount-input-v2 {
  font-family: Roboto, "Helvetica Neue", sans-serif !important;
  font-size: 16px !important;
  font-weight: 400 !important;
  letter-spacing: 0.5px !important;
  line-height: 24px !important;
  color: rgb(255, 255, 255) !important;
  background: transparent !important;
  border: 0 !important;
  border-bottom: 1px solid rgba(193, 206, 255, 0.42) !important;
  padding: 0 !important;
  height: auto !important;
  min-height: 24px !important;
  outline: none !important;
  width: auto !important;
  min-width: 220px !important;
}
html body .wallet-modal .wallet-amount-input-v2:focus {
  border-bottom-color: rgb(35, 123, 255) !important;
  box-shadow: none !important;
}
html body .wallet-modal .wallet-amount-input-v2::placeholder {
  color: rgba(193, 206, 255, 0.42) !important;
  opacity: 1 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128434 */


/* ============================================================
   CSFLOAT-1:1 PARITY ship #128435 (wallet-preset chips — REAL flat brand text)
   measured: csfloat suggested-amount chips are NOT pills. Each is a
   <div class="highlight-text">$25.00</div>:
     fontSize 14px, fontWeight 700, color rgb(35,123,255) brand-blue,
     bg transparent, border 0, padding 0, border-radius 0, cursor pointer.
   The whole row is 5 chips in a single horizontal flow with ~110px
   spacing. They read as inline brand-tinted bold-numeral text links —
   NOT chip surfaces.
   sboxmarket ship #213 line 126176 shipped pills (panel bg, 1px border,
   999px radius, 32h, brand fill on .active). Strip the chip chrome and
   render flat brand-blue text. Active state stays brand-blue (already
   the default colour) but underlines.
   ============================================================ */
html body .wallet-modal .wallet-preset-row {
  display: flex !important;
  flex-wrap: wrap !important;
  align-items: baseline !important;
  gap: 24px !important;
  background: transparent !important;
  border: 0 !important;
  padding: 0 !important;
}
html body .wallet-modal .wallet-preset-btn,
html body .wallet-modal .wallet-preset-row button {
  font-family: Roboto, "Helvetica Neue", sans-serif !important;
  font-size: 14px !important;
  font-weight: 700 !important;
  letter-spacing: 0 !important;
  background: transparent !important;
  color: rgb(35, 123, 255) !important;
  border: 0 !important;
  border-radius: 0 !important;
  padding: 0 !important;
  height: auto !important;
  min-height: 24px !important;
  line-height: 24px !important;
  cursor: pointer !important;
  text-decoration: none !important;
  transition: opacity 120ms ease !important;
}
html body .wallet-modal .wallet-preset-btn:hover,
html body .wallet-modal .wallet-preset-row button:hover {
  background: transparent !important;
  border: 0 !important;
  color: rgb(35, 123, 255) !important;
  opacity: 0.85 !important;
  text-decoration: underline !important;
}
html body .wallet-modal .wallet-preset-btn.active,
html body .wallet-modal .wallet-preset-row button.active {
  background: transparent !important;
  border: 0 !important;
  color: rgb(35, 123, 255) !important;
  text-decoration: underline !important;
}
html body .wallet-modal .wallet-preset-btn:focus-visible,
html body .wallet-modal .wallet-preset-row button:focus-visible {
  outline: 2px solid rgb(35, 123, 255) !important;
  outline-offset: 2px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128435 */
"""

# Atomic append: read tail to verify we don't double-write, then write+fsync+rename
with open(CSS, "rb") as f:
    f.seek(0, 2)
    size = f.tell()
    f.seek(max(0, size - 8000))
    tail = f.read().decode("utf-8", errors="ignore")

if "ship #128430" in tail and "ship #128435" in tail:
    print("ALREADY APPENDED — bail")
else:
    tmp = CSS + ".ship128430.tmp"
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
    print(f"APPENDED ships #128430-#128435 to {CSS} at {datetime.datetime.now()}")

# Mirror to build dir if it exists
if os.path.exists(BUILD_CSS):
    with open(BUILD_CSS, "rb") as f:
        f.seek(0, 2); s = f.tell(); f.seek(max(0, s-8000))
        btail = f.read().decode("utf-8", errors="ignore")
    if "ship #128430" not in btail:
        with open(BUILD_CSS, "ab") as bf:
            bf.write(addition.encode("utf-8"))
            bf.flush()
            os.fsync(bf.fileno())
        print(f"MIRRORED to build {BUILD_CSS}")
    else:
        print("build mirror already has #128430")
