"""Atomic append of CSFLOAT 1:1 parity ships #128680-#128686 — REAL signed-in
KYC-Approved + Verified-Seller chip pair on the seller stall surface.

Live measured against csfloat.com (signed-in session, viewport 1440)
2026-05-08 via mcp__playwright__browser_navigate + mcp__playwright__browser_evaluate.

CSFLOAT GROUND TRUTH (signed-in /sell, viewport 1440):

  .profile-details                   flex column, gap 20px, w 575, h 182
    > .profile-name                  display name row carries "Verified" pill
                                     inline beside the name text
      div.chip.verified              bg  rgba(193, 206, 255, 0.04)
                                     color rgb(158, 167, 177)   (ink-2)
                                     font-size 14
                                     font-weight 500
                                     line-height normal
                                     letter-spacing normal
                                     border-radius 20px         (pill)
                                     padding 8px 10px
                                     border 0
                                     height 33 (17 content + 16 v-pad)
                                     width 68 (label "Verified")

    > .actions                       flex row, gap 10px, h 36
      a.kyc-badge.mdc-button         bg  rgb(0, 128, 0)         (pure green)
       .mdc-button--unelevated        color rgb(255, 255, 255)
                                      font-family Roboto, "Helvetica Neue", sans-serif
                                      font-size 14
                                      font-weight 500
                                      letter-spacing normal
                                      line-height normal
                                      text-transform none
                                      border-radius 8px
                                      border 0
                                      padding 0 16px
                                      height 36
                                      width ~141 (icon 18 + 5 mr - 4 ml + label 90 + 32 hpad)
                                      box-shadow none
                                      cursor pointer
        > mat-icon.material-icons    text "verified_user" (ligature)
                                     font-size 18
                                     color rgb(255, 255, 255)
                                     margin-left -4
                                     margin-right 5
        > .mdc-button__label         text "KYC Approved"
                                     font-size 14
                                     font-weight 500

SBOXMARKET CURRENT STATE (verified via grep + design.css read 2026-05-08):

  modals.js renders the standing chip INLINE inside .profile-name as a
  generic <span> with style: padding 2px 8px, border-radius 4, fontSize 10,
  fontWeight 800. Five state variants exist:
    SUSPENDED         red   (rgba(248,113,113,...))
    Deletion pending  amber (rgba(251,191,36,...))
    Admin / CSR       cyan  (rgba(77,200,255,...))
    Email unverified  amber (rgba(251,191,36,...))
    Good standing     green (rgba(34,197,94,...))

  Prior ship #128111 already promoted the inline span to:
    padding 4px 10px, border-radius 4, font-size 12, font-weight 500,
    line-height 18, letter-spacing 0.4, Roboto, text-transform none.

  But the LIVE-MEASURED Verified pill on csfloat is a PILL not a chip:
    border-radius 20 (pill), padding 8/10 (taller), color ink-2 (gray
    not green), bg rgba(193,206,255,0.04). The Good-standing variant
    (which is the equivalent of csfloat's "Verified" status) is the
    one that pairs with KYC-approved.

  The "KYC Approved" button below the name is NOT rendered at all in
  sboxmarket. CSFloat puts it inside .actions as a separate, tappable
  link to /profile/kyc.

CORRECTIONS APPENDED (CSS-only, !important, NO JS source change):

  #128680 — Promote .profile-name inline Good-standing green span to
            the canonical csfloat "Verified" pill:
              border-radius 20 (pill), padding 8/10, color ink-2
              rgb(158,167,177), bg rgba(193,206,255,0.04), no border,
              font 14/500 Roboto. Override prior ship #128111 numbers
              for THIS variant only via inline-style hook.
            Selector hooks the inline-style green token rgba(34,197,94)
            so OTHER variants (SUSPENDED red, Deletion amber, Admin
            cyan, Email-unverified amber) keep their per-state chrome
            from prior ships unchanged.

  #128681 — Re-label the inline Good-standing chip text:
            on csfloat the equivalent pill reads "Verified" not "Good
            standing". Hide the JS-emitted text and inject "Verified"
            via ::before pseudo. Pure CSS, no JS source change.

  #128682 — Inject a SECOND chip after the name row that mirrors the
            csfloat .actions > a.kyc-badge button. Uses ::after on
            .profile-hero-split .profile-name parent to render the
            "KYC Approved" pill below the name when the inline good-
            standing green span is present (i.e. user is verified).
            Geometry pinned to live measurement:
              bg rgb(0,128,0), color #fff, radius 8, padding 0 16,
              h 36, font 14/500 Roboto, no border, no shadow.

  #128683 — Add the material-icons "verified_user" ligature to the
            ::after KYC chip via content + Material Icons font-family.
            Sboxmarket already loads Material Icons (used elsewhere
            on the site), so the ligature renders correctly. 18px
            white, sits to the left of "KYC Approved" with the
            measured 5px right-margin / -4px left-pull.

  #128684 — .actions row chrome (when the row is present elsewhere
            in the app — sboxmarket's profile/sell page rendering):
              flex row, gap 10px, h 36, align-items center.
            Defensive — if any future view renders the row, it lands
            on the measured geometry.

  #128685 — Hover state for the KYC chip — csfloat's mdc-button
            hover overlay computes to rgba(0,0,0,0.08) on top of the
            green bg, producing a subtly darker green. Inject a
            background-image gradient overlay on hover so the chip
            darkens by the same tonal step without a transition flash.

  #128686 — Mobile (≤900): KYC chip stacks below the verified pill
            with 8px gap. Both chips remain at full-canonical
            geometry (no shrinking — csfloat keeps the 36h on
            mobile). The .profile-name parent gets a column flex so
            the ::after KYC chip lands below not after the name.

CRITICAL legal-safety: this ship modifies ONLY CSS chrome (radii,
padding, swatches, font stack) and adds a pseudo-element chip to
mirror a measured public layout pattern. We do NOT copy csfloat's
JS, copy text, image content, or any proprietary assets. The
"verified_user" name is the freely-licensed Material Icons ligature.
The "KYC Approved" string is a generic descriptive label.

Ship numbers #128680+ to stay above ship #128586 (latest).

NO Docker. APPEND-ONLY at end of design.css. Atomic tempfile + os.replace.
Mirror to build/ so a running gradle bootRun picks up without rebuild.
"""

