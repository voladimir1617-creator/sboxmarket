/loop /grind

BOSS QA — CYCLE 2. The boss verified your work via headless Chrome DOM dump + fresh screenshots in `_qa_boss/cycle_1/`. NICE WORK on these — VERIFIED CLOSED:

- ✅ G2 — LIVE badge separated from SkinBox wordmark
- ✅ I2 — Item price block now reads "Floor Price / Steam Price / 30D Change / Supply" (clean)
- ✅ I4 — "11,652" is now labeled "Supply" (no more mystery number)
- ✅ I5 — CS-GO tabs (`4S / RAR / BOX / S/T / NUM PRINT`) gone from /item/1 DOM
- ✅ M1 partial — "RESERVATION OF FREE STOCKS" garbled label is gone
- ✅ G1 partial — cookie banner gone from re-screenshot of /home and /item/1 BUT… (read on)

Now the still-open list, with NEW evidence and corrections:

═══════════════════════════════════════════════════
P0 — STILL OPEN, VERIFIED VIA DOM
═══════════════════════════════════════════════════

[H1/I1 — FLOAT BAR — REOPEN] You searched for `float-bar` / `wear-bar` / `condition-bar` (hyphenated). The actual class in the DOM is `modal-preview-floatbar` (one word, no hyphen). Run:
```
grep -rn "modal-preview-floatbar\|floatbar\|preview-floatbar-thumb" src/main/resources/static/
```
Kill the JSX node + the `.modal-preview-floatbar` + `.modal-preview-floatbar-thumb` CSS. After: hit `/item/1` headless and verify `grep -c floatbar` returns 0 in the dumped DOM.

[G1 — COOKIE BANNER — REOPEN PARTIAL] Banner is gone on /home and /item/1, but DOM-dump confirms the same `<div role="region" aria-label="Cookie consent" style="position: fixed; left: 16px; bottom: 16px; ...">` STILL renders on: /cart, /db, /faq, /help, /item/missing, /loadout/1, /loadout/missing, /market, /profile/personal, plus likely the rest. So the dismissal/persistence is NOT global. Either:
  (a) localStorage write isn't happening on Accept/Reject click, OR
  (b) the gate condition is per-route instead of app-level.
Fix it once at the App.js / layout level — read localStorage on mount, hide if `cookies-dismissed=1` is set. Write `cookies-dismissed=1` on either button. Then re-shoot ALL 17 routes — cookie banner must be gone everywhere, not just home + item.

[B1 — /loadout/1 STILL "LOADOUT NOT FOUND"] DOM still has `<h2>Loadout not found</h2>`. Check the LoadoutController return for id=1 + LoadoutRepository data. Either there are 0 loadouts in seed OR the only seeded loadouts are private (`public=false`). Fix in `SeedService.groovy`: add 3-5 PUBLIC loadouts with ids 1-5. Restart, hit `/loadout/1` — must render real loadout body (item slots, owner, like count). Verify by re-screenshot.

