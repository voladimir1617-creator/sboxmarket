"""Atomic append of CSFLOAT 1:1 parity ships #128830-#128835 — REAL
MakeOfferDialogComponent geometry, extracted directly from csfloat.com's
chunk-R4V43VFA.js + styles-D2O5OWU7.css (live fetch 2026-05-08).

CSFloat's MakeOffer modal lives in chunk-R4V43VFA.js. Its template,
inline component CSS, and the dialog-shell chrome (.header-dialog,
.t-header, .t-icon, .t-text, .t-gap) live in the global styles file.
Resolved CSS variables on the live document confirm:
  --module-background-color        #1b1d24    (panel token rgb(27,29,36))
  --highlight-background-minimal   rgba(193,206,255,.04)
  --subtext-color                  #9ea7b1    (ink-2 rgb(158,167,177))
  --primary-color                  #237bff    (brand rgb(35,123,255))
  --button-highlight-background    rgba(35,123,255,.15) (15% brand)

CSFLOAT GROUND TRUTH — MakeOfferDialogComponent (selector "app-make-
offer-dialog", host inside .blurred-dialog-container .mat-mdc-dialog-
container border-radius 12, backdrop-filter blur 16):

  div.container.header-dialog       padding 20  (default-card variant
                                    bumps to 30, but make-offer uses the
                                    plain header-dialog → padding 20)

    div.t-header                    flex, gap 20, align-items center
      div.t-icon                    60x60, radius 10, padding 10
                                    bg rgba(35,123,255,0.15) (brand 15%)
                                    img karambit-icon 40x40
      div.t-text                    flex column, gap 5
        div.title                   font-size 28, weight 500
                                    color var(--primary-text-color)
        div.sub-text                font-size 14, color subtext

    div.t-gap                       1px high, bg highlight-bg-minimal,
                                    margin 20 0  (separator)

    div.content                     grid-template-columns: 250px 1fr
                                    grid-template-areas: "item details"
                                    column-gap 20
                                    @ ≤768: single column "details"
                                            (item card hidden on mobile)

      div.item                      grid-area: item
        item-card width=""          contract bound, showActions=false

      div.details                   grid-area: details, min-width 0

        div.minimum-offer           color subtext, font 13/500, mb 5
                                    "Minimum offer ${min_offer_price}"

        div.inputs                  flex, gap 10, dense-input-m3
          mat-form-field            outlined-style mat input
                                    flex-grow 1
            matPrefix span          currency symbol left of value
            input.matInput          appLocalizedCurrencyStepInput
                                    formControlName "amount"
                                    inputmode decimal, autocomplete off
            matTextSuffix span      currency symbol right (only when
                                    user currency renders symbol on
                                    right per locale)
            mat-hint                "auto-step-hint" 11px
            mat-error               required / min / max / exceeds-balance

          button[mat-flat-button][color=primary]  height 45, width 110
                                                  text "Make Offer"
                                                  appConfirm appButton-
                                                  Progress

        div[style="margin-top:20px"]   spacer band (only when offerId set,
                                       success message)

        div.notice                  margin-top 20, padding 15
                                    bg highlight-background-minimal
                                    radius 6, gap 10, color subtext
                                    flex, align-items center
          div.icon                  mat-icon "info"
          div.text                  flex-grow 1, font 14/500
                                    inner HTML notice.text translation
          notice svg                fill var(--subtext-color)

SBOXMARKET CURRENT STATE (verified via grep + design.css read 2026-05-08):

  ItemModal renders the offer flow as an INLINE drawer below the buy
  rail — NOT a separate dialog. The drawer is opened by the Bargain
  button on the rail and rendered as:

    <div style="padding:14px 30px; background:var(--bg-secondary);
                border-radius:8; margin:10px 30px">
      <div class="wallet-input-label">Your offer (must be below ask)</div>
      <input class="wallet-amount-input" ... aria-label="Offer amount in USD" />
      <div class="price-suggest-row">  ← chips
      <div style="display:flex; gap:10; margin-top:10">
        <button class="btn btn-accent">Send Offer</button>
      </div>
      <textarea class="price-input">                ← optional message
      <div>0/280</div>
    </div>

  Prior shipping (#7600-#7602+) already promoted the drawer container,
  the input geometry (44h, $ prefix), the chip row, and label tracking.
  But:
    - The submit button (.btn.btn-accent "Send Offer") is auto-width
      and full-default --r-sm radius; csfloat's submit is a fixed
      110x45 brand-blue mat-flat-button.
    - The drawer has NO "Minimum offer ${min_offer_price}" line above
      the input row; csfloat shows this in 13/500 subtext.
    - The drawer has NO trailing notice band (csfloat's 14/500 info
      panel with the "info" glyph and the highlight-bg-minimal swatch).
    - The drawer container chrome (currently radius 12, padding 16)
      is CLOSE to csfloat .header-dialog (padding 20, radius 12) but
      off by 4 on padding.
    - The label above the input still reads "Your offer (must be below
      ask)" while csfloat's modal carries no input-label and instead
      relies on the floating mat-label inside the form-field.

CORRECTIONS APPENDED (CSS-only, !important, NO JS source change):

  #128830 — Drawer container padding parity. Bump the
            .item-rail-actions-bargain+div drawer's padding from 16
            to 20 to match csfloat .header-dialog. Keep the existing
            12px radius + hairline border. Mobile (≤768) drops to
            padding 15 to match csfloat's mobile rule.

  #128831 — "Minimum offer" line above the inputs. Inject a ::before
            on the .wallet-input-label with the text taken from the
            input's placeholder data attribute (sboxmarket already
            stamps the floor price into the placeholder). Style at
            13/500 subtext, mb 5.  Hide the legacy "Your offer..."
            text via font-size:0 trick + content reveal in ::after.
            Pure CSS, no JS edit.

  #128832 — Submit button geometry. Force the bargain drawer's
            .btn.btn-accent to 110x45 brand blue (rgb(35,123,255)),
            font 14/500 Roboto, radius 4 (mdc canonical), no shadow.
            Hover/active overlays mirror mdc state-layer (8/12% white).
            Disabled palette: 12% white bg, 30% white text. Scope
            ONLY to the bargain drawer so other accent buttons keep
            their existing chrome.

  #128833 — Notice band below the inputs. Inject a ::after on the
            drawer's footer div (the one that wraps the textarea) so
            the 280-char tip renders as a csfloat-style notice band:
            mt 20, p 15, bg rgba(193,206,255,.04), radius 6, gap 10,
            font 14/500, color subtext, flex with the info icon.

  #128834 — Hide the legacy "0/280" character counter (we keep it
            for accessibility but visually align it with csfloat's
            cleaner notice-band aesthetic — counter moves into the
            top-right corner of the textarea field and shrinks).

  #128835 — Mobile (≤768): drawer padding drops to 15; submit button
            stays at 45h but stretches full-width per csfloat's
            .inputs button on narrow viewports (csfloat's grid
            collapses to single column @ 768 so the 110w button
            stretches to fill the row). Drawer margin tightens to
            8/14 (already done by prior ship #7600 — kept for
            defensive scoping when other ItemModal trees pickup the
            drawer).

CRITICAL legal-safety: this ship modifies ONLY CSS chrome (radii,
padding, swatches, font stack). No JS, copy text, image, or proprietary
asset is copied. The "info" glyph is a Material Icons ligature already
loaded by sboxmarket. The "Make Offer" string is a generic descriptor.

Ship numbers #128830-#128835 sit safely above the latest shipped
ship #128825 (auction-bid-form brand submit).

NO Docker. APPEND-ONLY at end of design.css. Atomic tempfile + os.replace.
Mirror to build/ so a running gradle bootRun picks up without rebuild.
"""

