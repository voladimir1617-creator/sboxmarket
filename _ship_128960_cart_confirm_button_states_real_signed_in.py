"""Atomic append of CSFLOAT 1:1 parity ships #128960-#128965 — cart-confirm
modal Buy/Cancel button :hover / :active / :focus-visible state overlays plus
the modal-body scrollbar treatment, anchored to LIVE measurements taken from
csfloat's MDC raised-button family (the cart-confirm-actions Buy button uses
the same .mdc-button--raised .mat-mdc-raised-button .mat-primary token bundle
as csfloat's nav Sign-in button, so every state-layer overlay opacity, every
elevation-shadow tier, and every focus-indicator border resolves through the
same --mat-button-protected-* / --mat-sys-* token cascade).

CONTEXT:
  Prior ship #128541 painted the cart-confirm Buy button :hover by *shifting
  the background fill* from rgb(35, 123, 255) to rgb(56, 140, 255) (a
  +21 lightness bump on the brand-blue). That is NOT what csfloat does. Live
  measurement of csfloat's nav Sign-in button (same MDC raised + mat-primary
  token group as cart Buy) shows the rest background stays at the canonical
  brand rgb(35, 123, 255) on every interactive state — the visual hover /
  focus / pressed feedback is painted ENTIRELY through MDC's
  .mat-mdc-button-persistent-ripple::before overlay, which sits on top of
  the button at z-index 1 and transitions ONLY its opacity. The overlay
  background-color is locked at white via --mat-button-protected-state-layer-color
  (#ffffff) and the per-state opacity comes from:

    --mat-button-protected-hover-state-layer-opacity   = 0.04
    --mat-button-protected-focus-state-layer-opacity   = 0.12
    --mat-button-protected-pressed-state-layer-opacity = 0.12

  Resolved live elevation tokens (--mat-button-protected-*-container-elevation-shadow):
    rest    = 0 3px 1px -2px rgba(0,0,0,.2),
              0 2px 2px  0   rgba(0,0,0,.14),
              0 1px 5px  0   rgba(0,0,0,.12)
    hover   = 0 2px 4px -1px rgba(0,0,0,.2),
              0 4px 5px  0   rgba(0,0,0,.14),
              0 1px 10px 0   rgba(0,0,0,.12)
    focus   = identical to hover
    pressed = 0 5px 5px  -3px rgba(0,0,0,.2),
              0 8px 10px  1   rgba(0,0,0,.14),
              0 3px 14px  2   rgba(0,0,0,.12)

  Resolved live scrollbar tokens (csfloat global ::-webkit-scrollbar):
    width                              = 12px
    track    bg/radius                 = transparent / 8px
    thumb    bg/radius                 = rgba(193,206,255,0.07) / 8px
    thumb:hover bg                     = rgba(193,206,255,0.25)

CORRECTIONS — what each ship pins:
  #128960 — Cart Buy :hover NO LONGER shifts background. Overlay-only feedback:
            background stays rgb(35,123,255), persistent-ripple::before
            opacity becomes 0.04 (white), elevation lifts to the hover tier.
            Explicitly UN-DOES ship #128541's rgb(56,140,255) bg shift.
  #128961 — Cart Buy :focus-visible — overlay opacity 0.12 white, focus
            elevation tier (same as hover), focus-indicator border kept
            transparent (csfloat doesn't override --mat-focus-indicator-
            border-color, so the 3px border ring is invisible by design).
  #128962 — Cart Buy :active (pressed) — overlay opacity 0.12 white, pressed
            elevation tier (5px/8px/3px tier — the deepest MDC elevation).
            No transform, no scale — csfloat doesn't translateY on press.
  #128963 — Cart Cancel (ghost text-button) :hover — text buttons resolve
            through --mat-button-text-* tokens. Real csfloat text-button
            hover overlay is also 0.04 white (measured live on the nav
            Database link). Prior ship #128541 set ghost :hover to
            rgba(35,123,255, 0.08) — that's a brand-blue tint, not the
            white state-layer. Re-pin to the white-on-bluetext canonical
            so the ghost button reads as the same MDC family as Buy.
  #128964 — Cart-confirm list (.cart-confirm-list) scrollbar — pin to the
            csfloat global ::-webkit-scrollbar tokens: 12px track,
            transparent track bg, rgba(193,206,255,0.07) thumb bg with
            8px radius, hover thumb to rgba(193,206,255,0.25). Includes
            scrollbar-color fallback for Firefox.
  #128965 — Cart Buy rest-state elevation: pin the layered MDC elevation
            shadow at rest so the button reads with the same depth as
            csfloat's live raised-button. The local .btn-accent rule
            uses a single drop shadow; csfloat layers three.

NO Docker. APPEND-ONLY. Atomic tempfile + os.replace.
"""

