"""Atomic append of CSFLOAT 1:1 parity ships #128500-#128505 — header logo anchor
geometry (leftmost element of top nav).

Live measured against csfloat.com (signed-in session, viewport 1440)
2026-05-08 via mcp__playwright__browser_navigate + mcp__playwright__browser_evaluate.

CSFLOAT GROUND TRUTH (geometry only, NOT image content — sboxmarket has its
own brand mark and we do NOT copy csfloat's PNG/SVG):

  .toolbar (Angular host) — height 70, padding 15 (uniform), display flex,
                            align-items center, gap 25, bg rgba(27,29,36,0.8)
    a.logo                  — 40x40 hard-pinned (CSS literal width:40px;
                              height:40px), display block, padding 0, margin 0,
                              border-radius 0, background transparent
                              cursor pointer, transition: all (no defined hover
                              effect — the anchor stays visually identical on
                              hover; only the cursor flips to pointer)
      img                   — 40x40 (object-fit fill), border-radius 0,
                              filter none

  Logo anchor sits at x=15 from the toolbar's content edge (toolbar pad 15
  → logo flush against pad). First nav button starts at x=140 from viewport
  origin (with toolbar at x=60), giving a 25px gap from logo right edge
  (115) to first route-button left edge (140) — this 25 IS the toolbar's
  flex `gap`, NOT a per-element margin.

SBOXMARKET CURRENT STATE (live measured 2026-05-08 against http://localhost:8080):

  .nav             — 70h, bg rgb(21,23,28)            ✔ matches
  .nav-inner       — padding 0 18px, gap 16, ai center
                       → toolbar pad needs 15 (csfloat is 15 uniform), gap 25
  .nav-logo        — 40x40 box, padding 0, margin 0   ✔ box matches (ship #9)
                     transition includes color/bg/border/opacity (extra surface)
  .nav-logo-icon   — STILL 28x28 inside the 40-box   ✗ csfloat is 40x40 flush
                     border-radius 6px                ✗ csfloat is 0
                     drop-shadow filter applied       ✗ csfloat has no filter
  .nav-logo-text   — display none                     ✔ (ship #9)
  .nav-logo-live   — display none                     ✔ (ship #9)
  Logo→first nav-link gap = 311 - 283 = 28           ✗ csfloat is 25

Old ship #9 sized `.nav-logo > svg` (direct-child SVG) which DOES NOT MATCH
the actual JSX — the SVG is wrapped in a `.nav-logo-icon` div, so the old
40px rule never applied. The icon kept its 28x28 default. This batch fixes
the inner-icon geometry, the toolbar gap, the toolbar pad, and pins an
explicit no-effect hover (csfloat's logo anchor has zero hover affordance).

CRITICAL legal-safety: this ship modifies ONLY layout values (px sizes,
padding, margin, gap, transition, hover behavior). The actual brand mark
SVG (the isometric crate inside .nav-logo-icon) is sboxmarket-original and
remains untouched. We do NOT swap, mirror, or trace csfloat's logo asset.

Ship numbers #128500+ to stay above all prior ranges and leave headroom.

NO Docker. APPEND-ONLY at end of design.css. Atomic tempfile + os.replace.
Mirror to build/ so a running gradle bootRun picks up without rebuild.
"""

import os, sys, tempfile

