"""
CSFLOAT-1:1 PARITY ships #128932-#128937 — header currency + language
trigger micro-state corrections after live re-measure on csfloat:

  - Inter-trigger gap: csfloat measures 25px between USD trigger right
    edge and EN trigger left edge. Sbox uses an 8px adjacent-margin via
    `.nav-right > .nav-picker + .nav-icon-btn`. Bump to 25px so the
    triggers sit with the same generous Material breathing room.

  - Arrow color baseline: csfloat .mat-mdc-select-arrow path computes
    color rgba(255,255,255,0.7) at REST and only goes to full white on
    hover/focus. Ship #128922 set the mask bg to full white at all
    times — actually too bright vs csfloat's 70% rest color. Lock to
    0.7 alpha at rest; full alpha on hover/open.

  - Trigger cursor: csfloat .mat-mdc-select-trigger has cursor:pointer.
    Sbox button defaults to pointer too but lock explicitly for parity.

  - Trigger transition: csfloat sets `transition: all` (Material 2
    default global transition) which gives a soft feel on focus state.
    Sbox already has bounds via prior ships; lock the trigger's
    transition declaration to `all 80ms linear` to match Material's
    default form-field-animations-enabled timing.

  - Lang panel WIDTH parity: csfloat lang panel is 173.88, currency
    is 139.89 (33.99px difference because lang labels are longer
    "Deutsch (German)" etc.). Sbox panel is fluid via #128923's
    `width: auto` but the max-width 220 cap might clamp tightly on
    long lang names. Bump max to 240 so "Português (Portuguese)"
    fits without ellipsis.

  - Selected option pseudo-checkbox visibility: csfloat selected
    rows have a `<mat-pseudo-checkbox state="checked" appearance=
    "minimal">` element which is invisible by default (Material 2
    minimal appearance suppresses the visual checkbox). Sbox might
    inadvertently render a check via the pseudo-checkbox-checked
    state styles inherited from Material. Lock display:none on any
    pseudo-checkbox spelling so we never accidentally show one.
"""
from __future__ import annotations
from pathlib import Path
import os, tempfile

ROOT = Path(__file__).resolve().parent
CSS = ROOT / "src" / "main" / "resources" / "static" / "css" / "design.css"

PAYLOAD = r"""
/* =====================================================================
   CSFLOAT-1:1 PARITY ships #128932-#128937 — header trigger micro-states
   captured live on csfloat: inter-trigger gap 25px (vs sbox 8px),
   arrow rest color 70% white (not 100%), pseudo-checkbox invisibility
   lock, cursor + transition declarations.
   ===================================================================== */

/* CSFLOAT-1:1 PARITY ship #128932 — inter-trigger spacing 25px.
   csfloat measured 25.00px between USD trigger right (1072.16) and
   EN trigger left (1097.16). Sbox sets `.nav-right > .nav-picker +
   .nav-icon-btn { margin-left: 8px }` — but the picker-to-picker
   spacing wasn't covered. Use selector chain so adjacent navpickers
   in the .nav-right region get the csfloat-measured 25px gap. */
body nav.nav .nav-right .nav-picker + .nav-picker {
  margin-left: 25px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128932 */

/* CSFLOAT-1:1 PARITY ship #128933 — arrow REST color 70% white.
   csfloat .mat-mdc-select-arrow path computes
   `color: rgba(255,255,255,0.7)` at idle (no hover, no focus) and
   transitions to `rgb(255,255,255)` on hover/open. Ship #128922
   set the mask background to full white statically. Soften to 0.7
   at rest so the chip reads with the same Material 2 secondary-
   text alpha as csfloat. */
body nav.nav .nav-picker .nav-picker-chip .nav-picker-caret {
  background-color: rgba(255, 255, 255, 0.7) !important;
}
/* On hover or open, bump to full white to mirror csfloat's hover
   bump. */
body nav.nav .nav-picker .nav-picker-chip:hover .nav-picker-caret,
body nav.nav .nav-picker.open .nav-picker-chip .nav-picker-caret {
  background-color: rgb(255, 255, 255) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128933 */

/* CSFLOAT-1:1 PARITY ship #128934 — trigger cursor + transition lock.
   csfloat trigger has `cursor: pointer` + Material's global
   `transition: all` (resolved to ~80ms linear via Material's
   animations module). Sbox button is interactive so cursor:pointer
   is the default but assert explicitly so future changes can't
   drift. Transition matches the caret rotate timing of #22104. */
body nav.nav .nav-picker .nav-picker-chip {
  cursor: pointer !important;
  transition: color 80ms linear, opacity 80ms linear !important;
}
/* END CSFLOAT-1:1 PARITY ship #128934 */

/* CSFLOAT-1:1 PARITY ship #128935 — lang panel max-width bump for
   long labels. csfloat lang options include "Português (Portuguese)"
   which renders ~190px wide at 16/0.5px Roboto. Sbox max-width was
   220 in #128923 — cuts the row to 220 even though csfloat allows
   the panel to grow further. Bump to 240 so any future-added lang
   row fits without horizontal clipping. */
body nav.nav .nav-picker[aria-label="Language selector"].open .nav-picker-panel {
  max-width: 240px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128935 */

/* CSFLOAT-1:1 PARITY ship #128936 — pseudo-checkbox invisibility
   lock on selected rows. csfloat's selected option includes
   `<mat-pseudo-checkbox state="checked" appearance="minimal">` —
   the `appearance="minimal"` token is what suppresses the visual
   check icon. Sbox doesn't render a mat-pseudo-checkbox but if a
   future template adds one (or we pull in a Material partial), this
   selector ensures the check stays hidden so the row reads as
   csfloat does — selection conveyed only via bg color. */
body nav.nav .nav-picker.open .nav-picker-panel .nav-picker-row .mat-pseudo-checkbox,
body nav.nav .nav-picker.open .nav-picker-panel .nav-picker-row .mat-pseudo-checkbox-checked,
body nav.nav .nav-picker.open .nav-picker-panel .nav-picker-row .mat-pseudo-checkbox-minimal {
  display: none !important;
}
/* END CSFLOAT-1:1 PARITY ship #128936 */

/* CSFLOAT-1:1 PARITY ship #128937 — trigger user-select prevention.
   csfloat .mat-mdc-select-trigger has `user-select: none` so
   clicking on the trigger doesn't accidentally start a text
   selection (which the operator would see as a 1-frame highlight
   flicker). Sbox button has user-select:auto by default — lock
   to none for the same crisp click feel. */
body nav.nav .nav-picker .nav-picker-chip,
body nav.nav .nav-picker .nav-picker-chip .nav-picker-label,
body nav.nav .nav-picker .nav-picker-chip .nav-picker-caret {
  -webkit-user-select: none !important;
  user-select: none !important;
}
/* END CSFLOAT-1:1 PARITY ship #128937 */

/* =====================================================================
   END CSFLOAT-1:1 PARITY ships #128932-#128937
   ===================================================================== */
"""

def atomic_append(path: Path, text: str) -> None:
    if not path.exists():
        raise FileNotFoundError(path)
    body = path.read_text(encoding="utf-8")
    if "CSFLOAT-1:1 PARITY ships #128932" in body:
        print(f"[skip] ship #128932-#128937 already present in {path.name}")
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
