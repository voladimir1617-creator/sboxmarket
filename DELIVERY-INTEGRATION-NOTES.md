# Automated Steam Delivery — Integration Notes

This documents how the automated Steam trade-offer delivery (bot-escrow) plugs
into the **existing** escrow state machine, the two integration gaps as they were
found, and exactly what (if anything) the TradeService owner needs to add.

> **Correction (verified against the tree at `c156e4a`).** The original version of
> this line claimed all delivery code lived in **new files only** and that
> `TradeService.groovy` was **not** edited. That is no longer true.
> `TradeService.groovy` now holds an escrow collaborator
> (`@Autowired(required = false) SteamEscrowService steamEscrowService`) and a
> return-to-seller leg on the cancel/auto-cancel path, added by commit `d940532`
> — so a cancelled trade hands the bot-held item back instead of stranding it.
> `Wallet.groovy` and `SteamUser.groovy` have not been touched since this
> document was written. `StripeService.groovy` and `WalletController.groovy` have
> changed since, but for unrelated reasons — neither carries escrow or delivery
> logic (`StripeService` mentions `SteamEscrowService` only in a comment naming
> it as a sibling sweeper).

---

## New files added

Backend (Spring Boot / Groovy):
- `src/main/groovy/com/sboxmarket/service/SteamTradeBotService.groovy` — HTTP client to the bot sidecar.
- `src/main/groovy/com/sboxmarket/service/SteamBotResult.groovy` — typed, never-throwing result.
- `src/main/groovy/com/sboxmarket/service/SteamDeliveryService.groovy` — `@Service` + `@Scheduled` poller/orchestrator.
- `src/main/groovy/com/sboxmarket/model/SteamDeliveryAttempt.groovy` — audit + authoritative offer-tracking entity.
- `src/main/groovy/com/sboxmarket/repository/SteamDeliveryAttemptRepository.groovy` — repo for the above.
- `src/main/resources/db/migration/V200__steam_delivery_attempts.sql` — Flyway migration (version **V200**).

The DEPOSIT leg landed later (see the GAP 1 status update below) and added:
- `src/main/groovy/com/sboxmarket/service/SteamEscrowService.groovy` — deposit request, custody confirm, 24h deposit timeout, return-to-seller.
- `src/main/groovy/com/sboxmarket/model/EscrowedItem.groovy` — custody row, states `PENDING_DEPOSIT → IN_CUSTODY → DELIVERED | RETURNED | FAILED`.
- `src/main/groovy/com/sboxmarket/repository/EscrowedItemRepository.groovy`
- `src/main/resources/db/migration/V210__escrowed_items.sql`, `V258__escrowed_items_return_retry.sql`

Tests:
- `src/test/groovy/com/sboxmarket/service/SteamTradeBotServiceSpec.groovy`
- `src/test/groovy/com/sboxmarket/service/SteamDeliveryServiceSpec.groovy`
- `src/test/groovy/com/sboxmarket/service/SteamEscrowServiceSpec.groovy`
- `src/test/groovy/com/sboxmarket/service/SteamEscrowRecoverySpec.groovy`
- `src/test/groovy/com/sboxmarket/SteamListingSellerTradeUrlGateSpec.groovy` — the seller-side trade-URL pre-flight on `/api/steam/list[-bulk]`.
- `src/test/groovy/com/sboxmarket/RelistEscrowGateSpec.groovy` — the escrow pre-flight on `SellService.relist` (see "The relist path" below).

Sidecar (Node.js):
- `steam-bot/index.js`, `steam-bot/package.json`, `steam-bot/.env.example`, `steam-bot/README.md`

---

## How delivery drives the EXISTING escrow machine (no TradeService edit needed)

The real `TradeService` lifecycle is:

```
PENDING_SELLER_ACCEPT --sellerAccept--> PENDING_SELLER_SEND
PENDING_SELLER_SEND   --sellerMarkSent--> PENDING_BUYER_CONFIRM
PENDING_BUYER_CONFIRM --buyerConfirm--> VERIFIED   (release() credits the seller)
```

`release(Trade, boolean)` — the method that credits the seller and runs the
frozen fee/escrow math — is **`protected`**. The only **public** ways to reach
`VERIFIED` are `buyerConfirm(buyerUserId, tradeId)` and
`adminRelease(adminUserId, tradeId, reason)`.

