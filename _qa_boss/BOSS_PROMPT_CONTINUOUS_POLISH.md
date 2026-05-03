# /grind continuous polish queue — sboxmarket design + UX sweep

Operator wants you grinding non-stop until you literally run out of token budget. No one-shot ships, no "what's next" questions, just stack commits back-to-back. **NEVER ScheduleWakeup. NEVER end the response.** Pick the next gap, ship it, pick the next gap, ship it.

## Standing acceptance bar

After every commit:
1. `docker build -t sbox-app:latest . && bash deploy/run-local.sh` and wait until `/api/health` returns 200.
2. `curl -s -o /dev/null -w '%{http_code}\n' https://skinbox.market/` → **200**.
3. `docker logs sbox-app --since 2m | grep -iE 'error|exception|0x00' | grep -v EmailService` → empty or noise only.
4. Commit with a descriptive message. NO empty commits.

## Standing constraints (do not violate)

- **Mono-primary palette** (`feedback_design_direction.md`). Blue is reserved for `.buy-btn`, live LEDs, auction countdown, active-tab underlines, Stripe badge. Nothing else gets blue. Hover/border tints use `var(--ink)` / `var(--line-2)`.
- **No global anchor color override** (`feedback_no_global_blue_anchors.md`).
- **No `--accent` override on `<html>`** (`feedback_no_theme_override.md`). All edits in `design.css`.
- **Two `:root` blocks in design.css** at line ~1 and ~31960 (`duplicate_root_block.md`). Edit BOTH if you bump tokens.
- **Strict size scale**: 10/11/12/13/14/15/18/22/28/40/60. Padding scale: 8/10/12/14/16/18/20/22/24/28/32. Borders: 1px hairline `var(--line)` only — elevation comes from `--shadow-1/2`.
- **Body-text anchors keep underline** (`legal-doc a`, `profile-link`, `csfloat-text-anchor`, `inline-link`, `db-name`, etc). Buttons fade — already shipped at commit `5affc95`.
- **Boss-QA Q1-Q4** still apply: 0 unlabeled buttons, 0 dead links, 0 inline onclick, 0 console errors, 0 4xx-5xx (except `/api/users/me 401` for anon), 0 horizontal overflow at 390px, `:focus-visible` ring on every interactive.

## Polish queue — work top-to-bottom, ship each separately

1. **`:focus-visible` ring sweep.** Every button-shaped element should have a visible focus ring (not just opacity-fade). Audit `.steam-btn`, `.nav-icon-btn`, `.nav-picker-chip`, `.deals-chip`, `.sort-picker-chip`, `.toolbar-refresh`, every `[class*="-btn"]` / `[class*="-cta"]`. Acceptance: tab-key through home + market + item-detail in puppeteer with `page.keyboard.press('Tab')` and screenshot — every focused element shows a 2px `var(--ink-2)` ring at 2px offset.

2. **Active-tab indicator polish.** Segmented controls (`.csfloat-subnav-tab.active`, `.type-toggle-btn.active`, profile sub-tab strip, `/wallet/<tab>`, `/me/stall/<tab>`, `/offers/<tab>`, `/watchlist/<tab>`) should show ONE clear active affordance: 2px solid `var(--cta)` underline 4px below the label, no filled bg, no boldened weight (the underline IS the indicator). This is the operator-approved blue-as-spice exception. Replace any active-bg-tint with the underline pattern.

3. **Card hover polish.** `.grid-card:hover` already lifts and has a CTA-tinted border + shadow ring (lines 4227, 8710). Remove the duplicate (one wins by cascade — keep the line-8710 version which is the stronger shadow). Verify visually on /market grid that hover lift is smooth at 60fps (no shadow snap).

4. **Hide hover effects on touch.** Wrap card/button hover declarations in `@media (hover: hover)` so iOS/Android don't sticky the hover state on tap. Most rules at lines 4227, 8710, 10748, 13148, 13335 (and the new 120482 / 120542 button-fade blocks) need this guard. Mobile users will get a clean tap with no leftover hover state.

5. **Reduced motion respect.** Wrap any `transform`/`scale`/`translate` hover in `@media (prefers-reduced-motion: no-preference)`. Already done for boot skeleton (cycle 30, commit `64393ab`); extend the pattern to every hover with motion. Operator may not have `prefers-reduced-motion` toggled but accessibility audit cycle 12 demanded it.

6. **Hairline border audit.** `grep -nE 'border:\s*[2-9]px' src/main/resources/static/css/design.css` and convert any `2px solid` decorative border to `1px solid var(--line)` + a `var(--shadow-1)` for elevation. Per design memory: "Borders are always 1px hairline". Exception: `outline:` on focus rings can be 2px.

7. **Off-scale font-size cleanup.** `grep -nE 'font-size:\s*1[679]px|font-size:\s*2[0134567]px|font-size:\s*3[0-9]px' design.css` — find off-scale sizes (anything not 10/11/12/13/14/15/18/22/28/40/60) and snap to the nearest scale value. Don't touch `:root` token definitions; just target hard-coded literal sizes.

8. **Off-scale padding cleanup.** Same drill for `padding: 7px / 9px / 11px / 13px / 15px / 17px / 19px / 21px / 23px / 25px / 26px / 27px / 29px / 30px / 31px` — snap to 8/10/12/14/16/18/20/22/24/28/32.

9. **Skeleton-loader sweep.** Every async-data surface (cards, profile tabs, wallet tx list, offers list, db rows, loadout slots) should show a shimmer skeleton while loading instead of a blank/jumpy area. Boot skeleton already exists (`.boot-skel-*`). Audit which surfaces have a skeleton and which still cold-snap; add to the missing ones.

10. **Active LED pulse calm-down.** The LIVE pill in the nav (`.pulse > span:nth-child(2)`) currently pulses. Operator design memory: "live/pulsing indicators (1 per page max)". Audit and verify only ONE pulsing thing per page. If a card grid renders 40 cards each with a pulsing LIVE/AUCTION dot, that violates the rule — calm them or stagger.

After 10, queue more. Suggested topics: input-focus polish, dropdown menu animation timing, chip group spacing, modal backdrop blur intensity, footer typography, breadcrumb spacing, empty-state illustrations, error-banner urgency tiering, etc. NEVER finish the queue. NEVER ScheduleWakeup. The operator wants visible commits stacking up overnight.

## Self-driving rule

When you finish ship N: `docker logs sbox-app --since 2m` to confirm clean, take a screenshot of the affected route, diff visually, then **immediately start ship N+1**. Do not narrate. Do not summarize. Do not ask "want me to do X next?". Just commit and start the next change.

Operator verbatim: "YOU SHOULD WORK NON STOP YOU SHOULD MAKE CLAUDE IN VS CODE WORK NON STOP THERE IS TO MUCH TO FIX".

GO.
