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

---

## 🔧 REMAINING (ranked)

### P0 — ship-blockers
- _None outstanding._ (Items below are parity/prod-hardening, not launch blockers.)

### P1 — parity + production gaps
- **Full-bleed pages:** /db, /loadout, /help, /stall render as centered modals, not full-bleed pages like csfloat
- **Home hero below the fold** — should be above-fold (being fixed this lap)
- **DB thumbnails ~45% broken on cold load** — dead Steam-CDN seed hashes; data fix needed
- **Price-history chart line empty** — chart frame renders but the line has no points (being fixed this lap)
- **PENDING_ESCROW timeout sweeper missing** — stuck escrows never expire (being fixed this lap)
- **GlobalErrorController JSON path leak** — error responses leak internal paths (being fixed this lap)
- **Webhook idempotency + card-test fraud counters are in-memory / per-pod** — breaks under multi-pod scale-out; needs shared store (Redis/DB)
- **No structured JSON logging** — add `logback-spring.xml` for log aggregation
- **/database route 404s** — only /db exists; add alias/redirect
- **Profile has no inventory/listings tab** — csfloat profile leads with the user's listings

### P2 — polish
- **Tap targets** — some 32px chips are below the 44px minimum (partially addressed on mobile)
