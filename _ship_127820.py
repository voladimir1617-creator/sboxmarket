"""Atomic append of CSFLOAT 1:1 parity ships #127820-127826 — Profile / Personal Info content area
based on direct measurement of csfloat.com/profile (signed-in viewer), persisted to design.css.

Each block targets the inline-styled DOM emitted by ProfilePersonalTab in modals.js — we cannot
add classes without churning JS, so we anchor on the inline-style attribute substrings.
"""
import os, sys, tempfile

CSS = r"""/* ─────────────────────────────────────────────────────────────────────
   CSFLOAT-1:1 PARITY ship #127820 — Profile-panel canonical card geometry
   csfloat /profile content cards (.profile-card) measure: bg rgb(27,29,36),
   border-radius 12px, padding 30px, no visible border (hairline only between
   header + content via .t-gap = 1px rgba(193,206,255,0.04)). Sbox baseline
   .profile-panel shipped pad 24, border 1px var(--line), radius var(--r-md)
   ≈8px and bg var(--bg-1) ≈ rgb(11,12,15). Bring the panel surface into
   csfloat's measured tokens so it stops looking like a debugger panel.
*/
body .profile-panel {
  padding: 30px !important;
  border-radius: 12px !important;
  background: rgb(27, 29, 36) !important;
  border: 0 !important;
  margin-bottom: 16px !important;
}
/* END CSFLOAT-1:1 PARITY ship #127820 */

/* ─────────────────────────────────────────────────────────────────────
   CSFLOAT-1:1 PARITY ship #127821 — ProfilePersonalTab Account-Standing
   header title typography. Measured csfloat .profile-card .title (the
   inner card title): font-size 24px / font-weight 500 / color rgb(255,255,255)
   / line-height 1.25. Sbox inline emits fontSize 16 / fontWeight 700,
   which renders as a chunky stub vs csfloat's airy heading. Inline anchor:
   the wrapper div carries inline `font-size: 16px; font-weight: 700;` and
   sits as the first child of the standing card row. Pin to canonical csfloat
   measurement.
*/
body div[style*="font-size: 16px"][style*="font-weight: 700"][style*="color: var(--text-primary)"] {
  font-size: 24px !important;
  font-weight: 500 !important;
  color: rgb(255, 255, 255) !important;
  line-height: 1.25 !important;
  letter-spacing: normal !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
}
/* END CSFLOAT-1:1 PARITY ship #127821 */

/* ─────────────────────────────────────────────────────────────────────
   CSFLOAT-1:1 PARITY ship #127822 — Account-Standing ladder node sizing
   Measured csfloat .standing-level (inactive node): 28x28 circle. Active
   .standing-level.current: 40x40 circle. Sbox inline emits 36 (inactive)
   / 46 (active) — both 8-12px too large, which makes the ladder visually
   crowd the card. Anchor on the inline-style width/height the markup
   emits and pin to csfloat tokens. Also the marginTop:-5 hop the active
   node uses to compensate for its larger size goes away — at 40 vs 28 the
   12-px delta sits comfortably without negative margin.
*/
body div[style*="width: 36px"][style*="height: 36px"][style*="border-radius: 50%"] {
  width: 28px !important;
  height: 28px !important;
}
body div[style*="width: 46px"][style*="height: 46px"][style*="border-radius: 50%"] {
  width: 40px !important;
  height: 40px !important;
  margin-top: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #127822 */

/* ─────────────────────────────────────────────────────────────────────
   CSFLOAT-1:1 PARITY ship #127823 — Account-Standing label typography
   Measured csfloat .standing-level-wrapper > span.label: font-size 14px /
   font-weight 100 (inactive) / color rgb(158,167,177) (ink-2). Active label
   (.label.current): same 14px but font-weight 700 / color rgb(255,255,255)
   white. Sbox inline emits 12px / 600 inactive / 800 active — too dense at
   12, and active uses brand color when csfloat uses pure white. The fw 100
   inactive is intentional — csfloat goes ultra-thin to emphasize the bold
   active label. Anchor inline labels and rebase to csfloat tokens.
*/
body div[style*="font-size: 12px"][style*="font-weight: 600"][style*="color: var(--text-muted)"][style*="letter-spacing: 0.2px"] {
  font-size: 14px !important;
  font-weight: 100 !important;
  color: rgb(158, 167, 177) !important;
  letter-spacing: normal !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
}
body div[style*="font-size: 12px"][style*="font-weight: 800"][style*="letter-spacing: 0.2px"] {
  font-size: 14px !important;
  font-weight: 700 !important;
  color: rgb(255, 255, 255) !important;
  letter-spacing: normal !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
}
/* END CSFLOAT-1:1 PARITY ship #127823 */

/* ─────────────────────────────────────────────────────────────────────
   CSFLOAT-1:1 PARITY ship #127824 — Account-Standing inactive node fill
   Measured csfloat inactive .standing-level: background rgba(193,206,255,0.04)
   (the canonical csfloat tinted-white-on-dark wash). Sbox inline emits
   var(--bg-elevated) which renders as a flat opaque grey, breaking the
   "translucent on the panel" effect csfloat depends on. Pin the inactive
   circle background to the measured rgba wash. Active node bg stays opaque
   white per ship #127825 below.
*/
body div[style*="width: 28px"][style*="height: 28px"][style*="border-radius: 50%"][style*="background: var(--bg-elevated)"] {
  background: rgba(193, 206, 255, 0.04) !important;
}
/* END CSFLOAT-1:1 PARITY ship #127824 */

/* ─────────────────────────────────────────────────────────────────────
   CSFLOAT-1:1 PARITY ship #127825 — Account-Standing ACTIVE node fill +
   icon color. Measured csfloat .standing-level.current (the active 40x40):
   background rgb(255,255,255) (pure white), icon color rgb(59,130,246)
   (the canonical csfloat brand-blue token). NOT brand-bg with white-icon
   like sbox is shipping. The csfloat treatment reads as "pinned/spotlight"
   against the dark panel — bright white disc with the brand-blue glyph
   floating inside. Pin the active node accordingly. The MaterialIcon nested
   inside is already a child element so we can target the bg via the active
   node selector and the icon color via its inline color attribute parents.
*/
body div[style*="width: 40px"][style*="height: 40px"][style*="border-radius: 50%"][style*="border: 2px solid"] {
  background: rgb(255, 255, 255) !important;
  box-shadow: 0 0 0 4px rgba(35, 123, 255, 0.13), 0 8px 24px rgba(35, 123, 255, 0.20) !important;
  border-color: rgb(255, 255, 255) !important;
}
body div[style*="width: 40px"][style*="height: 40px"][style*="border-radius: 50%"] > .material-icons,
body div[style*="width: 40px"][style*="height: 40px"][style*="border-radius: 50%"] > i,
body div[style*="width: 40px"][style*="height: 40px"][style*="border-radius: 50%"] > span {
  color: rgb(59, 130, 246) !important;
}
/* END CSFLOAT-1:1 PARITY ship #127825 */

/* ─────────────────────────────────────────────────────────────────────
   CSFLOAT-1:1 PARITY ship #127826 — Profile-panel header divider hairline
   Measured csfloat .profile-card .t-gap (the 1px line between the panel
   header and the content rows): width 1190 (matches inner-pad-deducted
   panel width), height 1px, background rgba(193,206,255,0.04). Sbox baseline
   has no equivalent — rows separate via dashed border-bottom on each
   .profile-row, which doesn't form a single canonical hairline below the
   panel header. Inject a ::before pseudo-element on the FIRST .profile-row
   inside .profile-panel so the panel-header → content-rows transition gets
   the canonical csfloat hairline without DOM churn. Also rebase the row
   border-bottom to the same exact rgba so all dividers track in lock-step.
*/
body .profile-panel > .profile-row:first-child {
  position: relative !important;
  margin-top: 8px !important;
  padding-top: 16px !important;
}
body .profile-panel > .profile-row:first-child::before {
  content: "" !important;
  position: absolute !important;
  top: 0 !important;
  left: 0 !important;
  right: 0 !important;
  height: 1px !important;
  background: rgba(193, 206, 255, 0.04) !important;
}
body .profile-row {
  border-bottom-color: rgba(193, 206, 255, 0.04) !important;
}
/* END CSFLOAT-1:1 PARITY ship #127826 */

"""

target = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"
# Read tail to guard against re-running
with open(target, 'rb') as f:
    f.seek(-2048, os.SEEK_END)
    tail = f.read().decode('utf-8', errors='replace')
if 'ship #127820' in tail:
    print('ALREADY APPENDED — ABORT')
    sys.exit(0)

# Atomic append: copy + append + rename
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
    print('APPENDED', len(CSS), 'bytes -> ships #127820-#127826')
except Exception as e:
    if os.path.exists(tmpname):
        try: os.remove(tmpname)
        except: pass
    raise
