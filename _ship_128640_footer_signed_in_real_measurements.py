"""Atomic append of CSFLOAT 1:1 parity ships #128640-#128645 — site
footer chrome refined against the SIGNED-IN csfloat measurement (the
`<app-mini-footer>` shipped on every authed route — `/profile`, `/`,
`/db`, etc.).

Live measured 2026-05-08 against csfloat.com (signed-in session, viewport
1440x900) via mcp__playwright__browser_navigate + mcp__playwright__browser_evaluate.
Browser tab landed on `/profile` (the canonical signed-in surface where
csfloat consistently renders `<app-mini-footer>`).

CSFLOAT GROUND TRUTH — `<app-mini-footer> > .container` (the row at
the very bottom of every signed-in route, immediately below the page
content):

  app-mini-footer            display:inline (host), no own chrome
  div.footer (parent)        height 80, no bg, no padding
  .container (the actual row)
                             display:GRID, gap 40, padding 17px 5px,
                             margin: 17px 0 0,
                             border-top: 1px solid rgba(193,206,255,0.04),
                             max-width: NONE (full bleed inside .footer),
                             jc/align: normal/normal (default grid)

  Children (in DOM order, all sit in the grid):
    .copyright              "© CSFloat Inc. 2026. Not affiliated with Valve Corp."
                             font: 500 14px Roboto / "Helvetica Neue" / sans
                             color: rgb(158, 167, 177) (--ink-2 / token)
                             padding: 0, margin: 0, no border, display:block

    .links                  display:FLEX, gap 40, jc/align default
                             3 COLUMNS (NOT 4) — Resources / Tools / Company
                             column-gap 40, row-gap 40

    .social                 display:FLEX, flex-flow row nowrap, gap 15,
                             5 children (Discord, Twitter/X, GitHub, Steam,
                                          Chrome Extension)
                             — distinct from anon-/faq footer's 18px
                                gap; signed-in is 15px exact.

  PANEL CHROME — there is NONE.
  The signed-in mini-footer is a TRANSPARENT, full-bleed strip below the
  content. There is NO inner card with rgb(27,29,36) bg, NO 12px radius,
  NO 30px padding, NO mat-elev-2 shadow, NO 1337px max-width — the
  earlier ships #20003 + #20000 modeled the ANON `/faq` footer which
  csfloat ships with full panel chrome to compensate for the marketing-
  page tone. Signed-in users see a quiet inline strip with only a hairline
  separating it from the page content above.

  HAIRLINE — `rgba(193, 206, 255, 0.04)`.
  This is the canonical brand-blue-tinted hairline that csfloat uses
  EVERYWHERE on signed-in surfaces (toolbar separators, filter dividers,
  segment-control fill — see ship #19800-#19805 + #128520-#128525). The
  earlier ship #20008 explicitly STRIPPED border-top from the bottom-strip
  to match the no-border anon footer; signed-in csfloat puts that hairline
  BACK at the TOP of `.container` (separating it from page content).

SBOXMARKET CURRENT STATE (post ship #20000-#20009, anchored to anon-/faq):
  .site-footer:                  bg rgb(21,23,28), padding 30px 24px
                                   ✗ csfloat signed-in: bg transparent (or
                                     inherit from page bg), padding 0
  .site-footer-inner:            bg rgb(27,29,36), border-radius 12,
                                   padding 30, max-width 1337, mat-elev-2
                                   ✗ csfloat signed-in: bg transparent,
                                     border-radius 0, padding 17px 5px,
                                     max-width none, NO shadow, hairline
                                     border-top rgba(193,206,255,0.04)
  .site-footer-col gap-row:      column-gap 60 on >=1100
                                   ✗ csfloat signed-in: 40 (matches
                                     .container's 40 grid-gap)
  .site-footer-socials gap:      18
                                   ✗ csfloat signed-in: 15
  .site-footer-bottom margin-top: 30, padding 20px 30px 0
                                   ✗ csfloat signed-in: margin-top 17,
                                     padding 17px 5px, NO 30px gutter

SIX MEASURABLE CORRECTIONS LANDED (each its own ship per APPEND-only
rule; ships #20000-#20009 remain on disk and continue to apply — these
ships re-pin the values that diverge for signed-in fidelity):

  #128640 .site-footer outer chrome — bg transparent (inherit page bg-1),
          padding 0 (the .container handles its own gutter), border 0
  #128641 .site-footer-inner panel demote — bg transparent, border-radius
          0, padding 17px 5px, max-width none, box-shadow none — drop
          the anon "card" panel for the signed-in inline strip
  #128642 .site-footer-inner border-top hairline — 1px solid
          rgba(193, 206, 255, 0.04) at the TOP of the inner row,
          mirroring the brand-blue-tinted hairline csfloat ships on
          every signed-in surface
  #128643 .site-footer columns gap-row tighten — 40px column-gap
          on >=1100 (down from ship #20007's 60), matching the
          csfloat .container 40px grid-gap exactly
  #128644 .site-footer-socials row gap tighten — 15px (down from
          ship #20005's 18), matching csfloat .social row exactly
  #128645 .site-footer-bottom strip rhythm — margin-top 17 (was 30),
          padding 17px 5px (was 20px 30px 0), max-width none
          (was 1337) — the bottom legal strip now sits flush below the
          link rail at the same 17px rhythm csfloat's mini-footer uses
          (it carries the hairline above .container, not above the
          .legal sub-row).

All six ships re-pin against the EXACT computed values measured live
2026-05-08 from csfloat.com signed-in via Playwright getBoundingClientRect
+ getComputedStyle on `<app-mini-footer> > .container`. Ships 20000-9 (the
anon-footer ground truth) remain in place; these six append after them in
the cascade and override only the surfaces signed-in csfloat changes.
"""

