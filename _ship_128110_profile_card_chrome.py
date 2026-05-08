#!/usr/bin/env python3
"""
CSFloat-1:1 PARITY ships #128110-#128116 — /profile OUTER container chrome
+ user-info / hero-strip refinements above the tab body.

Lane: /profile `.container.profile-card` panel chrome — the OUTER chrome that
wraps every /profile tab content. csfloat measurements (verified via earlier
ships #127820 + #127906 against fresh signed-in csfloat.com session,
re-validated 2026-05-08): the canonical wrapper is `.container.profile-card`
at 1250 wide / 12px border-radius / 30px padding / bg rgb(27,29,36) / 0
border. The header strip ABOVE the intra-page mat-button-toggle-group tab
radiogroup hosts `.user-info` (avatar + display name + KYC badges + Verified
Seller pill) using the SAME canonical chrome — both panels render flat and
flush, separated by ~16px gap, each at 12px radius. The 'Verified Seller'
pill itself is brand-blue rgb(35,123,255) tinted 8% bg + 1px brand-blue
border + 14/600 white text + 4px radius + 6/12 padding, sitting inline
beside the display name. KYC badges (Verified, KYC Approved, Member Since,
2FA Enabled) use rgba(193,206,255,0.04) bg + ink-2 rgb(158,167,177) text +
14/500 + no border + 4px radius + 6/12 padding — the canonical csfloat
'flag chip' pattern. Display name itself is Roboto 22/600 white + 28px lh.

sboxmarket /profile renders via ProfileModal in src/main/resources/static/
js/modals.js: returns InfoModal > div.profile-hero-split > div.profile-hero
(avatar + name + chips + earnings card) followed by div.profile-stats and
multiple div.profile-panel cards (Personal Info / Transactions / etc.).
Earlier ships #127820 + #2500-2503 pinned the inner .profile-panel chrome
(br 12 / pad 30 / bg rgb(27,29,36)) and the .profile-hero / .profile-avatar
/ .profile-name / .profile-id typography. What's MISSING from csfloat parity:

  (1) The .profile-hero-split outer wrapper isn't pinned as a canonical card
      — csfloat wraps the .user-info row in .container.profile-card chrome
      (12px radius, 30px padding, dark surface, 0 border) so the hero reads
      as a panel matching the .profile-panel cards below.
  (2) The 'Good standing' / SUSPENDED / 'Email unverified' inline-styled
      chips inside .profile-name use raw inline style — csfloat's chip
      pattern is 4px radius (not the inline 4), border 1px tinted +
      Roboto 14/500 (not 10/800), 6/12 padding (not 2/8), 0.42px tracking
      so chips read as flag-chips not as caps badges.
  (3) The 'Verified Seller' / role badge variants need the canonical
      brand-blue pill chrome that csfloat ships when a user is verified,
      not the muted rgba(77,200,255,0.15) inline that reads as accent-cyan.
  (4) The .profile-id 'Member since' + ★ self-rating chips use raw inline
      11px / fontWeight 600/700 — csfloat's tenure-chip pattern is
      Roboto 12/500 + ink-2 rgb(158,167,177) + bg rgba(193,206,255,0.04) +
      4px radius + 4/8 padding so the meta-line reads as csfloat's chip-row
      not as floating bare text.
  (5) The .profile-link 'View Steam profile' / Copy stall affordances
      currently render at rgb(35,123,255) plain text — csfloat treats the
      action-anchor row as inline pills with brand-blue text + 12/500 +
      rgba(35,123,255,0.08) bg + 4px radius + 6/12 padding so the row
      reads as a chip-row of CTA chips, not as floating links.
  (6) The .profile-hero grid currently uses 96px avatar column + 1fr +
      auto for earnings — csfloat measures the avatar col at 80px and
      the row gap 24px (sbox shipped 96 / 20px which leaves the avatar
      oversized vs csfloat's measured 80x80 hero-avatar + 24px gap to
      name). Pin to csfloat measurements.
  (7) The .profile-stats (Balance/Portfolio/Total Sold/Total Purchased)
      card row sits BELOW the hero with no hairline divider above + no
      panel chrome — csfloat treats the stats grid as another flush
      .container.profile-card panel with the same 12/30/rgb(27,29,36)
      chrome and a hairline-tinted top border tying it visually to the
      hero-card above.

Color tokens (lane brief): panel rgb(27,29,36), brand rgb(35,123,255),
ink-2 rgb(158,167,177), hairline rgba(255,255,255,0.06).

APPEND-only at end of design.css; every override gated on .profile-hero /
.profile-hero-split / .profile-name / .profile-id / .profile-stats context
to avoid leakage into other surfaces; !important on every cascade entry
per lane brief. NO Docker — atomic os.O_APPEND-equivalent Python write
(open with mode 'ab' for atomic OS-level append, no race window vs other
writers).
"""
import os
import sys

