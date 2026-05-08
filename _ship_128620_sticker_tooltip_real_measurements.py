"""Atomic append of CSFLOAT 1:1 parity ships #128620-#128626 — sticker mat-tooltip
panel + body chrome (live-measured).

Live-measured against csfloat.com/item/972716531467291016 (signed-in session,
viewport 2560x1305) 2026-05-08 via mcp__playwright__browser_navigate +
mcp__playwright__browser_evaluate by hovering an `app-sticker-view
.mat-mdc-tooltip-trigger.sticker` (sticker thumb) on the live signed-in
detail page. The Angular Material tooltip auto-injected a
`.cdk-overlay-pane.mat-mdc-tooltip-panel.mat-mdc-tooltip-panel-below`
into `.cdk-overlay-container`, holding a
`<mat-tooltip-component><div class="mdc-tooltip mat-mdc-tooltip
mat-mdc-tooltip-show"><div class="mat-mdc-tooltip-surface
mdc-tooltip__surface">…</div></div></mat-tooltip-component>` tree with the
sticker name + wear + slot + reference price + global listings.

Measured panel `.mat-mdc-tooltip-panel-below` (computed styles + bounding):
   inline style:    top: 445.688px, left: 364.812px, transform: translateY(8px)
                    (panel positioned 8px BELOW its trigger via the inline
                    transform — Material's default offset for "below" placement)
   className suffix: mat-mdc-tooltip-panel-below      (and -above/-left/-right
                    siblings exist in the Material vocabulary; sticker tooltip
                    uses -below by default)
   computed:
     bg:               rgba(0,0,0,0)            (the panel itself is transparent)
     color:            rgb(255, 255, 255)
     padding:          0
     border-radius:    0
     box-shadow:       none
     opacity:          1
     transition:       all                       (Material default; default duration
                                                  is ~150ms enter / 75ms exit)
     transform:        matrix(1,0,0,1,0,8)       (≡ translateY(8px))

Measured surface `.mdc-tooltip__surface.mat-mdc-tooltip-surface`:
     bg:               rgba(21, 23, 28, 0.8)     (semi-transparent very-dark
                                                  panel — note this is NOT the
                                                  Material default rgba(97,97,97,
                                                  0.92) gray; csfloat overrides
                                                  to its panel-1 ink at 80% alpha)
     color:            rgb(255, 255, 255)        (pure white, NOT ink-1 #e8edf2)
     padding:          4px 8px                   (Material MDC default
                                                  4-vertical / 8-horizontal pill)
     border-radius:    4px                       (Material MDC default)
     border:           2px solid rgba(193, 206, 255, 0.07)
                                                  (csfloat-specific 2px hairline
                                                  in cool-blue-tinted white at
                                                  7% alpha — matches the brand
                                                  highlight on hover bubbles)
     box-shadow:       none                      (csfloat does NOT use shadow)
     font-size:        12px
     font-weight:      400
     line-height:      16px
     font-family:      Roboto, "Helvetica Neue", sans-serif
     max-width:        200px
     min-height:       24px
     text-align:       center
     overflow-wrap:    anywhere
     word-break:       normal
     opacity:          1

Body text observed (multi-line, nested `<br>` + plain text content):
     Line 1: "Sticker | Crown (Foil)"          ← name + style
     Line 2: "0% Wear"                          ← wear
     Line 3: "Slot 4"                           ← slot index
     Line 4: ""                                 ← blank spacer
     Line 5: "Reference Price: $406.94"
     Line 6: "Global Listings: 167"

Sboxmarket has a generic `.csfloat-tooltip` (line 19852) but it is a
`position:absolute` opaque-dark pill with arrow ::before, font Geist 11/500,
border 1px var(--line-2), 6px radius, 4px 12px shadow, transition
opacity+translateY 140ms. None of that matches csfloat's mat-tooltip:

  - panel chrome:  csfloat panel transparent / no chrome → mat-tooltip
                   pattern (csfloat-tooltip has chrome on the wrap itself)
  - bg:            csfloat rgba(21,23,28,0.8) ≠ sboxmarket dark-opaque oklch
  - border:        csfloat 2px rgba(193,206,255,0.07) ≠ sboxmarket 1px line-2
  - radius:        csfloat 4px ≠ sboxmarket 6px
  - shadow:        csfloat NONE ≠ sboxmarket 0 4px 12px rgba(0,0,0,0.55)
  - font:          csfloat Roboto 12/16 400 ≠ sboxmarket Geist 11px 500
  - color:         csfloat #ffffff ≠ sboxmarket var(--ink-1)
  - max-width:     csfloat 200px (allows wrap) ≠ sboxmarket nowrap (no wrap!)
  - text-align:    csfloat center ≠ sboxmarket nowrap default left
  - arrow:         csfloat NONE ≠ sboxmarket has 8x8 ::before triangle
  - offset:        csfloat 8px Y via inline panel transform
                   ≠ sboxmarket 2px transitional offset only

Pin a NEW sticker-tooltip class vocabulary that mirrors the Material
mat-mdc-tooltip-* selectors AND a sboxmarket-flavoured shorthand
(.csfloat-sticker-tooltip) so when sboxmarket renders a sticker hover
bubble it reads 1:1 with csfloat. Do NOT touch the existing .csfloat-tooltip
generic pill (still used for non-sticker tooltips).

Tokens used (matching --cf-* memory and tokens block):
  --cf-panel              rgb(27, 29, 36)
  --cf-brand              rgb(35, 123, 255)
  --cf-ink-2              rgb(158, 167, 177)
  --cf-hair               rgba(255, 255, 255, 0.06)

NO Docker. APPEND-ONLY at end of design.css. Atomic tempfile + os.replace.
Idempotency: first 4096 bytes of file tail checked for ship #128620 marker.
"""

