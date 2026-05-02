/loop /grind

BOSS QA — CYCLE 3. Excellent progress this lap. Verified-closed list (DOM + screenshot dual-checked):

- ✅ G1 — cookie banner removed across all 17 routes (DOM grep returns 0 banners site-wide)
- ✅ H1/I1 — `modal-preview-floatbar` deleted (DOM grep returns 0 on /item/1)
- ✅ M1 — market stat strip is now clean labeled "ACTIVE LISTINGS · LIVE AUCTIONS · LOWEST PRICE"
- ✅ S2 — stall decoration row now properly labeled ("1 last 30d · joined 16d ago · Active recently · Last listed 21h ago · Last sold 9d ago")
- ✅ F1 — /faq support-ticket banner is now slim (single-line)
- ✅ G2 — LIVE badge separated from logo wordmark
- ✅ G9 — wallet copy reads "Or select a suggested amount"
- ✅ D2 — sort dropdown reads "Sort: Lowest supply" (no more "Generic" prefix)
- ✅ Mobile cookie pill 🍪 collapses to bottom-left bug-bunny pill
- ✅ I2 / I4 — item price block labeled, "Supply" annotated

Now the open list — sorted by impact. Boss expects the entire list closed before next sleep.

═══════════════════════════════════════════════════
P0 — BLOCKERS
═══════════════════════════════════════════════════

[B1 — /loadout/1 STILL "Loadout not found" — NOT FIXED]
Headless DOM dump still shows: `<h2>Loadout not found</h2>`. Either you didn't add the seed data or the route handler is rejecting public lookup. Steps:
  1. `cd /c/Users/WW/Desktop/sboxmarket && grep -n "Loadout" src/main/groovy/com/sboxmarket/service/SeedService.groovy` — check if any loadouts are seeded.
  2. If not, add 3 PUBLIC sample loadouts to the seed (with known item ids 1-50, owner = first seeded user, public=true).
  3. `bash deploy/run-local.sh` → wait /api/health UP.
  4. `curl http://localhost:8082/api/loadouts/1` — must return 200 + body. If still 404, dig into LoadoutController access control.
  5. Then re-shoot `/loadout/1` headless — must show populated loadout with items, owner, like count.

═══════════════════════════════════════════════════
P0 — VISUAL POLISH (these are still ugly)
═══════════════════════════════════════════════════

[H2 — Home hero stack-back cards faded into invisibility — NOT FIXED]
Look at `_qa_boss/cycle_1/01-home.png`. Behind the Cardboard King hero card there are ghost-faded card outlines you can barely see. Either:
  (a) Make them VISIBLE parallax cards: 5° / -3° / -7° rotation, 80% / 60% / 40% opacity, real perspective + 12-16px drop-shadow.
  OR
  (b) Remove them entirely — current state looks like a render bug.

[H3 — Home rail tabs (Top Deals / Newest Items / Unique Items) still tiny generic underlines — NOT FIXED]
This is the most-clicked control on home and it looks like a $99 Bootstrap freelancer page. Implement a proper segmented control:
  - Container: `display:inline-flex; padding:4px; background:var(--surface); border-radius:10px; border:1px solid var(--line)`
  - Tab: `padding:8px 14px; border-radius:8px; font-weight:600; color:var(--ink-3)`
  - Active: `background:var(--ink); color:var(--bg); box-shadow:0 1px 0 rgba(0,0,0,0.04)`
  - Hover (inactive): `background:var(--surface-hi); color:var(--ink-1)`

[H4 — Every card shows the same green % discount chip — NOT FIXED]
On `/`, `/market`, `/cart` (Trending) every card has its own green percentage chip — makes them all look discounted, killing trust. Compute: only render the chip when `currentPrice / 30dMedian < 0.95` (5%+ below median). If no median yet, render NO chip. Cap-bottom rule: never claim a discount > 70% (looks fake).

[N1 — Footer "Discord — coming soon" + "X / Twitter — coming soon" — NOT FIXED]
DOM still shows `<button class="site-footer-social" disabled>` with title "Discord — coming soon" and "X / Twitter — coming soon" on EVERY page (footer is global, so it appears on 11+ routes per my sweep). A $10M / better-than-Amazon brand does not ship "coming soon" buttons. Either wire them up to real URLs OR delete them from the footer entirely. Pick one and execute.

═══════════════════════════════════════════════════
P0 — STALL PAGE (still half-built)
═══════════════════════════════════════════════════

[S1 — Stall page empty void — NOT FIXED]
`/stall/1` desktop still has 60%+ empty bottom. Add (in order):
  1. **"More from this seller" rail** — query `select * from listings where seller_id=? and status='ACTIVE' limit 12`. If empty, show "No other listings — browse marketplace" inline empty state. Anchor with a section title.
  2. **"Recent sales" table** — last 10 completed trades from this seller (item, price, date). If empty, "No completed sales yet". Replace the literal "(?)" placeholder.
  3. **"Reviews" preview** — top 3 most recent reviews. If 0, "No reviews yet — be the first after your purchase".
  4. **Trust badges row** — "Verified Steam · 1 sold · joined 16d ago · responds in 12h avg" inline above the rails.

[S3 — `(?)` placeholder text on Recent sales — NOT FIXED]
Even with the table empty, never render the literal "(?)" — render "—" or hide.

═══════════════════════════════════════════════════
P0 — DATABASE POLISH (D1, D3, D4, D5 untouched)
═══════════════════════════════════════════════════

