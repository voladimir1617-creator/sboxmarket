"""Atomic append of CSFLOAT 1:1 parity ships #128460-#128466 — page search-bar (real signed-in live measurements).

Live measured against csfloat.com/search (signed-in session, viewport 1440)
2026-05-08 via mcp__playwright__browser_navigate + mcp__playwright__browser_evaluate.

CONTEXT — what is the "header search bar":
  csfloat.com header (.toolbar, 70px tall) actually has NO omni-search input.
  Children are:
    .logo (40x40 @ 75,15)
    a.route-button "Market" (67x36)
    a.route-button "Database" (83x36)
    a.route-button "Loadout" (75x36)
    button "Tools v" (83x36)
    div.spacer (flex-grow:1, height:0, width:1488)
    span "*****"      (wallet bal masked, 61x40 @ 2061,15)
    span "USD"        (45x25)
    span "EN"         (36x25)
    a.hoverable-btn "0" (28x28 — cart count badge)
    a.hoverable-btn "!" (28x28 — alerts badge)
    a.hoverable-btn ""  (28x28 — wishlist heart)
    button.user.avatar  (48x48)
  → no <input>, no /-kbd-hint, no autocomplete-trigger anywhere in <app-header>.
  The "search" UX on csfloat is the SIDEBAR-level search-bar inside the
  /search route's app-advanced-search component. That is the omnibar users
  actually type into.

DOM tree dump for the real /search side-search bar:

  <mat-form-field class="mat-mdc-form-field search-bar dense-input-m3
                          mat-form-field-appearance-fill mat-primary
                          mat-mdc-form-field-has-icon-prefix
                          mat-mdc-form-field-has-icon-suffix">
    <div class="mat-mdc-text-field-wrapper mdc-text-field
                mdc-text-field--filled mdc-text-field--no-label">
      WRAPPER  rect 80,158 310x44   bg rgba(193,206,255,0.04)
                  border-radius 6px
                  padding 0 0 0 16px (L only — R is hugged by the suffix)
      <div class="mat-mdc-form-field-flex">
        <div class="mat-mdc-form-field-icon-prefix">
          PREFIX  rect 96,172 19x16   pad-right 4px
          <svg w15 h16 viewBox "0 0 15 16" fill="none" stroke="white">
            (search lens path)
          </svg>
        </div>
        <div class="mat-mdc-form-field-infix">
          INFIX   rect 115,158 271x44  padding 10px 0
                  min-height 44px
          <input placeholder="Search for items..."
                 class="mat-mdc-input-element mat-mdc-autocomplete-trigger">
            INPUT  rect 115,168 271x24
                   font 16/24 Roboto, color rgb(255,255,255)
                   placeholder color rgba(255,255,255,0.7)
        </div>
        <div class="mat-mdc-form-field-icon-suffix">
          SUFFIX  rect 386,180 4x0  (collapses to slash-square)
          <span class="slash-square mat-mdc-tooltip-trigger">
            <div class="round-square">
              SQUARE  rect 360,170 22x22  (border-box)
                       inner 20x20  bg rgba(193,206,255,0.04)
                       border 1px solid rgba(158,167,177,0.15)
                       border-radius 6px
                       display flex; align/justify center
              <div class="diagonal-line">/</div>
                DIAG   color rgb(158,167,177)  font 14/0 Roboto
                       (the "/" glyph; kbd shortcut hint)
            </div>
          </span>
        </div>
      </div>
    </div>
  </mat-form-field>

sboxmarket EMITS THE SAME DOM (mat-form-field.search-bar, .round-square,
.diagonal-line — verified at localhost:8080/search). Existing baseline
ships #13200-#13205 + #17100 + #17300 cover suggestions-dropdown row chrome
(recent-search clock icon, forget-X, keyboard-active emphasis) and ship
#128175 covers a generic .advanced-search > .search-bar mapping. But the
#128175 numbers were measured at an ANONYMOUS /search session and got
several values off vs the signed-in live numbers I measured today:

  #128175 wrapper  bg ok  br 8 (live 6)  padding 0 12 (live 0 0 0 16)
                   height 44 (ok)
  #128175 input    fs 16 (ok)  pl 0 (ok) — but placeholder color ink-2
                   158/167/177 (live rgba(255,255,255,0.7))
  #128175 kbd      24x24 (live 22x22 outer / 20x20 inner) br 4 (live 6)
                   border rgba(255,255,255,0.06) (live rgba(158,167,177,0.15))
                   fs 12 (live 14)
                   bg transparent (live rgba(193,206,255,0.04))
  #128175 wrapper does not address the prefix svg lens 15x16 sizing,
                   does not pin prefix paint color to white, does not pin
                   suffix slash-square chrome to its measured radius.

This batch APPENDS corrections without touching the prior selectors
(later in the cascade wins when both rule sets fire and !important is set).

Ships:
  #128460 — wrapper chrome (bg + 6br + 16pl)
  #128461 — wrapper height + suffix-pull padding-right 0 (suffix is its
            own flex item that owns the right-edge gutter)
  #128462 — prefix lens svg sizing + color (15x16 white)
  #128463 — slash-square (kbd-hint pill) chrome (22x22 outer / 20x20 inner
            / 6br / 1px solid hairline / 4% panel-tint bg)
  #128464 — diagonal-line "/" glyph color + 14px font sizing
  #128465 — infix padding 10/0 + 44 min-h + input placeholder 70%-white
  #128466 — :focus-within keeps transparent border (csfloat live shows
            no border swap on focus — only the line-ripple fires; sbox
            baseline injects a brand-blue box-shadow that csfloat does
            NOT use; strip it inside the .search-bar scope only)

NO Docker. APPEND-ONLY at end of design.css. Atomic tempfile + os.replace.
"""

