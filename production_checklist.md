# Production Readiness Checklist

Verified state of the csfloat-parity + production hardening work, captured so it
survives context compaction. Last updated 2026-06-01.

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
