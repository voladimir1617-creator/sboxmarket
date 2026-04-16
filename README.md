# SkinBox 🎮

A full-stack **s&box skin marketplace** built with Spring Boot + Groovy, styled after CSFloat.

> **s&box, not CS2.** This is a marketplace for s&box clothing/cosmetic items (Hats, Jackets, Shirts, Pants, Gloves, Boots, Accessories) — rarity tiers are Limited / Off-Market / Standard. There is **no float value, no wear, no stickers, no trade-up contract** — those are CS-only mechanics and do not exist in s&box. Features that could not map cleanly were intentionally dropped. See `memory/sbox_vs_csfloat.md` for the full delta.

## Production Deployment

```bash
# 1. Set secrets before starting
export SPRING_PROFILES_ACTIVE=prod
export SPRING_DATASOURCE_URL=jdbc:postgresql://db.internal:5432/skinbox
export SPRING_DATASOURCE_USERNAME=skinbox
export SPRING_DATASOURCE_PASSWORD=<from secrets manager>
export STRIPE_SECRET_KEY=sk_live_...
export STRIPE_PUBLISHABLE_KEY=pk_live_...
export STRIPE_WEBHOOK_SECRET=whsec_...
export STRIPE_SUCCESS_URL=https://skinbox.example/?deposit=success
export STRIPE_CANCEL_URL=https://skinbox.example/?deposit=cancel
export STEAM_API_KEY=<optional>
export STEAM_REALM=https://skinbox.example/
export STEAM_RETURN_URL=https://skinbox.example/api/auth/steam/return
export ADMIN_BOOTSTRAP_STEAM_IDS=76561198012345678
export CORS_ALLOWED_ORIGINS=https://skinbox.example
export COOKIE_SECURE=true
export COOKIE_SAME_SITE=strict
export SECURITY_HSTS=true

# 2. Run the JAR as a non-root user (systemd recommended)
java -jar build/libs/sboxmarket-1.0.0.jar
```

The `prod` profile (`application-prod.yml`) **automatically**:
- Disables the H2 console
- Locks actuator to `health` only with `show-details=never`
- Hides Swagger UI and `/v3/api-docs`
- Switches Hibernate to `ddl-auto: validate` (no auto-schema-mutation)
- Enables HSTS and strict cookies
- Writes rotating log files to `/var/log/skinbox/skinbox.log`
- Requires every sensitive env var to be set — startup fails loudly otherwise.

### Example systemd unit

