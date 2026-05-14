"""Ship #127674 — MyStall .stall-row REAL measurements from /stall/me

Measured live on csfloat.com signed-in /stall/me item-card .footer:
  .price        18px / weight 500 / rgb(255,255,255)
  .reference (% off ref)  12px / weight 700 / rgb(158,167,177) [ink-2 token]
  .seller-details Online label  12px / weight 400 / rgb(255,255,255)
  edit-pencil button  32x32, br 50%, color color(srgb 1 1 1 / 0.38)
  .footer  padding 12px, gap 10px, display flex column
  .header  padding 10px (item-name 16/500 white + subtext margin-top 6 14/500 ink-2)

sboxmarket equivalent is the horizontal `.stall-row` (modals.js:11608) — has
same atomic data fields rendered as a row instead of csfloat's column. The
row's own chrome already lives lean on the panel surface (no own bg/border),
so this ship targets only the price/meta TYPOGRAPHY and the action button so
each cell visually matches csfloat's tokens. Earlier ship #406 sized status
chips and #2700-era styled .price-val globally to mono 14/600 — that mono +
14px is correct for tabular row contexts elsewhere (cart-row, db-table,
recent-sales-row), so we scope the fix to `.stall-row .price-val` ONLY.

Append-only at file tail with !important. NO Docker.
"""
from pathlib import Path
import os, tempfile

CSS = Path(__file__).parent / "src" / "main" / "resources" / "static" / "css" / "design.css"

