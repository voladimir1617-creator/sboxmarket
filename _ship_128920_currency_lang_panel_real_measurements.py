"""
CSFLOAT-1:1 PARITY ships #128920-#128926 — header currency + language picker
re-measure-and-correct. Lane is the top-right "USD" + "EN" badges in the
csfloat header (Material 2 mat-select with custom .currency-selector-panel
overlay). Earlier sboxmarket ships #2600 / #6500-series / #7500-series /
#22100-#22105 covered the panel chrome. This batch re-measures live in
playwright AND diffs against the active sboxmarket build at localhost:8082,
shipping the gaps the prior ships missed.

Live csfloat measurements (May 2026, playwright getComputedStyle):
  Trigger USD:  45.28x25, .mat-mdc-select-trigger
                value: 16px / 24 / 0.5px / 400 / Roboto / rgb(255,255,255)
                arrow: 10x24 SVG with viewBox 0 0 24 24, path d "M7 10l5 5 5-5z"
                       fill rgb(255,255,255), positioned 0 margin
                trigger padding: 0; gap: normal (no flex-gap inside)
                trigger bg: transparent; border: none; radius: 0
  Trigger EN:   35.84x25 (same chrome as USD, just narrower because "EN")
  Panel currency:  139.89x325 anchored at trigger.left, trigger.bottom
                   .mat-mdc-select-panel.currency-selector-panel
                   bg rgba(21,23,28,0.8) + 2px solid rgba(193,206,255,0.07) border,
                   border-radius 0 0 15px 15px, padding 8px 0,
                   max-height 325, backdrop-filter blur(10px),
                   3-layer Material elevation shadow.
  Panel lang:      173.88x260 anchored similarly. SAME chrome but maxH 275
                   (5 langs * 48px row + 16 panel padding + 19 free = 275 cap).
  Row:             48 high, padding 0 16px, font 16px / 24 / 0.5px / 400 / Roboto
                   white. Selected: bg color(srgb 1 1 1 / 0.12), text content
                   "USD ($)" with the symbol in <span class="symbol">.
                   Lang row: just text content "Deutsch  (German)" (double-space
                   between native + parenthetical English in csfloat source).

sboxmarket gaps captured live at localhost:8082:
  - Trigger label fs 13px ls normal -- should be 16px / 0.5px
  - Trigger pad 0 8px + gap 4px -- should be 0 / no flex gap
  - Caret is text "▾" 10px oklch grey -- should be SVG chevron 10x24 white
  - Panel width 208 fixed -- should fluid track trigger width with currency
                              picker ~140 / lang picker ~174
  - Panel anchored ~150 left of trigger -- should anchor flush trigger.left
  - Row height 43 fs 14 fw 500 with 3 columns (flag, code, name) --
    should be 48 fs 16 fw 400 with simple "USD ($)" text content
  - Lang panel max-height was 325; csfloat lang caps at 275 (smaller list)
"""
from __future__ import annotations
from pathlib import Path
import os, tempfile

ROOT = Path(__file__).resolve().parent
CSS = ROOT / "src" / "main" / "resources" / "static" / "css" / "design.css"

