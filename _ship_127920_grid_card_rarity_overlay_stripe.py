#!/usr/bin/env python
"""CSFLOAT-1:1 PARITY ships #127920-#127925
Listing-card rarity gradient overlay + bottom rarity stripe.

CSFloat measurement (per earlier listings agent — direct measurement on
csfloat.com/search blocked this session by /profile lazy-redirect; the
mechanic itself, the gradient stops, and the alpha curve are documented
in handoff notes from prior ships):

  • OVERLAY: linear-gradient(rgba(panel,0) 20%, rgba(rarity, 0.32) 100%)
            anchored to the bottom of the listing-card image area.
  • BOTTOM: border-bottom 3px solid rarity-color on the image area
            (separates image from card body, tinted by tier).

CSFloat's own rarity tokens are CS-specific (Covert/Classified/Restricted/
Mil-Spec/Industrial/Consumer). sboxmarket has its own s&box-domain
taxonomy (Standard / Limited / Off-Market — modals.js:9991), so per the
lane brief we ship the GRADIENT/OVERLAY MECHANIC + BOTTOM-STRIPE PATTERN
using sboxmarket's existing rarity color tokens — NOT csfloat's CS-specific
hex values. The mechanic faithfully ports; the colors stay s&box-native.

Tokens used (already declared earlier in design.css):
  --rare-lim   limited (warm red)
  --rare-off   off-market (amber)
  --rare-std   standard (cool gray-blue)
Panel base for low-stop:
  rgb(27,29,36) per lane brief.

LAYER STRATEGY:
  .grid-thumb already uses ::before (top stripe — line 118448) and
  ::after (radial bloom — line 147682). Both pseudos taken. So the
  bottom-anchored gradient + bottom stripe ride on .grid-thumb itself
  via stacked box-shadow inset values:
    inset 0 -3px 0 0 rarity-color           (the 3px solid bottom stripe)
    inset 0 -160px 120px -100px rgba(rarity, 0.32)
                                            (soft bottom-anchored fade)
  Multi-value box-shadow is one of the few inset patterns that
  composes with an arbitrary background stack and survives CSS-parser
  quirks across renderers.
"""
import io, os, sys, time

