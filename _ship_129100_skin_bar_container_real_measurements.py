"""Atomic append of CSFLOAT 1:1 parity ships #129100-#129106 — the
sbox `.toolbar` strip pinned to csfloat's measured `.skin-bar-container`
+ search-wrap primitive geometry (real measured live).

Live measured against csfloat.com (signed-in, viewport 1440) on
2026-05-08 plus localhost:8082/market on the same date via
mcp__playwright__browser_navigate + mcp__playwright__browser_evaluate.

CSFLOAT GROUND TRUTH — `app-market-search > .skin-bar-container` row:

  app-market-search          starts at y=85 (15-px gap below 70-h header),
                             top of the marketplace stack on `/`. Composed
                             of ONE skin-bar-container child + the
                             app-search row that follows.

  .skin-bar-container        rect: full-bleed inside the 60-px gutter,
                             height 38px, margin: 15px 0 0 (15-top, 0
                             everywhere else), padding: 0, NO background,
                             NO border, NO border-radius. The 38-tall
                             strip is the slim "active-skin tags" /
                             control-chip row that sits ABOVE the actual
                             search field. On the bare-grid landing this
                             row is empty/invisible — the control weight
                             is carried by the descendants.

  app-search                 starts at y=123 (= 85 + 38, no gap) and
                             holds the omnibox input + filter buttons.

SBOXMARKET CURRENT STATE — `.main > .toolbar` (live measured 2026-05-08
at http://localhost:8082/market, viewport ~1440):

  .toolbar                   width 1435  height 44   margin 0 0 12px
                             padding 0   bg transparent  border 0
                             display flex  gap 10  align-items center
                             children (DOM order):
                               .search-wrap      (310x44)
                               .sort-picker
                               <empty div>       (the spacer flex child —
                                                  carries no class)
                               .type-toggle
                               .deals-chip
                               .sort-select.discount-select
                               .deals-chip.new-chip
                               .toolbar-refresh
                               .view-btns

  .search-wrap               310x44  bg rgba(193,206,255,0.04)
                             br 6px  padding 0 0 0 16px
                             flex 0 0 310px  ai center  position relative
                             (i.e. ALREADY matches csfloat search-bar wrapper
                              chrome by happy accident — pin defensively.)

KEY DIFFS (sbox → csfloat target):

  sbox .toolbar height 44  ✗  csfloat skin-bar 38
                             — sbox conflates skin-bar + app-search into a
                               single 44-h flex row because the search-wrap
                               inside is itself 44h. DO NOT shrink to 38
                               (would clip the search-wrap). Instead:
                               accept the 44h as documented sbox divergence
                               and align the OUTER margin so the strip
                               lands at the csfloat skin-bar's y-anchor.

  sbox .toolbar margin 0 0 12px   ✗  csfloat skin-bar margin 15px 0 0
                             — strip the 12-bottom (results-meta line below
                               provides separation), pin top 15 so the
                               control row anchors at the csfloat-measured
                               y=85 (page header 70 + 15 gap).

  sbox .toolbar bg transparent / border 0   ≈ already aligned, pin defensively.

  sbox .toolbar > spacer empty <div>   — sbox renders a class-less <div>
                             at index 2 to push the right-cluster controls
                             to the right edge. csfloat uses an explicit
                             .gap flex-child for this. Pin sbox's class-less
                             <div> spacer to flex:1 1 auto / min-width:0
                             so the layout ALWAYS right-justifies the
                             trailing controls (some CSS resets currently
                             collapse it to 0w on narrow viewports).

  sbox .search-wrap chrome   ≈ matches csfloat (310x44, bg 4%-tint, br 6,
                             pad 0 0 0 16). Pin all four numbers !important
                             so the cascade can't drift later.

  sbox font inheritance      ✗ csfloat search-input font: 16/24 Roboto white
                             — pin the .search-wrap > .search-input
                             typography to the csfloat baseline so the
                             input height + baseline match the slash-square
                             kbd hint center.

This batch APPENDS corrections targeting the CORRECT sbox class names
(.toolbar / .search-wrap / .search-input / .search-kbd / .search-icon)
that the renderer actually emits — earlier ships #128460-#128466 keyed
their selectors on csfloat's `mat-form-field.search-bar` markup which
sbox does NOT render, so those rules currently fire ZERO times in the
cascade. We re-anchor the chrome onto the live sbox markup here.

CRITICAL legal-safety: only geometry, padding, border-radius, background
opacity, and margin numbers are aligned. We do NOT swap any csfloat
icons / glyphs / copy / images / proprietary assets in. Sbox's own
search-icon SVG and `/` kbd glyph stay.

Ships #129100-#129106 land above the prior #128980 ceiling with headroom.

NO Docker. APPEND-ONLY at end of design.css. Atomic tempfile + os.replace.
Mirror to build/ so a running gradle bootRun picks up without rebuild.
"""