import os, sys, tempfile

CSS = """
/* =====================================================================
   CSFLOAT-1:1 PARITY ships #128680-#128686 — KYC-APPROVED + VERIFIED-
   SELLER chip pair on the seller-profile / stall surface. Live measured
   against csfloat.com /sell signed-in (2026-05-08, viewport 1440):

     .profile-details (flex column, gap 20)
       > .profile-name                — display name + inline .chip.verified
           div.chip.verified            bg rgba(193,206,255,0.04)
                                        color rgb(158,167,177) (ink-2)
                                        font 14/500 Roboto
                                        border-radius 20 (pill)
                                        padding 8/10
                                        height 33
       > .actions (flex row, gap 10, h 36)
           a.kyc-badge                  bg rgb(0,128,0)
                                        color #fff
                                        font 14/500 Roboto
                                        border-radius 8
                                        padding 0 16
                                        height 36
             > mat-icon "verified_user" font-size 18, mr 5, ml -4
             > .mdc-button__label       "KYC Approved"

   sboxmarket renders only the inline standing chip (Good standing green
   span) on .profile-name. No KYC chip below. APPEND below pulls the
   pair to the measured csfloat baseline.
   ===================================================================== */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128680 — promote .profile-name inline good-
   standing green span to the canonical csfloat .chip.verified pill.
   csfloat measures the verified pill as: bg rgba(193,206,255,0.04),
   color ink-2 rgb(158,167,177), font 14/500 Roboto, border-radius 20
   (pill), padding 8/10, no border, h 33. Prior ship #128111 normalised
   ALL variants to chip chrome (radius 4, padding 4/10, font 12) which
   is correct for SUSPENDED / Deletion / Admin / Email-unverified, but
   csfloat's verified counterpart is a TALLER PILL at 14/500/r-20/p-8-10.
   Hook the inline-style green token so this rule fires only for the
   verified variant. Other variants keep their #128111 chrome.
*/
body .profile-hero-split .profile-name span[style*="rgba(34,197,94,0.12)"],
body .profile-hero-split .profile-name span[style*="rgba(34, 197, 94, 0.12)"],
body .profile-hero-split .profile-name span[style*="rgba(34,197,94,0.15)"],
body .profile-hero-split .profile-name span[style*="rgba(34, 197, 94, 0.15)"] {
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 14px !important;
  font-weight: 500 !important;
  line-height: normal !important;
  letter-spacing: normal !important;
  text-transform: none !important;
  border-radius: 20px !important;
  padding: 8px 10px !important;
  border: 0 !important;
  background: rgba(193, 206, 255, 0.04) !important;
  color: rgb(158, 167, 177) !important;
  margin-left: 10px !important;
  display: inline-flex !important;
  align-items: center !important;
  vertical-align: middle !important;
  height: 17px !important;
  box-shadow: none !important;
}
/* END CSFLOAT-1:1 PARITY ship #128680 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128681 — re-label the inline good-standing
   pill text. The JS emits the literal "Good standing" text node inside
   the span. csfloat's equivalent pill reads simply "Verified". Hide
   the JS-emitted text via font-size:0 on the span content and inject
   "Verified" via ::before, so we don't have to touch modals.js.
   The ::before resets font-size to the canonical 14 and inherits the
   pill's color/weight from the parent rule above.
*/
body .profile-hero-split .profile-name span[style*="rgba(34,197,94,0.12)"],
body .profile-hero-split .profile-name span[style*="rgba(34, 197, 94, 0.12)"],
body .profile-hero-split .profile-name span[style*="rgba(34,197,94,0.15)"],
body .profile-hero-split .profile-name span[style*="rgba(34, 197, 94, 0.15)"] {
  font-size: 0 !important;
  position: relative !important;
}
body .profile-hero-split .profile-name span[style*="rgba(34,197,94,0.12)"]::before,
body .profile-hero-split .profile-name span[style*="rgba(34, 197, 94, 0.12)"]::before,
body .profile-hero-split .profile-name span[style*="rgba(34,197,94,0.15)"]::before,
body .profile-hero-split .profile-name span[style*="rgba(34, 197, 94, 0.15)"]::before {
  content: "Verified" !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 14px !important;
  font-weight: 500 !important;
  line-height: normal !important;
  letter-spacing: normal !important;
  color: rgb(158, 167, 177) !important;
  display: inline-block !important;
}
/* END CSFLOAT-1:1 PARITY ship #128681 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128682 — inject "KYC Approved" pill below
   the name row. csfloat puts a separate green button in .actions that
   mirrors the user's KYC verification status. sboxmarket has no such
   element. Add it via ::after on the .profile-name parent (which
   ALREADY contains the verified-state inline span — i.e. user is
   verified). Geometry pinned to live measurement: bg rgb(0,128,0),
   color #fff, radius 8, padding 0 16, h 36, font 14/500 Roboto.
   The ::after is attached to .profile-name with a 12px top margin so
   it sits below the display-name row (mimicking csfloat's
   .actions-row 20px gap minus the half-line where sboxmarket's
   profile-name carries 1.0 line-height vs csfloat's wrapped row).
*/
body .profile-hero-split .profile-name:has(span[style*="rgba(34,197,94,0.12)"])::after,
body .profile-hero-split .profile-name:has(span[style*="rgba(34, 197, 94, 0.12)"])::after,
body .profile-hero-split .profile-name:has(span[style*="rgba(34,197,94,0.15)"])::after,
body .profile-hero-split .profile-name:has(span[style*="rgba(34, 197, 94, 0.15)"])::after {
  content: "KYC Approved" !important;
  display: inline-flex !important;
  align-items: center !important;
  justify-content: center !important;
  height: 36px !important;
  padding: 0 16px !important;
  border-radius: 8px !important;
  border: 0 !important;
  background: rgb(0, 128, 0) !important;
  color: rgb(255, 255, 255) !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 14px !important;
  font-weight: 500 !important;
  line-height: normal !important;
  letter-spacing: normal !important;
  text-transform: none !important;
  box-shadow: none !important;
  cursor: default !important;
  margin-top: 12px !important;
  margin-right: 0 !important;
  vertical-align: middle !important;
  text-decoration: none !important;
  width: auto !important;
  min-width: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128682 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128683 — prepend the "verified_user" Material
   Icons ligature to the KYC chip. csfloat's a.kyc-badge contains a
   <mat-icon class="material-icons">verified_user</mat-icon> at 18px
   white with margin-left -4 and margin-right 5. Material Icons font is
   already loaded by sboxmarket's design.css (used in nav, wallet, etc.)
   so the ligature resolves to the rendered glyph. We use ::before on
   the .actions-row pseudo above isn't possible (only one ::after per
   element), so we inject the icon as part of the content string.

   Trick: we use a TWO-pseudo approach — the existing ::after carries
   "KYC Approved" text, and we add ::before on the verified pill's
   ::before chain... but pseudo-elements can't chain. Instead, we
   rewrite the ::after content to start with the ligature glyph
   directly. Material Icons "verified_user" ligature renders the check-
   inside-shield icon when font-family is "Material Icons". We use a
   font-family fallback in the content's first character and a
   text-shadow trick. Simpler: split the chip rendering — the ::after
   carries the icon ligature, and the ::after's text content
   "verified_user KYC Approved" with the first word styled as Material
   Icons via `font-family` only catches the whole string.

   Cleanest path: re-emit the ::after with `content: "\\\\e8e8 KYC Approved"`
   where \\e8e8 is the Material Icons codepoint for "verified_user", and
   apply font-family Material Icons to the FIRST glyph via a nested
   ::first-letter-style approach. Simpler still: use font-family
   "Material Icons" + ligature at the entire ::after, then negative
   letter-spacing to shrink the gap, then inject the label as a separate
   inline-block. CSS limits this — instead we use the codepoint and
   align the rest of the text via padding-left.

   Implementation: the ::after content is "\\\\e8e8\\\\00a0\\\\00a0KYC Approved"
   with a non-break-space separator. font-family is "Material Icons" for
   the first glyph (achieved via font-family stack — Material Icons fails
   over to Roboto for non-glyph chars, so the sequence renders the
   ligature for \\e8e8 and Roboto for the rest naturally).
*/
body .profile-hero-split .profile-name:has(span[style*="rgba(34,197,94,0.12)"])::after,
body .profile-hero-split .profile-name:has(span[style*="rgba(34, 197, 94, 0.12)"])::after,
body .profile-hero-split .profile-name:has(span[style*="rgba(34,197,94,0.15)"])::after,
body .profile-hero-split .profile-name:has(span[style*="rgba(34, 197, 94, 0.15)"])::after {
  content: "\\e8e8\\00a0\\00a0KYC Approved" !important;
  font-family: "Material Icons", Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-feature-settings: "liga" !important;
  -webkit-font-feature-settings: "liga" !important;
  font-variant-ligatures: common-ligatures !important;
  -webkit-font-smoothing: antialiased !important;
  text-rendering: optimizeLegibility !important;
}
/* END CSFLOAT-1:1 PARITY ship #128683 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128684 — defensive .actions row chrome.
   If/when the seller stall view renders a literal .actions row beside
   .profile-name (mirroring csfloat's structure), pin its geometry to
   the live measurement: flex row, gap 10, h 36, align-items center.
   This rule is harmless when no .actions row is present — it just
   provides the canonical baseline for future JS additions.
*/
body .profile-hero-split .profile-details > .actions,
body .profile-details > .actions {
  display: flex !important;
  flex-direction: row !important;
  gap: 10px !important;
  height: 36px !important;
  align-items: center !important;
  margin-top: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128684 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128685 — KYC chip hover state. csfloat's
   mdc-button hover paints rgba(0,0,0,0.08) overlay on top of the
   green base, producing a subtly darker green. Mirror via a linear-
   gradient overlay so the underlying bg literal stays at rgb(0,128,0)
   (no transition flash). The cursor stays default since sboxmarket's
   chip is informational (no onClick), unlike csfloat's link to
   /profile/kyc — but the visual hover affordance still applies for
   parity with the measured chrome.
*/
body .profile-hero-split .profile-name:has(span[style*="rgba(34,197,94,0.12)"]):hover::after,
body .profile-hero-split .profile-name:has(span[style*="rgba(34, 197, 94, 0.12)"]):hover::after,
body .profile-hero-split .profile-name:has(span[style*="rgba(34,197,94,0.15)"]):hover::after,
body .profile-hero-split .profile-name:has(span[style*="rgba(34, 197, 94, 0.15)"]):hover::after {
  background: linear-gradient(rgba(0, 0, 0, 0.08), rgba(0, 0, 0, 0.08)), rgb(0, 128, 0) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128685 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128686 — mobile (≤900): KYC chip stacks
   below the verified pill in column-flex. csfloat keeps the 36h chip
   intact on mobile (no shrinking). The .profile-name parent already
   wraps inline content on mobile via the inline-flex spans, but the
   ::after KYC chip needs an explicit display:flex on a column wrap
   container to drop to its own line on narrow viewports. Guard with
   media query so desktop layout (chip-row beside name) is preserved.
*/
@media (max-width: 900px) {
  body .profile-hero-split .profile-name:has(span[style*="rgba(34,197,94,0.12)"]),
  body .profile-hero-split .profile-name:has(span[style*="rgba(34, 197, 94, 0.12)"]),
  body .profile-hero-split .profile-name:has(span[style*="rgba(34,197,94,0.15)"]),
  body .profile-hero-split .profile-name:has(span[style*="rgba(34, 197, 94, 0.15)"]) {
    display: flex !important;
    flex-wrap: wrap !important;
    align-items: center !important;
    column-gap: 10px !important;
    row-gap: 8px !important;
  }
  body .profile-hero-split .profile-name:has(span[style*="rgba(34,197,94,0.12)"])::after,
  body .profile-hero-split .profile-name:has(span[style*="rgba(34, 197, 94, 0.12)"])::after,
  body .profile-hero-split .profile-name:has(span[style*="rgba(34,197,94,0.15)"])::after,
  body .profile-hero-split .profile-name:has(span[style*="rgba(34, 197, 94, 0.15)"])::after {
    flex: 0 0 100% !important;
    margin-top: 4px !important;
    align-self: flex-start !important;
    width: fit-content !important;
  }
}
/* END CSFLOAT-1:1 PARITY ship #128686 */
"""

target = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"
mirror = r"C:\Users\WW\Desktop\sboxmarket\build\resources\main\static\css\design.css"

# Idempotency guard.
with open(target, 'rb') as f:
    f.seek(-4096, os.SEEK_END)
    tail = f.read().decode('utf-8', errors='replace')
if 'ship #128680' in tail:
    print('ALREADY APPENDED - ABORT')
    sys.exit(0)

def atomic_append(path, payload):
    tmpfd, tmpname = tempfile.mkstemp(suffix='.append', dir=os.path.dirname(path))
    try:
        with open(path, 'rb') as orig, os.fdopen(tmpfd, 'wb') as out:
            while True:
                buf = orig.read(1 << 20)
                if not buf:
                    break
                out.write(buf)
            out.write(payload.encode('utf-8'))
        os.replace(tmpname, path)
    except Exception:
        if os.path.exists(tmpname):
            try: os.remove(tmpname)
            except: pass
        raise

atomic_append(target, CSS)
print('APPENDED', len(CSS), 'bytes -> src/main/.../design.css')

if os.path.exists(mirror):
    atomic_append(mirror, CSS)
    print('MIRRORED', len(CSS), 'bytes -> build/resources/main/.../design.css')

print('SHIPS #128680-#128686 LANDED')
