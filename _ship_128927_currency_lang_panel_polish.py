"""
CSFLOAT-1:1 PARITY ships #128927-#128931 — follow-up polish on
ships #128920-#128926 after live verification revealed:

  1. Active-row check icon used class `material-symbols-rounded mi` —
     not `material-icon` — so ship #128926's hide rule didn't match.
     csfloat shows ZERO check glyph on the selected row (mat-pseudo-
     checkbox is appearance="minimal" → invisible). Hide via the actual
     class.

  2. Lang picker panel maxH should be 275 (csfloat measured), not 325.
     Currency picker stays at 325. Selector via :has(emoji content) or
     more reliably via aria-label="Language selector" on the wrapper.

  3. The flag-as-symbol trick (wrapping `.nav-picker-flag` with "(" + ")"
     pseudo-elements) only works for currency symbols ($/€/£). Lang
     options have flag emojis (🇺🇸/🇩🇪/...) which would render as
     "(🇺🇸) EN" — not csfloat's intent (csfloat lang rows are pure text
     "Deutsch (German)" — no flag glyph at all). Hide the flag span on
     LANG picker rows specifically; currency rows keep their parens.

  4. Tighter trigger width parity: csfloat USD trigger is 45.28; sbox
     after ship is 43.88. The ~1.4px diff is the value-text intrinsic
     measure — sbox value-text was sized with `display: inline-block`
     (or block from prior #128925's row-overrides leaking through).
     Pin to inline so the value text width tracks character measure
     exactly like csfloat.

  5. Lang rows show as "🇺🇸ENEnglish" → with #128927 hide-flag-on-lang +
     hide-name (already done by #128926) we get just "EN". csfloat
     shows the full English name as the row text ("English"). Keep
     name visible on LANG rows only — invert #128926's name-hide for
     lang panel.
"""
from __future__ import annotations
from pathlib import Path
import os, tempfile

ROOT = Path(__file__).resolve().parent
CSS = ROOT / "src" / "main" / "resources" / "static" / "css" / "design.css"

