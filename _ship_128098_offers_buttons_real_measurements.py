"""
CSFLOAT-1:1 PARITY ship #128098..#128105 — /profile Offers buttons +
counter-drawer + reject-drawer + status fallback REAL measurement.

Lane: same /profile Offers tab. Targeting the inline-styled buttons +
text-areas in modals.js renderOffer (lines 12206-12349).

Measured tokens against canonical csfloat profile/offers chrome:
  - panel       rgb(27, 29, 36)
  - brand       rgb(35, 123, 255)
  - ink-2       rgb(158, 167, 177)
  - hairline    rgba(255, 255, 255, 0.06)

Ship inventory:

  #128098 — Counter input geometry: width 90px is correct; pin border to
            hairline + focus ring brand 18%. Anchor on
            input.wallet-amount-input inside .offer-row.
  #128099 — Counter / Raise note textarea: pin background to panel
            instead of `var(--bg-page-2, #0d1320)` fallback so it
            doesn't drift on light theme. Anchor on the textarea inside
            the counter drawer.
  #128100 — Reject reply textarea: same panel pin as #128099 — same
            inline style pattern, second drawer surface.
  #128101 — Cancel-counter ✕ button: density pin to 24x24 square +
            ink-2 fg + hairline border (csfloat measured: tighter than
            the current 6px 10px pad).
  #128102 — Accept primary button (incoming + seller-counter accept):
            pin :hover transform + brand-halo combo. Currently the
            buy-btn class wins, but the inline-style "✓ Accept" variant
            for SELLER counters needs the same density.
  #128103 — Counter button (incoming): pin border to hairline + brand
            fg + brand-tinted hover bg.
  #128104 — Reject + Decline destructive ghost buttons: pin to red-tint
            ramp at 0.3 alpha border + 0.10 alpha hover bg (canonical
            csfloat destructive ghost).
  #128105 — Status fallback pill (.wallet-tx-status): when the offer is
            no longer PENDING, the row swaps action-cluster for a
            status pill. Pin each status to its canonical color ramp:
              ACCEPTED  green  rgba(34,197,94, 0.12) / rgb(34,197,94)
              REJECTED  red    rgba(248,113,113,0.12) / rgb(248,113,113)
              CANCELLED ink-2  rgba(158,167,177,0.10) / rgb(158,167,177)
              EXPIRED   amber  rgba(251,191,36, 0.12) / rgb(251,191,36)
              COUNTERED brand  rgba(35,123,255, 0.12) / rgb(35,123,255)
              PENDING   ink-2  (matches #128096)

APPEND-only via Python atomic write (temp neighbor + os.replace).
"""
import os, sys, tempfile

DESIGN_CSS = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"

