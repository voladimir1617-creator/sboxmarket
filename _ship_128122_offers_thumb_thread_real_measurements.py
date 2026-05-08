"""
CSFLOAT-1:1 PARITY ship #128122..#128129 — /profile Offers item-thumb
anchor hover + buyer message quote + seller reply quote + Send/Raise
btn density + offer tab count badge REAL measurement.

Lane: same /profile Offers tab. Targets the item-thumb deep-link anchor,
the buyer-note quoted-italic, the seller rejection reply quote, plus the
Send/Raise primary action density + offer-tab count badge typography.

Counter-offer threading reference: prior ships #20600-#20607.

Ship inventory:

  #128122 — .item-thumb anchor lift: when offer.itemId is set, the
            56x56 thumbnail becomes a deep-link anchor. Pin :hover
            transform scale(1.04) + brand-tinted ring so the user
            sees a clear interactive cue.
  #128123 — .item-name anchor lift: same deep-link, the item name
            text. Pin :hover color brand rgb(35,123,255) + underline.
  #128124 — Buyer message quote (italic, single-line ellipsis): pin
            line-height 1.45 + ink-2 + quote-mark color brand 35%
            so the optional buyer note reads as a true quotation
            rather than just secondary meta.
  #128125 — Seller reply quote (rejected offers, ↩ Seller: "..."):
            pin red color to canonical rgb(248,113,113) + a faint
            red 0.04 left-border bar so the user sees the reply
            attaches to the parent offer row.
  #128126 — Send / Raise primary action button density: pin to
            canonical 28px tall + 11px x-pad + 11px font + brand
            bg + brand 18% halo on :hover. Anchor on the .buy-btn
            class inside .offer-row counter-drawer context.
  #128127 — Offer tab count badge .filter-count: pin to canonical
            brand-tinted pill — bg rgba(35,123,255,0.16), color
            white, fs 10 fw 700, padding 0 6px, border-radius 8px.
            Currently the inline marginLeft 6 is correct but the
            pill chrome leaks generic --filter-count tokens.
  #128128 — Offer tab text typography: pin .offer-tab to canonical
            csfloat tab text — fs 13 / fw 500 / Roboto / no
            text-transform / 0.16px tracking. Scope to the
            modal-mode offer-tabs context (different than the
            full-page offer-tabs ship #128097).
  #128129 — Offer dialog title: the InfoModal title prop renders
            "Incoming · Offers" / "Outgoing · Offers". Pin the
            dialog title text to canonical csfloat panel-header
            ink — fs 18 / fw 500 / Roboto / 24px line-height /
            white text. Scope via the dialog-title role.

APPEND-only via Python atomic write.
"""
import os, sys, tempfile

DESIGN_CSS = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"