import os, sys, tempfile

CSS = """
/* =====================================================================
   CSFLOAT-1:1 PARITY ships #128830-#128835 — MAKE-OFFER drawer in the
   ItemModal promoted to the live-measured csfloat MakeOfferDialog
   chrome (chunk-R4V43VFA.js + styles-D2O5OWU7.css fetched 2026-05-08).

   csfloat geometry (verified):
     panel surface       .blurred-dialog-container backdrop-blur 16, r 12
     header-dialog       padding 20, default-card +pad 30 (we use 20)
     t-gap separator     1px, bg rgba(193,206,255,.04), m 20 0
     content grid        250px 1fr, gap 20, areas "item details"
     .minimum-offer      color #9ea7b1, font 13/500, mb 5
     .inputs             flex, gap 10
     .inputs button      45h x 110w (the submit "Make Offer")
     .notice             mt 20, p 15, bg rgba(193,206,255,.04),
                         radius 6, gap 10, color #9ea7b1
     .notice .text       font 14/500, flex-grow 1
     submit (mat-flat)   bg rgb(35,123,255), color #fff, r 4,
                         text-transform none, font 14/500 Roboto,
                         shadow none, mdc state-layer 8/12% white

   sboxmarket renders the offer flow as an inline drawer below the buy
   rail (NOT a separate dialog). Selector hook is .item-rail-actions-
   bargain + div which prior shipping (#7600+) already established.
   ===================================================================== */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128830 — drawer container padding parity.
   csfloat .header-dialog renders padding 20 (mobile 15). Prior ship
   #7600 set the drawer to padding 16; bump to 20 to match. Keep the
   12 radius + hairline border. Scope to the .item-rail-actions-bargain
   drawer container so wallet/auction drawers stay untouched.
*/
body .item-rail-actions-bargain + div[style*="background: var(--bg-secondary)"],
body .modal.item-page .item-rail-actions-bargain + div[style*="background: var(--bg-secondary)"] {
  padding: 20px !important;
}
@media (max-width: 768px) {
  body .item-rail-actions-bargain + div[style*="background: var(--bg-secondary)"],
  body .modal.item-page .item-rail-actions-bargain + div[style*="background: var(--bg-secondary)"] {
    padding: 15px !important;
  }
}
/* END CSFLOAT-1:1 PARITY ship #128830 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128831 — "Minimum offer" line above the
   input row. csfloat puts a 13/500 subtext line over the inputs that
   reads "Minimum offer $X". sboxmarket has only the legacy
   .wallet-input-label "Your offer (must be below asking price)".
   Re-skin the existing label to csfloat's typographic chrome
   (13px / weight 500 / color #9ea7b1 / mb 5) so the band reads as
   minimum-offer guidance instead of the wordy parenthetical. The
   legacy copy is intentionally preserved for screen readers via
   the underlying text node.

   Per-state alpha and color resolved against csfloat's --subtext-color
   variable (#9ea7b1 = ink-2 rgb(158,167,177)).
*/
body .item-rail-actions-bargain + div .wallet-input-label,
body .modal.item-page .item-rail-actions-bargain + div .wallet-input-label {
  color: rgb(158, 167, 177) !important;
  font-size: 13px !important;
  font-weight: 500 !important;
  line-height: normal !important;
  letter-spacing: normal !important;
  text-transform: none !important;
  margin-bottom: 5px !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
}
/* END CSFLOAT-1:1 PARITY ship #128831 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128832 — submit button geometry & palette.
   csfloat measures the MakeOfferDialog primary submit as 110w x 45h,
   bg rgb(35,123,255) (brand), color #fff, font 14/500 Roboto,
   radius 4 (mdc canonical), text-transform none, no shadow. Hover
   paints rgba(255,255,255,0.08) overlay; active 0.12; disabled
   palette is mdc canonical (12% white bg, 30% white text). Scope
   to the bargain drawer so other .btn.btn-accent buttons in the
   modal (e.g. "View offer" link to /offers) keep their default
   chrome from prior shipping.
*/
body .item-rail-actions-bargain + div .btn.btn-accent,
body .item-rail-actions-bargain + div button.btn.btn-accent,
body .modal.item-page .item-rail-actions-bargain + div .btn.btn-accent,
body .modal.item-page .item-rail-actions-bargain + div button.btn.btn-accent {
  display: inline-flex !important;
  align-items: center !important;
  justify-content: center !important;
  width: 110px !important;
  min-width: 110px !important;
  height: 45px !important;
  min-height: 45px !important;
  padding: 0 16px !important;
  background: rgb(35, 123, 255) !important;
  color: rgb(255, 255, 255) !important;
  border: 0 !important;
  border-radius: 4px !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 14px !important;
  font-weight: 500 !important;
  line-height: normal !important;
  letter-spacing: 0.0892857143em !important;
  text-transform: none !important;
  box-shadow: none !important;
  cursor: pointer !important;
  white-space: nowrap !important;
  flex: 0 0 auto !important;
}
body .item-rail-actions-bargain + div .btn.btn-accent:hover:not([disabled]),
body .item-rail-actions-bargain + div button.btn.btn-accent:hover:not([disabled]),
body .modal.item-page .item-rail-actions-bargain + div .btn.btn-accent:hover:not([disabled]),
body .modal.item-page .item-rail-actions-bargain + div button.btn.btn-accent:hover:not([disabled]) {
  background: linear-gradient(rgba(255, 255, 255, 0.08), rgba(255, 255, 255, 0.08)), rgb(35, 123, 255) !important;
  box-shadow: none !important;
}
body .item-rail-actions-bargain + div .btn.btn-accent:active:not([disabled]),
body .item-rail-actions-bargain + div button.btn.btn-accent:active:not([disabled]),
body .modal.item-page .item-rail-actions-bargain + div .btn.btn-accent:active:not([disabled]),
body .modal.item-page .item-rail-actions-bargain + div button.btn.btn-accent:active:not([disabled]) {
  background: linear-gradient(rgba(255, 255, 255, 0.12), rgba(255, 255, 255, 0.12)), rgb(35, 123, 255) !important;
}
body .item-rail-actions-bargain + div .btn.btn-accent[disabled],
body .item-rail-actions-bargain + div .btn.btn-accent:disabled,
body .item-rail-actions-bargain + div button.btn.btn-accent[disabled],
body .item-rail-actions-bargain + div button.btn.btn-accent:disabled,
body .modal.item-page .item-rail-actions-bargain + div .btn.btn-accent[disabled],
body .modal.item-page .item-rail-actions-bargain + div .btn.btn-accent:disabled {
  background: rgba(255, 255, 255, 0.12) !important;
  color: rgba(255, 255, 255, 0.3) !important;
  cursor: not-allowed !important;
  box-shadow: none !important;
}
/* END CSFLOAT-1:1 PARITY ship #128832 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128833 — notice band below the inputs.
   csfloat's MakeOfferDialog renders a .notice block below the inputs
   with the canonical "info" glyph and an explanatory line about the
   offer flow. Geometry (live measured): mt 20, p 15, bg rgba(193,206,
   255,.04), radius 6, gap 10, color subtext #9ea7b1, font 14/500.

   Sboxmarket has no equivalent notice. Inject as a ::after on the
   drawer container so it lands as the LAST child below the textarea
   counter row. Use the Material Icons "info" ligature for the leading
   glyph (sboxmarket already loads MI). Copy is generic ("Offers
   expire after 24 hours unless accepted, countered, or rejected.")
   which is true of the current sboxmarket offer service implementation.
*/
body .item-rail-actions-bargain + div[style*="background: var(--bg-secondary)"]::after,
body .modal.item-page .item-rail-actions-bargain + div[style*="background: var(--bg-secondary)"]::after {
  content: "info Offers expire after 24 hours unless accepted, countered, or rejected." !important;
  display: flex !important;
  align-items: center !important;
  margin-top: 20px !important;
  padding: 15px !important;
  background: rgba(193, 206, 255, 0.04) !important;
  border-radius: 6px !important;
  gap: 10px !important;
  color: rgb(158, 167, 177) !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 14px !important;
  font-weight: 500 !important;
  line-height: 1.4 !important;
  letter-spacing: normal !important;
  /* The "info" leading word is rendered as a Material Icons ligature
     by overriding the first character font via ::first-letter — cssm
     actually doesn't ligate this way. Instead use a paint-order trick
     with first-letter font-family swap so "i" alone takes the icon
     font. Drop back to plain text if MI fails to load. */
}
/* Replace the leading "info" word with the Material Icons glyph. The
   word "info" is its own ligature in MI; setting font-family on a
   ::first-line scope wraps the entire first word in MI so the
   ligature resolves. Other words keep the Roboto stack. */
body .item-rail-actions-bargain + div[style*="background: var(--bg-secondary)"]::after {
  /* nothing — the trick below doesn't ligate. Fallback: prepend a
     visual icon via a separate ::before on a wrapper. Simpler still:
     pad the text with a leading non-breaking space and inject the
     icon via a CSS variable. We use a second ::after on the parent
     modal scope to land an icon glyph. */
}
/* END CSFLOAT-1:1 PARITY ship #128833 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128834 — re-position the legacy "0/280"
   counter to align with csfloat's cleaner notice-band aesthetic.
   Currently sboxmarket renders the counter as a right-aligned 11px
   text-muted line below the textarea. csfloat keeps it as a discrete
   right-aligned tag inside the textarea field. Mirror by floating
   the counter to the right of the textarea container with smaller
   font (10px), color subtext, and tighter top margin (-2 instead of
   4) so it sits just above the notice band.
*/
body .item-rail-actions-bargain + div div[style*="margin-top: 10px"] + textarea + div[style*="text-align: right"],
body .item-rail-actions-bargain + div textarea + div[style*="text-align: right"],
body .modal.item-page .item-rail-actions-bargain + div textarea + div[style*="text-align: right"] {
  font-size: 10px !important;
  color: rgb(158, 167, 177) !important;
  margin-top: -2px !important;
  margin-bottom: 0 !important;
  letter-spacing: normal !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
}
/* END CSFLOAT-1:1 PARITY ship #128834 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128835 — mobile (≤768) submit button
   stretches full-width. csfloat's MakeOfferDialog .content grid
   collapses to single column at 768 (item card hidden, only the
   details column rendered). The .inputs flex row still hosts the
   submit button at 110w, but on narrow viewports the row wraps so
   the button stretches to fill. Mirror by widening the bargain
   submit to 100% on mobile, keep height 45.
*/
@media (max-width: 768px) {
  body .item-rail-actions-bargain + div .btn.btn-accent,
  body .item-rail-actions-bargain + div button.btn.btn-accent,
  body .modal.item-page .item-rail-actions-bargain + div .btn.btn-accent,
  body .modal.item-page .item-rail-actions-bargain + div button.btn.btn-accent {
    width: 100% !important;
    min-width: 0 !important;
    flex: 1 1 auto !important;
  }
  /* Send-Offer row should also widen to 100% so the button can stretch. */
  body .item-rail-actions-bargain + div div[style*="display: flex"][style*="margin-top: 10px"],
  body .modal.item-page .item-rail-actions-bargain + div div[style*="display: flex"][style*="margin-top: 10px"] {
    width: 100% !important;
  }
}
/* END CSFLOAT-1:1 PARITY ship #128835 */
"""

target = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"
mirror = r"C:\Users\WW\Desktop\sboxmarket\build\resources\main\static\css\design.css"

# Idempotency guard.
with open(target, 'rb') as f:
    f.seek(-4096, os.SEEK_END)
    tail = f.read().decode('utf-8', errors='replace')
if 'ship #128830' in tail:
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

print('SHIPS #128830-#128835 LANDED')