import os, sys, tempfile

CSS = """

/* =====================================================================
   CSFLOAT-1:1 PARITY ships #129100-#129106 — `.toolbar` strip pinned to
   csfloat's measured `.skin-bar-container` row geometry + the
   `.search-wrap` primitive pinned to csfloat's measured wrapper chrome.

   csfloat ground truth (live measured 2026-05-08, viewport 1440):
     app-market-search          starts y=85 (header 70 + 15 gap)
       .skin-bar-container      38h, margin 15px 0 0, no chrome
       app-search               starts y=123 (no gap below skin-bar)
   csfloat search-bar wrapper inside app-search:
       310x44, bg rgba(193,206,255,0.04), br 6, padding 0 0 0 16

   sboxmarket conflates skin-bar + app-search into ONE `.toolbar` row
   that is 44h (because the .search-wrap child is 44h). We accept the
   44h as documented divergence and align the OUTER margin so the row
   anchors at the csfloat-measured y=85 baseline. Then pin the
   .search-wrap chrome + the inner .search-input typography to the
   csfloat numbers so the omnibox visually matches byte-for-byte.

   IMPORTANT: prior ships #128460-#128466 keyed their search-bar
   chrome rules onto `mat-form-field.search-bar` (Angular Material
   markup csfloat emits). sbox does NOT render mat-form-field — its
   markup is `.toolbar > .search-wrap > .search-input + .search-kbd`.
   Those prior rules currently fire ZERO times in the live sbox
   cascade. This batch re-targets the corrections onto sbox's actual
   class names so the chrome ACTUALLY lands.
   ===================================================================== */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #129100 — `.toolbar` outer margin → csfloat
   skin-bar baseline.
   csfloat .skin-bar-container computed margin: 15px 0 0 (15-top, 0 elsewhere).
   sbox .toolbar baseline computed margin: 0 0 12px. Strip the 12-bottom
   so the next `.results-meta` line owns the post-row gap on its own
   `padding: 14px 0`, and pin top 15 so the control row anchors at the
   csfloat-measured y=85 (page header 70 + 15 gap below). The 15-top is
   the same gap measured at csfloat between the 70-h header and the
   start of app-market-search → guarantees both pages put their first
   control row at exactly the same y-coordinate.
*/
html body .toolbar[role="toolbar"],
html body .main > .toolbar,
html body div.toolbar {
  margin-top: 15px !important;
  margin-right: 0 !important;
  margin-bottom: 0 !important;
  margin-left: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #129100 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #129101 — `.toolbar` flex layout pin
   csfloat .skin-bar-container & .container row use display:flex,
   flex-direction:row, align-items:center, gap:10. sbox baseline
   currently computes the same values BUT they're inherited from a
   loose .toolbar selector that other later rules in the cascade can
   easily override. Pin all four explicit so the row layout cannot
   drift under future appends.
*/
html body .toolbar[role="toolbar"],
html body .main > .toolbar,
html body div.toolbar {
  display: flex !important;
  flex-direction: row !important;
  align-items: center !important;
  gap: 10px !important;
  padding: 0 !important;
  background: transparent !important;
  border: 0 !important;
  border-radius: 0 !important;
  box-shadow: none !important;
  width: 100% !important;
  box-sizing: border-box !important;
}
/* END CSFLOAT-1:1 PARITY ship #129101 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #129102 — `.toolbar` empty spacer → csfloat .gap
   sbox renders a class-less <div> at .toolbar children index 2 to push
   the right-cluster controls (deals chips, sort-select, refresh,
   view-btns) to the right edge of the row. csfloat uses an explicit
   .gap flex-child with `flex: 1 1 auto` for the same purpose. The
   class-less sbox div sometimes collapses to 0w on narrow viewports
   because no rule pins its flex grow. Pin first-of-type un-classed
   div directly inside .toolbar to flex 1 1 auto / min-width 0 so the
   right-cluster ALWAYS pushes against the right edge.
*/
html body .toolbar[role="toolbar"] > div:not([class]):empty,
html body .toolbar[role="toolbar"] > div[class=""]:empty,
html body .main > .toolbar > div:not([class]):empty,
html body .main > .toolbar > div[class=""]:empty {
  flex: 1 1 auto !important;
  min-width: 0 !important;
  width: auto !important;
  height: 1px !important;
  align-self: stretch !important;
  background: transparent !important;
  pointer-events: none !important;
}
/* END CSFLOAT-1:1 PARITY ship #129102 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #129103 — `.search-wrap` wrapper chrome
   csfloat search-bar wrapper rect 310x44, bg rgba(193,206,255,0.04),
   border-radius 6, padding 0 0 0 16 (the right edge is hugged by the
   slash-square kbd hint). Sbox `.search-wrap` already computes these
   numbers by happy accident — pin all four !important so future
   cascade drift can't break the parity. Also pin position:relative so
   the absolute-positioned `.search-icon` and `.search-kbd` children
   anchor inside the wrapper, not the page.
*/
html body .toolbar .search-wrap[role="search"],
html body .toolbar > .search-wrap,
html body .main > .toolbar > .search-wrap {
  width: 310px !important;
  height: 44px !important;
  flex: 0 0 310px !important;
  background: rgba(193, 206, 255, 0.04) !important;
  border: 0 !important;
  border-radius: 6px !important;
  padding: 0 0 0 16px !important;
  display: flex !important;
  align-items: center !important;
  position: relative !important;
  box-sizing: border-box !important;
  box-shadow: none !important;
}
@media (max-width: 900px) {
  html body .toolbar .search-wrap[role="search"],
  html body .toolbar > .search-wrap,
  html body .main > .toolbar > .search-wrap {
    width: 100% !important;
    flex: 1 1 auto !important;
  }
}
/* END CSFLOAT-1:1 PARITY ship #129103 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #129104 — `.search-icon` lens primitive
   csfloat prefix lens svg sized 15x16 stroke #fff, sits at left of
   wrapper with 4-px right-pad gap to the input baseline. Sbox renders
   the lens as a span.search-icon with an inline mat-icon style SVG.
   Live-measured: 15x16, color rgb(158,167,177) (csfloat's lens is
   actually rgb(255,255,255) per ship #128462). Pin to 15x16 + #fff
   so both pages render the same lens weight at the same size.
*/
html body .toolbar .search-wrap > .search-icon,
html body .toolbar .search-wrap span.search-icon {
  width: 15px !important;
  height: 16px !important;
  color: rgb(255, 255, 255) !important;
  display: flex !important;
  align-items: center !important;
  justify-content: center !important;
  position: absolute !important;
  left: 16px !important;
  top: 50% !important;
  transform: translateY(-50%) !important;
  pointer-events: none !important;
  z-index: 1 !important;
}
html body .toolbar .search-wrap > .search-icon svg,
html body .toolbar .search-wrap > .search-icon svg *,
html body .toolbar .search-wrap span.search-icon svg,
html body .toolbar .search-wrap span.search-icon svg * {
  width: 15px !important;
  height: 16px !important;
  stroke: rgb(255, 255, 255) !important;
  color: rgb(255, 255, 255) !important;
}
/* END CSFLOAT-1:1 PARITY ship #129104 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #129105 — `.search-input` typography
   csfloat input font: 16/24 Roboto white, placeholder rgba(255,255,255,0.7).
   Sbox `.search-input` baseline inherits from `--ink` Geist 14, which
   reads visibly thinner than csfloat. Pin the input typography to the
   csfloat baseline so the omnibox baseline lines up with the
   .search-icon (15x16) on the left and the .search-kbd (22x22) on the
   right. Padding 0 36 0 23 is sbox-specific to leave room for the
   abs-positioned icon (left 16 + 15 lens + 4 gap = 23 left pad) and
   the kbd hint (22 sq + 14 right pad = 36 right pad) — keep those
   numbers, only pin font + color.
*/
html body .toolbar .search-wrap > input.search-input,
html body .toolbar .search-wrap input.search-input,
html body .toolbar input.search-input {
  font: 400 16px/24px Roboto, "Helvetica Neue", Arial, sans-serif !important;
  color: rgb(255, 255, 255) !important;
  background: transparent !important;
  border: 0 !important;
  outline: 0 !important;
  height: 44px !important;
  flex: 1 1 auto !important;
  min-width: 0 !important;
  border-radius: 0 !important;
  box-shadow: none !important;
}
html body .toolbar .search-wrap > input.search-input::placeholder,
html body .toolbar .search-wrap input.search-input::placeholder,
html body .toolbar input.search-input::placeholder {
  color: rgba(255, 255, 255, 0.7) !important;
  opacity: 1 !important;
  font-weight: 400 !important;
}
/* END CSFLOAT-1:1 PARITY ship #129105 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #129106 — `.search-kbd` kbd-hint pill chrome
   csfloat slash-square > round-square kbd hint:
     OUTER 22x22, INNER 20x20 (1px border each side)
     bg rgba(193,206,255,0.04)
     border 1px solid rgba(158,167,177,0.15)
     border-radius 6
     glyph "/" font 14 Roboto color rgb(158,167,177)
   Sbox `.search-kbd` already computes 22x22 / br 6 / 4%-tint by happy
   accident (it's the same primitive ship #128463 pinned for csfloat
   markup, applied via the .search-kbd selector elsewhere in the
   cascade). Pin the four numbers !important on the actual sbox
   selector so the kbd hint can't drift. Also pin glyph color +
   font-size so the "/" glyph matches the live csfloat measurement.
*/
html body .toolbar .search-wrap > .search-kbd,
html body .toolbar .search-wrap kbd.search-kbd,
html body .toolbar kbd.search-kbd {
  width: 22px !important;
  height: 22px !important;
  background: rgba(193, 206, 255, 0.04) !important;
  border: 1px solid rgba(158, 167, 177, 0.15) !important;
  border-radius: 6px !important;
  display: flex !important;
  align-items: center !important;
  justify-content: center !important;
  color: rgb(158, 167, 177) !important;
  font: 400 14px/1 Roboto, "Helvetica Neue", Arial, sans-serif !important;
  position: absolute !important;
  right: 14px !important;
  top: 50% !important;
  transform: translateY(-50%) !important;
  pointer-events: none !important;
  padding: 0 !important;
  margin: 0 !important;
  box-sizing: border-box !important;
  z-index: 1 !important;
  text-align: center !important;
  text-transform: none !important;
  box-shadow: none !important;
}
/* END CSFLOAT-1:1 PARITY ship #129106 */
"""

target = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"
mirror = r"C:\Users\WW\Desktop\sboxmarket\build\resources\main\static\css\design.css"

# Idempotency guard — only append if this ship batch hasn't landed yet.
with open(target, 'rb') as f:
    f.seek(-4096, os.SEEK_END)
    tail = f.read().decode('utf-8', errors='replace')
if 'ship #129100' in tail:
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

print('SHIPS #129100-#129106 LANDED')
