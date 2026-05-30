# Audit backlog — 2026-05 bug-hunt wave

Three parallel read-only hunters (frontend JS, adversarial money-paths, backend
concurrency) swept the now-rendering app. The transaction/money/multi-pod surface
is exhaustively hardened after ~170 prior waves, so fresh bugs concentrate in
read-path/memory, FX display, and subtle check-then-act corners. Each item below
has file:line + a concrete fix from the hunter that found it.

## ✅ Fixed in this wave
- **Deposit-cap concurrency bypass (P1, money).** `StripeService.createDepositSession`
  read `used24h` then acted with no wallet lock → ~20 parallel deposits blew the
  $5k/24h cap to ~$190k. Now reads via `WalletRepository.findByIdForUpdate`
  (PESSIMISTIC_WRITE), serializing per wallet. Pinned by StripeServiceSpec.
- **TradeProtection.reverseClaim double-clawback (P2, money).** Used unlocked
  `findByTradeId` while sibling `autoClaim` locks → two concurrent reversals both
  debit the cover. Now uses `findByTradeIdForUpdate`.

## P1 — do next
- **CSR goodwill credit has no cumulative cap.** `CsrService.issueGoodwillCredit`
  (~406-477) caps each call at $25 but nothing caps call count → compromised/colluding
  CSR drains unbounded (~$180k/hr via the 20/10s limiter). Fix: rolling per-CSR 24h
  cap (sum `ADJUSTMENT_CREDIT` where `stripeReference='csr_${csrId}'` in 24h), mirror
  the deposit/withdraw window. The class docstring currently *claims* this is safe — it isn't.
- **ReviewService.pendingReviewsFor (~404) unbounded hydrate.** Calls un-paged
  `findUnreviewedByBuyer(uid)` then `.take(50)` in memory; a paged overload exists
  for exactly this. Hot-path (`/api/profile/pending-reviews`) memory+DB DoS. Fix:
  `findUnreviewedByBuyer(uid, PageRequest.of(0,50))`, drop the in-memory take.
- **ReviewService.eligibleTradesFor (~439-442) unbounded.** Un-paged `findVerifiedBetween`
  + `findByFromUserId(buyer)` (every review the buyer EVER wrote, all sellers) to build
  one pair's reviewed-set. Fix: `reviewRepository.findByTradeIdIn(trades*.id)` (add it)
  and page `findVerifiedBetween`.
- **Frontend Buy double-submit.** `handleBuy` (app.js:5107-5156) + all 3 Buy buttons
  (cards.js:538-554, modals.js:611-631, modals.js:1637-1649) have NO in-flight guard —
  the only money action lacking one. Double-click fires two POSTs (stale wallet check;
  on ItemModal can buy the next-cheapest listing). Fix: `buyingId` state, disable +
  "Buying…" during the await, mirror `cartBusy`/`offerBusy`.
- **Frontend `tradeOfferUrl` rendered as raw href (stored-XSS, defense-in-depth).**
  modals.js:7378, modals.js:7894 (buyer), staff-modals.js:1633 (staff). Seller-supplied
  URL → `href` with no render-time scheme check; React does NOT block `javascript:`;
  the client regex is API-bypassable (api.js:1769). Fix: only render the anchor when
  `/^https:\/\/steamcommunity\.com\/tradeoffer\//.test(url)`, else fall back to the
  generic Steam-inbox link already alongside it.

## P2
- **TransactionRepository.sumRefundsByDeposit (~363)** uses `LIKE '%deposit #1%'` →
  matches #1,#10,#100. Over-counts low ids in the cumulative-refund guard (admin-only,
  makes the cap stricter). Fix: `refundedDepositId` FK column, or an anchored/bracketed token.
- **WatchlistAlertService.fireRow (~380-405)** sends the price-drop EMAIL synchronously
  inside the uncommitted REQUIRES_NEW `sweepForItem` tx; a commit failure rolls the
  FIRED flip back but the email already left → next sweep re-sends (5-min dedup is racy
  at the cadence boundary). Fix: send on `afterCommit`, like NotificationService.push.
- **SavedSearchService.notifyMatchingForListing (~375-408)** un-paged
  `findCandidatesForListing` + per-owner `isBlocked` N+1 on the seller's listing-create
  thread. Fix: Pageable cap (≤500) + single bulk block-set query.
- **SupportService.reply (~276-286)** computes `createdAt = max(now, lastInThread+1)`
  via non-atomic read → two concurrent replies collide on the same stamp, re-opening the
  ordering bug. Fix: order by `(createdAt, id)` tiebreak, or a per-ticket seq column.
- **Frontend FX double-display.** Sell proceeds (modals.js:10108-10125) + wallet
  fee-breakdown (modals.js:14863-14871) pass USD-field amounts through `fmt()` (which
  multiplies by the FX rate), contradicting the "(USD)" label. Fix: render fixed `$`
  or append "(in USD)" like the deposit hint already does.
- **Frontend pending-withdrawal Cancel double-submit** (modals.js:14494-14508) — no
  in-flight guard. Fix: `cancellingId` state.
- **Frontend 401 console spam.** api.js `safeJson` logs `console.warn` on 401 (not in
  the muted list) even though the session-expired event is handled. Fix: mute 401.

## P3
- **utils.js:85 `fmtCompact`** has an unreachable `>=1000 → M` branch (dead code). Delete.

## Verified-safe (hunters explicitly checked — no action)
Money: double-spend (buy/cancel, offer-accept+buy, buy-order fill+manual), trade-escrow
double-payout, withdraw self-cancel vs admin-reject, self-dealing (buy/offer/bid/review/
vote/follow), negative/zero/overflow amounts (all `setScale(2,HALF_UP)`), state-replay.
Frontend: cart race (`loadSeqRef`), offer/bid busy guards + first-bid floor, deposit/
withdraw busy guard, filter↔URL sync, NaN guards, no `dangerouslySetInnerHTML`. Backend:
NotificationService deferral/markAllRead-set, WatchlistAlert upsert round-then-validate,
SavedSearch discount division (Groovy `/` rounds, no throw), SteamMarket locale parse,
TOTP replay/drift/constant-time, EmailService unsubscribe HMAC, Announcement boundaries.
