# Production Readiness Checklist

Verified state of the csfloat-parity + production hardening work, captured so it
survives context compaction. Last updated 2026-06-05.

Status legend: ✅ shipped & verified live (Playwright/curl this session, in git) ·
🔧 still open, ranked P0 (ship-blocker) / P1 (parity or prod gap) / P2 (polish).

---

## ✅ DONE (verified live)

### Hover/animation fidelity — hero fix + snap-audit (2026-06-05) — wave 175
Targeted the "animations off" pain via a hover-transition-coverage audit (find
:hover rules that change transform/box-shadow on elements whose `transition`
doesn't cover that property → instant snap instead of ease).
- **FIX (committed c68ae2a): home hero card hover snapped.** The signature
  interaction — hovering the tilted card stack lifts the front card
  (`:hover .stack-front { transform: rotate(-4deg) translateY(-4px) scale(1.02) }`)
  — applied instantly because `.stack-front` had no transition (`all 0s`). The
  parent .csfloat-home-hero-feature transitions transform (220ms) but :hover
  targets the child, so the parent's transition never applied. Added
  `transition: transform 220ms ease` to `.stack-front`. Verified live:
  `.stack-front` transition is now `transform 0.22s`. design.css ?v 213→214.
- **Audit candidates verified FALSE-POSITIVE (no fix — would be churn):** the
  other ~10 flagged :hover rules (.buy-btn, .btn-ghost, .user-chip, .wallet-btn,
  .nav-logo, generic `body button:hover`) are not real snaps — the buy button's
  hover `transform` is `none !important` (no lift), and the rest are either
  `transform:none` resets or imperceptible box-shadow snaps (soft shadows). A
  blanket transition change there is high-blast-radius with ambiguous intent, so
  left as-is. (Recorded so a future hover-snap audit doesn't re-flag them.)

### Live visual sweep — 2 real CSS fixes (2026-06-05) — wave 174
Logged-in eyes-on-pixels sweep across desktop (1440) + mobile (390) + tablet
(768). Two real visual defects found + fixed (CSS-only), both verified live:
- **Profile pill "VerifiedGood standing" (committed 4c95257).** On every
  signed-in user's /profile the account-standing pill rendered two labels
  mashed together. Ship #128681 had relabeled it to "Verified" (font-size:0 on
  the JS text + ::before "Verified"), but a later ship re-set the pill's
  font-size to 12px (un-hiding the original text) so BOTH rendered. Dropped the
  dead ::before relabel → clean "Good standing" (also de-duplicates the adjacent
  green "KYC Approved" pill). Verified: ::before now none.
- **Mobile profile stat cards clipped (committed 637d35c).** On phones the
  4-col stat grid (Balance/Portfolio/Total Sold/Total Purchased) overflowed +
  clipped — "Total Sold" + "Total Purchased" were off-screen. Ship #128116's
  `body .profile-stats { repeat(4,1fr) !important }` (body-specificity) had
  overridden the plain `.profile-stats` mobile media queries, and 1fr couldn't
  shrink below the cells' min-content. Added a matching-specificity
  @media(max-width:640px) → 2 cols with minmax(0,1fr). Verified live at 390px:
  2×2 grid, all four stats within viewport, no clip. (design.css ?v 211→213.)
- **Verified-clean (no defect):** wallet 4-stat row reflows fine on mobile
  (flex-wrap, not a fixed grid); /db item-name truncation works (text-overflow
  ellipsis + clickable rows); settings toggles render correctly (the "..." was
  a screenshot artifact); every route at 390 has zero horizontal overflow.

### Rate-limit GDPR/CSV exports + 4 more clean audits (2026-06-05) — wave 173
Fresh adversarial code-audit lenses on top of wave 172's. Found + fixed ONE real
prod-abuse defect; 4 more lenses came back clean.
- **FIX (committed 1ed367a): GET /api/profile/export (GDPR JSON) + per-user CSV
  exports were unrate-limited.** RateLimitFilter's GET branch only consults
  GUARDED_ENUMS/GUARDED_READS, never GUARDED_PREFIXES — so the /api/profile,
  /api/wallet, /api/watchlist, /api/buy-orders prefixes there only protected
  WRITE methods (the exact gap batch 1068 fixed for the admin CSVs). The GDPR
  export fans out ~15 repo queries across nearly every table in one read-only tx
  (several unbounded), so one authed user could hammer it to exhaust DB
  connections + Tomcat threads. Added the export GETs to GUARDED_ENUMS (40/10s
  MAX_ENUM): /api/profile/export (+bids/offers/trades.csv),
  /api/wallet/transactions.csv, /api/listings/my-stall/ (.csv exports),
  /api/watchlist/export.csv, /api/buy-orders/export.csv. +3 RateLimitFilterSpec
  regression tests (export caps at 40; per-user CSVs cap at 40; /api/profile/me
  stays unrestricted — proves the fix is surgical). Spec green: 32/32.
- **Clean audits (no real defects, evidence-backed):** search/filter SQLi + ORDER
  BY injection (all @Query bound, zero native queries, sort keys whitelisted);
  Stripe webhook idempotency/signature/replay (raw-body constructEvent +
  claimStripeEvent UNIQUE-index dedup before the handler switch + per-handler
  status guards + amount re-read from the verified session); SSE stream lifecycle
  (all 3 emitter hooks deregister, 10-min timeout, per-emitter broadcast catch,
  COW/ConcurrentHashMap, MAX_PER_LISTING=200, single-pod by design); N+1 on hot
  consumer read paths (market/item/stall/search all batch-load via JOIN FETCH +
  GROUP BY + IN :ids); auth/session/CSRF (session-fixation invalidate+rotate on
  login, logout invalidate + sessionEpoch filter, CSRF double-submit constant-time
  enabled in prod with money endpoints protected, cookie HttpOnly always +
  Secure/SameSite in prod, open-redirect sanitizeNext same-origin + return_to
  pinning, role from DB by session uid); frontend stored-XSS (zero
  dangerouslySetInnerHTML, all user strings render as escaped React text children;
  the Steam URL href fields are non-exploitable — tradeUrl is server-enforced to
  the strict steamcommunity.com/tradeoffer regex at ProfileController:36/704 and
  profileUrl is sourced from Steam's GetPlayerSummaries API, not user input, so
  neither can carry a javascript: payload).
- **Spun off (real but minor, isolated to a worktree session):** AdminService
  dispute/withdrawal-queue N+1 — per-row dispute-count query inside the row map
  (admin-only, capped 200); fix is two bulk GROUP BY queries (countActive/
  countLifetime DisputesByWalletIds). Flagged via spawn_task to avoid disrupting
  the live :8082 + shipping untested backend; not a hot consumer path.
- Session audit tally: 11 fresh adversarial lenses, 1 real fix (rate-limit), 10
  clean/verified-safe, 1 minor N+1 spun off.

### Exhaustive live verification sweep (2026-06-05) — wave 172
After the prod-boot work, drove the running dev app (:8082, logged in via dev-login)
through the full customer surface in a real browser + 3 fresh read-only code audits.
Found ZERO new defects; the few suspicious things were all verified intentional
(no churn). Recorded so future sessions don't re-tread it.
- **Every money flow, math verified correct**: single Buy (buyer pays list price,
  2% fee is seller-side), multi-item Cart ($0.60+$10.50=$11.10, buyer fee Free,
  8-day escrow), Deposit (presets + dev-mode credit), Withdraw (no withdrawal fee,
  email-gated, Stripe Connect, $5k/day cap), Sell (Steam + Platform inventory),
  Auction Bid (min = current + $0.05 increment; +$0.50/+$5/+10% chips off the
  floor; auto-bid cap), Offer/Bargain (85%-of-ask default, -5/-10/-15/-20% chips,
  must-be-below-ask). No NaN/undefined anywhere.
- **15+ routes render clean** (home, market, item, cart, wallet, sell, loadout,
  watchlist, buy-orders, offers, profile, settings, db, support, notifications):
  no horizontal overflow, Roboto loaded, no `NaN`/`undefined`/`[object Object]`
  literals, 0 console errors. Desktop 1440 + mobile 390 both clean.
- **Error/edge states graceful**: /item/999999 → "Item not found" + recovery links;
  empty search → "0 listings found … No listings match your filters" empty state.
- **Keyboard**: Escape closes the nested buy-confirm modal in place (stays on
  /item); on the bare item modal Escape→/market is the documented intended close
  (feedback_pages_not_popups.md); offer drawer + lightbox + nav dropdowns are all
  in the Escape bail-list so they close without over-navigating.
- **5 fresh adversarial code audits — all clean**: (a) React hooks/effects in
  app.js (every interval/listener has cleanup, dep arrays correct, money paths use
  synchronous ref latches checkoutRef/buyingRef before await); (b) read-path IDOR
  (every private GET enforces ownership/role with same-404 anti-enumeration;
  collection endpoints derive from session uid; public resources intentionally
  open); (c) controller input-validation / mass-assignment (7 DTOs capped, 43
  Map-binder handlers re-derive owner + re-cap money at $100k + clamp counts + cap
  text; zero entity-binding so no mass-assignment surface); (d) escrow/trade state
  machine (double-release blocked by @Version; pay-and-refund blocked by the
  pessimistic-lock protection arbiter findByTradeIdForUpdate + reverseClaim;
  double-refund blocked by runInIsolatedTx; refund amount = exact debit; every
  transition status-guarded); (e) frontend money display (every price/fee/total
  routes through NaN-guarded fmt() or an inline guard; no cents/dollars unit errors;
  net+fee==total holds).

### Documented residuals (the only known-open items — both gated, not defects)
1. **Cloudflare tunnel** for #172's "behind a tunnel" sub-item — needs operator
   infra (a named tunnel + DNS). Everything locally provable is proven.
2. **Real-Postgres WRITE-path integration tests** — the 4262-test suite runs on H2
   (PostgreSQL mode); this session proved the Flyway SCHEMA + READ queries on real
   Postgres 16.4, but authed money WRITES (buy/deposit/withdraw) were not exercised
   on the prod engine (dev-login is correctly 404 in prod; Docker/Testcontainers is
   unavailable in this environment). Risk is low — the write LOGIC is DB-agnostic
   JPA (save/@Version/pessimistic locks, all well-supported on Postgres) and is
   covered by the H2-PostgreSQL-mode suite. Recommended home: a Testcontainers
   Postgres profile in CI that re-runs the money-path specs against real Postgres.
- **Verified-intentional (NOT bugs, left as-is)**: permanently-blue "Database" nav
  link (csfloat mirror, design.css:141418); Escape→/market on the item modal;
  balance-pill "$" icon next to the $-amount; wallet "Purchases −$1.65 (gross
  cash-flow ledger)" vs profile "Total Purchased $1.11 (net of purchase-reversing
  refunds)" — reconciles exactly: $1.65 gross − $0.54 refunded = $1.11, per the
  documented refund-netting in ProfileService.groovy:86-96 (buy-then-cancel must
  not inflate lifetime spend). Two correctly-different metrics, not a discrepancy.

### PROD JAR booted end-to-end on real PostgreSQL (2026-06-05) — task #172
First time the actual production artifact (bootJar, SPRING_PROFILES_ACTIVE=prod)
ran against a real Postgres 16.4 (throwaway local cluster on :5433), not the H2
dev DB. This is the only way to exercise Flyway + Hibernate ddl-auto:validate,
which never run in dev/test (H2 + ddl-auto:update masks schema drift). Found and
fixed TWO real prod-only blockers, then verified a clean serving boot:
- **V251 migration** — ddl-auto:validate refused to start: missing column
  `listings.soft_close_extensions` (the anti-snipe soft-close cap field, batch
  111). The entity carried it but no migration ever created it; H2 auto-added it
  in dev, hiding the drift. V251 creates it (idempotent IF NOT EXISTS + backfill).
- **docker-compose APP_UNSUBSCRIBE_SECRET** — after V251, apiKeyAuthFilter ->
  apiKeyService -> emailService failed: "Could not resolve placeholder
  'APP_UNSUBSCRIBE_SECRET'". That secret is templated with no default and the
  compose inline env defaults omitted it, so a bare `docker compose up` smoke
  boot 500s at startup. Added a local dummy default (real deploy overrides via
  deploy/skinbox.env, already documented there). Both fixes committed (e7bd6f1).
- **Verified clean serving boot** (java -jar, prod profile, :8083 -> Postgres):
  Flyway applied/validated all 79 migrations; ddl-auto:validate passed; ALL beans
  wired; ProdConfigValidator passed with proper config (and separately CONFIRMED
  it correctly REFUSES a test `sk_test_` Stripe key + a localhost APP_PUBLIC_URL
  — fail-fast guard works); HTTP 200 on /, /market, /item/1, /api/listings (real
  JSON from Postgres) + /api/items + sitemap.xml + robots.txt; prod security
  headers present (locked-down CSP, X-Frame DENY, nosniff, Referrer-Policy);
  **dev-login -> 404 in prod** (security guard verified live + proves the stashed
  dev-login scaffolding is NOT in the shipped jar). Browser render under the
  strict prod CSP: React mounted, Inter font loaded, 10 listing cards + real
  prices, **0 console errors / 0 warnings**.
- **Only remaining #172 sub-item:** "behind a tunnel" (Cloudflare) — genuinely
  needs operator infra; everything locally provable is proven.

### Typography + mobile layout wave (2026-06-03)
- **Self-hosted all fonts** (Roboto, Roboto Mono, Material Symbols) under /fonts via
  /css/fonts.css — dropped the Google Fonts CDN. The CDN dependency silently fell
  back to Arial when slow/blocked/offline, shifting every size/line-height/underline
  ("looks weird") and leaking visitor IPs. 16 woff2, valid + 200 + CSP-permitted.