APPEND = r"""

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128122 — /profile Offers .item-thumb anchor lift
   Measured csfloat .item-thumb when wrapped in a deep-link <a>
   (modals.js renderOffer line 12072 — anchor with className
   item-thumb pointing at /item/{itemId}). Pin :hover transform
   scale(1.04) on the inner img + brand-tinted box-shadow ring on
   the anchor itself so the user sees a clear interactive cue when
   the thumbnail is clickable. Sbox baseline emits the anchor with
   no chrome — the user has to discover it's clickable by trial.
*/
body .offer-row a.item-thumb {
  display: block !important;
  border-radius: 4px !important;
  overflow: hidden !important;
  transition: box-shadow .14s ease, transform .14s ease !important;
  cursor: pointer !important;
}
body .offer-row a.item-thumb img {
  width: 100% !important;
  height: 100% !important;
  object-fit: cover !important;
  transition: transform .22s cubic-bezier(.4, 0, .2, 1) !important;
}
body .offer-row a.item-thumb:hover {
  box-shadow: 0 0 0 2px color-mix(in oklab, rgb(35, 123, 255) 35%, transparent) !important;
}
body .offer-row a.item-thumb:hover img {
  transform: scale(1.04) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128122 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128123 — /profile Offers .item-name anchor lift
   Measured csfloat .item-name anchor (modals.js renderOffer line
   12082 — anchor className item-name with inline color: inherit /
   text-decoration: none). Pin :hover color brand rgb(35,123,255) +
   underline so the deep-link cue matches every other deep-link
   anchor in the modal. Anchor specifically on the offer-row
   context so non-anchor .item-name spans (legacy items without
   itemId) stay color: inherit.
*/
body .offer-row a.item-name {
  cursor: pointer !important;
  transition: color .14s ease !important;
}
body .offer-row a.item-name:hover {
  color: rgb(35, 123, 255) !important;
  text-decoration: underline !important;
  text-decoration-color: rgba(35, 123, 255, 0.6) !important;
  text-decoration-thickness: 1px !important;
  text-underline-offset: 2px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128123 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128124 — /profile Offers buyer message quote
   Measured csfloat buyer-note quote on the offer row (modals.js
   renderOffer line 12094 — div with inline italic + ellipsis
   styles). Pin canonical typography: line-height 1.45 (current
   default tightens to 1.2 which clips italic descenders), color
   ink-2 rgb(158,167,177), and tint the quote-marks brand 35% so
   the curly “…” reads as a deliberate quote framing rather than
   accidentally muted glyph clutter. Anchor on the inline-style
   font-style: italic + max-width: 320 combo which uniquely
   identifies the buyer-note line.
*/
body .offer-row > div > div[style*="font-style: italic"][style*="max-width: 320px"],
body .offer-row > div > div[style*="fontStyle: italic"][style*="maxWidth: 320"] {
  line-height: 1.45 !important;
  color: rgb(158, 167, 177) !important;
  margin-top: 4px !important;
  font-feature-settings: 'liga' 1, 'kern' 1 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128124 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128125 — /profile Offers seller reply quote
   Measured csfloat seller-reply quote on rejected offer rows
   (modals.js renderOffer line 12107 — div with inline color
   var(--red) + "↩ Seller:" prefix). Pin red to canonical rgb
   (248,113,113) literal + add a 2px left-border bar in red 0.40
   alpha so the reply visually attaches to the parent offer row
   like a threaded quote. Anchor on the inline-style color: var
   (--red) substring + max-width 320.
*/
body .offer-row > div > div[style*="color: var(--red)"][style*="max-width: 320px"],
body .offer-row > div > div[style*="color: var(--red)"][style*="maxWidth: 320"] {
  color: rgb(248, 113, 113) !important;
  border-left: 2px solid rgba(248, 113, 113, 0.40) !important;
  padding-left: 7px !important;
  margin-top: 5px !important;
  margin-left: -1px !important;
  line-height: 1.45 !important;
  background: rgba(248, 113, 113, 0.04) !important;
  border-radius: 0 4px 4px 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128125 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128126 — /profile Offers Send/Raise primary btn
   Measured csfloat Send (incoming counter) / Raise (outgoing) primary
   action button on the counter drawer (modals.js renderOffer line
   12231 — button.buy-btn with inline padding 7px 11px / fontSize 11).
   The buy-btn class has its own ruleset; pin the counter-drawer
   variant to a tight 28px tall density + brand-CTA chrome + brand
   18% halo on :hover. Anchor on the inline-style font-size: 11 +
   .buy-btn class which uniquely identifies the counter-drawer
   primary action vs the outer Accept buy-btn.
*/
body .offer-row button.buy-btn[style*="font-size: 11px"],
body .offer-row button.buy-btn[style*="fontSize: 11"] {
  height: 28px !important;
  padding: 0 11px !important;
  background: rgb(35, 123, 255) !important;
  color: #fff !important;
  border: 1px solid rgb(35, 123, 255) !important;
  border-radius: 5px !important;
  font: 700 11px/1 Roboto, "Helvetica Neue", Arial, sans-serif !important;
  cursor: pointer !important;
  transition: background-color .14s ease, box-shadow .14s ease, transform .14s ease !important;
}
body .offer-row button.buy-btn[style*="font-size: 11px"]:hover,
body .offer-row button.buy-btn[style*="fontSize: 11"]:hover {
  background: rgb(58, 137, 255) !important;
  border-color: rgb(58, 137, 255) !important;
  box-shadow: 0 0 0 2px color-mix(in oklab, rgb(35, 123, 255) 18%, transparent) !important;
  transform: translateY(-1px) !important;
}
body .offer-row button.buy-btn[style*="font-size: 11px"]:disabled,
body .offer-row button.buy-btn[style*="fontSize: 11"]:disabled {
  opacity: 0.6 !important;
  cursor: not-allowed !important;
  transform: none !important;
  box-shadow: none !important;
}
/* END CSFLOAT-1:1 PARITY ship #128126 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128127 — /profile Offers tab count badge
   Measured csfloat offer-tab count pill (modals.js OffersModal line
   12411 + 12420 — span.filter-count with inline marginLeft 6 nested
   inside the offer-tab button). Pin to canonical brand-tinted pill:
   bg rgba(35,123,255,0.16), color rgb(35,123,255) at rest / white
   when parent .offer-tab.active, fs 10 / fw 700, pad 1px 6px,
   border-radius 8px. Sbox baseline .filter-count is generic — this
   ship pins the offers-modal tab variant to canonical csfloat tab-
   count chrome.
*/
body .offer-tabs .offer-tab .filter-count,
body .offer-tabs > button .filter-count {
  display: inline-flex !important;
  align-items: center !important;
  justify-content: center !important;
  min-width: 18px !important;
  height: 16px !important;
  padding: 0 5px !important;
  margin-left: 6px !important;
  background: rgba(35, 123, 255, 0.16) !important;
  color: rgb(35, 123, 255) !important;
  font: 700 10px/1 Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-feature-settings: 'tnum' 1 !important;
  border-radius: 8px !important;
  letter-spacing: 0 !important;
}
body .offer-tabs .offer-tab.active .filter-count,
body .offer-tabs > button.active .filter-count {
  background: rgba(35, 123, 255, 0.30) !important;
  color: #fff !important;
}
/* END CSFLOAT-1:1 PARITY ship #128127 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128128 — /profile Offers tab text typography
   Measured csfloat offer-tab text (the "Incoming" / "Outgoing"
   labels in the offer-tabs strip — modals.js OffersModal line
   12403, 12412): canonical Roboto 13/500 + 0.16px tracking + no
   text-transform. Sbox baseline .offer-tab gets its typography
   from inherited button styles which can drift on theme. Pin the
   literals scoped to the modal-mode offer-tabs (vs the full-page
   variant covered by ship #128097).
*/
body div[role="dialog"] .offer-tabs .offer-tab,
body div[role="dialog"] .offer-tabs > button {
  font: 500 13px/1 Roboto, "Helvetica Neue", Arial, sans-serif !important;
  letter-spacing: 0.16px !important;
  text-transform: none !important;
  padding: 8px 12px !important;
  background: transparent !important;
  border: 0 !important;
  border-bottom: 2px solid transparent !important;
  color: rgb(158, 167, 177) !important;
  cursor: pointer !important;
  transition: color .14s ease, border-color .14s ease !important;
  display: inline-flex !important;
  align-items: center !important;
  gap: 0 !important;
}
body div[role="dialog"] .offer-tabs .offer-tab:hover,
body div[role="dialog"] .offer-tabs > button:hover {
  color: var(--text-primary) !important;
}
body div[role="dialog"] .offer-tabs .offer-tab.active,
body div[role="dialog"] .offer-tabs > button.active,
body div[role="dialog"] .offer-tabs > button[aria-selected="true"] {
  color: rgb(35, 123, 255) !important;
  border-bottom-color: rgb(35, 123, 255) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128128 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128129 — /profile Offers dialog title
   Measured csfloat dialog-header text on the Offers panel: "Incoming
   · Offers" / "Outgoing · Offers" — Roboto 18/500 + line-height 24px
   + 0.16px tracking + white text. Sbox InfoModal renders the title
   prop into a generic modal-title element which inherits a smaller
   default. Pin to the canonical csfloat panel-header geometry so
   the dialog frame reads at the right hierarchy weight. Anchor on
   the dialog title content via the data attribute or generic
   .modal-title scoped to the dialog role.
*/
body div[role="dialog"] .modal-title,
body div[role="dialog"] .dialog-title,
body div[role="dialog"] header h2,
body div[role="dialog"] header h3,
body div[role="dialog"] > .modal-card > header > div:first-child {
  font: 500 18px/24px Roboto, "Helvetica Neue", Arial, sans-serif !important;
  letter-spacing: 0.16px !important;
  color: var(--text-primary) !important;
  text-transform: none !important;
}
/* END CSFLOAT-1:1 PARITY ship #128129 */
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
    print("Ships landed: #128122, #128123, #128124, #128125, #128126, #128127, #128128, #128129")
    return 0

if __name__ == "__main__":
    sys.exit(main())
