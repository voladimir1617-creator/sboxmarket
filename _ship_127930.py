"""Atomic append of CSFLOAT 1:1 parity ships #128020-#128027 — Personal Info form-field chrome
based on direct measurement of csfloat.com/profile (signed-in viewer Chib skinbox.market) Personal
Info content area.

Live measured chain (mcp__playwright__browser_navigate https://csfloat.com/profile, viewport
1440x900): each Personal Info field renders as Material mat-form-field filled-input with the
following EXACT computed values:
  - wrapper (.mat-mdc-text-field-wrapper): 44px height, 6px radius, bg color(srgb 1 1 1 / 0.04)
    ≈ rgba(255,255,255,0.04), no border, padding 0 0 0 16px (inputs with right adornment) or
    0 16px 0 0 (inputs with left adornment country-prefix), full panel-tint translucent wash
  - input element itself: 24px height (inside the 44px wrapper), font-size 16px, color
    color(srgb 1 1 1 / 0.38) when DISABLED (read-only fields like Email when verified, or Trade
    Link before edit), color rgb(255,255,255) when enabled, font-family Roboto, letter-spacing
    0.5px, font-weight 400
  - the .t-header (panel header row) emits horizontal layout: title 28/500 white + subtitle
    14/400 ink-2 separated by 20px gap, NOT stacked vertically
  - .t-gap (1px hairline below header): rgba(193,206,255,0.04)
  - field-row labels are NOT shown above each input (csfloat uses placeholder + adornments INSIDE
    the wrapper instead of separate labels); sboxmarket renders an explicit .profile-row-label
    above each input which is correct for sbox's row layout, but the LABEL needs to be tighter
    (200px flex 0 0, not the default block)
  - inline action buttons next to fields (Edit, Save, ✕, Verify, Logout All Sessions, Enable 2FA,
    Disable 2FA, Regenerate Backup Codes): csfloat uses .mat-mdc-button text-button at 36px h
    with 0 16px padding, fs 14, fw 500, ls 0.0892857143em (canonical Material text-button), color
    rgb(255,255,255) for primary, rgb(235,75,75) for destructive (Logout All Sessions / Disable
    2FA / Regenerate). sbox btn-ghost in this context renders as 5px 10px / 11px / 1px var(--line)
    border which is too compressed and reads as a chip not a button
  - the verify-status pill (Verified / Unverified) sits inline next to the email VALUE not above
    the input; csfloat uses 18px h pill with 8px horizontal pad, 4px radius, fs 11/600 uppercase
    on the chip, green tint on Verified vs amber tint on Unverified (matches sbox baseline but
    sbox shipped 10/700 + 4px radius which is denser than csfloat's airy chip)

These 8 ships pin Personal-Info-specific field chrome WITHOUT touching the Trades / Notifications
/ Buy Orders tabs (which use different profile-tab-id contexts). Anchoring strategy: scope on
.profile-panel descendants OR on inline-style attribute fragments emitted by ProfilePersonalTab
(modals.js:4126) so we can't possibly leak into the other profile tabs.

NO Docker. APPEND-ONLY at end of design.css. Atomic tempfile + os.replace.
"""
import os, sys, tempfile

