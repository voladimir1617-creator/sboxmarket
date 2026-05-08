"""Atomic append of CSFLOAT 1:1 PARITY ships #128888-#128890 — collapse the
.nav-inner wrapper margin/padding so the logo lands at the csfloat-canonical
x=75 (=60 outer + 15 nav-pad).

Live re-measured 2026-05-08 against http://localhost:8080/ AFTER ships
#128880-#128887 landed: the .nav element correctly has padding 15px and the
logo has margin-right 25px etc. — but the LOGO STILL SITS AT x=240 not x=75.
Inspection of nav children showed sboxmarket has an INTERMEDIATE .nav-inner
flex wrapper between nav.nav and .nav-logo:

  nav.nav { padding 15px } → starts kids at viewport+15+60(outer)=75
  └ div.nav-inner { margin: 0 150px; padding: 15px; gap: 25px }
                                   ^^^                ^^^
                                   pushes 150 right   adds another 15 inset
    └ .nav-logo  → ends up at x=60+15+150+15=240 (off by +165px)

To restore csfloat's logo.x = 75 (where 60 comes from sboxmarket's outer
container and 15 from nav padding), the .nav-inner wrapper needs to be
collapsed:
  - margin: 0 150px → margin: 0
  - padding: 15px → padding: 0
  - gap: 25px → 0 (the .nav-logo's margin-right 25px from #128883
       handles the logo→links gap; the .nav-links' gap 25px from #128884
       handles the inter-link gap; the .nav-inner doesn't need its own
       gap because its only direct children are .nav-logo + .nav-links
       + .nav-right and the spacing between those is handled by margins
       on the inner items, not by the wrapper's flex gap)

Also resetting:
  - .nav-inner display: flex → KEEP flex (needed for nav-right to push
       right with margin-left:auto, and for align-items:center across
       the row).
  - .nav-inner align-items: stretch → center (vertical centering)
  - .nav-inner width: now flex:1 of nav so it spans the remaining width
       between the 15px nav padding edges.

Note that the .nav.nav padding remains 15px (from ship #128881) — the
combined effect is logo at x = 60(outer container) + 15(nav-pad) +
0(nav-inner margin) + 0(nav-inner padding) = 75. PERFECT MATCH.

NEW SHIPS APPENDED:

  #128888  .nav-inner margin/padding/gap ZERO out. Lock with !important
           so any later ship can't accidentally re-add the 150px
           horizontal margin or 15px padding.

  #128889  .nav-inner flex parity: display flex, flex-direction row,
           align-items center, flex 1 1 auto, min-width 0, width 100%.
           These re-anchor the wrapper as a transparent passthrough
           that just lays its kids out horizontally.

  #128890  .nav-right left-margin-auto guard: csfloat's right cluster
           (search bar + icons + user chip) sits at the FAR right of the
           toolbar via margin-left:auto. Sboxmarket .nav-right was
           previously positioned by grid-template-columns "auto" 3rd
           track; now that we've switched .nav to flex (#128882),
           explicitly add margin-left: auto to .nav-right so it pushes
           to the right edge of .nav-inner. Keep .nav-right's existing
           internal gap rules (gap 4px etc) — only change the
           positioning.
"""

import os, sys, tempfile, shutil

CSS_PATH = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"

APPEND = r"""

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128888 — .nav-inner wrapper geometry collapse.
   Live re-measured 2026-05-08: after ships #128880-#128887 landed, the
   .nav element correctly has padding 15px but the logo still sits at
   x=240 instead of csfloat's x=75. Root cause: an intermediate
   .nav-inner flex wrapper between .nav and .nav-logo carries
   margin: 0 150px AND padding: 15px AND gap: 25px which together
   account for the 165px offset (150 left margin + 15 left padding =
   165). Collapse all three to 0 so the wrapper becomes a transparent
   passthrough and the logo lands flush with the .nav padding edge.
   The 25px gap between logo and first nav-link is owned by .nav-logo's
   margin-right 25px from ship #128883; the 25px inter-link gap is owned
   by .nav-links' flex gap 25px from ship #128884. The wrapper itself
   needs no spacing of its own. */
.nav-inner,
.nav > .nav-inner,
nav.nav .nav-inner {
  margin: 0 !important;
  padding: 0 !important;
  gap: 0 !important;
  border: 0 none transparent !important;
  background: transparent !important;
  background-color: transparent !important;
  box-shadow: none !important;
}
/* END CSFLOAT-1:1 PARITY ship #128888 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128889 — .nav-inner flex re-anchor. Now
   that the wrapper has 0 margin/padding/gap (#128888), pin its layout
   to a flex passthrough that fills the available .nav width with
   align-items:center for vertical centering of all kids (logo, links,
   right cluster). Also pin width 100% + flex:1 so the wrapper expands
   to fill the .nav padding-box and the .nav-right cluster
   (margin-left:auto on its own) can push to the far right edge. */
.nav-inner,
.nav > .nav-inner,
nav.nav .nav-inner {
  display: flex !important;
  flex-direction: row !important;
  align-items: center !important;
  flex: 1 1 auto !important;
  width: 100% !important;
  min-width: 0 !important;
  max-width: none !important;
  height: auto !important;
}
/* END CSFLOAT-1:1 PARITY ship #128889 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128890 — .nav-right margin-left:auto guard.
   Csfloat's right-side cluster (currency picker, search, icons, user
   chip) sits at the far right of the toolbar via the natural flex
   "push to end" idiom: margin-left:auto on the cluster. Sboxmarket
   .nav-right was previously positioned by grid-template-columns
   "auto" 3rd track in the .nav grid layout; ship #128882 switched
   .nav to flex which BROKE that positioning (the cluster would now
   sit immediately to the right of .nav-links instead of pushing to
   the toolbar's right edge). Explicit margin-left:auto restores the
   right-aligned cluster. Keep all existing internal styles
   (gap 4px between icons, etc) — only the wrapper's positioning
   changes. */
.nav-right,
.nav > .nav-right,
nav.nav .nav-right,
.nav > .nav-inner > .nav-right,
.nav-inner > .nav-right {
  margin-left: auto !important;
  flex: 0 0 auto !important;
  flex-shrink: 0 !important;
  align-self: center !important;
}
/* END CSFLOAT-1:1 PARITY ship #128890 */

"""

with open(CSS_PATH, "rb") as f:
    existing = f.read()

if b"#128888" in existing and b"#128890" in existing:
    print("Already appended ships #128888-#128890. Skipping.")
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

print(f"Appended ships #128888-#128890. Old size: {len(existing)} bytes. New size: {len(new_blob)} bytes.")
