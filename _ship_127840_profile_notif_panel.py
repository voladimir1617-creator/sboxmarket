"""Atomic append corrections for csfloat-1:1 parity ships #127840-#127847.

Lane: /profile Notifications-tab content -- the per-event preferences
panel that csfloat surfaces under /profile sidebar > Notifications.
sboxmarket's nearest analog is the SettingsModal "Notifications & sound"
section + the .profile-row Email-notifications row in modals.js. These
ships pin the visual parity (toggle geometry, row accent bar, header
icon block, info card, section divider, label typography) so the
sboxmarket render matches csfloat pixel-by-pixel.

Measurements taken from a live screenshot of csfloat /profile (signed
in as voladimir1617@gmail.com) on 2026-05-06 at 1440 viewport. Toggle
sizes confirmed against MDC switch standard. APPEND-only, !important
throughout; no edits to existing rules. Tokens used:
  panel rgb(27,29,36) / brand rgb(35,123,255) /
  ink-2 rgb(158,167,177) / hairline rgba(255,255,255,0.06)
"""
from pathlib import Path

CSS = r"""
/* =====================================================================
   CSFLOAT-1:1 PARITY ship #127840 -- /profile Notifications panel,
   header icon block. csfloat surfaces a 64x64 rounded square with a
   soft brand-blue tint (rgba(35,123,255,0.10)) and a 28px filled mail
   icon centred inside, sitting to the left of the panel title block.
   Scope: .settings-row context inside an InfoModal/full-page settings
   surface that has a leading icon column. We expose this via a new
   .profile-notif-icon utility that the modal author can drop in
   adjacent to the Section() heading; existing settings keep the lean
   look unchanged. */
.profile-notif-icon {
  width: 64px !important;
  height: 64px !important;
  border-radius: 12px !important;
  background-color: rgba(35, 123, 255, 0.10) !important;
  border: 0 !important;
  display: inline-flex !important;
  align-items: center !important;
  justify-content: center !important;
  flex-shrink: 0 !important;
  color: rgb(35, 123, 255) !important;
}
.profile-notif-icon > svg {
  width: 28px !important;
  height: 28px !important;
  fill: currentColor !important;
}
/* END CSFLOAT-1:1 PARITY ship #127840 */

/* =====================================================================
   CSFLOAT-1:1 PARITY ship #127841 -- /profile Notifications panel,
   header title + subtitle typography. csfloat reference: title 22px /
   weight 600 / -0.02em tracking / colour rgb(255,255,255); subtitle
   14px / weight 400 / colour rgb(158,167,177) / 4px gap below the
   title. Existing .settings-section-heading clamps every heading to
   14px which is correct for INLINE section dividers but too small for
   the panel hero. New .profile-notif-title + .profile-notif-subtitle
   pair sit alongside the icon block. */
.profile-notif-title {
  font-size: 22px !important;
  font-weight: 600 !important;
  letter-spacing: -0.011em !important;
  line-height: 28px !important;
  color: rgb(255, 255, 255) !important;
  margin: 0 !important;
  text-transform: none !important;
}
.profile-notif-subtitle {
  font-size: 14px !important;
  font-weight: 400 !important;
  line-height: 20px !important;
  color: rgb(158, 167, 177) !important;
  margin: 4px 0 0 !important;
  letter-spacing: 0 !important;
}
.profile-notif-header {
  display: flex !important;
  align-items: center !important;
  gap: 20px !important;
  padding: 0 0 18px !important;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06) !important;
  margin: 0 0 8px !important;
}
/* END CSFLOAT-1:1 PARITY ship #127841 */

/* =====================================================================
   CSFLOAT-1:1 PARITY ship #127842 -- /profile Notifications panel
   per-row layout. csfloat each preference is a flex row with 18px
   vertical padding, the title+description column on the left, the
   MDC toggle on the right. There is NO bottom-divider hairline
   between rows -- the row separator is implicit through the 18px
   padding. We override the .settings-row default (which adds a 1px
   hairline) when the row is inside .profile-notif-rows. */
.profile-notif-rows .settings-row,
.profile-notif-rows > .settings-row {
  padding: 18px 0 18px 14px !important;
  border-bottom: 0 !important;
  position: relative !important;
  gap: 24px !important;
}
.profile-notif-rows .settings-row + .settings-row {
  margin-top: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #127842 */

/* =====================================================================
   CSFLOAT-1:1 PARITY ship #127843 -- /profile Notifications panel
   row LEFT BLUE ACCENT BAR. csfloat marks every ON row with a 3px
   wide brand-blue vertical stripe spanning the full row height,
   inset 0 from the row's left edge. OFF rows have no bar. We render
   it via a ::before pseudo on the row scoped by :has() targeting the
   inner toggle's .on / non-.off state. Falls back gracefully on
   browsers without :has() (Chromium 105+, Safari 15.4+, Firefox 121+
   all support it). */
.profile-notif-rows .settings-row::before {
  content: "" !important;
  position: absolute !important;
  left: 0 !important;
  top: 6px !important;
  bottom: 6px !important;
  width: 3px !important;
  border-radius: 2px !important;
  background-color: transparent !important;
  pointer-events: none !important;
  transition: background-color 160ms cubic-bezier(0.4, 0, 0.2, 1) !important;
}
.profile-notif-rows .settings-row:has(.toggle-switch:not(.off))::before,
.profile-notif-rows .settings-row:has(.toggle-switch.on)::before {
  background-color: rgb(35, 123, 255) !important;
}
/* END CSFLOAT-1:1 PARITY ship #127843 */

/* =====================================================================
   CSFLOAT-1:1 PARITY ship #127844 -- /profile Notifications panel
   row title + description typography. csfloat reference: title 14px
   weight 600 colour rgb(255,255,255); description 13px weight 400
   colour rgb(158,167,177); 4px gap between title and description.
   Existing .settings-row .settings-label clamps to weight 500 which
   is correct in the modal but too light in the profile panel hero. */
.profile-notif-rows .settings-row .settings-label,
.profile-notif-rows .settings-row > div > .settings-label {
  font-size: 14px !important;
  font-weight: 600 !important;
  line-height: 20px !important;
  color: rgb(255, 255, 255) !important;
  letter-spacing: 0 !important;
  margin: 0 0 4px !important;
  text-transform: none !important;
}
.profile-notif-rows .settings-row .settings-sublabel,
.profile-notif-rows .settings-row > div > .settings-sublabel {
  font-size: 13px !important;
  font-weight: 400 !important;
  line-height: 18px !important;
  color: rgb(158, 167, 177) !important;
  letter-spacing: 0 !important;
  margin: 0 !important;
  max-width: 560px !important;
}
/* END CSFLOAT-1:1 PARITY ship #127844 */

/* =====================================================================
   CSFLOAT-1:1 PARITY ship #127845 -- /profile Notifications panel
   toggle geometry. csfloat uses Material's MDC slide-toggle at the
   default 52x32 size, larger than the 36x20 we pin in the modal
   /settings rows. Knob diameter 24px, 4px padding from track edge,
   16px travel distance. Brand blue track when on, neutral grey
   rgba(255,255,255,0.18) when off (matches the existing #2302
   palette). Scoped to .profile-notif-rows so the modal /settings
   surface keeps the compact 36x20 toggle. */
.profile-notif-rows .settings-row .toggle-switch {
  width: 52px !important;
  height: 32px !important;
  border-radius: 999px !important;
  border: 0 !important;
  padding: 0 !important;
  background-color: rgba(255, 255, 255, 0.18) !important;
  position: relative !important;
  cursor: pointer !important;
  flex-shrink: 0 !important;
  transition: background-color 180ms cubic-bezier(0.4, 0, 0.2, 1) !important;
  display: inline-block !important;
  box-shadow: none !important;
}
.profile-notif-rows .settings-row .toggle-switch::after,
.profile-notif-rows .settings-row .toggle-switch::before {
  content: "" !important;
  position: absolute !important;
  top: 4px !important;
  left: 4px !important;
  width: 24px !important;
  height: 24px !important;
  border-radius: 50% !important;
  background-color: rgb(255, 255, 255) !important;
  margin: 0 !important;
  transition: transform 180ms cubic-bezier(0.4, 0, 0.2, 1) !important;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.4) !important;
}
.profile-notif-rows .settings-row .toggle-switch:not(.off),
.profile-notif-rows .settings-row .toggle-switch.on {
  background-color: rgb(35, 123, 255) !important;
}
.profile-notif-rows .settings-row .toggle-switch:not(.off)::after,
.profile-notif-rows .settings-row .toggle-switch:not(.off)::before,
.profile-notif-rows .settings-row .toggle-switch.on::after,
.profile-notif-rows .settings-row .toggle-switch.on::before {
  transform: translateX(20px) !important;
}
.profile-notif-rows .settings-row .toggle-switch.off {
  background-color: rgba(255, 255, 255, 0.18) !important;
}
.profile-notif-rows .settings-row .toggle-switch.off::after,
.profile-notif-rows .settings-row .toggle-switch.off::before {
  transform: translateX(0) !important;
}
.profile-notif-rows .settings-row .toggle-switch:focus-visible {
  outline: 2px solid rgb(35, 123, 255) !important;
  outline-offset: 3px !important;
}
/* END CSFLOAT-1:1 PARITY ship #127845 */

/* =====================================================================
   CSFLOAT-1:1 PARITY ship #127846 -- /profile Notifications panel,
   knob CHECKMARK and MINUS icons. csfloat embeds a 14x14 white
   checkmark inside the knob when ON, and a 14x14 white minus when
   OFF. Rendered via a background-image SVG on the ::after pseudo so
   no extra DOM is needed. Centered inside the 24px knob via 50% 50%
   positioning. */
.profile-notif-rows .settings-row .toggle-switch:not(.off)::after,
.profile-notif-rows .settings-row .toggle-switch.on::after {
  background-image: url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='14' height='14' viewBox='0 0 24 24' fill='none' stroke='rgb(35,123,255)' stroke-width='3' stroke-linecap='round' stroke-linejoin='round'><polyline points='20 6 9 17 4 12'/></svg>") !important;
  background-repeat: no-repeat !important;
  background-position: 50% 50% !important;
  background-size: 14px 14px !important;
}
.profile-notif-rows .settings-row .toggle-switch.off::after {
  background-image: url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='14' height='14' viewBox='0 0 24 24' fill='none' stroke='rgb(120,125,135)' stroke-width='3' stroke-linecap='round' stroke-linejoin='round'><line x1='5' y1='12' x2='19' y2='12'/></svg>") !important;
  background-repeat: no-repeat !important;
  background-position: 50% 50% !important;
  background-size: 14px 14px !important;
}
/* END CSFLOAT-1:1 PARITY ship #127846 */

/* =====================================================================
   CSFLOAT-1:1 PARITY ship #127847 -- /profile Notifications panel
   "About Notifications" footer info card. csfloat closes the panel
   with a rounded rgba(255,255,255,0.03) card containing a 20px info
   icon at left, a 13px weight 600 white title, and a 12px regular
   muted body explaining that important security notifications still
   send. Padding 16px / radius 12px / gap 16px between icon and copy. */
.profile-notif-info {
  display: flex !important;
  align-items: flex-start !important;
  gap: 16px !important;
  padding: 16px 18px !important;
  margin-top: 24px !important;
  background-color: rgba(255, 255, 255, 0.03) !important;
  border: 1px solid rgba(255, 255, 255, 0.06) !important;
  border-radius: 12px !important;
}
.profile-notif-info > svg,
.profile-notif-info > .profile-notif-info-icon {
  width: 20px !important;
  height: 20px !important;
  flex-shrink: 0 !important;
  color: rgb(158, 167, 177) !important;
  fill: currentColor !important;
  margin-top: 1px !important;
}
.profile-notif-info-title {
  font-size: 13px !important;
  font-weight: 600 !important;
  line-height: 18px !important;
  color: rgb(255, 255, 255) !important;
  margin: 0 0 2px !important;
  letter-spacing: 0 !important;
}
.profile-notif-info-body {
  font-size: 12px !important;
  font-weight: 400 !important;
  line-height: 17px !important;
  color: rgb(158, 167, 177) !important;
  margin: 0 !important;
  letter-spacing: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #127847 */
"""

def main():
    css_path = Path("src/main/resources/static/css/design.css")
    if not css_path.exists():
        raise SystemExit(f"design.css not found at {css_path}")
    # Atomic append: read+append+write to a tmp then os.replace -- POSIX
    # rename atomicity gives us crash-safety vs. partial writes.
    import os, tempfile
    original = css_path.read_text(encoding="utf-8")
    if "ship #127840" in original:
        print("ship #127840 already present; aborting to avoid duplicate")
        return
    new = original.rstrip() + "\n" + CSS.lstrip("\n")
    with tempfile.NamedTemporaryFile(
        "w", encoding="utf-8", delete=False,
        dir=str(css_path.parent), prefix="design.", suffix=".css.tmp"
    ) as fh:
        fh.write(new)
        tmp_name = fh.name
    os.replace(tmp_name, css_path)
    print(f"appended {len(CSS)} chars; new total {len(new)} chars / "
          f"{new.count(chr(10))+1} lines")

if __name__ == "__main__":
    main()
