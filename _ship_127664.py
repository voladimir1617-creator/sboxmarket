"""Atomic append of CSFLOAT-1:1 PARITY ship #127664 to design.css.

Measured live on csfloat.com /profile signed in as Chib skinbox.market.
The /profile page uses <mat-button-toggle-group>, NOT a bottom-underline tab pattern.
Container: 1250x38, bg rgba(193,206,255,0.04), border-radius 7px, padding 0.
Each toggle: h=32, font-size 16/400, color white, border-radius 4px (inner), no border.
Active toggle: bg rgba(193,206,255,0.04), white text.

Earlier ship #5700 styled profile-tabs with 12.5px text + bottom-underline pattern;
that's the WRONG pattern entirely. Reality is a Material segmented control with
background-fill active state. This ship overrides every previous profile-tabs rule.
"""
import io, os

PATCH = """

/* ============================================================
   CSFLOAT-1:1 PARITY ship #127664 (profile-tabs REAL measurement -- Material toggle-group)
   measured: csfloat.com /profile signed in as Chib skinbox.market.
   The /profile page uses <mat-button-toggle-group> NOT a bottom-underline tab pattern.
   Container: 1250x38, bg rgba(193,206,255,0.04), border-radius 7px, padding 0,
              display flex, gap normal (no inter-tab gap, but flex auto-fits widths).
   Each toggle: h=32px (38 - 2*3 inner padding), font-size 16px, font-weight 400,
                color white for both active+inactive, border-radius 4px (inner rounding),
                no border, no border-bottom underline.
   Active toggle: bg rgba(193,206,255,0.04) (subtle fill, NOT brand blue), white text.
   Inactive toggle: transparent bg.
   Earlier ship #5700 styled profile-tabs with 12.5px text + bottom-underline pattern;
   that's the WRONG pattern entirely. Reality is a Material segmented control with
   background-fill active state. Override every previous profile-tabs rule.
   ============================================================ */
html body .profile-tabs,
html body .profile-tabs[role="tablist"] {
  background: rgba(193, 206, 255, 0.04) !important;
  background-color: rgba(193, 206, 255, 0.04) !important;
  border: 0 !important;
  border-bottom: 0 !important;
  border-radius: 7px !important;
  padding: 3px !important;
  gap: 0 !important;
  display: flex !important;
  flex-wrap: nowrap !important;
  align-items: stretch !important;
  margin: 0 0 18px 0 !important;
  overflow-x: auto !important;
  overflow-y: hidden !important;
  scrollbar-width: none !important;
  height: 38px !important;
  min-height: 38px !important;
  box-sizing: border-box !important;
}
html body .profile-tabs::-webkit-scrollbar { display: none !important; }
html body .profile-tabs > button.profile-tab,
html body .profile-tabs > .profile-tab {
  background: transparent !important;
  background-color: transparent !important;
  color: rgb(255, 255, 255) !important;
  border: 0 !important;
  border-bottom: 0 !important;
  border-radius: 4px !important;
  padding: 0 16px !important;
  height: 32px !important;
  min-height: 32px !important;
  margin: 0 !important;
  font-family: Roboto, "Helvetica Neue", sans-serif !important;
  font-size: 16px !important;
  font-weight: 400 !important;
  line-height: 32px !important;
  letter-spacing: 0 !important;
  text-transform: none !important;
  display: inline-flex !important;
  align-items: center !important;
  justify-content: center !important;
  gap: 6px !important;
  white-space: nowrap !important;
  cursor: pointer !important;
  flex: 0 0 auto !important;
  box-shadow: none !important;
  text-decoration: none !important;
  outline: none !important;
}
html body .profile-tabs > button.profile-tab:hover,
html body .profile-tabs > .profile-tab:hover {
  background: rgba(255, 255, 255, 0.04) !important;
  color: rgb(255, 255, 255) !important;
}
html body .profile-tabs > button.profile-tab.active,
html body .profile-tabs > .profile-tab.active,
html body .profile-tabs > button.profile-tab[aria-selected="true"],
html body .profile-tabs > .profile-tab[aria-selected="true"] {
  background: rgba(193, 206, 255, 0.04) !important;
  background-color: rgba(193, 206, 255, 0.04) !important;
  color: rgb(255, 255, 255) !important;
  border-bottom: 0 !important;
  font-weight: 400 !important;
}
html body .profile-tabs > button.profile-tab.active::after,
html body .profile-tabs > .profile-tab.active::after,
html body .profile-tabs > button.profile-tab::after {
  display: none !important;
  content: none !important;
}
/* END CSFLOAT-1:1 PARITY ship #127664 */
"""

PATH = os.path.join(os.path.dirname(__file__), 'src','main','resources','static','css','design.css')
with io.open(PATH, 'a', encoding='utf-8', newline='\n') as f:
    f.write(PATCH)
print('Appended ship #127664 to', PATH)
print('New file size:', os.path.getsize(PATH))
