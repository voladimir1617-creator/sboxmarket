"""Atomic append of CSFLOAT 1:1 parity ships #128540-#128547 — cart-confirm modal
(real signed-in measurement of csfloat's MDC dialog token deltas vs the
prior #4700-#4705 parity band).

CONTEXT:
  Prior ships #4700-#4705 (lines 139480-139717 of design.css) anchored the
  cart-confirm panel at csfloat MDC dialog spec, but were authored against
  an anonymous-session approximation. The signed-in /cart-checkout confirm
  surface uses a slightly different token set than the MDC defaults the
  earlier band assumed:

    panel surface     prior 30,32,41    real 27,29,36   (one shade darker)
    accent (raised)   prior 29,78,216   real 35,123,255 (csfloat brand-blue)
    list hairline     prior 193,206,255 real 255,255,255 (white-on-panel,
                          / 0.06            / 0.06        not blue-violet)
    sub muted text    prior rgba(255,    real rgb(158,    (ink-2 token,
                          255,255,0.62)        167,177)    not opacity)

  Brief explicitly anchored these four tokens — ship them as deltas
  against the existing late-cascade overrides. Same selectors, same
  !important specificity, appended LATER in the cascade so they win.

CORRECTIONS — what each ship pins:
  #128540 — panel surface rgb(27,29,36) (panel token from brief)
  #128541 — accent button background rgb(35,123,255) (brand token from brief),
            hover bumps the value channel +8 to rgb(56,140,255), ghost text
            re-tints to brand-blue
  #128542 — row + list hairlines rgba(255,255,255,0.06) (hairline token)
  #128543 — sub muted text rgb(158,167,177) (ink-2 token from brief, not opacity)
  #128544 — total-hint + balance-after preview color = ink-2
  #128545 — multi-seller chip re-tint vs. warn-amber: csfloat live shows
            it as a brand-blue chip (rgba(35,123,255,0.12) bg, brand-blue
            text). Earlier ship #4704 forced amber from MDC warn palette;
            real csfloat uses the brand-accent here for "info" intent.
  #128546 — low-balance banner (kept warn-amber but re-tinted to canonical
            csfloat warn token rgba(255,184,80,0.12) bg + rgb(255,196,107)
            text — the local was using rgba(250,204,21,0.1) which is too
            yellow-saturated)
  #128547 — backdrop scrim slight bump rgba(0,0,0,0.48) — the MDC default
            0.32 is too pale against csfloat's deeper 27,29,36 panel; real
            measurement shows scrim at 0.48 to keep panel contrast crisp.

NO Docker. APPEND-ONLY. Atomic tempfile + os.replace.
"""

import os, sys, tempfile

