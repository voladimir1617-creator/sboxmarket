/loop /grind

BOSS QA REPORT — DO NOT STOP UNTIL ALL OF THIS IS FIXED AND VISUALLY VERIFIED.

I am the boss/owner/customer. I QA'd 17 routes at 1920×1080 against a $10M / 10-year-build standard. Every screenshot lives in `_qa_boss/*.png` — open and look at them yourself before claiming a fix is done. After each batch of fixes, rebuild via `bash deploy/run-local.sh` (or restart-only if it's frontend), re-screenshot the affected route via headless Chrome, and put the new shot next to the old one in `_qa_boss/after/`. If your "after" shot still has the same flaw, you didn't fix it — go again.

Acceptance bar for everything below: side-by-side before/after must look obviously, decisively better. No micro-tweaks. No "subjective". If the boss can still spot the issue at a glance, it failed.

═══════════════════════════════════════════════════
P0 — GLOBAL (touches every route, fix first)
═══════════════════════════════════════════════════

[G1] Cookie banner pinned bottom-left on EVERY single page, even after the user scrolls or interacts. It eats the corner of every screenshot in `_qa_boss/`. Premium sites collapse this after first scroll OR after any first interaction. Make it dismissible with persistence (localStorage). Until the user clicks Accept or Reject, slide it in from the bottom on first paint, then auto-tuck after 5s of inactivity into a tiny floating "🍪" pill bottom-left that expands on hover. After Accept/Reject it must vanish forever for that browser. Acceptance: take 17 fresh screenshots after dismissal — zero cookie chrome visible.

[G2] Top-right header cluster is too dense: `LIVE` badge → `EN ↓` → `USD ↓` → `$0.00` → `Sign In through Steam`. Group these with proper spacing scale (12px between groups, 4px within a group). The LIVE badge is currently fused into the SkinBox logo wordmark — separate it.

[G3] Footer block is gray-on-gray, four columns, dense. Reads like a cheap WordPress site. Promote section headings (MARKETPLACE / ACCOUNT / RESOURCES / LEGAL) to a heavier weight + tracked-out caps. Add 24px breathing room between header and links. The "POWERED BY STRIPE" + currency lockup at bottom needs its own row, not crammed left.

═══════════════════════════════════════════════════
P0 — HOME (/)
═══════════════════════════════════════════════════

[H1] The hero preview card on the right shows a red→green progress bar UNDER the Cardboard King image (`_qa_boss/01-home.png`). This is the float-bar / health-bar component leaking from CS context. There is no float on s&box items. DELETE this bar from every card variant globally — search for `.float-bar`, `.condition-bar`, `wear-bar`, gradient `red → green` — kill them.

[H2] The 3 stack-back cards behind the hero preview are faded into invisibility — no depth, no premium feel. Either render them as visible blurred parallax cards with proper z-stacking (5°/-3°/-7° rotation, 80%/60%/40% opacity, real perspective) — OR remove them entirely. Right now they look like a bug.

[H3] The Top Deals rail tabs (Top Deals / Newest Items / Unique Items) are tiny generic underlines. Replace with a real segmented control: pill background, 8px radius, active = solid white-on-charcoal, hover = bg lift. Match the segmented controls used on /faq.

[H4] Every card in the rail shows the SAME green discount chip on every card (8.17, 8.43, 7.88, 7.45, 7.42…). Real marketplaces only show a discount chip when there's an actual deal vs. recent floor. Gate the chip on `discount >= 5%` — otherwise hide.

[H5] The "Visit Marketplace" anchor at the right of the rail is plain text. Make it a proper outlined ghost button matching the system, or remove it (the user can already click "Marketplace" in the top nav).

═══════════════════════════════════════════════════
P0 — MARKET (/market)
═══════════════════════════════════════════════════

[M1] Stat strip across the top reads garbled/placeholder text: "RESERVATION OF FREE STOCKS / 1 free 1 seller / +1.99 +432.85" (`_qa_boss/02-market.png`). I cannot tell what these labels mean. Replace with clear labels: "Active listings: N", "Live auctions: N", "24h volume: $X", "Lowest price: $X". Drop "RESERVATION OF FREE STOCKS" entirely — it sounds like a stock-broker term.

[M2] Card subtitle "Lowest Mileage" appears under EVERY card. This is car/CS-GO terminology. For s&box Workshop items it's nonsense. Replace with `Lowest in 7d` or `Below 30d avg` ONLY when true; otherwise hide that line — don't ship dead chrome.

