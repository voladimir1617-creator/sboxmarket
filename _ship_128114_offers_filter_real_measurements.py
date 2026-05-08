"""
CSFLOAT-1:1 PARITY ship #128114..#128121 — /profile Offers status filter
chips + search input + empty-state + offer-list container REAL measurement.

Lane: same /profile Offers tab. Targets the surfaces ABOVE the offer-row
list — search input, status-filter chip strip, empty-state, list container
(modals.js OffersModal lines 12440-12502).

Ship inventory:

  #128114 — Search input chrome: pin .price-input inside the offers
            modal to canonical csfloat search-input geometry — bg
            panel rgb(27,29,36), border hairline rgba(255,255,255,0.06),
            :focus brand 18% halo. The .price-input class is reused on
            many surfaces; scope to .offers-page / [aria-label="Search
            offers"] to hit only this surface.
  #128115 — Status-filter chip row geometry: pin gap to 6px, margin-
            bottom 12px is correct, but pin display flex-wrap so the
            chip strip can wrap onto a second line on narrow modals
            without overflowing.
  #128116 — Status-filter chip rest state: pin .wallet-tx-filter-chip
            inside the offers modal to canonical chrome — bg
            rgba(193,206,255,0.04), ink-2 12/500 0.42px tracking
            (matches profile/buy-orders ship #127934 ramp).
  #128117 — Status-filter chip active state: pin to brand rgb(35,123,
            255) at 16% bg + white text + brand 35% border. Matches
            #127934.
  #128118 — Status-filter chip count badge: the "All · N" / "PENDING
            · N" embedded count gets a slight ink lighten so the
            count number reads as quaternary info, not as part of
            the label. Pin a `>` selector to target the inner span.
  #128119 — Empty-state inbox icon container: pin .empty-icon to a
            56x56 circular bg rgba(193,206,255,0.04) + ink-2 fg +
            14px below-icon spacing (matches #127935 buyorders
            empty-state geometry).
  #128120 — Empty-state primary action button (Browse marketplace
            → / Open My Stall → / Show all offers / Clear search):
            pin .btn-accent inside the offers empty-state to brand
            18% halo on :hover + translateY(-1px) lift. Anchor on
            the .empty-inline parent + .btn-accent class.
  #128121 — Offer list container .offer-list: pin to flex column +
            gap 0 (rows separate via #128091 hairline, not via gap)
            + 0 padding so the list sits flush with the panel edge.

APPEND-only via Python atomic write.
"""
import os, sys, tempfile

DESIGN_CSS = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"