[S1 — STALL EMPTY VOID — REOPEN] `/stall/1` still has 70% black void below the single Cardboard King card. AFTER screenshot at `_qa_boss/cycle_1/26-stall-real.png` confirms unchanged from baseline. Add: "More from this seller" rail (even if it's "No other listings — browse marketplace"), Recent sales TABLE (you have the (?) placeholder right next to it — replace), Reviews preview (3 most recent or "No reviews yet"), trust badges row. Page must NOT have an empty 60%+ void.

[S2 — DECORATION ROW BROKEN TEXT — REOPEN] Row still reads: `Last 24h · joined …yr ago · active recently · last seen 22d ago · last sold $25k 100 ago · last sold $5k 1d ago` — concatenated mush. Restructure into a labeled stat-card grid (4 boxes, label on top, value below). Labels: "Joined" / "Last seen" / "Lifetime sales" / "Last sale".

[S3 — `(?)` PLACEHOLDER STILL SHIPPED] `/stall/1` shows literal "(?)" next to "Recent sales". Replace with the real count, or "—" if unknown. Never literal "(?)".

═══════════════════════════════════════════════════
P0 — NEW FINDINGS (you missed these)
═══════════════════════════════════════════════════

[N1 — FOOTER "Discord — coming soon" + "X / Twitter — coming soon"] DOM shows two `<button class="site-footer-social" disabled>` with `title="Discord — coming soon"` and `title="X / Twitter — coming soon"`. A $10M / better-than-Amazon site does NOT show "coming soon" social icons. Either:
  (a) wire them up with real URLs (Discord invite already exists on the F.E.A.R. project — `https://discord.gg/SPAwYX7bmP` if relevant; otherwise pick a placeholder URL), OR
  (b) hide them entirely until they're real.
File: `src/main/resources/static/js/footer.js` or wherever `site-footer-social` is rendered.

[N2 — H1/H2/H3 ON HOME] Home rail tabs (Top Deals / Newest Items / Unique Items) are STILL tiny generic underlines in `_qa_boss/cycle_1/01-home.png`. Implement the segmented-control style I described in BOSS_PROMPT (cycle 1 H3): pill background, 8px radius, active = solid white-on-charcoal, hover = bg lift. This is the most visible UI on home and it still looks $99 freelancer.

[N3 — HOME HERO PREVIEW STACK STILL FADED] H2 unfixed. The 3 stack-back cards behind Cardboard King are still invisible smudges in the new screenshot. Either ship the parallax (5°/-3°/-7° rotation, 80%/60%/40% opacity, real perspective + drop-shadow) or remove them.

[N4 — DISCOUNT CHIPS ON EVERY CARD] H4 unfixed. Every card on /home rail and /market grid shows the same green % chip (8.17, 8.43, 7.88, 7.45, 7.42, …). Not credible. Gate the chip strictly on `discount >= 5%` vs the 30-day median for that item — and hide otherwise. If you can't compute median yet, show no chip rather than seeded-fake ones.

[N5 — DATABASE TABLE D1-D5 ALL OPEN] /db DOM grew but visual screenshot shows row spacing still tight, "Generic (Lowest supply)" sort label still placeholder, "OFF-Market" red badge still appears with positive prices. Address D1, D2, D3, D4, D5 from BOSS_PROMPT.md — they were not even attempted.

[N6 — TYPOGRAPHY: HEADER "Database · 80 indexed" REPEATED] /db shows the page title "Database · 80 indexed" twice in different sizes inside a header chrome lockup. Pick one. The smaller "addbar Database 80" pill chip still looks like a debug element.

═══════════════════════════════════════════════════
P1 — DEEP QA: BUTTONS / LINKS / CONSOLE
═══════════════════════════════════════════════════

[Q1 — CLICK SCAN] Run `node .claude/watchdog/qa-click-scan.js` (existing script) against http://localhost:8082 to enumerate clickables and report any with empty handlers, missing aria-labels, or broken hrefs. Save output to `_qa_boss/cycle_2/click-scan.json`. Address any flagged items in the same lap.

[Q2 — CONSOLE / NETWORK ERRORS] Drive headless chrome on each public route (`/`, `/market`, `/db`, `/help`, `/faq`, `/cart`, `/watchlist`, `/wallet`, `/sell`, `/settings`, `/profile/personal`, `/item/1`, `/stall/1`, `/loadout/1`) and capture every console error + every 4xx/5xx network response. Log to `_qa_boss/cycle_2/console-errors.txt`. Acceptance: ZERO console errors and ZERO 4xx/5xx network responses on those 14 public routes.

[Q3 — MOBILE 390×852] Re-shoot all routes at mobile viewport (`--window-size=390,852` + iPhone UA) and look at: cookie banner overlap, footer column collapse, market grid (must be 2-col, never 1-col per memory), nav burger menu functionality, item-detail price block readability. Save to `_qa_boss/cycle_2/m_*.png`. Fix any horizontal overflow, single-col fallbacks, or unreadable price stacks.

[Q4 — KEYBOARD] Tab through /market — every interactive control must show a visible focus ring + reach in logical order. If filter chips lose focus to the next chip without a visible ring, that's a fail (WCAG 2.4.7). Address with `:focus-visible` rules.

═══════════════════════════════════════════════════
EXECUTION RULES (UPDATED)
═══════════════════════════════════════════════════

1. Work top-to-bottom: H1/I1 (float bar) → G1 global cookie persistence → B1 (loadout seed) → S1 → S2 → S3 → N1-N6 → Q1-Q4.
2. After EVERY fix or batch of related fixes: rebuild + restart via `bash deploy/run-local.sh`, wait for /api/health UP.
3. Re-screenshot the affected route to `_qa_boss/cycle_2/<id>.png`.
4. DOM-verify: `chrome --headless --dump-dom http://localhost:8082/<route> | grep -c <pattern>` — should be 0 for the killed pattern (e.g. `floatbar`).
5. Commit per logical batch with subject `Boss QA cycle 2: H1+I1 kill modal-preview-floatbar` etc.
6. NEVER claim done without after-screenshot AND DOM verification.
7. NEVER ScheduleWakeup. The boss is asleep — keep grinding solo. Watchdog will re-poke if you idle.
8. When this list is empty, walk all 17 routes desktop + 14 mobile yourself, find the next 10 issues, and start cycle 3 — DO NOT WAIT FOR THE BOSS.

GO.
