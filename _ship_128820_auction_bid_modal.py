"""Atomic append of CSFLOAT 1:1 parity ships #128820-#128825 — REAL signed-in
csfloat AUCTION BID-PLACEMENT MODAL (the dialog that opens when a buyer clicks
"Auto Bid" on an active auction listing).

Live measured against csfloat.com (signed-in session, viewport 1440)
2026-05-08 via mcp__playwright__browser_navigate +
mcp__playwright__browser_evaluate. Source page: /item/254078312593360889
(active auction — Galil AR | Akoben). Dialog opened by clicking the
button.auto-bid-btn rendered next to each auction's countdown.

CSFLOAT GROUND TRUTH (signed-in /item, viewport 1440):

  div.cdk-overlay-pane.blurred-dialog-container.mat-mdc-dialog-panel
    background = rgb(27, 29, 36)      (panel token)
    border-radius = 4px               (mat-mdc-dialog default)
    padding = 0 (chrome lives on inner .mat-mdc-dialog-surface)

    > inner .mat-mdc-dialog-surface
        background = rgb(27, 29, 36)
        padding = 24 24 16 24

      > header row (flex row, gap 12, align-items center)
          mat-icon "gavel"               text "gavel"  (Material Icons ligature)
                                         font-size 24
                                         color rgb(255, 255, 255)
                                         margin 0
          .title-stack (flex column, gap 2)
            h2.title                     "Auto Bid"
                                         font-family Roboto
                                         font-size 20
                                         font-weight 500
                                         line-height 32
                                         letter-spacing normal
                                         color rgb(255, 255, 255)
                                         margin 0
            p.subtitle                   "Automatically bid up to a target price"
                                         font-family Roboto
                                         font-size 14
                                         font-weight 400
                                         line-height 20
                                         color rgb(158, 167, 177)  (ink-2)
                                         margin 0

      > item-card preview (csfloat reuses its grid item-card chrome)
                                         margin-top 16
                                         margin-bottom 16
                                         border-radius 8
                                         max-width 100%
                                         (item-card hover-disabled here)

      > .min-bid-row                     "Minimum Bid: $138.50"
                                         font-family Roboto
                                         font-size 14
                                         font-weight 400
                                         color rgb(158, 167, 177)
                                         margin-bottom 8

      > .mat-mdc-form-field (input wrapper, full width)
                                         h 56
                                         background rgba(255,255,255,0.04)
                                         border-radius 4 4 0 0
                                         border-bottom 1 solid rgba(255,255,255,0.42)
          > .mat-mdc-prefix              "$"
                                            font 16/400 Roboto
                                            color rgb(158,167,177)
                                            padding-left 16
          > input.mat-mdc-input-element  type number
                                            font 16/400 Roboto
                                            color rgb(255,255,255)
                                            padding 16 16 16 8
                                            background transparent
                                            border 0

      > button.place-auto-bid (full width brand)
                                         display flex
                                         width 100%
                                         height 36
                                         background rgb(35, 123, 255)   (brand)
                                         color rgb(255, 255, 255)
                                         border 0
                                         border-radius 4
                                         font 14/500 Roboto
                                         text-transform none
                                         letter-spacing 0.0892857143em (mdc)
                                         box-shadow none
                                         margin-top 16

      > .info-accordion (mat-expansion-panel)
                                         margin-top 16
                                         background transparent
                                         border 1 solid rgba(255,255,255,0.06)
                                         border-radius 4
          > header (flex row, gap 8)
              mat-icon "info"            font-size 18
                                         color rgb(158,167,177)
              span.info-label            "Info"  (collapsed) — text style
                                         font 14/500 Roboto
                                         color rgb(158,167,177)
          > body (when expanded)
              p                          font 13/400 Roboto
                                         line-height 20
                                         color rgb(158,167,177)

SBOXMARKET CURRENT STATE (verified via grep + design.css read 2026-05-08):

  csfloat-modals.js:2703 renders an INLINE .auction-bid-form on the item
  page (no separate modal). The form has:
    - wallet-amount-input (bid amount)             min-bid placeholder
    - wallet-amount-input (auto-bid cap optional)  side-by-side
    - .price-suggest-row chips (Min / +0.50 / +5 / +10%)
    - optional wallet-error / wallet-short banner
    - .btn.btn-accent "Place Bid" submit
  The form sits inside .auction-panel chrome inside the item modal.
  Existing CSS (design.css:7125):
    .auction-bid-form { display flex; gap 10; align-items stretch;
                         padding 14 18; background var(--bg-1);
                         border 1 solid var(--line); border-radius r-md;
                         margin-bottom 16; }
    .auction-bid-form input { flex 1; padding 12 14; bg var(--bg);
                              font mono 15/500; border 1 solid var(--line); }

  Differences vs csfloat measured chrome:
    – sboxmarket uses MONO font for the input number; csfloat uses Roboto
      sans 16/400.
    – sboxmarket uses two inputs side-by-side flex row; csfloat stacks
      them vertically and the auto-bid is its own modal.
    – sboxmarket has NO "$" currency prefix glyph inside the input.
      csfloat paints a "$" .mat-mdc-prefix at left of input.
    – sboxmarket has NO "Minimum Bid: $X" helper line above the input.
      The Min only shows as the placeholder which disappears on focus.
    – sboxmarket Place Bid button uses .btn.btn-accent which is
      auto-width and not full-row; csfloat is full-width 36h brand bg.
    – sboxmarket form has bg var(--bg-1) + 1px border around the row;
      csfloat dialog surface IS the panel bg, no inner card wrap. Drop
      the inner border on the form when it sits inside the auction
      panel (panel already provides chrome).

CORRECTIONS APPENDED (CSS-only, !important, NO JS source change):

  #128820 — Promote the .auction-bid-form input typography from MONO 15
            to canonical csfloat Roboto SANS 16/400. csfloat uses Roboto
            for the bid-amount field. Prevents the form from reading
            like a code editor and matches the dialog typography. Apply
            color rgb(255,255,255), padding 16/16/16/8 (room for the
            $-prefix added in #128821), background transparent, border 0
            border-bottom 1 solid rgba(255,255,255,0.42) for the
            material text-field underline pattern, border-radius
            4 4 0 0 to match csfloat's filled-input chrome.

  #128821 — Inject a "$" currency-prefix glyph inside the bid-amount
            input via ::before on the .auction-bid-form (positioned
            relative + child input padding-left). Pure CSS — no JS
            source change. Color ink-2 rgb(158,167,177), font 16/400
            Roboto, sits 16px from the left edge, vertically centered
            against the input row. Hooks the FIRST input only (the
            primary bid amount) — the second auto-bid-cap input keeps
            its placeholder pattern.

  #128822 — Render a "Minimum Bid: $XX.XX" helper line ABOVE the bid
            input via a CSS pseudo-element on .auction-bid-form. The
            min value isn't available to CSS, so the helper just reads
            "Minimum Bid" as a label; the actual figure stays as the
            input placeholder (which becomes redundant once the user
            types but is csfloat-canonical). Font 14/400 Roboto,
            color ink-2 rgb(158,167,177), margin-bottom 8.

  #128823 — Stack the two inputs vertically (column flex). csfloat
            keeps them on separate rows — the bid-amount in the Place
            Bid context, the auto-bid-cap in the Auto Bid dialog. For
            sboxmarket's combined inline form we mirror the visual
            stack. Gap 8px between inputs. Drop the inner card bg /
            border on the form (the parent .auction-panel provides
            chrome). Padding becomes 0; the form inherits panel
            padding. background transparent, border 0.

  #128824 — Promote the Place Bid submit button to csfloat brand chrome
            full-width: width 100%, h 36, bg rgb(35,123,255) (brand),
            color #fff, border-radius 4, font 14/500 Roboto, no shadow,
            text-transform none, letter-spacing 0.0892857143em (mdc).
            Override the generic .btn.btn-accent which is auto-width +
            radius var(--r-sm). Margin-top 12 to space below the
            inputs+chips block. Use a scoped selector
            (.auction-bid-form .btn.btn-accent) so the override fires
            ONLY inside the bid form — generic accent buttons elsewhere
            keep their existing chrome.

  #128825 — Hover/active states for the brand Place Bid:
              hover  bg-image overlay rgba(255,255,255,0.08)
              active bg-image overlay rgba(255,255,255,0.12)
            Mirrors csfloat's mdc-button state-layer pattern. Cursor
            pointer (already inherited but pinned for clarity).
            Disabled state: bg rgba(255,255,255,0.12), color
            rgba(255,255,255,0.3), cursor not-allowed.

CRITICAL legal-safety: this ship modifies ONLY CSS chrome (radii,
padding, swatches, font stack) and a label string ("Minimum Bid")
that is a plain English descriptor of a generic auction concept.
We do NOT copy csfloat's JS, copy text strings beyond the generic
label, image content, or any proprietary assets.

Ship numbers #128820-#128825 sit above the latest #128805 in design.css.

NO Docker. APPEND-ONLY at end of design.css. Atomic tempfile + os.replace.
Mirror to build/ so a running gradle bootRun picks up without rebuild.
"""

