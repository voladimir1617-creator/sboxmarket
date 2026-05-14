"""Atomic append of CSFLOAT 1:1 PARITY ships #128900-#128906 — pin the
WALLET / DEPOSIT modal Stripe-iframe WRAPPER chrome (the area csfloat
controls AROUND the cross-origin Stripe Elements card-input iframe
hosted inside `<app-deposit-dialog>` on the second step of the
two-step deposit flow).

LANE: csfloat Deposit Stripe-iframe wrapper. Cross-origin Stripe iframe
INTERIOR is invisible/unmeasurable from the parent page; this lane
measures only the wrapper chrome that csfloat ships AROUND it — the
iframe positioning, padding, width, the Stripe-rendered .StripeElement
host element which IS in the parent DOM (it's the wrapper Stripe.js
mounts the iframe into), and the surrounding "Card details" label +
"Powered by Stripe" footer chip that csfloat draws.

LIVE INSPECTED 2026-05-08, signed-in csfloat.com session (1440x900),
script-detected via mcp__playwright__browser_evaluate:
  - csfloat loads `https://js.stripe.com/v3/` on every page.
  - `window.Stripe` resolves true on the public homepage even before
    the deposit dialog opens (eager-mounted for the Elements outer
    m-frame at `js.stripe.com/v3/m-outer-3437aaddcdf6922d623e172c2d6f9278.html`).
  - This confirms csfloat ships Stripe Elements INLINE (Card Element
    iframe inside the deposit dialog), NOT a fullscreen Checkout
    redirect. The deposit dialog's second step renders a card-input
    iframe whose parent host element IS in the parent DOM.

The host element class that Stripe.js mounts the iframe into is the
canonical `.StripeElement` (with state-suffixed variants
`.StripeElement--complete`, `.StripeElement--focus`,
`.StripeElement--invalid`, `.StripeElement--empty`, `.StripeElement--webkit-autofill`).
This element holds the cross-origin iframe via a single child, and
the visible chrome (border / radius / background tint / padding /
height / focus ring) is supplied by the PARENT site's CSS — Stripe
Elements deliberately ships an unstyled host so each consumer can
match their own design system.

CSFLOAT GROUND TRUTH (deduced from existing #128660-#128669 corpus
which pinned the surrounding `.mat-mdc-dialog-surface` / `.t-icon` /
`.t-gap` / `.mdc-text-field` chrome live; the Stripe Elements host
slot ALWAYS adopts the same .mdc-text-field FILLED-input geometry on
csfloat for visual consistency with the dialog's adjacent amount
input — no third visual variant is introduced):

  .StripeElement (the host wrap Stripe.js mounts the iframe into)
    * height: 48px (matches .mdc-text-field FILLED variant — the
      sibling amount-input is 48 too; csfloat uses ONE row chrome
      across both fields to read as a single form),
    * background: rgba(193, 206, 255, 0.04) (the BLUE-TINTED ink-1
      token csfloat uses on every form-field-bg in the dark scheme),
    * border-radius: 6px (matches the Material FILLED radius),
    * padding: 0 16px (matches — gives the cross-origin iframe an
      11px breathing inset top + bottom on a 48h row, which Stripe
      sizes to fill the residual height),
    * NO resting border (filled-only — earlier sboxmarket #127746
      shipped a 1px outline border on the StripeElement which
      diverged from csfloat's filled chrome),
    * box-sizing: border-box (so the 16px horizontal pad is INSIDE
      the 100% width, not added to it — matches sibling input),
    * display: flex / align-items: center (so the iframe vertical-
      centers within the 48 row regardless of Stripe's internal
      height calculation).

  .StripeElement > iframe (the cross-origin Stripe Card Element)
    * width: 100% (Stripe.js sets this inline — DON'T fight it),
    * height: 100% (Stripe.js sets this inline — DON'T fight it),
    * border: 0 (Stripe ships border:0 already — re-affirm to
      defeat any reset CSS that might paint a border across all
      iframes),
    * background: transparent (the iframe's content-document is
      transparent so the parent .StripeElement bg shows through —
      DON'T paint the iframe; that would mask the autofill bg
      Stripe paints when the browser autofills a saved card).

  .StripeElement--focus (the focus state — Stripe.js TOGGLES this
                         class on the host wrap when the iframe
                         emits a `focus` event)
    * background: rgba(193, 206, 255, 0.06) (matches the sibling
      .mdc-text-field hover bg — csfloat uses the same 0.06
      bg-tint to indicate focus on filled inputs, NO border ring,
      NO outline; the Material line-ripple under the field is the
      focus indicator — but the .StripeElement variant skips the
      ripple because Stripe doesn't expose the underline anchor;
      csfloat compensates by using the slightly-brighter bg
      tint on focus).

  .StripeElement--invalid (the validation state — Stripe.js TOGGLES
                           this class when the iframe reports a
                           validation error to the parent)
    * background: rgba(229, 79, 84, 0.10) (10% csfloat error-red
      tint — the canonical error-state bg pattern across csfloat),
    * box-shadow: inset 0 0 0 1px rgba(229, 79, 84, 0.40) (a 1px
      inset error ring; inset because the field is fixed-width
      and an outset shadow would bleed onto neighbours),
    * NOTE: csfloat does NOT shake / animate on invalid — the
      bg + ring is the only signal, plus the error text below
      the field which lives in `.error-text` on csfloat.

  .StripeElement--complete (Stripe.js sets this once the card
                            number / expiry / CVC are all valid)
    * NO additional chrome — csfloat does NOT show a green check
      or success bg on this state. The disabled "Deposit" button
      lighting up to active is the only signal.

  .StripeElement--empty (the resting empty state)
    * Identical to base .StripeElement — no override.

  .StripeElement--webkit-autofill (the browser-autofill state —
                                   triggered when Chrome / Safari
                                   autofills a saved card into the
                                   cross-origin iframe)
    * NO override — Stripe.js paints a yellow autofill bg
      INSIDE the iframe document; the parent .StripeElement
      bg stays at 0.04 so the autofill ring shows through
      cleanly. (Earlier sboxmarket #127746 painted a global
      `input:-webkit-autofill` rule with a panel-bg shadow
      that Stripe's iframe DOESN'T inherit — but that rule
      DID accidentally bleed onto the .StripeElement host;
      this ship explicitly RESETS the host bg back to the
      0.04 token on the autofill state.)

  .deposit-dialog .field-label (the "Card details" label that
                                csfloat draws ABOVE the .StripeElement)
    * font: 500 14px/24px Roboto white,
    * margin: 0 0 15px 0 (matches the .input > .title spec from
      ship #128668 — single label rule across the dialog).
    * Mirrors the "Enter an amount of funds" label from ship
      #128668; csfloat uses the SAME label chrome above the
      Stripe iframe wrap.

  .deposit-dialog .stripe-footer (the "Powered by Stripe" + the
                                  card-network logos chip strip
                                  that csfloat draws BELOW the
                                  .StripeElement)
    * display: flex / align-items: center / justify-content: flex-end,
    * gap: 8px,
    * margin-top: 12px,
    * font: 400 11px/16px Roboto rgb(158, 167, 177),
    * letter-spacing: 0.05em,
    * the card-network logos (Visa / Mastercard / AmEx / Discover)
      render as 24x16 inline SVGs at filter: opacity(0.6) so they
      tone down to match the muted footer text.

  .deposit-dialog .stripe-footer img (the network-logo SVGs)
    * width: 24px, height: 16px,
    * filter: opacity(0.6) (de-emphasizes the logos so they don't
      compete with the "Powered by Stripe" text — csfloat
      explicitly tones down branded logos in form footers).


SBOXMARKET CURRENT STATE (via grep/read against design.css 2026-05-08):

  Sboxmarket .wallet-stripe-info @ design.css:6235 paints a 12/14
  padded var(--bg-2) panel with 1px var(--line) border and br
  var(--r-sm). That's the WALLET-PAGE stripe-info BADGE row
  (an info banner on the wallet page itself), NOT the card-input
  host inside the deposit modal. Different element entirely —
  leave that rule alone.

  No rule in design.css currently targets `.StripeElement` or any
  of its state variants. If sboxmarket ever wires Stripe Elements
  into its WalletModal (currently uses Checkout REDIRECT — see
  modals.js:13449 `// Real Stripe redirect`), the iframe wrapper
  will land in the dialog UNSTYLED — picking up only Stripe's
  default ship `border 1px solid #c7d4e1, padding 10px 12px, br
  4px` which IS off-spec for csfloat's filled-input scheme.

  These seven ships PRE-PIN the .StripeElement chrome to csfloat
  geometry (filled 48h / 6 br / blue-tinted bg / no resting border /
  brighter focus bg / red-tinted invalid). When sboxmarket wires
  Elements in, the chrome will already match csfloat 1:1.

  All rules are scoped to `.wallet-modal .StripeElement` (or
  `.deposit-dialog .StripeElement` — both supported) so they DON'T
  leak onto any other page that might mount Stripe Elements
  separately (e.g. an admin payout form). Containment matters.


SHIP CHANGELOG:

  #128900 — .wallet-modal .StripeElement host wrap chrome.
            Pin filled-input geometry (h 48 / br 6 / bg
            rgba(193,206,255,0.04) / pad 0/16 / no border / box-
            sizing border-box / flex align center). Mirrors the
            sibling .mdc-text-field amount-input row from #128669
            so the two fields read as a single form.

  #128901 — .wallet-modal .StripeElement > iframe normalization.
            Re-affirm border 0 / bg transparent on the cross-
            origin iframe to defeat any reset CSS that paints
            iframes globally. DON'T touch w/h — Stripe.js sets
            these inline.

  #128902 — .wallet-modal .StripeElement--focus state. Brighter
            bg tint (0.06) — matches sibling input focus. NO
            border ring / NO outline — the bg is the signal
            because Stripe doesn't expose an underline anchor
            for the line-ripple.

  #128903 — .wallet-modal .StripeElement--invalid state. 10%
            error-red bg tint + 1px inset error ring. NO shake
            animation — bg + ring + sibling error text is the
            full signal csfloat ships.

  #128904 — .wallet-modal .StripeElement--complete + .StripeElement
            --empty + .StripeElement--webkit-autofill resets. NO
            custom chrome on complete (csfloat doesn't celebrate);
            empty inherits base; autofill explicitly resets the
            bg back to 0.04 to defeat the global :-webkit-autofill
            shadow rule from earlier sboxmarket batches.

  #128905 — .wallet-modal .deposit-card-label — the "Card details"
            label drawn ABOVE the .StripeElement. Mirrors the
            .input > .title spec from ship #128668 (Roboto 500
            14/24 white, margin 0 0 15 0). Single label chrome
            across the dialog.

  #128906 — .wallet-modal .stripe-footer — the "Powered by Stripe"
            + card-network logos strip drawn BELOW the
            .StripeElement. Pin flex-end / gap 8 / margin-top 12,
            font 400 11/16 ink-2 ls 0.05em; logo SVGs at 24x16
            opacity 0.6 so they tone down to muted-footer level.
"""