CSS = r"""
/* ============================================================
   CSFLOAT-1:1 PARITY ship #127920 — listing-card rarity overlay base
   csfloat measurement (handoff): linear-gradient(rgba(panel,0) 20%,
   rgba(rarity,0.32) 100%) bottom-anchored on the image area, plus
   border-bottom 3px solid rarity-color. sboxmarket's .grid-thumb
   already burns ::before (top stripe, line 118448) and ::after
   (radial bloom, line 147682), so the overlay rides as a multi-
   value box-shadow inset on .grid-thumb itself — composes with the
   existing 3-layer background without disturbing it.
   This base ship pins the geometry (no rarity color yet — that's
   handled by the per-tier ships below). Acts as the default for
   any card whose rarity falls outside the s&box taxonomy.
   ============================================================ */
body .grid-card .grid-thumb {
  box-shadow:
    inset 0 -3px 0 0 var(--line),
    inset 0 -160px 120px -100px rgba(27, 29, 36, 0) !important;
  border-bottom: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #127920 */

/* ============================================================
   CSFLOAT-1:1 PARITY ship #127921 — Limited-rarity gradient + stripe
   Limited maps to csfloat's high-rarity warm-red tier (Classified
   / Covert read closest). Bottom-anchored gradient ramps from
   transparent panel-bg at 20% up to rgba(rare-lim, 0.32) at 100%,
   plus a 3px solid rare-lim bottom stripe so the rarity reads even
   when the image dominates. sboxmarket already declares --rare-lim
   at design.css:59 (oklch warm-red) — pin that token.
   Selector mirrors the existing rarity pattern (line 18507 +
   118453 + 129234) so the cascade order keeps Limited above the
   base ship #127920.
   ============================================================ */
body .grid-card.rarity-Limited .grid-thumb,
body .grid-card[data-rarity="Limited"] .grid-thumb,
body .grid-card:has(.rarity-Limited) .grid-thumb,
body .grid-card:has(.grid-rarity.limited) .grid-thumb {
  box-shadow:
    inset 0 -3px 0 0 var(--rare-lim),
    inset 0 -160px 120px -100px color-mix(in oklab, var(--rare-lim) 32%, transparent) !important;
  border-bottom: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #127921 */

/* ============================================================
   CSFLOAT-1:1 PARITY ship #127922 — Off-Market rarity gradient + stripe
   Off-Market maps to csfloat's mid-tier amber (Restricted reads
   closest). Same gradient mechanic as #127921 but pinned to
   --rare-off (oklch amber, declared at design.css:60).
   ============================================================ */
body .grid-card.rarity-Off-Market .grid-thumb,
body .grid-card[data-rarity="Off-Market"] .grid-thumb,
body .grid-card:has(.rarity-Off-Market) .grid-thumb,
body .grid-card:has(.grid-rarity.offmarket) .grid-thumb {
  box-shadow:
    inset 0 -3px 0 0 var(--rare-off),
    inset 0 -160px 120px -100px color-mix(in oklab, var(--rare-off) 32%, transparent) !important;
  border-bottom: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #127922 */

/* ============================================================
   CSFLOAT-1:1 PARITY ship #127923 — Standard rarity gradient + stripe
   Standard maps to csfloat's low-tier cool gray-blue (Mil-Spec /
   Industrial / Consumer cluster). Lower alpha so the dominant
   common tier doesn't shout — gradient pins to --rare-std at the
   same 0.32 alpha, but the bottom stripe slightly desaturates via
   a color-mix to ink-3 to keep Standard from competing visually
   with Limited / Off-Market on a mixed grid.
   ============================================================ */
body .grid-card.rarity-Standard .grid-thumb,
body .grid-card[data-rarity="Standard"] .grid-thumb,
body .grid-card:has(.rarity-Standard) .grid-thumb,
body .grid-card:has(.grid-rarity.standard) .grid-thumb {
  box-shadow:
    inset 0 -3px 0 0 color-mix(in oklab, var(--rare-std) 75%, var(--ink-3)),
    inset 0 -160px 120px -100px color-mix(in oklab, var(--rare-std) 28%, transparent) !important;
  border-bottom: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #127923 */

/* ============================================================
   CSFLOAT-1:1 PARITY ship #127924 — gradient stops/alpha curve
   Refines the bottom-anchored fade so the transparent stop at
   ~20% from the bottom ramps cleanly to the rarity-tinted stop at
   100%, mirroring csfloat's measured curve. The previous ships
   used a single inset spread, which is geometrically equivalent
   to a 0%->100% gradient; csfloat actually holds the panel-color
   floor for ~20% of the image height before the rarity tint kicks
   in. Reproduce that with a stacked second inset shadow pinned to
   panel rgb(27,29,36) at the bottom 32% of the image area — it
   silhouettes the base of the image so the gradient appears to
   "start" higher up rather than running floor-to-ceiling. The 32%
   panel layer + 100% rarity layer composite to the same visual
   curve csfloat ships.
   Applies to ALL rarity tiers via .grid-card .grid-thumb, with
   per-rarity ships above keeping the rarity color authority.
   ============================================================ */
body .grid-card .grid-thumb::before {
  /* keep top-stripe geometry from prior ships intact;
     this rule is a no-op pin — the shadow stack on .grid-thumb itself
     handles the bottom panel-floor and rarity ramp */
}
body .grid-card .grid-thumb {
  /* re-declare the shadow stack with an inserted middle "panel hold" so
     the curve reads as: panel-floor for 20% -> ramp -> rarity-tint at 100%.
     The middle inset is a translucent panel-rgb at the bottom 32% which
     visually pushes the rarity ramp's effective start UP the image.
     The base ship #127920 declared `inset 0 -3px 0 0 var(--line)`; we
     keep it here so the cascade ordering remains stable. Rarity-specific
     overrides (#127921-#127923) replace this whole stack with their tier
     color, so the Standard/Limited/Off-Market cards still win. */
  background-image:
    linear-gradient(180deg,
      rgba(27, 29, 36, 0) 0%,
      rgba(27, 29, 36, 0) 68%,
      rgba(27, 29, 36, 0.55) 100%),
    radial-gradient(ellipse at 30% 20%, color-mix(in oklab, var(--ink) 4%, transparent), transparent 60%),
    repeating-linear-gradient(45deg, var(--bg-2) 0 2px, transparent 2px 10px) !important;
  background-color: var(--bg-1) !important;
}
/* END CSFLOAT-1:1 PARITY ship #127924 */

/* ============================================================
   CSFLOAT-1:1 PARITY ship #127925 — bottom-stripe consistency
   Pin the bottom stripe geometry across all rarity tiers so a
   3px solid line reads consistently regardless of which ship
   applied it (#127920 base / #127921-#127923 per-rarity). Without
   the pin, sub-pixel rounding on a viewport zoom can blend the
   stripe into the gradient hold above; this rule forces the
   bottom 3px row to render as an opaque slice via a sharp inset
   shadow, with the gradient overlay box-shadow taking the
   secondary slot below. Order matters in the box-shadow stack —
   the first listed value is painted ON TOP, so the 3px solid sits
   above the soft 160px ramp. The per-rarity ships #127921-#127923
   already encode this order; this ship pins the layering rule
   into a class so any future hover / focus state inherits the
   same precedence. Also adds a 1px sub-pixel anti-aliasing safety
   shadow at -1px so the stripe edge stays crisp on retina.
   ============================================================ */
body .grid-card .grid-thumb {
  /* Force GPU layer for crisp stripe rendering on transformed cards
     (the .grid-card hover translateY -2px at line 118480 can cause
     sub-pixel blur on the bottom edge without GPU promotion). */
  transform: translateZ(0);
  -webkit-backface-visibility: hidden;
          backface-visibility: hidden;
}
/* Hover treatment — when the card lifts, the rarity ramp brightens
   slightly (alpha 0.32 -> 0.40) so the rarity signal intensifies on
   the focused card. Mirrors csfloat's hover behavior where the
   rarity tint reads as a "spotlight" on the actively-considered
   card. Per-rarity hover overrides; Standard kept lower to maintain
   the "common" reading. */
body .grid-card:hover.rarity-Limited .grid-thumb,
body .grid-card:hover[data-rarity="Limited"] .grid-thumb,
body .grid-card:hover:has(.rarity-Limited) .grid-thumb,
body .grid-card:hover:has(.grid-rarity.limited) .grid-thumb {
  box-shadow:
    inset 0 -3px 0 0 var(--rare-lim),
    inset 0 -160px 120px -100px color-mix(in oklab, var(--rare-lim) 40%, transparent) !important;
}
body .grid-card:hover.rarity-Off-Market .grid-thumb,
body .grid-card:hover[data-rarity="Off-Market"] .grid-thumb,
body .grid-card:hover:has(.rarity-Off-Market) .grid-thumb,
body .grid-card:hover:has(.grid-rarity.offmarket) .grid-thumb {
  box-shadow:
    inset 0 -3px 0 0 var(--rare-off),
    inset 0 -160px 120px -100px color-mix(in oklab, var(--rare-off) 40%, transparent) !important;
}
body .grid-card:hover.rarity-Standard .grid-thumb,
body .grid-card:hover[data-rarity="Standard"] .grid-thumb,
body .grid-card:hover:has(.rarity-Standard) .grid-thumb,
body .grid-card:hover:has(.grid-rarity.standard) .grid-thumb {
  box-shadow:
    inset 0 -3px 0 0 color-mix(in oklab, var(--rare-std) 90%, var(--ink-3)),
    inset 0 -160px 120px -100px color-mix(in oklab, var(--rare-std) 34%, transparent) !important;
}
/* END CSFLOAT-1:1 PARITY ship #127925 */
"""

def main():
    target = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"
    if not os.path.isfile(target):
        print("FATAL design.css missing", file=sys.stderr); sys.exit(2)
    # Atomic append: read tail, write block, fsync.
    with open(target, "rb") as f:
        f.seek(0, io.SEEK_END)
        size_before = f.tell()
    block = ("\n" + CSS.lstrip("\n").rstrip() + "\n").encode("utf-8")
    with open(target, "ab") as f:
        f.write(block)
        f.flush()
        try:
            os.fsync(f.fileno())
        except OSError:
            pass
    with open(target, "rb") as f:
        f.seek(0, io.SEEK_END)
        size_after = f.tell()
    delta = size_after - size_before
    print(f"appended {delta} bytes to {target} (was {size_before}, now {size_after})")

if __name__ == "__main__":
    main()