`SteamDeliveryService` therefore drives the machine through the existing public
transitions, acting on the participants' behalf once the **Steam Web API has
verified the on-chain trade**:

1. **Send leg** — for a trade in `PENDING_SELLER_SEND`, the bot sends the Steam
   offer to the buyer, then the orchestrator calls
   `tradeService.sellerMarkSent(sellerUserId, tradeId, buyerTradeUrl)` →
   `PENDING_SELLER_SEND → PENDING_BUYER_CONFIRM`.
2. **Verify leg** — when the sidecar reports the offer **`accepted`**, the
   orchestrator calls `tradeService.buyerConfirm(buyerUserId, tradeId)` →
   `PENDING_BUYER_CONFIRM → VERIFIED`, which invokes the existing `release()`
   and credits the seller. **No fee/escrow math is reimplemented.**

This works **today, unmodified**. Trade state + the Steam offer id/state are kept
in the new `steam_delivery_attempts` table, so **no column is added to `Trade`**.

### Caveats of the "act on the buyer's behalf" approach
- `buyerConfirm` calls `banGuard.assertNotBanned(buyerUserId)` and gates on state
  `PENDING_BUYER_CONFIRM`. If the buyer was banned mid-trade, or the trade already
  advanced (manual confirm / dispute / auto-release sweeper), `buyerConfirm`
  throws — the orchestrator **catches and logs**, and the existing auto-release
  sweeper remains the safety net. No double-credit is possible (the `@Version`
  optimistic lock on `Trade` plus the state gate prevent it).
- Auditing: the resulting `TRADE_VERIFIED` audit row is attributed to the buyer
  (the manual-confirm actor), not to the system. If you want it attributed to the
  system, use the optional hook below.

### OPTIONAL one-line hook (only if you object to impersonating the buyer)
If you'd prefer the system to release **without** the buyer-id/ban-guard
semantics, add ONE public method to `TradeService` that the orchestrator can call
instead of `buyerConfirm`:

```groovy
/** System/bot-verified delivery release. Verifies state then runs the existing
 *  release(). No buyer impersonation, no ban-guard (the Steam Web API already
 *  proved delivery). actor=null in the audit row. */
@Transactional
Trade systemConfirmDelivery(Long tradeId, String reason = 'Steam offer accepted (bot-verified)') {
    def t = get(tradeId)
    require(t.state == 'PENDING_BUYER_CONFIRM', "Trade cannot be released in state ${t.state}")
    if (reason) t.note = textSanitizer.medium(reason)
    release(t, true)   // existing protected method — frozen fee/escrow math
    t
}
```

Then in `SteamDeliveryService.advanceToVerified(...)` swap
`tradeService.buyerConfirm(trade.buyerUserId, trade.id)` for
`tradeService.systemConfirmDelivery(trade.id)`. **This is optional — the shipped
code does not require it.**

---

> **STATUS UPDATE — GAP 1 IS CLOSED.** The section below describes the deposit
> leg as "not yet built". It has since been built: `SteamEscrowService` +
> `EscrowedItem` + `EscrowedItemRepository` + migration `V210` implement the
> seller→bot deposit, custody confirmation against the bot's real inventory, the
> 24h deposit timeout, and the return-to-seller path.
> `SteamDeliveryService.resolveAssetId` now reads
> `steamEscrowService.heldAssetIdForListing(trade.listingId)` first and only
> falls back to `steam.delivery.test-asset-id`. Read the rest of this section as
> history, not as an open item.
>
> What is genuinely still open is **not code**: the sidecar has never been run,
> because it needs a dedicated Steam account with a mobile authenticator and a
> cleared trade hold. See the "Operator setup" checklist in `steam-bot/README.md`.

## GAP 1 (CLOSED — kept for history): the Steam **asset id** of the sold item is not persisted

A bot-escrow model needs the concrete **Steam asset id** the bot must give the
buyer. The current platform does **not** store this anywhere on `Trade` or
`Listing` — the old honor-system flow had the human seller pick the item in
their own Steam client. `Listing` references the catalogue `Item` (a *type*,
e.g. "AK-47 | Redline"), not a specific tradeable inventory asset.