import os, sys, tempfile

CSS = """
/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128620 — sticker mat-tooltip PANEL chrome
   Measured csfloat .cdk-overlay-pane.mat-mdc-tooltip-panel(-below) on
   /item/<id> sticker hover:
     panel host:     position:absolute via cdk-overlay-pane (Material
                     places it via inline top/left)
     bg:             rgba(0,0,0,0)               (transparent — chrome
                                                  lives on the inner surface)
     padding:        0
     border-radius:  0
     box-shadow:     none
     opacity:        1
     transition:     all (Material default ≈150ms enter / 75ms exit)
     transform:      translateY(8px) for -below   (panel pushed 8px past the
                                                  trigger in the placement
                                                  axis — matches Material's
                                                  default "below" offset)
   The panel is the OVERLAY HOST — chrome must NOT live here. Pin to
   transparent + no chrome so the inner surface reads as the bubble.
*/
body .cdk-overlay-pane.mat-mdc-tooltip-panel,
body .mat-mdc-tooltip-panel,
body .csfloat-sticker-tooltip-panel,
body .csfloat-mat-tooltip-panel {
  background: transparent !important;
  padding: 0 !important;
  border-radius: 0 !important;
  border: 0 !important;
  box-shadow: none !important;
  opacity: 1 !important;
  pointer-events: none !important;
  position: absolute !important;
  z-index: 1500 !important;
  transition: all 150ms cubic-bezier(0, 0, 0.2, 1) !important;
}
body .mat-mdc-tooltip-panel.mat-mdc-tooltip-panel-below,
body .csfloat-sticker-tooltip-panel.is-below {
  transform: translateY(8px) !important;
  transform-origin: center top !important;
}
body .mat-mdc-tooltip-panel.mat-mdc-tooltip-panel-above,
body .csfloat-sticker-tooltip-panel.is-above {
  transform: translateY(-8px) !important;
  transform-origin: center bottom !important;
}
body .mat-mdc-tooltip-panel.mat-mdc-tooltip-panel-left,
body .csfloat-sticker-tooltip-panel.is-left {
  transform: translateX(-8px) !important;
  transform-origin: right center !important;
}
body .mat-mdc-tooltip-panel.mat-mdc-tooltip-panel-right,
body .csfloat-sticker-tooltip-panel.is-right {
  transform: translateX(8px) !important;
  transform-origin: left center !important;
}
/* END CSFLOAT-1:1 PARITY ship #128620 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128621 — sticker mat-tooltip SURFACE chrome
   Measured csfloat .mdc-tooltip__surface.mat-mdc-tooltip-surface on
   sticker hover:
     bg:             rgba(21, 23, 28, 0.8)        (csfloat panel-1 @ 80%)
     color:          rgb(255, 255, 255)
     padding:        4px 8px
     border-radius:  4px
     border:         2px solid rgba(193, 206, 255, 0.07)
     box-shadow:     none
     font-size:      12px
     font-weight:    400
     line-height:    16px
     font-family:    Roboto, "Helvetica Neue", sans-serif
     max-width:      200px
     min-height:     24px
     text-align:     center
     overflow-wrap:  anywhere
     word-break:     normal
     opacity:        1
   Sboxmarket .csfloat-tooltip is a different bubble (Geist 11/500, 6px
   radius, oklch dark-opaque, with arrow). Pin a NEW surface vocabulary
   that mirrors the live mat-tooltip surface verbatim.
*/
body .mat-mdc-tooltip-surface,
body .mdc-tooltip__surface,
body .mat-mdc-tooltip .mdc-tooltip__surface,
body .csfloat-sticker-tooltip-surface,
body .csfloat-mat-tooltip-surface,
body .csfloat-sticker-tooltip {
  background: rgba(21, 23, 28, 0.8) !important;
  color: rgb(255, 255, 255) !important;
  padding: 4px 8px !important;
  border-radius: 4px !important;
  border: 2px solid rgba(193, 206, 255, 0.07) !important;
  box-shadow: none !important;
  font-size: 12px !important;
  font-weight: 400 !important;
  line-height: 16px !important;
  font-family: Roboto, "Helvetica Neue", sans-serif !important;
  max-width: 200px !important;
  min-height: 24px !important;
  text-align: center !important;
  overflow-wrap: anywhere !important;
  word-break: normal !important;
  opacity: 1 !important;
  -webkit-backdrop-filter: blur(2px) !important;
  backdrop-filter: blur(2px) !important;
  display: block !important;
  box-sizing: border-box !important;
  letter-spacing: 0 !important;
  text-shadow: none !important;
  margin: 0 !important;
  pointer-events: none !important;
  white-space: normal !important;
}
/* END CSFLOAT-1:1 PARITY ship #128621 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128622 — sticker mat-tooltip wrapper class chain
   Measured csfloat `.mdc-tooltip.mat-mdc-tooltip.mat-mdc-tooltip-show` on
   sticker hover. The inner wrapper holds the surface + handles the
   show/hide animation lifecycle:
     transform-origin: center top                  (when -below; mirrored
                                                  for the other 3 placements)
     animation/transition: handled by Material's `.mat-mdc-tooltip-show`
                                                  enter class (≈150ms ease-out)
   Sboxmarket has no equivalent wrapper layer; the sticker bubble was
   sized via a single ::after on the wrap. Pin the wrapper here as a
   passthrough that propagates transform-origin and contains the surface
   so the show/hide animation reads 1:1 with the live mat-tooltip.
*/
body .mdc-tooltip,
body .mat-mdc-tooltip,
body .csfloat-mat-tooltip-wrap,
body .csfloat-sticker-tooltip-wrap {
  background: transparent !important;
  border: 0 !important;
  border-radius: 0 !important;
  padding: 0 !important;
  margin: 0 !important;
  box-shadow: none !important;
  display: inline-block !important;
  pointer-events: none !important;
  position: relative !important;
  transform-origin: center top !important;
  will-change: transform, opacity !important;
}
body .mdc-tooltip.mat-mdc-tooltip-show,
body .mat-mdc-tooltip.mat-mdc-tooltip-show,
body .csfloat-sticker-tooltip-wrap.is-show {
  opacity: 1 !important;
  transform: scale(1) !important;
  transition: opacity 150ms cubic-bezier(0, 0, 0.2, 1),
              transform 150ms cubic-bezier(0, 0, 0.2, 1) !important;
}
body .mdc-tooltip.mat-mdc-tooltip-hide,
body .mat-mdc-tooltip.mat-mdc-tooltip-hide,
body .csfloat-sticker-tooltip-wrap.is-hide {
  opacity: 0 !important;
  transform: scale(0.8) !important;
  transition: opacity 75ms cubic-bezier(0.4, 0, 1, 1),
              transform 75ms cubic-bezier(0.4, 0, 1, 1) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128622 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128623 — sticker tooltip BODY content lines
   Measured csfloat sticker hover bubble text content (newline-separated):
     Line 1: "Sticker | Crown (Foil)"            (name + style)
     Line 2: "0% Wear"                            (wear pct)
     Line 3: "Slot 4"                             (slot index)
     Line 4: ""                                   (blank spacer)
     Line 5: "Reference Price: $406.94"
     Line 6: "Global Listings: 167"
   The body uses raw text + `<br>` separators inside the surface — no
   nested element chrome (color contrast comes purely from the surface
   bg + uniform white). Pin sboxmarket's optional sticker-tooltip body
   classes (.csfloat-sticker-tooltip-name / -wear / -slot / -ref / -count)
   to inherit the live 12/16 white-on-dark vocabulary with no per-line
   overrides — keeps the bubble feeling like one block of text.
*/
body .csfloat-sticker-tooltip > *,
body .csfloat-sticker-tooltip-name,
body .csfloat-sticker-tooltip-wear,
body .csfloat-sticker-tooltip-slot,
body .csfloat-sticker-tooltip-ref,
body .csfloat-sticker-tooltip-count,
body .csfloat-sticker-tooltip-line {
  color: inherit !important;
  font: inherit !important;
  background: transparent !important;
  border: 0 !important;
  margin: 0 !important;
  padding: 0 !important;
  display: block !important;
  text-align: center !important;
  letter-spacing: 0 !important;
}
body .csfloat-sticker-tooltip-spacer {
  display: block !important;
  height: 16px !important;
  background: transparent !important;
}
/* END CSFLOAT-1:1 PARITY ship #128623 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128624 — sticker tooltip ENTER ANIMATION
   Measured csfloat panel transition was a passthrough `transition: all`
   on the panel host with the inner wrapper handling the actual
   opacity+scale animation via `.mat-mdc-tooltip-show`. Material's
   default Angular Animations builder outputs:
     enter:  150ms cubic-bezier(0, 0, 0.2, 1)
             scale 0.8 → 1, opacity 0 → 1
     exit:    75ms cubic-bezier(0.4, 0, 1, 1)
             scale 1   → 0.8, opacity 1 → 0
   csfloat does not set `mat-tooltip-show-delay` so the default 0ms
   show delay applies; hide delay is also 0ms by default. Pin a CSS
   keyframes pair so sboxmarket's render path (no Angular Animations
   runtime) can mirror the lifecycle via a class flip.
*/
@keyframes csfloat-sticker-tooltip-enter {
  from {
    opacity: 0;
    transform: scale(0.8);
  }
  to {
    opacity: 1;
    transform: scale(1);
  }
}
@keyframes csfloat-sticker-tooltip-exit {
  from {
    opacity: 1;
    transform: scale(1);
  }
  to {
    opacity: 0;
    transform: scale(0.8);
  }
}
body .csfloat-sticker-tooltip-wrap.is-entering,
body .mat-mdc-tooltip.mat-mdc-tooltip-show.is-entering {
  animation: csfloat-sticker-tooltip-enter 150ms cubic-bezier(0, 0, 0.2, 1) both !important;
  transform-origin: center top !important;
}
body .csfloat-sticker-tooltip-wrap.is-exiting,
body .mat-mdc-tooltip.mat-mdc-tooltip-hide.is-exiting {
  animation: csfloat-sticker-tooltip-exit 75ms cubic-bezier(0.4, 0, 1, 1) both !important;
  transform-origin: center top !important;
}
/* END CSFLOAT-1:1 PARITY ship #128624 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128625 — sticker tooltip POSITIONING offset
   Measured csfloat panel inline `top: 445.688px; left: 364.812px;
   transform: translateY(8px);` against trigger at x=427, y=412.6875,
   w=38.65625, h=33. The placement math:
     panel anchor X (left) = trigger.cx - panel.w/2
                           = (427 + 38.65625/2) - panel.w/2
                           = 446.328 - 81.516 = 364.812  ✓
     panel anchor Y (top)  = trigger.bottom = 412.6875 + 33 = 445.6875 ✓
     panel offset Y        = +8px via translateY(8px)
   Pin a CSS-positioning recipe that lets sboxmarket place a sticker
   tooltip wrapper using the SAME offsets — anchor center-x to trigger
   center, anchor top to trigger bottom, then offset 8px down.
*/
body .csfloat-sticker-tooltip-host {
  position: absolute !important;
  pointer-events: none !important;
  z-index: 1500 !important;
}
body .csfloat-sticker-tooltip-host[data-placement="below"] {
  transform: translate(-50%, 8px) !important;
  transform-origin: center top !important;
}
body .csfloat-sticker-tooltip-host[data-placement="above"] {
  transform: translate(-50%, calc(-100% - 8px)) !important;
  transform-origin: center bottom !important;
}
body .csfloat-sticker-tooltip-host[data-placement="left"] {
  transform: translate(calc(-100% - 8px), -50%) !important;
  transform-origin: right center !important;
}
body .csfloat-sticker-tooltip-host[data-placement="right"] {
  transform: translate(8px, -50%) !important;
  transform-origin: left center !important;
}
/* END CSFLOAT-1:1 PARITY ship #128625 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128626 — sticker tooltip CDK overlay container
   Measured `.cdk-overlay-container` wrapping all live tooltips on csfloat:
     position:        fixed at the document root
     z-index:         1000+   (Material default; csfloat keeps the default)
     pointer-events:  none on the container (children re-enable as needed)
     dimensions:      cover full viewport
   Sboxmarket emits sticker tooltips from per-element local trees — pin
   the cdk-overlay-container vocabulary so any portal/teleport solution
   sboxmarket adopts will read identically. Also pin `.cdk-overlay-pane`
   inside it to be naked (the chrome lives on the surface).
*/
body .cdk-overlay-container,
body .csfloat-cdk-overlay-container {
  position: fixed !important;
  top: 0 !important;
  left: 0 !important;
  z-index: 1000 !important;
  width: 100% !important;
  height: 100% !important;
  pointer-events: none !important;
  contain: layout !important;
}
body .cdk-overlay-container .cdk-overlay-pane,
body .csfloat-cdk-overlay-container .csfloat-cdk-overlay-pane {
  position: absolute !important;
  pointer-events: auto !important;
  box-sizing: border-box !important;
  z-index: 1000 !important;
  display: flex !important;
  max-width: 100% !important;
  max-height: 100% !important;
}
/* The MDC panel default CSS sometimes leaks a Roboto fallback — re-pin
   font on the surface so it ALWAYS resolves correctly even when the
   page-level body font has been overridden by sboxmarket tokens. */
body .cdk-overlay-container .mdc-tooltip__surface,
body .cdk-overlay-container .mat-mdc-tooltip-surface,
body .csfloat-cdk-overlay-container .csfloat-sticker-tooltip-surface {
  font-family: Roboto, "Helvetica Neue", sans-serif !important;
  font-feature-settings: normal !important;
  font-variant-ligatures: normal !important;
  -webkit-font-smoothing: antialiased !important;
  -moz-osx-font-smoothing: grayscale !important;
}
/* END CSFLOAT-1:1 PARITY ship #128626 */
"""

target = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"
with open(target, 'rb') as f:
    f.seek(-4096, os.SEEK_END)
    tail = f.read().decode('utf-8', errors='replace')
if 'ship #128620' in tail:
    print('ALREADY APPENDED - ABORT')
    sys.exit(0)

tmpfd, tmpname = tempfile.mkstemp(suffix='.append', dir=os.path.dirname(target))
try:
    with open(target, 'rb') as orig, os.fdopen(tmpfd, 'wb') as out:
        while True:
            buf = orig.read(1 << 20)
            if not buf:
                break
            out.write(buf)
        out.write(CSS.encode('utf-8'))
    os.replace(tmpname, target)
    print('APPENDED', len(CSS), 'bytes -> ships #128620-#128626')
except Exception as e:
    if os.path.exists(tmpname):
        try: os.remove(tmpname)
        except: pass
    raise
