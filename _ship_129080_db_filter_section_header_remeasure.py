"""
CSFLOAT-1:1 PARITY ships #129080-#129085 — /db (FloatDB) filter sidebar section
header (.row > .name + .icon) live RE-MEASUREMENT vs csfloat /db.

Earlier ship #128171 (line ~185724 in design.css) approximated the section
header as 27px-tall flex row with `font-size: 16px !important; font-weight:
400 !important; color: rgb(255,255,255) !important; text-transform: none;
letter-spacing: normal !important` and the chevron .icon as `width: 15px;
height: 15px; font-size: 15px; color: rgb(158,167,177) !important`. Re-
measured today on csfloat /db (anon-accessible route, no Steam auth needed)
via Playwright getComputedStyle on the live `.advanced-search app-search-row
.row.expanded` rows for Wear / Special / Patterns / Stickers / Charms / Rarity
/ Collection. Found that #128171 was wrong on multiple axes — and that the
sboxmarket /db page is currently rendering correctly only because Angular's
component-scoped attribute selectors (e.g. `.container[_ngcontent-...]
.row[_ngcontent-...] .name[_ngcontent-...]`, specificity 0,3,3) silently
out-cascade the #128171 `body .advanced-search app-search-row .row > .name`
chain (specificity 0,3,3 too — Angular's hash adds an extra attribute = 0,4,3).
That dependency on Angular's emitted attribute hashes is fragile: if the
component bundle re-builds and the hash changes, our wrong #128171 16px/400
rule wins again and ships a visibly heavier section header.

This batch APPENDS corrective rules with stronger specificity that lock in
the actual measured truth, so the visible state is invariant under any
Angular rebuild. Out-cascade target: line 185763-185771 (`.row > .name`),
line 185740-185762 (`.row` block), line 185772-185779 (`.row > .icon`).

Live csfloat /db measurements (May 2026, anon Playwright):

  ROW (.row, .row.expanded):
    display: flex                                    (matches #128171 ✓)
    flex-direction: row                              (matches #128171 ✓)
    align-items: center                              (matches #128171 ✓)
    justify-content: space-between                   (matches #128171 ✓)
    padding: 6px 0                                   (matches #128171 ✓)
    height: 15px (computed inner; row outer = 27px)  (matches #128171 ✓)
    cursor: pointer                                  (matches #128171 ✓)
    background: transparent                          (matches #128171 ✓)
    width: 310px (sidebar inner = 350 - 2*20)        (matches #128171 ✓)
    Row-as-a-block geometry already correct.

  NAME (.row > .name):
    font-size: 12px              ← #128171 said 16px (4px too tall)
    font-weight: 700             ← #128171 said 400 (NOT bold per #128171,
                                    but csfloat is actually bold)
    color: rgb(255,255,255)      (matches #128171 ✓)
    letter-spacing: 0.6px        ← #128171 said `normal` (no letter-spacing)
    line-height: normal          (matches #128171 ✓)
    text-transform: none         (matches #128171 ✓)
    margin: 0                    (matches #128171 ✓)
    padding: 0                   (matches #128171 ✓)
    font-family: Roboto, "Helvetica Neue", sans-serif

  ICON (.row > .icon):
    Container is a 15x15 DIV holding a 14x15 viewBox SVG with path
    `M4 6L7 9L10 6` stroke=currentColor stroke-width=2 stroke-linecap=round
    stroke-linejoin=round. Stroke=currentColor means the chevron color is
    inherited from .icon's `color`.
    width: 15px                  (matches #128171 ✓)
    height: 15px                 (matches #128171 ✓)
    color: rgb(255,255,255)      ← #128171 said rgb(158,167,177) (ink-2)
                                    csfloat actually uses pure white #fff
                                    same as the .name label, NOT ink-2.
    display: block               (matches #128171 — implicit ✓)
    transform (collapsed): matrix(1, 0, 0, 1, 0, 0)   = identity (chevron-down)
    transform (expanded):  matrix(-1, 0, 0, -1, 0, 0) = 180° rotation
                                                        (becomes chevron-up)
    transition: 0.3s                                  (NOT shipped by #128171)

  CONTENT SIBLING (.content / .content.expanded after the .row):
    margin: 6px 0 0              ← #128171 didn't measure this
    padding: 0                   ← #128171 didn't measure this
    gap row.bottom -> content.top = 6px (the .content's 6px top margin)

  HAIRLINE GAP (.advanced-search > .gap, divides sections):
    height: 1px
    width: 310px (full inner sidebar)
    background: rgba(193, 206, 255, 0.04) — csfloat's blue-tinted hairline,
    NOT pure white-on-black. (Approx equivalent visual luminance to
    rgba(255,255,255,0.06) at the panel-token surface.)

Each corrective ship uses the selector chain
`html body .advanced-search app-search-row div.row > div.name` (specificity
1,3,5) which beats Angular's component-scoped 0,4,3 even without !important,
so the corrections are stable across rebuilds. We still flag !important
defensively.

Ship #129080 — .row geometry RE-AFFIRM (no behavioural delta vs #128171,
                                         but locks against any later
                                         override that drifts the row off
                                         flex / pad / height).
Ship #129081 — .name FONT-SIZE 16px -> 12px correction (#128171 was wrong)
Ship #129082 — .name FONT-WEIGHT 400 -> 700 correction (#128171 was wrong)
Ship #129083 — .name LETTER-SPACING normal -> 0.6px correction (#128171 missed)
Ship #129084 — .icon COLOR ink-2 -> #fff correction (chevron should match
                label, not be ink-2; the SVG uses currentColor so .icon's
                color drives the stroke)
Ship #129085 — .icon TRANSFORM + TRANSITION (chevron flips 180° when the
                section is expanded, with a 300ms transition; #128171
                didn't ship the rotation/transition at all so the chevron
                stays static — visible in expand/collapse animation).
                Also pin the .content sibling's 6px top margin (the
                vertical breath between header and first checkbox).
"""
from __future__ import annotations
from pathlib import Path
import os, tempfile