```ini
[Unit]
Description=SkinBox Marketplace
After=network.target

[Service]
User=skinbox
WorkingDirectory=/opt/skinbox
EnvironmentFile=/etc/skinbox/skinbox.env
ExecStart=/usr/bin/java -jar /opt/skinbox/sboxmarket-1.0.0.jar
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

### Example Nginx block

```nginx
server {
    listen 443 ssl http2;
    server_name skinbox.example;
    ssl_certificate     /etc/letsencrypt/live/skinbox.example/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/skinbox.example/privkey.pem;

    gzip on;
    gzip_types application/json text/css application/javascript image/svg+xml;

    location / {
        proxy_pass         http://127.0.0.1:8080;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
        proxy_http_version 1.1;
    }
}
```

## Security layers

Every layer is on by default and configurable via env vars:

| Layer              | What it does                                                               | Toggle                                   |
|--------------------|----------------------------------------------------------------------------|------------------------------------------|
| `CorrelationIdFilter` | Tags every request with an `X-Correlation-Id` + writes full CSP/HSTS   | `security.hsts` env                      |
| `CsrfFilter`       | Double-submit-cookie CSRF guard on every `/api/**` write                   | `security.csrf-enabled`                  |
| `RateLimitFilter`  | 20 writes / 10 s per IP per guarded surface; returns 429 with Retry-After | Always on                                |
| Session cookies    | HttpOnly always; Secure + SameSite controlled by env                       | `COOKIE_SECURE`, `COOKIE_SAME_SITE`      |
| CORS allowlist     | Env-driven; dev = `*`, prod = explicit origin                              | `CORS_ALLOWED_ORIGINS`                   |
| Stripe webhook     | Signature-verified via `webhookSecret` inside `StripeService`              | Always on when keys are configured       |
| Admin / CSR roles  | `USER < CSR < ADMIN`, enforced by `AdminService.requireAdmin`              | `ADMIN_BOOTSTRAP_STEAM_IDS`              |
| Audit log          | Append-only trail of every staff action + money movement                  | `/api/admin/audit`                       |

The audit log is exposed at `GET /api/admin/audit` (admin-only) and supports `?event=`, `?actor=`, `?subject=` filters.

### Closed vulnerabilities (2026-04-14 security audit)

Every finding from the external audit has a live mitigation in this repo:

- **H2 console** is disabled by default (`H2_CONSOLE=false`); requires explicit opt-in.
- **Stored XSS** in support tickets, stall descriptions, ban reasons and CSR notes is closed by the `TextSanitizer` component — every user-provided free-text field is HTML-stripped at ingestion and length-capped per field kind.
- **Stripe confirm-deposit** now verifies the session with Stripe (`paymentStatus == 'paid'`, metadata `walletId`, amount match). Unknown sessions return 400.
- **Wallet response** no longer echoes the Stripe publishable key or the user's `steamId64`.
- **Error responses** hide the request path and internal entity names in production (`security.verbose-errors=false`, default). The correlation id stays in server logs for debugging.
- **`search=…`** on both `/api/listings` and `/api/database` caps to 100 chars and whitelists sort/category/rarity.
- **Swagger UI** and **/v3/api-docs** are OFF by default (`SWAGGER_ENABLED=false`). Admin endpoints and staff surfaces have their own gates regardless.
- **CSRF double-submit-cookie** protects every `POST/PUT/PATCH/DELETE` on `/api/**`; Stripe webhooks and Steam OpenID returns are explicitly exempt.
- **Rate limit**: 20 writes / 10 s per IP per guarded surface, returns 429 with `Retry-After`.
- **Content-Security-Policy** covers `self` + unpkg + Google Fonts + Steam CDNs + Stripe; blocks inline scripts.
- **Session cookies** are `HttpOnly` always; `Secure` + `SameSite=strict` in prod.
- **Append-only audit log** at `GET /api/admin/audit` records every staff action and every money movement with IP + user-agent + correlation id.

## What's in here

