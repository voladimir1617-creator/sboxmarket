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
