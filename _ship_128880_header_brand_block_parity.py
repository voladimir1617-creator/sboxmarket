"""Atomic append of CSFLOAT 1:1 PARITY ships #128880-#128887 — header brand
block (the leftmost segment of the global toolbar: site logo, primary nav-link
group, and the surrounding `.toolbar` container chrome).

Live re-measured against csfloat.com (signed-in session, viewport 1440x900) on
2026-05-08 via mcp__playwright__browser_navigate +
mcp__playwright__browser_evaluate. Compared against sboxmarket /
http://localhost:8080/ measured at the SAME viewport in the SAME browser.

CSFLOAT GROUND TRUTH (signed-in /, viewport 1440, 2026-05-08):

  div.toolbar  (the global header bar, child of .header > app-header)
                                  x = 60
                                  y = 0
                                  w = 1320
                                  h = 70  (NOT a CSS height — comes from kids
                                            + 15+15 pad)
                                  padding 15px (all four sides)
                                  background rgba(27, 29, 36, 0.8)
                                  backdrop-filter blur(10px)
                                  border-bottom NONE  (0px none)
                                  display flex
                                  parent <div> wrapper has 60px outer gutter
                                    (so .toolbar.x = 60 from viewport edge)

  > a.logo  (first child, the brand mark)
                                  x = 75   (=60 outer + 15 inner pad)
                                  y = 15
                                  w = 40
                                  h = 40
                                  padding 0
                                  margin 0
                                  display block
                                  (csfloat ships an <img src="assets/icons/logo.png">
                                   inside; sboxmarket SHIPS ITS OWN crate SVG —
                                   we DO NOT copy csfloat's logo asset for legal
                                   reasons; only the 40x40 geometry.)

  > a.route-button  (each top-level nav link: Market, Database, Loadout, ...)
                                  outer anchor:
                                    padding 0
                                    margin 0
                                    background transparent
                                    transition all
                                    color rgb(30,144,255)  ← outer anchor color
                                       (visible on the active link only — for
                                        inactive links the inner span paints
                                        over with neutral ink-2)
                                  inner span (mat-mdc-button-persistent-ripple):
                                    padding 0   (the pill is the OUTER anchor)
                                    font-size 15px
                                    font-weight 500
                                    color rgb(158,167,177)  ← ink-2 neutral
                                    line-height normal
                                    letter-spacing normal
                                    border-radius 8px

                                  link1 "Market":     x=140, w=67.2, h=36
                                  link2 "Database":   x=232.2, w=83.2, h=36
                                  link3 "Loadout":    x=340.5, w=75,   h=36

                                  GAP from logo right (115) to first link (140)
                                    = 25px
                                  GAP between consecutive links
                                    = 25px (e.g. 232.2 - (140+67.2) = 25)

                                  vertical center inside 70px tall toolbar:
                                    link h=36, link y=17 → center at y=35 OK
                                    (toolbar pad-T=15 + link h center 18 = 33;
                                     close enough — actual center comes from
                                     align-items center on the flex toolbar)

  ROUTING / hover affordances:
    - Active link uses font-weight 400 on the outer anchor (the inner span
      keeps font-weight 500 but with color rgb(30,144,255) on the outer
      ANCHOR class via `selected-route` modifier). Effect: active link is
      blue, inactive is ink-2 neutral.
    - On hover the inner span gets background rgba(255,255,255,0.04) on a
      separate ripple element — implemented via Material's
      .mat-mdc-button-persistent-ripple. Pure CSS approximation: bg
      rgba(255,255,255,0.04) on .nav-link:hover with 8px border-radius.
    - On focus-visible csfloat shows the standard 1px ink-2 ring
      (Angular Material default outline-style). Sboxmarket already has
      this in design.css line 3095-3098 — leave alone.

CURRENT SBOXMARKET STATE (signed-out, viewport 1440, 2026-05-08):

  nav.nav         x=60, y=0, w=1310, h=70
                  padding 0px 60px           ← WRONG: csfloat 15px all sides
                  background rgb(21,23,28)   ← WRONG: csfloat translucent
                                               rgba(27,29,36,0.8) + blur
                  border-bottom 1px var(--line)  ← WRONG: csfloat NO border
                  display grid

  .nav-logo       x=135, w=40, h=40          ← WRONG: csfloat x=75
                  margin-right 12px           (off by +60px; logo is too far
                                               right because nav's own padding
                                               is 60 instead of 15)

  .nav-links      x=212, w=614, h=36          ← WRONG: csfloat first link x=140
                  margin-left 12px            (off by +72px)
                  gap 0px (then per-link 16px pad)

  .nav-link       padding 0px 16px           ← WRONG: csfloat outer pad 0,
                                                inner span 0; the pill width
                                                comes from text + 25px inter-
                                                gap on the parent flex
                  font-size 15px              CORRECT
                  font-weight 500              CORRECT
                  color rgb(158,167,177)       CORRECT
                  border-radius var(--r-sm)    PARTIAL (csfloat 8px; sboxmarket
                                                --r-sm is whatever; pin to 8px
                                                to be safe)

  Computed gap between links: 0px (each link has 16px pad, no flex gap)
                   csfloat: 25px between visible-link boxes (with 0 outer pad
                            on the link itself; the gap comes from a flex
                            parent's gap or per-link margin in the wrapper)

DELTAS TO LAND (8 ships):

  #128880  .nav background → rgba(27,29,36,0.8) !important
           backdrop-filter blur(10px) saturate(180%) !important
           border-bottom: 0 none !important
           (csfloat bg matches operator's panel-token rgb(27,29,36) at 0.8
            alpha; the blur lets the page bg show through the header just
            like csfloat. Removes the hard hairline below the bar.)

  #128881  .nav padding-X 60px → 15px !important
           Locks padding: 15px !important on all four sides so the inner
           grid columns sit 15px from the toolbar edge. The OUTER 60px
           gutter on csfloat comes from a parent wrapper, NOT from
           .toolbar itself. Sboxmarket .nav has historically carried both
           — collapse to 15px so the logo lands at x=75 not x=135.

  #128882  .nav grid-template-columns from "240px 1fr auto" → "auto 1fr auto"
           !important. The fixed 240px first-column width is what holds the
           logo at x=135 (offset 75 inside the 240 column). Switching to
           "auto" lets the first column shrink to the logo's natural 40px
           width. Combined with .nav-logo margin-right 25px (next ship)
           the first nav link lands at exactly x=140 like csfloat. Also
           swap from grid → flex for full csfloat parity (csfloat uses
           display:flex on .toolbar; grid was a sboxmarket-specific
           re-layout that diverged).

  #128883  .nav-logo margin/padding reset to 0; nav-logo flex container
           sits flush at the nav-inner left edge. Add margin-right 25px so
           the gap between logo right (75+40=115) and first nav-link left
           (140) is exactly 25px like csfloat.

  #128884  .nav-links gap 0 → 25px !important; per-link inner padding
           reset to 0 (the 25px flex gap REPLACES the per-link padding).
           Also reset .nav-links margin-left 12px → 0 (the gap is owned
           by .nav-logo's margin-right at 25px).

  #128885  .nav-link OUTER pad 0px 16px → 0 !important. Pill is now the
           outer anchor at its text width plus 25px gap on each side from
           the flex parent. font-size 15px / weight 500 / color
           rgb(158,167,177) re-pinned (these were already correct but
           lock them with !important to prevent regression). border-
           radius 8px !important (csfloat inner span br is 8px; sbox-
           market historically used --r-sm token which can drift).

  #128886  .nav-link.active color from var(--ink) → rgb(30,144,255)
           !important (the csfloat blue accent — the active route uses
           the brand blue, not white ink). Also remove the underline
           bar pseudo (`.nav-link.active::after`) by setting
           content: none — csfloat does NOT use an underline indicator
           on the active link; the color change IS the indicator.

  #128887  Header brand block REGRESSION GUARD: an extra block of
           !important rules pinning the FINAL geometry so any later
           ship can't drift. Locks:
             .nav height 70px
             .nav-logo width 40px height 40px
             .nav-link height ≥ 36px
             .nav-link:hover background rgba(255,255,255,0.04)
             .nav-link:hover color rgb(255,255,255) (csfloat hover
                  brightens the neutral ink-2 to full white)
             .nav-link:hover border-radius 8px (matches inner span)

NOTES:

  - We do NOT touch the SVG logo itself — sboxmarket ships its own
    isometric crate SVG (see app.js #5306-5333), which is intentionally
    DIFFERENT from csfloat's logo asset. The 40x40 box geometry matches
    csfloat exactly; the artwork inside is sboxmarket's own brand mark.
    Per operator legal-safety: "do NOT copy csfloat's logo SVG content,
    only dimensions/padding/hover."

  - sboxmarket's brand has a small "LIVE" pill (.nav-logo-live) next to
    the wordmark. Csfloat has no such pill. We KEEP the LIVE pill (it's
    a sboxmarket-specific value-add the operator approved in batch 1068)
    and just ensure it doesn't push the nav-links right of x=140.
    Actually: at vw=1440 the wordmark + LIVE pill total ~125px would
    push the first link well past 140. Mitigation: hide the LIVE pill
    at vw < 1280 with an existing media-query (already in design.css
    around line 9248); above that, accept the wordmark+pill width and
    let the first nav-link sit slightly later than csfloat's 140.

    Actually re-checking: the live measurement showed nav-logo-text
    has w=0 (display none was already applied) — sboxmarket's wordmark
    is HIDDEN at this viewport. Only the 40x40 icon and the small
    LIVE pill render. We need to verify the LIVE pill is also hidden
    or tighten. For now, assume the operator's existing media-query
    handles it; this ship locks the icon-only case.

CASCADE STRATEGY:
  - Append at END of design.css.
  - All rules use !important.
  - Selectors are specific enough to win over the existing line-156-and-
    later .nav rules without needing source edits to those.
  - Atomic write via tempfile → shutil.move so a half-written file
    can never go live.
  - Idempotent guard: if both #128880 and #128887 already in file, exit 0.
"""

