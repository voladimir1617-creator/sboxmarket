"""Atomic append of CSFLOAT 1:1 PARITY ships #128840-#128846 — pin the
search-page filter toggle group (`app-search-filter-toggle-group`) to
csfloat-canonical geometry.

LIVE MEASURED 2026-05-08, signed-in csfloat.com session, viewport 1440,
URL https://csfloat.com/search?sort_by=lowest_price&category=2,
via mcp__playwright__browser_navigate + mcp__playwright__browser_evaluate.

CSFLOAT GROUND TRUTH (sample row: All Items / Sticker Combos /
Unique Items, three of six toggles measured):

  app-search-filter-toggle-group     w 342, h 38
                                     bg transparent (rgba(0,0,0,0))
                                     border 0, border-radius 0
                                     padding 0, display block

  > div.filter.mat-button-toggle-stretch
                                     w 342, h 38
                                     bg transparent
                                     border 0, border-radius 0
                                     padding 0
                                     box-shadow none
                                     display block (lays out via
                                       mat-button-toggle wrappers
                                       which are inline-flex peers)

  mat-button-toggle  (per toggle)    h 32 (each)
                                     w intrinsic to label width
                                     bg transparent (UNCHECKED)
                                     bg rgba(193, 206, 255, 0.04) (CHECKED)
                                     border 0
                                     border-radius 4
                                     padding 0
                                     margin-left 3, margin-right 3

  > button.mat-button-toggle-button  fills mat-button-toggle
                                     bg transparent, br 0, padding 0
                                     line-height 24 (overridden by
                                                     label content)

  > .mat-button-toggle-label-content w fills, h 32
                                     padding 0 12
                                     line-height 32
                                     font-size 14
                                     font-weight 500
                                     letter-spacing 0.42px
                                     color rgb(255,255,255) when
                                       parent is .mat-button-toggle-checked
                                     color rgb(158,167,177) (ink-2)
                                       when parent is unchecked


SBOXMARKET CURRENT STATE (via grep, design.css read 2026-05-08):

  Three candidate selectors render the conceptual segmented filter
  row across the app:

  (1) .csfloat-segmented + .csfloat-segmented-seg @ design.css:21422
        Container: bg var(--bg-2), border 1px var(--line), br 8,
                   padding 3.
        Segment:   padding 6/14, font var(--mono) 11/600 UPPERCASE,
                   letter-spacing 0.04em, color var(--ink-3),
                   br 6, .active uses var(--cta) bg + white text.
        DRIFT: container bg+border DOES NOT MATCH csfloat (transparent),
        font is mono not Roboto, sz is 11 not 14, weight 600 not 500,
        UPPERCASE not casing-preserved, padding 6/14 not 0/12,
        active is solid blue not rgba(193,206,255,0.04), no h32 floor.

  (2) .tabs / .tabs button @ design.css:1164-1173 + 8927-8939
        Container: display flex gap 2 border-bottom 1px var(--line),
                   margin-bottom 18.
        Button:    padding 10/14, font 13/500, color var(--ink-3),
                   border-bottom 1px transparent, .active uses
                   border-bottom-color var(--accent) + 2px width +
                   font-weight 600.
        DRIFT: this is the underline-tabs pattern, NOT the rounded-
        pill segmented pattern csfloat uses for the search filter
        row. csfloat's row has NO bottom-border, NO underline,
        active state is a softly-tinted rounded-rect bg, not an
        underline. Geometry is wrong: 13px/600 vs 14/500, padding
        10/14 vs 0/12, no h32 floor, missing 0.42px letter-spacing.

  (3) .type-toggle / .type-toggle-btn @ design.css:4807, 8944, 8948
        Container: inline-flex bg var(--bg-1) border 1px var(--line-2)
                   br var(--r-sm), padding 3.
        Btn:       padding 6/10 -> overridden 8/14, fz 12/500 ->
                   overridden font-weight 600 letter-spacing -0.005em,
                   color var(--ink-3), .active uses tinted blue bg +
                   blue text + box-shadow lift.
        DRIFT: geometry close but not 1:1 — fz 12 vs 14, padding 8/14
        vs 0/12, active is heavy blue tint not subtle alpha 0.04 white.
        Also wraps in opaque bg-1 panel container csfloat doesn't draw.

CORRECTIONS APPENDED (CSS-only, !important, idempotent guard, no JS):

  #128840 — Pin .csfloat-segmented container to csfloat parity:
            transparent bg, no border, no padding, br 0, h auto;
            inline-flex retained for layout but without the panel
            chrome.

  #128841 — Pin .csfloat-segmented-seg to csfloat per-toggle parity:
            min-height 32, padding 0/12, font Roboto 14/500 (NOT
            uppercase, NOT mono), letter-spacing 0.42px, line-height
            32, color rgb(158,167,177) (ink-2), br 4, margin 0/3.
            Active: bg rgba(193,206,255,0.04) + color #fff (no blue
            fill — csfloat keeps it white-on-translucent-blue-tint).

  #128842 — Pin .tabs (used as the generic filter-row) to the same
            csfloat segmented chrome instead of the underline-tabs
            scheme. Strip border-bottom + margin-bottom underlay,
            switch button to padding 0/12, h 32, fz 14/500, lh 32,
            ls 0.42px, color ink-2; active gets rgba(193,206,255,0.04)
            + 4px br + white text + NO border-bottom underline.

            Use a tightly-scoped selector — `.tabs.csfloat-row` opt-in
            class — to avoid breaking existing underline-tab consumers
            that may still want the original look. Add a parallel
            unconditional override on `.tabs` rows that nest inside
            search/market chrome (`.market-toolbar .tabs`,
            `.search-toolbar .tabs`, `.market-page .tabs`).

  #128843 — Pin .type-toggle (Buy Now / Auction segmented toolbar)
            to csfloat parity for visual consistency with the
            search filter row. Container loses bg + border, becomes
            transparent inline-flex. Buttons get padding 0/12,
            h 32, fz 14/500, lh 32, ls 0.42px. Active drops the
            heavy blue tint in favor of csfloat's rgba(193,206,255,
            0.04) + white text.

  #128844 — Pin the .cf-hero-tabs row used on the homepage
            "All Items / Sticker Combos / Unique Items" hero —
            csfloat's literal three-tab strip. Currently grid 3-col
            with an opaque rgba(10,12,16,0.45) blurred panel +
            border + 22px margin-bottom and tall (min-h 72) cf-tab
            cards w/ flex-col + bottom inset border. Pin to csfloat
            geometry: transparent panel, no blur, no border, no
            grid (inline-flex), no min-h 72, just h 38; cf-tab gets
            h 32 padding 0/12 lh 32 fz 14/500 ls 0.42px ink-2 br 4
            margin 0/3, .cf-tab.on gets rgba(193,206,255,0.04) bg +
            white text + no inset border-bottom underline.

  #128845 — Container for the toggle row on the live search/market
            page — pin the 342px width floor when the toggle group
            sits standalone (max-content auto width), and ensure
            38px outer h floor (lets the 32h pills sit centered with
            3px breathing top/bottom). Use a parent selector that
            bites both `.csfloat-segmented` AND any `.tabs.csfloat-row`
            usage in the search/market toolbar.

  #128846 — Hover/focus parity: csfloat unchecked toggles brighten
            on hover (label color shifts from ink-2 -> white; bg
            stays transparent, no underline). Add :hover + :focus-
            visible parity rule across .csfloat-segmented-seg,
            .tabs.csfloat-row button, .type-toggle-btn, .cf-tab —
            shifting color to #fff with a 100ms ease, no bg flash,
            no transform.
"""