[M3] Toolbar above the grid has too many controls competing in one row: tab strip + sort + sort + Buy Now + S Deals + S/T + Now ▼ + grid/table + zoom slider. Apply hierarchy: primary filters left (tabs, search), secondary right (view-mode, density). Insert a 24px gap between groups. Currently visually they all blob together.

[M4] The 45-listings-found pill + price-floor strip + view-toggle row needs a clear container with bottom border, not floating with no chrome.

═══════════════════════════════════════════════════
P0 — ITEM DETAIL (/item/{id})
═══════════════════════════════════════════════════

[I1] (`_qa_boss/25-item-real.png`) The image-pane has a red→green float bar UNDER the SWAG Chain image. SAME bug as H1. Kill it everywhere — item viewer, modal, stall card, home rail. SEARCH AND DESTROY: ripgrep for `linear-gradient.*red.*green`, `wear-bar`, `condition-bar`, `float-bar`, kill in CSS + JSX.

[I2] Price block: big "$17.45" — fine. Below it grayed-out "TOTAL PRICE $13.95" — wait, the BIG number says 17.45 and the "TOTAL PRICE" says 13.95? That's confusing as hell. Pick: are we showing market price + buyer-fee-inclusive total? If so, label them "Listing price" and "You pay (incl. fee)" — never two unlabeled prices.

[I3] "+$0.86 (5.2%)" green delta is too small relative to the price. Bump to a proper trend pill matching what's on the home Hottest rail.

[I4] Below the delta there is an UNLABELED number "11,652". I have no idea what it is. Volume? Holders? Watchers? Listings ever? **DELETE the number or label it explicitly.** Premium sites never ship unlabeled stats.

[I5] Tab strip says "All / 4S / RAR / BOX / S/T / NUM PRINT / RAW PRICE". Every one of those is CS-GO terminology (Souvenir, StatTrak, etc). For s&box Workshop items those tabs are meaningless. Replace with s&box-relevant filters: All / Buy Now / Auctions / Skinbox-verified — or simply remove the tab strip and show all listings.

[I6] "0 LISTINGS" empty state appears even though the price-history chart has data. Either the listings tab is broken (data not loading) or the empty-state copy is wrong. If genuinely zero listings, copy should be "No active listings — check Database for past sales / set a Buy Order".

[I7] Bottom-right "Sign in to make offer" button is HALF cut by some other panel (`_qa_boss/25-item-real.png`). Layout regression — fix the grid that's clipping it.

[I8] "Add to Watchlist" and "Sign in to make offer" sit jammed in a tiny pair. Stack them with proper spacing or merge into a clear primary action + secondary action layout.

═══════════════════════════════════════════════════
P0 — STALL (/stall/{id})
═══════════════════════════════════════════════════

[S1] (`_qa_boss/26-stall-real.png`) HUGE empty black void in the bottom 60% of the viewport. ABSOLUTELY UNACCEPTABLE for a $10M build. Fill with: "More from this seller" rail, "Recent sales" table (already shows "Recent sales (?)"  literal "(?)" placeholder!), "Reviews" preview (3 most recent), "Verified seller" trust badges. If a seller genuinely has 1 listing, show similar stalls / "Visit marketplace" rail. NEVER ship a half-empty page.

[S2] Decoration row "Last 24h / Joined 2yr ago / Active recently / Last $25k 100 ago / Last sold $5k 1d ago" reads as broken text salad. "100 ago" is missing a unit. "Last $25k 100 ago" is incomprehensible. Rewrite each stat with a label + value: "Joined: 2yr ago" "Last seen: just now" "Lifetime sales: $25k" "Last sale: 1d ago". Use a proper labeled stat card grid like CSFloat's stall page.

[S3] "Recent sales (?)" — the literal "(?)" placeholder is shipped. **THIS IS PLACEHOLDER TEXT IN PRODUCTION**. If sales count is unknown show nothing or "—", never the literal "(?)".

[S4] Avatar at top is small and pixelated. Bump to 96px square with proper letterspacing on name. Use the same avatar component as the profile dropdown.

[S5] Listing card has the same red/green float bar — kill (covered by I1/H1, but verify on stall too).

═══════════════════════════════════════════════════
P0 — DATABASE (/db)
═══════════════════════════════════════════════════