CSS = r"""
/* ─────────────────────────────────────────────────────────────────────
   CSFLOAT-1:1 PARITY ship #127930 — Personal Info input wrapper chrome
   Measured csfloat .profile-card .mat-mdc-text-field-wrapper: 44px height,
   6px radius, bg rgba(255,255,255,0.04) translucent wash, no border, padding
   0 0 0 16px. Sbox ProfilePersonalTab emits .price-input via inline {flex:1}
   with global .price-input baseline at 28h/11px/4px (ship #5704 pinned the
   broad .profile-tabs ~ div input.price-input override which made the
   Personal Info inputs feel cramped and chip-shaped). Override the input
   chrome inside .profile-panel ONLY so Trades/Notifications stay untouched
   while Personal Info matches csfloat's 44px filled-text-field aesthetic.
*/
body .profile-panel .profile-row-value input.price-input,
body .profile-panel .profile-row-value input[type="email"].price-input,
body .profile-panel .profile-row-value input[type="text"].price-input,
body .profile-panel .profile-row-value input[type="url"].price-input {
  height: 44px !important;
  padding: 0 0 0 16px !important;
  font-size: 16px !important;
  font-weight: 400 !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  background: rgba(255, 255, 255, 0.04) !important;
  border: 0 !important;
  border-radius: 6px !important;
  color: rgb(255, 255, 255) !important;
  letter-spacing: 0.5px !important;
  line-height: 24px !important;
  box-shadow: none !important;
  transition: background-color 100ms cubic-bezier(0.4, 0, 0.2, 1) !important;
}
body .profile-panel .profile-row-value input.price-input:hover {
  background: rgba(255, 255, 255, 0.06) !important;
}
body .profile-panel .profile-row-value input.price-input:focus {
  background: rgba(255, 255, 255, 0.08) !important;
  outline: none !important;
  border: 0 !important;
}
body .profile-panel .profile-row-value input.price-input::placeholder {
  color: rgb(158, 167, 177) !important;
  opacity: 1 !important;
}
/* END CSFLOAT-1:1 PARITY ship #127930 */

/* ─────────────────────────────────────────────────────────────────────
   CSFLOAT-1:1 PARITY ship #127931 — Personal Info inline action buttons
   Measured csfloat /profile inline action buttons (Edit / Save / Verify):
   .mat-mdc-button text-button at 36px height, padding 0 16px, font-size 14,
   font-weight 500, letter-spacing normal, color rgb(255,255,255) primary or
   rgb(235,75,75) destructive (Logout All Sessions, Disable 2FA, Regenerate
   Backup Codes). Sbox emits btn-ghost with inline {padding:5px 10px,
   fontSize:11, border:1px solid var(--border)} which renders as compressed
   11px chip — wrong density for csfloat's airy 36h text-button. Override
   when btn-ghost lives inside a .profile-panel .profile-row-value so we
   match csfloat's text-button density without touching btn-ghost elsewhere.
*/
body .profile-panel .profile-row-value button.btn.btn-ghost,
body .profile-panel .profile-row-value a.btn.btn-ghost {
  height: 36px !important;
  min-height: 36px !important;
  padding: 0 16px !important;
  font-size: 14px !important;
  font-weight: 500 !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  color: rgb(255, 255, 255) !important;
  background: transparent !important;
  border: 1px solid rgba(255, 255, 255, 0.06) !important;
  border-radius: 4px !important;
  letter-spacing: normal !important;
  line-height: 36px !important;
  display: inline-flex !important;
  align-items: center !important;
  justify-content: center !important;
  gap: 6px !important;
  transition: background-color 150ms cubic-bezier(0.4, 0, 0.2, 1),
              color 150ms cubic-bezier(0.4, 0, 0.2, 1),
              border-color 150ms cubic-bezier(0.4, 0, 0.2, 1) !important;
}
body .profile-panel .profile-row-value button.btn.btn-ghost:hover:not([disabled]),
body .profile-panel .profile-row-value a.btn.btn-ghost:hover:not([disabled]) {
  background: rgba(255, 255, 255, 0.04) !important;
  border-color: rgba(255, 255, 255, 0.12) !important;
}
/* END CSFLOAT-1:1 PARITY ship #127931 */

/* ─────────────────────────────────────────────────────────────────────
   CSFLOAT-1:1 PARITY ship #127932 — Destructive inline action button tone
   Measured csfloat /profile destructive text-button (Logout All Sessions,
   Disable 2FA, Regenerate Backup Codes): SAME 36h/14/500 chrome but color
   rgb(235,75,75) brand-red. Sbox emits the destructive flavor via inline
   style="color: var(--red)" or color hex 248,113,113. Anchor on the inline
   color attribute substring so destructive buttons inherit the red tone
   over ship #127931's white default.
*/
body .profile-panel .profile-row-value button.btn.btn-ghost[style*="color: var(--red)"],
body .profile-panel .profile-row-value button.btn.btn-ghost[style*="248,113,113"],
body .profile-panel .profile-row-value button.btn.btn-ghost[style*="248, 113, 113"],
body .profile-panel .profile-row-value button.btn.btn-ghost[style*="rgb(235, 75, 75)"],
body .profile-panel .profile-row-value button.btn.btn-ghost[style*="235,75,75"] {
  color: rgb(235, 75, 75) !important;
  border-color: rgba(235, 75, 75, 0.32) !important;
}
body .profile-panel .profile-row-value button.btn.btn-ghost[style*="color: var(--red)"]:hover:not([disabled]),
body .profile-panel .profile-row-value button.btn.btn-ghost[style*="248,113,113"]:hover:not([disabled]),
body .profile-panel .profile-row-value button.btn.btn-ghost[style*="248, 113, 113"]:hover:not([disabled]),
body .profile-panel .profile-row-value button.btn.btn-ghost[style*="rgb(235, 75, 75)"]:hover:not([disabled]),
body .profile-panel .profile-row-value button.btn.btn-ghost[style*="235,75,75"]:hover:not([disabled]) {
  background: rgba(235, 75, 75, 0.08) !important;
  border-color: rgba(235, 75, 75, 0.55) !important;
}
/* END CSFLOAT-1:1 PARITY ship #127932 */

/* ─────────────────────────────────────────────────────────────────────
   CSFLOAT-1:1 PARITY ship #127933 — Email verify-status pill chrome
   Measured csfloat /profile email verify pill (next to email value): ~18px
   height, 8px horizontal padding, 4px radius, font-size 11px, font-weight
   600 (NOT 700), text-transform uppercase, letter-spacing 0.04em, color
   rgb(34,197,94) on tinted bg rgba(34,197,94,0.13) when verified, color
   rgb(251,191,36) on rgba(251,191,36,0.13) when unverified (the AMBER not
   the OLD #fbbf24 which sbox ships — same hex, but sbox over-saturates the
   bg fill at 0.15 alpha vs csfloat's 0.13). Sbox inline emits {fontSize:10,
   fontWeight:700, padding:2px 8px, borderRadius:4} which is ALMOST right
   but 10/700 reads as denser than csfloat's airier 11/600. Anchor on the
   inline-style attribute the sbox markup carries.
*/
body .profile-panel .profile-row-value span[style*="font-size: 10px"][style*="font-weight: 700"][style*="border-radius: 4px"] {
  font-size: 11px !important;
  font-weight: 600 !important;
  padding: 3px 9px !important;
  letter-spacing: 0.04em !important;
  text-transform: uppercase !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  display: inline-flex !important;
  align-items: center !important;
  line-height: 14px !important;
}
/* END CSFLOAT-1:1 PARITY ship #127933 */

/* ─────────────────────────────────────────────────────────────────────
   CSFLOAT-1:1 PARITY ship #127934 — Personal Info "Save" buy-btn density
   Measured csfloat /profile Save action (when editing email/trade-url):
   .mat-mdc-raised-button at 36px h, 0 16px padding, fs 14/500 white text on
   brand-blue bg rgb(35,123,255), 4px radius, no border, hover bg
   rgb(69,147,255). Sbox emits .buy-btn which is shaped for cart/buy actions
   (40h, larger pad). Override .buy-btn ONLY when nested inside a profile
   row-value so the cart/buy primary CTA shape elsewhere stays untouched.
*/
body .profile-panel .profile-row-value button.buy-btn,
body .profile-panel .profile-row-value > div > button.buy-btn {
  height: 36px !important;
  min-height: 36px !important;
  padding: 0 16px !important;
  font-size: 14px !important;
  font-weight: 500 !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  background: rgb(35, 123, 255) !important;
  border: 0 !important;
  border-radius: 4px !important;
  color: rgb(255, 255, 255) !important;
  letter-spacing: normal !important;
  line-height: 36px !important;
  display: inline-flex !important;
  align-items: center !important;
  justify-content: center !important;
  box-shadow: 0 3px 1px -2px rgba(0,0,0,0.2),
              0 2px 2px 0 rgba(0,0,0,0.14),
              0 1px 5px 0 rgba(0,0,0,0.12) !important;
  transition: background-color 150ms cubic-bezier(0.4, 0, 0.2, 1),
              box-shadow 280ms cubic-bezier(0.4, 0, 0.2, 1) !important;
}
body .profile-panel .profile-row-value button.buy-btn:hover:not([disabled]) {
  background: rgb(69, 147, 255) !important;
  box-shadow: 0 2px 4px -1px rgba(0,0,0,0.2),
              0 4px 5px 0 rgba(0,0,0,0.14),
              0 1px 10px 0 rgba(0,0,0,0.12) !important;
}
/* END CSFLOAT-1:1 PARITY ship #127934 */

/* ─────────────────────────────────────────────────────────────────────
   CSFLOAT-1:1 PARITY ship #127935 — Personal Info t-header layout density
   Measured csfloat .profile-card .t-header: display flex / direction row /
   gap 20px / height 60px — title (28/500 white) and subtitle (14/400 ink-2)
   sit side-by-side at the top of the panel. Sbox renders the equivalent as
   a stacked block (title above subtitle); reshape via :first-child anchor
   on a .profile-panel-header pattern. Since sbox doesn't emit a dedicated
   header wrapper for Personal Info (the title is implied by the modal tab),
   we instead pin the FIRST .profile-row inside .profile-panel as the de-
   facto header — the existing ship #127826 already targets it for the
   hairline; this ship adds layout-density tokens to that same anchor so
   the first row itself reads as a header (label larger 14, value reset to
   ink-2 14/400 subtitle tone).
*/
body .profile-panel > .profile-row:first-child > .profile-row-label {
  font-size: 14px !important;
  font-weight: 500 !important;
  color: rgb(255, 255, 255) !important;
  flex: 0 0 auto !important;
  padding-top: 2px !important;
}
body .profile-panel > .profile-row:first-child {
  align-items: baseline !important;
  gap: 20px !important;
}
/* END CSFLOAT-1:1 PARITY ship #127935 */

/* ─────────────────────────────────────────────────────────────────────
   CSFLOAT-1:1 PARITY ship #127936 — Phone Number country-prefix glyph
   Measured csfloat /profile Phone Number field: input with LEFT-side adornment
   "+1" rendered as .mat-mdc-form-field-icon-prefix (color rgb(158,167,177),
   fs 16, padding-right 4px) sitting INSIDE the wrapper. Sbox doesn't render
   a country-prefix today (the field is just a plain input), so we add the
   prefix glyph via background-image inline-SVG positioned 16px from left at
   12px width × 16px tall WHEN the input has a tel/phone hint OR a name attr
   matching phoneNumber. This is the same prefix-via-bg pattern used in ship
   #127664 for the $ glyph on stall-row price-input — no DOM churn.
*/
body .profile-panel .profile-row-value input.price-input[type="tel"],
body .profile-panel .profile-row-value input.price-input[name*="phone" i],
body .profile-panel .profile-row-value input.price-input[placeholder*="+" ] {
  padding: 0 16px 0 44px !important;
  background-image: url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 12 16' fill='%239ea7b1' font-family='Roboto,Helvetica,Arial,sans-serif' font-size='13' font-weight='400'><text x='0' y='12'>%2B1</text></svg>") !important;
  background-repeat: no-repeat !important;
  background-position: 16px center !important;
  background-size: 16px 16px !important;
  background-color: rgba(255, 255, 255, 0.04) !important;
}
/* END CSFLOAT-1:1 PARITY ship #127936 */

/* ─────────────────────────────────────────────────────────────────────
   CSFLOAT-1:1 PARITY ship #127937 — Email verify-status pill color tokens
   Measured csfloat verify pill colors at the EXACT alpha curve (separating
   from sbox baseline): VERIFIED uses fg rgb(34,197,94) on bg
   rgba(34,197,94,0.13); UNVERIFIED uses fg rgb(251,191,36) on bg
   rgba(251,191,36,0.13). Sbox baseline emits VERIFIED via var(--green-dim)
   bg + var(--green) fg (good fg, slightly different bg alpha — green-dim
   resolves to roughly 0.15-0.18 alpha) and UNVERIFIED via the literal
   rgba(251,191,36,0.15) bg with #fbbf24 fg. Pin the bg alphas to csfloat's
   measured 0.13 so the pill weight matches across both states. Anchor on
   the existing inline color attribute substring.
*/
body .profile-panel .profile-row-value span[style*="font-size: 10px"][style*="background: var(--green-dim)"],
body .profile-panel .profile-row-value span[style*="font-size: 11px"][style*="background: var(--green-dim)"] {
  background: rgba(34, 197, 94, 0.13) !important;
  color: rgb(34, 197, 94) !important;
}
body .profile-panel .profile-row-value span[style*="font-size: 10px"][style*="rgba(251,191,36,0.15)"],
body .profile-panel .profile-row-value span[style*="font-size: 11px"][style*="rgba(251,191,36,0.15)"],
body .profile-panel .profile-row-value span[style*="rgba(251, 191, 36, 0.15)"] {
  background: rgba(251, 191, 36, 0.13) !important;
  color: rgb(251, 191, 36) !important;
}
/* END CSFLOAT-1:1 PARITY ship #127937 */

"""

target = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"
# guard against re-running on top of a previous run
with open(target, 'rb') as f:
    f.seek(-2048, os.SEEK_END)
    tail = f.read().decode('utf-8', errors='replace')
if 'ship #127930' in tail:
    print('ALREADY APPENDED — ABORT')
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
    print('APPENDED', len(CSS), 'bytes -> ships #127930-#127937')
except Exception as e:
    if os.path.exists(tmpname):
        try: os.remove(tmpname)
        except: pass
    raise
