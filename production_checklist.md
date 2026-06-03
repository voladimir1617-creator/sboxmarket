# Production Readiness Checklist

Verified state of the csfloat-parity + production hardening work, captured so it
survives context compaction. Last updated 2026-06-02.

Status legend: ✅ shipped & verified live (Playwright/curl this session, in git) ·
🔧 still open, ranked P0 (ship-blocker) / P1 (parity or prod gap) / P2 (polish).

---

## ✅ DONE (verified live)

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
