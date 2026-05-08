"""Atomic append of CSFLOAT 1:1 parity ships #128560-#128566 — REAL signed-in
homepage page-level chrome.

Live measured against csfloat.com (signed-in session, viewport 1440)
2026-05-08 via mcp__playwright__browser_navigate + mcp__playwright__browser_evaluate.

CSFLOAT GROUND TRUTH ( signed-in https://csfloat.com/ → redirects to /search,
viewport 1440x900):

  body                      — padding 0px 60px       (60-px page gutter is
                                                      body-level, NOT inside
                                                      a wrapper)
                              background-color rgb(21, 23, 28)
                              font-family Roboto, "Helvetica Neue", sans-serif
                              font-size 16px
                              margin 0
                              color rgb(255, 255, 255)

  app-root                  — width = viewport - 120 (e.g. 1320 at 1440vw)
                              x = 60 (matches body padding)
                              padding 0, margin 0
                              background transparent

  .wrapper > .header-container > .header > app-header
                            — 70px tall, full bleed inside the gutter
                              padding 0, margin 0, bg transparent
                              (the toolbar inside paints rgba(27,29,36,0.8))

  .wrapper > .content       — starts at y=70 (immediately under header)
                              padding 0, margin 0, bg transparent
                              full bleed (1320w in 1440vw)

  app-market-search         — top of the content stack on `/`
                              starts at y=85 (15-px gap below header)
                              composed of:
                                .skin-bar-container  (38px tall, 15px top-margin)
                                app-search           (starts y=123)

  .skin-bar-container       — 38h, margin: 15px 0 0
                              full-bleed inside the 60-px gutter

  No hero. No featured-rail. No promo callouts. No market-grid section
  header. No marketing landing of any kind.
  The bare `/` URL on csfloat is functionally identical to `/search` —
  the user always lands on the marketplace grid.

  .footer (at end of .wrapper) renders as a 0-height empty slot on the
  homepage. SiteFooter content is NOT shown on `/`.

SBOXMARKET CURRENT STATE (verified via grep + design.css read 2026-05-08):

  body                      — padding 0 (NO horizontal gutter)            ✗
                              font-family 'Geist', ui-sans-serif, …       ✗ (csfloat: Roboto)
                              font-size 14px                              ✗ (csfloat: 16px)
                              background var(--bg) (oklch-derived dark)   ≈ matches BG token

  Home route renders:
    h('section.csfloat-home-hero', ...)                                    ✗ csfloat has none
      .csfloat-home-hero-inner padding: 80px 32px 88px                    ✗ ~250px tall hero
      .csfloat-home-hero-stack stacked feature cards                      ✗ none on csfloat
    SiteFooter (always rendered, always visible)                          ✗ csfloat home: hidden

  No body-level 60-px gutter — the marketplace grid sits flush against
  the viewport edge, where on csfloat it's inset 60px on each side.

ROUTE DETECTION:

  router.js / app.js do NOT set a body class or data-attr per route. Sbox
  identifies the home route uniquely by:
    1. .site-root does NOT carry the .full-page-mode class
       (FULL_PAGE_ROUTES list excludes 'home' / 'market')
    2. .csfloat-home-hero is rendered as a direct child of .site-root
       (only on routeName === 'home')

  We use Chrome's :has() selector to scope home-route rules to
  `body:has(.site-root:not(.full-page-mode) .csfloat-home-hero)` so the
  rules ONLY fire when the hero is present in the DOM (i.e. on `/`).
  This avoids breaking /market, /profile, etc. without any JS source
  change.

CORRECTIONS APPENDED (CSS-only, !important, NO JS source change):

  #128560 — body padding 0 60px on the home route (real csfloat gutter)
  #128561 — body bg pin to rgb(21, 23, 28) (token literal, not oklch)
  #128562 — body font-family Roboto stack + 16px size + #fff color
  #128563 — hide .csfloat-home-hero on the home route (csfloat has none)
  #128564 — hide .homepage-trust on the home route (csfloat has none)
  #128565 — hide SiteFooter on the home route (csfloat .footer is 0h)
  #128566 — top-of-content gap = 15px before grid (csfloat .content
            has app-market-search starting at y=85 = header_h(70) + 15)

CRITICAL legal-safety: this ship modifies ONLY layout / token values
(padding, background swatch, font stack, top-gap) and applies
`display:none` to sboxmarket-original sections that csfloat does not
ship. We do NOT copy csfloat's image content, JS, copy, or any
proprietary assets. The hero/trust/footer JSX stays in app.js — it's
just hidden on this one route to match the measured chrome.

Ship numbers #128560+ to stay above ship #128547 (latest) with headroom.

NO Docker. APPEND-ONLY at end of design.css. Atomic tempfile + os.replace.
Mirror to build/ so a running gradle bootRun picks up without rebuild.
"""