ROOT = Path(__file__).resolve().parent
CSS = ROOT / "src" / "main" / "resources" / "static" / "css" / "design.css"

PAYLOAD = r"""
/* =====================================================================
   CSFLOAT-1:1 PARITY ships #129080-#129085 — /db filter sidebar section
   header (.row > .name + .icon) live RE-MEASUREMENT vs csfloat /db.
   Out-cascades the prior ship #128171 rules at L185740-L185779 which
   approximated the .name as 16px/400 and the .icon as 15px ink-2. csfloat
   /db actually paints the .name as 12px/700/0.6 letter-spacing pure-white
   and the .icon chevron as 15x15 currentColor=#fff with a matrix(-1,...)
   180° transform when the section is expanded (300ms transition). The
   sboxmarket /db page is currently rendering correctly *only* because
   Angular's component-scoped `[_ngcontent-...]` attribute selectors win
   the cascade against #128171's `body .advanced-search app-search-row
   .row > .name` chain — but that's fragile: if the Angular bundle
   rebuild changes the hash, #128171's wrong 16px/400 will visibly ship.
   Lock the truth here with `html body .advanced-search app-search-row
   div.row > div.name` (specificity 1,3,5) so the rendered output is
   invariant under any future Angular rebuild.
   ===================================================================== */

/* CSFLOAT-1:1 PARITY ship #129080 — .row geometry RE-AFFIRM. csfloat
   .advanced-search app-search-row .row.expanded measured 310x27 outer
   (15px inner + 6px+6px padding-y), display:flex row, justify-content
   space-between, align-items center, cursor pointer, transparent bg,
   no border. Already pinned by #128171 at L185740-L185762 — re-pin here
   with stronger specificity so a future style addition can't drift the
   row off-rhythm. Measured row x=525 width=310 on the live csfloat /db
   sidebar (350px panel - 20px*2 padding = 310px inner column). */
html body .advanced-search app-search-row div.row,
html body .advanced-search app-search-row div.row.expanded,
html body .filter.sticky .advanced-search app-search-row div.row,
html body .filter.sticky .advanced-search app-search-row div.row.expanded {
  display: flex !important;
  flex-direction: row !important;
  align-items: center !important;
  justify-content: space-between !important;
  padding: 6px 0 !important;
  margin: 0 !important;
  height: 15px !important;
  min-height: 15px !important;
  box-sizing: content-box !important;
  cursor: pointer !important;
  background: transparent !important;
  border: 0 !important;
  border-radius: 0 !important;
  width: 100% !important;
}
/* END CSFLOAT-1:1 PARITY ship #129080 */

/* CSFLOAT-1:1 PARITY ship #129081 — .name FONT-SIZE 12px (NOT 16px).
   csfloat .advanced-search .row > .name measured `font-size: 12px` in
   getComputedStyle on every section header (Wear / Special / Patterns /
   Stickers / Charms / Rarity / Collection). #128171 said 16px which is
   the dropdown mat-label font, NOT the section header. The 4px surplus
   in #128171 makes the header read as h4-weight rather than the calmer
   12px label-cap-style csfloat actually ships. Lock to 12 exact. */
html body .advanced-search app-search-row div.row > div.name,
html body .advanced-search app-search-row div.row.expanded > div.name,
html body .filter.sticky .advanced-search app-search-row div.row > div.name {
  font-size: 12px !important;
}
/* END CSFLOAT-1:1 PARITY ship #129081 */

/* CSFLOAT-1:1 PARITY ship #129082 — .name FONT-WEIGHT 700 (NOT 400).
   csfloat .advanced-search .row > .name measured `font-weight: 700` =
   bold. #128171 said 400 (regular) — explicitly noted as "NOT 600 / NOT
   bold" in the source comment, but the live csfloat measurement
   contradicts that. The bold weight + 12px small size is what gives
   csfloat's section labels their characteristic "all-cap-feel without
   actually being uppercase" cadence. Lock to 700 exact. */
html body .advanced-search app-search-row div.row > div.name,
html body .advanced-search app-search-row div.row.expanded > div.name,
html body .filter.sticky .advanced-search app-search-row div.row > div.name {
  font-weight: 700 !important;
  color: rgb(255, 255, 255) !important;
  text-transform: none !important;
  line-height: normal !important;
  margin: 0 !important;
  padding: 0 !important;
  font-family: Roboto, "Helvetica Neue", sans-serif !important;
}
/* END CSFLOAT-1:1 PARITY ship #129082 */

/* CSFLOAT-1:1 PARITY ship #129083 — .name LETTER-SPACING 0.6px (NOT
   normal). csfloat .advanced-search .row > .name measured
   `letter-spacing: 0.6px` — a small positive tracking that opens the
   12px bold characters so they don't crowd. #128171 said `normal` (=
   0px tracking). Without the 0.6px tracking the bold 12px text reads
   denser/heavier than csfloat's. The 0.6px is approx 5% of 12 — a
   conventional micro-tracking adjustment for small bold UI labels. */
html body .advanced-search app-search-row div.row > div.name,
html body .advanced-search app-search-row div.row.expanded > div.name,
html body .filter.sticky .advanced-search app-search-row div.row > div.name {
  letter-spacing: 0.6px !important;
}
/* END CSFLOAT-1:1 PARITY ship #129083 */

/* CSFLOAT-1:1 PARITY ship #129084 — .icon COLOR pure white (NOT ink-2).
   csfloat .advanced-search .row > .icon measured `color: rgb(255,255,255)`.
   The chevron SVG inside the .icon DIV is `<path stroke="currentColor"
   ...>` so the .icon's color directly drives the visible chevron stroke.
   #128171 set color to rgb(158,167,177) (the csfloat ink-2 muted
   text token) — that paints the chevron as a soft grey rather than
   the same pure-white as the .name label. csfloat actually keeps the
   chevron at the same brightness as the label so the header reads as
   one unified white block. Lock chevron to #fff and re-pin the 15x15
   container size + display:block (the SVG inside is 14x15 by viewBox
   but the container is a square 15x15 wrapper). */
html body .advanced-search app-search-row div.row > div.icon,
html body .advanced-search app-search-row div.row.expanded > div.icon,
html body .filter.sticky .advanced-search app-search-row div.row > div.icon {
  width: 15px !important;
  height: 15px !important;
  color: rgb(255, 255, 255) !important;
  display: block !important;
  padding: 0 !important;
  margin: 0 !important;
  background: none !important;
  background-color: transparent !important;
  border: 0 !important;
}
/* The SVG inside follows the parent currentColor automatically because
   the path uses stroke=currentColor, but pin its dimensions defensively
   so a stray Angular host-context style can't blow it up to 24x24. */
html body .advanced-search app-search-row div.row > div.icon > svg,
html body .advanced-search app-search-row div.row.expanded > div.icon > svg {
  width: 14px !important;
  height: 15px !important;
  display: block !important;
  fill: none !important;
}
html body .advanced-search app-search-row div.row > div.icon > svg path,
html body .advanced-search app-search-row div.row.expanded > div.icon > svg path {
  stroke: currentColor !important;
  stroke-width: 2 !important;
  stroke-linecap: round !important;
  stroke-linejoin: round !important;
  fill: none !important;
}
/* END CSFLOAT-1:1 PARITY ship #129084 */

/* CSFLOAT-1:1 PARITY ship #129085 — .icon TRANSFORM + TRANSITION
   chevron flip when section is expanded, plus .content sibling's 6px
   top margin. csfloat measured the .icon's `transform` as
   matrix(1,0,0,1,0,0) (= identity, chevron-down) when the section is
   collapsed and `matrix(-1,0,0,-1,0,0)` (= 180° rotation, chevron-up)
   when the section is expanded — and the `transition` property is set
   to `0.3s` so the flip animates over 300ms when the user toggles the
   section. The expanded state is signalled by the `.expanded` class on
   the parent .row. #128171 didn't ship the transform or transition at
   all, so the chevron stays static — visibly broken: clicking a header
   should rotate the chevron, but currently nothing happens. Lock the
   collapsed identity, the expanded 180°, and the 300ms transition.
   Also pin the .content / .content.expanded sibling's `margin: 6px 0 0;
   padding: 0` (the breath between the header row and the first
   checkbox of the section body — measured exactly 6px on csfloat). */
html body .advanced-search app-search-row div.row > div.icon,
html body .filter.sticky .advanced-search app-search-row div.row > div.icon {
  transform: matrix(1, 0, 0, 1, 0, 0) !important;
  transition: transform 0.3s !important;
}
html body .advanced-search app-search-row div.row.expanded > div.icon,
html body .filter.sticky .advanced-search app-search-row div.row.expanded > div.icon {
  transform: matrix(-1, 0, 0, -1, 0, 0) !important;
}
html body .advanced-search app-search-row div.content,
html body .advanced-search app-search-row div.content.expanded,
html body .filter.sticky .advanced-search app-search-row div.content,
html body .filter.sticky .advanced-search app-search-row div.content.expanded {
  margin: 6px 0 0 !important;
  padding: 0 !important;
}
/* The hairline divider <div class="gap"> between sections — csfloat
   measured 310x1, background rgba(193,206,255,0.04) (a barely-blue
   hairline, NOT pure white-on-black). Re-pin so a later override
   doesn't bump it back to a heavier 1px white stripe. */
html body .advanced-search > div.gap,
html body .filter.sticky .advanced-search > div.gap {
  width: 100% !important;
  height: 1px !important;
  margin: 0 !important;
  padding: 0 !important;
  background: rgba(193, 206, 255, 0.04) !important;
  border: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #129085 */

/* =====================================================================
   END CSFLOAT-1:1 PARITY ships #129080-#129085
   ===================================================================== */
"""

def atomic_append(path: Path, text: str) -> None:
    """Append text to file via temp-file write + rename so a crash mid-write
    can't half-corrupt the source. tempfile in same dir -> same volume -> rename
    is atomic on Windows + POSIX."""
    if not path.exists():
        raise FileNotFoundError(path)
    body = path.read_text(encoding="utf-8")
    if "CSFLOAT-1:1 PARITY ships #129080" in body:
        print(f"[skip] ship #129080-#129085 already present in {path.name}")
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
