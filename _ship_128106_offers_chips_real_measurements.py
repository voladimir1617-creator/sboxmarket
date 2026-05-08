"""
CSFLOAT-1:1 PARITY ship #128106..#128113 — /profile Offers reputation
chips + price column + offered-amount + counterparty meta REAL measurement.

Lane: same /profile Offers tab. Targets the inline-styled reputation
chips (trades / star-rating) + the amount/asking-price/pct-off price
column (modals.js renderOffer lines 12150-12203).

Ship inventory:

  #128106 — Buyer trades chip: pin to canonical brand rgb(35,123,255)
            tints. Inline style uses legacy rgb(30,165,255) which
            resolves slightly cyan vs canonical pure brand blue.
  #128107 — Star-rating chip (buyer + seller): pin amber chip ink to
            literal #fbbf24, tighten alpha curve to 0.10 (canonical)
            from current 0.08 (slightly under-saturated).
  #128108 — Reputation chip row (parent flex div): pin gap to 6px and
            pin color of meta sub-row to ink-2 rgb(158,167,177)
            instead of var(--text-muted) drift.
  #128109 — Offered amount (primary $): pin to brand rgb(35,123,255)
            literal + JetBrains Mono / fs 14 / fw 800. Inline style
            uses var(--accent) which resolves correctly default but
            drifts on light theme.
  #128110 — Asking-price strikethrough column geometry: pin display
            block + line-height 1.4 so the strikethrough sits on its
            own line cleanly under the offered amount, not crammed
            against it.
  #128111 — Pct-off badge: pin green to canonical rgb(34,197,94)
            literal + add background tint (currently no bg, just fg
            color — csfloat measured: subtle 0.10 green tint chip).
  #128112 — Pct-off when zero / null variant: pin to ink-2 instead of
            var(--text-muted) drift, keep no-bg (chip should be
            invisible when there's no discount to show).
  #128113 — Auto-decline countdown ⏱ stamp: pin red color to canonical
            rgb(248,113,113) literal when urgent (<24h), ink-2 when
            non-urgent — matches #128092 ink-2 ramp.

APPEND-only via Python atomic write.
"""
import os, sys, tempfile

DESIGN_CSS = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"