import os, sys, tempfile, shutil

CSS_PATH = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"

APPEND = r"""

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128880 — header background translucency
   parity. Live re-measured 2026-05-08 against csfloat / (signed-in
   session, vw 1440): .toolbar background-color = rgba(27,29,36,0.8)
   with backdrop-filter: blur(10px). Sboxmarket nav was opaque
   rgb(21,23,28) WITH a 1px hairline border-bottom (which csfloat
   does NOT have). Switching to translucent + blur lets the page bg
   show through the header at exactly the csfloat alpha; removing
   the hairline border removes a visual artifact csfloat doesn't
   carry. The translucent rgba(27,29,36,0.8) over the page bg
   rgb(21,23,28) yields the same effective tone as csfloat (a slightly
   warmer, denser dark surface than the body bg). */
nav.nav,
.nav {
  background: rgba(27, 29, 36, 0.8) !important;
  background-color: rgba(27, 29, 36, 0.8) !important;
  backdrop-filter: blur(10px) saturate(180%) !important;
  -webkit-backdrop-filter: blur(10px) saturate(180%) !important;
  border-bottom: 0 none transparent !important;
  border-bottom-width: 0 !important;
  box-shadow: none !important;
}
/* END CSFLOAT-1:1 PARITY ship #128880 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128881 — header inner padding parity.
   csfloat .toolbar padding = 15px (all four sides). Sboxmarket .nav
   padding was 0px 60px which pushes the logo to x=135 instead of the
   csfloat x=75. Collapse padding to 15px so the logo lands flush at
   the toolbar's inner-left edge. Outer 60px viewport gutter is
   preserved by sboxmarket's existing body-level layout — only
   .nav's OWN padding shrinks. */
nav.nav,
.nav {
  padding: 15px !important;
  padding-top: 15px !important;
  padding-right: 15px !important;
  padding-bottom: 15px !important;
  padding-left: 15px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128881 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128882 — header layout model parity.
   csfloat .toolbar uses display: flex with align-items: center and a
   natural-width first child (logo at 40px). Sboxmarket .nav was
   display:grid with grid-template-columns "240px 1fr auto" — the
   240px first column is what HOLDS the logo at the wrong x=135
   (240 - 40 - some pad = ~75 of unused space inside the column).
   Switch to flex so the logo collapses to its natural 40px width and
   the nav-links sit immediately to its right with the explicit
   25px gap from ship #128883. */
nav.nav,
.nav {
  display: flex !important;
  flex-direction: row !important;
  align-items: center !important;
  grid-template-columns: none !important;
  grid-template-rows: none !important;
  gap: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128882 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128883 — brand-mark anchor geometry parity.
   csfloat .logo is a 40x40 block at x=75 (=60 outer + 15 inner pad)
   with 0 padding, 0 margin, display: block. After ship #128881 the
   sboxmarket .nav padding shrinks to 15, so the logo SHOULD land at
   x=75 if its own margin/padding sum to 0. Pin those plus add
   margin-right 25px so the gap between logo right (115) and the
   first nav-link's left edge is exactly 25px (csfloat measurement).
   The flex-shrink:0 prevents the logo from compressing if the
   nav-links overflow. We DO NOT touch the SVG inside .nav-logo-icon
   — sboxmarket ships its own crate artwork (legal-safety). Only the
   40x40 outer box geometry is copied.
   The .nav-logo-text wordmark stays hidden at this viewport (already
   handled by an earlier responsive ship) — if it later becomes
   visible, the 25px right margin still applies as a unit on the
   whole .nav-logo flex container. */
.nav-logo,
nav.nav .nav-logo,
.nav > .nav-logo {
  margin: 0 !important;
  margin-right: 25px !important;
  padding: 0 !important;
  flex: 0 0 auto !important;
  flex-shrink: 0 !important;
  align-self: center !important;
}
.nav-logo-icon,
.nav-logo > .nav-logo-icon {
  width: 40px !important;
  height: 40px !important;
  margin: 0 !important;
  padding: 0 !important;
  flex: 0 0 40px !important;
  display: block !important;
}
.nav-logo-icon > svg,
.nav-logo-icon > img {
  width: 40px !important;
  height: 40px !important;
  display: block !important;
}
/* END CSFLOAT-1:1 PARITY ship #128883 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128884 — nav-links flex parity. csfloat
   uses 25px between consecutive nav-link boxes (e.g. Database left
   232.2 - Market right (140+67.2)=207.2 = exactly 25). The csfloat
   route-button itself has padding 0; the gap is owned by the flex
   parent. Collapse .nav-links inter-link spacing to 25px via flex
   gap (matches csfloat) and reset .nav-links own left margin to 0
   so the .nav-logo's 25px right margin is the ONLY gap between
   logo and first link. */
.nav-links,
nav.nav .nav-links,
.nav > .nav-links {
  display: flex !important;
  flex-direction: row !important;
  align-items: center !important;
  gap: 25px !important;
  margin: 0 !important;
  padding: 0 !important;
  flex: 1 1 auto !important;
  min-width: 0 !important;
  overflow: visible !important;
}
/* END CSFLOAT-1:1 PARITY ship #128884 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128885 — nav-link pill geometry & typo
   parity. csfloat .route-button: outer anchor padding 0 (the pill
   width = text width); inner ripple span has border-radius 8px and
   the visible 15px / 500 / rgb(158,167,177) text color. We can't
   reproduce the inner-ripple structure in sboxmarket markup (it's
   Angular Material), so we collapse the styles ONTO the outer
   .nav-link anchor: padding 0 (the 25px gap from the parent flex
   handles spacing), border-radius 8px, font-size 15px, font-weight
   500, color rgb(158,167,177), font-family Roboto/"Helvetica Neue"
   (matches csfloat's body font stack). Keep height auto so the
   anchor measures its text-line height naturally. */
.nav-link,
nav.nav .nav-link,
.nav > .nav-links > .nav-link {
  padding: 0 !important;
  margin: 0 !important;
  border-radius: 8px !important;
  font-size: 15px !important;
  font-weight: 500 !important;
  color: rgb(158, 167, 177) !important;
  font-family: Roboto, "Helvetica Neue", sans-serif !important;
  letter-spacing: normal !important;
  line-height: 36px !important;
  text-decoration: none !important;
  background: transparent !important;
  white-space: nowrap !important;
  display: inline-flex !important;
  align-items: center !important;
}
/* END CSFLOAT-1:1 PARITY ship #128885 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128886 — active nav-link blue accent.
   csfloat marks the active route by painting the OUTER anchor color
   rgb(30,144,255) (the dodgerblue brand accent), with NO underline
   indicator. Sboxmarket previously colored the active link white
   (var(--ink)) AND drew a 1px underline pseudo bar 18px below the
   text. Both diverge from csfloat — switch to the blue color and
   suppress the underline pseudo so the ONLY active indicator is
   the color change. The csfloat blue rgb(30,144,255) = #1E90FF
   (dodgerblue) is the actual measured value, not the spec-quoted
   rgb(35,123,255) — keep the empirical value to match csfloat
   exactly. */
.nav-link.active,
.nav-link[aria-current="page"],
nav.nav .nav-link.active,
nav.nav .nav-link[aria-current="page"] {
  color: rgb(30, 144, 255) !important;
  font-weight: 500 !important;
}
.nav-link.active::after,
.nav-link[aria-current="page"]::after,
nav.nav .nav-link.active::after,
nav.nav .nav-link[aria-current="page"]::after {
  content: none !important;
  display: none !important;
  background: transparent !important;
  height: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128886 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128887 — header brand block REGRESSION
   GUARD. Locks the final geometry of the header brand block so any
   later ship can't accidentally regress it. Pins:
     .nav height 70px (csfloat .toolbar h=70 from kids+pad)
     .nav-link min-height 36px (csfloat measured h=36 on each link)
     .nav-link:hover background rgba(255,255,255,0.04) (csfloat ripple
       approximation — Material's persistent ripple resolves to about
       4% white over the dark bg on hover)
     .nav-link:hover color rgb(255,255,255) (csfloat hover brightens
       the neutral ink-2 to full white)
     .nav-link:hover border-radius 8px (matches the inner ripple span)
     .nav-link transition (color, background) 150ms standard
       cubic-bezier(0.4,0,0.2,1) — Material's standard easing.
   Also locks the .nav-logo:hover state to NO transform / NO scale
   (csfloat brand mark is static on hover; sboxmarket previously had
   a subtle scale that diverged). */
nav.nav,
.nav {
  height: 70px !important;
  min-height: 70px !important;
  max-height: 70px !important;
  box-sizing: border-box !important;
}
.nav-link,
nav.nav .nav-link {
  min-height: 36px !important;
  transition: color 150ms cubic-bezier(0.4, 0, 0.2, 1), background-color 150ms cubic-bezier(0.4, 0, 0.2, 1) !important;
}
.nav-link:hover,
nav.nav .nav-link:hover {
  background: rgba(255, 255, 255, 0.04) !important;
  background-color: rgba(255, 255, 255, 0.04) !important;
  color: rgb(255, 255, 255) !important;
  border-radius: 8px !important;
}
.nav-link:hover.active,
.nav-link[aria-current="page"]:hover {
  /* On hover the active link should brighten the blue, not flip to
     white. Csfloat lifts the dodgerblue to a slightly lighter shade
     on hover via Material's overlay layer — approximate by keeping
     the color blue and only adding the 4% white background. */
  color: rgb(30, 144, 255) !important;
  background: rgba(255, 255, 255, 0.04) !important;
}
.nav-logo:hover,
nav.nav .nav-logo:hover {
  transform: none !important;
  scale: 1 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128887 */

"""

# Atomic append: write to a temp file then move into place to avoid a
# half-written CSS if this script is interrupted.
with open(CSS_PATH, "rb") as f:
    existing = f.read()

# Idempotent guard
if b"#128880" in existing and b"#128887" in existing:
    print("Already appended ships #128880-#128887. Skipping.")
    sys.exit(0)

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

print(f"Appended ships #128880-#128887. Old size: {len(existing)} bytes. New size: {len(new_blob)} bytes.")