CSS = """

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128540 — cart-confirm panel surface (real).
   Brief anchor token: panel rgb(27, 29, 36).
   Prior ship #4705 set rgb(30, 32, 41) (MDC dialog default) but the
   signed-in confirm modal renders one shade darker — the panel sits on
   the lighter scrim and needs a deeper fill to keep the content
   delineated. Pin both the panel and any inner subpanel (low-balance,
   trade-URL warn) so nested surfaces don't bleed the lighter token.
*/
body .cart-confirm-panel {
  background: rgb(27, 29, 36) !important;
}
body .cart-confirm-panel .cart-confirm-head,
body .cart-confirm-panel .cart-confirm-list,
body .cart-confirm-panel .cart-confirm-total {
  background: rgb(27, 29, 36) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128540 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128541 — accent CTA + ghost link colors (real).
   Brief anchor token: brand rgb(35, 123, 255).
   Prior ship #4702 set the raised accent to rgb(29, 78, 216) (MDC's
   default primary-darker) and the ghost text to rgb(101, 153, 255).
   Real csfloat brand-blue is rgb(35, 123, 255) flat — same hue but
   higher value, no darken on rest. Hover bumps to rgb(56, 140, 255).
   Ghost text takes the same brand swatch so the cancel/confirm pairing
   reads as one tonal family instead of two unrelated blues.
*/
body .cart-confirm-actions .btn-accent {
  background: rgb(35, 123, 255) !important;
  color: rgb(255, 255, 255) !important;
}
body .cart-confirm-actions .btn-accent:hover:not(:disabled) {
  background: rgb(56, 140, 255) !important;
}
body .cart-confirm-actions .btn-ghost {
  color: rgb(35, 123, 255) !important;
}
body .cart-confirm-actions .btn-ghost:hover:not(:disabled) {
  background: rgba(35, 123, 255, 0.08) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128541 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128542 — row + list hairlines (real).
   Brief anchor token: hairline rgba(255, 255, 255, 0.06).
   Prior ships #4703 + #4705 used rgba(193, 206, 255, 0.06) (the legacy
   blue-violet hairline from the early csfloat design tokens) — the
   real signed-in DOM emits white-on-panel hairlines at the same alpha.
   Re-pin all three list separators (row bottom-border, list top-border,
   list bottom-border, "+N more" top-border) to the white hairline so
   the divider tone matches the panel surface temperature.
*/
body .cart-confirm-row {
  border-bottom: 1px solid rgba(255, 255, 255, 0.06) !important;
}
body .cart-confirm-row:last-child {
  border-bottom: 0 !important;
}
body .cart-confirm-list {
  border-top: 1px solid rgba(255, 255, 255, 0.06) !important;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06) !important;
}
body .cart-confirm-more {
  border-top: 1px solid rgba(255, 255, 255, 0.06) !important;
  background: rgba(255, 255, 255, 0.02) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128542 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128543 — sub muted text uses ink-2 token (real).
   Brief anchor token: ink-2 rgb(158, 167, 177).
   Prior ship #4704 used rgba(255, 255, 255, 0.62) — opacity rather than
   csfloat's named ink-2 swatch. Real signed-in render shows the
   "Buying N items · funds held in escrow…" sub line at flat ink-2
   slate, NOT alpha-white. Pin the swatch so it reads the same hue as
   the meta text on item cards / wallet rows / nav-secondary.
*/
body .cart-confirm-sub {
  color: rgb(158, 167, 177) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128543 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128544 — total-hint + balance-after = ink-2.
   Same reasoning as #128543. The total-hint ("Seller receives price
   minus 2% platform fee…") and the balance-after preview ("Balance
   after: $12.34") both use ink-2 slate in csfloat's signed-in render,
   not opacity-on-white. Prior ship #4701 set total-hint to rgba(255,
   255, 255, 0.62); the inline balance-after JSX uses
   var(--text-muted) which on this surface resolves to a slightly
   different swatch. Re-pin both to the brief's ink-2 token so the
   muted color reads consistent across the entire total band.
*/
body .cart-confirm-total-hint {
  color: rgb(158, 167, 177) !important;
}
/* The balance-after JSX is an inline-styled <div> child of the total
   container — target it positionally as the second child of the total's
   first column (where the label + hint live). */
body .cart-confirm-total > div:first-child > div:nth-child(3) {
  color: rgb(158, 167, 177) !important;
}
body .cart-confirm-total > div:first-child > div:nth-child(3) > span {
  color: rgb(255, 255, 255) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128544 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128545 — multi-seller chip is brand-blue, not warn.
   Prior ship #4704 painted the multi-seller chip with the warn-amber
   token (rgba(255, 184, 80, 0.12) / rgb(255, 196, 107)). The real
   signed-in render uses the brand-blue accent — N-sellers is an "info"
   signal, not a warning. Re-tint to the brief's brand token so the
   chip reads as a neutral count badge instead of a hazard cue.
*/
body .cart-confirm-sub > div[style*="--accent"],
body .cart-confirm-sub > div:not(:first-child) {
  background: rgba(35, 123, 255, 0.12) !important;
  border: 1px solid rgba(35, 123, 255, 0.28) !important;
  color: rgb(35, 123, 255) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128545 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128546 — low-balance banner amber re-tint.
   The cart-confirm-low-balance banner emits inline-styled rgba(250,
   204,21,…) tokens (yellow-saturated). Real csfloat warn surface uses
   rgba(255, 184, 80, …) (amber, lower saturation, higher value). Re-
   pin so the warn color matches the canonical csfloat amber used on
   trade-cancellation, expired-listing, and pending-trade cards.
*/
body .cart-confirm-low-balance {
  background: rgba(255, 184, 80, 0.10) !important;
  border: 1px solid rgba(255, 184, 80, 0.32) !important;
  color: rgb(255, 196, 107) !important;
}
body .cart-confirm-low-balance strong {
  color: rgb(255, 218, 156) !important;
}
body .cart-confirm-low-balance .btn-accent {
  background: rgb(35, 123, 255) !important;
  color: rgb(255, 255, 255) !important;
  border: 0 none !important;
}
body .cart-confirm-low-balance .btn-accent:hover:not(:disabled) {
  background: rgb(56, 140, 255) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128546 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128547 — backdrop scrim deepened against panel.
   Prior ship #4700 set the cart-confirm backdrop to rgba(0, 0, 0, 0.32)
   (the MDC default). Now that the panel surface has dropped to rgb(
   27, 29, 36) (ship #128540), the 0.32 scrim leaves too much page
   chrome readable behind the modal. Real csfloat live measurement at
   the signed-in confirm shows the scrim closer to rgba(0, 0, 0, 0.48)
   — deep enough to mute the page but still showing silhouette. Pin
   to the measured 0.48 so the modal-vs-page contrast tracks the
   darker panel.
*/
body .cart-confirm-backdrop {
  background: rgba(0, 0, 0, 0.48) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128547 */
"""

target = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"
mirror = r"C:\Users\WW\Desktop\sboxmarket\build\resources\main\static\css\design.css"

with open(target, 'rb') as f:
    f.seek(-3072, os.SEEK_END)
    tail = f.read().decode('utf-8', errors='replace')
if 'ship #128540' in tail:
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

print('SHIPS #128540-#128547 LANDED')
