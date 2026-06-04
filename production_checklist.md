# Production Readiness Checklist

Verified state of the csfloat-parity + production hardening work, captured so it
survives context compaction. Last updated 2026-06-03.

Status legend: ✅ shipped & verified live (Playwright/curl this session, in git) ·
🔧 still open, ranked P0 (ship-blocker) / P1 (parity or prod gap) / P2 (polish).

---

## ✅ DONE (verified live)

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