[D1 — duplicate "Database 80" pill chip inside search row]
The page header at top already says "Database · 80 indexed". The smaller "addbar Database 80" pill in the search row is debug chrome — kill it.

[D3 — `OFF-Market` red badge on rows with positive market price]
Hard Hat shows $1,449.32 AND "OFF-Market" red tag (look at `_qa_boss/04-database.png`). If `OFF-Market` means "not currently for sale on SkinBox" but Steam-Market price is known, label is misleading. Either rename to "No SkinBox listings" or hide when price > 0. Investigate the data source in `ItemController.groovy` / database aggregation query.

[D4 — table row spacing too cramped]
Bump to `min-height: 64px`, thumbnail 48px square, 12px gap between thumb and title. This makes the page feel premium not Excel.

[D5 — chevron-only column → make whole row clickable]
Remove the standalone chevron column. Wrap the entire `<tr>` in `<a href="/item/{id}">` (or attach onClick navigate). The chevron can stay AS A VISUAL CUE inside the last cell, but the click target is the whole row.

═══════════════════════════════════════════════════
P0 — GATED PAGES (G6, G8 — anon leak)
═══════════════════════════════════════════════════

[G6 — /settings accessible to anonymous users — NOT FIXED]
Mobile screenshot confirms: full settings page renders for anon, including notification toggles for events the anon user can't trigger. Apply the same sign-in gate as `/profile` and `/sell`. If you want anon to access the Display & Currency section only, scope the page to render only that block when `me==null`.

[G8 — /wallet deposit form visible to anonymous users — NOT FIXED]
Mobile screenshot confirms: the entire deposit flow with $25/$50/$100/$250/$500 chips renders to anon. Anon clicking those does what — Stripe with no user? Show the sign-in gate (same component as `/profile`, `/sell`).

═══════════════════════════════════════════════════
P0 — MOBILE-SPECIFIC NEW BUGS
═══════════════════════════════════════════════════

[M1m — /item/1 MOBILE: price block + listings section completely missing]
Mobile shot at `_qa_boss/cycle_2/m04-item.png` shows ONLY: image → breadcrumb → "Out of Stock" + "Sign in to make offer" + Steam button. The entire price block ("$17.45 · Floor Price · Steam Price · 30D Change · Supply") is GONE on mobile. So is the price-history chart + active-listings table + similar-items rail. Catastrophic — that's the actual product info. Investigate the responsive CSS — likely a `@media (max-width: 600px) { display: none }` that's too aggressive, OR a flex/grid order that pushes content outside the viewport.

[M2m — /stall/1 MOBILE: H1 overflows the right edge]
"Chib skinbox.market's Stall" gets cut at "...'s Stall" past the 390px viewport. Either truncate the username with ellipsis after N chars or use `word-break: break-word` + smaller font scaling at <420px.

[M3m — /db MOBILE: table only shows row numbers, item names cut off]
Mobile shot at `_qa_boss/cycle_2/m03-database.png` shows just `#1 / #2 / #3 / #4 (Standard)`. Item name + category + supply + price are all off-screen right. Switch to a stacked card layout for `<= 600px`: each row becomes a card with image left, name + meta stacked right, price bottom-right.

═══════════════════════════════════════════════════
P1 — DEEP QA YOU PROMISED IN CYCLE 2
═══════════════════════════════════════════════════

[Q1 — click-scan]
Run `node .claude/watchdog/qa-click-scan.js` if available. If not, spin up a small node script that uses puppeteer (or playwright if installed) to visit each public route + click every button with `[role="button"], button` and capture which click resulted in: navigation, modal open, no-op, console error, network call. Output: `_qa_boss/cycle_3/click-scan.json`. Address all no-op + error rows.

[Q2 — console errors — ZERO TOLERANCE]
Spin up: `node` script using Chrome DevTools Protocol (or `puppeteer` if available) — visit each route, capture `console.error` + `console.warn`. Save to `_qa_boss/cycle_3/console.txt`. Acceptance: 0 errors, 0 warnings on these routes:
  /, /market, /db, /help, /faq, /cart, /watchlist, /wallet, /sell, /settings, /profile/personal, /item/1, /stall/1, /loadout/{validId}, /item/missing, /stall/missing, /loadout/missing.

[Q4 — focus rings]
Tab through `/market` and `/item/1`. Every button + filter chip + link must show a visible focus ring. Add `:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }` if absent. Verify by automation if possible.

═══════════════════════════════════════════════════
RULES (REPEATED)
═══════════════════════════════════════════════════

1. Order: B1 → H2/H3/H4 → N1 → S1+S3 → D1/D3/D4/D5 → G6/G8 → M1m/M2m/M3m → Q1/Q2/Q4.
2. Rebuild + restart after every fix or batch. `bash deploy/run-local.sh`.
3. After each route, re-shoot to `_qa_boss/cycle_3/<id>.png` AND DOM-dump-grep verify the killed pattern returns 0 hits.
4. Commit per logical batch. Subject: `Boss QA cycle 3: B1 seed loadouts + LoadoutController`.
5. NO ScheduleWakeup. NO ending the response. Boss is asleep. Watchdog will re-poke if you idle past 120s.
6. When list is empty, walk all 17 desktop + 14 mobile yourself. The bar is "better than Amazon, looks like $10M / 10 years built". Find the next 10 issues yourself.

GO HARDER.
