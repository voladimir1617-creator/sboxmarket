# Automated Steam Delivery — Integration Notes

This documents how the new automated Steam trade-offer delivery (bot-escrow)
plugs into the **existing** escrow state machine, the two real integration gaps,
and exactly what (if anything) the TradeService owner needs to add. All delivery
code is in **new files only**; `TradeService.groovy`, `StripeService.groovy`,
`WalletController.groovy`, and the `Wallet`/`SteamUser` models were **not** edited.

---

## New files added

Backend (Spring Boot / Groovy):
- `src/main/groovy/com/sboxmarket/service/SteamTradeBotService.groovy` — HTTP client to the bot sidecar.
- `src/main/groovy/com/sboxmarket/service/SteamBotResult.groovy` — typed, never-throwing result.
- `src/main/groovy/com/sboxmarket/service/SteamDeliveryService.groovy` — `@Service` + `@Scheduled` poller/orchestrator.
- `src/main/groovy/com/sboxmarket/model/SteamDeliveryAttempt.groovy` — audit + authoritative offer-tracking entity.
- `src/main/groovy/com/sboxmarket/repository/SteamDeliveryAttemptRepository.groovy` — repo for the above.
- `src/main/resources/db/migration/V200__steam_delivery_attempts.sql` — Flyway migration (version **V200**).

Tests:
- `src/test/groovy/com/sboxmarket/service/SteamTradeBotServiceSpec.groovy`
- `src/test/groovy/com/sboxmarket/service/SteamDeliveryServiceSpec.groovy`

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

Scheduling is already enabled app-wide (`@EnableScheduling` on
`SboxMarketApplication`), so no extra config bean is needed.

---

## What is real vs. mocked (honest summary)

- **Real:** the Node sidecar logs in a Steam bot, sends/auto-confirms trade
  offers for app 590830, polls offer status, accepts incoming offers, reads
  inventory — all via `steam-tradeoffer-manager`/`steamcommunity`/`steam-totp`.
- **Real:** the Groovy client + orchestrator + offer tracking + the wiring into
  the existing `sellerMarkSent`/`buyerConfirm` release path (frozen money math).
- **Mocked in tests:** the sidecar HTTP (Spock stubs `doRequest`) and the bot
  service / repositories / TradeService (Spock mocks). No live Steam calls in CI.
- **Not yet built (GAP 1):** the seller→bot **deposit leg** + a persisted
  per-trade **asset id**. Without it, the send leg has no concrete item to give,
  so production auto-delivery needs that follow-up (or the staging override) to
  be genuinely end-to-end.
