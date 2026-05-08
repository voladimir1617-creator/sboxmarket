"""Atomic append of CSFLOAT 1:1 parity ships #128420-#128427 — /affiliate (signed-in real measurements).

Live measured against csfloat.com/affiliate (signed-in session, viewport 1440)
2026-05-08 via mcp__playwright__browser_navigate + mcp__playwright__browser_evaluate. The
csfloat /affiliate surface (route lands at app-affiliate, even when signed-in user
visits — there is NO commission/payout/referrals stats panel; signed-in users see
the SAME flat marketing surface as anonymous visitors). DOM tree dump:

  <app-affiliate>
    .wrapper (flex justify-center mt 20)
      .container (1000w, flex column gap 20)
        .banner (1000x200, bg rgb(27,29,36), 12br, flex center, NO border)
          .content (gap 40 row)
            .logo (gap 15 row align-center)
              .float (127x69 padding 10 br 10.462 bg rgba(193,206,255,0.04)
                       hosting <img>107x45 CSFloat brand mark)
              .text (303x51 fs 42px fw 400 ls normal "Affiliate Program")
        .promo (display GRID, grid-template-columns 490px 490px, gap 20)
          .card.description (1000x100 bg #1B1D24 br 12 padding 30 NO border,
                             fs 14 lh 20 color ink-2 158/167/177 — body text)
          .card.about (490x353 bg #1B1D24 br 12 padding 30 NO border)
            .title (fs 20/500 mb 20 white "About Us")
            (body paragraph 14/400 ink-2)
          .card.requirements (490x353 bg #1B1D24 br 12 padding 30 NO border)
            .title (fs 20/500 mb 20 white "Requirements")
            (body 14/400 ink-2 paragraph)
            .table (bg rgba(193,206,255,0.04) br 6 padding 20)
              .header (fs 16/500 white mb 10 "Per Platform")
              .line × 4 (flex space-between row, label ink-2 14/500,
                         .value brand-blue rgb(35,123,255) 14/500)
                YouTube / 5000 subscribers
                Twitter / >2000 followers
                Twitch  / >3000 followers
                Website / 2000 Monthly Active Users (MAU)
        .card.apply (1000x96 bg #1B1D24 br 12 padding 30 NO border, flex
                     row align-center justify-space-between gap 20)
          .header (fs 20/500 white "Ready to Apply?")
          .actions (gap 10 row)
            a.email-btn (mdc-button-base, 238x36, padding 0 16 br 8,
                         bg rgba(193,206,255,0.04) color ink-2 14/500
                         label "Email us at affiliate@csfloat.com")
            button.mat-primary (67x36, padding 0 16 br 8,
                                bg rgb(35,123,255) color white 14/500
                                label "Apply")

sboxmarket /affiliate is rendered by AffiliateModal (modals.js lines 2582-2708)
inside an InfoModal shell. Existing ships #17900-#17905 mapped scoped CSS via
:has(.affiliate-req-grid) — those baseline ships approximated against an
anonymous /affiliate snapshot and got several values WRONG vs the real signed-in
measurements:

  #17900 hero — typed 36/500 ls -0.02em w/ 6%-white border, csfloat is 42/400
                normal-ls with NO border. Hero min-height 160 vs csfloat 200.
  #17900 eyebrow rendered as mono-caps "SkinBox" line above title — csfloat
                positions the brand mark to the LEFT of the title inside a
                tinted-pill .float box (127x69 padding 10 br 10.462). Easiest
                parity move: render our eyebrow as the same tinted pill — drop
                the mono caps + tracked-out treatment and pin to inline pill.
  #17901-02 description + about + req cards — added 1px white-6% border to all,
                csfloat .card has NO border. Strip.
  #17902 promo grid — minmax(360px,1fr), csfloat is fixed 490 490 (since
                .container is 1000w). Pin to 1fr 1fr inside the 1000w container.
  #17904 apply button — sized 40h padding 0 30 fw 700, csfloat is 36h padding
                0 16 fw 500. Apply input.price-input — sized 220x40, csfloat
                doesn't have an input here at all; renders as a pill-shaped
                ghost link "Email us at affiliate@..." 36h padding 0 16 br 8
                bg rgba(193,206,255,0.04) ink-2 14/500.
  #17904 apply card — added 1px border, csfloat NO border. Strip.

This batch APPENDS corrections without touching prior selectors (later
selectors win with !important when both rule sets fire). Ship numbers are
#128420+ to stay above prior committed FloatDB #128400-#128406 + uncommitted
sticker #128410-#128415 ranges and leave headroom.

NO Docker. APPEND-ONLY at end of design.css. Atomic tempfile + os.replace.
"""