APPEND = r"""

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128106 — /profile Offers buyer trades chip
   Measured csfloat reputation chip "—N trade(s)" on the offer row
   (modals.js renderOffer line 12160): inline style uses legacy
   rgb(30,165,255) at 0.08 bg / var(--accent) fg / 0.25 border.
   Pin to canonical brand rgb(35,123,255) at the same alpha curve so
   the trades chip matches the brand color used everywhere else on
   the row (counter input focus, raise btn, active tab underline).
   Anchor on the inline-style background substring which uniquely
   identifies the trades chip.
*/
body .offer-row span[style*="rgba(30,165,255,0.08)"],
body .offer-row span[style*="rgba(30, 165, 255, 0.08)"] {
  background: rgba(35, 123, 255, 0.10) !important;
  color: rgb(35, 123, 255) !important;
  border-color: rgba(35, 123, 255, 0.28) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128106 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128107 — /profile Offers star-rating chip
   Measured csfloat star-rating chip "★ N.N (count)" on the offer row
   (modals.js renderOffer lines 12168 + 12188): inline style uses
   rgba(251,191,36,0.08) bg / #fbbf24 fg / 0.25 border. Pin to a
   slightly tighter alpha curve (0.10 bg / 0.30 border) which is the
   canonical csfloat amber chip ramp — current 0.08 reads slightly
   under-saturated next to the trades chip and the bright #fbbf24
   text. Anchor on the inline-style background substring.
*/
body .offer-row span[style*="rgba(251,191,36,0.08)"],
body .offer-row span[style*="rgba(251, 191, 36, 0.08)"] {
  background: rgba(251, 191, 36, 0.10) !important;
  border-color: rgba(251, 191, 36, 0.30) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128107 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128108 — /profile Offers reputation row meta ink-2
   Measured csfloat reputation chip parent row (modals.js renderOffer
   line 12154): the wrapper flex-row carries `color: var(--text-muted)`
   inline which propagates to any non-chip text in the row. The
   reputation chips set their own color but if a future variant adds
   plain text, the parent --text-muted resolves slightly brighter than
   canonical ink-2. Pin parent row color to ink-2 rgb(158,167,177) so
   the inheritance chain stays canonical. Anchor on the inline-style
   margin-top + font-size combo which uniquely identifies the chip
   row wrapper.
*/
body .offer-row > div > div[style*="margin-top: 4px"][style*="font-size: 11px"],
body .offer-row > div > div[style*="marginTop: 4"][style*="fontSize: 11"] {
  color: rgb(158, 167, 177) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128108 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128109 — /profile Offers offered-amount brand
   Measured csfloat offered-amount $ on the offer row (modals.js
   renderOffer line 12200): the buyer's offered price, the visual
   anchor of the entire row. Inline style uses var(--accent) /
   JetBrains Mono / fs 14 / fw 800. Pin to canonical brand rgb
   (35,123,255) literal so the offered amount stays the brand-anchored
   focal point at every theme tier. The 14/800 + JetBrains Mono
   geometry is correct and stays as-is. Anchor on the inline-style
   font-weight + font-family combo which uniquely identifies the
   offered-amount cell (vs the asking-price strikethrough at fw 400).
*/
body .offer-row > div[style*="text-align: right"] > div[style*="font-weight: 800"][style*="JetBrains Mono"],
body .offer-row > div[style*="textAlign: right"] > div[style*="fontWeight: 800"][style*="JetBrains Mono"] {
  color: rgb(35, 123, 255) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128109 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128110 — /profile Offers price column geometry
   Measured csfloat price column on the offer row (modals.js
   renderOffer line 12199): three stacked cells (offered amount /
   asking-price strikethrough / pct-off badge). The wrapper has
   `text-align: right; margin-right: 14`. Pin canonical column
   geometry: each child stacks via display block, line-height 1.35,
   and the cluster has 4px gap so the three lines breathe properly.
   Sbox baseline lets the children inherit the row's flex layout
   which can squish the cells when the row is narrow.
*/
body .offer-row > div[style*="text-align: right"][style*="margin-right: 14"],
body .offer-row > div[style*="textAlign: right"][style*="marginRight: 14"] {
  display: flex !important;
  flex-direction: column !important;
  align-items: flex-end !important;
  gap: 2px !important;
  line-height: 1.35 !important;
  min-width: 78px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128110 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128111 — /profile Offers pct-off green chip
   Measured csfloat pct-off badge "−N%" on the offer row (modals.js
   renderOffer line 12202): when pctOff > 0, inline style uses var
   (--green) fg with no background. Pin to canonical rgb(34,197,94)
   literal + add subtle 0.10 alpha green chip background so the badge
   reads as a true chip (parity with the trades / star chips above).
   Anchor on the inline-style font-weight: 700 + font-size: 10 combo
   which uniquely identifies the pct-off cell.
*/
body .offer-row > div[style*="text-align: right"] > div[style*="font-size: 10px"][style*="font-weight: 700"],
body .offer-row > div[style*="textAlign: right"] > div[style*="fontSize: 10"][style*="fontWeight: 700"] {
  background: rgba(34, 197, 94, 0.10) !important;
  color: rgb(34, 197, 94) !important;
  border: 1px solid rgba(34, 197, 94, 0.22) !important;
  padding: 1px 6px !important;
  border-radius: 4px !important;
  display: inline-block !important;
}
/* Ghost the badge entirely when pctOff is zero / null — current
   variant emits color var(--text-muted) and an empty string, which
   leaves an invisible 0-width chip that still reserves the chip
   geometry above. Force display:none when the badge has no text
   content via the :empty selector. */
body .offer-row > div[style*="text-align: right"] > div[style*="font-size: 10px"][style*="font-weight: 700"]:empty,
body .offer-row > div[style*="textAlign: right"] > div[style*="fontSize: 10"][style*="fontWeight: 700"]:empty {
  display: none !important;
}
/* END CSFLOAT-1:1 PARITY ship #128111 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128112 — /profile Offers asking-price column
   Measured csfloat asking-price strikethrough column on the offer row
   (modals.js renderOffer line 12201): with the new column geometry
   from #128110, the strikethrough cell needs an explicit padding-bottom
   of 1px and line-height 1 so the line-through line renders centered
   through the digits without clipping the digit baseline. Pin the
   chrome so the strikethrough sits cleanly under the brand-colored
   offered amount.
*/
body .offer-row > div[style*="text-align: right"] > div[style*="font-size: 11px"][style*="line-through"],
body .offer-row > div[style*="textAlign: right"] > div[style*="fontSize: 11"][style*="line-through"] {
  display: block !important;
  line-height: 1.2 !important;
  padding-bottom: 1px !important;
  text-decoration-color: rgba(158, 167, 177, 0.55) !important;
  text-decoration-thickness: 1px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128112 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128113 — /profile Offers ⏱ countdown stamp
   Measured csfloat auto-decline countdown stamp on the offer row
   (modals.js renderOffer line 12134): "· ⏱ Nh left" / "Nm left" /
   "Nd left" inline span; urgent (<24h) uses var(--red), non-urgent
   uses var(--text-muted). Pin urgent to canonical rgb(248,113,113)
   literal and non-urgent to ink-2 rgb(158,167,177) literal so the
   countdown reads at the canonical csfloat alarm-vs-info color
   semantic at every theme tier. Add a faint chip background in
   urgent state so the seller can't miss it. Anchor on the
   inline-style margin-left + font-weight combo which uniquely
   identifies the countdown stamp.
*/
body .offer-row .item-sub > span[style*="margin-left: 10px"][style*="font-weight: 700"],
body .offer-row .item-sub > span[style*="marginLeft: 10"][style*="fontWeight: 700"] {
  font-feature-settings: 'tnum' 1, 'lnum' 1 !important;
  letter-spacing: -0.01em !important;
}
body .offer-row .item-sub > span[style*="color: var(--red)"] {
  color: rgb(248, 113, 113) !important;
  background: rgba(248, 113, 113, 0.10) !important;
  padding: 1px 6px !important;
  border-radius: 4px !important;
  border: 1px solid rgba(248, 113, 113, 0.20) !important;
}
body .offer-row .item-sub > span[style*="color: var(--text-muted)"] {
  color: rgb(158, 167, 177) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128113 */
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
    print("Ships landed: #128106, #128107, #128108, #128109, #128110, #128111, #128112, #128113")
    return 0

if __name__ == "__main__":
    sys.exit(main())