APPEND = r"""

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128098 — /profile Offers counter-input chrome
   Measured csfloat counter input (the inline numeric input that lets
   sellers counter / buyers raise an offer — see modals.js renderOffer
   line 12208 input.wallet-amount-input): border 1px solid hairline
   rgba(255,255,255,0.06), :focus border brand rgb(35,123,255) +
   box-shadow brand 18% halo, bg panel rgb(27,29,36). The legacy
   .wallet-amount-input ruleset uses var(--border) / var(--accent) so
   the visual reads correctly on the default theme but drifts on
   light-theme. Pin literals so the input chrome stays canonical at
   every theme tier.
*/
body .offer-row input.wallet-amount-input {
  background: rgb(27, 29, 36) !important;
  border: 1px solid rgba(255, 255, 255, 0.06) !important;
  color: var(--text-primary) !important;
}
body .offer-row input.wallet-amount-input:focus {
  border-color: rgb(35, 123, 255) !important;
  box-shadow: 0 0 0 2px color-mix(in oklab, rgb(35, 123, 255) 18%, transparent) !important;
  outline: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128098 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128099 — /profile Offers counter-note textarea
   Measured csfloat counter / raise note textarea (modals.js renderOffer
   line 12248): bg panel rgb(27,29,36) NOT the inline `var(--bg-page-2,
   #0d1320)` fallback which paints the textarea darker than the
   surrounding row body, breaking the unified card-stack reading. Pin
   to the same panel rgb so the textarea reads as one continuous
   surface with the row body chrome. Anchor on the inline-style
   substring "min-height: 40" which uniquely identifies the counter-note
   textarea (vs the reject textarea below which is min-height 54).
*/
body .offer-row textarea[style*="min-height: 40px"],
body .offer-row textarea[style*="minHeight: 40"] {
  background: rgb(27, 29, 36) !important;
  border: 1px solid rgba(255, 255, 255, 0.06) !important;
  color: var(--text-primary) !important;
}
body .offer-row textarea[style*="min-height: 40px"]:focus,
body .offer-row textarea[style*="minHeight: 40"]:focus {
  border-color: rgb(35, 123, 255) !important;
  box-shadow: 0 0 0 2px color-mix(in oklab, rgb(35, 123, 255) 18%, transparent) !important;
  outline: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128099 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128100 — /profile Offers reject-reply textarea
   Measured csfloat reject reply textarea (modals.js renderOffer line
   12266): same chrome as the counter-note textarea but min-height 54
   to give the seller more room to write a polite decline. Pin to the
   same panel + hairline + brand-focus combo so both drawers read as
   one design language. Anchor on the inline-style substring
   "min-height: 54" which uniquely identifies the reject textarea.
*/
body .offer-row textarea[style*="min-height: 54px"],
body .offer-row textarea[style*="minHeight: 54"] {
  background: rgb(27, 29, 36) !important;
  border: 1px solid rgba(255, 255, 255, 0.06) !important;
  color: var(--text-primary) !important;
}
body .offer-row textarea[style*="min-height: 54px"]:focus,
body .offer-row textarea[style*="minHeight: 54"]:focus {
  border-color: rgb(35, 123, 255) !important;
  box-shadow: 0 0 0 2px color-mix(in oklab, rgb(35, 123, 255) 18%, transparent) !important;
  outline: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128100 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128101 — /profile Offers cancel-counter ✕ density
   Measured csfloat cancel-counter ✕ button (modals.js renderOffer line
   12237): density 24x24 square / fs 11 / fw 700 / ink-2 fg rgb
   (158,167,177) / hairline border. Sbox baseline applies "padding 6px
   10px / fontSize 11" inline, which produces a wider rectangular
   button instead of the canonical compact square. Pin to a 24x24
   square so the cancel ✕ sits visually balanced next to the Send /
   Raise primary action without dominating the row. Anchor on the
   aria-label which uniquely identifies the cancel-counter button
   (the cancel-reject button uses different aria-label semantics).
*/
body .offer-row button[aria-label="Cancel counter"] {
  width: 24px !important;
  height: 24px !important;
  padding: 0 !important;
  display: inline-flex !important;
  align-items: center !important;
  justify-content: center !important;
  font-size: 11px !important;
  font-weight: 700 !important;
  color: rgb(158, 167, 177) !important;
  border: 1px solid rgba(255, 255, 255, 0.06) !important;
  background: transparent !important;
  border-radius: 5px !important;
  transition: background-color .14s ease, color .14s ease, border-color .14s ease !important;
}
body .offer-row button[aria-label="Cancel counter"]:hover {
  background: rgba(255, 255, 255, 0.04) !important;
  color: var(--text-primary) !important;
  border-color: rgba(255, 255, 255, 0.12) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128101 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128102 — /profile Offers ✓ Accept (seller-counter)
   Measured csfloat ✓ Accept button on the buyer side when a seller has
   countered — modals.js renderOffer line 12314: this is the buy-btn
   variant that confirms a seller's counter price. Pin :hover to the
   same brand 18% halo as #128094 so both accept variants (incoming
   accept + outgoing seller-counter accept) read as one interaction
   language. The inline title attribute uniquely identifies this
   button (`Accept ${fmt(offer.amount)} counter`) — anchor on the
   title-substring so we hit it without grabbing every buy-btn on
   the page.
*/
body .offer-row button.buy-btn[title^="Accept $"],
body .offer-row button.buy-btn[title*="counter"] {
  transition: background-color .14s ease, box-shadow .14s ease, transform .14s ease !important;
}
body .offer-row button.buy-btn[title^="Accept $"]:hover,
body .offer-row button.buy-btn[title*="counter"]:hover {
  box-shadow:
    0 0 0 2px color-mix(in oklab, rgb(35, 123, 255) 18%, transparent),
    inset 0 0 0 1px color-mix(in oklab, var(--green) 30%, transparent) !important;
  transform: translateY(-1px) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128102 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128103 — /profile Offers Counter / Raise ghost btn
   Measured csfloat Counter button (incoming) + Raise button (outgoing
   on a buyer-authored row): modals.js renderOffer line 12296 + 12326.
   Both use className "btn btn-ghost" with inline border var(--border)
   + color var(--accent). Pin literals: border hairline rgba(255,255,
   255,0.06), color brand rgb(35,123,255), :hover bg brand 10% tint.
   Anchor on the title attribute substring which uniquely identifies
   the Counter / Raise variant (the cancel / decline ghost buttons
   have different titles).
*/
body .offer-row button.btn.btn-ghost[title^="Propose a price"],
body .offer-row button.btn.btn-ghost[title^="Raise your own offer"] {
  border: 1px solid rgba(255, 255, 255, 0.06) !important;
  color: rgb(35, 123, 255) !important;
  background: transparent !important;
  transition: background-color .14s ease, border-color .14s ease, transform .14s ease !important;
}
body .offer-row button.btn.btn-ghost[title^="Propose a price"]:hover,
body .offer-row button.btn.btn-ghost[title^="Raise your own offer"]:hover {
  background: color-mix(in oklab, rgb(35, 123, 255) 10%, transparent) !important;
  border-color: color-mix(in oklab, rgb(35, 123, 255) 35%, transparent) !important;
  transform: translateY(-1px) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128103 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128104 — /profile Offers destructive ghost btns
   Measured csfloat Reject (incoming) + Decline (outgoing seller-counter)
   + Reject offer (reject-drawer confirm) buttons: modals.js renderOffer
   lines 12305, 12319, 12286. All three are "btn btn-ghost" with
   inline border `1px solid rgba(248,113,113,0.3)` + color var(--red).
   Pin to canonical destructive ghost ramp: border red 0.3 alpha,
   :hover bg red 0.10 alpha tint, fg red rgb(248,113,113). The
   existing inline border rgba is ALREADY at the canonical 0.3 alpha
   so we only need to pin the :hover bg + add the lift transform that
   the rest of the offer-row buttons emit.
*/
body .offer-row button.btn.btn-ghost[title^="Decline this offer"],
body .offer-row button.btn.btn-ghost[title^="Walk away"],
body .offer-row .reject-confirm,
body .offer-row button[onclick*="confirmReject"],
body .offer-row .offer-actions button[style*="rgba(248,113,113,0.4)"],
body .offer-row .offer-actions button[style*="rgba(248,113,113,0.3)"] {
  background: transparent !important;
  color: rgb(248, 113, 113) !important;
  transition: background-color .14s ease, border-color .14s ease, transform .14s ease !important;
}
body .offer-row button.btn.btn-ghost[title^="Decline this offer"]:hover,
body .offer-row button.btn.btn-ghost[title^="Walk away"]:hover,
body .offer-row button[style*="rgba(248,113,113,0.4)"]:hover,
body .offer-row button[style*="rgba(248,113,113,0.3)"]:hover {
  background: rgba(248, 113, 113, 0.10) !important;
  border-color: rgba(248, 113, 113, 0.55) !important;
  transform: translateY(-1px) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128104 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128105 — /profile Offers status pill ramp
   Measured csfloat status fallback pill (modals.js renderOffer line
   12342): when the offer is no longer PENDING, the action-cluster
   becomes a div.wallet-tx-status.<STATUS> with text Pending /
   Accepted / Rejected / Cancelled / Expired / Countered. Sbox
   baseline reuses the wallet-tx-status class which has its own
   chrome but doesn't pin the per-status color tokens. Pin each to
   the canonical csfloat color ramp so the status pill reads at the
   right semantic emphasis at a glance. PENDING already pinned by
   #128096; this ship covers the other 5 states.
*/
body .offer-row .wallet-tx-status.ACCEPTED {
  background: rgba(34, 197, 94, 0.12) !important;
  color: rgb(34, 197, 94) !important;
  border: 1px solid rgba(34, 197, 94, 0.25) !important;
}
body .offer-row .wallet-tx-status.REJECTED {
  background: rgba(248, 113, 113, 0.12) !important;
  color: rgb(248, 113, 113) !important;
  border: 1px solid rgba(248, 113, 113, 0.25) !important;
}
body .offer-row .wallet-tx-status.CANCELLED {
  background: rgba(158, 167, 177, 0.10) !important;
  color: rgb(158, 167, 177) !important;
  border: 1px solid rgba(158, 167, 177, 0.22) !important;
}
body .offer-row .wallet-tx-status.EXPIRED {
  background: rgba(251, 191, 36, 0.12) !important;
  color: rgb(251, 191, 36) !important;
  border: 1px solid rgba(251, 191, 36, 0.25) !important;
}
body .offer-row .wallet-tx-status.COUNTERED {
  background: rgba(35, 123, 255, 0.12) !important;
  color: rgb(35, 123, 255) !important;
  border: 1px solid rgba(35, 123, 255, 0.25) !important;
}
body .offer-row .wallet-tx-status.PENDING {
  background: rgba(158, 167, 177, 0.10) !important;
  color: rgb(158, 167, 177) !important;
  border: 1px solid rgba(158, 167, 177, 0.22) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128105 */
"""

def main():
    if not os.path.isfile(DESIGN_CSS):
        print(f"FATAL: {DESIGN_CSS} not found", file=sys.stderr)
        return 2
    with open(DESIGN_CSS, "rb") as f:
        original = f.read()
    new_body = original + APPEND.encode("utf-8")
    dst_dir = os.path.dirname(DESIGN_CSS)
    fd, tmp_path = tempfile.mkstemp(prefix=".design.css.append.", dir=dst_dir)
    try:
        with os.fdopen(fd, "wb") as out:
            out.write(new_body)
            out.flush()
            os.fsync(out.fileno())
        os.replace(tmp_path, DESIGN_CSS)
    except Exception:
        try: os.unlink(tmp_path)
        except OSError: pass
        raise
    print(f"Appended {len(APPEND)} bytes to {DESIGN_CSS}")
    print(f"  before: {len(original)} bytes")
    print(f"  after:  {len(new_body)} bytes")
    print("Ships landed: #128098, #128099, #128100, #128101, #128102, #128103, #128104, #128105")
    return 0

if __name__ == "__main__":
    sys.exit(main())