import os, sys, tempfile

CSS = """

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128960 — cart Buy :hover overlay-only (real).
   Measured live on csfloat's MDC raised-button. The brand-blue background
   does NOT shift on hover — feedback is the .mat-mdc-button-persistent-
   ripple::before overlay at white / 0.04 opacity plus the layered
   hover-tier elevation shadow. UN-DO ship #128541's rgb(56,140,255) bg
   shift (a +21 lightness bump) and instead paint the canonical 4% white
   veil via a ::before pseudo so the rest background stays brand-blue.
*/
body .cart-confirm-actions .btn-accent:hover:not(:disabled) {
  background: rgb(35, 123, 255) !important;
  box-shadow:
    0 2px 4px -1px rgba(0, 0, 0, 0.20),
    0 4px 5px 0   rgba(0, 0, 0, 0.14),
    0 1px 10px 0  rgba(0, 0, 0, 0.12) !important;
  filter: none !important;
  transform: none !important;
}
body .cart-confirm-actions .btn-accent {
  position: relative !important;
}
body .cart-confirm-actions .btn-accent::before {
  content: "" !important;
  position: absolute !important;
  inset: 0 !important;
  border-radius: inherit !important;
  background: rgb(255, 255, 255) !important;
  opacity: 0 !important;
  pointer-events: none !important;
  transition: opacity 0.15s cubic-bezier(0.4, 0, 0.2, 1) !important;
  z-index: 1 !important;
}
body .cart-confirm-actions .btn-accent:hover:not(:disabled)::before {
  opacity: 0.04 !important;
}
body .cart-confirm-actions .btn-accent > * {
  position: relative !important;
  z-index: 2 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128960 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128961 — cart Buy :focus-visible overlay (real).
   Measured live: --mat-button-protected-focus-state-layer-opacity = 0.12.
   The MDC focus-indicator border itself is transparent on csfloat (they
   don't override --mat-focus-indicator-border-color), so the visible
   focus signal IS the persistent-ripple overlay bumped from 0.04 to 0.12.
   No outline, no ring — just the deeper white veil + focus elevation.
*/
body .cart-confirm-actions .btn-accent:focus-visible:not(:disabled) {
  background: rgb(35, 123, 255) !important;
  outline: 0 !important;
  box-shadow:
    0 2px 4px -1px rgba(0, 0, 0, 0.20),
    0 4px 5px 0   rgba(0, 0, 0, 0.14),
    0 1px 10px 0  rgba(0, 0, 0, 0.12) !important;
}
body .cart-confirm-actions .btn-accent:focus-visible:not(:disabled)::before {
  opacity: 0.12 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128961 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128962 — cart Buy :active (pressed) state (real).
   Measured live: --mat-button-protected-pressed-state-layer-opacity = 0.12,
   pressed elevation jumps to the deepest MDC tier (5/8/3 px tier). No
   transform, no scale — csfloat raised buttons don't translate on press.
*/
body .cart-confirm-actions .btn-accent:active:not(:disabled) {
  background: rgb(35, 123, 255) !important;
  transform: none !important;
  box-shadow:
    0 5px 5px -3px rgba(0, 0, 0, 0.20),
    0 8px 10px 1px rgba(0, 0, 0, 0.14),
    0 3px 14px 2px rgba(0, 0, 0, 0.12) !important;
}
body .cart-confirm-actions .btn-accent:active:not(:disabled)::before {
  opacity: 0.12 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128962 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128963 — cart Cancel (ghost) :hover overlay (real).
   Measured live on csfloat nav text-button (mat-mdc-button, no raised
   variant): the persistent-ripple::before paints white at 0.04 opacity
   on hover, NOT a brand-tinted veil. Prior ship #128541 set ghost :hover
   bg to rgba(35,123,255,0.08) — that's a brand-blue wash; the canonical
   csfloat text-button uses the white state-layer the same as raised.
   Re-pin to white-4% so the ghost button uses the same MDC overlay
   semantics as the Buy button.
*/
body .cart-confirm-actions .btn-ghost:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.04) !important;
  color: rgb(35, 123, 255) !important;
}
body .cart-confirm-actions .btn-ghost:focus-visible:not(:disabled) {
  background: rgba(255, 255, 255, 0.12) !important;
  outline: 0 !important;
  color: rgb(35, 123, 255) !important;
}
body .cart-confirm-actions .btn-ghost:active:not(:disabled) {
  background: rgba(255, 255, 255, 0.12) !important;
  transform: none !important;
  color: rgb(35, 123, 255) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128963 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128964 — cart-confirm list scrollbar (real).
   Measured live on csfloat global ::-webkit-scrollbar:
     width 12px, track transparent / radius 8px,
     thumb rgba(193,206,255,0.07) / radius 8px,
     thumb:hover rgba(193,206,255,0.25).
   The cart-confirm-list defaults to whatever scrollbar the host browser
   ships, which on Chrome is the wide grey OS scrollbar. Pin the csfloat
   tokens scoped to .cart-confirm-list so the modal body scrolls with
   the canonical csfloat thin blue-violet thumb on transparent track.
   Includes scrollbar-color fallback for Firefox (thumb / track).
*/
body .cart-confirm-list {
  scrollbar-width: thin !important;
  scrollbar-color: rgba(193, 206, 255, 0.07) transparent !important;
}
body .cart-confirm-list::-webkit-scrollbar {
  width: 12px !important;
  background-color: transparent !important;
}
body .cart-confirm-list::-webkit-scrollbar-track {
  background-color: transparent !important;
  border-radius: 8px !important;
}
body .cart-confirm-list::-webkit-scrollbar-thumb {
  background-color: rgba(193, 206, 255, 0.07) !important;
  border-radius: 8px !important;
}
body .cart-confirm-list::-webkit-scrollbar-thumb:hover {
  background-color: rgba(193, 206, 255, 0.25) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128964 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128965 — cart Buy rest-state elevation (real).
   Measured live --mat-button-protected-container-elevation-shadow:
     0 3px 1px -2px rgba(0,0,0,.2),
     0 2px 2px  0   rgba(0,0,0,.14),
     0 1px 5px  0   rgba(0,0,0,.12)
   The local .btn-accent uses a single flat drop shadow; csfloat layers
   three (key + ambient + crisp). Pin the rest tier so the button has
   the same depth as csfloat's live raised-button.
*/
body .cart-confirm-actions .btn-accent {
  box-shadow:
    0 3px 1px -2px rgba(0, 0, 0, 0.20),
    0 2px 2px 0   rgba(0, 0, 0, 0.14),
    0 1px 5px 0   rgba(0, 0, 0, 0.12) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128965 */
"""

target = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"
mirror = r"C:\Users\WW\Desktop\sboxmarket\build\resources\main\static\css\design.css"

# Idempotency guard — read 16KB tail and check for the LAST ship marker
# (ship #128965 is the last in this batch; payload ~6.7KB so 16KB tail is safe)
with open(target, 'rb') as f:
    f.seek(-16384, os.SEEK_END)
    tail = f.read().decode('utf-8', errors='replace')
if 'ship #128965' in tail:
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

print('SHIPS #128960-#128965 LANDED')