> *No longer accurate, and worth being precise about because the relist gate
> turns on it:* `Listing.assetId` **does** exist now (migration `V250`), set by
> `/api/steam/list[-bulk]` from the asset the seller picked. But it records the
> asset **as it was in that seller's inventory at list time** — Steam reassigns
> asset ids on every trade, so once an item has changed hands the stored id no
> longer names a copy the current owner holds. It is a double-list guard, not a
> custody key. The authoritative custody id is `EscrowedItem.heldAssetId`, which
> is what the bot actually received.

A correct bot-escrow flow has **two legs**, and the deposit leg is the missing
piece:

1. **Deposit leg (not yet built):** when a seller lists/sells, the bot must first
   **receive** the seller's specific item into the bot's own inventory (the
   sidecar already exposes `POST /offers/incoming/:id/accept` and `GET /inventory`
   for exactly this). Only then does the bot own an asset id it can send onward.
2. **Delivery leg (built):** the bot sends that asset id to the buyer and the
   platform verifies acceptance.

Until the deposit leg + an asset-id field exist, `SteamDeliveryService` resolves
the asset id from a config override `steam.delivery.test-asset-id` (for a
staging/demo item the bot already holds) and otherwise records a `NO_ASSET_ID`
attempt and leaves the trade to the existing manual/sweeper flow. **This is the
one thing that makes end-to-end auto-delivery real vs. mocked.**

Recommended follow-up (separate task, would touch seller/listing/trade flow):
- Add a deposit step (bot receives item, store its `assetId`), persist that
  `assetId` on the `Trade` (or a side table), and have `resolveAssetId(trade)`
  read it.

---

## GAP 2 (informational): multi-pod claim

If the app runs multiple pods, two pods could both poll the same trade. The
existing `Trade` `@Version` optimistic lock + the `buyerConfirm` state gate make
a **double-credit impossible** (the second `buyerConfirm` throws and is caught).
The only duplicate-able side effect is a redundant `steam_delivery_attempts`
audit row and possibly a duplicate "offer sent" notification. If that matters,
add an atomic claim (mirroring `TradeRepository.claimReviewNudge`) before send.
Not required for correctness.

---

## Configuration

Backend (`application.yml` / env), all optional with safe defaults:

| Property | Env | Default | Meaning |
|---|---|---|---|
| `steam.bot.base-url` | `STEAM_BOT_BASE_URL` | *(empty)* | Sidecar URL. **Empty ⇒ bot disabled** (dev/test no-op). |
| `steam.bot.api-token` | `BOT_API_TOKEN` | *(empty)* | Shared bearer token (must match sidecar). |
| `steam.bot.connect-timeout-ms` | — | `5000` | HTTP connect timeout. |
| `steam.bot.request-timeout-ms` | — | `15000` | HTTP request timeout. |
| `steam.delivery.enabled` | — | `true` | Master switch for the poller. |
| `steam.delivery.poll-interval-ms` | — | `30000` | Poll cadence. |
| `steam.delivery.initial-delay-ms` | — | `20000` | Initial delay before first poll. |
| `steam.delivery.batch-size` | — | `50` | Max trades processed per tick per queue. |
| `steam.delivery.offer-message` | — | `sboxmarket delivery` | Message on outgoing offers. |
| `steam.delivery.test-asset-id` | — | *(empty)* | Staging asset-id override (see GAP 1). |

The DEPOSIT leg has its own keys, all read by `SteamEscrowService` and all
inert while `steam.bot.base-url` is empty:

| Property | Default | Meaning |
|---|---|---|
| `steam.escrow.enabled` | `true` | Master switch for the escrow sweeps (independent of the bot's own flag). |
| `steam.escrow.offer-message` | `sboxmarket escrow` | Message on deposit-request + return offers. |
| `steam.escrow.batch-size` | `50` | Deposit-confirm candidates per tick. |
| `steam.escrow.initial-delay-ms` / `poll-interval-ms` | `25000` / `30000` | Deposit-confirm poller cadence. |
| `steam.escrow.deposit-timeout-hours` | `24` | Hours a deposit may sit `PENDING_DEPOSIT` before custody → `FAILED` and the held listing `PENDING_ESCROW` → `CANCELLED`. |
| `steam.escrow.timeout-initial-delay-ms` / `timeout-interval-ms` | `60000` / `3600000` | Deposit-timeout sweeper cadence. |
| `steam.escrow.deposit-retry-backoff-ms` | `300000` | Minimum gap between two deposit re-requests for one listing. |
| `steam.escrow.deposit-retry-initial-delay-ms` / `deposit-retry-interval-ms` | `75000` / `300000` | Unsent-deposit re-request sweeper cadence. |
| `steam.escrow.return-retry-backoff-ms` | `600000` | Minimum gap between two return attempts for one item. |
| `steam.escrow.return-retry-initial-delay-ms` / `return-retry-interval-ms` | `90000` / `600000` | Pending-return sweeper cadence. |
| `steam.escrow.return-alert-attempts` | `5` | Attempts past which a still-held item logs at ERROR. Not a give-up threshold — the return sweep never stops. |

Scheduling is already enabled app-wide (`@EnableScheduling` on
`SboxMarketApplication`), so no extra config bean is needed.

---

## The relist path refuses while escrow is live

`SellService.relist` (POST `/api/listings/sell` — "sell an item from your
platform inventory") is the **second** way a listing gets created, and it does
not and cannot go through the deposit leg. It creates its row `ACTIVE`, so with
the bot live a relisted item was buyable while the bot held nothing: the buyer
pays, a Trade opens, `resolveAssetId` finds no custody row, records
`NO_ASSET_ID`, and the sale stalls until the 3-day auto-cancel refunds the buyer.

It cannot be routed through `requestDepositForListing` because that call needs a
**current, real, seller-owned asset id** to hand the bot, and a relist has none:

- platform/house-bought items never had a Steam asset (`Listing.assetId` is null);
- for a Steam-sourced item the stored `assetId` is the **original seller's** —
  Steam reassigns asset ids on every trade, which is precisely why
  `fetchBotInventoryIndex` keys the bot inventory by `market_hash_name` as well
  as by id.

Passing either in would be worse than the gap: `relist` flips the source row to
`RELISTED` before creating the fresh one, the fresh row would hold in
`PENDING_ESCROW` against a deposit Steam can never fill, and
`sweepStalePendingDeposits` cancels it 24h later **without** returning the item —
so the seller's item would leave their platform inventory for good.

So `relist` refuses, pre-flight, before any read or write:
`BadRequestException("RELIST_NEEDS_STEAM_DEPOSIT", …)`, pointing the seller at
the Sell modal's **Steam tab**, which is the path that can escrow properly. Same
posture as `SteamInventoryController.requireSellerTradeUrl`. With the bot
unconfigured the guard is inert and relist behaves exactly as it always has.
Pinned by `RelistEscrowGateSpec`.

---

## What is real vs. mocked (honest summary)

- **Real:** the Node sidecar logs in a Steam bot, sends/auto-confirms trade
  offers for app 590830, polls offer status, accepts incoming offers, reads
  inventory — all via `steam-tradeoffer-manager`/`steamcommunity`/`steam-totp`.
- **Real:** the Groovy client + orchestrator + offer tracking + the wiring into
  the existing `sellerMarkSent`/`buyerConfirm` release path (frozen money math).
- **Mocked in tests:** the sidecar HTTP (Spock stubs `doRequest`) and the bot
  service / repositories / TradeService (Spock mocks). No live Steam calls in CI.
- **Real (GAP 1 is CLOSED — this bullet used to say "not yet built"):** the
  seller→bot **deposit leg** and the persisted asset id both exist.
  `SteamEscrowService` requests the seller's specific asset, confirms custody
  against the bot's own inventory before the listing becomes buyable, times out
  a deposit the seller never accepts, and returns a held item on
  cancel/expiry/trade-cancel. The id lives on `EscrowedItem`
  (`assetId` as requested, `heldAssetId` as actually received) keyed by
  `listingId` — no column was added to `Trade` — and
  `SteamDeliveryService.resolveAssetId` reads it first, falling back to
  `steam.delivery.test-asset-id` only as an explicit staging override.
- **Not covered by the deposit leg:** relisting from platform inventory. It has
  no asset id to deposit and now refuses while the bot is live — see "The relist
  path refuses while escrow is live" above.
- **Not verifiable from this repo:** whether the sidecar has ever been run
  against a real Steam account. That is an operator fact, not a code fact; the
  status note above asserts it has not.
