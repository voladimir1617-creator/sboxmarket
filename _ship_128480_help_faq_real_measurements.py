"""Ship #128480 — CSFLOAT 1:1 PIXEL parity for the Help / FAQ surface.

Measured live against https://csfloat.com/faq (signed-in real measurement)
on this run. csfloat /help itself is a 404; the real FAQ lives at /faq and
exposes the canonical "panel chrome + 60x60 icon-tile + 28px title" pattern
csfloat re-uses across its single-page wrappers.

Key real measurements pulled from the live page (not from earlier
approximations) are encoded as comments next to each declaration.

APPEND-only. No edits to existing CSS rules — just a new override block
appended to design.css with !important so it wins over older approximate
ship rules.
"""
from __future__ import annotations
import os
import tempfile

CSS_PATH = r"c:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"

APPEND = """
/* CSFLOAT-1:1 PARITY ship #128480 - /faq panel chrome + header icon-tile real measurements
   Live source: https://csfloat.com/faq (signed in). Measured on this run:
   - body bg                rgb(21, 23, 28)            (already matches)
   - .container max-width   1337px
   - .container gap         30px (between header strip and body)
   - .container margin-top  60px
   - .faq-widget bg         rgb(27, 29, 36)            (panel token)
   - .faq-widget radius     12px
   - .faq-widget padding    30px
   - .faq-widget gap        20px (between header / gap / faq-content)
   - .header height         60px
   - .header gap            20px
   - .header .icon          60x60 box, bg rgba(193,206,255,0.04), radius 10px,
                            flex centered (the css-icon hero token)
   - .header .text gap      5px (column)
   - .header .text .title   28px / 500 weight, white
   - .header .text .sub-text 16px, ink-2 rgb(158,167,177)

   Earlier ship #19200-#19205 (support-form approximation) and #15900-#15906
   (onboarding/help-step layout approximation) hard-coded different values
   because they couldn't reach a real csfloat help page (csfloat uses
   Intercom for support so /help is a 404). With the real /faq surface now
   measured, this ship realigns the help-modal panel chrome to those tokens.

   The sboxmarket markup difference: HelpModal renders inside .modal.info-modal
   (in full-page mode that becomes a regular page panel), with
   .help-intro as the hero strip. We map csfloat's .header pattern onto
   .help-intro and the panel chrome onto .full-page-mode .info-modal so
   the route /help reads at the same rhythm as csfloat /faq. */

html body .full-page-mode .info-modal:has(.help-intro),
html body .site-root.full-page-mode .info-modal:has(.help-intro) {
  /* Panel chrome (csfloat .faq-widget): rgb(27,29,36) bg, 12px radius,
     30px padding, 20px gap between hero strip and body sections. */
  background: rgb(27, 29, 36) !important;
  background-color: rgb(27, 29, 36) !important;
  border-radius: 12px !important;
  border: 0 !important;
  box-shadow: none !important;
  padding: 30px !important;
  max-width: 1337px !important;
  margin: 60px auto 0 !important;
  display: flex !important;
  flex-direction: column !important;
  gap: 20px !important;
}

/* Hero header strip — csfloat .header treatment.
   Layout: flex row, 20px gap, height 60px when collapsed. The sboxmarket
   help-intro renders as <icon-svg> + <div with title and sub-text>; we
   restyle the icon to match the 60x60 icon-tile and the inner div to a
   gap:5px column with 28/500 title. */
html body .full-page-mode .info-modal .help-intro,
html body .site-root.full-page-mode .info-modal .help-intro {
  display: flex !important;
  flex-direction: row !important;
  align-items: stretch !important;
  gap: 20px !important;
  width: auto !important;
  max-width: none !important;
  margin: 0 !important;
  padding: 0 !important;
  text-align: left !important;
  min-height: 60px !important;
  background: transparent !important;
  border: 0 !important;
  border-radius: 0 !important;
  font-size: 16px !important;
  color: rgb(255, 255, 255) !important;
  line-height: normal !important;
  box-sizing: border-box !important;
}

/* The leading icon inside .help-intro becomes the 60x60 tile. The
   HelpModal markup uses <MaterialIcon name="info" size=22>; we wrap the
   icon glyph in a 60x60 flex-centered box per csfloat .header .icon. */
html body .full-page-mode .info-modal .help-intro > .material-symbols-rounded:first-child,
html body .full-page-mode .info-modal .help-intro > .mi:first-child,
html body .full-page-mode .info-modal .help-intro > svg:first-child,
html body .full-page-mode .info-modal .help-intro > [class*="material"]:first-child {
  flex: 0 0 60px !important;
  width: 60px !important;
  height: 60px !important;
  background: rgba(193, 206, 255, 0.04) !important;
  border-radius: 10px !important;
  display: inline-flex !important;
  align-items: center !important;
  justify-content: center !important;
  font-size: 28px !important;            /* visible glyph size inside the tile */
  color: rgb(255, 255, 255) !important;
  margin: 0 !important;
  padding: 0 !important;
  box-sizing: border-box !important;
}

/* The text column to the right of the icon — csfloat .header .text:
   flex column, 5px gap, contains .title (28/500) and .sub-text (16px ink-2). */
html body .full-page-mode .info-modal .help-intro > div:not(:first-child),
html body .full-page-mode .info-modal .help-intro > div:nth-child(2) {
  display: flex !important;
  flex-direction: column !important;
  gap: 5px !important;
  flex: 1 1 auto !important;
  min-width: 0 !important;
  align-self: center !important;
  background: transparent !important;
}

/* Title row inside .help-intro — csfloat .header .text .title (28/500). */
html body .full-page-mode .info-modal .help-intro > div:not(:first-child) > div:first-child,
html body .full-page-mode .info-modal .help-intro > div:nth-child(2) > div:first-child {
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 28px !important;
  font-weight: 500 !important;
  line-height: normal !important;
  letter-spacing: normal !important;
  color: rgb(255, 255, 255) !important;
  margin: 0 !important;
  padding: 0 !important;
}

/* Subtitle / sub-text — csfloat .header .text .sub-text (16/400, ink-2). */
html body .full-page-mode .info-modal .help-intro > div:not(:first-child) > div:nth-child(2),
html body .full-page-mode .info-modal .help-intro > div:nth-child(2) > div:nth-child(2) {
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 16px !important;
  font-weight: 400 !important;
  line-height: normal !important;
  letter-spacing: normal !important;
  color: rgb(158, 167, 177) !important;
  margin: 0 !important;
  padding: 0 !important;
}

/* Container body gap (csfloat .container gap:30px between header strip and content).
   Sections below .help-intro should have a consistent vertical rhythm:
   .help-section-title rows act like the row dividers, so reset their
   asymmetric top/bottom margins and rely on the parent panel's flex gap
   plus a bottom-margin for breathing room before the next section's body. */
html body .full-page-mode .info-modal .help-intro + .help-section-title {
  margin-top: 30px !important;
}

/* Section heading typography on the help page — csfloat reuses the same
   28/500 title weight at the section level; keep h2 readable but trim the
   legacy 18/500 toward something proportional to the 28 hero. */
html body .full-page-mode .info-modal .help-section-title {
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 18px !important;
  font-weight: 500 !important;
  line-height: 26px !important;
  letter-spacing: 1.26px !important;     /* keep parity with ship #1429 */
  color: rgb(255, 255, 255) !important;
  margin: 0 0 14px !important;
  display: flex !important;
  align-items: center !important;
  gap: 10px !important;
  text-transform: none !important;
}

/* The help-section-title's leading icon — keep it ink-2, 18px. */
html body .full-page-mode .info-modal .help-section-title > .material-symbols-rounded,
html body .full-page-mode .info-modal .help-section-title > [class*="material"],
html body .full-page-mode .info-modal .help-section-title > svg {
  font-size: 18px !important;
  width: 18px !important;
  height: 18px !important;
  color: rgb(158, 167, 177) !important;
  flex: 0 0 18px !important;
}

/* Body text on the help page — csfloat ink stack: 16/400 default, ink-2 muted. */
html body .full-page-mode .info-modal p,
html body .full-page-mode .info-modal .help-step-body {
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  color: rgb(158, 167, 177) !important;
}

/* Hairline divider colour parity (rgba(255,255,255,0.06) per global token). */
html body .full-page-mode .info-modal .help-step {
  border-bottom-color: rgba(255, 255, 255, 0.06) !important;
  border-bottom-style: solid !important;
  border-bottom-width: 1px !important;
}
html body .full-page-mode .info-modal .help-steps .help-step:last-child {
  border-bottom: 0 !important;
}

/* Contact strip at the bottom — match panel chrome (no extra border, sit
   inside the same panel rhythm). csfloat treats the .actions slot as a
   borderless inline strip; keep the wrapper flush. */
html body .full-page-mode .info-modal .help-contact {
  background: transparent !important;
  border: 0 !important;
  border-top: 1px solid rgba(255, 255, 255, 0.06) !important;
  border-radius: 0 !important;
  padding: 20px 0 0 !important;
  margin: 20px 0 0 !important;
}

/* Brand accent (rgb(35,123,255)) on contact CTA — already correct via
   .btn-accent token; explicit pin so the help page can't drift. */
html body .full-page-mode .info-modal .help-contact .btn.btn-accent {
  background: rgb(35, 123, 255) !important;
  color: rgb(255, 255, 255) !important;
  border: 0 !important;
  border-radius: 8px !important;
  padding: 8px 14px !important;
  font-size: 12px !important;
  font-weight: 500 !important;
}

/* END CSFLOAT-1:1 PARITY ship #128480 */
"""


def main():
    if not os.path.isfile(CSS_PATH):
        raise SystemExit(f"design.css not found: {CSS_PATH}")
    # Atomic append: read, append in-memory, write to temp, fsync, replace.
    with open(CSS_PATH, "rb") as f:
        original = f.read()
    if b"CSFLOAT-1:1 PARITY ship #128480" in original:
        print("ship #128480 block already present; skipping (idempotent).")
        return
    payload = original + APPEND.encode("utf-8")
    dirpath = os.path.dirname(CSS_PATH)
    fd, tmppath = tempfile.mkstemp(prefix=".design.css.", suffix=".tmp", dir=dirpath)
    try:
        with os.fdopen(fd, "wb") as tf:
            tf.write(payload)
            tf.flush()
            os.fsync(tf.fileno())
        os.replace(tmppath, CSS_PATH)
    finally:
        if os.path.exists(tmppath):
            try:
                os.remove(tmppath)
            except OSError:
                pass
    appended_bytes = len(payload) - len(original)
    print(f"appended {appended_bytes} bytes to {CSS_PATH}")


if __name__ == "__main__":
    main()