import os, sys, tempfile

CSS = """
/* =====================================================================
   CSFLOAT-1:1 PARITY ships #128820-#128825 — AUCTION BID-PLACEMENT
   MODAL CHROME. Live measured against csfloat.com /item (signed-in,
   viewport 1440, 2026-05-08). Reference dialog opened by clicking the
   button.auto-bid-btn on an active-auction item-card. Visible chrome:

     div.cdk-overlay-pane.blurred-dialog-container.mat-mdc-dialog-panel
       bg rgb(27,29,36), border-radius 4, padding 0
       > .mat-mdc-dialog-surface (padding 24/24/16/24)
         > header (flex row, gap 12)
             mat-icon "gavel"          24px white
             .title-stack
               h2 "Auto Bid"           20/500 Roboto, white
               p  "Automatically..."   14/400 Roboto, ink-2
         > item-card preview           radius 8, margin 16 0
         > "Minimum Bid: $X"           14/400 Roboto, ink-2, mb 8
         > .mat-mdc-form-field         h 56, bg rgba(255,255,255,0.04)
             $ prefix                  16/400 Roboto, ink-2, pl 16
             input                     16/400 Roboto, white, p 16/16/16/8
             border-bottom rgba(255,255,255,0.42)
         > button.place-auto-bid       w 100%, h 36, bg rgb(35,123,255)
                                       white, radius 4, 14/500, mt 16
         > .info-accordion             border rgba(255,255,255,0.06)
             "info" icon + body 13/400 Roboto, ink-2

   sboxmarket renders an INLINE .auction-bid-form on the item modal
   instead of a separate dialog — see csfloat-modals.js:2703. Inputs
   are MONO and side-by-side, no "$" prefix glyph, no Min-Bid helper,
   .btn.btn-accent submit is auto-width and uses --r-sm radius, the
   form has its own bg/border card chrome inside the auction panel.

   APPEND below pulls the inline form to the measured csfloat dialog
   chrome (typography, $-prefix, helper, vertical stack, full-width
   brand submit, hover overlay).
   ===================================================================== */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128820 — input typography. csfloat measures
   the bid-amount input as 16/400 Roboto, white, padding 16/16/16/8,
   transparent bg, border 0 border-bottom 1 solid rgba(255,255,255,0.42)
   (mat filled-input underline). sboxmarket currently paints mono 15/500
   inside a card. Promote both inputs to the canonical csfloat field.
*/
body .auction-bid-form input,
body .auction-bid-form input.wallet-amount-input,
body .auction-bid-form input.wallet-amount-input-v2 {
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 16px !important;
  font-weight: 400 !important;
  line-height: 24px !important;
  letter-spacing: normal !important;
  color: rgb(255, 255, 255) !important;
  padding: 16px 16px 16px 8px !important;
  background: rgba(255, 255, 255, 0.04) !important;
  border: 0 !important;
  border-bottom: 1px solid rgba(255, 255, 255, 0.42) !important;
  border-radius: 4px 4px 0 0 !important;
  outline: none !important;
  box-shadow: none !important;
  height: 56px !important;
  box-sizing: border-box !important;
}
body .auction-bid-form input::placeholder {
  color: rgb(158, 167, 177) !important;
  font-weight: 400 !important;
  letter-spacing: normal !important;
}
body .auction-bid-form input:focus {
  border-bottom-color: rgb(35, 123, 255) !important;
  border-bottom-width: 2px !important;
  background: rgba(255, 255, 255, 0.04) !important;
  box-shadow: none !important;
}
/* END CSFLOAT-1:1 PARITY ship #128820 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128821 — "$" currency-prefix glyph on the
   FIRST (bid-amount) input. csfloat paints a .mat-mdc-prefix "$" at
   the left of the field, 16/400 Roboto, ink-2 rgb(158,167,177), 16px
   from the left edge. Inject via ::before on the .auction-bid-form
   anchored to position absolute over the first child input. Hooks
   only the first input (bid amount) — the auto-bid-cap input keeps
   its placeholder pattern.
*/
body .auction-bid-form {
  position: relative !important;
}
body .auction-bid-form > input:first-of-type {
  padding-left: 28px !important;
}
body .auction-bid-form::before {
  content: "$" !important;
  position: absolute !important;
  left: 16px !important;
  /* Anchor to the FIRST input row. The form column-stacks (#128823)
     so the first input sits at the top with a known offset. The min-
     bid helper sits above it (#128822). Compute the top offset:
       .min-bid helper height ≈ 20px + margin-bottom 8 = 28px
       input row top within form ≈ 28px from form top
       input padding-top 16px + half of (font-size 16) = 24
     => prefix top ≈ 28 + 24 = 52, minus prefix half-line (8) ≈ 44.
     Use 52px and rely on line-height for vertical alignment. */
  top: 52px !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 16px !important;
  font-weight: 400 !important;
  line-height: 24px !important;
  color: rgb(158, 167, 177) !important;
  pointer-events: none !important;
  z-index: 2 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128821 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128822 — "Minimum Bid" helper line ABOVE
   the input row. csfloat shows "Minimum Bid: $XX.XX" 14/400 Roboto
   ink-2, margin-bottom 8. The actual figure stays as the input
   placeholder; the helper is a static label. Pseudo-element on
   .auction-bid-form rendered as the first child via order tricks.
*/
body .auction-bid-form::after {
  content: "Minimum Bid" !important;
  position: absolute !important;
  top: 0 !important;
  left: 0 !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 14px !important;
  font-weight: 400 !important;
  line-height: 20px !important;
  color: rgb(158, 167, 177) !important;
  letter-spacing: normal !important;
  pointer-events: none !important;
}
/* Push the form content down to make room for the helper line. */
body .auction-bid-form {
  padding-top: 32px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128822 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128823 — vertical stack of inputs and drop
   the inner card chrome. csfloat dialogs render the inputs full-width
   stacked vertically with 8px gap. The form is on the dialog surface
   directly with no inner card. Mirror by switching the .auction-bid-
   form to column flex, gap 8, no internal bg/border (the parent
   .auction-panel already provides chrome).
*/
body .auction-bid-form {
  display: flex !important;
  flex-direction: column !important;
  gap: 8px !important;
  padding: 32px 0 0 0 !important;
  background: transparent !important;
  border: 0 !important;
  border-radius: 0 !important;
  margin-bottom: 16px !important;
  align-items: stretch !important;
}
body .auction-bid-form > input {
  flex: 0 0 auto !important;
  width: 100% !important;
}
/* The price-suggest-row chips already have margin-top 6 inline; keep
   them tight against the second input but space below before submit. */
body .auction-bid-form > .price-suggest-row {
  margin-top: 8px !important;
  margin-bottom: 4px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128823 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128824 — full-width brand Place Bid submit.
   csfloat measures the dialog primary button as: width 100%, h 36,
   bg rgb(35,123,255) (brand), color #fff, border-radius 4, font
   14/500 Roboto, text-transform none, letter-spacing 0.0892857143em
   (mdc canonical), box-shadow none. Margin-top 12 to space below the
   inputs/chips block. Override the generic .btn.btn-accent which is
   auto-width and uses --r-sm radius. Scope to .auction-bid-form so
   accent buttons elsewhere keep their default chrome.
*/
body .auction-bid-form .btn.btn-accent,
body .auction-bid-form button.btn.btn-accent {
  display: flex !important;
  align-items: center !important;
  justify-content: center !important;
  width: 100% !important;
  height: 36px !important;
  min-height: 36px !important;
  padding: 0 16px !important;
  margin-top: 12px !important;
  background: rgb(35, 123, 255) !important;
  color: rgb(255, 255, 255) !important;
  border: 0 !important;
  border-radius: 4px !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 14px !important;
  font-weight: 500 !important;
  line-height: 36px !important;
  letter-spacing: 0.0892857143em !important;
  text-transform: none !important;
  box-shadow: none !important;
  cursor: pointer !important;
}
/* Sign-in fallback button (anon viewer) gets the same chrome. */
body .auction-bid-form .btn.btn-accent:not([disabled]) {
  background: rgb(35, 123, 255) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128824 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128825 — hover/active/disabled states for
   the brand Place Bid. csfloat's mdc-button state-layer paints
   rgba(255,255,255,0.08) on hover and rgba(255,255,255,0.12) on
   active over the brand bg. Mirror via gradient overlay. Disabled
   gets the canonical mdc disabled palette (12% white bg, 30% white
   text, not-allowed cursor).
*/
body .auction-bid-form .btn.btn-accent:hover:not([disabled]),
body .auction-bid-form button.btn.btn-accent:hover:not([disabled]) {
  background: linear-gradient(rgba(255, 255, 255, 0.08), rgba(255, 255, 255, 0.08)), rgb(35, 123, 255) !important;
  box-shadow: none !important;
}
body .auction-bid-form .btn.btn-accent:active:not([disabled]),
body .auction-bid-form button.btn.btn-accent:active:not([disabled]) {
  background: linear-gradient(rgba(255, 255, 255, 0.12), rgba(255, 255, 255, 0.12)), rgb(35, 123, 255) !important;
}
body .auction-bid-form .btn.btn-accent[disabled],
body .auction-bid-form button.btn.btn-accent[disabled],
body .auction-bid-form .btn.btn-accent:disabled,
body .auction-bid-form button.btn.btn-accent:disabled {
  background: rgba(255, 255, 255, 0.12) !important;
  color: rgba(255, 255, 255, 0.3) !important;
  cursor: not-allowed !important;
  box-shadow: none !important;
}
/* END CSFLOAT-1:1 PARITY ship #128825 */
"""

target = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"
mirror = r"C:\Users\WW\Desktop\sboxmarket\build\resources\main\static\css\design.css"

# Idempotency guard.
with open(target, 'rb') as f:
    f.seek(-4096, os.SEEK_END)
    tail = f.read().decode('utf-8', errors='replace')
if 'ship #128820' in tail:
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

print('SHIPS #128820-#128825 LANDED')