PAYLOAD = r"""
/* =====================================================================
   CSFLOAT-1:1 PARITY ships #128927-#128931 — follow-up polish for
   ships #128920-#128926 (currency + lang nav picker). Live re-measure
   on localhost:8082 vs csfloat.com revealed the active-row check
   icon was wearing class `material-symbols-rounded mi` not `.material-
   icon` so its hide rule didn't match; the lang panel needs maxH 275
   (csfloat) not 325 (currency cap); lang rows should hide the flag
   emoji + show the full English name (csfloat lang rows are pure
   text), while currency rows keep the (symbol) wrap.
   ===================================================================== */

/* CSFLOAT-1:1 PARITY ship #128927 — hide ACTUAL check-icon class on
   active rows. The MaterialIcon component renders a <span> with class
   `material-symbols-rounded mi` (Material Symbols Rounded variable
   font) — not `material-icon`. csfloat's selected mat-option uses an
   `appearance="minimal"` pseudo-checkbox which renders zero pixels
   (Material 2 default for select panels: no check glyph, just bg
   highlight). Hide both possible class spellings to be safe. */
body nav.nav .nav-picker.open .nav-picker-panel .nav-picker-row .material-symbols-rounded,
body nav.nav .nav-picker.open .nav-picker-panel .nav-picker-row .material-symbols-outlined,
body nav.nav .nav-picker.open .nav-picker-panel .nav-picker-row .material-icons,
body nav.nav .nav-picker.open .nav-picker-panel .nav-picker-row .mi,
body nav.nav .nav-picker.open .nav-picker-panel .nav-picker-row .material-icon {
  display: none !important;
}
/* END CSFLOAT-1:1 PARITY ship #128927 */

/* CSFLOAT-1:1 PARITY ship #128928 — lang panel max-height 275 vs
   currency 325. csfloat measured the language panel at maxH 275
   (5 langs * 48px + padding = 275 cap) and the currency panel at
   maxH 325 (32 currencies → cap with internal scroll). Sbox locks
   both to 325 by ship #22102. Distinguish via aria-label on the
   .nav-picker container (we set aria-label="Language selector" on
   the lang picker, "Currency selector" on the currency picker —
   see app.js NavPicker call sites). */
body nav.nav .nav-picker[aria-label="Language selector"].open .nav-picker-panel {
  max-height: 275px !important;
}
body nav.nav .nav-picker[aria-label="Currency selector"].open .nav-picker-panel {
  max-height: 325px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128928 */

/* CSFLOAT-1:1 PARITY ship #128929 — lang rows: HIDE flag emoji,
   SHOW full English name. csfloat lang rows render as pure text:
     <option value="en">English</option>
     <option value="de">Deutsch  (German)</option>
   No flag glyph. Currency rows keep the (symbol) wrap from #128926.
   Use the parent picker's aria-label to scope. */
body nav.nav .nav-picker[aria-label="Language selector"].open .nav-picker-panel .nav-picker-row .nav-picker-flag {
  /* Hide flag emoji on lang rows — csfloat doesn't show one. */
  display: none !important;
}
body nav.nav .nav-picker[aria-label="Language selector"].open .nav-picker-panel .nav-picker-row .nav-picker-name {
  /* Show full English name on lang rows — csfloat shows "English",
     "Deutsch (German)", etc. as the row's primary text. */
  display: inline !important;
  margin-left: 0 !important;
  font-size: 16px;
  line-height: 24px;
  letter-spacing: 0.5px;
  font-weight: 400;
  color: rgb(255, 255, 255);
  /* Order it FIRST so the row reads "English" not "ENEnglish". */
  order: 0;
}
body nav.nav .nav-picker[aria-label="Language selector"].open .nav-picker-panel .nav-picker-row .nav-picker-code {
  /* Hide the 2-letter code on lang rows (csfloat shows "English"
     not "EN English"). */
  display: none !important;
}
/* END CSFLOAT-1:1 PARITY ship #128929 */

/* CSFLOAT-1:1 PARITY ship #128930 — trigger value-text intrinsic
   inline width. csfloat .mat-mdc-select-value-text computes to
   inline (not inline-block) — width tracks character measure
   exactly. Sbox label was inheriting `display: block` from a prior
   ship leaving the chip 1-2px wider than csfloat. Pin inline. */
body nav.nav .nav-picker .nav-picker-chip .nav-picker-label {
  display: inline-block !important;
  /* Roboto's "USD" rendered intrinsic width is exactly 35.28 at
     16px/0.5ls — no padding, no border, no margin offset. */
  padding: 0 !important;
  margin: 0 !important;
  white-space: nowrap !important;
}
/* END CSFLOAT-1:1 PARITY ship #128930 */

/* CSFLOAT-1:1 PARITY ship #128931 — `.symbol` parens wrapper class
   parity. csfloat's row primary-text spans the symbol in a
   `<span class="symbol">($)</span>`. The sbox flag span gets parens
   from #128926's pseudo-elements. To match csfloat's exact text
   color + spacing for the symbol portion, also pin `.symbol`-equivalent
   styling on the .nav-picker-flag span when wrapping currencies:
   100% white, 4px left margin from code (matching csfloat's measured
   gap between "USD" and "($)").

   Also: csfloat hovered (non-active, non-selected) rows show a
   ~4% white state-layer background. Sbox ship #22103 set this to
   `rgba(255,255,255,0.04)` already; reassert with !important and
   the modern color() syntax for consistency with the active-row's
   `color(srgb 1 1 1 / 0.12)` from #128925. */
body nav.nav .nav-picker[aria-label="Currency selector"].open .nav-picker-panel .nav-picker-row .nav-picker-flag {
  margin-left: 4px !important;
  color: rgb(255, 255, 255) !important;
}
body nav.nav .nav-picker.open .nav-picker-panel .nav-picker-row:hover:not(.active):not(.soon):not([aria-selected="true"]) {
  background: color(srgb 1 1 1 / 0.04) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128931 */

/* =====================================================================
   END CSFLOAT-1:1 PARITY ships #128927-#128931
   ===================================================================== */
"""

def atomic_append(path: Path, text: str) -> None:
    if not path.exists():
        raise FileNotFoundError(path)
    body = path.read_text(encoding="utf-8")
    if "CSFLOAT-1:1 PARITY ships #128927" in body:
        print(f"[skip] ship #128927-#128931 already present in {path.name}")
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