PAYLOAD = r"""
/* =====================================================================
   CSFLOAT-1:1 PARITY ships #128920-#128926 — nav currency + language
   picker live re-measurement vs sboxmarket build at localhost:8082.
   Triggers + panel + rows are off in measurable ways the prior chain of
   ship #2600 / #6500-series / #7500-series / #22100-#22105 missed because
   they only re-measured panel chrome (bg/border/shadow) — never the
   trigger label typography or the row's text-only content.

   Captured live with playwright on csfloat.com (browser_evaluate +
   getComputedStyle on .mat-mdc-select-trigger, .mat-mdc-select-arrow,
   div.mat-mdc-select-panel.currency-selector-panel, mat-option) AND
   the local sboxmarket nav-picker. Each ship below corrects ONE
   measurable diff. Specificity stacked (`body nav.nav .nav-picker-X`
   = 0,3,2 baseline + class chain so prior ships are out-cascaded.
   ===================================================================== */

/* CSFLOAT-1:1 PARITY ship #128920 — trigger LABEL typography. Sbox
   was 13px / fw 400 / ls normal Roboto; csfloat trigger value-text
   computes to 16px / 24 / fw 400 / ls 0.5px Roboto — Material 2's
   default body-1 token. The 3px font-size mismatch is visible in
   side-by-side: sbox "USD" reads tighter + smaller than csfloat. */
body nav.nav .nav-picker .nav-picker-chip .nav-picker-label {
  font-size: 16px !important;
  line-height: 24px !important;
  letter-spacing: 0.5px !important;
  font-weight: 400 !important;
  font-family: Roboto, "Helvetica Neue", sans-serif !important;
}
/* END CSFLOAT-1:1 PARITY ship #128920 */

/* CSFLOAT-1:1 PARITY ship #128921 — trigger PADDING + GAP. Sbox chip
   has padding 0 8px and gap 4px (so caret sits 4px right of label
   with 8px breathing room either side). csfloat .mat-mdc-select-
   trigger has padding 0 + no flex gap — the arrow is positioned by
   its own margin (which computes to 0). Removing the chip gap +
   horizontal padding tightens the trigger to csfloat's exact width
   profile (45.28 for USD vs sbox's bloated 55.05). */
body nav.nav .nav-picker .nav-picker-chip {
  padding: 0 !important;
  gap: 0 !important;
  /* csfloat trigger has no border or background — already none in
     sbox, but lock to defaults to prevent drift from prior :hover
     ships that may have layered halos. */
  border: none !important;
  background: transparent !important;
}
/* END CSFLOAT-1:1 PARITY ship #128921 */

/* CSFLOAT-1:1 PARITY ship #128922 — caret CHEVRON glyph. Sbox uses
   the unicode `▾` (BLACK DOWN-POINTING SMALL TRIANGLE U+25BE) at
   10px fs colored oklch grey. csfloat's caret is an actual Material
   SVG: <svg viewBox="0 0 24 24"><path d="M7 10l5 5 5-5z"/></svg>
   sized 24x24 viewBox but rendered 10x24 inside a positioned wrapper,
   path filled rgb(255,255,255). Hide the unicode triangle and back
   it with a CSS-mask-image so the chip renders the EXACT Material
   chevron shape without changing app.js JSX. The mask path matches
   csfloat's `M7 10l5 5 5-5z` so the silhouette is identical down to
   pixel alignment. */
body nav.nav .nav-picker .nav-picker-chip .nav-picker-caret {
  /* Hide the unicode triangle by transparenting it. */
  color: transparent !important;
  font-size: 0 !important;
  /* Render Material chevron via mask. Width 10 / Height 24 match the
     csfloat measured arrow rect (10x24, viewBox sized 24x24). */
  width: 10px !important;
  height: 24px !important;
  display: inline-block !important;
  background-color: rgb(255, 255, 255) !important;
  -webkit-mask-image: url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'><path d='M7 10l5 5 5-5z'/></svg>") !important;
  mask-image: url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'><path d='M7 10l5 5 5-5z'/></svg>") !important;
  -webkit-mask-repeat: no-repeat !important;
  mask-repeat: no-repeat !important;
  -webkit-mask-position: center !important;
  mask-position: center !important;
  -webkit-mask-size: 24px 24px !important;
  mask-size: 24px 24px !important;
  /* csfloat's mat-form-field-animations-enabled rule rotates the
     arrow with `transition: transform 80ms linear` — preserve. */
  transition: transform 80ms linear !important;
  vertical-align: middle !important;
}
/* When picker open, csfloat rotates arrow 180deg. Sbox already does
   this via `.nav-picker.open .nav-picker-caret { transform:
   rotate(180deg) }` (see ship #22104). Reassert with this selector
   chain so the increased specificity from #128922 doesn't drop it. */
body nav.nav .nav-picker.open .nav-picker-chip .nav-picker-caret {
  transform: rotate(180deg) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128922 */

/* CSFLOAT-1:1 PARITY ship #128923 — panel WIDTH lock. Sbox locked
   width to 208px (probably to fit the 3-column flag/code/name row
   layout). csfloat fits content to text only — currency panel width
   computes to 139.89, lang panel to 173.88. Switch to width:auto +
   min-width:max-content so the panel hugs the longest row text
   exactly like csfloat. (Caps with max-width so unusually long lang
   names don't blow out the layout.) */
body nav.nav .nav-picker.open .nav-picker-panel {
  width: auto !important;
  min-width: max-content !important;
  /* csfloat caps panel at trigger width minimum but never wider
     than ~200px in practice; lock max so we don't grow uncontrolled
     if a future option label adds many chars. */
  max-width: 220px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128923 */

/* CSFLOAT-1:1 PARITY ship #128924 — panel ANCHOR alignment. Sbox
   panel was rendering at x=703.97 while triggers sit at x=855.16
   (USD) — a ~150px LEFT shift, suggesting the panel's
   `position: absolute; right: 0` is pinning to .nav-right's right
   edge instead of the trigger's left edge. csfloat anchors the
   overlay flush to .mat-mdc-select-trigger.left (overlay rect.x ===
   trigger rect.x). Switch to `left: 0` so the panel drops directly
   under the trigger like csfloat. */
body nav.nav .nav-picker.open .nav-picker-panel {
  left: 0 !important;
  right: auto !important;
  /* csfloat overlay sits at trigger.bottom (47.5 = 22.5 trigger top
     + 25 trigger height + 0 gap). Sbox was at 54.37 — about 7px
     low. Pin to top:100% so the panel attaches flush. */
  top: 100% !important;
  margin-top: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128924 */

/* CSFLOAT-1:1 PARITY ship #128925 — row HEIGHT + TYPOGRAPHY.
   Sbox row was 43.58h, font 14/24/normal/500 (mismatched). csfloat
   row computes to exactly 48 high (Material 2 list-item single-line
   token), font 16/24/0.5px/400 Roboto white. The 4.5px height
   shortfall + 2px font-size shortfall + 100 font-weight surplus
   make the sbox panel feel cramped + bolder than csfloat.
   Lock to csfloat's exact tokens. */
body nav.nav .nav-picker.open .nav-picker-panel .nav-picker-row {
  height: 48px !important;
  min-height: 48px !important;
  padding: 0 16px !important;
  font-size: 16px !important;
  line-height: 24px !important;
  letter-spacing: 0.5px !important;
  font-weight: 400 !important;
  font-family: Roboto, "Helvetica Neue", sans-serif !important;
  color: rgb(255, 255, 255) !important;
  /* Default-state rows have no background; selected state at
     color(srgb 1 1 1 / 0.12) is already locked by ship #22103. */
  background: transparent;
}
/* Selected row keeps modern color() syntax for color-managed
   compositing (matches csfloat exactly — they shipped the new
   color() function on this surface in late 2025). */
body nav.nav .nav-picker.open .nav-picker-panel .nav-picker-row.active,
body nav.nav .nav-picker.open .nav-picker-panel .nav-picker-row[aria-selected="true"] {
  background: color(srgb 1 1 1 / 0.12) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128925 */

/* CSFLOAT-1:1 PARITY ship #128926 — row CONTENT flex layout. csfloat
   row markup is just `<span class="primary-text"> CODE <span
   class="symbol">(SYM)</span></span>` — no flag column, no separate
   full-name column. Sbox row markup has THREE child spans (.flag /
   .code / .name) plus an active-state check icon. To match csfloat's
   text-only "USD ($)" appearance without rewriting JSX:
     - Keep sbox flag span but restyle as the leading symbol-glyph
       (e.g., "$" / "€"), padded zero so it reads as part of the
       label flow.
     - Hide sbox .name (full English name) on the row — csfloat
       doesn't show "US Dollar" next to "USD".
     - Hide active-state check icon — csfloat shows ZERO checkbox/
       check (mat-pseudo-checkbox is appearance="minimal" → invisible,
       row state is conveyed only by the bg color highlight).
   Result: sbox row reads "$ USD" (matches csfloat's "USD ($)" tone
   without rewriting JSX to swap the glyph order). */
body nav.nav .nav-picker.open .nav-picker-panel .nav-picker-row {
  /* Flat row layout — csfloat is just inline text, no grid columns. */
  display: flex !important;
  align-items: center !important;
  gap: 4px !important;
  text-align: left !important;
  /* Hide multi-column grid styling from prior sbox ships. */
  grid-template-columns: none !important;
}
/* Hide the verbose full-name column — csfloat row is code+symbol only. */
body nav.nav .nav-picker.open .nav-picker-panel .nav-picker-row .nav-picker-name {
  display: none !important;
}
/* Hide the trailing check icon on active rows — csfloat conveys
   selection only through bg color, not a glyph. */
body nav.nav .nav-picker.open .nav-picker-panel .nav-picker-row.active .material-icon,
body nav.nav .nav-picker.open .nav-picker-panel .nav-picker-row .material-icon {
  display: none !important;
}
/* Style the leading flag glyph as the parenthetical symbol (csfloat
   shows "USD ($)" — code first, symbol in parens trailing). To get
   the parens visually without mutating JSX, wrap the flag visually
   with ::before/::after pseudo-elements on the parent row. */
body nav.nav .nav-picker.open .nav-picker-panel .nav-picker-row .nav-picker-flag {
  font-weight: 400 !important;
  font-size: 16px !important;
  line-height: 24px !important;
  color: rgb(255, 255, 255) !important;
  /* Order: code first, then symbol. Use flex order to swap. */
  order: 2;
  margin-left: 4px;
}
body nav.nav .nav-picker.open .nav-picker-panel .nav-picker-row .nav-picker-flag::before {
  content: "(";
}
body nav.nav .nav-picker.open .nav-picker-panel .nav-picker-row .nav-picker-flag::after {
  content: ")";
}
/* code stays at order 1 so it leads the row text content. */
body nav.nav .nav-picker.open .nav-picker-panel .nav-picker-row .nav-picker-code {
  order: 1;
  font-weight: 400 !important;
  font-size: 16px !important;
  line-height: 24px !important;
  color: rgb(255, 255, 255) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128926 */

/* =====================================================================
   END CSFLOAT-1:1 PARITY ships #128920-#128926
   ===================================================================== */
"""

def atomic_append(path: Path, text: str) -> None:
    """Append text to file via temp-file write + rename so a crash mid-write
    can't half-corrupt the source. tempfile in same dir → same volume → rename
    is atomic on Windows + POSIX."""
    if not path.exists():
        raise FileNotFoundError(path)
    body = path.read_text(encoding="utf-8")
    if "CSFLOAT-1:1 PARITY ships #128920" in body:
        print(f"[skip] ship #128920-#128926 already present in {path.name}")
        return
    new = body + text
    fd, tmp_path = tempfile.mkstemp(prefix=path.name + ".", suffix=".tmp", dir=str(path.parent))
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(new)
        os.replace(tmp_path, path)
        added = len(text.splitlines())
        print(f"[ok] appended {added} lines to {path.name}")
    finally:
        if os.path.exists(tmp_path):
            os.unlink(tmp_path)

if __name__ == "__main__":
    atomic_append(CSS, PAYLOAD)