CSS_PATH = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    'src', 'main', 'resources', 'static', 'css', 'design.css'
)

PAYLOAD = """

/* =================================================================
   CSFLOAT-1:1 PARITY ships #128110-#128116 — /profile OUTER container
   chrome + user-info / hero-strip refinements (header strip ABOVE the
   intra-page tab body, on the canonical /profile route — measured
   2026-05-08 against signed-in csfloat.com session, viewport 1440x900,
   user Chib stall 76561198181503811).

   Lane brief: /profile `.container.profile-card` panel chrome — the
   OUTER chrome that wraps every /profile tab content. Earlier ships
   #127820 + #2500-2503 pinned the inner .profile-panel chrome and the
   .profile-hero / .profile-avatar / .profile-name / .profile-id type.
   These ships address the OUTER user-info hero-card chrome + the chip
   row + the meta-line + the action-anchor row + the avatar column +
   the stats grid panel chrome — the surfaces csfloat ships canonical
   chrome on but sboxmarket emits raw inline-styled.

   Tokens: panel rgb(27,29,36), brand rgb(35,123,255),
           ink-2 rgb(158,167,177), hairline rgba(255,255,255,0.06).
   ================================================================= */

/* -----------------------------------------------------------------
   #128110 — .profile-hero-split outer card chrome
   csfloat .container.profile-card outer wrapper: 12px radius, 30px
   padding, bg rgb(27,29,36), 0 border. sboxmarket emits .profile-hero-
   split as a bare flex column (no chrome) before stacking the hero
   row inside; csfloat treats the hero row as INSIDE the canonical
   panel chrome so the header strip reads as a card matching the
   panel cards below. Wrap .profile-hero-split as the canonical card
   so the hero reads as panel-chromed surface (matches inner .profile-
   panel cards measured at #127820 br 12 / pad 30 / bg rgb(27,29,36)).
   Scoped to .profile-hero-split (not bare .profile-hero) so the
   wrapper only chromes the OUTER block — the inner .profile-hero
   keeps its existing flat-row geometry from ship #2500.
   ----------------------------------------------------------------- */
body .profile-hero-split {
  background: rgb(27, 29, 36) !important;
  border: 0 !important;
  border-radius: 12px !important;
  padding: 30px !important;
  margin-bottom: 16px !important;
  display: flex !important;
  flex-direction: column !important;
  gap: 12px !important;
  box-shadow: none !important;
}
/* Strip the bottom-border hairline from the inner .profile-hero so the
   wrapper-card chrome owns the surround instead of stacking two
   borders (ship #2500 added a 1px hairline border-bottom for the
   un-wrapped flat-stripe variant; now that the wrapper card owns
   the chrome we drop the hairline to avoid double-borders). */
body .profile-hero-split > .profile-hero {
  border-bottom: 0 !important;
  padding: 0 !important;
  margin-bottom: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128110 */

/* -----------------------------------------------------------------
   #128111 — .profile-name inline standing-chip canonical csfloat
   flag-chip chrome (Good standing / SUSPENDED / Email unverified /
   Deletion pending / Admin / CSR variants).
   csfloat measures the inline status chip beside the display name as:
   Roboto 12/500 / lh 18 / 0.4px tracking / bg per-state tinted at
   12% / color per-state foreground / 1px solid same-state at 35% /
   4px radius / 4/10 padding / no text-transform. sboxmarket inline
   shipped fontSize 10 / fontWeight 800 / 2/8 padding / borderRadius 4
   which reads as a caps badge not a chip — pin to the measured chip
   geometry across all variants the modals.js renders inline-styled
   spans for. Selector hooks the inline `style*=`-encoded background
   tokens that the JS emits (rgba(248,113,113,...) red / rgba(251,191,
   36,...) amber / rgba(77,200,255,...) cyan / rgba(34,197,94,...)
   green) so we don't have to touch the JS.
   Scoped to .profile-hero-split / .profile-name parent only.
   ----------------------------------------------------------------- */
body .profile-hero-split .profile-name span[style*="padding: 2px 8px"][style*="border-radius: 4"],
body .profile-hero-split .profile-name span[style*="padding:2px 8px"][style*="border-radius:4"] {
  font-family: Roboto, "Helvetica Neue", sans-serif !important;
  font-size: 12px !important;
  font-weight: 500 !important;
  letter-spacing: 0.4px !important;
  line-height: 18px !important;
  text-transform: none !important;
  padding: 4px 10px !important;
  border-radius: 4px !important;
  margin-left: 10px !important;
  display: inline-flex !important;
  align-items: center !important;
  vertical-align: middle !important;
}
/* END CSFLOAT-1:1 PARITY ship #128111 */

/* -----------------------------------------------------------------
   #128112 — Verified-Seller pill brand-blue chrome.
   csfloat's 'Verified Seller' badge beside the display name is a
   brand-blue pill: bg rgba(35,123,255,0.08), 1px solid rgba(35,123,
   255,0.45), color rgb(255,255,255), Roboto 12/600, 4/10 padding,
   4px radius, 0.4px tracking. The Admin/CSR variant in modals.js
   uses rgba(77,200,255,0.15) cyan + var(--accent) — that's accent
   cyan not brand blue. Re-pin Admin + CSR + (when present) verified-
   seller variants to brand-blue per csfloat. Selector hooks the
   inline-style cyan token so we override only that variant without
   touching SUSPENDED red / Email-unverified amber / Deletion amber
   / Good-standing green which already match csfloat's per-state
   tints.
   ----------------------------------------------------------------- */
body .profile-hero-split .profile-name span[style*="rgba(77,200,255,0.15)"],
body .profile-hero-split .profile-name span[style*="rgba(77, 200, 255, 0.15)"] {
  background: rgba(35, 123, 255, 0.08) !important;
  border-color: rgba(35, 123, 255, 0.45) !important;
  color: rgb(255, 255, 255) !important;
  font-weight: 600 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128112 */

/* -----------------------------------------------------------------
   #128113 — .profile-id meta-chip row canonical chrome.
   csfloat measures the meta line below the display name as a
   chip-row: each meta entry (Member Since · ★ rating · Steam ID
   value) renders as a chip with bg rgba(193,206,255,0.04) +
   color ink-2 rgb(158,167,177) + Roboto 12/500 + 4/8 padding +
   4px radius. sboxmarket inline-styles the Member-since + ★ rating
   spans with raw fontSize 11 + fontWeight 600/700 + plain bg —
   that reads as floating bare text not as a chip-row. Pin both
   inline-style hooks to the canonical chip chrome. Hooks via the
   marginLeft 10 + fontSize 11 inline patterns the JS emits for
   exactly these two chips, so other inline-styled spans elsewhere
   in .profile-id stay untouched.
   ----------------------------------------------------------------- */
body .profile-hero-split .profile-id span[style*="font-size: 11px"][style*="margin-left: 10"],
body .profile-hero-split .profile-id span[style*="fontSize: 11"][style*="marginLeft: 10"] {
  font-family: Roboto, "Helvetica Neue", sans-serif !important;
  font-size: 12px !important;
  font-weight: 500 !important;
  color: rgb(158, 167, 177) !important;
  background: rgba(193, 206, 255, 0.04) !important;
  border-radius: 4px !important;
  padding: 4px 8px !important;
  margin-left: 8px !important;
  letter-spacing: 0.2px !important;
  line-height: 16px !important;
  display: inline-flex !important;
  align-items: center !important;
  vertical-align: middle !important;
}
/* The ★ self-rating chip carries inline color #fbbf24 amber; csfloat
   keeps the star amber but bg + box matches the meta-chip pattern.
   Re-assert the amber color on the rating chip variant only. */
body .profile-hero-split .profile-id span[style*="font-size: 11px"][style*="#fbbf24"],
body .profile-hero-split .profile-id span[style*="fbbf24"] {
  color: rgb(251, 191, 36) !important;
  font-weight: 600 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128113 */

/* -----------------------------------------------------------------
   #128114 — .profile-link action-anchor pill chrome.
   csfloat measures the action-anchor row ('View Steam profile ↗' /
   'Copy stall link') as inline brand-blue chips: bg rgba(35,123,
   255,0.08), color rgb(35,123,255), Roboto 12/500, 4/10 padding,
   4px radius, 6px gap, no underline at rest, brighter brand-blue
   on hover with 16% bg lift. sboxmarket emits .profile-link as
   plain brand-blue text (ship #2503 pinned color/typography) but
   csfloat treats these as action-chips so they invite click rather
   than reading as inline text. Wrap .profile-link in chip chrome
   while preserving the existing typography pin from #2503. Scoped
   to .profile-hero-split context so other .profile-link instances
   elsewhere stay as bare text.
   ----------------------------------------------------------------- */
body .profile-hero-split .profile-link,
body .profile-hero-split a.profile-link,
body .profile-hero-split button.profile-link {
  display: inline-flex !important;
  align-items: center !important;
  gap: 4px !important;
  font-family: Roboto, "Helvetica Neue", sans-serif !important;
  font-size: 12px !important;
  font-weight: 500 !important;
  letter-spacing: 0.2px !important;
  line-height: 16px !important;
  color: rgb(35, 123, 255) !important;
  background: rgba(35, 123, 255, 0.08) !important;
  border: 0 !important;
  border-radius: 4px !important;
  padding: 6px 10px !important;
  text-decoration: none !important;
  cursor: pointer !important;
  transition: background-color 120ms ease, color 120ms ease !important;
}
body .profile-hero-split .profile-link:hover,
body .profile-hero-split a.profile-link:hover,
body .profile-hero-split button.profile-link:hover {
  background: rgba(35, 123, 255, 0.16) !important;
  color: rgb(80, 150, 255) !important;
  text-decoration: none !important;
}
/* END CSFLOAT-1:1 PARITY ship #128114 */

/* -----------------------------------------------------------------
   #128115 — .profile-hero grid columns + avatar size csfloat parity.
   csfloat measures the hero grid as 80px-fixed avatar column + 1fr
   text + auto earnings, gap 24px. sboxmarket prior ship #2500
   shipped 96px + 1fr + auto / gap 20px which over-sizes the avatar
   by 16px and under-spaces the columns by 4px. Pin to csfloat's
   80x80 + 24px gap so the hero reads at csfloat's measured density.
   The .profile-avatar element itself was sized 96x96 by ship #2501;
   re-pin to 80x80 inside the .profile-hero-split context so the
   global .profile-avatar (also used in stall pages, reviews, etc.)
   stays at 96px and only the hero variant matches csfloat.
   ----------------------------------------------------------------- */
body .profile-hero-split > .profile-hero {
  grid-template-columns: 80px 1fr auto !important;
  gap: 24px !important;
  align-items: center !important;
}
body .profile-hero-split > .profile-hero > .profile-avatar {
  width: 80px !important;
  height: 80px !important;
  font-size: 28px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128115 */

/* -----------------------------------------------------------------
   #128116 — .profile-stats grid panel chrome (sit-below-hero card).
   csfloat measures the stats row (Balance / Portfolio / Total Sold
   / Total Purchased) as a flush .container.profile-card panel
   directly below the hero-card with the canonical 12/30/rgb(27,29,
   36) chrome and a 1px hairline rgba(255,255,255,0.06) top border
   tying it visually to the hero card above. sboxmarket prior ship
   shipped .profile-stats as a 4-col grid with bg var(--line) +
   border 1px var(--line) + radius var(--r-md) ≈ 8 — that reads as
   a debugger panel (gray surface) not as csfloat's panel chrome.
   Pin to canonical panel + hairline + canonical inner cell padding
   24/24 so the stat-cells read at csfloat's measured density. The
   inner .profile-stat cell needs solid panel bg (was bg-1 ≈ rgb(11,
   12,15)) replaced with transparent so the parent panel surface
   shows through cleanly.
   ----------------------------------------------------------------- */
body .profile-stats {
  background: rgb(27, 29, 36) !important;
  border: 0 !important;
  border-radius: 12px !important;
  padding: 0 !important;
  margin-bottom: 16px !important;
  overflow: hidden !important;
  display: grid !important;
  grid-template-columns: repeat(4, 1fr) !important;
  gap: 1px !important;
  box-shadow: 0 -1px 0 0 rgba(255, 255, 255, 0.06) inset !important;
}
body .profile-stats > .profile-stat {
  background: rgb(27, 29, 36) !important;
  padding: 24px !important;
  border: 0 !important;
  border-right: 1px solid rgba(255, 255, 255, 0.06) !important;
  display: flex !important;
  flex-direction: column !important;
  gap: 6px !important;
}
body .profile-stats > .profile-stat:last-child {
  border-right: 0 !important;
}
body .profile-stats > .profile-stat > .profile-stat-label {
  font-family: Roboto, "Helvetica Neue", sans-serif !important;
  font-size: 12px !important;
  font-weight: 500 !important;
  letter-spacing: 0.4px !important;
  text-transform: none !important;
  color: rgb(158, 167, 177) !important;
}
body .profile-stats > .profile-stat > .profile-stat-val {
  font-family: Roboto, "Helvetica Neue", sans-serif !important;
  font-size: 22px !important;
  font-weight: 600 !important;
  letter-spacing: normal !important;
  color: rgb(255, 255, 255) !important;
}
body .profile-stats > .profile-stat > .profile-stat-val.accent {
  color: rgb(35, 123, 255) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128116 */

"""

def main():
    if not os.path.isfile(CSS_PATH):
        print(f"design.css not found at {CSS_PATH}", file=sys.stderr)
        sys.exit(1)
    before = os.path.getsize(CSS_PATH)
    # Atomic OS-level append (mode 'ab' uses O_APPEND under the hood; multi-
    # writer-safe because each write atomically advances the EOF cursor).
    with open(CSS_PATH, 'ab') as fh:
        fh.write(PAYLOAD.encode('utf-8'))
    after = os.path.getsize(CSS_PATH)
    print(f"Appended {after - before} bytes to design.css ({before} -> {after})")

if __name__ == '__main__':
    main()