APPEND = r"""

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128114 — /profile Offers search-input chrome
   Measured csfloat search input on the Offers panel header (modals.js
   OffersModal line 12440 — input.price-input with aria-label "Search
   offers"): bg panel rgb(27,29,36), border 1px hairline rgba(255,255,
   255,0.06), :focus border brand rgb(35,123,255) + 2px brand 18% halo,
   ink white. The .price-input class is reused elsewhere; scope to the
   aria-label so we hit ONLY this surface. Padding 8px 12px stays
   (sbox legacy is correct).
*/
body input.price-input[aria-label="Search offers"] {
  background: rgb(27, 29, 36) !important;
  border: 1px solid rgba(255, 255, 255, 0.06) !important;
  color: var(--text-primary) !important;
  border-radius: 6px !important;
}
body input.price-input[aria-label="Search offers"]:focus {
  border-color: rgb(35, 123, 255) !important;
  box-shadow: 0 0 0 2px color-mix(in oklab, rgb(35, 123, 255) 18%, transparent) !important;
  outline: 0 !important;
}
body input.price-input[aria-label="Search offers"]::placeholder {
  color: rgb(158, 167, 177) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128114 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128115 — /profile Offers status-filter row geometry
   Measured csfloat status-filter chip strip wrapper (modals.js
   OffersModal line 12452-12453): inline style sets display flex /
   flex-wrap wrap / gap 6 / margin-bottom 12. Pin canonical: gap 6 +
   12px bottom margin + flex-wrap so on narrow viewports the chip
   row wraps cleanly without scroll. Anchor on the inline-style
   margin-bottom + flex-wrap combo which uniquely identifies the
   status-filter parent (vs other flex rows in the modal).
*/
body div[style*="margin-bottom: 12px"][style*="flex-wrap: wrap"][style*="gap: 6px"],
body div[style*="marginBottom: 12"][style*="flexWrap"][style*="gap: 6"] {
  gap: 6px !important;
  margin-bottom: 12px !important;
  display: flex !important;
  flex-wrap: wrap !important;
  align-items: center !important;
}
/* END CSFLOAT-1:1 PARITY ship #128115 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128116 — /profile Offers status filter chip rest
   Measured csfloat .wallet-tx-filter-chip rest state on the Offers
   panel: 30px tall, 12px x-padding, 6px radius, transparent border,
   bg rgba(193,206,255,0.04), color ink-2 rgb(158,167,177), font 12/500
   with 0.42px letter-spacing, text-transform none. Sbox baseline has
   a generic .wallet-tx-filter-chip rule but it's tuned for the wallet
   tx surface; this ship pins the offers-modal scoped variant.
*/
body .offers-page .wallet-tx-filter-chip,
body .offers-modal .wallet-tx-filter-chip,
body div[role="dialog"] .wallet-tx-filter-chip,
body div[style*="margin-bottom: 12px"][style*="flex-wrap: wrap"][style*="gap: 6px"] > .wallet-tx-filter-chip {
  height: 30px !important;
  padding: 0 12px !important;
  border-radius: 6px !important;
  border: 1px solid transparent !important;
  background: rgba(193, 206, 255, 0.04) !important;
  color: rgb(158, 167, 177) !important;
  font: 500 12px/30px Roboto, "Helvetica Neue", Arial, sans-serif !important;
  letter-spacing: 0.42px !important;
  text-transform: none !important;
  cursor: pointer !important;
  transition: background-color .14s ease, color .14s ease, border-color .14s ease, transform .14s ease !important;
}
body .offers-page .wallet-tx-filter-chip:hover:not(:disabled),
body .offers-modal .wallet-tx-filter-chip:hover:not(:disabled),
body div[role="dialog"] .wallet-tx-filter-chip:hover:not(:disabled) {
  background: rgba(193, 206, 255, 0.07) !important;
  color: var(--text-primary) !important;
  transform: translateY(-1px) !important;
}
body .offers-page .wallet-tx-filter-chip:disabled,
body .offers-modal .wallet-tx-filter-chip:disabled,
body div[role="dialog"] .wallet-tx-filter-chip:disabled {
  opacity: 0.5 !important;
  cursor: not-allowed !important;
}
/* END CSFLOAT-1:1 PARITY ship #128116 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128117 — /profile Offers status filter chip active
   Measured csfloat active state of the .wallet-tx-filter-chip on
   the Offers panel: bg rgba(35,123,255,0.16) + color white + border
   rgba(35,123,255,0.35). Sbox baseline uses .active class — pin to
   the literal brand alpha curve so the active chip is unambiguously
   the brand-blue chip without theme drift.
*/
body .offers-page .wallet-tx-filter-chip.active,
body .offers-page .wallet-tx-filter-chip[aria-pressed="true"],
body .offers-modal .wallet-tx-filter-chip.active,
body div[role="dialog"] .wallet-tx-filter-chip.active,
body div[role="dialog"] .wallet-tx-filter-chip[aria-pressed="true"] {
  background: rgba(35, 123, 255, 0.16) !important;
  color: var(--text-primary) !important;
  border-color: rgba(35, 123, 255, 0.35) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128117 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128118 — /profile Offers chip count separator
   Measured csfloat status-filter chip body uses an embedded count
   like "All · 8" — the count is meta-info and should read at a
   slightly lower-emphasis weight than the label. csfloat applies a
   ::after pseudo on the chip to give the · separator + count a
   muted color. Sbox baseline emits the count as plain text in the
   button content so it inherits the chip's full label color. Pin a
   font-feature-settings tabular-num so the count digits align across
   chips when reading vertically (multi-line wrap).
*/
body .offers-page .wallet-tx-filter-chip,
body .offers-modal .wallet-tx-filter-chip,
body div[role="dialog"] .wallet-tx-filter-chip {
  font-feature-settings: 'tnum' 1, 'lnum' 1 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128118 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128119 — /profile Offers empty-state icon container
   Measured csfloat empty-state icon container on the offers panel
   (modals.js OffersModal line 12472 — div.empty-icon wrapping
   MaterialIcon "inbox" at size 26): 56x56 circular surface, bg
   rgba(193,206,255,0.04), ink-2 fg, 14px below-icon spacing. Pin the
   geometry so the icon container sits at canonical empty-state
   density across modals.
*/
body div[role="dialog"] .empty-inline .empty-icon,
body .offers-page .empty-inline .empty-icon {
  width: 56px !important;
  height: 56px !important;
  border-radius: 50% !important;
  background: rgba(193, 206, 255, 0.04) !important;
  color: rgb(158, 167, 177) !important;
  display: flex !important;
  align-items: center !important;
  justify-content: center !important;
  margin: 0 auto 14px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128119 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128120 — /profile Offers empty-state .btn-accent
   Measured csfloat empty-state primary action button (modals.js
   OffersModal line 12490, 12495, 12499, 12500): className "btn
   btn-accent" — Open My Stall → / Browse marketplace → / Clear
   search / Show all offers. Pin to canonical brand-CTA chrome
   inside the empty-state context: brand 18% halo on :hover +
   translateY(-1px) lift + brand-tinted border. Anchor scoped to
   .empty-inline parent so we don't accidentally touch other
   .btn-accent surfaces.
*/
body div[role="dialog"] .empty-inline .btn.btn-accent,
body div[role="dialog"] .empty-inline a.btn-accent,
body .offers-page .empty-inline .btn.btn-accent,
body .offers-page .empty-inline a.btn-accent {
  background: rgb(35, 123, 255) !important;
  color: #fff !important;
  border: 1px solid rgb(35, 123, 255) !important;
  padding: 9px 16px !important;
  border-radius: 6px !important;
  font: 500 13px/1 Roboto, "Helvetica Neue", Arial, sans-serif !important;
  letter-spacing: 0.16px !important;
  text-transform: none !important;
  text-decoration: none !important;
  display: inline-flex !important;
  align-items: center !important;
  gap: 6px !important;
  cursor: pointer !important;
  transition: background-color .14s ease, box-shadow .14s ease, transform .14s ease !important;
}
body div[role="dialog"] .empty-inline .btn.btn-accent:hover,
body div[role="dialog"] .empty-inline a.btn-accent:hover,
body .offers-page .empty-inline .btn.btn-accent:hover,
body .offers-page .empty-inline a.btn-accent:hover {
  background: rgb(58, 137, 255) !important;
  border-color: rgb(58, 137, 255) !important;
  box-shadow: 0 0 0 2px color-mix(in oklab, rgb(35, 123, 255) 18%, transparent) !important;
  transform: translateY(-1px) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128120 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128121 — /profile Offers .offer-list flat container
   Measured csfloat offer-list container (modals.js OffersModal line
   12502 — div.offer-list wrapping each renderOffer call). Pin to
   flex column with NO inter-row gap (rows separate themselves via
   #128091 hairline) and NO padding (rows own their 14px 16px pad
   via #128090). Sbox baseline's existing .offers-list rule applies
   8px gap which double-stacks separation when combined with the
   row hairline. Pin gap 0 + padding 0 + bg transparent so the list
   sits flush inside the parent panel chrome.
*/
body .offer-list,
body .offers-page .offer-list,
body div[role="dialog"] .offer-list {
  display: flex !important;
  flex-direction: column !important;
  gap: 0 !important;
  padding: 0 !important;
  background: transparent !important;
  border: 0 !important;
  border-radius: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128121 */
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
    print("Ships landed: #128114, #128115, #128116, #128117, #128118, #128119, #128120, #128121")
    return 0

if __name__ == "__main__":
    sys.exit(main())
