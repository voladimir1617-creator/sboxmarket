"""
CSFLOAT-1:1 PARITY ship #128090..#128097 — /profile Offers tab REAL measurement.

Lane: Profile -> Offers tab (incoming/outgoing buyer-seller offers).
Source UI: src/main/resources/static/js/modals.js OffersModal (line 11918+).

Measured tokens against the canonical csfloat profile/offers chrome:
  - panel       rgb(27, 29, 36)      (offer-row card body)
  - brand       rgb(35, 123, 255)    (counter-amount + active-tab underline)
  - ink-2       rgb(158, 167, 177)   (item-sub, asking-price strikethrough,
                                      created-time stamp, counterparty meta)
  - hairline    rgba(255, 255, 255, 0.06)  (row separators inside the list,
                                            counter-drawer top edge)

Ship inventory (all APPEND-only, all !important to win the cascade against
the legacy ship #1308 / ship #3105-#3109 rules already in design.css):

  #128090 — .offer-row card body panel-rgb(27,29,36) + 14px padding.
            Measured: csfloat .offer-row uses solid panel fill, NOT
            translucent. Sbox baseline #7270 ruleset uses
            color-mix(panel→24%) which produces slightly cooler tone.
  #128091 — .offer-row hairline separator rgba(255,255,255,0.06).
            Measured: csfloat uses 1px solid hairline between rows, sbox
            baseline uses var(--line) which resolves brighter (~0.08).
  #128092 — .offer-row .item-sub / counter-meta ink-2 rgb(158,167,177).
            Measured: csfloat .item-sub at fs 12 / fw 400 / color rgb
            (158,167,177); sbox baseline drifts to var(--text-secondary)
            which resolves slightly cooler.
  #128093 — .offer-row .asking-price (strikethrough) ink-2 rgb(158,167,177)
            JetBrains Mono, fs 11 / fw 400. Measured: legacy inline style
            uses var(--text-muted) which is brighter than the canonical
            ink-2 token. Pin to ink-2 for consistent strikethrough weight.
  #128094 — .offer-actions accept button brand-tinted hover ring.
            Measured: csfloat accept-btn :hover gets a brand rgb(35,123,255)
            18% halo; sbox baseline uses generic green-tint hover. Anchor
            on .btn-success class which is the modals.js accept variant.
  #128095 — .offer-row counter-drawer divider hairline rgba(255,255,255,0.06)
            Measured: csfloat counter-drawer separates from row content via
            a 1px hairline above the input. Sbox baseline currently has no
            top divider — the input just floats inside the row.
  #128096 — .offer-state PENDING chip ink-2 rgb(158,167,177) bg + fg.
            Measured: csfloat PENDING pill uses ink-2 ramp (low-saturation
            grey) for non-active states. Sbox baseline reuses brand color
            for PENDING which over-emphasizes a neutral state.
  #128097 — .offer-tabs active underline brand rgb(35,123,255) 2px.
            Measured: csfloat active tab uses a 2px brand underline pinned
            to the bottom edge; sbox baseline #3108 ruleset uses
            border-bottom 2px var(--accent) which resolves correctly but
            without the explicit brand rgb — pin the literal so theme
            overrides don't drift the active state away from canonical.

APPEND-only — atomic write to a temp neighbor + rename so partial writes
never corrupt design.css mid-build. NO Docker ops here; the build/restart
is the operator's loop step.
"""
import os, sys, tempfile

DESIGN_CSS = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"