CSS = """
/* =====================================================================
   CSFLOAT-1:1 PARITY ships #128500-#128505 — HEADER LOGO ANCHOR GEOMETRY
   csfloat.com header (live measured 2026-05-08 viewport 1440):
     a.logo       — 40x40 hard-pinned, padding 0, no border-radius,
                    no filter, no hover effect (cursor only)
     img inside   — 40x40 flush (no inner padding shrink)
     toolbar      — padding 15 uniform, flex gap 25 between siblings
   sboxmarket old ship #9 fixed the OUTER 40-box but missed:
     .nav-logo-icon kept 28x28 (with border-radius 6 + drop-shadow)
     .nav-inner kept padding 18 + gap 16 (csfloat is 15 / 25)
     no explicit no-effect hover lock
   This batch APPENDS corrections — only geometry, NOT logo image
   content. Sboxmarket retains its own original brand mark inside the
   icon slot.
   ===================================================================== */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128500 — pin .nav-logo-icon to 40x40 flush
   csfloat .logo > img is 40x40 with object-fit fill, sitting flush
   inside the 40-box anchor (zero inner padding). Sbox .nav-logo-icon
   was 28x28 — leaves 6px of empty padding on every side, making the
   logo glyph look 30% smaller than csfloat's. Pin to 40x40 to match
   the visual weight of the leftmost nav element.
*/
nav.nav .nav-logo .nav-logo-icon {
  width: 40px !important;
  height: 40px !important;
}
nav.nav .nav-logo .nav-logo-icon img,
nav.nav .nav-logo .nav-logo-icon svg {
  width: 40px !important;
  height: 40px !important;
  display: block !important;
}
/* END CSFLOAT-1:1 PARITY ship #128500 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128501 — strip .nav-logo-icon border-radius
   csfloat img inside .logo has computed border-radius 0px. Sbox icon
   was 6px which clipped the corners of our isometric crate against
   the dark nav. Strip to 0 so the crate's stroke renders intact.
*/
nav.nav .nav-logo .nav-logo-icon {
  border-radius: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128501 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128502 — strip .nav-logo-icon drop-shadow
   csfloat .logo and its img both compute filter:none. Sbox .nav-logo-icon
   was applying drop-shadow(oklch(0.55 0.18 250 / 0.4) 0 0 8px) which
   produced an 8px blue halo around the crate that csfloat's flat logo
   never has. Strip filter for parity.
*/
nav.nav .nav-logo .nav-logo-icon {
  filter: none !important;
}
/* END CSFLOAT-1:1 PARITY ship #128502 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128503 — pin no-effect hover on .nav-logo
   csfloat .logo computed transition is `all` but the anchor has NO
   defined hover (no opacity dim, no background, no scale, no filter).
   Cursor flips to pointer and that is it. Sbox .nav-logo inherited a
   color/bg/border/opacity transition from the generic link surface —
   on hover the icon's drop-shadow color would shift through the
   transition, which csfloat never does. Lock the hover to a no-op
   so we match csfloat's static-on-hover behavior exactly.
*/
nav.nav .nav-logo,
nav.nav .nav-logo:hover,
nav.nav .nav-logo:focus,
nav.nav .nav-logo:focus-visible,
nav.nav .nav-logo:active {
  background: transparent !important;
  opacity: 1 !important;
  transform: none !important;
  filter: none !important;
}
nav.nav .nav-logo:hover .nav-logo-icon,
nav.nav .nav-logo:focus .nav-logo-icon,
nav.nav .nav-logo:focus-visible .nav-logo-icon,
nav.nav .nav-logo:active .nav-logo-icon {
  filter: none !important;
  transform: none !important;
  opacity: 1 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128503 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128504 — toolbar inner padding 15 uniform
   csfloat .toolbar uses padding:15px (uniform, all four edges). Sbox
   .nav-inner uses padding:0 18px which leaves 18px on the left edge
   instead of csfloat's 15. Pin .nav-inner padding to 15px uniform
   for the leftmost-anchor positioning to match (logo lands at
   container_left + 15 instead of container_left + 18).
*/
nav.nav .nav-inner {
  padding: 15px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128504 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128505 — toolbar flex gap 25
   csfloat .toolbar uses gap:25px between flex children — the gap
   between logo and the first nav route-button is exactly that 25.
   Sbox .nav-inner uses gap:16px which collapses the logo→first-link
   spacing to 28 (40 logo + ~16 gap minus inherited margin) instead
   of csfloat's 25. Pin gap to 25 for parity with csfloat's nav rhythm.
*/
nav.nav .nav-inner {
  gap: 25px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128505 */
"""

target = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"
mirror = r"C:\Users\WW\Desktop\sboxmarket\build\resources\main\static\css\design.css"

with open(target, 'rb') as f:
    f.seek(-3072, os.SEEK_END)
    tail = f.read().decode('utf-8', errors='replace')
if 'ship #128500' in tail:
    print('ALREADY APPENDED - ABORT')
    sys.exit(0)

def atomic_append(path, payload):
    tmpfd, tmpname = tempfile.mkstemp(suffix='.append', dir=os.path.dirname(path))
    try:
        with open(path, 'rb') as orig, os.fdopen(tmpfd, 'wb') as out:
            while True:
                buf = orig.read(1 << 20)
                if not buf:
                    break
                out.write(buf)
            out.write(payload.encode('utf-8'))
        os.replace(tmpname, path)
    except Exception:
        if os.path.exists(tmpname):
            try: os.remove(tmpname)
            except: pass
        raise

atomic_append(target, CSS)
print('APPENDED', len(CSS), 'bytes -> src/main/.../design.css')

if os.path.exists(mirror):
    atomic_append(mirror, CSS)
    print('MIRRORED', len(CSS), 'bytes -> build/resources/main/.../design.css')

print('SHIPS #128500-#128505 LANDED')