import os, sys, tempfile

CSS = """

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128460 — search-bar wrapper chrome (real)
   Live-measured 2026-05-08 on csfloat.com/search:
   .mat-mdc-text-field-wrapper inside mat-form-field.search-bar →
     bg rgba(193,206,255,0.04), border-radius 6px (NOT 8 as ship #128175
     guessed), padding 0 0 0 16px (the right edge is hugged by the
     slash-square suffix). Pin the 6/16 numbers exactly so the pill chrome
     reads like csfloat's filled-text-field-no-label preset.
*/
body mat-form-field.search-bar > .mat-mdc-text-field-wrapper,
body mat-form-field.search-bar .mat-mdc-text-field-wrapper {
  background: rgba(193, 206, 255, 0.04) !important;
  border-radius: 6px !important;
  padding: 0 0 0 16px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128460 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128461 — search-bar wrapper geometry
   Live-measured: wrapper rect 310x44 (sbox routes vary the 310 width
   inside .advanced-search; pin only the 44 height + flex-display so
   the prefix/infix/suffix triplet aligns center-vertically against the
   wrapper baseline). The mat-mdc-form-field-flex inside is the actual
   row container for the three icon zones — pin its height to 44 too
   so the slash-square doesn't drop below baseline on dense-input-m3.
*/
body mat-form-field.search-bar > .mat-mdc-text-field-wrapper {
  height: 44px !important;
  min-height: 44px !important;
  display: flex !important;
  align-items: center !important;
}
body mat-form-field.search-bar .mat-mdc-text-field-wrapper > .mat-mdc-form-field-flex {
  display: flex !important;
  align-items: center !important;
  gap: 0 !important;
  height: 44px !important;
  min-height: 44px !important;
  flex: 1 1 auto !important;
  padding: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128461 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128462 — prefix lens svg sizing + paint
   Live-measured prefix container at .mat-mdc-form-field-icon-prefix:
     rect 96,172 19x16 with padding-right 4px. The icon is an INLINE
     <svg width="15" height="16" viewBox="0 0 15 16" fill="none"
     stroke="white"> not a mat-icon font glyph. Sbox baseline emits a
     mat-icon font ligature here (which renders 24x24 by default and
     pushes the input off-center). Pin the prefix container's flex
     alignment + padding, and any svg/mat-icon child to 15x16 white.
*/
body mat-form-field.search-bar .mat-mdc-form-field-icon-prefix {
  display: flex !important;
  align-items: center !important;
  padding: 0 4px 0 0 !important;
  flex: 0 0 auto !important;
  color: rgb(255, 255, 255) !important;
}
body mat-form-field.search-bar .mat-mdc-form-field-icon-prefix > svg,
body mat-form-field.search-bar .mat-mdc-form-field-icon-prefix > svg * {
  width: 15px !important;
  height: 16px !important;
  color: rgb(255, 255, 255) !important;
  stroke: rgb(255, 255, 255) !important;
}
body mat-form-field.search-bar .mat-mdc-form-field-icon-prefix > mat-icon,
body mat-form-field.search-bar .mat-mdc-form-field-icon-prefix > .mat-icon {
  width: 16px !important;
  height: 16px !important;
  font-size: 16px !important;
  line-height: 16px !important;
  color: rgb(255, 255, 255) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128462 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128463 — slash-square (kbd hint) chrome
   Live-measured suffix .slash-square > .round-square:
     OUTER (border-box) 22x22, INNER 20x20 (1px border each side)
     bg rgba(193,206,255,0.04)
     border 1px solid rgba(158,167,177,0.15)  (NOT the rgba(255,255,255,0.06)
                                               hairline ship #128175 guessed)
     border-radius 6px
     display flex; align-items center; justify-content center
   Sbox legacy .keyboard-shortcut / .kbd selectors targeted 24x24/4br/
   transparent-bg/0.06-border. Pin the .round-square + .slash-square
   selectors directly (which is the actual emitted markup) so both Sbox
   and csfloat themes paint the SAME 22x22 tinted-pill kbd hint.
*/
body mat-form-field.search-bar .mat-mdc-form-field-icon-suffix {
  display: flex !important;
  align-items: center !important;
  padding: 0 12px 0 0 !important;
  flex: 0 0 auto !important;
  height: 44px !important;
}
body mat-form-field.search-bar .slash-square {
  display: inline-flex !important;
  align-items: center !important;
  justify-content: center !important;
  width: 22px !important;
  height: 22px !important;
  flex: 0 0 auto !important;
}
body mat-form-field.search-bar .slash-square > .round-square,
body mat-form-field.search-bar .round-square {
  width: 20px !important;
  height: 20px !important;
  background: rgba(193, 206, 255, 0.04) !important;
  border: 1px solid rgba(158, 167, 177, 0.15) !important;
  border-radius: 6px !important;
  display: flex !important;
  align-items: center !important;
  justify-content: center !important;
  padding: 0 !important;
  margin: 0 !important;
  color: rgb(255, 255, 255) !important;
  font-family: Roboto, "Helvetica Neue", sans-serif !important;
}
/* END CSFLOAT-1:1 PARITY ship #128463 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128464 — diagonal-line "/" glyph color
   Live-measured .diagonal-line:
     color rgb(158,167,177) (ink-2, the muted slate)
     font 14px Roboto / 0 line-height (the wrapper sets line-height 0 so
     the "/" sits visually centered without consuming row height)
   Sbox legacy ship #128175 guessed 12px font here — bump to live 14
   so the slash glyph fills the 20x20 inner box at the correct visual
   weight. textAlign:left on the live measurement is fine because the
   parent's flex centering does the actual horizontal centering.
*/
body mat-form-field.search-bar .slash-square .diagonal-line,
body mat-form-field.search-bar .round-square .diagonal-line,
body mat-form-field.search-bar .diagonal-line {
  color: rgb(158, 167, 177) !important;
  font-size: 14px !important;
  font-weight: 400 !important;
  line-height: 0 !important;
  font-family: Roboto, "Helvetica Neue", sans-serif !important;
  background: transparent !important;
  padding: 0 !important;
  margin: 0 !important;
  text-align: center !important;
  pointer-events: none !important;
}
/* END CSFLOAT-1:1 PARITY ship #128464 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128465 — infix padding + input typography
   Live-measured .mat-mdc-form-field-infix inside .search-bar:
     rect 115,158 271x44, padding-top 10px, padding-bottom 10px
     min-height 44px (matches wrapper height — infix fills the row)
   Live-measured input:
     font 16/24 Roboto white
     placeholder color rgba(255,255,255,0.7) (NOT the ink-2 158/167/177
     that ship #128175 set — csfloat uses 70%-white which reads brighter
     and is one of the few places csfloat departs from its ink-2 token)
   Pin both numbers exact so the input baseline lines up with the
   prefix lens (172 baseline) and the slash-square center (181 baseline).
*/
body mat-form-field.search-bar .mat-mdc-form-field-infix {
  padding: 10px 0 !important;
  min-height: 44px !important;
  flex: 1 1 auto !important;
  display: flex !important;
  align-items: center !important;
  border: 0 !important;
}
body mat-form-field.search-bar input,
body mat-form-field.search-bar input.mat-mdc-input-element,
body mat-form-field.search-bar input.mat-mdc-autocomplete-trigger {
  font: 400 16px/24px Roboto, "Helvetica Neue", sans-serif !important;
  color: rgb(255, 255, 255) !important;
  background: transparent !important;
  border: 0 !important;
  outline: none !important;
  padding: 0 !important;
  margin: 0 !important;
  height: 24px !important;
  flex: 1 1 auto !important;
  min-width: 0 !important;
  border-radius: 0 !important;
}
body mat-form-field.search-bar input::placeholder,
body mat-form-field.search-bar input.mat-mdc-input-element::placeholder,
body mat-form-field.search-bar input.mat-mdc-autocomplete-trigger::placeholder {
  color: rgba(255, 255, 255, 0.7) !important;
  opacity: 1 !important;
  font-weight: 400 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128465 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128466 — :focus-within stays clean
   Live-measured the wrapper at focus-state: NO border swap, NO box-shadow
   ring, NO color change to the wrapper itself — csfloat relies entirely
   on the line-ripple under the wrapper to indicate focus. Sbox global
   stylesheet baseline (line 124033) injects:
     .searchbar input:focus, ... { box-shadow: 0 0 0 3px brand-blue-glow; }
   which fires on .search-bar input too via the loose .searchbar selector
   collision. Strip the focus-ring inside .search-bar scope so csfloat's
   subtler line-ripple-only focus reads correctly. Keep the line-ripple
   visible by NOT zeroing its display.
*/
body mat-form-field.search-bar:focus-within > .mat-mdc-text-field-wrapper,
body mat-form-field.search-bar > .mat-mdc-text-field-wrapper:focus-within,
body mat-form-field.search-bar > .mat-mdc-text-field-wrapper.mdc-text-field--focused {
  background: rgba(193, 206, 255, 0.04) !important;
  border: 0 !important;
  box-shadow: none !important;
  outline: 0 !important;
}
body mat-form-field.search-bar input:focus,
body mat-form-field.search-bar input.mat-mdc-input-element:focus,
body mat-form-field.search-bar input.mat-mdc-autocomplete-trigger:focus {
  box-shadow: none !important;
  outline: 0 !important;
  border: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128466 */
"""

target = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"
mirror = r"C:\Users\WW\Desktop\sboxmarket\build\resources\main\static\css\design.css"

with open(target, 'rb') as f:
    f.seek(-3072, os.SEEK_END)
    tail = f.read().decode('utf-8', errors='replace')
if 'ship #128460' in tail:
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

# Mirror to build/ so a running gradle bootRun picks up without rebuild
if os.path.exists(mirror):
    atomic_append(mirror, CSS)
    print('MIRRORED', len(CSS), 'bytes -> build/resources/main/.../design.css')

print('SHIPS #128460-#128466 LANDED')