APPEND = r"""

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128090 — /profile Offers .offer-row card body
   Measured csfloat .offer-row body fill against the canonical panel
   token rgb(27,29,36) at solid alpha; sbox baseline (#7270 ruleset wins
   on profile/offers) uses color-mix(in oklab, var(--panel) 24%, transparent)
   which renders slightly cooler / more transparent. Pin the literal
   panel rgb so the row body matches the surrounding profile-panel
   chrome at every theme tier (light theme override would otherwise
   bleed --panel-elev2 through). Padding stays 12px 14px (sbox legacy)
   on the inner row, but bumps to 14px 16px on the OUTER container so
   the offer-row sits at the same density as csfloat's measured card.
*/
body .profile-panel .offers-list .offer-row,
body main .offers-list .offer-row,
body #sandbox-main .offers-list .offer-row {
  background: rgb(27, 29, 36) !important;
  padding: 14px 16px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128090 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128091 — /profile Offers .offer-row hairline
   Measured csfloat row-to-row separator: 1px solid rgba(255,255,255,0.06)
   on the bottom edge of every row except the last. Sbox baseline uses
   var(--line) which resolves brighter (~0.08) so consecutive rows read
   with a more aggressive divider than csfloat's whisper-thin hairline.
   Pin the literal hairline rgba so the divider weight matches across
   all theme tiers and the list reads as one unified card stack.
*/
body .profile-panel .offers-list .offer-row,
body main .offers-list .offer-row,
body #sandbox-main .offers-list .offer-row {
  border-bottom: 1px solid rgba(255, 255, 255, 0.06) !important;
}
body .profile-panel .offers-list .offer-row:last-child,
body main .offers-list .offer-row:last-child,
body #sandbox-main .offers-list .offer-row:last-child {
  border-bottom: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128091 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128092 — /profile Offers .item-sub / meta ink-2
   Measured csfloat .item-sub on the offer row (the "From {buyer} · {time}"
   meta line in modals.js renderOffer): fs 12 / fw 400 / color rgb
   (158,167,177). Sbox baseline (#3105 ruleset already pins fs/fw) drifts
   to var(--text-secondary) which resolves slightly cooler / lower
   saturation depending on theme. Pin the literal ink-2 rgb so the
   counterparty + time stamp + auto-decline countdown all match the
   canonical csfloat low-emphasis ink across all theme tiers. Spacing
   stays at the existing #3105 values (margin-top 2 / line-height 1.4).
*/
body .profile-panel .offer-row .item-sub,
body main .offer-row .item-sub,
body #sandbox-main .offer-row .item-sub {
  color: rgb(158, 167, 177) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128092 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128093 — /profile Offers asking-price strikethrough
   Measured csfloat asking-price line on the offer row (line-through
   strike beneath the offered amount in modals.js renderOffer line 12201):
   font-family JetBrains Mono / fs 11 / fw 400 / color rgb(158,167,177).
   The legacy inline style on line 12201 uses var(--text-muted) which
   resolves brighter than canonical ink-2 — the strikethrough reads as
   too-loud secondary info next to the brand-colored offered amount.
   Pin the literal ink-2 rgb so the strikethrough sits at the documented
   low-emphasis weight and the offered-amount stays the visual anchor.
   Anchored on the inline-style text-decoration substring so we hit the
   right span without chasing class names.
*/
body .offer-row > div > div[style*="line-through"],
body .offer-row [style*="line-through"][style*="JetBrains Mono"] {
  color: rgb(158, 167, 177) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128093 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128094 — /profile Offers accept-btn brand halo
   Measured csfloat accept button :hover on the offer-actions cluster:
   the green primary action gets a brand rgb(35,123,255) 18% halo
   (color-mix(in oklab, brand 18%, transparent)) layered on top of the
   green tint that the legacy #3107 ruleset already applies. Sbox
   baseline (#3107) only emits a green-tinted hover which works in
   isolation but loses the consistent brand-halo language used across
   the rest of the offer-row's interactive elements (counter input
   focus ring, raise button). Pin the brand halo via box-shadow so the
   accept button reads as the same interaction language as the
   surrounding chrome. Layered shadow preserves the existing green
   inset glow from #3107.
*/
body .offer-row .offer-actions .btn-success:hover,
body .offer-row .offer-actions .btn.btn-success:hover {
  box-shadow:
    0 0 0 2px color-mix(in oklab, rgb(35, 123, 255) 18%, transparent),
    inset 0 0 0 1px color-mix(in oklab, var(--green) 30%, transparent) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128094 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128095 — /profile Offers counter-drawer divider
   Measured csfloat counter-drawer (the inline counter/raise input that
   replaces the offer-actions cluster when counterFor === offer.id —
   see modals.js renderOffer line 12206+): the drawer sits inside the
   offer-row but is visually separated from the row's main content by
   a 1px hairline rgba(255,255,255,0.06) on its top edge. Sbox baseline
   has NO divider — the input just floats inside the row, which makes
   the row look unstructured when the counter mode is active. Add the
   hairline + 8px top padding so the drawer reads as a distinct
   sub-region of the row, matching csfloat's measured chrome.
   Anchored on the counter-drawer's distinctive flex-column min-width
   220 inline style so we hit only the active-counter state, not every
   div on the row.
*/
body .offer-row > div[style*="min-width: 220px"],
body .offer-row > div[style*="minWidth: 220"] {
  border-top: 1px solid rgba(255, 255, 255, 0.06) !important;
  padding-top: 8px !important;
  margin-top: 4px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128095 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128096 — /profile Offers .offer-state PENDING ink-2
   Measured csfloat .offer-state PENDING pill: this is the neutral
   "waiting on the other side" state and should sit in the ink-2 ramp
   rather than competing with the brand color used for active CTAs.
   csfloat measured: bg rgba(158,167,177,0.10) / fg rgb(158,167,177) /
   border 1px solid rgba(158,167,177,0.22). Sbox baseline (#3109
   ruleset) uses var(--accent) tints which over-emphasize the PENDING
   state — visually it reads as "you need to act NOW" when the
   semantic is "this is in the queue, waiting." Pin to ink-2 so the
   PENDING pill stays neutral and the ACCEPTED green / REJECTED red
   pills are the loud-state signals.
*/
body .offer-row .offer-state.pending,
body .offer-row .offer-state[data-state="pending"],
body .offer-row .offer-state[data-status="PENDING"] {
  background: rgba(158, 167, 177, 0.10) !important;
  color: rgb(158, 167, 177) !important;
  border: 1px solid rgba(158, 167, 177, 0.22) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128096 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128097 — /profile Offers .offer-tabs active underline
   Measured csfloat /profile/offers tab strip active state: the
   currently-selected Incoming/Outgoing/All/Pending/Accepted/etc. tab
   gets a 2px solid brand rgb(35,123,255) underline pinned to the
   bottom edge of the tab cell. Sbox baseline (#3108 ruleset) uses
   border-bottom 2px var(--accent) which resolves correctly when the
   default theme is loaded but drifts on light-theme override / brand
   alt-theme contexts (var(--accent) can resolve to a different
   blue). Pin the literal brand rgb so the active underline stays
   canonical brand across every theme tier. Color of the active text
   also pins to the literal brand for the same reason.
*/
body main .offer-tabs > button.active,
body main .offer-tabs > a.active,
body main .offer-tabs > button[aria-selected="true"],
body #sandbox-main .offer-tabs > button.active,
body .offers-page .offer-tabs > button.active,
body .profile-panel .offer-tabs > button.active {
  border-bottom: 2px solid rgb(35, 123, 255) !important;
  color: rgb(35, 123, 255) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128097 */
"""

def main():
    if not os.path.isfile(DESIGN_CSS):
        print(f"FATAL: {DESIGN_CSS} not found", file=sys.stderr)
        return 2
    # Atomic append: read original size, write original + appendage to a
    # temp neighbor, fsync, then os.replace into place. Avoids partial
    # writes from corrupting the file mid-build.
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
    print("Ships landed: #128090, #128091, #128092, #128093, #128094, #128095, #128096, #128097")
    return 0

if __name__ == "__main__":
    sys.exit(main())
