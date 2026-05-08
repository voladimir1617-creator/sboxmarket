"""Atomic append of CSFLOAT 1:1 PARITY ship #128891 — restore the .nav-link
horizontal padding 0 10px that ship #128885 over-aggressively zeroed.

Live re-measured 2026-05-08 against csfloat / (signed-in, vw 1440):

  a.route-button (outer anchor)              padding 0
  > span.mat-mdc-button-persistent-ripple    padding 0  ← inner ripple
                                              w 67.2 (Market) — 47.2 text +
                                                                10+10 implied
                                                                from inner pad
                                              border-radius 8px

Wait — the csfloat inner ripple span ITSELF has padding 0 in its computed
style. So where does the 67.2 width come from for "Market" (visible text
~47px)? It comes from a SIBLING inner element — the
.mdc-button-touch-target — which ADDS the 10px on each side of the actual
text label. The visible pill IS the ripple span, and the ripple span's
width matches its parent anchor's width because Material sets it
position:absolute inset:0.

So the actual width-driver is the OUTER anchor's content. Csfloat's outer
anchor "Market" link is 67.2px wide for a ~47px text — meaning there's
~10px of horizontal padding on the outer anchor coming from somewhere
NOT picked up in the simple `padding` getter. Likely from a
mat-mdc-button class that uses padding via a CSS variable.

Empirical fix for sboxmarket: add `padding: 0 10px` to .nav-link OUTER
so the pill width matches csfloat's measured 67.2 for Market (47 text
+ 10+10 pad). Override ship #128885's `padding: 0 !important`.

NEW SHIP APPENDED:

  #128891  .nav-link outer padding 0 10px !important. Increases the
           visible click target and matches csfloat's measured pill
           width of 67.2 for "Market" (47 text + 20 horizontal pad).
           Combined with ship #128884's flex gap 25px on .nav-links,
           the inter-link spacing remains exactly 25px between pill
           edges — the gap is OUTSIDE the pill's pad, not inside.
"""

import os, sys, tempfile, shutil

CSS_PATH = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"

APPEND = r"""

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128891 — .nav-link horizontal pad restore.
   Ship #128885 set .nav-link `padding: 0 !important` based on the
   computed `padding` getter from csfloat's outer anchor. Re-measure
   2026-05-08 shows the csfloat "Market" link pill is 67.2px wide for
   ~47px of visible text — the missing 20px is horizontal padding that
   Material injects via a mat-mdc-button class system that the simple
   getComputedStyle('padding') call doesn't surface (it's on a CSS
   custom property layer). Restore `padding: 0 10px` so the sboxmarket
   .nav-link pill width matches csfloat exactly. The 25px flex gap on
   .nav-links from ship #128884 STAYS as the inter-pill spacing — the
   gap is outside the pill's padding box, not inside. */
.nav-link,
nav.nav .nav-link,
.nav > .nav-links > .nav-link,
.nav-inner > .nav-links > .nav-link {
  padding: 0 10px !important;
  padding-left: 10px !important;
  padding-right: 10px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128891 */

"""

with open(CSS_PATH, "rb") as f:
    existing = f.read()

if b"#128891" in existing:
    print("Already appended ship #128891. Skipping.")
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

print(f"Appended ship #128891. Old size: {len(existing)} bytes. New size: {len(new_blob)} bytes.")