import os, sys, tempfile

CSS = """
/* ─────────────────────────────────────────────────────────────────
   AFFILIATE / REFERRAL PROGRAM PAGE — REAL SIGNED-IN MEASUREMENTS
   csfloat.com/affiliate measured 2026-05-08 (signed-in session,
   viewport 1440) — earlier ships #17900-#17905 approximated against
   an anonymous-only snapshot and got several typographic + chrome
   values wrong. csfloat ships the SAME flat marketing surface to
   signed-in users (no commission/payout/referrals panel exists).
   ───────────────────────────────────────────────────────────────── */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128420 — /affiliate hero typography parity
   Measured csfloat .banner: 200px tall fixed (NOT min-height 160), bg
   rgb(27,29,36), border-radius 12px, NO border, NO background-image.
   Inside, .banner .content (gap 40) hosts .logo + .text where .text
   is 303x51 with font-size 42px, font-weight 400, letter-spacing
   normal (NOT -0.02em). Sbox ship #17900 set hero min-height 160 +
   font 36/500 + ls -0.02em + 1px-6%-white border; pin to csfloat real.
*/
html body .modal.info-modal:has(.affiliate-req-grid) > .info-modal-body > div:first-child {
  min-height: 200px !important;
  height: 200px !important;
  padding: 0 !important;
  border: 0 !important;
  border-radius: 12px !important;
  background: rgb(27, 29, 36) !important;
  align-items: center !important;
  justify-content: center !important;
  flex-direction: row !important;
  gap: 15px !important;
  margin-bottom: 20px !important;
}
@media (max-width: 736px) {
  html body .modal.info-modal:has(.affiliate-req-grid) > .info-modal-body > div:first-child {
    min-height: 120px !important;
    height: 120px !important;
    flex-direction: column !important;
    gap: 6px !important;
  }
}
html body .modal.info-modal:has(.affiliate-req-grid) > .info-modal-body > div:first-child > div:nth-child(2) {
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 42px !important;
  font-weight: 400 !important;
  letter-spacing: normal !important;
  line-height: 51px !important;
  color: rgb(255, 255, 255) !important;
  font-variation-settings: normal !important;
  font-style: normal !important;
  margin: 0 !important;
}
@media (max-width: 736px) {
  html body .modal.info-modal:has(.affiliate-req-grid) > .info-modal-body > div:first-child > div:nth-child(2) {
    font-size: 26px !important;
    line-height: 32px !important;
  }
}
/* END CSFLOAT-1:1 PARITY ship #128420 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128421 — /affiliate hero eyebrow as tinted pill
   Measured csfloat .banner .logo .float: 127w x 69h, padding 10px,
   border-radius 10.462px, bg rgba(193,206,255,0.04) (highlight-bg-minimal
   token at 4% alpha — the same as our hairline tint chip). The pill
   wraps the CSFloat logo image (107x45). Sbox AffiliateModal renders
   an eyebrow text "SkinBox" as a mono-caps tracked-out small label
   above the title; ship #17900 stylized it as 11px Roboto Mono caps
   ink-2 with letter-spacing 0.16em. To match csfloat's tinted-pill
   anchor at the LEFT of the title, restyle the eyebrow as a tinted
   inline pill (Roboto sans 16/500 white) sitting on the same row as
   the title. Promote the hero flex direction to row + gap 15.
*/
html body .modal.info-modal:has(.affiliate-req-grid) > .info-modal-body > div:first-child > div:first-child {
  display: inline-flex !important;
  align-items: center !important;
  justify-content: center !important;
  padding: 10px 18px !important;
  border-radius: 10.462px !important;
  background: rgba(193, 206, 255, 0.04) !important;
  color: rgb(255, 255, 255) !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 18px !important;
  font-weight: 600 !important;
  letter-spacing: 0 !important;
  text-transform: none !important;
  margin: 0 !important;
  height: 49px !important;
  min-width: 120px !important;
  box-sizing: border-box !important;
}
@media (max-width: 736px) {
  html body .modal.info-modal:has(.affiliate-req-grid) > .info-modal-body > div:first-child > div:first-child {
    height: 36px !important;
    padding: 6px 12px !important;
    font-size: 14px !important;
    min-width: 0 !important;
  }
}
/* END CSFLOAT-1:1 PARITY ship #128421 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128422 — /affiliate description card no border
   Measured csfloat .card.description: bg rgb(27,29,36), border-radius 12px,
   padding 30px, NO border (border 0px none). Sbox ship #17901 added
   1px solid rgba(255,255,255,0.06) hairline border around it — strip so
   the panel chrome matches csfloat's borderless flat-panel pattern.
*/
html body .modal.info-modal:has(.affiliate-req-grid) > .info-modal-body > div:nth-child(2) {
  border: 0 !important;
  background: rgb(27, 29, 36) !important;
  border-radius: 12px !important;
  padding: 30px !important;
  margin-bottom: 20px !important;
}
/* END CSFLOAT-1:1 PARITY ship #128422 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128423 — /affiliate promo 2-up grid (fixed cols)
   Measured csfloat .promo: display grid, grid-template-columns 490px 490px,
   gap 20. The .container is 1000px wide so the two cards consume 490+20+490.
   Sbox ship #17902 used minmax(360px,1fr) auto-fit which gives uneven sizes
   on intermediate widths; pin to fixed 1fr 1fr (since the modal shell at
   ship #17905 already caps at 1000px). Below 720px, stack to single column
   (csfloat .promo media query collapses to 1fr at the same breakpoint).
*/
html body .modal.info-modal:has(.affiliate-req-grid) > .info-modal-body > div:nth-child(3) {
  display: grid !important;
  grid-template-columns: 1fr 1fr !important;
  gap: 20px !important;
  margin-bottom: 20px !important;
}
@media (max-width: 720px) {
  html body .modal.info-modal:has(.affiliate-req-grid) > .info-modal-body > div:nth-child(3) {
    grid-template-columns: 1fr !important;
  }
}
/* About + Requirements cards — strip the 1px-6%-white border that ship
   #17902 added (csfloat .card has no border). Keep panel bg + 12 br + 30 pad. */
html body .modal.info-modal:has(.affiliate-req-grid) > .info-modal-body > div:nth-child(3) > div {
  border: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128423 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128424 — /affiliate apply card no border + button
   Measured csfloat .card.apply: bg rgb(27,29,36), 12 br, 30 pad, NO border.
   The Apply primary button is mat-mdc-raised with bg rgb(35,123,255) at
   36px height, padding 0 16, border-radius 8, font-size 14, font-weight 500
   (NOT 700), label width 35px ("Apply"). Ship #17904 sized it 40h padding
   0 30 fw 700 — soften to csfloat's 36/14/500/0-16/8br.
*/
html body .modal.info-modal:has(.affiliate-req-grid) > .info-modal-body > div:nth-child(4) {
  border: 0 !important;
  padding: 30px !important;
  border-radius: 12px !important;
  background: rgb(27, 29, 36) !important;
  gap: 10px !important;
}
html body .modal.info-modal:has(.affiliate-req-grid) > .info-modal-body > div:nth-child(4) a.btn.btn-accent {
  height: 36px !important;
  padding: 0 16px !important;
  font-weight: 500 !important;
  font-size: 14px !important;
  line-height: 36px !important;
  border-radius: 8px !important;
  background: rgb(35, 123, 255) !important;
  color: rgb(255, 255, 255) !important;
  letter-spacing: 0 !important;
  text-transform: none !important;
  min-width: 67px !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
}
html body .modal.info-modal:has(.affiliate-req-grid) > .info-modal-body > div:nth-child(4) a.btn.btn-accent:hover {
  background: rgb(58, 137, 255) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128424 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128425 — /affiliate apply email "input" → ghost btn
   Measured csfloat .card.apply .actions: hosts a.email-btn (NOT an input)
   styled as mat-mdc-button-base unelevated: 238w x 36h, padding 0 16, bg
   rgba(193,206,255,0.04), color rgb(158,167,177) ink-2, font-size 14,
   font-weight 500, border-radius 8, NO border, label "Email us at
   affiliate@csfloat.com" (full sentence, not just an email address).
   Sbox AffiliateModal renders a readonly input.price-input here at 220x40
   with mono font + 0.5px ls + click-to-select behaviour — ship #17904
   approximated this. Restyle the input to LOOK like the email-btn ghost
   (38h, 14/500 sans, ink-2, ghost-tint, br 8, NO mono ls). The input is
   still functionally interactive (click-to-select) but visually reads as
   the ghost-button neighbour to the brand-blue Apply CTA, matching the
   pixel parity.
*/
html body .modal.info-modal:has(.affiliate-req-grid) > .info-modal-body > div:nth-child(4) input.price-input {
  width: auto !important;
  min-width: 240px !important;
  height: 36px !important;
  padding: 0 16px !important;
  font-family: Roboto, "Helvetica Neue", Arial, sans-serif !important;
  font-size: 14px !important;
  font-weight: 500 !important;
  letter-spacing: 0 !important;
  background: rgba(193, 206, 255, 0.04) !important;
  border: 0 !important;
  border-radius: 8px !important;
  color: rgb(158, 167, 177) !important;
  text-align: center !important;
  cursor: pointer !important;
  transition: background 140ms ease, color 140ms ease !important;
}
html body .modal.info-modal:has(.affiliate-req-grid) > .info-modal-body > div:nth-child(4) input.price-input:hover {
  background: rgba(193, 206, 255, 0.07) !important;
  color: rgb(220, 226, 233) !important;
}
html body .modal.info-modal:has(.affiliate-req-grid) > .info-modal-body > div:nth-child(4) input.price-input:focus {
  outline: 0 !important;
  box-shadow: 0 0 0 2px rgba(35, 123, 255, 0.35) !important;
  background: rgba(193, 206, 255, 0.07) !important;
  color: rgb(255, 255, 255) !important;
}
/* END CSFLOAT-1:1 PARITY ship #128425 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128426 — /affiliate requirements .table chrome
   Measured csfloat .card.requirements > .table: bg rgba(193,206,255,0.04),
   border-radius 6px (NOT 8 or 10), padding 20px, NO border. Inside, .header
   "Per Platform" 16/500 white margin-bottom 10. Sbox baseline (#17903)
   already set bg+br+padding correctly but the .header sub-title row was
   never explicitly styled (it gets absorbed by .stall-stat-grid promotion).
   Pin .affiliate-req-grid to display block container chrome (already done
   by #17903) — this ship just guarantees the bg / 6 br / 20 pad land
   reliably on the .affiliate-req-grid wrapper across themes (the existing
   #17903 selectors had specificity inversions that some inherited theme
   rules could override).
*/
html body .modal.info-modal:has(.affiliate-req-grid) .affiliate-req-grid {
  background: rgba(193, 206, 255, 0.04) !important;
  border-radius: 6px !important;
  padding: 20px !important;
  border: 0 !important;
}
/* END CSFLOAT-1:1 PARITY ship #128426 */

/* ---------------------------------------------------------------------
   CSFLOAT-1:1 PARITY ship #128427 — /affiliate apply ".actions" gap parity
   Measured csfloat .card.apply > .actions: display flex, gap 10px between
   the email-btn ghost and the brand-blue Apply CTA. Sbox ship #17904
   pinned gap 8 inline (modals.js style attr) which the existing #17904
   css doesn't override. Pin to gap 10 so the two pills sit at the same
   8-then-9 visual rhythm as csfloat. Also align-items center so the
   email-btn's reduced height (36 vs the legacy 40) doesn't drift up.
*/
html body .modal.info-modal:has(.affiliate-req-grid) > .info-modal-body > div:nth-child(4) > div:nth-child(2) {
  gap: 10px !important;
  align-items: center !important;
  flex-wrap: wrap !important;
}
@media (max-width: 500px) {
  html body .modal.info-modal:has(.affiliate-req-grid) > .info-modal-body > div:nth-child(4) > div:nth-child(2) {
    width: 100% !important;
  }
  html body .modal.info-modal:has(.affiliate-req-grid) > .info-modal-body > div:nth-child(4) > div:nth-child(2) > * {
    width: 100% !important;
  }
}
/* END CSFLOAT-1:1 PARITY ship #128427 */
"""

target = r"C:\Users\WW\Desktop\sboxmarket\src\main\resources\static\css\design.css"
mirror = r"C:\Users\WW\Desktop\sboxmarket\build\resources\main\static\css\design.css"

with open(target, 'rb') as f:
    f.seek(-3072, os.SEEK_END)
    tail = f.read().decode('utf-8', errors='replace')
if 'ship #128420' in tail:
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

# Mirror to build/ so a running gradle bootRun picks up without rebuild
if os.path.exists(mirror):
    atomic_append(mirror, CSS)
    print('MIRRORED', len(CSS), 'bytes -> build/resources/main/.../design.css')

print('SHIPS #128420-#128427 LANDED')