import os, sys, tempfile

CSS = """
/* =====================================================================
   CSFLOAT-1:1 PARITY ships #128560-#128566 — HOME ROUTE PAGE CHROME
   Real signed-in csfloat.com `/` (live measured 2026-05-08, viewport
   1440):
     body         padding 0 60px, bg rgb(21,23,28), Roboto 16px #fff
     app-root     1320w (= viewport - 120), x=60
     header       70h
     content      starts y=70
     app-market-search       starts y=85   (15-px gap below header)
       .skin-bar-container   38h, margin 15px 0 0
       app-search            starts y=123
     NO HERO, NO FEATURED RAIL, NO TRUST SECTION, NO FOOTER on `/`.
   Sboxmarket diverges by:
     - no body horizontal gutter
     - 'Geist' 14px (csfloat is Roboto 16px)
     - renders csfloat-home-hero on `/`
     - renders SiteFooter on every route
   APPEND below pulls page chrome to the measured csfloat baseline.

   Route scoping: app.js does NOT set a per-route body class, but the
   home route is the only one that renders `.csfloat-home-hero` as a
   child of `.site-root:not(.full-page-mode)`. We scope all rules with
   the :has() selector below so they only fire on `/`. /market,
   /profile, /wallet etc. remain untouched.
   ===================================================================== */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128560 — home route body horizontal gutter
   csfloat body computes padding: 0px 60px. The 60-px page gutter is
   body-level, not nested inside a wrapper. Sbox body has no horizontal
   padding so the marketplace grid sits flush against the viewport
   edges, where csfloat insets the entire app shell by 60px each side.
*/
html body:has(.site-root:not(.full-page-mode) > .csfloat-home-hero) {
  padding-left: 60px !important;
  padding-right: 60px !important;
}
@media (max-width: 900px) {
  html body:has(.site-root:not(.full-page-mode) > .csfloat-home-hero) {
    padding-left: 16px !important;
    padding-right: 16px !important;
  }
}
/* END CSFLOAT-1:1 PARITY ship #128560 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128561 — body BG = literal rgb(21, 23, 28)
   csfloat body backgroundColor computed as rgb(21, 23, 28). Sbox uses
   var(--bg) which is oklch-derived (~0.13 0.012 260) and resolves to a
   slightly different swatch under the browser's color profile. Pin the
   exact rgb literal so the page background matches csfloat byte-for-
   byte under any color profile.
*/
html body:has(.site-root:not(.full-page-mode) > .csfloat-home-hero) {
  background-color: rgb(21, 23, 28) !important;
  background-image: none !important;
}
/* END CSFLOAT-1:1 PARITY ship #128561 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128562 — body font Roboto/16/white
   csfloat body computes:
     font-family Roboto, "Helvetica Neue", sans-serif
     font-size   16px
     color       rgb(255, 255, 255)
   Sbox body uses Geist 14px var(--ink). At body level this only
   affects elements that explicitly inherit (rare since most surfaces
   pin px). Where it matters: tooltip text, loose copy, and the bare
   page that doesn't override fontSize. Pin the measured 16px + Roboto
   + #fff exactly on the home route so the base matches csfloat's
   signed-in body.
*/
html body:has(.site-root:not(.full-page-mode) > .csfloat-home-hero) {
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 16px !important;
  color: rgb(255, 255, 255) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128562 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128563 — hide .csfloat-home-hero on `/`
   Real csfloat `/` redirects to /search and shows ONLY: header + 15px
   gap + skin-bar + grid. There is NO marketing hero, NO stacked
   feature card cluster, NO headline copy. Sbox renders a 700-line
   `csfloat-home-hero` section as the first child of HomePage — hide
   it on the home route so the measured chrome reads through. The JSX
   stays in app.js (zero JS source change) so the hero can be
   re-enabled later if the spec changes; this ship just blanks it
   visually to match csfloat's bare-grid landing.
*/
html body .site-root:not(.full-page-mode) > section.csfloat-home-hero {
  display: none !important;
}
/* END CSFLOAT-1:1 PARITY ship #128563 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128564 — hide .homepage-trust on `/`
   The `.homepage-trust` callout (fee calculator + marketing copy) is
   gated `routeName !== 'home'` in app.js so it doesn't render on `/`,
   but if the gate is ever flipped the section would still violate
   csfloat parity (csfloat has zero marketing on `/`). Defensive hide
   so any future re-render still respects the bare-grid baseline.
*/
html body .site-root:not(.full-page-mode):has(> section.csfloat-home-hero) section.homepage-trust {
  display: none !important;
}
/* END CSFLOAT-1:1 PARITY ship #128564 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128565 — hide SiteFooter on `/`
   csfloat `/` renders an EMPTY .footer slot (height 0). The
   marketplace grid scrolls to the very bottom of the document with no
   footer chrome. Sbox SiteFooter (links + branding band) shows on
   every route including home. Hide on the home route so the document
   bottom matches csfloat's measured 0-height footer slot.
*/
html body .site-root:not(.full-page-mode):has(> section.csfloat-home-hero) > footer.site-footer,
html body .site-root:not(.full-page-mode):has(> section.csfloat-home-hero) > .site-footer {
  display: none !important;
}
/* END CSFLOAT-1:1 PARITY ship #128565 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128566 — 15px gap header→content on `/`
   csfloat layout chain:
     header 70h ends at y=70
     .content starts at y=70 (no margin)
     app-market-search starts at y=85 (gap of 15)
     .skin-bar-container computed margin: 15px 0 0
   The 15-px gap below the header is achieved by the FIRST child of
   .content carrying margin-top:15. With the hero (#128563) and trust
   (#128564) hidden, the next visible child of .site-root on `/` is
   the marketplace surface itself. Pin its top margin to the measured
   15px so the grid lands at exactly the y-coordinate csfloat lands
   its skin-bar at.

   The hero is `display:none` (still in DOM) so we use sibling combinator
   to add the margin to whatever comes next. We also fall through to
   the first .market-page / .grid container so the rule fires whether
   the hero is hidden or fully removed in a later JS-source change.
*/
html body .site-root:not(.full-page-mode):has(> section.csfloat-home-hero) > section.csfloat-home-hero ~ *:not(section.csfloat-home-hero):not(section.homepage-trust):not(footer):first-of-type {
  margin-top: 15px !important;
}
html body .site-root:not(.full-page-mode):has(> section.csfloat-home-hero) > .market-page,
html body .site-root:not(.full-page-mode):has(> section.csfloat-home-hero) > .market,
html body .site-root:not(.full-page-mode):has(> section.csfloat-home-hero) > main {
  margin-top: 15px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128566 */
"""

target = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"
mirror = r"C:\Users\WW\Desktop\sboxmarket\build\resources\main\static\css\design.css"

# Idempotency guard — only append if this ship batch hasn't landed yet.
with open(target, 'rb') as f:
    f.seek(-4096, os.SEEK_END)
    tail = f.read().decode('utf-8', errors='replace')
if 'ship #128560' in tail:
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

print('SHIPS #128560-#128566 LANDED')