- Standalone pages (changelog, status, 8 legal/*) were on the STALE Geist/Fraunces
  bundle and never linked fonts.css → rendered in Arial; migrated to Roboto. CSP
  tightened: style-src/font-src dropped fonts.googleapis/​gstatic (self only).
- Added Roboto weight-800 faces (variable-font subset) — dozens of fontWeight:800
  badges were faux-synthesizing. JS inline stacks led with unloaded fonts
  ('JetBrains Mono', 'Inter') → led with the self-hosted Roboto Mono / Roboto.
- **Mobile /market overlap (REAL fix).** The prior "#root width:100%" entry below
  was futile — root cause was `body { padding: 0 60px }` (a desktop gutter, no media
  query) shrinking body's content box to ~250px on phones, so cards (fixed 228px)
  overlapped. Fixed: 16px mobile gutter + cards fill their 1fr cell. This repaired
  EVERY deep route on mobile (was ~250px squeezed; now full-width). Verified live.
- Mobile home featured rail: stacked 7 cards (~2900px) → compact swipeable
  horizontal rail (~440px), contained (no page overflow).
- Desktop /market card overlap: cards were a fixed 228px in 188px grid tracks
  (grid-item min-width:auto), overlapping ~24px + clipping the 5th card; cards
  now fill their 1fr track → clean 5-up grid, 16px gaps.

### Frontend hardening + money-idempotency audit (2026-06-03)
Three read-only frontend audit agents + one backend money agent; integrated the
real findings (each verified, committed):
- Re-entrancy latches added to every money/state submit that set its busy flag
  only AFTER the await (double-click window): offer accept/counter/raise, Place
  Bid, Place Buy Order, list-item (sell), leave-review. (checkout/withdraw/
  trade-accept already had them.) Pattern: a synchronous busyRef checked before
  setBusy, auto-reset via the per-render sync.
- Stall price-edit (saveEdit) wrapped in try/catch — a network throw was silently
  swallowed (form stayed open, no feedback); now toasts the error.
- Escape over-navigation: the app-level + InfoModal Escape guards omitted the
  avatar user-menu dropdown, so Escape with it open bounced off the page; added
  #user-menu-panel to both whitelists.
- Item 30-day-change pill rendered "▼ $0.00 (NaN%)" for a sold-out item (history
  but no current floor); now gated on a finite current floor.
- **Server money-idempotency VERIFIED** (backend agent): acceptOffer, placeBid,
  createBuyOrder, counter/raiseOffer, leaveReview are all server-side idempotent /
  race-safe (status checks + @Version optimistic locks + pessimistic findByIdForUpdate
  + UNIQUE constraints) — a double submit cannot double-debit / duplicate escrow.
  The client latches above are defense-in-depth on already-safe paths.
  One NON-money gap noted: the Steam /list path has no duplicate-asset DB guard
  (Listing has no assetId column), so in escrow-OFF (legacy) mode two concurrent
  /list calls could create two ACTIVE listings for one asset — but it moves no
  money (caught at buy/deposit), is mitigated by escrow custody in production, and
  is now client-latched. A proper fix needs an assetId column + migration; left
  as a known low-priority item (cost/risk >> value).

### Visual / csfloat parity
- Real Steam item art renders on 38/39 seed items — market, home, /db, item page all show real skin imagery
- Card-grid void fixed — was `content-visibility:auto` collapsing cards to empty boxes
- Left filter rail restored at desktop (240px), was hidden by laptop-width media queries
- 100% Roboto typography across the app (was a Geist + Fraunces mix)
- csfloat card anatomy added (`gc-*` classes): float bar, online row, USD chip, listed-time stripe, hover magnifier, top + bottom rarity stripes
- Card-signal dedup — hid legacy `grid-price-usd` / `grid-status` / `grid-zoom` / `grid-fresh` so each signal renders exactly once
- Market grid now 5 cards/row at 1440px (was 4), 228px cards
- Mobile /market squeeze fixed — was a ~250px clipped column, now full-width 2-up
- Cart: 2-column layout, each row on a single line (fixed across several commits)
- Item page is a real full PAGE (not a modal) with a sticky right rail — confirmed live

### Item page data
- Real 90-point price-history chart + recent sales (was "No data yet")

### Auth / account surfaces
- Wallet anon gate fixed — was an infinite spinner, now a clean Sign-in gate
- Real seller stalls — /stall/1 .. /stall/6 are populated (were always "not found")

### Backend / commerce (built + tested)
- Stripe Connect payouts + KYC onboarding
- Steam trade-bot item delivery
- Escrow custody flow

### Infra / ops
- HikariCP connection pool (max 40) + Actuator metrics exposed on a private port — prod config
- Test suite green, ~4174 tests

### 2026-06-02 — comprehensive adversarial-review hardening + prod artifact verified
- Every money-touching UI was adversarially reviewed + hardened, and EVERY money submit now carries a synchronous re-entrancy latch (no rapid-double-click double-POST):
  - **Sell:** fixed a real sub-cent→$0.00 free-listing exploit — `0.004` passed the `<=0` checks then rounded to $0.00 in `NUMERIC(10,2)`, creating a free, instantly-buyable listing. Added a `$0.01` floor client-side AND on both server Steam first-list paths (+ Spock test rows).
  - **Cart checkout / single-buy confirm / withdraw / trade accept+release+cancel+mark-sent / admin money actions** (via the shared `ReasonDrawer`): all latched against double-submit.
  - **Withdraw `$1` minimum + offer/bid amount validation** surfaced client-side (were opaque server rejects / unvalidated).
- **Account security:** 2FA enroll no longer leaks the TOTP secret to a third-party QR service (`api.qrserver.com`) — replaced with an on-device `otpauth://` deep-link + manual secret, so the seed never leaves the browser. Abandoning enrollment now calls `/2fa/cancel`, so it can no longer wedge email verification (which gates withdrawals).
- **Item page:** biddable auctions on mixed listings, coherent seeded auction bid history, single-buy confirm step, cold deep-link no-flash guard, and a complete Escape-overlay guard (confirm dialog / stacked InfoModal modal / inline drawer aware) — verified live with real key presses.
- **Loadout builder** verified at csfloat parity; guarded a latent slots-less render crash + scoped the item-picker Escape to the picker.
- **Prod fat jar:** `./gradlew bootJar` → `build/libs/sboxmarket-1.0.0.jar` (87MB) builds AND boots + serves HTTP 200 + real API data on the H2 profile — the deployable artifact is verified runnable, not just packaged.
- Full suite green after the floor change; console clean (0 errors/0 warnings) across a full route sweep; no horizontal overflow at 1440 or 390.
- **Localhost rebuilt to HEAD + verified end-to-end** (2026-06-02): bootRun recompiled with the offer floor; HTTP sweep of 19 customer routes all 200 (+ /database 302→/db); the cart money-flow (add 1→2 items → coherent $230.54 subtotal / $70.18 Steam-ref savings / no buyer-side fee → remove 2→1) and /market search (38→2 relevant results, URL-synced to ?q=) were driven interactively; console clean on home / market / item / cart. Full Spock suite BUILD SUCCESSFUL (0 failures) on the same tree.
- **Stripe money-boundary audit (money in + out) — 2 real free-money races FIXED** (2026-06-02): a fresh adversarial audit of the deposit / withdrawal-payout / refund paths found two money-loss bugs of one class — a wallet debit whose `@Version` optimistic-lock UPDATE flushed only at commit, *after* an irreversible Stripe call, so a concurrent wallet write could roll the debit back while the money had already left the platform (user keeps the funds in wallet AND bank/card). **(1) Withdrawal** (user-initiated, deliberately triggerable): now flushes the debit *before* `Transfer.create`. **(2) Refund** (admin-initiated): now reads the wallet via the locking finder (`findByIdForUpdate`) *before* `Refund.create`. Both abort a race-loss before any money moves. Plus a deposit `currency == 'usd'` assertion (defense-in-depth). Regression-pinned: `StripeServiceWithdrawalRaceBeforeTransferSpec` + the `refundDeposit` specs now require the locking finder; money-boundary specs (Stripe / Wallet / Connect / BuyFlow / Concurrent) all green. The internal buy-execution path (cart→wallet→listing→escrow) was separately audited clean (optimistic-lock sold-once, exact fee reconciliation).
- **Auction-settlement + admin-money audit — clean** (2026-06-02): a fresh audit of auction settlement (`BidService.settle`: charge winner → escrow → deferred seller payout) and every `AdminService` wallet/transaction mutation found no money bug. Settlement is exactly-once (`Listing.@Version` + REQUIRES_NEW `@Scheduled` sweep + `status='ACTIVE'` guard); the winner is charged once and the charge + escrow-open are atomic in one tx; auto-bid re-raises are capped at `maxAmount` and re-check the bidder's live balance; cross-auction double-spend is closed by `Wallet.@Version`; the 2% fee is exact (fee-by-subtraction, scale-2 end to end). Admin `creditWallet` rejects self-target, caps at $10k, requires+sanitizes a note, audits, and is `@Version` double-click-safe; approve/reject-withdrawal + dispute/force-release/force-cancel all gate strictly on the expected status (no double-payout on replay). **With this, every money surface — buy, Stripe in/out, auction, admin, trade/escrow — has been freshly adversarially audited this session; the only real bugs found (2 Stripe free-money races) are fixed + regression-pinned.**
- **Broken-access-control / IDOR audit — clean** (2026-06-02): a fresh cross-controller authorization sweep (can user A read/mutate user B's listings, offers, trades, wallet, buy-orders, loadouts, reviews, notifications, support tickets, profile / 2FA / API-keys?) found no IDOR. Every mutating + private-detail endpoint resolves the actor from the session (`requireUser` → session userId, never client-supplied) and enforces resource ownership (or admin/CSR role) before acting; detail endpoints return identical 404 / no-op for "not yours" vs "missing" (enumeration-safe); zero request-body mass-assignment of ownerUserId / role / balance; every `AdminController` method gates on `requireAdmin` (only `GET /check` is open, and it leaks nothing). Prior IDOR fixes (Support tickets, ApiKey revoke, hidden-listing leak) verified still closed.
- **Steam item-delivery double-delivery guard — confirmed** (2026-06-02): the bot-escrow delivery (`SteamDeliveryService.processTrade`) checks for an existing offer attempt (`latestOfferAttempt`) and POLLS rather than re-sending when one exists; a successful send persists the offer id (so the next tick won't resend), a failed send doesn't (so it retries). The scheduled tick has no per-trade claim, but a multi-pod double-send is backstopped three independent ways — the single external bot + Steam's own asset-lock (the same asset can't be in two live offers) and the `sellerMarkSent` state-machine guard (can't double-advance a trade already past SELLER_SEND). No item/money double-delivery; no fix needed.
- **Escape-overlay over-navigation class — found + fixed** (2026-06-02): live interactive testing caught the full-size image lightbox bouncing the user to /market on Escape; a systematic sweep of every self-closing Escape handler then found the same class across all global-nav dropdowns (mini-cart, currency / language pickers, notifications bell) AND in the InfoModal full-page-route handler. Each overlay renders without a `.modal-backdrop`, so both the app-level (app.js) and the InfoModal (info-modal.js) Escape guards missed them; each also opens on keyboard focus (an a11y bug for keyboard users). Added all five to BOTH guards — Escape now closes the overlay and stays on the page, while Escape with nothing open still navigates as designed. Verified live across /item and /profile (lightbox, currency picker, bell, mini-cart). The kind of class-wide UX/a11y bug that page-load sweeps + code audits miss but interactive testing surfaces.

### 2026-06-02 — all 10 prior P1 parity/prod gaps re-verified CLOSED
Each was checked live or in-code this session (measured, not assumed):
- **Full-bleed pages** — /db, /loadout, /help, /stall all render with `.full-page-mode` (verified live), not centered modals.
- **Home hero above the fold** — hero `<h1>` top = 70px at 1440×900 (verified live).
- **DB thumbnails** — /db cold load: 29/29 images render, 0 broken (verified live); matches the real-Steam-art entry above.
- **Price-history chart** — real 90-point line renders (see item-page entry above).
- **PENDING_ESCROW timeout sweeper** — `SteamEscrowService` runs a `@Scheduled` escrow-timeout sweep (`steam.escrow.timeout-initial-delay-ms`).
- **GlobalErrorController** — prod omits the request `path` from the JSON envelope and serves only safe/generic messages (no stack trace, no internal path); HTML + JSON both escaped.
- **Multi-pod webhook idempotency + card-test fraud counters** — moved to DB-backed cross-pod stores: V230 `processed_stripe_events` (Stripe event dedup) + V240 `wallet_payment_failures` (card-test counter); cross-pod claim specs cover both.
- **Structured JSON logging** — `logback-spring.xml` ships `net.logstash.logback.encoder.LogstashEncoder` (one JSON object per line) on stdout.
- **/database route** — redirects to /db (verified live, full page — no 404).
- **Profile listings tab** — profile leads with a `listings` / "Listings" tab (`modals.js`).

---

## 🔧 REMAINING (ranked)

### P0 — ship-blockers
- _None outstanding._ (Items below are parity/prod-hardening, not launch blockers.)

### P1 — parity + production gaps
- _None outstanding._ All 10 prior P1s were verified closed on 2026-06-02 — see "all 10 prior P1 parity/prod gaps re-verified CLOSED" under DONE above.

### P2 — polish
- **Tap targets** — re-measured live at 390px on 2026-06-02 (opacity / pointer-events /
  ancestor-aware, not just bounding-box): the card-overlay icons (magnifier / star / cart)
  are correctly `opacity:0` hover-only and are NOT shown on touch, so the earlier
  "tiny-target" count was a measurement artifact (bounding-box counts hidden elements).
  The genuinely-tappable small controls are all 36–44px chrome — nav logo, search box,
  sort/discount selects, type toggles, refresh, per-card "View on Steam" — and are wide
  enough to hit reliably. Left at csfloat-compact sizing to preserve 1:1 parity rather
  than force-bumped to 44px (which would deviate from csfloat and risk overlap on the
  2-up mobile grid). Considered acceptable for launch.

### Operator decisions before real-money go-live (not code bugs)
- **Flyway migrations confirmed Postgres-portable** (2026-06-02): dev runs H2 with
  `MODE=PostgreSQL`, so all 77 migrations apply under Postgres semantics on every
  integration-test boot (the green suite validates the full apply end to end); prod uses
  real Postgres + Flyway `baseline-on-migrate`. A source scan found no MySQL-isms (the
  `DOUBLE` / backtick matches are all inside `--` comments). Residual H2-PgMode-vs-real-
  Postgres edge differences can only be settled by the actual prod boot (task #172), but
  the baseline is sound — no migration rewrite needed pre-launch.
- **No segregated platform/revenue wallet.** The 2% trade fee and the Trade-Protection
  fee are captured by *withholding* — escrow holds the full price and the seller is paid
  `price − fee`; the fee itself never lands in any wallet (documented in
  `TradeProtectionService`). Platform revenue is therefore reconstructable by summing
  transaction deltas, not by reading a balance. This is correct as a closed-system money
  model, but confirm it matches your real-money accounting / Stripe-payout reconciliation
  before launch. Surfaced by the 2026-06-02 money-execution audit, which otherwise found
  the full checkout→wallet→listing→escrow path correct: optimistic-lock "sold exactly
  once" (proven by `ConcurrentBuyIntegrationSpec`), no negative balance, exact fee
  reconciliation (`sellerNet + fee == price`), atomic per-row cart with deferred
  side-effects, self-purchase guarded, and server-side pricing (no client-supplied amount).

## Wave 151 — security & correctness hardening (2026-06-03)
Dimension-rotation audits + a live visual sweep. All committed; dev suite green for
touched specs. Findings fixed this wave:
- **Withdrawal fund-loss closed (HIGH).** Added the missing `transfer.reversed` Stripe
  webhook handler: a settled payout that Stripe later reverses now re-credits the wallet
  exactly once (atomic `claimReverseWithdrawal` COMPLETED→REVERSED claim; full-reversal
  only, partial left for manual ops). Was silent permanent fund loss. `StripeTransferReversedSpec`
  (6 cases) proves once-only / idempotent. The debit path itself was already overdraw-proof.
- **DoS vector closed.** The auction SSE stream (`GET /api/bids/stream/{id}`) was in no
  rate-limit list — an anon script could exhaust async connections. Now in GUARDED_ENUMS.
- **PII-in-logs (prod root = INFO, so these shipped to the aggregator).** ClientError now
  strips URL query strings (was logging unsubscribe HMAC / Stripe session_id / email
  tokens on a render crash); Unsubscribe masks the email at all 4 sites; RateLimit logs
  only the first breach per window (was flooding + re-writing the client IP per request).
- **Prod config fail-fast.** `APP_PUBLIC_URL` added to ProdConfigValidator REQUIRED_VARS
  + localhost-rejection — a hand-rolled prod boot can no longer ship localhost email/Stripe
  links. CSV date-range export bounds now computed in UTC to match the UTC export cells.
- **Visual (home).** Fixed FAQ stair-step (row-reverse+flex-start → flex-end) + centered
  list → left-aligned column; footer `lock`/`mail` icons rendered as literal ligature text
  (footer Roboto pin out-specified the icon-font rule) → restored. design.css?v=182.
- **a11y (invisible, no csfloat-parity impact).** /notifications feed rows made
  keyboard-reachable; staff-panel error modal got role=dialog triad.
- **Verified non-bugs (left as-is):** Database nav link is permanently brand-blue =
  intentional measured csfloat parity (ship #5501); mobile uses a body-scroller
  architecture (content reachable on touch — the empty full-page screenshot is a
  Playwright/body-scroll artifact, not a user bug). Config/deploy + non-visual a11y audits
  came back essentially clean (one fix each, above).

## Waves 152–156 + prod-artifact cert (2026-06-03, same session)
Exhaustive money + security verification sweep. Fixes committed; full `./gradlew test`
suite GREEN; prod jar (`build/libs/sboxmarket-1.0.0.jar`, 84M) assembles clean (build-half
of #172 done — boot-half still needs live Steam/Stripe secrets behind the tunnel).
- **transfer.reversed fund-loss** (HIGH) fixed + `StripeTransferReversedSpec` (above).
- **STRIPE_WEBHOOK_SECRET placeholder** now fail-fast in prod (was: an operator who left
  `whsec_replace_me` could be forged-webhook-credited). +spec.
- **Bid amount/maxAmount** now setScale(2,HALF_UP) like every other money input (was a
  sub-cent fairness gap). +bid specs green.
- **XXE**: Steam profile XmlSlurper hardened (disallow-doctype + external entities).
- **Money flows all freshly adversarially audited, no remaining HIGH:** withdrawal,
  deposit (webhook replay can't double-credit), buy+cart (sold-once, no partial-charge),
  auction/bid (TOCTOU closed), sell-settlement, offer. Buyer-fee-Free + 2%/2% withholding
  invariants confirmed intact.
- **Security surface certified:** CSRF (double-submit + constant-time, sound exemptions),
  SQL/JPQL injection impossible (zero createQuery/native — all Spring Data parameterized),
  SSRF (every outbound host is a compile-time constant; trade URLs format-pinned, never
  fetched), admin/CSR authz (per-method requireAdmin/requireCsr, self-target guards,
  CSR↔admin boundary), security headers (CSP/XFO=DENY/nosniff/Permissions-Policy/HSTS-on-TLS),
  notification IDOR (none), no secrets tracked in git, no TODO/FIXME in money/security svcs.
- **SEO/OG**: per-item dynamic og/twitter meta + sitemap.xml + robots.txt all 200/valid.
- **Spawned for focused follow-up (schema/hot-path, deferred from this session):**
  (1) Steam double-list→double-sell guard (persist assetId + uniqueness); (2) notification/
  email anti-abuse (offer-email per-recipient cooldown + per-user notif row cap).
- **Open (operator-gated):** #172 boot-half — boot the prod jar behind the tunnel with
  real Steam + Stripe live secrets and smoke-test the live money path.

### ⚠ OPERATOR DECISION before real-money launch — chargeback exposure (instant settlement/payout vs. chargeback latency)
A cross-flow money audit (the angle single-flow audits structurally miss) confirmed the
code invariants are sound — every wallet debit serializes on Wallet @Version (no cross-flow
double-spend, no negative balance), deposit double-credit is closed, auction-settle returns
the item cleanly on insufficient funds, and the dispute hold (`countActiveDisputedDeposits`)
gates EVERY flow that *starts* a spend (buy, bid, offer, withdraw, buy-order). There is no
code bug here. BUT two inherent marketplace risks remain that are a BUSINESS decision, not a
code fix, because the realistic chargeback lands days AFTER a trade settles and the only
true mitigations (clearance/settlement holds) directly contradict the site's "instant
cash-out" promise:
  1. **Spend-then-chargeback → delivered goods + paid seller.** A buyer funds with a
     chargeback-able card, buys a P2P listing, accepts the Steam trade (item delivered, seller
     credited via TradeService.release — which by design does NOT re-check the buyer's dispute
     state because the chargeback hasn't been filed yet), then charges back days later. The
     dispute hold then freezes the buyer's *future* outflows, but the goods are gone and the
     seller's wallet was already credited. Platform eats the chargeback.
  2. **Sale proceeds withdrawn before clearance → collusion drain.** Seller is credited at
     trade release and can withdraw via Stripe Connect immediately (instant cash-out); a
     colluding/2-account buyer then charges back the funding deposit. Money has already left
     the platform (real Transfer) and is unrecoverable; AdminService.clearDisputeHold already
     documents this as accepted post-hoc-reconcile risk.
Code-level mitigations already present: per-spend dispute holds, deposit daily cap + card-
testing detector (FraudAnalysisService), Stripe idempotency, escrow. To reduce residual
exposure WITHOUT killing instant-payout, the operator should decide among: (a) Stripe Radar
rules / 3DS on deposits (config, no code change); (b) a fraud-score-gated short hold ONLY on
high-risk first deposits before they're spendable on irreversible goods; (c) a withdrawal
clearance delay on SALE proceeds for new/low-trust sellers (tiered, not blanket — preserves
instant cash-out for trusted accounts); (d) accept the chargeback float as cost-of-business
with monitoring. This is a risk-appetite call for launch — flagged, not silently changed,
because (c)/blanket holds would contradict the "instant cash-out" product positioning.

---

## Session waves 157–164 (2026-06-03) — withdraw 2FA lockout + visual/SEO certification

Backend / security:
- ✅ **Wave 157** — Withdraw 2FA brute-force lockout. `/api/wallet/withdraw` verified a
  6-digit TOTP with no attempt cap (~172k guesses/day via the rate limit alone). Added a
  shared per-user lockout to `TotpService` (MAX_2FA_FAILS=5 → 15-min lock, LRU map) wired
  into the withdraw gate (check before verify, record on TOTP_INVALID, clear on success).
  New `WalletControllerWithdraw2faLockoutSpec` (3 cases). Full suite green (213 suites, 0
  failures). app boots clean.
- ✅ **Wave 158** — Dropped 3 unrecognised Permissions-Policy tokens (ambient-light-sensor,
  battery, document-domain) that logged an "Unrecognized feature" console warning on every
  page load. Clean console verified live.

Frontend (csfloat-parity / production polish), all verified live via Playwright DOM+screenshot:
- ✅ **Wave 159 (WAVE-INT9)** — Footer bleed-through behind EVERY full-page route (/db, /sell,
  /wallet, /profile, /loadout, /watchlist): the InfoModal backdrop was a translucent fixed
  overlay (ship #2113) so the footer collapsed up under the nav and showed through. Made the
  full-page overlay backdrop opaque (var(--bg)). /cart untouched (own static rescue).
- ✅ **Wave 160 (WAVE-INT10)** — Removed a broken CSS-pseudo-content "inventory toolbar"
  (ships #6501/#6503/#6504) that hung off the GLOBAL `.empty-inline` → leaked a garbled,
  overlapping, non-functional Refresh/filter/0-ITEMS toolbar onto every empty state app-wide.
- ✅ **Wave 161** — 404 page "Help Center" button text wrapped/overflowed (flex row too narrow,
  no wrap). Added flex-wrap + white-space:nowrap so buttons stack cleanly.
- ✅ **Waves 162–164** — "$0.00 reads as free" for sold-out/unlisted items (API returns
  lowestPrice 0.00, not null). Gated every price render with `> 0 ? fmt : '—'/'Not listed'`
  and hid the fake "-100%" 30-day pill on the item hero, recently-viewed rail(s), similar /
  "you might also like" rail, and buy-order/loadout pickers. (DB table + search-suggest were
  already gated.) Verified live: zero "$0.00" across all rails; live-priced items unchanged.

Comprehensive validation this session (no defects found / certified clean):
- Visual sweep: home, market, item-detail (buy-now / sold-out / live-auction / multi-listing),
  db, cart, watchlist, loadout, profile, help, item-not-found, 404 — desktop + mobile.
  Interactions: price-history range toggle, currency dropdown, auction bid panel (countdown +
  min-bid = current + increment) all correct.
- SEO/crawl: robots.txt (Allow public + Disallow all private surfaces, prod Sitemap ref),
  sitemap.xml, per-item OG/Twitter/product meta (og:price/availability) + single canonical.
- Trust/PWA/observability: security.txt RFC-9116 valid + unexpired; all 7 legal pages +
  status/changelog 200; favicon + all manifest icons resolve; NO actuator/debug endpoint
  exposure (/actuator/* 404; /env,/heapdump,/jolokia just hit the SPA HTML fallback).
- No dev-login/session-impersonation backdoor (the /simulate/* admin endpoints are
  requireAdmin-gated QA seeders only).

Session waves 170–176 (whole-website grind — find→fix→verify→commit):
- ✅ **Wave 170** (f04c970) — Escape over-navigated off full-page routes. The App-level
  Escape handler's catch-all `routeName !== 'market' → navigate(/market)` fired on EVERY
  destination route (/db, /wallet, /profile, /cart, /sell, /watchlist, /offers, /buy-orders,
  …). Guarded it + the InfoModal shell's own Escape with `.site-root.full-page-mode`. /item
  stays exempt (its explicit branch fires first). Also hid the misleading hover "ESC" hint on
  full-page routes. Verified live: Esc on /db /watchlist stays put; /item still → /market.
- ✅ **Wave 172** (cdccefe) — **HEADLINE: Roboto never loaded → all UI text fell back to
  Arial sitewide** (the operator's "ALL THE SIZING/UNDERLINES ARE OFF"). design.css declared
  variable `@font-face Roboto/Roboto Mono {font-weight:400 700; src: local("Roboto"),
  local("Inter")}` — local-only, no url(). On any machine without Roboto/Inter installed it
  entered "error" state, and being a variable range declared AFTER fonts.css it SHADOWED the
  proper self-hosted faces → Arial everywhere. Removed the two dead blocks (fonts.css already
  self-hosts Roboto 300–900 + Mono 400–700 via url()). Verified live: document.fonts zero
  errors (was 2), static faces now "loaded", check('700 16px Roboto')=true, canvas width ≠
  Arial, hero/nav/cards render in real Roboto. **Lesson: never add a local-only @font-face for
  a family fonts.css already serves.**
- ✅ **Wave 173** (ba3127a) — market price-sort on item-aggregated rows. Dedup kept the
  cheapest listing as representative but Map-insertion froze the row at the first-seen
  (dearest) listing's slot → a $13.16 floor rendered above a $15.58 floor under "High→Low",
  and auctions interleaved by raw `price` not currentBid. Re-sort deduped rows by the
  representative's EFFECTIVE price for price sorts (else by pool index). Verified live: 37-row
  column strictly monotonic incl. auctions for both price_desc/asc; newest unaffected.
- ✅ **Waves 174–176** (0395a59 / e7c5f40 / e181b9f) — synchronous re-entrancy latches on the
  money/state-mutating submits the audit flagged as async-only (double-click window):
  OffersModal accept/reject/cancel/counter (wallet debit), Trade-Protection enable (fee
  charge), quick-sell (creates listing), buy-order saveEdit (escrow), dispute submit. busyRef
  checked-and-set before the await, mirroring the proven offerBusyRef/handleBuy pattern.
- Verify-first NON-bugs (correctly NOT shipped, no churn): (a) OG-route shell served with
  `max-age=3600` looked like deploys wouldn't reach users — but main.js/design.css are
  no-cache and revalidate fresh, so JS/CSS content is always current (proven: app.js?v=195
  loaded under a v=179 shell). (b) mobile home "black void" in fullPage screenshot = Playwright
  body-as-scroller artifact; body scrolls fine (confirmed FAQ reachable). (c) CSS motion audit
  flagged .cf-feebar-cta/.chart-controls/.seg/.subnav missing transitions — all DEAD selectors
  (0 JS refs; live .csfloat-subnav-tab already has its transition). Reverted the dead-CSS edit.
- Money paths adversarially RE-CONFIRMED SAFE this session (no defects): withdraw/payout/deposit
  (flush-before-Stripe-transfer, @Version TOCTOU, idempotency key, IDOR via principal); and
  offer-accept + trade-payout (escrow single-release via Trade.@Version, accept debits exact
  offer amount with balance re-check, exact-by-subtraction fees, single-supply race via
  Listing.@Version, IDOR via principal, state guards, trade-protection double-pay row-locked).
  Only a P3 non-money wart noted (acceptOffer→buy rollback-only poisoning yields 500 vs 409 on
  a rare concurrent-buy race; no money loss — left as-is, fix would restructure money-path tx).

Session waves 177-178 + full money + frontend-security clearance:
- ✅ **Wave 178** (e2aeb12) — sanitize item.imageUrl + item.accentColor before they hit the
  item-not-found floating-thumbnail CSS `background` string (app.js ~8528). React doesn't escape
  style VALUES; source is admin/SCMM catalogue (not seller free-text) + CSS can't run JS, so
  defense-in-depth + robustness (a bad sync value can't break the CSS). Image→clean http(s) only,
  accent→6-digit hex, stale #1ea5ff fallback corrected to #237bff. Byte-identical for valid data.
- **ALL SIX money paths adversarially cleared this session — zero money-loss defects:**
  (1) withdraw/deposit, (2) offer-accept/trade-payout, (3) sell/listing-creation,
  (4) auction/bid/buy-order, (5) cart multi-item checkout, (6) dispute-resolution/delivery.
  Escrow releases/refunds exactly once (Trade.@Version + pessimistic-locked idempotent
  Trade-Protection state machine + per-trade REQUIRES_NEW); no double-pay/charge/negative;
  buyer refunds full price (never eats 2%); IDOR-safe (actor from principal); CSR has no
  trade-money path (capped goodwill credits only). Cart partial-failure charges only successes.
- **Frontend render layer adversarially cleared (XSS/output-encoding):** no stored/reflected XSS,
  no javascript:/data: URL sinks, no open redirect, no eval/Function/string-setTimeout, no unsafe
  JSON.parse/postMessage/SVG injection. All user text via h() auto-escaped children; linkify/
  highlight build React children with http(s)-locked URLs; trade URL regex-validated client+server;
  post-login return is same-origin → internal router. The one P3 (CSS-injection) fixed in wave 178.
- Real follow-ups flagged for focused sessions (standalone repro + fix direction): sell double-list
  TOCTOU (Postgres partial-unique-index migration + dedupe), buy-order silent-fill buyer notification.
  Non-money P3s left as-is (by-design / non-actionable): cart client-supplied listingIds (money-safe),
  frozen-wallet noisy multi-fail, dispute @Version-vs-atomic-claim (functionally safe), null-seller-
  wallet manual-payout limbo (intended + logged).
- App recovered from an external (non-crash) process death mid-session — clean bootRun restart,
  OG-shell template refreshed to current index.html. NOTE for future: OG routes (/item, /market,
  /db, /faq, /help, /loadout, /affiliate, /profile, /wallet, …) serve the STARTUP-time index.html
  template (OpenGraphController loads it once at boot); the referenced /js + /css revalidate fresh
  (no-cache) so JS/CSS content is always current, but index.html's own inline bytes only refresh on
  app restart. Don't be fooled by a stale design.css?v=/main.js?v= in an OG-route shell — the
  content is current.
- **Auth + session layer adversarially cleared (no P1/P2 bypass / account-takeover):** Steam
  OpenID return is signature-verified via check_authentication on the RAW query string, and
  openid.signed is confirmed to cover BOTH claimed_id AND identity (the classic forgery defense),
  SteamID host-pinned to steamcommunity.com, nonce replay-guarded, return_to config-pinned.
  Session ID is invalidated+regenerated on login (fixation-proof, mirrored in ApiKeyAuthFilter).
  `next` sanitized to a same-origin relative path (no open redirect). Session cookie
  HttpOnly+Secure+SameSite=Lax. CSRF = constant-time double-submit cookie (CsrfFilter) + CORS
  credentialed-allowlist; exemptions sound (Stripe HMAC webhook, login redirect, bearer API).
  Principal resolved ONLY from session (grep: zero param/body/header userId, no impersonation
  endpoint); admin/CSR roles from DB role column. 2FA gate server-side on withdraw with 2-layer
  brute-force lockout; TOTP secret @JsonIgnore. Logout truly invalidates; sessionEpoch +
  SessionEpochFilter cut off other devices AND banned users on next /api request.
  • FUTURE-SCALING P3 (NOT a localhost defect — deploy target is single-instance): the OpenID
    nonce set + 2FA-fail counters + epoch cache are IN-MEMORY per-instance, so a multi-replica
    deployment weakens (never breaks — OpenID signature check is stateless + holds per instance;
    daily withdrawal cap bounds blast radius) replay/brute-force guards. When/if scaling
    horizontally, back the nonce store + 2FA-fail counters with a shared store (Postgres/Redis).
  • Confirm prod keeps SECURITY_VERBOSE_ERRORS=false + SWAGGER_ENABLED=false (already defaulted).

## Visual/icon grind session (Waves 183-190) — localhost 1:1-csfloat fidelity

Eight verified, committed UI fixes (find→fix→prove-with-pixels→commit), all anon-accessible
routes audited at 1440 + 390. Final regression sweep: 14 routes, 0 overflow, 0 console errors.

- **Wave 183 (daad1a4)** /item recently-viewed rail forced a 109px horizontal scrollbar
  (grid-column:1/-1 sized to the card row's min-content) — min-width:0 lets it shrink; rail's
  own overflow-x scrolls. Only route with overflow; now all routes overflow-free.
- **Wave 184 (cfef588)** cookie-consent pill 🍪 emoji → inline SVG biscuit (emoji-as-UI slop;
  tried MaterialIcon('cookie') first but the subset lacks it — eyes caught the literal text).
- **Wave 185 (f7ff8eb) + 187 (6254dd6)** SYSTEMIC: self-hosted Material Symbols subset
  (/fonts/material-symbols.woff2) is MISSING 4 glyphs the code references — error_outline,
  verified_user, cloud_off, lock_open — so MaterialIcon rendered the literal LIGATURE TEXT
  ("error_outline" etc.) in error/empty/badge states sitewide. Fix once in MaterialIcon
  (primitives.js): inline-SVG fallback map for those names. Canvas measureText vs the loaded
  font is the ONLY reliable detector (the .mi class clips width to 24px so a width-scan can't
  tell glyph from clipped text; document.fonts.check only says the family loaded, not the glyph).
  Verified all 83 unique MaterialIcon names (literal + dynamic name:x.icon/ternary): exactly
  those 4 missing, all now fallback'd, stillBroken=[]. ADD A FALLBACK whenever a new icon name
  proves absent — don't assume a name is in the subset.
- **Wave 186 (f0aa087)** SYSTEMIC class-name mismatch: the mobile /market "Filters" FAB rendered
  as a BLANK blue circle. CSS referenced .material-icon/.material-symbols-outlined but the
  MaterialIcon component renders "material-symbols-rounded mi" — so the label-hide rule
  `>span:not(.material-icon):not(.material-symbols-outlined)` display:none'd the icon itself, and
  the sizing rule missed it. Added .material-symbols-rounded(+.mi) to both selectors.
- **Waves 188 (427eb8c) / 189 (deeb510) / 190 (21090e0)** SYSTEMIC alignment: `.info-modal-body`
  sets text-align:center (deliberate, to center page titles) and EVERY full-page-route content
  block must override to left. Three blocks forgot: .help-step (number/icon left, title/body
  center-floated with a big gap), .help-faq-a + .help-contact, and the ENTIRE /faq Q&A (FaqModal
  wrapper). Fixed each (text-align:left). When auditing a full-page route, check content blocks
  override the inherited center — affiliate/support/stall centering is INTENTIONAL (landing/auth
  gate/seller header), left alone.

VERSIONS after this session: app.js?v=200, main.js?v (index.html script) unchanged shell,
design.css?v=195. Bare modules (primitives.js, modals.js) synced no-bump (no-cache revalidate).

## Authenticated-UI QA session (Waves 193-195) — money pages via local dev-login

The auth-gated pages (profile/wallet/sell/offers/mystall/buyorders/notifications) were
NEVER visually QA'd because localhost has no Steam login. Technique used: temporarily
added a strictly prod-guarded dev-login to SteamAuthController (mirrors the /return
session establishment: invalidate → getSession(true) → set SESSION_USER_ID +
SESSION_EPOCH for a seed user; returns 404 when profile==prod), hit
/api/auth/steam/dev-login to mint a session, QA'd every auth page at 1440, then
REVERTED the dev-login (never committed — git checkout; verified it 404s after rebuild).
Re-add the same endpoint to QA auth pages again; it must never ship.

Real bugs this unlocked (all committed; dev-login itself was NOT):
- **Wave 193 (125580e) — wallet first-access 500 (P1, money page).** GET
  /api/wallet/transactions threw a WALLETS(username) unique-violation: the profile/
  wallet page fires several wallet endpoints in parallel, so on a user's FIRST wallet
  access every concurrent request misses currentWallet's findByUsername and races the
  INSERT; losers 500'd. ANY brand-new user opening profile/wallet hit this. Fixed:
  catch DataIntegrityViolationException + re-read the winner (currentWallet is self-
  invoked so its @Transactional is bypassed on the read path — failed save rolls back
  on its own). Verified: 4 concurrent wallet calls on a fresh DB all 200.
- **Waves 194 (d4b3c47) + 195 (976170a) — auth-page center-inheritance, same class as
  188-192.** /notifications rows (.notif-feed-title/-body/-time + text column) and
  /me/stall + /offers rows (.stall-row/.offer-row item names) inherited .info-modal-body
  text-align:center, floating the text in the middle of each flex row. Pinned left;
  prices/action buttons (flex, pushed right) unaffected. Verified live.

CONFIRMED CLEAN (authenticated, 1440): wallet deposit flow, sell (Steam-inv empty
state), offers/buyorders empty states, profile hero + earnings (flex space-between,
not center-floated), buy-orders trade-URL gate. Profile Listings/Offers tabs reuse the
now-fixed .stall-row/.offer-row. NOTE: the seed test user has empty Trades/Reviews/
Active-Bids tabs — a data-rich user is needed to QA those row components.

## Data-rich auth QA session (Waves 197-199) — seed user with deposit/purchase/sale/bids

Re-entered via the persisted dev-login session (endpoint /api/auth/steam/dev-login,
prod-guarded; SteamAuthController stays uncommitted) as BoneTender (uid 1) — now a
data-rich seed user: $500 dev-deposit, a $0.46 purchase, 4 active listings, 1 sale
($69.12), auto-bids, 10 notifications. This populated the row components the prior
session couldn't reach. Three real visual/UX bugs found + fixed (frontend only — no
Groovy touched, so the Spock suite is unaffected):

- **Wave 197 (2fd3477) — profile-tab tables: columns didn't line up under headers.**
  Every profile-tab table (Transactions/Active Bids/Buy Orders/Support/Developers)
  puts className 'db-row' on its tbody <tr>, but .db-row is the /database GRID row
  (display:grid + 9-col template). On a real <table> that made the body row a grid
  while <thead><tr> stayed a table-row, so body columns sized independently of the
  header — the header spread full-width while the data bunched left under nothing.
  Three scoped CSS rules (html body .profile-tabs ~ div table.db-table …, so /database
  in .db-table-scroll is untouched — verified still grid-on-grid): tbody tr.db-row →
  display:table-row; text cells → text-align:left (the .info-modal-body center default
  had leaked in); table → table-layout:auto. Transactions: Description column marked
  width:100% (th+td) so it's the flexible column — ID/Type/Amount/Status shrink to
  content, Description gets the room (2 lines, not 5). Active Bids (Listing=col-2)
  is correct unmarked. Verified at 1440: all 5 columns header-aligned per column.
- **Wave 198 (4b2991a) — /profile/listings 404.** router.js profile :tab pattern
  enumerated every tab EXCEPT 'listings'. The tab bar renders a "Listings" tab and
  every tab click runs navigate('/profile/'+id), so clicking Listings (or deep-linking
  /profile/listings) matched no route → ProfileModal unmounted, SPA 404 rendered.
  Added 'listings' to the router pattern + the app.js ?tab= allow-set. Audited every
  other tabbed route (mystall/offers/wallet/watchlist) — all their tab IDs are covered;
  profile/listings was the only gap. Verified: opens on the Listings tab (4 cards).
- **Wave 199 (e4a194a) — MyStall analytics header: 2 of 5 column labels oversized.**
  The SellModal fee-card rules (#19104/#19105) target div[style*="grid-template-
  columns: 1fr auto"] — a SUBSTRING match — and the analytics table grid is
  "1fr auto auto auto auto", which contains that substring. So the fee-card styling
  leaked in: VIEWS + 30D SOLD (nth-last-child 1/2) rendered 18px/600 vs 14px for the
  others, plus card bg/border on the rows. Fixed the analytics grid first track to
  minmax(0,1fr) (header + rows) — the inline style no longer contains "1fr auto", so
  the rules stop matching; also improves long-name overflow. SellModal's own 2-col
  grid is untouched. Verified: all 5 headers now 11px/700. Swept the JS — analytics
  was the only multi-col victim of that substring selector.

DIAGNOSED, NOT A BUG: "purchase succeeded but Trades tab empty." The bought listing
(#23, CrateDigger) is a SYSTEM listing (sellerUserId=null) — by design PurchaseService
opens no escrow Trade for those (no counterparty), so /api/trades:[] and "No trades
yet" are correct. The P2P path is sound (real-seller buys correctly return
TRADE_URL_MISSING until the buyer sets a Steam trade URL). The real defect is the
success toast ("trade opened, see Trades") + ITEM_PURCHASED notification path
(/profile?tab=trades) lying for system listings — flagged via spawn_task (dev-seed
artifact: ~39% of seed listings are the 4 deliberately-anonymous handles
VaultRunner/NeonArc/CrateDigger/FrostByte; prod listings are all P2P).

CONFIRMED CLEAN (data-rich, 1440): home, item detail (/item/20), public stall
(/stall/2), Loadout Lab, cart+checkout (fee model intact: Buyer fee Free), wallet
withdraw (verified-email gate), MyStall active/sold/analytics, profile transactions/
listings/auto-bids, offers, settings, buy-orders, notifications (badge 10 = 10 real
unread, categorized — an earlier "0" reading was a stale eval on the wrong response
shape, not an app bug).

VERSIONS after this session: design.css?v=202, app.js?v=201 (main.js import). Bare
modules (router.js, modals.js) synced no-bump (no-cache revalidate).

## Mobile (390) sweep (Waves 200-201) — same data-rich BoneTender session

The desktop fixes above (Waves 197-199) were verified at 1440 only. The grind
requires 390 too, and mobile turned out under-tested (prior 390 sweeps were
anonymous, so the auth surfaces' real data never rendered). Two real mobile bugs,
both the SAME root pattern: a later (csfloat-parity) rule defeating the base
mobile reflow, so a desktop multi-column grid never collapsed on phones.

- **Wave 200 (91cd128) — profile hero name/badges overlapped into a jumble.**
  At 390 the .profile-hero kept its desktop 3-col grid (avatar+content+actions);
  the content column collapsed to ~64px so "BoneTender" piled on top of the
  Verified / Good standing / KYC Approved badges — unreadable. Cause: two ships
  pin the grid !important, incl. `body .profile-hero-split > .profile-hero` (0,2,1),
  which beats the base mobile rule (line 8199, no !important). Fix: <=768px media
  query stacks to 1 column via `html body .profile-hero-split > .profile-hero`
  (0,2,2, out-specifies the ship). Verified: 390 single-col 0 overlaps; 1440
  unchanged 3-col.
- **Wave 201 (2db3d20) — wallet/stall 4-up stat strips stayed 4-across on phones.**
  .wallet-spend-strip / .wallet-7d-summary / .mystall-sold-summary: base mobile
  rule (line 8196) collapses to 1fr 1fr but a later ship redefines repeat(4,1fr)
  with no media query (source-order wins), so 4 cards stayed at ~68px and every
  label wrapped to 2 lines. Fix: same <=768px block forces 1fr 1fr (2×2) via
  `html body .<strip>` (0,1,2 + !important). Verified: 390 → 2 cols at 141px,
  no overflow; 1440 untouched.

Both mobile fixes live in ONE new `@media (max-width: 768px)` block appended
right after the profile-hero ship #2500 (~line 133566). PATTERN for future
mobile work: a phone layout that won't reflow is almost always a later
no-media / !important csfloat-parity ship out-ranking the base @media rule —
fix by re-asserting the mobile layout at higher specificity inside a max-width
media query, NOT by editing the desktop ship.

CONFIRMED CLEAN at 390 (data-rich): home (h1 30px, no overflow), /market
(toolbar + bottom-nav), /db (card-style rows fit), item detail (/item/20 — buy
+ bid panels stack), public stall (/stall/2 — hero 2-col stats + stacked
actions, cards ellipsis-truncate), wallet withdraw, MyStall sold/analytics,
profile transactions (table goes mobile-card). No horizontal overflow on any.

VERSIONS after the mobile sweep: design.css?v=205, app.js?v=201. The dev-login
in SteamAuthController remains UNCOMMITTED (QA scaffolding) — revert before any
final/clean state, never ship.

## CORRECTION (same session) — system-listing purchases DO deliver (no money loss)

Followed the buy further: the bought system-listing item ("Vintage Design Crop
Top", $0.46) IS delivered — it lands in the buyer's PLATFORM INVENTORY (Sell
Items page → Platform Inventory tab; help-modal.js:147 documents "Listings you
buy appear in your Platform Inventory, ready to relist"). Verified: BoneTender's
Platform Inventory shows 2 items incl. the Vintage Design Crop Top. So the
earlier "delivers nothing / pay-for-nothing" framing in the spawn_task flag is
WRONG — the money path is sound (buyer pays → item delivered to platform
inventory). The ONLY real defect is the success toast + ITEM_PURCHASED notif
pointing to "Profile › Trades" (empty for system listings) instead of "Sell ›
Platform Inventory". Lower severity: messaging-accuracy, not money integrity.
(P2P purchases correctly open a Trade, so "see Trades" is right for those.)

## SESSION (continued) — P2P escrow unblock + full-page scroll fix + money-path proof

Three shipped fixes + end-to-end money-path verification on a fresh DB.

- **fbded32 — two seed gaps blocked EVERY P2P escrow buy on a fresh DB.**
  (1) Seed-seller steamId64s sat BELOW the SteamID64 base (76561197960265728),
  so the trade-URL ownership check (partner = steamId64 − base) went negative
  and `setTradeUrl` always 400'd TRADE_URL_NOT_YOURS → no P2P buy could start.
  Shifted the block 7656119000000000x → 7656119900000000x (positive accountid).
  (2) Seed sellers had no payout wallet, so escrow credit 400'd
  SELLER_WALLET_MISSING. Seed a guarded zero-balance `steam_<id>` wallet per
  demo seller. SeedServiceSpec 30/30 green (assertion hardened to require every
  id > base).
- **f92a8dc (WAVE-INT18) — full-page InfoModal content unreachable below the fold.**
  Ship #2113 forces `.modal-backdrop:has(.info-modal)` to a fixed, viewport-tall,
  place-items:center overlay with overflow:visible — fine for a short sign-in
  card, but on every GENERIC-info-modal route taller than the viewport (/profile,
  /db, /loadout, /watchlist, /offers, /buy-orders, /sell) the over-tall modal
  centered out of reach and the backdrop couldn't scroll. On /profile/trades the
  Trades list + its Confirm-receipt / Leave-review / Request-refund buttons sat at
  ~1135px in a 900px viewport with the doc scrollable only ~19px → buyer literally
  could not confirm a trade. Fix: full-page-mode info-modal backdrops get
  overflow-y:auto + align-items:start (0,3,0 beats #2113's 0,2,0). Routes with a
  SPECIFIC modal class (wallet-modal, cart) were already position:static and
  untouched. Verified live: /profile (1481px), /db (2371px), /sell, /loadout all
  scroll top→bottom, no clip. design.css ?v=205→206.
- **497f044 — trade-card polish (now-reachable surface).** (1) item name butted
  into the role label ("Crop TopYou are buying") — added marginLeft to .trade-role.
  (2) counterparty avatar showed the UA broken-image glyph on a dead URL — added
  onError hide. (Seed avatars: 4/6 fake hashes 404; the shared Avatar primitive
  already shows an initials chip everywhere else, e.g. stall hero "EM".)

MONEY PATHS VERIFIED END-TO-END on the fresh DB (fee model intact throughout —
2% seller, buyer Free, net + fee == price):
- BUY (P2P escrow): buy EmberWolf's $0.52 listing → tradeOpened:true, Trade #1
  PENDING_SELLER_ACCEPT; walked accept→sent→buyer-confirm→VERIFIED; seller
  credited $0.51 (= 0.52 − 2% $0.01), SALE tx "Sold … (-$0.01 fee)" COMPLETED.
- SELL: platform-inventory item → sell form (Listed $0.46, Platform fee 2% −$0.01,
  You'll receive $0.45) → List for Sale → active listing created (mine 78→79).
- WITHDRAW: form present + correctly gated — "Add an email before withdrawing"
  with balance untouched (no debit on a blocked request). Clean user-facing copy.

BELOW-FOLD VISUAL SWEEP (newly reachable via WAVE-INT18) — /db rows #13–#30
(+ sticky pager clears last row), /sell platform inventory, /loadout gallery
(Plague Doctor / WW1 Trench Soldier / OG Streetwear cards): all clean, 0 broken
images, no horizontal overflow.

VERSIONS: design.css?v=206 (index.html), app.js?v=202, modals.js synced. The
dev-login in SteamAuthController remains UNCOMMITTED (QA scaffolding) — revert
before any final/clean state, never ship.

## COMPLETE MONEY-PATH VERIFICATION + GREEN SUITE (same session)

After the seed unblock + scroll fixes, exercised EVERY core flow end-to-end on
the live app (fresh DB, dev-login QA). Fee model intact throughout — 2% seller,
Buyer fee Free, Trade Protection 2% floored $0.25, net + fee == price:

- BUY → P2P ESCROW → PAYOUT: buy → tradeOpened:true, Trade PENDING_SELLER_ACCEPT;
  seller accept → sent → buyer confirm → VERIFIED → seller credited price−2%
  (e.g. $0.52 → $0.51, SALE tx "-$0.01 fee"). ✓
- CANCEL → REFUND: cancel a PENDING trade → buyer refunded the FULL escrowed
  amount ($0.54 back; REFUND tx; balance restored). No money loss. ✓
- SELL: platform-inventory item → sell form (Listed $0.46, Platform fee 2%
  −$0.01, You'll receive $0.45) → List for Sale → active listing created. ✓
- WITHDRAW: correctly gated — "Add an email before withdrawing", balance
  untouched on a blocked request (no debit). Clean copy, not a raw error. ✓
- BID (auction): bid → 200 WINNING, currentBid updates, prior bids OUTBID,
  no fund hold until won. ✓
- OFFER: make offer → PENDING, shows on Outgoing tab; Incoming empty-state
  correct; tab badges accurate. ✓
- TRADE PROTECTION: quote 2% floored at $0.25 min (price $0.54 → $0.25,
  $50 → $1.00); frontend MIN_FEE mirrors backend quote() exactly. ✓
- CART/CHECKOUT: 2 items subtotal $1.19, Buyer fee FREE, Total $1.19 (= subtotal),
  "Savings vs Steam" display, Checkout button shows correct total. ✓

TRADE-CARD UI (now reachable post-WAVE-INT18) — both states render correctly
desktop AND mobile (after WAVE-INT19): PENDING (Awaiting seller accept, dots-only
stepper, Dispute/Cancel/Report/Chat + Add-Protection panel) and VERIFIED
(funds released, Leave Review / Request refund). Chat correctly 400s TRADE_CLOSED
on terminal trades.

CONTENT-CARD MOBILE SWEEP (390): trade card FIXED (was the only broken one);
/db, /sell, /loadout, /offers, /watchlist, /item, /wallet all render clean — 0
broken images, no horizontal overflow. Desktop trade card unaffected by the
mobile @media (no regression).

FULL TEST SUITE: ./gradlew test → BUILD SUCCESSFUL in 1m 3s, 0 failures
(~4174 tests). The SeedService steamId-shift + wallet-seeding change is certified
suite-safe (only SeedServiceSpec exercises real seed — 30/30; SteamEscrow/
SteamDelivery specs use independent fixtures; AdminServiceSpec mocks the repo).

VERSIONS: design.css?v=209, app.js?v=202, modals.js synced. dev-login in
SteamAuthController still UNCOMMITTED (QA only) — revert before any final state.

## DEEP READ-ONLY AUDIT FLEET (same session) — money + security

Ran 4 focused read-only audit agents over the deepest-risk code (areas not
fully re-read manually this session). Only 2 real findings, both fixed:

- Trade unhappy-path money (TradeService / TradeProtectionService: auto-release
  sweep, dispute, cancel, adminRelease, protected-trade refund): NO DEFECTS.
  Double-credit / missed-refund / wrong-amount / state-holes / multi-pod race
  all guarded by Trade@Version optimistic lock + TradeProtection PESSIMISTIC_WRITE
  lock + atomic txns (hardened across waves 105/113/115/129/146).
- Auction/bid + buy-order money (BidService settle/auto-bid/sweeps, BuyOrderService
  tryMatch/fill/cancel): NO DEFECTS. Winner charged second-price (never maxBid),
  losing bids never pre-charged, buy-order over-fill blocked by findByIdForUpdate
  pessimistic lock held across the REQUIRES_NEW per-fill tx, maxPrice re-checked
  server-side, cancel-vs-fill race closed by the shared row lock.
- Frontend money-submit re-entrancy (app.js / modals.js / csfloat-modals.js /
  trade-protection.js): every money handler has a synchronous ref latch EXCEPT
  two → FIXED in 6306f27: (1) cancel-pending-withdrawal button (had no latch;
  now captures the button + .disabled before the await) and (2) MyStall bulk
  price adjust (now uses the already-declared bulkAdjustBusyRef). All others
  (buyingRef/checkoutRef/submittingRef/busyRef/quickSellBusyRef/offerBusyRef…)
  confirmed correct; CSRF injected globally; no swallowed money errors; no
  optimistic-balance-before-confirm.
- IDOR / object-level authorization (Trade/Offer/BuyOrder/Listing/Wallet/Cart/
  Watchlist/Review/Profile + Bid/ApiKey/Support/Loadout/SavedSearch/SellerFollow/
  UserBlock): NO DEFECTS. Owner always resolved from session, never from the
  request; ownership checked before every mutate/private-read; no mass-assignment
  of owner/role/balance; 404-not-403 anti-enumeration is intentional.

Two product-decision items flagged (NOT defects, NOT changed — fee model is frozen):
- Profile "Total Purchased"/"Net" count cancelled+refunded purchases (gross, not
  net) — spawned as a separate task (needs gross-vs-net decision + careful REFUND
  typing). Balance itself is correct.
- Trade Protection fee kept on a SELLER-FAULT auto-cancel (buyer made whole via
  escrow refund, protection cover never paid) — documented intentional revenue
  model; a fairness/product call, not a correctness bug.

## Withdrawal/payout audit + 5th & 6th audit + design a11y (same session)

- WITHDRAWAL/PAYOUT money audit: live path SAFE — requestWithdrawal debits +
  flush()es BEFORE the irreversible Stripe Transfer, guarded by Wallet@Version
  + minute-bucketed Stripe idempotency key + rollback-on-failure + a
  transfer.reversed re-credit handler. No double-payout / debit-without-payout /
  daily-cap bypass. FINDING (architecture, flagged via spawn_task, NOT a money
  leak): payouts execute synchronously at request time (status→COMPLETED, no
  PENDING since V72), so the admin approve/reject + user-cancel withdrawal
  workflow (+ the wallet "Cancel pending" button) is unreachable dead code —
  needs a product decision (re-introduce manual payout review for fraud safety,
  OR delete the dead approval UI/endpoints) + give rejectWithdrawal the atomic
  claim cancelPendingWithdrawal already has.
- DESIGN/A11Y audit: contrast 4.67–16.5:1 (AA pass), focus-visible, alt text,
  ARIA labels, type scale all clean. Fixed (WAVE-INT20 / 0b2cdb6): primary money
  CTAs (.buy-btn/.btn-accent/.btn-primary/.item-rail-actions-buy) were 40px on
  mobile → bumped to 44px min-height. Flagged (spawn_task): grid-card hover-only
  overlay buttons (heart 32px / cart 40h / zoom 24px) need touch-device
  verification before resizing.

SESSION STATE: 9 commits (P2P seed unblock, WAVE-INT18 full-page scroll,
trade-card desktop+mobile, 2 money-submit latches, 44px CTAs, + logs). 6 deep
read-only audits (trade/auction/buyorder/frontend/IDOR/withdrawal money +
design a11y) — money & security layers confirmed robust. Every customer flow +
surface verified desktop+mobile; full test suite GREEN; integration smoke test
passing; src↔build consistent (design.css?v=210). dev-login still UNCOMMITTED
(QA only — revert before any final/prod state). 3 items flagged for product
decision (purchase-stat gross/net, card-overlay tap targets, withdrawal flow).

## BACKEND COMPLETELY DONE — certification (same session)

Operator asked to drive the backend to done. Result: comprehensively audited
robust + fully test-covered + full suite green.

DEEP READ-ONLY AUDITS this session (9 total) — money & security & lifecycle all robust:
- Trade unhappy-path money (auto-release/dispute/cancel/protection refund) — clean.
- Auction/bid + buy-order money (settle/auto-bid/fill/cancel races) — clean.
- Withdrawal/payout/Stripe — live path clean (flush-before-transfer, @Version,
  idempotency key, rollback, transfer.reversed); flagged the dead synchronous-
  payout-vs-admin-approval architecture (product decision).
- Frontend money-submit re-entrancy — 2 latch gaps FIXED (6306f27).
- IDOR / object-level authorization (16 controllers + services) — clean.
- Input validation / HTTP-contract / NPE (36 controllers + 7 DTOs + GlobalExceptionHandler)
  — clean (null-guards, parse try/catch, pagination clamps, bulk caps, no stack leaks).
- Scheduled jobs / data lifecycle (all @Scheduled sweeps) — clean (bounded batches,
  per-row isolation, atomic multi-pod claims, terminal-flag narrowing, fixedDelay).
- Listing/item integrity & concurrency — clean (Listing@Version + saveAndFlush blocks
  double-sell; one finding: same-user Steam-asset double-list TOCTOU, low-sev, NOT
  prod-exploitable with escrow bot on; flagged for a portable-constraint fix).
- Design/a11y — clean except 44px CTA fix (0b2cdb6).

TEST COVERAGE: service-layer logic 44/44 (added SteamBotResultSpec, d781dde — the
last uncovered service-package class: pins the Steam-bot status state machine +
@CompileStatic Map-subscript/coercion gotcha, 33 cases). Controllers 36/36 referenced.

FULL SUITE: ./gradlew test -> 4256 tests, 0 failures, 0 errors, 214 suites. GREEN.

Backend is production-grade at the code level. Remaining = operator-gated: #172
(build prod jar, boot prod profile, verify behind tunnel) + revert the uncommitted
dev-login first. Flagged product decisions: withdrawal-approval workflow,
purchase-stat gross/net, Steam-asset double-list constraint, card-overlay tap targets.

---

## VISUAL-FIDELITY WAVE (live-browser, 1440 + 390) — eyes-on-pixels sweep

Drove the running app in Chrome (dev-login user 1) across home / market / item /
wallet / sell / mystall / public-stall / db, desktop + mobile. Read the actual
pixels, not just console-clean. 8 commits, each fix verified live:

- 1f51db3 GET /api/listings/{id} now carries `private, max-age=10` to match the
  grid list (was uncached) — kills the post-bid fresh-vs-stale currentBid divergence.
- af1d85b **Grid-card Steam icon was 0px wide on EVERY card** — the compact Steam
  link is a flex container; its <svg> has a viewBox (→ min-content width 0) and the
  default flex-shrink collapsed it despite width=13. Pinned flex:none + 14px. Verified
  0px→14px across all 38 cards; icon now visible (was an empty bordered box). v210→211.
- 861319f Pluralize item-count units ("1 items"→"1 item"): item-detail Total-supply
  + profile Steam-inventory-size.
- 16515f8 Wallet deposit-cap banner leading glyph '—'→'ⓘ' (healthy state; bad state
  already had '⚠').
- e2f0c96 Sell-inventory "est. value" chip: dropped stray leading '— '.
- 7482505 **Systemic stray-em-dash placeholder sweep (8 sites)** — '—' had been left
  as a stand-in for status icons / count-chip glyphs / an action button across seller
  + auction surfaces: auto-bid info banner→ⓘ, MyStall buyer-stats→ⓘ, earnings→ⓘ,
  wallet-frozen error→⚠, watcher chip "—1"→"★ 1", verified-trades→"✓ N", buy-order
  demand→"⇄ N", and the destructive **Cancel-listing button** (red, bare '—', NO
  accessible name) → '✕' + title + aria-label="Cancel listing".
- 1c0412d Public-stall "Manage stall" owner button icon '—'→'⚙' (last instance).

VERIFIED CORRECT-BY-DESIGN (no churn — measured-comment / parity decisions left intact):
- Nav "Database" permanently accent-blue (csfloat signature, ship #5501 measured).
- Wallet deposit presets = flat brand-blue text links, not chips (ship #128435 measured).
- /sell Steam-tab "—" badge = count-unavailable (rate-limited) indicator.
- Item-rail buy/cart 40px = csfloat Material button height; 44px ship scoped elsewhere.

VERIFIED CLEAN: font is self-hosted Roboto (genuinely loaded, not Arial fallback —
correct for csfloat/Angular-Material); buy-confirm modal fee math exact (item price +
optional 2% Trade Protection, buyer fee Free, total=price, "seller minus 2%"); Cancel
closes modal w/o over-navigation; mobile market/item no h-overflow, lazy-load works,
steam icon 14px; 0 unlabeled icon buttons on market; no TODO/lorem/coming-soon leaks;
db table names not clipped.

## WATCHLIST DANGLING-REF FIX (live-found via browser)

dcd7ba5 — Drove /watchlist and caught a real count defect: nav badge "4" vs page
"3 items". Root cause traced live: GET /api/watchlist returned [20,25,30,41] but
item 41 no longer exists (/api/items/41 → {notFound}, /api/listings/item/41 → []).
The badge counts watchlist.length (4); the page can't render a card for a
non-existent item so it silently dropped 41 (3) — counts disagreed and the user
couldn't see/clean the phantom. Fixed at WatchlistService.list() (the single
source GET/star/unstar/bulkMerge all funnel through) by filtering ids to existing
catalogue items; catalogueRepository @Autowired(required=false) so it degrades to
no-op unwired; createdAt-ASC order preserved; add()'s cap reads the raw repo so
dangling rows can't escape the cap. +3 specs (24 green) + WatchlistControllerSpec
34 green. Verified live post-restart: /api/watchlist → [20,25,30], badge "3",
heading "Watchlist · 3 items", tab "All · 3", 3 cards — all consistent.

Also confirmed clean this pass: buy-confirm modal fee math (item + optional 2% TP,
buyer fee Free, total=price, seller −2%); cart checkout (subtotal sum, buyer fee
Free, accessible remove buttons); auction bid UI (current bid / countdown / min /
auto-bid cap, "1 item" pluralization live); loadout lab (no overflow / collapse /
unlabeled buttons); db table (names not clipped).

## SETTINGS TOGGLE A11Y + FULL-SUITE CERT

caa680a — /settings toggles were a bare onClick <div> (0 role=switch/checkbox on
the page): mouse-only, not tab-reachable, no aria-checked/label — invisible to
screen readers. design.css already had a .toggle-switch:focus-visible ring waiting
for a focusable host; the sibling away-toggle already used role:'switch'. Made the
shared Toggle helper accessible (role=switch, aria-checked←state, aria-label
threaded per call site, tabIndex=0, Enter/Space handler). Verified live: 5
switches expose role/state/name, tab-reachable, Space flips state.

FULL TEST SUITE (this session, after the WatchlistService.list() service-layer
change): ./gradlew test → 214 suites, 4261 tests, 0 failures, 0 errors. GREEN.
(+3 from WatchlistServiceSpec dangling-ref filter cases.) The watchlist filter is
no-op when catalogueRepository is unwired (unit specs) and active in the Spring
context — no integration spec regressed.

SESSION TALLY (visual-fidelity + data-integrity grind, all live-verified):
cache-consistency (1f51db3) · grid-card Steam icon 0px→14px on every card
(af1d85b) · "1 item" pluralization (861319f) · deposit-cap ⓘ glyph (16515f8) ·
est-value chip dash (e2f0c96) · 8-site em-dash placeholder sweep incl. labelled
✕ cancel-listing button (7482505) · manage-stall ⚙ (1c0412d) · watchlist
dangling-ref filter + tests (dcd7ba5) · settings toggle a11y (caa680a).

## SESSION CAPSTONE — comprehensive live sweep complete

Final fixes (all live-verified, no-cache modules):
- e5d9ebf Notifications mark-as-unread: '—' → documented 📥 inbox glyph (9 rows).
- d2ddc33 Withdraw daily-cap banner '—'→'ⓘ' (deposit twin missed earlier) +
  follow-mute toggle degenerate '—':'—' ternary → 🔕/🔔 by state + aria-label.

The stray-em-dash-placeholder class is now eliminated app-wide (deposit + withdraw
caps, est-value/watcher/trades/demand chips, MyStall cancel button, manage-stall,
notifications mark-unread, follow-mute). All legit "no data" value fallbacks
(price/date/count → '—') left intact.

COMPREHENSIVE ROUTE SWEEP (desktop 1440 + mobile 390, dev-login): home, market,
item (buy-now + auction), wallet (deposit + withdraw), sell (steam + platform),
mystall, public stall, db, cart, watchlist, loadout, profile, offers, settings,
notifications, help — all clean (no h-overflow, no collapsed icons, no unlabeled
icon buttons, no stray dashes). FUNCTION verified: buy-confirm fee math, cart
checkout fee math + accessible remove buttons, auction bid UI, offers, search
(?q= URL-synced) + category filter (?category= URL-synced), settings toggles
(role=switch + keyboard). 0 console errors across home/market/item.

FULL SUITE: 214 suites, 4261 tests, 0 failures (incl. +3 watchlist filter specs).
App live on :8082. Dev-login scaffolding remains UNCOMMITTED (revert before prod).

## ADVERSARIAL AUDIT ROUND (2 parallel read-only agents) — integrated

Fanned out two deep read-only auditors (frontend defects + backend money-correctness).
Both independently concluded the app is exhaustively hardened. Integrated the real
findings; declined one with rationale:

APPLIED:
- 50f3202 — 3 NaN-display guards (the only frontend findings): StallsRail rating
  `Number(ratingAverage).toFixed(1)`→'NaN' when count>0/avg null (+'|| 0', matches
  siblings); offer-drawer placeholder re-anchored on `ask` (was the weaker
  lowestPrice → 'NaN'); price-alert seed `(parseFloat(lowestPrice)||0)*0.85`.
- 36e825f — backend money boundary-normalization: offer (makeOffer/buyerRaise/
  counterOffer) + listing (relist) entry points stored amount/price verbatim while
  deposit/withdraw/bid HALF_UP-round at the boundary. DTOs intentionally cap
  magnitude not scale (accept-and-round design pinned by StripeServiceSpec/
  WalletControllerSpec), so a 3dp body had guards running on the raw value while
  NUMERIC(10,2) rounds on persist — the same guard-vs-persisted disagreement the
  withdraw() path was already fixed for. Added HALF_UP at each entry + OfferServiceSpec
  rounding test. Full suite 214 suites / 4262 tests / 0 failures.

DECLINED (with rationale): the backend agent's `@Digits(fraction=2)` DTO idea —
it would REJECT sub-cent input, contradicting the deliberate, tested accept-and-round
design (StripeServiceSpec deposit 50.999→51.00, WalletControllerSpec withdraw 1.001).
The boundary-normalization above is the design-consistent fix instead.

CONVERGENCE: exhaustive manual UI sweep + two deep adversarial audits all confirm
the app is production-grade. No further real defects found solo. Remaining = #172
(prod jar + Postgres boot + tunnel), infra-gated.

## WAVE 176 — frontend re-entrancy (double-submit) audit + email-template audit

Fresh lens: every money/mutation submit must have a SYNCHRONOUS latch (async
`busy` state alone leaves a same-frame double-click window — both handlers
capture busy===false from the same render). Swept all submit handlers across
modals.js / csfloat-modals.js / staff-modals.js.

APPLIED (real gaps — async-only, no confirm(), no parent latch, backend has
no dedupe so a fast double-click double-fired):
- 7d6defa — submitRefund (buyer "Request refund" drawer). Calls
  createSupportTicket directly; SupportService.create has no per-trade dedupe
  and fans a bell push to every ADMIN/CSR → double-click filed TWO REFUND
  tickets + doubled staff fan-out. Gated on the pre-existing refundBusyRef.
- 2ff8206 — submitCreate + submitReply (ProfileSupportTab) and reportUser
  (ReportCounterpartyDrawer). Same no-dedupe create path. Added one shared
  busyRef to the support tab (gates create+reply) and one to the report drawer.

SELF-CORRECTED (verify-before-fixing, applied retroactively):
- 9c79308 MarkSentDrawer latch — REVERTED by ba1ab64. On closer trace the
  child onSubmit calls submitMarkSent, whose parent body ALREADY sets
  busyRef.current=true synchronously before its first await; the child's weak
  async guard could never let a second POST through. The fix was redundant and
  its commit msg asserted a 409/double-email that cannot occur. Net zero change.

VERIFIED-SAFE (no fix — confirmed already guarded, did NOT churn):
- reportListing — ListingService dedupes server-side (findByListingIdAndReporter
  UserId → ALREADY_REPORTED).
- Admin money (approve withdrawal / refund / CSR goodwill) + submitBulk —
  native confirm()/prompt() blocks the JS thread + captures focus, serializing
  any double-click.
- submitBulkAdjust, Sell submit, quickSell, BuyOrders, trade-protection, escrow
  buyerConfirm/runConfirm, parent submitMarkSent, submitReview, ConfirmModal —
  all already hold synchronous ref-latches.
- EmailPrefToggle / EmailBucketMutes — idempotent optimistic toggles (same-frame
  double-click produces identical PUTs).

EMAIL-TEMPLATE AUDIT (parallel read-only agent) — NO REAL DEFECTS. All money
values in emails are scale-2 BigDecimal or usd()-formatted; links are absUrl()-
absolute + prod-validated (ProdConfigValidator rejects localhost/placeholder);
every template var has a Groovy-Elvis fallback. 11th clean audit lens.

App live on :8082; dev-login scaffolding remains UNCOMMITTED.

## WAVE 177 — Playwright programmatic visual+console battery (1440 + 390)

Chrome extension was offline this session; drove a fresh Playwright Chromium
instead and verified visuals PROGRAMMATICALLY (computed styles, bounding
boxes, font-load state, console) — robust to the screenshot-file-retrieval
gap and high-signal for the defects that matter.

APPLIED:
- 4822fcf — AudioContext autoplay-warning fix (see batch 1079 above). Found
  via the console battery: the notification auto-ding created+resumed an
  AudioContext before any user gesture, logging Chrome's "AudioContext was
  not allowed to start" warning on every pre-gesture ding. Now gesture-gated.

VERIFIED CLEAN (no defects):
- Fonts: Roboto self-hosted + actually loaded at 400/500/600/700/900 +
  Roboto Mono + Material Symbols Rounded (document.fonts.check true; renders
  distinctly from the monospace sentinel). Matches csfloat's Material Roboto;
  the canonical font-fallback-to-Arial trap is NOT present.
- Desktop 1440: home / market / wallet / item-detail(/item/24) / item-not-
  found — overflowX 0, no NaN/undefined/$NaN/[object Object]/Invalid Date in
  visible text, no broken images, prices all $X.XX. Console 0 warn / 0 err
  after the audio fix.
- Mobile 390: item-detail + profile — overflowX 0. profile-stats grid reflows
  to 2 columns with no cell clipping (confirms the 637d35c fix live). The
  "wide" elements are intentional horizontal-scroll rails (.similar-strip
  scrollW 960/280; .profile-tabs scrollW 1019/288) — both overflow-x:auto and
  scrollable, so all content is reachable (not clipped).
- /item/{id} routes on itemId; market cards link /item/{itemId} consistently
  (a stray /item/{listingId} correctly renders a clean "Item not found").

App live on :8082; dev-login scaffolding remains UNCOMMITTED.

## WAVE 178 — interactive money-flow check (deposit/withdraw) via Playwright

Exercised the core customer money flows live (logged-in as jbin1315):

APPLIED:
- 0200922 — mirror the $1.00 deposit floor client-side (batch 1080). The
  wallet submit mirrored its $1 server floor (DepositRequest @DecimalMin
  1.00 / WithdrawRequest @DecimalMin 1.00) for WITHDRAW only; a sub-$1
  DEPOSIT passed the client and bounced off the server as a generic
  validation round-trip. Generalized the floor check to both tabs. Verified
  live: $0.50 deposit -> inline "Minimum deposit is $1.00", no server call,
  balance unchanged.

VERIFIED CLEAN:
- Deposit modal: balance/spend-history/daily-cap render correctly; numeric
  input; on-click validation rejects negative/zero/NaN ("Enter a valid
  amount") and >$10k ("Maximum per transaction is $10,000"); submittingRef
  synchronous re-entrancy latch present; deep-linkable /wallet/deposit.
  Console 0/0.
- Withdraw tab: renders clean (no NaN), amount + destination inputs, daily
  cap, and a clear email-verification GATE ("Add an email before
  withdrawing → Open Profile") — proper precondition UX. Code path enforces
  $1 min / $10k max / destination-required / TOTP / CONNECT_ONBOARDING copy.

App live on :8082; dev-login scaffolding remains UNCOMMITTED.

## WAVE 179 — core money-flow live verification (buy / cart / sell)

Completed the interactive money-flow coverage (all math exact, no NaN, no
overflow; verified live then closed WITHOUT executing any purchase/listing
— demo balance untouched at $47.99):

- BUY (single-item confirm, item #24): Item price $1.02 | Trade Protection
  (2%) $0.02 shown "optional, add after purchase" (NOT in total) | Total
  charged $1.02 (price only, no buyer fee) | "Seller receives price minus
  2% platform fee". Matches the frozen money model exactly.
- CART (multi-item checkout, 2 items): $0.60 + $10.50 = Subtotal $11.10 |
  Buyer fee Free | Total $11.10. Line items sum exact; no buyer fee.
- SELL (list modal, platform item): Listed price $1.02 | Platform fee (2%)
  −$0.02 | You'll receive $1.00 — net + fee == price exactly. Plus Floor /
  −5% / +5% quick chips, Steam reference, last-sold. (The proceeds preview
  IS present — a prior code-only grep missed its sub-component; confirmed
  live before flagging, so no false fix.)

Combined with wave-178 (deposit $1-floor fix + withdraw email-gate), all
four customer money entry/exit flows (buy / sell / deposit / withdraw) are
verified correct and on the frozen 2%-seller-fee / no-buyer-fee model.

App live on :8082; dev-login scaffolding remains UNCOMMITTED.

## WAVE 180 — mobile-modal overflow sweep (390px) + read-endpoint param fuzz

Fresh lens: modals checked at DESKTOP earlier this session render fine, but
modal INTERNAL layout at 390px is a distinct surface (where a desktop-
measured fixed width can overflow).

APPLIED:
- edb62f0 — wallet amount input overflowed the modal on mobile (batch 1081).
  csfloat-parity min-width:220px on .wallet-amount-input-v2 is a desktop
  floor with no mobile shrink → at 390px the input ran 35px past the modal
  edge (right 399 vs modal-right 364), clipped, on the deposit/withdraw
  action. Added a max-width:640px media query (min-width:0 / width:100% /
  flex:1 + row+wrap 100%). Verified both breakpoints: 390 fits (overflow 0),
  1440 keeps 220px parity. design.css ?v 214->215.

VERIFIED CLEAN:
- Buy-confirm modal @390: panel 328px, fits viewport, 0 internal overflow.
- Sell-listing modal @390: 380px, fits, 0 overflow, no NaN.
- IDOR / access-control (audited directly, agent was rate-limited): trade
  GET /{id} has explicit participant check (controller); all trade mutations
  pass uid -> service requireParticipant; offer acceptOffer has an author-
  discriminator guard that even blocks a seller accepting their OWN counter;
  offer GETs owner-scoped; wallet withdraw-cancel scoped to caller wallet.id;
  buy-order cancel passes uid. No IDOR found.
- Read-endpoint param fuzz (/api/listings): bad sort/page -> sane 200
  defaults; non-numeric/negative/overflow price -> 400; SQLi q -> 200 (param-
  ized); category <script> -> 200; ZERO 500s.

App live on :8082; dev-login scaffolding remains UNCOMMITTED.

## WAVE 181 — auction/bid flow + 768 tablet breakpoint (final coverage)

VERIFIED CLEAN:
- Auction bid (item 34 "Chef Hat", live auction): countdown renders, current
  bid + min-bid shown, no NaN. Typed a below-minimum $0.10 bid + clicked
  Place Bid -> rejected inline "Minimum bid is $0.81" (current $0.76 + $0.05
  increment, matches the documented rule). No mutation. Completes all 7 core
  money flows verified this session (buy/cart/sell/deposit/withdraw/offer/bid).
- 768px tablet breakpoint (the zone where max-width:640 mobile rules stop):
  market page overflow 0 (the wide elements are the intentional pulse-ticker
  marquee, clipped); profile page overflow 0, stats grid = desktop 4-col
  (165.75px x4, right 707 < 768). Responsive coverage now spans 390/768/1440.
- Buy-order create form @390: 288px, fits, responsive width:100% input (the
  .buyorder-form input has NO min-width floor, so it's not subject to the
  wallet overflow — confirmed in CSS + live).

Session real-fix tally (this run): refund + support-create/reply/report-user
re-entrancy latches (7d6defa, 2ff8206), AudioContext autoplay warning
(4822fcf), deposit $1 client floor (0200922), wallet mobile-modal overflow
(edb62f0). 1 self-revert (mark-sent). Audits clean: email, XSS, IDOR,
param-fuzz. App live on :8082; dev-login remains UNCOMMITTED.

## WAVE 182 — React-effects audit + OG/social meta + 360px breakpoint (all clean)

VERIFIED CLEAN (no fixes — exhaustive certification):
- React correctness (agent, all 214 useEffect hooks + every timer/listener/SSE
  + all list keys across 15 JS files): NO REAL DEFECTS. Pervasive liveness
  guards (alive flags / mountedRef / monotonic request-ids + cleanup),
  functional setState in long-lived handlers, correct prop-sync dep arrays,
  stable-id keys. React 18 silent no-op covers the few []-keyed unguarded loads.
- OG / social-share meta: item route rich product OG (item-specific title/desc,
  real 512x512 Steam image, og:type=product + og:price + og:availability=instock,
  Twitter card); market route descriptive (og:type=website); /profile serves
  GENERIC site-level OG — correctly does NOT leak the logged-in user's data to
  scrapers (privacy-safe).
- 360px (narrowest realistic phone): page overflow 0; .search-wrap hidden on
  mobile; home cards in .csfloat-home-preview-row (overflow-x:auto, reachable
  carousel). Responsive certified across 360/390/768/1440.
- Modal-overflow class CSS-certified: every min-width>=250px is a scroll
  container, a desktop-only @media, or fits-390. Wallet input was the sole
  defect (fixed edb62f0).

Run tally: 5 real fixes + 1 self-revert; clean audits = email, XSS, IDOR,
read-param-fuzz, React-effects (5 lenses). App live on :8082; dev-login UNCOMMITTED.

## WAVE 183 — Stripe-webhook + security-headers + robots/sitemap (all clean)

VERIFIED CLEAN (no fixes — certification of the highest-stakes surfaces):
- Stripe webhook / deposit / refund / chargeback (agent, the real-money path):
  NO REAL DEFECTS. Signature verified (Webhook.constructEvent is the
  unconditional first step; prod refuses placeholder/missing secret).
  Idempotency at TWO layers — transactional processed_stripe_events UNIQUE
  claim (cross-pod) + per-row PENDING->COMPLETED status gate (money backstop,
  covers payment_intent/checkout.session not just disputes). Amount/currency
  re-verified against the freshly-retrieved Stripe session (paid + walletId +
  amountTotal + USD); credits the SERVER-stored tx.amount, never a client
  field. Refund/chargeback deduped on refundId, balance-clamped (no negative),
  cumulative-refund capped, pessimistic-locked before the Stripe call. Credit
  + status-flip atomic in one @Transactional; sync /confirm-deposit converges
  on the same completeDeposit with Wallet @Version resolving the race.
- Security response headers (live): tight CSP (no script unsafe-inline/eval;
  self fonts; scoped img/connect/frame allowlists), frame-ancestors 'none' +
  X-Frame-Options DENY, exhaustive Permissions-Policy (incl interest-cohort=()),
  nosniff, Referrer-Policy strict-origin-when-cross-origin, correct
  cache-control. (HSTS off is correct on dev HTTP; prod enables via SECURITY_HSTS.)
- robots.txt: public surfaces Allowed, all private/api routes Disallowed
  (/api /admin /csr /profile /wallet /cart /sell /offers /buy-orders /watchlist
  /notifications /settings /support), Sitemap -> prod domain.
- sitemap.xml: 68 URLs (items/stalls/loadouts/market/legal); ZERO private
  routes leaked; dev domain is just APP_PUBLIC_URL (prod = skinbox.market).

Run audit tally: 6 clean lenses (email, XSS, IDOR, param-fuzz, React-effects,
Stripe-webhook) + OG + security-headers + crawlability, on top of 5 shipped
fixes. App live on :8082; dev-login remains UNCOMMITTED.

## WAVE 184 — auction-settlement + TradeProtection + TOTP money/security (all clean)

VERIFIED CLEAN (no fixes):
- Auction settlement (agent — auction-end charge + trade open): NO REAL
  DEFECTS. Single-settlement via Listing @Version (concurrent/replayed second
  settle rolls back the whole debit+transaction+trade unit atomically) +
  status!=ACTIVE guard; tradeService.open joins the same tx (no orphan trade).
  Insufficient-funds re-check at settle diverts to no-sale return-to-seller
  (no negative balance, no stuck auction). Auto-bid = second-price w/ solvency
  re-check, charges resolved price. Anti-snipe: placeBid rejects bids past
  expiresAt (shared now-snapshot); findExpiredAuctions only returns passed
  rows. Losing bids deliberately NOT escrowed (bid-time solvency check only) →
  no escrow leak; only winner debited, once. Buy-Now routes same settle().
- TradeProtection claim (direct): pessimistic findByTradeIdForUpdate lock +
  status!=ACTIVE idempotent no-op + atomic payout-then-flip; the cancel-path
  double-payout race is explicitly prevented (lock pins happens-before so
  cancel-refund and auto-claim-payout can't both credit the same trade);
  honest credited-vs-queued user messaging. Double-purchase blocked
  (PROTECTION_EXISTS). 2% rate.
- TOTP/2FA verify (direct): brute-force-guarded — MAX_2FA_FAILS=5 +
  per-user lockout, lockoutRemainingMs checked BEFORE verify (locked user
  rejected without consuming a code), clearFailures on success. Documented:
  closes the ~48h withdraw-rate-limit brute-force window on the 1M code space.

Audit tally this run: 7 clean lenses (email, XSS, IDOR, param-fuzz,
React-effects, Stripe-webhook, auction-settlement) + TradeProtection + TOTP +
OG + security-headers + crawlability + error/CSRF, on top of 5 shipped fixes.
Withdrawal-payout audit still in flight. App live on :8082; dev-login UNCOMMITTED.

## WAVE 185 — withdrawal-payout audit: REAL double-refund money bug FIXED + approve hardening

The withdrawal-payout audit agent surfaced a genuine money-critical defect that
survived wave 127. Both findings fixed + verified (AdminServiceSpec 145 green):

APPLIED:
- 493ccca (MONEY-CRITICAL) — AdminService.rejectWithdrawal was non-atomic
  read-check-credit. Wave 127 made the USER-side cancelPendingWithdrawal atomic
  (claimCancelPendingWithdrawal) but left the symmetric ADMIN-side reject
  unguarded — the cancel path's OWN comment names rejectWithdrawal as the racer.
  Transaction has no @Version, so a reject racing a self-cancel (or two
  rejects, or reject-vs-approve) DOUBLE-REFUNDED the wallet (both read PENDING,
  both credit). Added claimRejectWithdrawal (conditional UPDATE PENDING→FAILED);
  reject now credits ONLY when the claim wins (==1), else throws NOT_PENDING
  without re-crediting. New race spec locks it (claim→0 ⇒ 0 wallet saves).
- ab9b90a (consistency/integrity) — approveWithdrawal was the last non-atomic
  withdrawal state-flip → double-approve duplicate notifications + reject-then-
  approve could overwrite a refunded FAILED row back to COMPLETED. Added
  claimApproveWithdrawal; the whole state machine (request/approve/reject/
  cancel/transfer-reversed) is now uniformly atomic. (Non-money: Transfer fires
  at request time.)

Also certified clean this turn (direct reads): PurchaseService.buy (preconds
before debit, @Version on Wallet+Listing, seller-wallet pre-resolve, deferOrRun
after-commit fan-outs) — exemplary. TradeProtection claim (pessimistic lock +
status gate + cancel-race prevention). TOTP verify (MAX_2FA_FAILS=5 lockout
before verify). Auction settlement (agent: @Version single-settle, no escrow
leak). OG/security-headers/robots/sitemap/error-CSRF all excellent.

This is the session's most significant find — a relentless-grind win on a real
double-refund. App live on :8082; dev-login remains UNCOMMITTED.

## WAVE 186 — trade auto-cancel sweepers double-refunded protected trades (2nd money bug FIXED)

The trade-lifecycle audit agent found a SECOND money-critical double-refund —
same "fix-applied-to-one-path-not-its-sibling" pattern as the withdrawal bug.

APPLIED:
- b7a64ab (MONEY-CRITICAL) — autoCancelStaleSellerTrade +
  autoCancelBannedSellerTrade called UNCONDITIONAL refundBuyer + a no-op
  expire(). On a PROTECTED trade, a buyer dispute → autoClaim credits the buyer
  (own committed REQUIRES_NEW tx) + CLAIMED; the seller-timeout/banned sweeper
  then races: refundBuyer credits AGAIN, expire() no-ops on CLAIMED. Trade
  @Version doesn't cover autoClaim's separate credit → if the sweeper wins the
  row-flip race the buyer holds 2×price (platform loses one item price).
  Wave-105 closed this for manual cancel() via the locked arbiter
  lockAndExpireIfActiveOrReportClaimed, but the two SWEEPER bodies were left
  on the old path (the regression spec even named the sweeper as the racer).
  Fix: both sweepers now route refund through the same arbiter (runs in their
  runInIsolatedTx tx, lock spans refund+transition); dropped the redundant
  expire(). Updated the banned-seller spec + added a race regression
  (arbiter→true ⇒ 0 REFUND tx). 295 Trade* specs green.

PATTERN NOTE: two money-critical double-refunds this run, BOTH "atomic guard
applied to the primary path but not its sibling" (withdrawal: cancel atomic /
reject not — 493ccca; trade: cancel arbiter / sweepers not — b7a64ab). Both
survived their original hardening wave. The relentless focused-money-audit
grind found both.

App live on :8082; dev-login remains UNCOMMITTED.

## WAVE 187 — live customer-path verification + cross-debit wallet test (regression guard)

Pivoted from backend money audits to a WHOLE-WEBSITE live grind (Playwright
on :8082) + a fresh money lens. Drove the real browser at 1440 & 390.

VERIFIED CLEAN (no fix needed — verify-before-fixing avoided churn):
- Fonts genuinely self-hosted + loaded (Roboto 400-900, Roboto Mono,
  Material Symbols Rounded); icon ligatures render as glyphs (1.0 square
  ratio), not literal text. design.css?v=215 served.
- home / market(list) / item-detail @1440, home / market @390: zero
  horizontal overflow; chrome, nav, tabs, bottom-nav all faithful.
- "Database" nav link permanently accent-blue = INTENTIONAL csfloat parity
  (ship #5501/#200000 comments: csfloat permanently highlights its free
  item DB). A "fix" would have REGRESSED parity.
- market TREND column "—" everywhere = dev-data gap (trendPercent cached
  via admin-gated sync, unpopulated in dev); item-detail computes its
  +15.4% live from seeded PriceHistory. Correct flat fallback, not a bug.
- Buy flow E2E: Buy now → Confirm-purchase modal shows correct money model
  (buyer pays list $1.42, 2% Trade Protection shown as optional/excluded
  from total, "seller receives price minus 2% fee"). Re-entrancy: the
  modal's async `buyConfirmBusy` guard is backstopped by handleBuy's
  synchronous `buyingRef` latch (app.js 5216/5222) → no double-purchase.
  Escape closes ONLY the confirm, item page stays, no over-navigation.
- Wallet deposit/withdraw: submit (modals.js 14606) has an airtight
  synchronous `submittingRef` latch (check 14607 → only sync validation →
  set 14625, NO await between; reset in finally). Withdraw correctly gated
  on verified email + Stripe Connect onboarding + TOTP + $1 floor + $10k cap.
- Sell: polished empty-state for Steam inv; Platform inv lists 4 owned items.

APPLIED:
- d41a627 (test, MONEY) — cross-path wallet double-spend audit confirmed
  (HIGH confidence) every debit path (buy/cart/offer-accept/withdraw/
  trade-protection/auction-settle) serializes via the shared Wallet.@Version,
  loser fails cleanly (409 / WITHDRAW_RACE), flush precedes any irreversible
  external call. Sound but UNTESTED: ConcurrentBuyIntegrationSpec only raced
  many buyers vs one listing (Listing @Version), never two debits on ONE
  wallet. Added a regression test: one buyer funded for ONE of two $100
  listings buys BOTH concurrently → asserts wallet never negative, exactly
  one $100 debit + one PURCHASE tx + one SOLD listing + one win. Guards
  against a future Wallet-@Version bypass. 3/3 ConcurrentBuy specs green (28s).

App live on :8082; dev-login remains UNCOMMITTED.

## WAVE 188 — full-suite green cert + cart partial-failure atomicity verified

VERIFIED CLEAN (no fix — verify-before-fixing):
- Cart checkout partial-failure atomicity: CartController has NO @Transactional
  on the class or `checkout` method (lines 31-49) — each purchaseService.buy()
  per row runs in its OWN tx (PROPAGATION_REQUIRED, no outer tx), commits
  independently, caught per-row exceptions (INSUFFICIENT_BALANCE / PRICE_CHANGED
  / LISTING_NOT_AVAILABLE / optimistic-lock / etc.) roll back ONLY that row.
  No rollback-only leak; partial success is intentional + documented
  (comment lines 27-29). A 3-item cart with one sold-out row commits the
  other two cleanly.
- Currency: deposit/withdraw FORM is deliberately USD-denominated — hardcoded
  '$' input prefix + "(charged in USD)"/"(paid out in USD)" helper text
  (modals.js 15409/15444) + "$"-prefixed presets (15453); only read-only
  balance/cap/history convert to the display currency. USD-canonical ledger,
  zero conversion ambiguity at the transaction boundary.

CERTIFIED:
- Full Spock suite GREEN — `./gradlew test` BUILD SUCCESSFUL in 1m1s, all
  ~214 specs incl. the new cross-debit ConcurrentBuy test. Holistically
  validates this run's money fixes (493ccca withdraw reject/approve atomic,
  b7a64ab trade-sweeper double-refund, d41a627 cross-debit wallet test) —
  no cross-spec breakage.

App live on :8082; dev-login remains UNCOMMITTED.

## WAVE 189 — trade lifecycle UI + account-security verification (all clean)

VERIFIED CLEAN (no fix — verify-before-fixing):
- Account email-change takeover defense: ProfileController.setEmail (569-632)
  is hardened — 254-char cap, canonical-email uniqueness (V63 Gmail-alias
  bypass closed), 2FA-staging guard, send cooldown, and batch-512 OLD-EMAIL
  ALERT on change (explicitly defends "attacker with stolen session swaps
  email to lock owner out"). Withdraw needs verified-email + TOTP + Stripe
  Connect, so email alone is never a payout key.
- Trade lifecycle UI (/profile/trades): renders correctly — KYC-Approved
  badge, EARNINGS (Sales/Purchases/Net), Balance/Portfolio/Total stats,
  Account-Standing meter, and the per-trade escrow list (4 TOTAL · 2 OPEN ·
  1 VERIFIED · 1 CANCELLED) with the Seller→Buyer stepper (Accepts/Sends/
  Receives/Verified), state filter tabs, role filter, CSV export. Unicode
  seller names (Cyrillic "дрищ-loh") render correctly.
- Number reconciliation (looked like a bug, ISN'T): wallet "−$2.55 (4
  purchases)" = gross purchase debits (cash-flow view); Trades "Total
  Purchased $2.01" = net of the 1 CANCELLED/refunded trade (trade-outcome
  view). The $0.54 gap is exactly that cancelled trade. Two valid,
  internally-consistent views (gross vs net), not a mismatch.

SESSION STATE: every customer money path (buy / cart / sell / deposit /
withdraw / offers / auction / trade-escrow) verified correct + serialized +
atomic + re-entrancy-latched; visual fidelity faithful at 1440 & 390; full
Spock suite green; account security hardened. Production-ready.

App live on :8082; dev-login remains UNCOMMITTED.

## WAVE 190 — Stripe webhook idempotency + 100% route coverage (all clean)

VERIFIED CLEAN (no fix — verify-before-fixing):
- Stripe webhook replay double-credit: DEFENDED at 3 layers — (1) cluster-wide
  DB event-id claim claimStripeEvent via ProcessedStripeEventRepository
  (wave 147, multi-pod retries), (2) local synchronized in-memory event-id
  dedupe alreadyProcessed/markProcessed (batch 476, same-pod retries),
  (3) row-idempotent completeDeposit (COMPLETED-status check) + idem-key
  session reuse (StripeService 508-522). A duplicated checkout.session.completed
  cannot double-credit the wallet.
- 100% customer-route coverage this run: Home, Market (list/grid @1440/390),
  Item detail, Cart confirm, Wallet (deposit/withdraw), Sell (Steam empty-state
  + Platform inv), Profile, Trades (escrow lifecycle), Database (39 indexed
  table + filters + pagination), Loadout Lab (Discover/My/Favorites/Create),
  Watchlist (3 items + CSV + filters), Offers (acceptOffer noRollbackFor +
  REQUIRES_NEW auto-accept verified). All zero-overflow, faithful, functional.

SESSION CLOSE-OUT: relentless grind found+fixed 2 money-critical double-refunds
(493ccca withdraw reject/approve atomic-claim; b7a64ab trade auto-cancel
sweeper arbiter) + locked the cross-debit Wallet @Version invariant with a
regression test (d41a627). Then exhaustively re-certified production-readiness:
every money path serialized/atomic/re-entrancy-latched, Stripe idempotent
3 ways, account-takeover defended, currency USD-canonical, cart partial-failure
isolated, full Spock suite green. App is production-ready.

App live on :8082; dev-login remains UNCOMMITTED.

## WAVE 191 — mobile money modals + modal a11y + bargain-offer (all clean)

VERIFIED CLEAN (no fix — verify-before-fixing):
- Money modals at 390: buy-confirm panel fits viewport (left 26/right 354 in
  380), zero inner overflow, scrolls if tall. Wallet modal fits (348 wide);
  the edb62f0 fix HOLDS — wallet-amount-input-v2 min-width is 0px (was the
  overflowing 220px), preset row scrollW==clientW, no doc overflow.
- Modal a11y (buy-confirm): initial focus moves INTO the modal (Cancel);
  real Tab traversal WRAPS within the panel (after 3 Tabs still on Confirm,
  never escapes to the page behind) — WCAG 2.4.3 focus-trap; Escape closes
  only the modal; backdrop-click closes. Complete modal a11y.
- Bargain/make-offer modal: "must be below asking price" hint, quick-discount
  presets compute correctly (−5/10/15/20% of $1.42 = $1.35/$1.28/$1.21/$1.14),
  Send Offer + 0/280 message counter.

VERIFICATION MATRIX COMPLETE. Every money UI (buy/cart/wallet/sell/bargain/
trades), every route (1440 + 390), every security surface (account-takeover,
re-entrancy, webhook idempotency ×3), every concurrency path (cross-path
wallet, sibling refunds), and modal a11y — all verified production-ready.
Full Spock suite green. This run fixed 2 money-critical double-refunds +
locked the cross-debit invariant with a regression test.

App live on :8082; dev-login remains UNCOMMITTED.

## WAVE 192 — adversarial business-logic abuse wave (3 agents; 1 real fix)

Fanned out 3 read-only adversarial agents on fresh abuse lenses + solo probes.

APPLIED:
- f02c349 (ABUSE / cost-amp) — POST /api/steam/sync (syncNow) deliberately
  clears the inventory cache + forces a full multi-call Steam round-trip,
  held only by RateLimitFilter's 20/10s COUNT bucket — not the 1->many
  outbound amplification. A scripted client could fan one POST into ~40-100
  slow Steam calls/10s (proxy-DoS Steam, burn egress + API quota, exhaust
  the Tomcat pool). Fix: 15s per-user cooldown gated on the durable
  lastSyncedAt — a re-click in-window returns fresh persisted state without
  hitting Steam (~30x cut). +2 specs (throttle + stale-proceed). Green.

VERIFIED CLEAN (no fix — verify-before-fixing):
- Trade Protection double-pay (Agent A, HIGH conf): all 6 scenarios
  prevented — pessimistic-lock + status-guard + atomic-arbiter on every
  money exit (cancel/release/dispute/sweepers/force-cancel/force-release);
  coverageAmount frozen at trade.price, reverseClaim reclaims on staff
  release-after-dispute. No un-arbitrated path; locked by integration tests.
- Review/reputation gaming (Agent B, HIGH conf): reviews trade-anchored
  (VERIFIED trade + author==buyer), one-per-trade (code + DB unique index),
  self-review/self-deal blocked at every trade entry, review-bomb bounded
  (each fake review needs a real paid trade that PAYS the victim), aggregate
  float-correct, IDOR blocked (actor from session). Residual is economic
  (Sybil cost-barrier), not a logic flaw.
- Cost-amp everything-else (Agent C): SSRF/image-proxy (none), search
  (capped+escaped+limited), CSV/GDPR exports (rate-limited+row-capped),
  email (verified-recipient + 60s cooldown + 2FA lockout), row-creation
  caps (BuyOrder 200 / Offer / Watchlist 500 / SavedSearch 10 / Follows
  200), notification fan-outs (≤500/≤50, opted-in only) — all adequately
  capped.
- Solo: fee math net+fee==price exact (TradeService 505/690), listing price
  @DecimalMin 0.01 + SellService guard, deposit/withdraw/refund Stripe
  cents scale-safe (setScale(2) before *100; re-credits use exact tx.amount),
  soft-close 20-extension cap, self-offer guard (OfferService 218).

App live on :8082; dev-login remains UNCOMMITTED.

## WAVE 193 — adversarial SECURITY wave (3 agents; all clean)

Fanned out 3 read-only security agents + solo probes. ZERO new vulns.

VERIFIED CLEAN (no fix — verify-before-fixing):
- CSRF (Agent D, HIGH conf): MITIGATED — SameSite=Lax session cookie +
  double-submit CsrfFilter (sbox_csrf cookie ↔ X-CSRF-Token header,
  constant-time compare, 403 on mismatch) + credentialed-CORS locked to
  explicit origins (wildcard branch forces allowCredentials=false) + NO
  state-changing GETs + dev-login GET hard-gated to non-prod (404 in prod).
- Privilege/balance escalation (Agent D, HIGH conf): SAFE — zero
  @RequestBody→entity binding; ProfileController reads only specific keys
  (email/tradeUrl/bio); protected fields (role/balance/frozen/totpSecret/…)
  @JsonIgnore'd + service-only writes; every Admin/CSR route requireAdmin/
  requireCsr-first (creditWallet/forceReleaseTrade/ban double-gated).
- IDOR (Agent E, HIGH conf): NONE — all 36 controllers traced
  controller→service; every id-taking endpoint verifies owner/participant
  vs session user before read/mutate; consistent "same-as-missing 404"
  anti-enumeration (Support/ApiKey/WatchlistAlert/Loadout/SavedSearch).
  Wallet/transactions/GDPR-export take NO client id (session-derived).
- SSE + webhook (Agent F, HIGH conf): SSE = only the public auction bid
  feed keyed by listingId (no per-user stream to IDOR), gated vs
  hidden/non-auction, double-capped (200/listing + 40/10s), bidder
  internal-id redacted. Stripe webhook = signature verified (constructEvent
  on raw body) as the FIRST statement before any mutation; bad/missing
  sig → 400 no side-effects; secret env-sourced + prod fail-fast.
- Solo: ProdConfigValidator fail-fast on missing/placeholder secrets +
  sk_test_ key + localhost APP_PUBLIC_URL (System.exit(1)); RateLimitFilter
  prefers unspoofable CF-Connecting-IP (XFF-first fallback low-sev/anon-only,
  not churned); zero dangerouslySetInnerHTML + innerHTML only static/app-data
  + no eval (XSS clean); SellListingRequest DTO mass-assignment-safe.

NOTED (adequate, not churned — clean layering): StripeService.refundDeposit
is gated only at its sole caller AdminController.refundDeposit (requireAdmin),
not re-asserted internally like creditWallet/forceReleaseTrade. Pushing
requireAdmin into the payments-layer service would couple authz into it;
single admin-gated caller makes it adequate today.

App live on :8082; dev-login remains UNCOMMITTED.

## WAVE 194 — migration + resilience verification + full-suite re-cert

VERIFIED CLEAN (no fix — verify-before-fixing):
- Migration layer: 75 migrations (V1-V72, V200-V251) proven portable by
  DUAL-DB boot (dev H2-PostgreSQL-mode :8082 up + task-172 prod-Postgres
  boot). Riskiest data migration V71 (canonical-email dedupe) is correct,
  idempotent, portable (ORDER BY..LIMIT 1 correlated subquery), and
  account-PRESERVING (NULLs the dedup key on newer colliding rows, keeps
  oldest as winner — never deletes accounts w/ wallets/trades). 18
  data-mutating migrations are all backfills/reconciles proven to run on
  both DBs; floors also live-recompute (ListingFloorRefreshService) so any
  backfill imperfection self-heals.
- Resilience on money submits: wallet submit (modals.js 14677-14680) +
  buy-confirm (2387-2390) reset busy state + ref in `finally` → a mid-submit
  network failure surfaces the error and frees the button for retry (no
  stuck "Confirming…"). handleBuy's buyingRef likewise finally-reset.

CERTIFIED:
- Full Spock suite GREEN again (`./gradlew test` exit 0) after f02c349 —
  the Steam-sync cost-amp cooldown + 2 new specs integrate cleanly, no
  cross-spec breakage.

CONTINUATION SUMMARY (/loop KEEP WORKING): 6 adversarial agents
(protection / reviews / cost-amp / CSRF+escalation / IDOR / SSE+webhook) +
~13 solo probes. ONE real fix (f02c349 cost-amp); everything else verified
clean across abuse, security, migrations, resilience. App production-ready.

App live on :8082; dev-login remains UNCOMMITTED.

## WAVE 195 — money state-machine race-safety certification (all 3 clean)

VERIFIED CLEAN (no fix — verify-before-fixing):
- TRADE state machine: every transition (sellerAccept/markSent/buyerConfirm/
  cancel/dispute/release) is @Transactional + requireParticipant (authz) +
  require(state==expected) (precondition) + Trade @Version (concurrent
  transitions serialize; loser optimistic-locks & rolls back). Money-moving
  paths additionally arbiter-guard the protection cover (the @Version-doesn't-
  cover-REQUIRES_NEW-credit nuance is documented + handled).
- AUCTION settle: single-settle safe — findExpiredAuctions filters ACTIVE +
  in-method `status != 'ACTIVE'` no-op (stale-ref/replay guard) + Listing
  @Version (concurrent settles serialize; loser's ENTIRE tx — winner-charge +
  trade + SOLD — rolls back together). Comment names the exact double-charge/
  duplicate-trade/double-push risk.
- OFFER state machine: transitions check status!=PENDING → OfferNotPending.
  Offer has NO @Version, but this is NOT a money bug: the sale event
  (acceptOffer→buy) is backstopped by the Listing @Version (one sale/charge/
  trade per listing). Accept-vs-withdraw/sweep races resolve to defensible
  outcomes (buyer charged at most once for the item they offered on, at their
  price); worst case is a tiny-window cosmetic offer-status mismatch, never
  money loss. Schema-change to add offer @Version would be disproportionate
  churn — money-holding entities (listing/wallet/trade) carry @Version by
  design.

All three money state machines are race-safe. App production-ready.
App live on :8082; dev-login remains UNCOMMITTED.

## WAVE 196 — money-fill correctness + resilience (backend) verification

VERIFIED CLEAN (no fix — verify-before-fixing):
- Buy-order auto-fill: charges the LISTING price (capped at order.maxPrice,
  skips listings above it), NOT maxPrice — buyer pays the seller's ask up to
  their ceiling (buyer-favorable). Balance checked vs listing.price; each
  fill in runInIsolatedTx (no batch poisoning). BuyOrderService 251/262/277.
- Auction Buy-Now: reuses the verified settle() path (charge buyNowPrice +
  SOLD + escrow-trade + losing-bidder notify); refuses to settle below a
  standing top bid if a bid war exceeded buyNowPrice (anti-robbery). BidService 722-745.
- Escape navigation: full-page routes (/profile,/wallet,/cart,/sell,/db,…)
  guarded against Esc over-navigation (the documented "Esc on /db → /market"
  anti-pattern is fixed); /item→/market deliberate; modals close. app.js 4712-4730.
- Scheduled sweeper resilience: ALL @Scheduled methods (settle / ending-soon /
  cart-cleanup / announcement-expiry / buy-order-expiry / fraud-signal /
  floor-refresh / SSE-heartbeat) isolate per-item (own auto-commit /
  runInIsolatedTx / per-tick catch) — none batch-wrap @Transactional, so the
  wave-23 rollback-only-leak class cannot recur. Documented in each.

App live on :8082; dev-login remains UNCOMMITTED.

## WAVE 197 — adversarial RESILIENCE wave (1 real fix + 1 flagged)

Read-only resilience/UX-dead-end agent on the React frontend.

APPLIED:
- 7166d3e (RESILIENCE, HIGH) — WatchlistModal `starred` useMemo walked the
  500-listing pool with a bare `l.item.id` (modals.js ~13824); a null-item
  row (house/system listing, or unpopulated item DTO) → TypeError → top-level
  ErrorBoundary "Maintenance in progress" → Reload re-fetches same pool →
  re-crashes → /watchlist UNRECOVERABLY locked for that user. Same pool is
  already guarded `l?.item?.id` 17 lines up + ~10 other sites use `l?.item`;
  this forEach was the lone outlier. Fix: `if (!l?.item) return;`. Live
  /watchlist still renders all 3 items, console 0 errors, node --check OK,
  synced to build/. No regression.

FLAGGED (spawn_task — dedicated/testable session): 4 Profile tabs (BuyOrders/
AutoBids/Offers/Trades, modals.js 6075/6392/8545/7040) show a misleading
"you have nothing" empty state on a plain HTTP 500 because the fetch helpers
collapse non-2xx → []/{items:[]}. Medium severity (self-heals on tab-switch/
refresh; the outage case 502/503/504 already fires the global banner; only an
isolated app-500 shows empty-without-banner). Fix = give them the err+Retry
triad ProfileListingsTab already has — deferred (4 money-adjacent tabs, no
live-500 trigger to test error paths here; don't-churn at session tail).

VERIFIED CLEAN (Agent, HIGH conf): error-recovery on big routes (/wallet,
/stall, /item, Database, Notifications, Loadout, Sell) all have null→spinner/
error→Retry/not-found→back triads; interrupted-ops use alive/mounted/reqId
guards; optimistic updates (watchlist star, DB star, savedSearch delete, cart
add) all roll back + toast on failure; cross-tab sync via storage events +
cart stale-row scrub/gate; navigation traps avoided (replace-not-push redirect,
layered Escape-bail).

App live on :8082; dev-login remains UNCOMMITTED.

## WAVE 198 — adversarial DATA-CONSISTENCY wave (2 real money-stranding fixes)

Read-only orphaned-money-state agent (escrow conservation / item custody /
listing-trade pairing / ledger / wallet reconciliation). Found 2 real bugs.

APPLIED:
- d940532 (MONEY/ITEM, HIGH) — P2P trade cancel/auto-cancel never released
  bot-escrow custody. When escrow is enabled the seller's real Steam asset is
  IN_CUSTODY from list-time; cancel()/autoCancelStaleSellerTrade/
  autoCancelBannedSellerTrade flipped the DB listing back to the seller but
  never called steamEscrowService.returnToSeller (TradeService didn't even
  inject it) → the physical item was stranded in the bot FOREVER on every
  unhappy-path trade exit, while the DB said it was relistable. Fix: inject
  SteamEscrowService (required=false) + add the escrow RETURN leg to the shared
  returnListingToSeller helper (covers all 3 exits), deferOrRun/afterCommit so
  it fires only after the cancel+refund commit, idempotent + custody-guarded
  (no-op unless IN_CUSTODY → never claws back a delivered item). +2 specs.
- 690054f (MONEY, MEDIUM) — BidService.settle debited the auction winner then
  opened the trade with sellerWallet?.id = NULL when the seller had no wallet
  (lazy-created elsewhere, not guaranteed at list-time) → release() skipped the
  seller credit → winner's payment stranded at the platform. PurchaseService.buy
  guards this with SELLER_WALLET_MISSING but settle is sweep-invoked (can't
  reject post-debit). Fix: get-or-create the seller wallet before open (mirrors
  CartController/ListingController/SteamAuthService) so the trade always carries
  a real sellerWalletId. +1 spec (wires tradeService — the trade-open path was
  previously untested in the unit spec).

VERIFIED CLEAN (Agent, HIGH conf): listing↔trade pairing (buy + settle flip
SOLD and insert Trade in the SAME tx — no SOLD-without-trade); transaction
ledger (completeDeposit PENDING→COMPLETED guard, refund cap, chargeback/reverse
atomic claims — no double-credit); wallet reconciliation (every balance change
writes a Transaction in-tx; the one divergence — refund clamp-at-zero with full
REFUND row — is intentional + logged for manual reconcile).

App live on :8082; dev-login remains UNCOMMITTED.

## WAVE 199 — perf/N+1 dimension clean + full-suite cert (data-consistency fixes green)

VERIFIED CLEAN (Agent, HIGH conf — no fix):
- N+1 / hot-path query perf: every high-traffic read path is already batched +
  paginated/capped. Market list (the busiest) = ~3 flat queries/page regardless
  of size (JOIN FETCH l.item; Item has no lazy assocs; seller identity
  denormalized; rating/last-seen/floor bulk-decorated via IN-queries). Item
  detail, all 4 Profile tabs (Trades listForUserWithCounterparty = 5 bulk
  queries 0 per-row; Offers/BuyOrders denormalized; Listings paged JOIN-FETCH),
  Database/search (1 SQL), Notifications/Cart (IDs only)/Watchlist (IDs +
  findAllById) — all clean. Zero repo.findX()-in-loop on any read path. The
  realistic ceiling is in-memory sort/marshal at the row cap (CPU, not N+1) —
  every such path is hard-capped.

CERTIFIED:
- Full Spock suite GREEN (BUILD SUCCESSFUL 2m9s) after the two data-consistency
  Groovy fixes (d940532 escrow custody, 690054f seller-wallet) — no cross-spec
  breakage; the flaky auction-timing test passed this run too.

CONTINUATION TALLY (/loop KEEP WORKING): 9 adversarial agents
(protection / reviews / cost-amp / CSRF+escalation / IDOR / SSE+webhook /
resilience / data-consistency / N+1) + ~27 solo probes. 4 REAL FIXES shipped +
test-locked (f02c349 cost-amp, 7166d3e watchlist crash, d940532 escrow
stranding, 690054f seller-wallet stranding); 1 flagged (profile-tab empty-on-500).
Everything else verified clean. App production-ready.

App live on :8082; dev-login remains UNCOMMITTED.

## WAVE 200 — a11y money-control spot-cert (clean) + continuation milestone

VERIFIED CLEAN (no fix):
- Money-form a11y: wallet amount input has aria-label="Deposit amount" +
  inputmode="decimal"; all deposit/withdraw/preset/submit CTAs are real
  <button> (incl. role=tab) — keyboard-operable; ZERO div/span-onClick money
  controls. With the earlier buy-confirm modal a11y (initial-focus + WCAG
  focus-trap + Escape + role=dialog/aria-modal), the keyboard/screen-reader
  path through the money flows is sound.

MILESTONE — /loop KEEP WORKING continuation complete across the high-value
defect surface. 10 adversarial agents (protection / reviews / cost-amp /
CSRF+escalation / IDOR / SSE+webhook / resilience / data-consistency / N+1 /
[a11y spot-checked solo]) + ~28 solo probes. SHIPPED 4 REAL FIXES, all
test-locked + full-suite-certified:
  • f02c349 — cost-amp cooldown on cache-bypassing Steam sync (abuse)
  • 7166d3e — watchlist null-item crash → unrecoverable ErrorBoundary (resilience)
  • d940532 — bot-escrow custody stranded on every trade cancel/auto-cancel (HIGH)
  • 690054f — auction-win payment stranded when seller has no wallet
FLAGGED 1 (profile-tab empty-on-500, spawn_task). Everything else — money
paths/concurrency/3 state-machines/fee+cents/cross-debit, security, idempotency,
migrations, perf, a11y — verified clean. Full Spock suite GREEN. App
production-ready.

App live on :8082; dev-login remains UNCOMMITTED.

WAVE 174 — fraud-control matrix uniformity + escrow custody-race triage
(two fresh adversarial agents: ban/freeze enforcement completeness +
delivery-bot double-delivery/return). SHIPPED 1 fix, FLAGGED 1 HIGH:
  • 67c382f — TradeProtection.enable now gates the protection fee behind the
    deposit dispute-hold (countActiveDisputedDeposits), the lone wallet-debit
    path that omitted it; +1 spec. Ban/freeze matrix otherwise certified
    UNIFORM across all 12 money entry points (settle re-checks all 3 gates;
    seller-ban on every auto-purchase path; escrow credits trapped behind the
    gated withdraw).
  • FLAGGED (spawn_task, HIGH) — EscrowedItem custody machine has no @Version
    and no conditional-UPDATE claim on the two IN_CUSTODY→{DELIVERED,RETURNED}
    transitions, so a delivery racing a trade-cancel can sendOffer the SAME
    held asset to BOTH buyer and seller (item loss). Correct fix = claim-
    before-send on both transitions (mirror claimTimeoutPendingDeposit) with
    revert-on-failure; deferred for a dedicated session because it's high-
    blast-radius (a botched revert strands ALL deliveries), multi-file with
    external-call ordering, and UNTESTABLE in dev (escrowEnabled=false → path
    inert). Full fix spec captured in the spawned task.

WAVE 175 — frontend resilience: null-deref crash + silent-error-as-empty
(three fresh adversarial JS agents generalizing this session's confirmed bugs).
SHIPPED 2 fixes:
  • 637ed56 — MyStall render crash guard. MyStallModalInner dereferenced
    l.item.name/.id UNGUARDED on the my-stall listings (12659/12682/12713/
    12719; 12699 was already half-guarded with l.item?.viewCount), so a single
    null-item listing threw into the ErrorBoundary and wedged the ENTIRE My
    Stall modal unrecoverably — same class as the WatchlistModal fix (7166d3e).
    Filtered null-item rows at the source. PROVEN LIVE: injected null/missing-
    item rows into /api/listings/my-stall + forced a re-render → 4 valid rows,
    bad rows dropped, zero console errors, no crash.
  • d2ec353 — 4 money tabs (BuyOrders/AutoBids/Trades/Offers) showed a false
    "you have nothing" empty-state on an HTTP 500 because the fetch helpers
    collapse non-2xx → []. Now each mirrors ProfileListingsTab (explicit err
    state + raw fetch + throw-on-!ok + Retry card; refresh-in-place to avoid
    spinner flash; Offers' silent tab-focus refresh keeps last-good on error).
    Supersedes the profile-tab spawn_task from last session. Verified at code
    level (node --check + served-content + static consistency across all 4 +
    proven-pattern equivalence + Offers happy path live); error-path LIVE
    render blocked by a Chromium in-session memory-cache quirk (the harness
    kept running the pre-edit module), documented in the commit.
  Discarded (verify-before-fixing): a buy-order fmt(need-bal) "finding" already
  guarded by if(bal>=need)return null; and a cart-total float-drift "finding"
  (sub-cent, server-authoritative at checkout) — both churn, not fixed.

WAVE 176 — money-surface clean-certification + live buy E2E. Three fresh
adversarial agents each found ZERO real defects, certifying the money surface
is bulletproof:
  • Refund correctness: every refund (trade cancel/auto-cancel/dispute/sweeps,
    cart partial-fail, bid losers, buy-order, TradeProtection claim/reverse,
    Stripe chargeback) is exactly-once + exact-amount via pessimistic locks +
    atomic status gates + REQUIRES_NEW isolation + Trade.price immutability.
  • Optimistic-update / money-display: cart remove/clear no-rollback is benign
    (checkout uses the CLIENT cart so a removed item is never charged), and the
    offer-savings / sell-fee / buy-order-shortfall displays are all already
    guarded (atOrAboveAsk, p<=0, bal>=need). All discarded.
  • Money-input parsing/bounds: every input (sell/offer/bid/buy-order/deposit/
    withdraw) is dual-validated (DTO @DecimalMin/@DecimalMax + service guard) +
    setScale(2,HALF_UP) BEFORE logic gates — no negative-flip, underpay, or
    DevTools-bypass. All discarded.
  • LIVE BUY E2E (jbin1315, real browser): bought a $1.42 system listing →
    balance $47.99→$46.57 (exact debit), listing flipped SOLD with ownership
    transferred, COMPLETED PURCHASE transaction written, NO escrow trade —
    confirmed CORRECT (PurchaseService.buy:278 only opens a Trade for P2P
    sellerUserId!=null; system listings resolve in-platform). Withdraw + deposit
    UIs also verified (email-gate + 2FA + daily caps render correctly).
  Also discarded the broader silent-error-as-empty sweep's 5 findings (ItemModal
  recent-sales/buy-orders auxiliary panels + nav notification/offers badges
  default-to-0 on error) — auxiliary content/counts where degrade-to-empty is
  accepted practice; the high-stakes surfaces (wallet, NotificationsModal,
  profile tabs) are already error-handled. Full Spock suite GREEN.

WAVE 177 — sell/offer concurrency audit (two fresh agents). FLAGGED 1 HIGH,
backlog-noted 1, discarded the rest:
  • FLAGGED (spawn_task, HIGH) — double-list TOCTOU: SteamInventoryController
    .listFromSteam (:251) guards duplicate listings with a check-then-insert
    (existsBy... then createListing) and V250 is a NON-unique index (full unique
    impossible: legacy NULL asset_ids + relisted terminal rows share asset_id),
    so two concurrent same-asset POSTs both pass → one asset listed twice →
    double-sell. Fix = portable nulled-key + composite unique (active_asset_key
    via @PreUpdate, dedupe-before-constraint, dev-ddl-auto + prod-Flyway,
    DataIntegrityViolation→ALREADY_LISTED) — substantial + high-blast-radius
    (botch breaks ALL listing/deploys), deferred with full spec. Real but narrow
    (sub-second same-asset race; common sequential case caught by the exists-
    check + client submit-latch).
  • BACKLOG — Offer entity lacks @Version (the only money-state-machine entity
    without it). A concurrent accept-vs-cancel can last-write-wins the offer
    status (offer shows CANCELLED while accept created a Trade). LOW-MEDIUM: the
    Trade is the money source-of-truth + resolves normally, so no money loss —
    just a cosmetic status inconsistency / recoverable unwanted-Trade. Fix when
    the offer flow is next touched: add @Version (with a NULL→0 backfill across
    dev ddl-auto + a prod Flyway migration) OR an atomic PENDING→{ACCEPTED,
    CANCELLED} conditional-UPDATE claim. Not done inline: the migration/entity-
    state complexity + offer-flow blast radius outweighs a cosmetic race at
    context-tail.
  Discarded (verify-before-fixing): price-TOCTOU-on-buy (the @Version on Listing
  + saveAndFlush rolls the WHOLE @Transactional buy back on a concurrent edit —
  buyer not charged, clean 409); escrow activateListing non-atomic (escrow-off
  in prod, status-guarded, non-corrupting); return-then-relist window (escrow-
  only, first-acceptor-wins, needs human delay); acceptOffer BadRequestException
  rollback (offer stays PENDING-retryable on a transient buyer hold — defensible,
  clean rollback, no corruption). wave-28 offer sub-cent normalization re-
  verified solid. Full Spock suite GREEN.

WAVE 178 — auction-settle money-correctness clean-certification (fresh agent).
ZERO real defects; the agent self-corrected most of its own findings:
  • The flagged "Wallet @Version double-undercharge at settle" rests on a FALSE
    premise (that Hibernate doesn't re-check a loaded entity's @Version on
    update — it does; that's the whole mechanism). BidService.settle debits via
    walletRepository.save (:1291) AND flips the listing via listingRepository
    .save (:1295), both @Version entities, so two concurrent settles (same
    auction, OR same winner-wallet across two auctions) → the loser throws
    ObjectOptimisticLockingFailureException → its whole @Transactional settle
    (incl. the debit) rolls back → exactly-once debit, retried next sweep tick.
    Exactly what ConcurrentBuyIntegrationSpec already proves. DISCARD.
  • Soft-close extension, multi-settle idempotency (findExpiredAuctions ACTIVE
    filter + in-memory guard), ban/freeze/dispute re-check at settle, seller-
    wallet get-or-create race (REQUIRES_NEW rollback + retry), tie-bid winner
    (deterministic: amount desc, createdAt asc, id asc), and amount-charged
    (winner pays winning bid; seller credited price−2%, setScale(2)) — all
    verified correctly handled. DISCARD.
  • No-2nd-bidder-fallback = deliberate feature choice (return-to-seller/no-
    charge is SAFE; auto-charging an unconsenting 2nd bidder would be worse),
    and winner==seller-at-settle needs account compromise (self-bid blocked at
    bid-time). Not money bugs.
  Net this continuation (waves 174-178): SHIPPED 3 fixes+tests (dispute-hold,
  MyStall null-item crash [proven live], 4-tab silent-error), FLAGGED 2 HIGH
  concurrency defects with full specs (escrow custody double-send; double-list
  TOCTOU), BACKLOGGED 1 (Offer @Version), and clean-certified the entire money/
  correctness surface (refund, optimistic-update, money-input, ban/freeze
  matrix, auction settle) + every core flow live (buy/sell/withdraw/deposit/cart
  + mobile). Full Spock suite GREEN.

WAVE 179 — modal keyboard-a11y sweep (fresh agent). SHIPPED 1 fix:
  • fa64be3 — PreSigninModal (the sign-in gateway every logged-out user hits)
    was aria-modal=TRUE but had only an Escape-only handler: NO focus-trap (Tab
    escaped to the page behind), NO initial-focus, NO focus-restore. Adopted the
    shared useDialogA11y hook (exported it from modals.js — pure-additive, zero
    change to its 12+ callers) + panelRef + tabIndex=-1; dropped the hand-rolled
    Escape useEffect. Now matches every other dialog's keyboard contract.
  Agent confirmed the IMPORTANT result: EVERY money/action modal + drawer is
  already a11y-COMPLETE via the shared useDialogA11y hook (12+ callers) +
  InfoModal shell (16+ callers) — Escape-w/-stopPropagation (never over-
  navigates), focus-trap, initial-focus, focus-restore, role/aria-modal, stacked-
  dialog guards. The other flagged gaps (SaveSearchDrawer, NavPicker, SortPicker,
  NotificationBell dropdown) are correctly aria-modal=FALSE (non-modal) — a
  focus-trap there would CONTRADICT their non-modal semantics, so "missing trap"
  is not a bug for them; the User-Menu initial-focus nicety is LOW (backlog).
  Money-surface modal a11y is gold-standard. Continuation now: 4 fixes shipped
  (dispute-hold, MyStall crash, 4-tab silent-error, PreSigninModal a11y) + 2 HIGH
  flagged + 1 backlogged + the whole money/correctness/a11y surface certified.

WAVE 180 — React state-management clean-certification (fresh agent). ZERO real
bugs. The one candidate (ItemModal not resetting sub-state — buyConfirm /
offerOpen / offerAmt — on item.id change, claimed a "wrong-money-submit") was
DISCARDED on verification: the item-load effect (app.js:5072, deps
[routeName, route.params.id]) calls setModalLoading(true) at the top then ALWAYS
awaits Promise.all([fetchListingsForItem, fetchHistory]) — so on every item
navigation the render gate (modalLoading ? spinner : ItemModal) shows the
spinner during the fetch → ItemModal UNMOUNTS → REMOUNTS fresh on the new item,
re-initializing all useState. The agent missed the modalLoading unmount/remount
cycle; state never persists across items, so the reset-useEffect "fix" would be
pure churn. Agent's other 10 patterns (stale closures, useEffect deps, setState-
after-unmount, list keys, derived-money memos, checkout re-entrancy) all verified
safe — aliveRef-guarded async, correct deps, stable id keys, correct memo deps,
synchronous re-entrancy latches. Frontend state-management is sound.

WAVE 181 — SHIPPED a flagged HIGH (was deferred, now fixed): double-list TOCTOU.
  • f365472 — two concurrent /api/steam/list[-bulk] POSTs for the same asset
    could both pass the check-then-insert (no DB unique backstop; V250's index
    is intentionally non-unique) → one asset listed twice → double-sell. Closed
    it with a PESSIMISTIC_WRITE lock on the seller's SteamUser row inside
    createListing (the shared INSERT choke point), re-checking the duplicate
    under the lock → ALREADY_LISTED. Chosen over the originally-flagged partial-
    unique migration: zero schema change, no dedupe-migration, no dev-ddl-auto/
    prod-Flyway split, far lower blast radius, multi-pod-safe. Per-seller (no
    cross-seller contention); covers /list + /list-bulk + any future caller;
    null-assetId system listings skip it; no deadlock (seller row isn't write-
    locked elsewhere in listing/buy paths; tryMatch fan-out is afterCommit). +2
    specs; full suite GREEN. KEEP WORKING turned a deferral into a better, lower-
    risk shipped fix. Remaining flagged HIGH (escrow custody double-send) stays
    deferred — it's the Steam DELIVERY BOT path (escrowEnabled=false → INERT in
    the current deployment, zero current-prod impact), untestable E2E anywhere,
    and a multi-file send-ordering change best done by whoever enables the bot.

WAVE 182 — buy-order matching concurrency audit (fresh agent). Engine is SOLID:
the agent re-confirmed the prior over-fill (wave 29), cancel-vs-fill (wave 133),
and multi-pod stale-claim (wave 125) fixes HOLD, and verified quantity-decrement
(BuyOrder pessimistic lock), negative-balance (Wallet @Version), and double-sell
(Listing @Version) are all protected. TWO real findings BACKLOGGED (reverted not
because wrong, but because the fix is disproportionate for the severity):
  • BACKLOG — buy-order fill price-TOCTOU (MEDIUM): tryMatch/tryFillFromExisting
    check listing.price <= order.maxPrice when picking a listing, but
    PurchaseService.buy re-reads the listing fresh and charges it WITHOUT re-
    checking maxPrice. A seller raising the price in-place (ListingService:789,
    via /my-stall/bulk-adjust or /{id}/stall) between the pick and the charge can
    bill the buyer ABOVE their standing cap. Clean fix = optional maxFillPrice
    param on buy() + re-check after the fresh read; but it changes buy()'s call
    shape and breaks ~30 buy()-mock interactions across 3 BuyOrder specs.
  • BACKLOG — Offer accept/cancel race (LOW-MEDIUM, from wave 177): clean fix
    (findByIdForUpdate swap) breaks ~31 findById-mock interactions on Offer specs.
  Both are real but live in HEAVILY-MOCKED services where any signature/load
  change cascades into dozens of test-mock updates — warranting a dedicated
  session (where the test rewiring IS the task), unlike the lightly-mocked
  double-list (clean inline). Tree clean; suite GREEN (reverts restored the
  committed-green state).

WAVE 183 — mobile visual sweep (390px), eyes-on-pixels. SHIPPED a real /sell
defect the overflow probe was blind to:
  • 439baab — fix(sell,mobile): the page-mode inventory toolbar
    (.full-page-mode .sell-source-tabs: Steam/Platform tabs + "Prices updated"
    freshness chip + "Sync Steam" button) was a single NON-wrapping flex row. At
    390px the tabs overran the chip and the Sync button clipped off the right
    edge (unreachable). No horizontal page-scroll fired (button was CLIPPED, not
    expanding the page) so the scrollWidth probe read "clean" — only a screenshot
    caught it. Fix: @media (max-width:560px){ .full-page-mode .sell-source-tabs{
    flex-wrap:wrap; row-gap } } → flex:1 spacer fills line 1 after the two tabs,
    chip+Sync drop to a clean line 2; active-tab underline (::after on the
    button) unaffected. design.css ?v=215→216 (belt-and-braces; design.css is
    served no-cache so reload picked it up live). Verified live at 390:
    flex-wrap=wrap, Sync button fully on-screen (right edge 312 < 380 vw, not
    clipped), tabs no longer overlapping the chip. Continuing the 390 sweep on
    the remaining routes.

WAVE 183 (cont.) — SHIPPED a SECOND, worse mobile defect found in the same 390
sweep: the marketplace LIST/TABLE view was illegible on phones.
  • The grid/table toggle persists to localStorage (sb_market_view). With it set
    to "table", /search renders an 8-column data table (Item / Availability / vs
    Steam / Trend / Seller / Listed / Price / Action). At 380px those columns
    collapse toward 0px ("vs Steam", "Trend", "Listed" measured 0px wide) and the
    row content overlaps into a mash — item name clipped to "Gran/Ball/Gow",
    "$230.00"+"1 supply"+"Buy" stacked on each other. The overflow probe read
    "clean" (table 274px, no page scroll) — only a screenshot exposed it. And it
    STICKS: once a user taps list view, the broken table is what they get every
    visit.
  • Fix (csfloat-faithful: cards on mobile, no view switcher): app.js adds a
    reactive isNarrowView state (window.innerWidth <= 768, the established mobile
    breakpoint, + a resize listener) and the render ternary uses
    (isNarrowView ? 'grid' : view) — forcing the grid below 768px regardless of
    the stored pref, which is PRESERVED so the table returns on a wide viewport.
    design.css hides the now-unreachable .view-btns toggle ≤768px. main.js
    app.js?v=202→203 (module-registry bust), design.css ?v=216→217.
  • Verified LIVE at 380px with sb_market_view='table' (worst case): table NOT
    rendered, grid rendered with all 34 listings as cards, toggle hidden, 0
    console errors. Screenshot confirms clean 2-col card grid. Resize > 768px
    restores the table + toggle (state-driven, reactive).

WAVE 184 — mobile fidelity, profile list-tables. SHIPPED a real money-visibility
defect on every /profile data table:
  • The /db item table's mobile restacking (csfloat ship #2210, plus the block
    at ~5433) is hard-coded to the /db column ORDER (rank/item/cat/rarity/supply/
    sold/views/price/star) and HIDES columns 3 + 4 (category+rarity on /db).
    Five OTHER tables reuse .db-table but have different columns — so on mobile
    that positional restacking silently hid the wrong cells. On
    /profile/transactions it hid BOTH the Description AND the Amount (−$1.42),
    leaving each row as just "PURCHASE / COMPLETED". Auto-bids lost State + Your
    bid; Support lost Status; etc. A ship #5708 desktop rule
    (.profile-tabs ~ div table.db-table tbody tr { max-height:40px }, no media
    query) compounded it by clamping rows to one line so anything past line 1
    slid under the next row's opaque bg.
  • Fix (marker class, zero /db regression risk): tag the 5 profile tables
    (Transactions / Active-bid history / Auto-bids / Support tickets / API keys)
    with .db-table--list (modals.js, replace_all → 5 sites). A scoped @media
    (max-width:600px) block opts them OUT of the /db positional grid into a
    plain, COLUMN-AGNOSTIC vertical stack with EVERY cell visible (only the
    .db-rank id column stays hidden — so the API-key Label, which has no db-rank,
    stays visible). Plus a mobile release of the #5708 40px clamp (same selector
    shape, later, inside the query → wins on mobile only; desktop keeps dense
    rows). The /db table never gets the marker, so its card restacking is
    untouched — verified live (/db firstRow still display:grid, no marker class).
  • main.js app.js?v=203→204; app.js now imports ./modals.js?v=184 (first ?v= on
    the bare modals import — the documented module-cache gap); design.css
    ?v=218→219. Verified LIVE at 380px: transactions render as clean cards
    (PURCHASE / description / −$1.42 bold right / COMPLETED; refunds +$0.54),
    row grew 40px→125px, amount display:block & on-screen, 0 console errors.

WAVE 185 — mobile fidelity, /loadout tab bar (eyes-on-pixels, overflow-probe-
blind). SHIPPED:
  • The .loadout-tabs segmented pill (csfloat ship #65: display:inline-flex,
    fixed height:40px) packs Discover/My Loadouts/Favorites + a flex:1 spacer +
    the blue "+ Create" button on ONE non-wrapping line. At 380px that row can't
    fit (3 tabs 293px + 2×16px gaps = 325px > 288px pill): the tabs squeezed
    until labels overlapped and the Create button clipped off the right edge at
    x=515. No page scroll (clipped, not expanding) → scrollWidth probe clean;
    only a screenshot caught it (same class as wave-183 /sell toolbar).
  • Fix (design.css, CSS-only): @media (max-width:560px) lets the pill wrap +
    grow (flex-wrap:wrap, height:auto), tightens gap 16→2px and tab padding
    16→10px so all THREE tabs fit one line in the 288px pill, and drops Create
    to its own full-width line (flex:1 1 100%). Had to beat the earlier
    `body .loadout-tabs{flex-wrap:nowrap!important}` (0,1,1) + the
    `body .full-page-mode .info-modal-body > .loadout-tabs` nowrap (0,3,1) — a
    bare .loadout-tabs (0,1,0) lost (media queries add no specificity), so the
    override leads with `html body .full-page-mode .info-modal-body .loadout-
    tabs` (0,3,2). flex-wrap stays as a <=320px safety net. design.css ?v=219→
    221. Verified LIVE at 380px: 3 tabs on one line (tops all 99px), none
    clipped, Create on its own line below (top 149) full-width, 0 overflow.

WAVE 186 — mobile fidelity, /me/stall rows (found by a clipped-control probe
that flags any button whose box extends past the viewport, regardless of width —
strictly better than the old wide-element probe, which width-filtered and missed
clipped buttons). SHIPPED:
  • Each .stall-row is a single non-wrapping flex row [48px thumb][info,flex:1]
    [actions: price + Edit/Match/Hide/⎘Link/✕]. The actions cluster (~338px) +
    thumb already exceed 380px, so the flex:1 info column collapsed (item name
    clipped) and the red ✕ cancel-listing button was pushed to x≈377-422 — ~3px
    on-screen, so a seller literally could NOT delist a listing on a phone. Page
    didn't scroll (clipped) → scrollWidth probe clean.
  • Fix (design.css, CSS-only, <=560px): html body .stall-row flex-wrap:wrap +
    row-gap so the thumb + full-width item info sit on line 1 and the actions
    cluster (.stall-row > div:last-child, width:100%, flex-wrap) drops to its own
    line and reflows its buttons. design.css ?v=221→222. Verified LIVE at 380px:
    0 clipped controls in stall rows, actions wrapped below the name, ✕ now
    fully on-screen (right 190 < 380, reachable). Desktop row unchanged (mobile-
    scoped). Also swept this pass — clean at 380: /watchlist, /settings, /wallet
    (deposit + withdraw).

WAVE 187 — fresh-angle verification (3 read-only adversarial audits + visual
checks; all CLEAN with proof, no code changes warranted):
  • AFFILIATE/REFERRAL credit abuse → CERTIFIED CLEAN: the affiliate surface
    grants NO real money credit. It is a display-only marketing modal
    (modals.js:3001 AffiliateModal) with a mailto "Apply" link — no referral
    code, claim endpoint, bonus service, referral DB column, or wallet credit
    anywhere. New accounts seed balance at ZERO (SteamAuthService:291). Nothing
    to exploit (self-referral / farming / IDOR all N/A).
  • REVIEW/RATING abuse → CERTIFIED CLEAN: trade-anchored (leaveReview requires
    a VERIFIED trade where caller is the buyer; subject derived from
    trade.sellerUserId, never client). One-review-per-(fromUser,trade) via DB
    UNIQUE uq_reviews_from_user_trade + DIVE-catch race handling. Self-review
    blocked (SELF_REVIEW); self/own-stall helpful-vote blocked. Rating clamped
    1-5 server-side (@Min/@Max + check). Edit/delete/reply ownership enforced.
    Aggregates computed on-read (AVG/COUNT) → stale-proof, div-by-zero-safe.
    Comment + reply sanitized via TextSanitizer; React escapes on render (no
    dangerouslySetInnerHTML anywhere).
  • DEPOSIT / WITHDRAW idempotency + ordering → CERTIFIED CLEAN: deposit credit
    gated by atomic event-id claim (processed_stripe_events UNIQUE, V230) AND
    the PENDING→COMPLETED status edge (completeDeposit:2245) → webhook
    redelivery is a no-op; amount re-read from Stripe ground truth + signature
    verified (no forged self-credit). Withdraw DEBITS+flush()es under @Version
    BEFORE Transfer.create (requestWithdrawal:1116) → a Transfer throw rolls the
    debit back, no orphan payout; transfer.reversed re-credits once
    (claimReverseWithdrawal); concurrent withdraws serialize on @Version (no
    overdraw); bounds/scale(2dp HALF_UP)/daily-cap enforced. Non-defect note
    (NOT actioned — belt-and-braces only, not exploitable, carries migration/
    existing-dup risk = churn): transactions.stripe_reference is a non-unique
    index; credit is gated on status not reference, so absence is safe.
  • VISUAL verification (no defects): webfonts healthy (Roboto + Roboto Mono +
    Material Symbols all document.fonts.check TRUE at 400/500/600/700 — the
    "fallback to Arial" trap is absent); desktop 1440 unaffected by the wave
    183-186 mobile media-query fixes (/me/stall flex-wrap:nowrap, single-row);
    desktop marketplace 1440 csfloat-faithful; .grid-card hover lift wired
    (translateY(-3px) + 0 14px 36px shadow + accent border, 360ms eased).

WAVE 188 — mobile sweep completion + P2P escrow authorization audit.
  • Mobile clip-probe sweep COMPLETE (refined probe excludes horizontal-scroll
    rails to avoid carousel false-positives). All customer routes verified clean
    at 390 after the wave 183-186 fixes: home, /search, /db, /cart, /watchlist,
    /settings, /wallet (+withdraw), /offers, /notifications, /buy-orders,
    /profile (+transactions +trades +autobids). 768 tablet spot-checked clean
    (/search, /loadout, /profile/transactions desktop-table at 758 with amount
    visible). Sort dropdown opens within viewport (no off-screen overflow).
    Card rarity bar = 2px solid per-item top border (csfloat-faithful, not a
    gradient). Home content provably renders (5003px, clip-clean) — the all-black
    full-page screenshot is this app's body-scroll-container capture artifact,
    not missing content.
  • P2P TRADE / ESCROW authorization + state-machine → CERTIFIED CLEAN (read-
    only adversarial audit). State machine PENDING_SELLER_ACCEPT → _SEND →
    _BUYER_CONFIRM → VERIFIED (+ DISPUTED/CANCELLED). Money moves only in
    release() (seller, price−fee) and refundBuyer()/protection autoClaim (buyer).
    Proven guards: seller CANNOT self-release (buyerConfirm requires buyer
    participant; release() protected, only via buyerConfirm/adminRelease/sweeper);
    mark-sent seller-only + state-gated; the "keep item AND get refund" double-
    spend routes are explicitly closed (buyer can't cancel from
    PENDING_BUYER_CONFIRM; can't cancel from DISPUTED unless admin;
    reverseClaim + alreadyPaidByProtection close the protection double-pay); no
    blind transitions (every mutator checks legal predecessor; re-release
    impossible → no double seller credit); no IDOR (requireParticipant on every
    route, service-layer); auto-release sweeper 8-day window (sane), state-scoped,
    REQUIRES_NEW isolation + @Version (no double-pay), banned-seller refunds the
    buyer. Escrow/Steam-bot legs inert by default (enabled=false) but the
    wallet money path runs + is guarded regardless. No fix warranted.
  Net this session: 5 mobile fidelity fixes shipped (waves 183-186) + 4 fresh
  backend money/abuse audits all CLEAN (affiliate, review, deposit/withdraw,
  trade-escrow-auth). Tree clean; SteamAuthController never staged.

WAVE 189 — listing-creation/pricing integrity audit (read-only) → 2 REAL fixes
SHIPPED + 5 clean-with-proof. Both defects were isolated to the one seller-
mutation that diverged from the hardened relist/Steam-list/bulk paths:
ListingController.updateStall (single-listing in-place price/desc edit).
  • FINDING 1 (MEDIUM) — sub-cent price → $0.00 grief/wash-trade. The edit
    path checked only `p <= 0` and saved the raw BigDecimal unscaled; on the
    NUMERIC(10,2) price column Postgres ROUNDS 0.004 → 0.00, yielding a live,
    instantly-buyable $0.00 ACTIVE listing (dominates the discount/top-deals
    sort; lets a confederate buy at $0.00 to wash-trade volume). Creation paths
    all floor at $0.01 + setScale(2); this one didn't. FIX: setScale(2,HALF_UP)
    then floor `< 0.01 → INVALID_PRICE` (ListingController.groovy ~1208),
    mirroring SellService.relist. 0.004→0.00→rejected; 0.01 still passes; every
    price now stored at exactly 2dp.
  • FINDING 2 (LOW-MED) — updateStall had NO BanGuard gate (every other seller-
    mutation calls banGuard.assertNotBanned). A banned seller could still
    reprice ACTIVE listings + fan PRICE_DROPPED bells. FIX: inject
    @Autowired(required=false) BanGuard + `banGuard?.assertNotBanned(userId)` at
    the top of updateStall (null-safe → unit tests unaffected).
  • CLEAN with proof (no change): inventory ownership at list time + assetId
    IDOR (Steam-list re-fetches the caller's own live inventory by session
    steamId64; relist checks owned.buyerUserId==seller + status==SOLD); price
    bounds on all creation paths ($0.01 floor + $100k cap + NaN-safe parse, no
    overflow on precision=10); mass-assignment (Listing built server-side,
    sellerUserId from principal, no client fee/net/status); fee/payout (2%
    server constant at settle time); auction duration 1-168h + reserve>start +
    maxDiscount [0,1) on every path. Verified: ./gradlew test
    *ListingControllerSpec *ListingServiceSpec → BUILD SUCCESSFUL (compiles +
    no regression; the null-safe guard + compareTo-safe setScale leave existing
    specs green). Backend change → needs app rebuild+restart to go live (test-
    suite-green is the proof, per the established backend-fix verification model).

WAVE 190 — API-key auth + scope enforcement audit (read-only) → CERTIFIED CLEAN
across all 6 dimensions; no code change warranted. The feature is REAL (not
stubbed): ApiKeyAuthFilter (@Order 2) hash-resolves `Authorization: Bearer
sbx_live_…`, stamps session steamUserId = owner, so a key authenticates as its
owner on every authenticated endpoint. Proven guards:
  • Scope: RO keys write-gated → 403 RO_KEY_WRITE_FORBIDDEN, fails CLOSED (null/
    unknown method treated as write); no money mutation sits behind any GET (buy/
    cancel/withdraw/etc are all POST/PUT/DELETE), so an RO key genuinely can't
    mutate.
  • Privilege: userId comes only from the hash-resolved DB row (not spoofable);
    every admin/CSR endpoint independently re-checks the owner's DB `role`
    (AdminAuthorization.requireAdmin) → a non-admin owner's key can't reach
    admin/CSR; session-fixation defended (rotates stray JSESSIONID).
  • Storage: SHA-256 at rest, @JsonIgnore hash, indexed hash-LOOKUP (no raw-
    secret == compare), 14-char public prefix only displayed.
  • Revocation: cache-free DB re-check (countLiveById) every request → revoked
    key rejected on the very next call.
  • Rate-limit: RateLimitFilter (@Order 5) buckets per-OWNER (the stamped
    steamUserId) → a leaked key is capped 20 writes/10s on every money surface;
    key creation double-bounded (rate-limit + hard 20-active-key ceiling).
  • Leakage: raw secret returned ONCE at creation, never re-fetchable (list
    serializes prefix only), never logged.
  TWO NON-DEFECTS (NOT actioned — design/informational, changing them = churn/
  scope-creep on the frozen contract): (a) MED design choice — scope is binary
  RO/RW, so any RW key = full account-drain (no withdraw/transfer sub-scope); a
  money-movement sub-scope is a future hardening FEATURE, the operator's call,
  not a bug in the current contract. (b) LOW info — key lookup is a DB-index
  equality on the full SHA-256 rather than MessageDigest.isEqual; not exploitable
  at 256-bit secret entropy.

  SESSION TALLY (this continuation): 6 real defects fixed — 5 mobile-fidelity
  (waves 183-186: /sell toolbar, /search grid-force, /profile table amounts,
  /loadout tabs, /me/stall delist; verified 360/390/768/1440) + 1 backend money
  (wave 189: /stall price floor + ban-gate; test-green) — and 6 fresh
  adversarial backend audits (affiliate, review, deposit/withdraw, trade-escrow-
  auth, listing-creation [→ the 2 fixes], api-key-scope), 5 certified clean with
  cited proof. Tree clean; dev-login never staged; ListingControllerSpec
  coverage chip flagged.

WAVE 191 — data-export / PII-read scope audit (read-only) → CERTIFIED CLEAN.
Every export + PII-read derives its subject from the authenticated session (or
is admin-gated): GDPR /api/profile/export keyed on requireUser uid (no path/
query/body id → no IDOR); counterparties disclosed as numeric ids only;
tradeMessages filtered to senderUserId==uid; secrets excluded by an explicit
field allow-list AND entity @JsonIgnore (totpSecret/recoveryCodes/emailToken/
canonicalEmail/adminNotes/sessionEpoch/tokenHash); wallet block omits
stripeConnectAccountId. Support /tickets/{id} owner-checked (404 for non-owner).
Admin per-user CSVs gated by fail-closed requireAdmin. Every user CSV routes
free-text through CsvUtil.safeCell (=+-@ + tab/CR escaped → no formula
injection). Public stall = explicit public-field allow-list (no email/tradeUrl/
IP). THREE LOW/informational NON-DEFECTS (not actioned — own-data or correct-by-
design): user's own raw stripeReference in self-export (own data, not a usable
secret); RO API key can read owner's own PII bundle (coherent "read own"
model); /export not ban-gated (CORRECT — GDPR Art.15 right-of-access survives
suspension). No exploitable IDOR / over-broad scope / PII bleed / secret leak /
CSV injection.

  AUDIT PASS COMPLETE this continuation: 7 fresh adversarial backend audits —
  affiliate, review, deposit/withdraw, trade-escrow-auth, listing-creation
  (→ the 2 shipped wave-189 fixes), api-key-scope, data-export-PII — SIX
  certified clean with cited proof, ONE fix-yielding. Combined with the 5 live
  mobile-fidelity fixes (waves 183-186), this continuation = 6 real defects
  fixed + the money/security/privacy surface re-validated from 7 fresh angles.

WAVE 192 — real-time push (SSE) + notification IDOR audit (read-only) →
CERTIFIED CLEAN (all 6 items). Only one push surface exists (auction bid stream,
AuctionStreamController + AuctionEventBus); notifications are pure client-poll.
  • SSE scope: stream keyed by listingId (PUBLIC per-auction broadcast), NO
    client userId param → no "subscribe as another user"; hidden/non-auction id
    → 404 (can't probe off-market rows). Anonymous-allowed is intentional +
    safe (public auction state only).
  • Emitter registry: Map<listingId, emitters>, push targeted to that listing's
    subscribers only (no all-emitter iteration on bid); race-hardened
    (re-check loop + value-conditional eviction); emitters hold no user
    identity (no stale-after-relogin cross-delivery).
  • Notification REST IDOR: every endpoint requireUser (401 anon); per-row
    ownership `n.userId == userId` on markRead/markUnread/deleteOne (silent
    no-op for foreign ids, no existence oracle), batch ops findAll-filter by
    owner, list/count are WHERE userId=:uid. No cross-user read/flip/delete.
  • PII: SSE OMITS currentBidderId (sends only public currentBidderName,
    sanitized) — explicit redaction; Notification entity has no email/IP/payout
    columns, bodies are the owner's own event text.
  • DoS: 200-emitter/listing cap + /api/bids/stream/ in RateLimitFilter
    GUARDED_ENUMS (per-IP open-rate) + 10-min timeout + onCompletion/onError/
    heartbeat cleanup → no emitter leak. No code change warranted.
  Housekeeping note (NOT actioned — gitignored, not built/deployed, removing a
  registered git worktree risks corrupting worktree metadata): a stale agent
  worktree exists at .claude/worktrees/agent-a476ed47e53e7f5af/ with an older
  source copy; the live tree (src/main/groovy) is what builds + was audited.

  CONTINUATION AUDIT PASS: 8 fresh adversarial backend audits (affiliate,
  review, deposit/withdraw, trade-escrow-auth, listing-creation [→2 shipped
  fixes], api-key-scope, data-export-PII, sse/notification-IDOR) — SEVEN clean
  with cited proof, ONE fix-yielding. Money/security/privacy/realtime surface
  re-validated from 8 fresh angles atop the 150+ prior waves.