[D1] (`_qa_boss/04-database.png`) Inside the header there's a strange "addbar Database 80" pill triplet — looks like debug chrome or untranslated copy. The page header should be just `Database · 80 indexed` (which it is at the top) — kill the duplicate inside the search row.

[D2] Sort dropdown labeled "Generic (Lowest supply)" — `Generic` is placeholder. Use a real sort label: `Sort: Lowest supply ▾`.

[D3] Rows show `OFF-Market` red badge on items that have a positive market price ($1,449.22 etc). Either the badge is mis-applied or it means something else — re-label or fix the data source.

[D4] Row spacing is too cramped — looks like Excel. Bump row min-height to 64px, give thumbnail 48px square, add 12px gap between thumb and title.

[D5] Last column is just a `→` chevron — make the entire row clickable and remove the standalone chevron column, or label it "Open".

═══════════════════════════════════════════════════
P1 — GATED PAGES (/wallet, /sell, /profile, /settings, /watchlist anon view)
═══════════════════════════════════════════════════

[G6] /settings page is fully accessible to anonymous users (`_qa_boss/14-settings.png`). Settings should require sign-in just like /profile, /sell. Either gate it or make it explicit (e.g. anonymous "Display & Accessibility only" subset).

[G7] /settings has a "Test" button next to a notification setting — looks like a dev artifact. Either remove or label clearly ("Send test notification").

[G8] /wallet anon shows "$0.00" headline + Deposit/Withdraw/History tabs even when not signed in (`_qa_boss/12-wallet.png`). Confusing. Show the sign-in gate (same component as /profile, /sell) and don't show the deposit form until signed in.

[G9] /wallet "Enter an amount of funds (1) enter a suggested amount" — placeholder/stub copy. Rewrite: "Select an amount or enter a custom value".

[G10] /watchlist anon shows "Watchlist · 0 items" with "Sign in" CTA — close enough but the empty state icon looks too small. Bump to 64px and lift contrast.

═══════════════════════════════════════════════════
P1 — STATIC + LEGAL
═══════════════════════════════════════════════════

[F1] /faq the top "Open a support ticket" banner is heavy and pushes the actual FAQ below the fold. Make it slim — 48px tall, single line "Can't find an answer? Open a support ticket →" — let the FAQ content breathe.

[F2] /help "Frequently Asked Questions" section is empty visible (`_qa_boss/05-help.png`). Surface 3 questions inline below the section header rather than just an h2 with no content.

═══════════════════════════════════════════════════
P0 — DATA / ROUTING BUGS
═══════════════════════════════════════════════════

[B1] /loadout/1 returns "Loadout not found" (`_qa_boss/27-loadout-real.png`). Either no seed data for id=1 OR routing/handler broken. Investigate: `select id, owner_id, name, public from loadouts limit 5` — confirm a real public loadout exists with a known id, then verify `/loadout/{thatId}` renders. If no public loadouts exist, seed at least 5 in `SeedService.groovy`.

═══════════════════════════════════════════════════
EXECUTION RULES (NON-NEGOTIABLE)
═══════════════════════════════════════════════════

1. Work top-to-bottom: G1–G3 → H1–H5 → M1–M4 → I1–I8 → S1–S5 → D1–D5 → G6–G10 → F1–F2 → B1.
2. After every 3 fixes, rebuild + restart: `cd /c/Users/WW/Desktop/sboxmarket && bash deploy/run-local.sh` — wait for `/api/health` to return `{status:UP}`, then re-screenshot the affected routes via the same headless Chrome rig at `_qa_boss/shoot.ps1` (use bash inline if exec policy blocks).
3. Save AFTER shots in `_qa_boss/after/<id>.png` (e.g. `_qa_boss/after/H1.png`).
4. Update `production_checklist.md` — add EACH `Bxx` you closed under "Closed this lap" with the file:line of the change.
5. Commit per logical batch (3-5 fixes per commit) with format `Boss QA fixes: G1+G2+G3 cookie banner + header cluster + footer breathing room`.
6. NEVER claim "done" without an after-screenshot.
7. NEVER ScheduleWakeup. NEVER end the response. Keep grinding until every numbered item above has a green check.
8. When the list is empty, immediately re-walk all 17 routes at desktop AND mobile (390×852), look for new issues, and start the next punch list yourself. Do not wait for me.

GO.