import os, sys, tempfile

CSS = """
/* =====================================================================
   CSFLOAT-1:1 PARITY ships #128640-#128645 — SIGNED-IN footer chrome.
   Measured 2026-05-08 against csfloat.com (signed-in session, viewport
   1440x900) via Playwright getBoundingClientRect + getComputedStyle on
   `<app-mini-footer> > .container` rendered on /profile, /, /db.
   Earlier ships #20000-#20009 modeled the ANON /faq footer (the only
   footer csfloat shows to logged-out users). Signed-in users see a
   quieter `<app-mini-footer>` — transparent, full-bleed, hairline-
   separated, no panel chrome. These six append after #20000-#20009 in
   the cascade and re-pin only the surfaces that diverge.
   ===================================================================== */

/* CSFLOAT-1:1 PARITY ship #128640 — site-footer outer chrome (signed-in)
   measured: csfloat signed-in <app-mini-footer> ancestor `div.footer`
   has bg transparent, padding 0, no border. The footer simply inherits
   the page bg-1 (rgb(21, 23, 28)) — there is no decorative band around
   it. Earlier ship #20003 painted the outer footer rgb(21,23,28) to
   match the page bg, which is correct for the page-bg token but it
   ALSO added 30px 24px padding around the inner card. Signed-in
   csfloat has NO outer padding — the inner row .container does its own
   17px 5px gutter. Strip the outer 30px 24px padding so the strip sits
   flush at the bottom of the page like csfloat's signed-in mini-footer.
*/
html body footer.site-footer {
  background-color: transparent !important;
  background: transparent !important;
  padding: 0 !important;
  border: 0 none !important;
}
/* END CSFLOAT-1:1 PARITY ship #128640 */

/* CSFLOAT-1:1 PARITY ship #128641 — site-footer-inner panel demote
   measured: csfloat signed-in `<app-mini-footer> > .container` has
   bg transparent, border-radius 0, padding 17px 5px, max-width NONE
   (full bleed), NO box-shadow. Earlier ship #20003 layered a dark
   rgb(27,29,36) card with 12px radius, 30px padding, 1337 max-width,
   and the Material elevation-2 shadow stack inside the outer footer
   — that's the chrome csfloat uses on the ANON /faq landing page,
   not the signed-in mini-footer. Demote the panel to a transparent
   inline strip that picks up the page bg behind it.
*/
html body footer.site-footer .site-footer-inner {
  background-color: transparent !important;
  background: transparent !important;
  border-radius: 0 !important;
  padding: 17px 5px !important;
  max-width: none !important;
  margin: 0 !important;
  box-shadow: none !important;
}
/* END CSFLOAT-1:1 PARITY ship #128641 */

/* CSFLOAT-1:1 PARITY ship #128642 — site-footer-inner top hairline
   measured: csfloat signed-in `<app-mini-footer> > .container` has
   border-top: 1px solid rgba(193, 206, 255, 0.04). This is the
   canonical brand-blue-tinted hairline csfloat ships on EVERY signed-in
   surface (filter rails, segment-control fills, panel separators —
   see prior ships #19800-#19805 documenting the same token). Without
   this hairline, ship #128641's transparent strip blends invisibly
   into the page bg and the footer loses its visual separation from
   the content above. Pin the hairline to match csfloat exactly.
*/
html body footer.site-footer .site-footer-inner {
  border-top: 1px solid rgba(193, 206, 255, 0.04) !important;
  border-right: 0 none !important;
  border-bottom: 0 none !important;
  border-left: 0 none !important;
}
/* END CSFLOAT-1:1 PARITY ship #128642 */

/* CSFLOAT-1:1 PARITY ship #128643 — site-footer link rail column-gap
   measured: csfloat signed-in `.container` is grid with gap 40px and
   `.links` inside is flex with column-gap 40px. Earlier ship #20007
   pinned 60px column-gap on >=1100px to match the anon /faq footer
   (which spreads its 4 columns wider in the panel-card chrome).
   Signed-in csfloat tightens to 40px to match the .container grid-gap
   so the link rail reads as part of the same rhythm as the copyright
   and social-row neighbors. Re-pin to 40px on the wide breakpoint so
   the columns sit at the canonical signed-in spacing.
*/
@media (min-width: 1100px) {
  html body footer.site-footer .site-footer-inner {
    column-gap: 40px !important;
    gap: 40px !important;
  }
}
/* END CSFLOAT-1:1 PARITY ship #128643 */

/* CSFLOAT-1:1 PARITY ship #128644 — site-footer social row gap tighten
   measured: csfloat signed-in `.social` has display:flex, gap:15px,
   flex-flow:row nowrap, 5 children (Discord, Twitter/X, GitHub, Steam,
   Chrome Extension). Earlier ship #20005 pinned 18px gap to match the
   anon /faq footer which uses slightly more breathing room around the
   social row inside the panel card. Signed-in csfloat tightens to 15px
   exact (the inline strip context calls for tighter horizontal rhythm
   so the social SVGs read as a unit). Re-pin to 15px.
*/
html body footer.site-footer .site-footer-socials {
  gap: 15px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128644 */

/* CSFLOAT-1:1 PARITY ship #128645 — site-footer-bottom strip rhythm
   measured: csfloat signed-in mini-footer has NO separate "bottom"
   strip — its copyright, links, and social row all sit as siblings in
   the SAME .container grid (gap 40, padding 17px 5px, margin-top 17).
   sboxmarket carries a separate .site-footer-bottom for copyright +
   socials + meta (per the legal-disclosure structure ship #20008
   preserved). Per "match-don't-redesign" we keep that structure but
   re-pin its rhythm to csfloat's signed-in numbers: margin-top 17
   (was 30), padding 17px 5px (was 20px 30px 0), max-width NONE
   (was 1337) so the strip reads at the same vertical+gutter rhythm
   as csfloat's mini-footer .container would if it carried this row.
   Hairline at the TOP of .site-footer-inner (ship #128642) carries
   the visual separation — no need for an extra hairline on
   .site-footer-bottom.
*/
html body footer.site-footer .site-footer-bottom {
  margin: 17px 0 0 !important;
  padding: 17px 5px !important;
  max-width: none !important;
  border-top: 0 none !important;
  gap: 16px 40px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128645 */

/* csfloat-1:1 ships #128640-#128645 — SIGNED-IN footer parity:
   - .site-footer outer: transparent, no padding, no border (#128640)
   - .site-footer-inner panel demote: transparent, no radius, no shadow,
     padding 17px 5px, max-width none (#128641)
   - .site-footer-inner top hairline: 1px solid rgba(193,206,255,0.04)
     mirroring csfloat's signed-in token (#128642)
   - link rail column-gap 40 on >=1100 (down from 60) matching the
     csfloat .container grid-gap (#128643)
   - social row gap 15 (down from 18) matching csfloat .social (#128644)
   - .site-footer-bottom strip rhythm 17/17px-5px max-width-none matching
     csfloat .container row rhythm (#128645)
   Earlier ships #20000-#20009 (anon-/faq footer card chrome) remain on
   disk; these append after them in the cascade and override only the
   surfaces signed-in csfloat changes.
*/
"""

target = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"
mirror = r"C:\Users\WW\Desktop\sboxmarket\build\resources\main\static\css\design.css"

with open(target, 'rb') as f:
    f.seek(-3072, os.SEEK_END)
    tail = f.read().decode('utf-8', errors='replace')
if 'ship #128640' in tail:
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

print('SHIPS #128640-#128645 LANDED')