- **Real URL routing** — CSFloat-style, HTML5 history API. Every feature has its own path: `/`, `/search`, `/db`, `/item/:id`, `/stall/:id`, `/loadout`, `/loadout/:id`, `/profile`, `/wallet`, `/watchlist`, `/sell`, `/me/stall`, `/offers`, `/buy-orders`, `/cart`, `/notifications`, `/support`, `/help`, `/faq`, `/settings`, `/admin`, `/csr`. Back/forward/refresh all work, deep links are shareable.
- **Marketplace** — grid + table, live category filters, keyboard shortcut `/` to focus search, `Esc` to close any modal
- **Auctions** — proper bid panel with live countdown, auto-bid bot, scheduled expiry sweep every 30 s
- **Buy Orders** — standing reverse listings; new matching listings auto-purchase via `BuyOrderService.tryMatch`
- **Shopping Cart** — client-side (localStorage), bulk checkout via `POST /api/cart/checkout` that walks listings sequentially with per-row success/failure. Cart icon in the nav with a live badge count.
- **Trade Escrow** — full state machine (`PENDING_SELLER_ACCEPT → PENDING_SELLER_SEND → PENDING_BUYER_CONFIRM → VERIFIED`) in `TradeService`, scheduled sweeper auto-releases after the Steam trade-hold window (default 8 days, configurable via `TRADE_AUTO_RELEASE_DAYS`).
- **2FA via TOTP** — `TotpService` implements RFC 6238 (SHA-1, 30s step, 6 digits, ±1 window drift). Enroll via `/api/profile/2fa/enroll` (returns otpauth URL for QR code), confirm via `/api/profile/2fa/confirm`. `/api/wallet/withdraw` refuses without a fresh code once enrolled.
- **Email onboarding** — optional email + verification token on `SteamUser`. `PUT /api/profile/email` issues a token, `POST /api/profile/email/verify` consumes it.
- **Offer counter-offers** — sellers can counter via `POST /api/offers/{id}/counter`; chain is stored via `parent_offer_id` + `author` so the frontend can render a full back-and-forth conversation.
- **Audit log** — append-only `audit_log` table; every staff action + every money movement writes one row with IP, user-agent, and correlation id. Surfaced at `/api/admin/audit` with `?event=`, `?actor=`, `?subject=` filters.
- **Loadout Lab** — 8-slot clothing loadouts (Hats/Jackets/Shirts/Pants/Gloves/Boots/Accessories/Wild), public discover tab, AI budget generator
- **Database** — every indexed item, rarest-first (sorted by supply — no float, per s&box reality)
- **Notifications** — server-persisted feed with kind-specific icons, 25 s polling bell, full feed modal
- **Support Tickets** — threaded conversations with a rule-based auto-responder (`SupportService.autoReply`)
- **API Keys** — hashed tokens (SHA-256), shown once, revocable from the Developers tab
- **Privacy mode** — Ctrl-click the balance to mask every amount across the UI
- **Profile tabs** — Personal Info / Transactions / Buy Orders / Auto-Bids / Trades / Offers / Support / Developers with an account-standing progress bar
- **My Stall** — edit price/description inline, hide individual listings, flip an Away-mode switch to hide them all
- **Steam integration** — real Steam OpenID login, public-profile XML fallback, **auto-sync every 20 minutes**, sell directly from your Steam inventory (appid 590830)
- **Fee calculator + trust stats + 6-step trading journey** on the homepage instead of stock-market-style top-gainers panels
- **Stripe deposit/withdrawal** — dev-mode instant credit if no keys are configured, real Checkout if they are

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│  HTTP boundary  — thin controllers, validated DTOs in,       │
│                    mapped domain objects out                 │
│  ┌────────────────┐  ┌──────────────┐  ┌─────────────────┐   │
│  │ ListingCtrl    │  │ WalletCtrl   │  │ OfferCtrl       │   │
│  │ SteamAuthCtrl  │  │ ItemCtrl     │  │ StripeWebhook   │   │
│  │ BuyOrderCtrl   │  │ BidCtrl      │  │ LoadoutCtrl     │   │
│  │ NotificationC. │  │ ProfileCtrl  │  │ SupportCtrl     │   │
│  │ ApiKeyCtrl     │  │ DatabaseCtrl │  │ SteamInventoryC.│   │
│  └────────┬───────┘  └──────┬───────┘  └────────┬────────┘   │
│           │                 │                   │            │
│  ┌────────▼─────────────────▼───────────────────▼────────┐   │
│  │  Services  — single-responsibility, transactional     │   │
│  │  PurchaseService    SellService    OfferService       │   │
│  │  ListingService     ItemService    StripeService      │   │
│  │  SteamAuthService   SeedService    NotificationSvc    │   │
│  │  BuyOrderService    BidService     LoadoutService     │   │
│  │  ProfileService     SupportService ApiKeyService      │   │
│  │  SteamInventorySvc  SteamSyncService                  │   │
│  └────────┬──────────────────────────────────────────────┘   │
│           │                                                  │
│  ┌────────▼──────────────────────────────────────────────┐   │
│  │  Repositories  — Spring Data JPA interfaces           │   │
│  └────────┬──────────────────────────────────────────────┘   │
│           │                                                  │
│  ┌────────▼──────────────────────────────────────────────┐   │
│  │  H2 (file) or PostgreSQL  — swapped via env vars      │   │
│  └───────────────────────────────────────────────────────┘   │
│                                                              │
│  Cross-cutting:                                              │
│    RequestContextFilter    — correlation id + security hdrs  │
│    GlobalExceptionHandler  — typed exceptions → ErrorResp    │
│    ErrorResponse DTO       — consistent 4xx/5xx contract     │
└──────────────────────────────────────────────────────────────┘
```

**SOLID enforced at every layer:**
- **S** — each service handles exactly one flow (`PurchaseService` only runs buys, `OfferService` only runs offers; they compose via DI when a flow spans both).
- **O** — new features extend by creating new services/controllers, never by editing old ones.
- **L** — all repositories extend `JpaRepository`; a Postgres swap is zero code edits.
- **I** — modals and DTOs are narrow. Request DTOs carry only the fields a single endpoint needs.
- **D** — services depend on repository interfaces. Controllers depend on service beans. Nothing depends on concrete DB, Stripe, or Steam implementations; those are injected.

**Error handling contract:**

Every non-2xx response returns exactly this shape:
```json
{
  "code": "INSUFFICIENT_BALANCE",
  "message": "Insufficient balance: need $50.00, have $10.00",
  "path": "/api/listings/5/buy",
  "correlationId": "a1b2c3d4",
  "timestamp": "2026-04-13T08:12:34.567Z",
  "details": null
}
```
Clients branch on `code`, never on `message`. `correlationId` is echoed in the `X-Correlation-Id` header and every log line for that request via SLF4J MDC.

**Exception hierarchy** (`com.sboxmarket.exception`):
```
ApiException
├─ NotFoundException           404
├─ UnauthorizedException       401
├─ ForbiddenException          403
├─ BadRequestException         400
├─ ConflictException           409
├─ InsufficientBalanceException 402  (carries required + available)
├─ ListingNotAvailableException 409
└─ OfferNotPendingException    409
```
Services throw typed exceptions only — raw `RuntimeException` becomes a 500 and is logged as a bug.

**Request validation:** every state-changing endpoint accepts a `@Valid` DTO with `jakarta.validation` constraints (`@NotNull`, `@DecimalMin`, `@DecimalMax`, etc.). Validation failures are mapped to `VALIDATION_FAILED` 400 with per-field messages.

**Security headers** (applied by `RequestContextFilter` on every response):
- `X-Content-Type-Options: nosniff`
- `X-Frame-Options: DENY`
- `Referrer-Policy: strict-origin-when-cross-origin`
- `Permissions-Policy: geolocation=(), microphone=(), camera=()`
- `X-Correlation-Id: <short uuid>`

**Tests:** `PurchaseServiceSpec` covers the money path — happy buy, insufficient balance, listing-not-available, wallet-missing, own-listing. Run with `./gradlew test`.

---

---

## Tech Stack

| Layer     | Technology                          |
|-----------|-------------------------------------|
| Backend   | Spring Boot 3.2 + Groovy 4          |
| Database  | H2 (in-memory, swap to Postgres)    |
| ORM       | Spring Data JPA / Hibernate         |
| API Docs  | SpringDoc OpenAPI (Swagger)         |
| Tests     | Spock Framework                     |
| Frontend  | Vanilla React 18 (ESM, no build)    |

---

## Quick Start

### Prerequisites
- Java 17+
- Gradle (or use the wrapper)
- *(optional)* [Stripe CLI](https://stripe.com/docs/stripe-cli) — only if you want real deposits

### Run (no Stripe keys — dev mode)
```bash
cd sboxmarket
./gradlew bootRun
```

Open → **http://localhost:8080**

Without Stripe keys the wallet runs in **DEV MODE**: deposits are credited instantly, withdrawals are debited instantly, and no real money moves. Perfect for local dev.

### Run with real Stripe (test mode)

1. Get your keys from https://dashboard.stripe.com/test/apikeys
2. Export env vars **before** starting the server:

   **Windows (PowerShell)**
   ```powershell
   $env:STRIPE_SECRET_KEY      = "sk_test_..."
   $env:STRIPE_PUBLISHABLE_KEY = "pk_test_..."
   $env:STRIPE_WEBHOOK_SECRET  = "whsec_..."   # from stripe listen (next step)
   ./gradlew bootRun
   ```

   **macOS / Linux**
   ```bash
   export STRIPE_SECRET_KEY=sk_test_...
   export STRIPE_PUBLISHABLE_KEY=pk_test_...
   export STRIPE_WEBHOOK_SECRET=whsec_...
   ./gradlew bootRun
   ```

3. In a second terminal, forward Stripe webhooks to your local server:
   ```bash
   stripe listen --forward-to localhost:8080/api/stripe/webhook
   ```
   The CLI prints a `whsec_...` — paste it into `STRIPE_WEBHOOK_SECRET` and restart.

4. Click **Balance → Deposit** in the UI. You'll be redirected to Stripe Checkout. Use test card `4242 4242 4242 4242`, any future expiry, any CVC. On return, the webhook credits your wallet.

> ⚠️ **Withdrawals** require [Stripe Connect](https://stripe.com/connect) Express accounts for real payouts. In this demo a withdrawal debits the wallet and creates a `PENDING` transaction that an operator would fulfil off-platform.

### API Docs
Open → **http://localhost:8080/swagger-ui.html**

### H2 Database Console
Open → **http://localhost:8080/h2-console**
- JDBC URL: `jdbc:h2:mem:sboxmarket`
- Username: `sa` / Password: *(empty)*

---

## REST API

### Listings
| Method | Endpoint                    | Description                       |
|--------|-----------------------------|-----------------------------------|
| GET    | `/api/listings`             | All active listings (filterable)  |
| GET    | `/api/listings/{id}`        | Single listing                    |
| GET    | `/api/listings/item/{id}`   | Listings for a specific item      |
| POST   | `/api/listings/{id}/buy`    | Purchase a listing                |
| DELETE | `/api/listings/{id}`        | Cancel a listing                  |
| GET    | `/api/listings/stats`       | Market stats (volume, floor etc.) |

**Query params for GET /api/listings:**
```
sort       = price_asc | price_desc | newest | rarity
category   = Clothing | Hats | Accessories | Workshop
rarity     = Limited | Off-Market | Standard
minPrice   = 0.00
maxPrice   = 9999.00
search     = "wizard"
```

### Items
| Method | Endpoint                  | Description                      |
|--------|---------------------------|----------------------------------|
| GET    | `/api/items`              | All items (filterable)           |
| GET    | `/api/items/{id}`         | Single item                      |
| GET    | `/api/items/{id}/history` | 30-day price history             |
| GET    | `/api/items/stats`        | Item stats (categories, floor)   |
| POST   | `/api/items`              | Create new item                  |

### Wallet & Stripe
| Method | Endpoint                         | Description                                 |
|--------|----------------------------------|---------------------------------------------|
| GET    | `/api/wallet`                    | Current balance, currency, Stripe mode      |
| GET    | `/api/wallet/transactions`       | Deposit/withdraw history                    |
| POST   | `/api/wallet/deposit`            | `{amount}` → returns Stripe Checkout URL    |
| POST   | `/api/wallet/withdraw`           | `{amount, destination}` → debits wallet     |
| POST   | `/api/wallet/confirm-deposit`    | `?sessionId=` → used by success redirect    |
| POST   | `/api/stripe/webhook`            | Stripe → us; verifies signature & credits   |

### Offers & Bargaining
| Method | Endpoint                     | Description                                    |
|--------|------------------------------|------------------------------------------------|
| GET    | `/api/offers/incoming`       | Offers on the signed-in user's listings        |
| GET    | `/api/offers/outgoing`       | Offers the signed-in user has made             |
| POST   | `/api/offers`                | `{listingId, amount}` — make an offer          |
| POST   | `/api/offers/{id}/accept`    | Seller accepts — transfers funds + item        |
| POST   | `/api/offers/{id}/reject`    | Seller rejects                                 |
| DELETE | `/api/offers/{id}`           | Buyer cancels their outgoing offer             |

### Buy Orders (standing reverse listings)
| Method | Endpoint                  | Description                                                 |
|--------|---------------------------|-------------------------------------------------------------|
| GET    | `/api/buy-orders`         | All active orders for the signed-in buyer                   |
| POST   | `/api/buy-orders`         | `{itemId?, category?, rarity?, maxPrice, quantity}`         |
| DELETE | `/api/buy-orders/{id}`    | Cancel. Matching engine fires on every new/relisted listing |

### Auctions & Bids
| Method | Endpoint                  | Description                                                 |
|--------|---------------------------|-------------------------------------------------------------|
| POST   | `/api/bids`               | `{listingId, amount, maxAmount?}` — place manual/auto bid   |
| GET    | `/api/bids/listing/{id}`  | Full bid history for a listing                              |
| GET    | `/api/bids/auto`          | Current user's active auto-bid ceilings                     |

`BidService.sweepExpired` runs on `@Scheduled(fixedDelay = 30_000)` and settles every auction whose `expiresAt` has passed — charges the winner, credits the seller (minus 5% fee), and emits `AUCTION_WON` / `AUCTION_LOST` notifications.

### Loadout Lab
| Method | Endpoint                               | Description                                  |
|--------|----------------------------------------|----------------------------------------------|
| GET    | `/api/loadouts/discover`               | Public loadouts (supports `?search=`)        |
| GET    | `/api/loadouts/mine`                   | Signed-in user's loadouts                    |
| GET    | `/api/loadouts/{id}`                   | Loadout + 8 slots                            |
| POST   | `/api/loadouts`                        | `{name, description?, visibility}`           |
| PUT    | `/api/loadouts/{id}/slot/{slot}`       | `{itemId}` — fill a slot                     |
| POST   | `/api/loadouts/{id}/slot/{slot}/lock`  | Toggle lock (AI-generate skips locked slots) |
| POST   | `/api/loadouts/{id}/generate`          | `{budget}` — AI fills unlocked slots         |
| POST   | `/api/loadouts/{id}/favorite`          | Increment favourite counter                  |
| DELETE | `/api/loadouts/{id}`                   | Delete                                       |

### Database (rarest-first item index)
| Method | Endpoint              | Description                                              |
|--------|-----------------------|----------------------------------------------------------|
| GET    | `/api/database`       | `?q=&category=&rarity=&sort=&limit=&offset=` (paginated) |

`sort` options: `rarest` (by supply ASC), `most_traded`, `price_desc`, `price_asc`, `newest`. **No float sort** — s&box cosmetics have no float value.

### Notifications
| Method | Endpoint                          | Description                         |
|--------|-----------------------------------|-------------------------------------|
| GET    | `/api/notifications`              | `{items, unread}` for signed-in user|
| POST   | `/api/notifications/{id}/read`    | Mark one as read                    |
| POST   | `/api/notifications/read-all`     | Mark all as read                    |

### Profile (aggregated for tabbed modal)
| Method | Endpoint          | Description                                                         |
|--------|-------------------|---------------------------------------------------------------------|
| GET    | `/api/profile/me` | `{user, wallet, stats, counts, standing}` — one call powers profile |

Account standing has five tiers (`EXCELLENT / GOOD / POOR / AT_RISK / BANNED`) computed from the user's trade history by `ProfileService.computeStanding`.

### Support Tickets (threaded)
| Method | Endpoint                                 | Description                               |
|--------|------------------------------------------|-------------------------------------------|
| GET    | `/api/support/tickets`                   | All tickets for the signed-in user        |
| GET    | `/api/support/tickets/{id}`              | Ticket + messages                         |
| POST   | `/api/support/tickets`                   | `{subject, category, body}` — new ticket  |
| POST   | `/api/support/tickets/{id}/reply`        | `{body}` — user reply                     |
| POST   | `/api/support/tickets/{id}/resolve`      | Mark resolved                             |

Every new ticket gets an immediate rule-based auto-response from `Clara (auto)` based on category so the thread is never empty on day one.

### Developer API Keys
| Method | Endpoint              | Description                                                    |
|--------|-----------------------|----------------------------------------------------------------|
| GET    | `/api/api-keys`       | List user's keys (metadata only — no raw tokens)               |
| POST   | `/api/api-keys`       | `{label}` — mint a new key. Raw token returned **ONCE**.       |
| DELETE | `/api/api-keys/{id}`  | Revoke. Applications using it stop working immediately.        |

Tokens are `sbx_live_…` prefixed and stored as SHA-256 hashes only.

### Steam Integration & Auto-Sync
| Method | Endpoint                  | Description                                                   |
|--------|---------------------------|---------------------------------------------------------------|
| GET    | `/api/steam/inventory`    | Live Steam inventory for s&box (appid 590830) + catalogue map |
| POST   | `/api/steam/sync`         | Force-refresh profile + inventory without waiting             |
| POST   | `/api/steam/list`         | `{assetId, price}` — create a market listing from Steam item  |
| GET    | `/api/auth/steam/login`   | Start Steam OpenID flow                                       |
| GET    | `/api/auth/steam/return`  | OpenID callback                                               |
| GET    | `/api/auth/steam/me`      | Current authenticated user                                    |
| POST   | `/api/auth/steam/logout`  | Invalidate session                                            |

`SteamSyncService` runs on `@Scheduled(fixedDelay = 20 * 60 * 1000L)` — every 20 minutes it walks every registered user, refreshes their profile via the OpenID return flow and re-reads their public s&box inventory, rate-limited to 1 request/sec per user. New items trigger a `STEAM_INVENTORY` notification.

### Listing from a Steam inventory — flow

1. User signs in with Steam.
2. They open **Sell Items**, which shows two tabs:
   - **🎮 Steam Inventory** — live `/api/steam/inventory` (appid 590830, context 2)
   - **📦 Platform Inventory** — items previously bought on sboxmarket
3. They pick a Steam item, enter a price, and hit **List for Sale**.
4. The frontend calls `POST /api/steam/list {assetId, price}`.
5. `SteamInventoryController` re-fetches the live inventory to verify ownership + tradability, then:
   - Looks up a matching catalogue `Item` by name
   - **Auto-creates** the catalogue entry if the item is new (category inferred from Steam tags)
   - Persists a fresh `ACTIVE` `Listing` via `ListingService.createListing`
   - `BuyOrderService.tryMatch` fires automatically in case a standing buy order is waiting
6. The new listing appears on the marketplace immediately.

---

## Project Structure

```
sboxmarket/
├── build.gradle
├── settings.gradle
├── README.md
└── src/
    ├── main/
    │   ├── groovy/com/sboxmarket/
    │   │   ├── SboxMarketApplication.groovy   ← Entry point
    │   │   ├── config/
    │   │   │   └── WebConfig.groovy           ← CORS + SPA routing
    │   │   ├── controller/
    │   │   │   ├── ItemController.groovy      ← /api/items
    │   │   │   └── ListingController.groovy   ← /api/listings
    │   │   ├── model/
    │   │   │   ├── Item.groovy
    │   │   │   ├── Listing.groovy
    │   │   │   └── PriceHistory.groovy
    │   │   ├── repository/
    │   │   │   ├── ItemRepository.groovy
    │   │   │   ├── ListingRepository.groovy
    │   │   │   └── PriceHistoryRepository.groovy
    │   │   └── service/
    │   │       ├── ItemService.groovy
    │   │       ├── ListingService.groovy
    │   │       └── SeedService.groovy         ← Seeds 30 real s&box items
    │   └── resources/
    │       ├── application.yml
    │       └── static/
    │           └── index.html                 ← Full React SPA (no build needed)
    └── test/
        └── groovy/com/sboxmarket/
            └── ItemControllerSpec.groovy      ← Spock tests