BLOCK = r"""

/* ============================================================
   CSFLOAT-1:1 PARITY ship #127674 (MyStall .stall-row — REAL csfloat tokens)
   measured live on csfloat.com /stall/me (signed-in) item-card .footer:
     .price        font-size 18px / weight 500 / color rgb(255,255,255)
     .reference    font-size 12px / weight 700 / color rgb(158,167,177)
     online label  font-size 12px / weight 400 / color rgb(255,255,255)
     edit-pencil   32x32 button, br 50%, color rgba(255,255,255,0.38),
                   hover bg rgba(255,255,255,0.04), focus 2px brand ring
     header pad    10px (item-name 16/500 #fff, subtext margin-top 6 14/500 ink-2)
   sboxmarket renders the same data atoms as a horizontal .stall-row
   (modals.js:11608) instead of csfloat's vertical card .footer, so this
   ship snaps the per-atom typography + edit-button chrome to csfloat's
   tokens without touching the row layout (which would require a JSX rewrite).
   .price-val is a global mono 14/600 used in cart-row, db-table, etc., so
   scope is narrowed to `.stall-row .price-val` ONLY — does NOT touch other
   surfaces. Earlier ship #126740-era styled the row's edit/hide/remove as
   .btn.btn-ghost text buttons; csfloat's edit affordance is a 32x32 round
   icon button, so we add a forward-compat selector for both shapes.
   ============================================================ */
html body .stall-row .price-val,
body .stall-row .price-val {
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 18px !important;
  font-weight: 500 !important;
  color: rgb(255, 255, 255) !important;
  letter-spacing: 0 !important;
  line-height: 1.2 !important;
}
/* CSFloat reference-widget percentage (% off ref price) — small bold ink-2.
   Scoped under .stall-row so we don't disturb any other .reference span. */
html body .stall-row .reference,
html body .stall-row .reference-widget,
html body .stall-row .reference-pct,
html body .stall-row .price-vs-ref,
body .stall-row .reference,
body .stall-row .reference-widget,
body .stall-row .reference-pct,
body .stall-row .price-vs-ref {
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 12px !important;
  font-weight: 700 !important;
  color: rgb(158, 167, 177) !important;
  letter-spacing: 0 !important;
}
/* CSFloat .seller-details Online / Away label — tiny 12/400 white. Scoped
   to the row so other "Online" labels (chat presence, etc.) stay untouched. */
html body .stall-row .seller-details,
html body .stall-row .online-label,
html body .stall-row .away-label,
body .stall-row .seller-details,
body .stall-row .online-label,
body .stall-row .away-label {
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 12px !important;
  font-weight: 400 !important;
  color: rgb(255, 255, 255) !important;
  letter-spacing: 0 !important;
}
/* CSFloat edit-pencil — 32x32 round Material icon button, white-alpha 38%
   icon. Earlier sbox baseline rendered .stall-row .btn.btn-ghost as a tall
   text "✎ Edit" pill (modals.js:11849) — we match the ICON-ONLY round
   variant so future icon-only ✎ buttons land on the right token chrome.
   Scoped via :has(svg)/:has(mat-icon) so the existing text "✎ Edit" pill
   keeps its tall shape and only icon-only variants get reshaped. */
html body .stall-row button.edit-pencil,
html body .stall-row button.icon-edit,
html body .stall-row button.btn-ghost.icon-only,
html body .stall-row button[aria-label="Edit price"]:has(svg):not(:has(span)),
html body .stall-row button[aria-label="Edit"]:has(svg):not(:has(span)),
body .stall-row button.edit-pencil,
body .stall-row button.icon-edit,
body .stall-row button.btn-ghost.icon-only {
  width: 32px !important;
  height: 32px !important;
  min-width: 32px !important;
  padding: 4px !important;
  border-radius: 50% !important;
  color: rgba(255, 255, 255, 0.38) !important;
  background: transparent !important;
  background-color: transparent !important;
  border: 0 !important;
  display: inline-flex !important;
  align-items: center !important;
  justify-content: center !important;
  cursor: pointer !important;
  transition: background-color 150ms cubic-bezier(0.4, 0, 0.2, 1),
              color 150ms cubic-bezier(0.4, 0, 0.2, 1) !important;
}
html body .stall-row button.edit-pencil:hover,
html body .stall-row button.icon-edit:hover,
html body .stall-row button.btn-ghost.icon-only:hover,
body .stall-row button.edit-pencil:hover,
body .stall-row button.icon-edit:hover,
body .stall-row button.btn-ghost.icon-only:hover {
  background-color: rgba(255, 255, 255, 0.04) !important;
  color: rgb(255, 255, 255) !important;
}
html body .stall-row button.edit-pencil:focus-visible,
html body .stall-row button.icon-edit:focus-visible,
html body .stall-row button.btn-ghost.icon-only:focus-visible,
body .stall-row button.edit-pencil:focus-visible,
body .stall-row button.icon-edit:focus-visible,
body .stall-row button.btn-ghost.icon-only:focus-visible {
  outline: 2px solid rgb(35, 123, 255) !important;
  outline-offset: 2px !important;
}
html body .stall-row button.edit-pencil svg,
html body .stall-row button.icon-edit svg,
html body .stall-row button.btn-ghost.icon-only svg,
body .stall-row button.edit-pencil svg,
body .stall-row button.icon-edit svg,
body .stall-row button.btn-ghost.icon-only svg {
  width: 18px !important;
  height: 18px !important;
  color: inherit !important;
  fill: currentColor !important;
}
/* The row's leading item-name + sub-meta typography on csfloat is
   16/500 #fff for the name and 14/500 ink-2 for the wear/condition sub-line.
   sbox already centers and gaps the row at 116606; this scopes just the
   typography so the cell weight/size reads identical to csfloat. */
html body .stall-row > div .item-name,
body .stall-row > div .item-name {
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 16px !important;
  font-weight: 500 !important;
  color: rgb(255, 255, 255) !important;
  letter-spacing: 0 !important;
  line-height: 1.3 !important;
}
html body .stall-row > div .subtext,
html body .stall-row > div .item-sub,
html body .stall-row > div .item-meta,
body .stall-row > div .subtext,
body .stall-row > div .item-sub,
body .stall-row > div .item-meta {
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 14px !important;
  font-weight: 500 !important;
  color: rgb(158, 167, 177) !important;
  letter-spacing: 0 !important;
  margin-top: 6px !important;
}
/* END CSFLOAT-1:1 PARITY ship #127674 */
"""

def atomic_append(path: Path, block: str):
    # Read current contents, append block, write to a sibling temp file in the
    # same directory, fsync, then os.replace so the file swap is atomic on
    # Windows + POSIX. This keeps the Spring static handler from ever serving
    # a partially-written design.css mid-restart.
    cur = path.read_text(encoding="utf-8")
    new = cur + block
    fd, tmp_path = tempfile.mkstemp(prefix="design.css.", suffix=".tmp", dir=str(path.parent))
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="") as f:
            f.write(new)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp_path, str(path))
    except Exception:
        try:
            os.unlink(tmp_path)
        except OSError:
            pass
        raise

if __name__ == "__main__":
    if not CSS.exists():
        raise SystemExit(f"design.css not found at {CSS}")
    before = CSS.stat().st_size
    atomic_append(CSS, BLOCK)
    after = CSS.stat().st_size
    print(f"design.css {before} -> {after} bytes (+{after-before})")