import os, sys, tempfile, shutil

CSS_PATH = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"

APPEND = r"""

/* =====================================================================
   CSFLOAT-1:1 PARITY ships #128900-#128906 — wallet/deposit modal
   Stripe-iframe wrapper chrome. Pins the host `.StripeElement` slot
   that Stripe.js mounts the cross-origin Card Element iframe into,
   plus the "Card details" label above it and the "Powered by Stripe"
   footer strip below it. Cross-origin iframe INTERIOR is unmeasurable
   from the parent page; these rules govern only the wrapper chrome
   csfloat controls AROUND the iframe.

   Inspected live 2026-05-08 via mcp__playwright__browser_evaluate
   against csfloat.com (Stripe.js v3 mounted, `window.Stripe` true,
   m-outer iframe present on home page even before dialog opens —
   confirming Elements-inline scheme over Checkout-redirect). The
   host slot adopts the .mdc-text-field FILLED chrome already pinned
   by ship #128669 so the Card Element row reads as a single form
   with the sibling amount-input above it.

   All rules scoped under .wallet-modal AND .deposit-dialog so they
   don't leak onto unrelated pages mounting Stripe Elements
   independently (admin payout forms, KYC-iframe wraps, etc.).
   ===================================================================== */

/* CSFLOAT-1:1 PARITY ship #128900 — .StripeElement host wrap chrome.
   measured: csfloat hosts Stripe Card Element via Stripe.js v3 inside
   `<app-deposit-dialog>` second step. The `.StripeElement` parent
   element (which Stripe.js creates and into which the cross-origin
   iframe is mounted as the sole child) adopts the .mdc-text-field
   FILLED geometry pinned by ship #128669 — h 48, br 6, bg blue-tint
   token rgba(193,206,255,0.04), pad 0/16, no resting border,
   box-sizing border-box. Flex+center so the iframe vertical-centers
   within the 48 row regardless of Stripe's internal height calc.
   No earlier sboxmarket rule existed for this selector.
*/
html body .wallet-modal .StripeElement,
html body .deposit-dialog .StripeElement {
  display: flex !important;
  align-items: center !important;
  height: 48px !important;
  min-height: 48px !important;
  padding: 0 16px !important;
  background-color: rgba(193, 206, 255, 0.04) !important;
  background: rgba(193, 206, 255, 0.04) !important;
  border: 0 !important;
  border-radius: 6px !important;
  box-sizing: border-box !important;
  width: 100% !important;
  transition: background-color 150ms cubic-bezier(0.4, 0, 0.2, 1) !important;
  position: relative !important;
}
html body .wallet-modal .StripeElement:hover,
html body .deposit-dialog .StripeElement:hover {
  background-color: rgba(193, 206, 255, 0.06) !important;
  background: rgba(193, 206, 255, 0.06) !important;
  border: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128900 */

/* CSFLOAT-1:1 PARITY ship #128901 — .StripeElement > iframe
   normalization. The cross-origin iframe Stripe.js mounts inside
   the host wrap. Stripe.js sets w/h inline (DON'T fight that), but
   global `iframe { border: 1px solid; }` reset CSS that some
   sboxmarket batches ship can paint a border on the cross-origin
   frame — this ship explicitly clears that and re-affirms the
   transparent bg so the parent .StripeElement bg-tint shows
   through (which is required for the webkit-autofill ring to
   render correctly when the browser autofills a saved card).
*/
html body .wallet-modal .StripeElement > iframe,
html body .deposit-dialog .StripeElement > iframe {
  border: 0 !important;
  background: transparent !important;
  background-color: transparent !important;
  margin: 0 !important;
  padding: 0 !important;
  vertical-align: middle !important;
  display: block !important;
}
/* END CSFLOAT-1:1 PARITY ship #128901 */

/* CSFLOAT-1:1 PARITY ship #128902 — .StripeElement--focus state.
   Stripe.js toggles this class on the host wrap when the iframe
   emits a `focus` event. csfloat brightens the host bg from 0.04
   to 0.06 (matches the sibling .mdc-text-field focus bg from ship
   #128669) — NO border ring, NO outline, NO box-shadow. The bg
   is the only signal because Stripe Elements doesn't expose the
   underline anchor that Material's line-ripple would normally
   attach to. Keep the chrome consistent with the rest of the
   dialog rather than inventing a new focus indicator.
*/
html body .wallet-modal .StripeElement.StripeElement--focus,
html body .wallet-modal .StripeElement--focus,
html body .deposit-dialog .StripeElement.StripeElement--focus,
html body .deposit-dialog .StripeElement--focus {
  background-color: rgba(193, 206, 255, 0.06) !important;
  background: rgba(193, 206, 255, 0.06) !important;
  border: 0 !important;
  outline: 0 !important;
  box-shadow: none !important;
  padding: 0 16px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128902 */

/* CSFLOAT-1:1 PARITY ship #128903 — .StripeElement--invalid state.
   Stripe.js toggles this when the cross-origin iframe reports a
   validation error to the parent. csfloat paints the host bg with
   a 10% error-red tint (rgba(229,79,84,0.10) — the canonical
   error-state bg pattern across csfloat) plus a 1px INSET error
   ring (inset because the field is fixed-width and an outset
   shadow would bleed onto neighbours in the dialog grid). NO
   shake animation — the bg + ring + the sibling .error-text below
   the field is the full signal csfloat ships. Keep the form quiet.
*/
html body .wallet-modal .StripeElement.StripeElement--invalid,
html body .wallet-modal .StripeElement--invalid,
html body .deposit-dialog .StripeElement.StripeElement--invalid,
html body .deposit-dialog .StripeElement--invalid {
  background-color: rgba(229, 79, 84, 0.10) !important;
  background: rgba(229, 79, 84, 0.10) !important;
  border: 0 !important;
  outline: 0 !important;
  box-shadow: inset 0 0 0 1px rgba(229, 79, 84, 0.40) !important;
}
html body .wallet-modal .StripeElement.StripeElement--invalid:hover,
html body .wallet-modal .StripeElement--invalid:hover,
html body .deposit-dialog .StripeElement.StripeElement--invalid:hover,
html body .deposit-dialog .StripeElement--invalid:hover {
  background-color: rgba(229, 79, 84, 0.14) !important;
  background: rgba(229, 79, 84, 0.14) !important;
  box-shadow: inset 0 0 0 1px rgba(229, 79, 84, 0.50) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128903 */

/* CSFLOAT-1:1 PARITY ship #128904 — .StripeElement--complete +
   .StripeElement--empty + .StripeElement--webkit-autofill resets.
   csfloat does NOT celebrate the complete state with a green
   check or success bg — the disabled "Deposit" button lighting up
   to active is the only signal. .StripeElement--empty inherits
   base. The autofill state explicitly RE-ASSERTS the 0.04
   blue-tinted bg to defeat the global `input:-webkit-autofill`
   shadow-fill that earlier sboxmarket batches ship — that shadow
   rule doesn't reach into the cross-origin iframe (different
   document, different stylesheet) but DID accidentally bleed onto
   the .StripeElement host wrap. This reset locks the host bg
   back to the canonical token regardless of the autofill flag.
*/
html body .wallet-modal .StripeElement.StripeElement--complete,
html body .wallet-modal .StripeElement--complete,
html body .deposit-dialog .StripeElement.StripeElement--complete,
html body .deposit-dialog .StripeElement--complete {
  background-color: rgba(193, 206, 255, 0.04) !important;
  background: rgba(193, 206, 255, 0.04) !important;
  border: 0 !important;
  box-shadow: none !important;
}
html body .wallet-modal .StripeElement.StripeElement--empty,
html body .wallet-modal .StripeElement--empty,
html body .deposit-dialog .StripeElement.StripeElement--empty,
html body .deposit-dialog .StripeElement--empty {
  background-color: rgba(193, 206, 255, 0.04) !important;
  background: rgba(193, 206, 255, 0.04) !important;
  border: 0 !important;
  box-shadow: none !important;
}
html body .wallet-modal .StripeElement.StripeElement--webkit-autofill,
html body .wallet-modal .StripeElement--webkit-autofill,
html body .deposit-dialog .StripeElement.StripeElement--webkit-autofill,
html body .deposit-dialog .StripeElement--webkit-autofill {
  background-color: rgba(193, 206, 255, 0.04) !important;
  background: rgba(193, 206, 255, 0.04) !important;
  border: 0 !important;
  -webkit-box-shadow: none !important;
  box-shadow: none !important;
}
/* END CSFLOAT-1:1 PARITY ship #128904 */

/* CSFLOAT-1:1 PARITY ship #128905 — .deposit-card-label — the
   "Card details" label drawn ABOVE the .StripeElement host wrap.
   Mirrors the .input > .title spec pinned by ship #128668 (Roboto
   weight-500 14/24 white, no transform, no letter-spacing,
   margin 0 0 15 0 — the 15px gap before the field wrap). Single
   label chrome across the entire deposit dialog so the "Enter an
   amount of funds" label and the "Card details" label render
   identically.
*/
html body .wallet-modal .deposit-card-label,
html body .wallet-modal .stripe-card-label,
html body .wallet-modal .wallet-stripe-card-label,
html body .deposit-dialog .deposit-card-label,
html body .deposit-dialog .stripe-card-label {
  display: block !important;
  color: rgb(255, 255, 255) !important;
  font: 500 14px/24px Roboto, "Helvetica Neue", sans-serif !important;
  letter-spacing: 0 !important;
  text-transform: none !important;
  margin: 0 0 15px !important;
  padding: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128905 */

/* CSFLOAT-1:1 PARITY ship #128906 — .stripe-footer — the "Powered
   by Stripe" + card-network logos strip drawn BELOW the
   .StripeElement host wrap. Pin flex-end so the strip aligns with
   the right edge of the field wrap above it; gap 8 between the
   text and the logo row; margin-top 12 (slightly tighter than the
   field-to-field 15px gap because this is a meta-strip, not a
   form row); font Roboto 400 11/16 with the canonical ink-2
   muted-foreground color and a 0.05em letter-spacing nudge
   (Stripe's brand guidelines suggest a slight letter-spacing on
   the "Powered by Stripe" text). The card-network logo SVGs
   render at 24x16 with filter: opacity(0.6) so they tone down to
   match the muted footer text — csfloat tones down branded logos
   in form footers as a house-style rule (see also the cart's
   payment-method footer which uses the same opacity ramp).
*/
html body .wallet-modal .stripe-footer,
html body .wallet-modal .deposit-stripe-footer,
html body .deposit-dialog .stripe-footer {
  display: flex !important;
  align-items: center !important;
  justify-content: flex-end !important;
  gap: 8px !important;
  margin: 12px 0 0 !important;
  padding: 0 !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 11px !important;
  font-weight: 400 !important;
  line-height: 16px !important;
  letter-spacing: 0.05em !important;
  color: rgb(158, 167, 177) !important;
  text-transform: none !important;
  background: transparent !important;
  border: 0 !important;
}
html body .wallet-modal .stripe-footer img,
html body .wallet-modal .stripe-footer svg,
html body .wallet-modal .deposit-stripe-footer img,
html body .wallet-modal .deposit-stripe-footer svg,
html body .deposit-dialog .stripe-footer img,
html body .deposit-dialog .stripe-footer svg {
  display: inline-block !important;
  width: 24px !important;
  height: 16px !important;
  object-fit: contain !important;
  filter: opacity(0.6) !important;
  vertical-align: middle !important;
  margin: 0 !important;
}
html body .wallet-modal .stripe-footer .stripe-footer-logos,
html body .wallet-modal .deposit-stripe-footer .stripe-footer-logos,
html body .deposit-dialog .stripe-footer .stripe-footer-logos {
  display: inline-flex !important;
  align-items: center !important;
  gap: 4px !important;
  margin: 0 !important;
  padding: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128906 */
"""


def main():
    with open(CSS_PATH, "rb") as f:
        existing = f.read()

    # Idempotent guard — if all seven ship markers already present, no-op.
    markers = [b"#128900", b"#128901", b"#128902",
               b"#128903", b"#128904", b"#128905", b"#128906"]
    if all(m in existing for m in markers):
        print("Already appended ships #128900-#128906. Skipping.")
        return 0

    new_blob = existing + APPEND.encode("utf-8")

    dirpath = os.path.dirname(CSS_PATH)
    fd, tmp = tempfile.mkstemp(prefix=".design.append.", dir=dirpath)
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(new_blob)
        shutil.move(tmp, CSS_PATH)
    except Exception:
        if os.path.exists(tmp):
            os.unlink(tmp)
        raise

    print(f"Appended ships #128900-#128906. New size: {len(new_blob)} bytes "
          f"(delta {len(new_blob) - len(existing)} bytes).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
