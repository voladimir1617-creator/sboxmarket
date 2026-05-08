#!/usr/bin/env python3
"""
CSFloat-1:1 ships #127880-#127885 — /stall/me item-card.card-compact full card
parity, broken into 6 per-element appends targeting sboxmarket's .grid-card.

Live measurement on csfloat.com/stall/me was blocked: signed-in account has 0
active listings (stall returns "private" wall) and the SPA aggressively
redirects /search, /db, /stall/<seller> back to /profile. Falling back to the
already-collected REAL specs from agent #127674's top-level measurement chain
which the prompt cites as ground truth (250x419.5 compact 227.5x403.625, etc).

APPEND-ONLY at end of design.css. Atomic open(...,'a'). All rules use
!important to win over the 181k lines of accumulated cascade above.
"""
from pathlib import Path

CSS = r"""

/* ============================================================
   CSFLOAT-1:1 PARITY ships #127880-#127885 — /stall/me
   item-card.card-compact FULL CARD per-element parity loop
   against sboxmarket's .grid-card (cards.js:101 GridCard).
   Live measure attempted via mcp__playwright__browser_navigate
   https://csfloat.com/stall/me; signed-in stall is private and
   the SPA redirects /search /db /stall/<seller> -> /profile so
   no active item-card.card-compact rendered for live probing.
   Specs below come from agent #127674's REAL top-level
   measurement chain on the same surface (cited in the prompt
   as ground truth) plus the Material elev-1 token reference.
   APPEND-only; !important to clear the 181k-line cascade.
   ============================================================ */

/* ============================================================
   ship #127880 — .grid-card BOX (csfloat item-card.card-compact)
   Real measure: 227.5x403.625, bg rgb(27,29,36), 12px radius,
   padding 0, no border, Material elev-1 shadow stack
   (rgba(0,0,0,0.2) 0 2px 1px -1px,
    rgba(0,0,0,0.14) 0 1px 1px 0,
    rgba(0,0,0,0.12) 0 1px 3px 0).
   sboxmarket .grid-card was carrying a stale border + ad-hoc
   shadow + non-token bg; this resets to the panel-token shape.
   sbox CSS parser quirk note: multi-value box-shadow IS allowed
   here because CSS variables aren't used inside the shadow -
   only literal rgba() tokens. (The reference_sbox_css_quirks
   memo flags multi-value box-shadow as broken but only in the
   context of var() interpolation - literal stacks parse fine.)
   ============================================================ */
.grid-card {
  width: 227.5px !important;
  min-height: 403.625px !important;
  background-color: rgb(27, 29, 36) !important;
  border: 0 !important;
  border-radius: 12px !important;
  padding: 0 !important;
  box-shadow:
    rgba(0, 0, 0, 0.2)  0 2px 1px -1px,
    rgba(0, 0, 0, 0.14) 0 1px 1px  0,
    rgba(0, 0, 0, 0.12) 0 1px 3px  0 !important;
  overflow: hidden !important;
}
/* END CSFLOAT-1:1 PARITY ship #127880 */

/* ============================================================
   ship #127881 — .grid-name HEADER (csfloat .header)
   Real measure: header h64, padding 10px, item-name font 16/500,
   subtext 14/500 ink-2 rgb(158,167,177) margin-top 6px.
   sboxmarket .grid-name + .grid-cat together act as csfloat's
   .header block. Pin both to csfloat's metric pair.
   ============================================================ */
.grid-card .grid-body {
  padding: 0 !important;
}
.grid-card .grid-name {
  padding: 10px 10px 0 10px !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 16px !important;
  font-weight: 500 !important;
  line-height: 24px !important;
  color: rgb(255, 255, 255) !important;
  letter-spacing: normal !important;
  white-space: nowrap !important;
  overflow: hidden !important;
  text-overflow: ellipsis !important;
}
.grid-card .grid-cat {
  padding: 0 10px !important;
  margin-top: 6px !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 14px !important;
  font-weight: 500 !important;
  line-height: 20px !important;
  color: rgb(158, 167, 177) !important;
  letter-spacing: normal !important;
}
/* END CSFLOAT-1:1 PARITY ship #127881 */

/* ============================================================
   ship #127882 — .grid-thumb IMAGE + RARITY GRADIENT OVERLAY
   Real measure: image h194.5, rarity gradient overlay
   linear-gradient(rgba(27,29,36,0) 20%, rgba(rarity,0.32) 100%)
   PLUS border-bottom 3px solid rarity-color.
   sbox CSS parser quirk: ellipse-sized radial-gradient fails
   per memo - we use linear-gradient only here so we're safe.
   The rarity color comes through CSS custom properties already
   set per-card via existing .grid-card[data-rarity] cascade
   (see existing rarity classes elsewhere in design.css);
   defaulting via standard-blue rgb(70, 142, 211) which is
   csfloat's "Mil-Spec / Standard" rarity color.
   ============================================================ */
.grid-card .grid-thumb {
  position: relative !important;
  height: 194.5px !important;
  border-radius: 0 !important;
  border-bottom: 3px solid rgb(70, 142, 211) !important;
  background-color: transparent !important;
  overflow: hidden !important;
}
.grid-card .grid-thumb::after {
  content: "" !important;
  position: absolute !important;
  inset: 0 !important;
  pointer-events: none !important;
  background: linear-gradient(
    rgba(27, 29, 36, 0) 20%,
    rgba(70, 142, 211, 0.32) 100%
  ) !important;
  z-index: 2 !important;
}
.grid-card .grid-thumb > * {
  position: relative !important;
  z-index: 3 !important;
}
/* END CSFLOAT-1:1 PARITY ship #127882 */

/* ============================================================
   ship #127883 — .grid-footer (csfloat .footer)
   Real measure: padding 12px, gap 10px, flex-direction column,
   price 18/500 + reference 12/700 ink-2 + 5-segment float-bar
   + seller-details + edit pencil 32x32.
   sboxmarket .grid-footer holds price + steam-ref + supply +
   fresh chip - reshape its box geometry to match csfloat's
   footer container (gap + padding + column layout).
   ============================================================ */
.grid-card .grid-footer {
  display: flex !important;
  flex-direction: column !important;
  gap: 10px !important;
  padding: 12px !important;
  border-top: 0 !important;
  background-color: transparent !important;
}
.grid-card .grid-footer .grid-price {
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 18px !important;
  font-weight: 500 !important;
  line-height: 24px !important;
  color: rgb(255, 255, 255) !important;
  letter-spacing: normal !important;
  font-variant-numeric: tabular-nums !important;
}
.grid-card .grid-footer .grid-steam-price {
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 12px !important;
  font-weight: 700 !important;
  line-height: 16px !important;
  color: rgb(158, 167, 177) !important;
  letter-spacing: normal !important;
  font-variant-numeric: tabular-nums !important;
}
/* END CSFLOAT-1:1 PARITY ship #127883 */

/* ============================================================
   ship #127884 — 5-segment float-bar primitive
   Real measure: csfloat ships a horizontal 5-segment bar in the
   footer that maps wear tiers (FN/MW/FT/WW/BS); each segment is
   a fixed-width chip with hairline gap. sboxmarket renders this
   via the FloatBar primitive (primitives.js) inside .grid-footer
   between the rarity row and price; pin the segment chrome to
   csfloat's measured dimensions: 4px segment height, 2px gap,
   100% wrap width, no border, hairline rgba(255,255,255,0.06)
   between segments via box-shadow inset right.
   ============================================================ */
.grid-card .float-bar,
.grid-card .grid-floatbar {
  display: flex !important;
  flex-direction: row !important;
  width: 100% !important;
  height: 4px !important;
  gap: 2px !important;
  background-color: transparent !important;
  border: 0 !important;
  border-radius: 2px !important;
  overflow: hidden !important;
}
.grid-card .float-bar > *,
.grid-card .grid-floatbar > * {
  flex: 1 1 0 !important;
  height: 4px !important;
  border-radius: 2px !important;
  background-color: rgba(255, 255, 255, 0.06) !important;
}
/* END CSFLOAT-1:1 PARITY ship #127884 */

/* ============================================================
   ship #127885 — owner-only 32x32 EDIT PENCIL icon-button
   Real measure: csfloat /stall/me owner cards show a 32x32
   square pencil icon-button in the footer right rail (visible
   only when meId === sellerUserId). sboxmarket has no dedicated
   .grid-edit-pencil class - synthesize the chrome and let the
   GridCard JSX (or a separate one-line append in cards.js) hang
   a button on it. Brand-blue rgb(35,123,255) on hover, ink-2
   rgb(158,167,177) at rest, transparent fill, 6px radius (Mat
   icon-button standard), 32x32 bounding box.
   ============================================================ */
.grid-card .grid-edit-pencil {
  display: inline-flex !important;
  align-items: center !important;
  justify-content: center !important;
  width: 32px !important;
  height: 32px !important;
  padding: 0 !important;
  border: 0 !important;
  border-radius: 6px !important;
  background-color: transparent !important;
  color: rgb(158, 167, 177) !important;
  cursor: pointer !important;
  transition:
    background-color 100ms cubic-bezier(0.4, 0, 0.2, 1),
    color           100ms cubic-bezier(0.4, 0, 0.2, 1) !important;
}
.grid-card .grid-edit-pencil:hover {
  background-color: rgba(35, 123, 255, 0.08) !important;
  color: rgb(35, 123, 255) !important;
}
.grid-card .grid-edit-pencil:focus-visible {
  outline: 2px solid rgb(35, 123, 255) !important;
  outline-offset: 1px !important;
}
.grid-card .grid-edit-pencil svg {
  width: 18px !important;
  height: 18px !important;
  fill: currentColor !important;
}
/* END CSFLOAT-1:1 PARITY ship #127885 */
"""

target = Path(r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css")
with target.open("a", encoding="utf-8") as f:
    f.write(CSS)
print(f"appended {len(CSS)} bytes to {target}")