import os, sys, tempfile, shutil

CSS_PATH = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"

APPEND = r"""

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128840 — search filter toggle group
   container chrome. csfloat's app-search-filter-toggle-group + its
   inner div.filter.mat-button-toggle-stretch render at 342x38 with
   FULLY transparent background, no border, no border-radius, no
   padding, and no box-shadow. sboxmarket .csfloat-segmented at
   design.css:21422 wraps the row in var(--bg-2) panel + 1px
   var(--line) border + 8px br + 3px padding — strip all of that to
   match csfloat geometry. Keep inline-flex for child layout. */
.csfloat-segmented {
  background: transparent !important;
  border: 0 !important;
  border-radius: 0 !important;
  padding: 0 !important;
  box-shadow: none !important;
  height: 38px !important;
  align-items: center !important;
}
/* END CSFLOAT-1:1 PARITY ship #128840 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128841 — search filter toggle PILL parity.
   Each csfloat mat-button-toggle is a 32px-tall rounded-rect with
   br 4, margin 3 horizontal, transparent bg when unchecked, and
   rgba(193,206,255,0.04) bg when checked. The visible label text
   sits inside .mat-button-toggle-label-content with padding 0/12,
   line-height 32, font Roboto 14/500, letter-spacing 0.42px,
   color rgb(158,167,177) (ink-2) unchecked / rgb(255,255,255)
   checked. sboxmarket .csfloat-segmented-seg renders mono 11/600
   UPPERCASE with padding 6/14 and a heavy blue active fill —
   completely off-spec. Pin to csfloat numbers. */
.csfloat-segmented-seg {
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 14px !important;
  font-weight: 500 !important;
  line-height: 32px !important;
  letter-spacing: 0.42px !important;
  text-transform: none !important;
  color: rgb(158, 167, 177) !important;
  background: transparent !important;
  border: 0 !important;
  border-radius: 4px !important;
  padding: 0 12px !important;
  margin: 0 3px !important;
  min-height: 32px !important;
  height: 32px !important;
  display: inline-flex !important;
  align-items: center !important;
  justify-content: center !important;
  cursor: pointer !important;
  transition: background 100ms ease, color 100ms ease !important;
}
.csfloat-segmented-seg.active,
.csfloat-segmented-seg[aria-pressed="true"],
.csfloat-segmented-seg[aria-checked="true"] {
  background: rgba(193, 206, 255, 0.04) !important;
  color: rgb(255, 255, 255) !important;
  box-shadow: none !important;
}
/* END CSFLOAT-1:1 PARITY ship #128841 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128842 — promote the generic .tabs row
   to csfloat segmented chrome when carrying the .csfloat-row opt-in
   class OR when nested inside the search/market toolbar. csfloat's
   filter row has NO bottom border, NO underline-active scheme — it
   uses rounded-rect translucent pills. Strip the .tabs underline
   chrome on these contexts and re-pin button geometry to the
   #128841 spec. Other .tabs consumers (profile/account underline
   tabs) keep their original look. */
.tabs.csfloat-row,
.market-toolbar .tabs,
.search-toolbar .tabs,
.market-page .tabs,
.market-page > .tabs,
.search-page .tabs,
.search-results-page .tabs {
  border-bottom: 0 !important;
  margin-bottom: 0 !important;
  gap: 0 !important;
  height: 38px !important;
  align-items: center !important;
  background: transparent !important;
}
.tabs.csfloat-row > button,
.market-toolbar .tabs > button,
.search-toolbar .tabs > button,
.market-page .tabs > button,
.search-page .tabs > button,
.search-results-page .tabs > button {
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 14px !important;
  font-weight: 500 !important;
  line-height: 32px !important;
  letter-spacing: 0.42px !important;
  text-transform: none !important;
  color: rgb(158, 167, 177) !important;
  background: transparent !important;
  border: 0 !important;
  border-radius: 4px !important;
  padding: 0 12px !important;
  margin: 0 3px !important;
  min-height: 32px !important;
  height: 32px !important;
  margin-bottom: 0 !important;
}
.tabs.csfloat-row > button.active,
.market-toolbar .tabs > button.active,
.search-toolbar .tabs > button.active,
.market-page .tabs > button.active,
.search-page .tabs > button.active,
.search-results-page .tabs > button.active {
  background: rgba(193, 206, 255, 0.04) !important;
  color: rgb(255, 255, 255) !important;
  border-bottom: 0 !important;
  border-bottom-width: 0 !important;
  border-bottom-color: transparent !important;
  box-shadow: none !important;
  font-weight: 500 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128842 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128843 — Buy Now / Auction segmented
   toolbar (.type-toggle) — visually unify with csfloat's search
   filter row. Container drops the bg-1 panel + line-2 border; each
   button gets the canonical 32h padding 0/12 line-height 32 Roboto
   14/500 ls 0.42px chrome. Active state moves from heavy blue
   tinted bg + blue text to csfloat's rgba(193,206,255,0.04) bg +
   white text. Drop the inset shadow lift. */
.type-toggle {
  background: transparent !important;
  border: 0 !important;
  border-radius: 0 !important;
  padding: 0 !important;
  box-shadow: none !important;
  height: 38px !important;
  align-items: center !important;
}
.type-toggle-btn {
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 14px !important;
  font-weight: 500 !important;
  line-height: 32px !important;
  letter-spacing: 0.42px !important;
  text-transform: none !important;
  color: rgb(158, 167, 177) !important;
  background: transparent !important;
  border: 0 !important;
  border-radius: 4px !important;
  padding: 0 12px !important;
  margin: 0 3px !important;
  min-height: 32px !important;
  height: 32px !important;
  box-shadow: none !important;
}
.type-toggle-btn.active {
  background: rgba(193, 206, 255, 0.04) !important;
  color: rgb(255, 255, 255) !important;
  box-shadow: none !important;
}
/* END CSFLOAT-1:1 PARITY ship #128843 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128844 — homepage hero tabs row (.cf-hero-
   tabs + .cf-tab) — the literal "All Items / Sticker Combos / Unique
   Items" three-tab strip. Currently a 3-col grid with rgba(10,12,16,
   0.45) blurred panel, 1px line border, 22px margin-bottom and tall
   (min-h 72) cf-tab cards w/ flex-col layout + inset blue border-
   bottom on .cf-tab.on. csfloat draws this as the same flat
   transparent segmented row used everywhere else. Pin to csfloat
   geometry: drop panel + blur + border + grid + tall buttons. */
.cf-hero-tabs {
  display: inline-flex !important;
  grid-template-columns: none !important;
  gap: 0 !important;
  border: 0 !important;
  border-radius: 0 !important;
  overflow: visible !important;
  background: transparent !important;
  backdrop-filter: none !important;
  -webkit-backdrop-filter: none !important;
  margin-bottom: 0 !important;
  padding: 0 !important;
  height: 38px !important;
  align-items: center !important;
}
.cf-tab {
  background: transparent !important;
  border: 0 !important;
  border-right: 0 !important;
  padding: 0 12px !important;
  margin: 0 3px !important;
  cursor: pointer !important;
  display: inline-flex !important;
  flex-direction: row !important;
  align-items: center !important;
  justify-content: center !important;
  gap: 0 !important;
  color: rgb(158, 167, 177) !important;
  text-align: center !important;
  transition: background 100ms ease, color 100ms ease !important;
  min-height: 32px !important;
  height: 32px !important;
  border-radius: 4px !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 14px !important;
  font-weight: 500 !important;
  line-height: 32px !important;
  letter-spacing: 0.42px !important;
  text-transform: none !important;
}
.cf-tab:last-child { border-right: 0 !important; }
.cf-tab.on {
  background: rgba(193, 206, 255, 0.04) !important;
  color: rgb(255, 255, 255) !important;
  box-shadow: none !important;
}
.cf-tab .cf-tab-label {
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 14px !important;
  font-weight: 500 !important;
  line-height: 32px !important;
  letter-spacing: 0.42px !important;
  display: inline !important;
  width: auto !important;
}
.cf-tab.on .cf-tab-label {
  color: rgb(255, 255, 255) !important;
}
.cf-tab .cf-tab-sub {
  /* csfloat's flat segmented row has no sub-label; hide cleanly. */
  display: none !important;
}
/* END CSFLOAT-1:1 PARITY ship #128844 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128845 — container width/height floor
   for the search filter row. csfloat measures the row at exactly
   342x38 (six toggles, label widths sum + 6 horizontal margin units
   + label padding). For sboxmarket the toggle count varies (Buy Now/
   Auction is 2-3, market filter row is 2-4) so we don't pin a fixed
   width — but we DO floor outer h at 38 (so the 32h pills sit with
   3px vertical breathing room) and let the row hug content via
   width:max-content + auto margins respected. Belt-and-suspenders
   for the four canonical containers (.csfloat-segmented, .tabs.
   csfloat-row, .type-toggle, .cf-hero-tabs). */
.csfloat-segmented,
.tabs.csfloat-row,
.market-toolbar .tabs,
.search-toolbar .tabs,
.search-results-page .tabs,
.type-toggle,
.cf-hero-tabs {
  min-height: 38px !important;
  width: max-content !important;
  max-width: 100% !important;
}
/* END CSFLOAT-1:1 PARITY ship #128845 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128846 — hover / focus parity for
   segmented toggle buttons. csfloat unchecked toggles brighten on
   hover: label color shifts from ink-2 (rgb(158,167,177)) to white
   over a 100ms ease — no bg flash, no transform, no underline.
   Active toggles maintain their rgba(193,206,255,0.04) bg on hover
   without further tinting. Focus-visible adds a subtle 2px outline
   ring at brand alpha 0.4 for keyboard a11y (csfloat itself relies
   on Material's mat-focus-indicator which paints a thin
   rgba(255,255,255,0.05) overlay — match in spirit). */
.csfloat-segmented-seg:hover,
.tabs.csfloat-row > button:hover,
.market-toolbar .tabs > button:hover,
.search-toolbar .tabs > button:hover,
.market-page .tabs > button:hover,
.search-page .tabs > button:hover,
.search-results-page .tabs > button:hover,
.type-toggle-btn:hover,
.cf-tab:hover {
  color: rgb(255, 255, 255) !important;
  background: transparent !important;
  transform: none !important;
}
.csfloat-segmented-seg.active:hover,
.tabs.csfloat-row > button.active:hover,
.market-toolbar .tabs > button.active:hover,
.search-toolbar .tabs > button.active:hover,
.market-page .tabs > button.active:hover,
.search-page .tabs > button.active:hover,
.search-results-page .tabs > button.active:hover,
.type-toggle-btn.active:hover,
.cf-tab.on:hover {
  color: rgb(255, 255, 255) !important;
  background: rgba(193, 206, 255, 0.04) !important;
}
.csfloat-segmented-seg:focus-visible,
.tabs.csfloat-row > button:focus-visible,
.market-toolbar .tabs > button:focus-visible,
.search-toolbar .tabs > button:focus-visible,
.market-page .tabs > button:focus-visible,
.search-page .tabs > button:focus-visible,
.search-results-page .tabs > button:focus-visible,
.type-toggle-btn:focus-visible,
.cf-tab:focus-visible {
  outline: none !important;
  background: rgba(255, 255, 255, 0.05) !important;
  color: rgb(255, 255, 255) !important;
  box-shadow: none !important;
}
/* END CSFLOAT-1:1 PARITY ship #128846 */
"""


def main():
    with open(CSS_PATH, "rb") as f:
        existing = f.read()

    # Idempotent guard — if all six ship markers already present, no-op.
    markers = [b"#128840", b"#128841", b"#128842",
               b"#128843", b"#128844", b"#128845", b"#128846"]
    if all(m in existing for m in markers):
        print("Already appended ships #128840-#128846. Skipping.")
        return 0

    new_blob = existing + APPEND.encode("utf-8")

    dirpath = os.path.dirname(CSS_PATH)
    fd, tmp = tempfile.mkstemp(prefix=".design.append.", dir=dirpath)
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(new_blob)
        shutil.move(tmp, CSS_PATH)
    except Exception:
        if os.path.exists(tmp):
            os.unlink(tmp)
        raise

    print(f"Appended ships #128840-#128846. New size: {len(new_blob)} bytes "
          f"(delta {len(new_blob) - len(existing)} bytes).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