```

---

## Seeded Data

On startup, **SeedService** automatically seeds:
- **30 real s&box items** (Neck Tattoo $1,227 → Fisherman Hat $1.99)
- **3–7 listings per item** with randomised prices & sellers
- **30 days of price history** per item with realistic trend simulation

---

## Storage

By default the app uses **file-mode H2** at `./data/sboxmarket.mv.db`. Wallet balances, listings, transactions, offers and watchlists all persist across restarts.

To wipe everything and reseed, just delete the `data/` directory and restart.

## Production Deployment

`application.yml` reads everything from environment variables with sane local defaults — switching to production is a matter of setting the right env vars, no code changes needed.

### PostgreSQL
```bash
export SPRING_DATASOURCE_URL="jdbc:postgresql://db.host:5432/skinbox"
export SPRING_DATASOURCE_DRIVER="org.postgresql.Driver"
export SPRING_DATASOURCE_USERNAME="skinbox"
export SPRING_DATASOURCE_PASSWORD="••••••"
export SPRING_JPA_DIALECT="org.hibernate.dialect.PostgreSQLDialect"
export SPRING_JPA_DDL="validate"  # use Flyway/Liquibase for real migrations
```
Add to `build.gradle`:
```groovy
runtimeOnly 'org.postgresql:postgresql'
```

### Stripe (test or live)
```bash
export STRIPE_SECRET_KEY="sk_live_..."
export STRIPE_PUBLISHABLE_KEY="pk_live_..."
export STRIPE_WEBHOOK_SECRET="whsec_..."
export STRIPE_SUCCESS_URL="https://skinbox.example.com/?deposit=success"
export STRIPE_CANCEL_URL="https://skinbox.example.com/?deposit=cancel"
```
Configure the webhook endpoint in your Stripe dashboard at `https://skinbox.example.com/api/stripe/webhook` listening for `checkout.session.completed` and `checkout.session.expired`.

### Steam OpenID
```bash
export STEAM_REALM="https://skinbox.example.com/"
export STEAM_RETURN_URL="https://skinbox.example.com/api/auth/steam/return"
# Optional — only needed for the Steam Web API. The XML profile fallback works without it.
export STEAM_API_KEY="your_steam_web_api_key"
```

### HTTPS
Front the JAR with nginx or Caddy for TLS termination. Spring Boot itself can also do TLS via `server.ssl.*` properties.

### Behind a reverse proxy
```bash
export SERVER_FORWARD_HEADERS_STRATEGY=framework
```
Spring will then trust `X-Forwarded-*` headers so the Steam realm/return URLs render with the correct external host.

---

## Run Tests

```bash
./gradlew test
```

Spock tests cover:
- `GET /api/items` returns seeded data
- Category/rarity filtering
- 404 for missing items
- Market stats endpoint
- Price-range and sort logic in ItemService
