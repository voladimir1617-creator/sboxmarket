package com.sboxmarket.service

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.BuyOrder
import com.sboxmarket.model.Listing
import com.sboxmarket.repository.BuyOrderRepository
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.security.BanGuard
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Lazy
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

/**
 * Standing buy orders — when a new listing is created (or an existing one has its
 * price lowered) this service finds all matching active orders and attempts an
 * auto-purchase using the first buyer who can afford it. Ordered by maxPrice DESC
 * then createdAt ASC so the buyer offering most and queued longest wins ties.
 */
@Service
@Slf4j
class BuyOrderService {

    @Autowired BuyOrderRepository buyOrderRepository
    @Autowired ItemRepository itemRepository
    @Autowired WalletRepository walletRepository
    @Autowired SteamUserRepository steamUserRepository
    @Autowired NotificationService notificationService
    @Autowired TextSanitizer textSanitizer
    @Autowired BanGuard banGuard
    @Autowired(required = false) com.sboxmarket.repository.TransactionRepository transactionRepository
    @Autowired(required = false) com.sboxmarket.repository.ListingRepository listingRepository
    @Autowired(required = false) EmailService emailService

    // Lazy to break the circular dependency: PurchaseService has no inbound
    // references here, but we are *called from* ListingService which in turn
    // calls PurchaseService. Using @Lazy keeps Spring's graph happy.
    @Autowired @Lazy PurchaseService purchaseService

    /** Optional so unit tests that build the service with `new
     *  BuyOrderService(...)` (no Spring context) still work — in that case
     *  there is never an active transaction and {@link #runInIsolatedTx}
     *  runs the work inline so the mocked-repo assertions still fire. */
    @Autowired(required = false) PlatformTransactionManager transactionManager

    /**
     * Run {@code work} in a fresh REQUIRES_NEW transaction when a
     * PlatformTransactionManager is wired (production), otherwise run
     * inline (Spock unit tests that build BuyOrderService without a
     * Spring context).
     *
     * Why this matters for the auto-fill loop: `purchaseService.buy` is
     * itself {@code @Transactional}. When the matcher's outer caller is
     * also transactional (create / update / tryFillFromExisting itself
     * carries {@code @Transactional}), Spring's PROPAGATION_REQUIRED
     * makes the inner buy join the SHARED outer transaction. A buy that
     * throws ({@code ListingNotAvailableException} from a sniped listing,
     * {@code InsufficientBalanceException} from a concurrent withdraw,
     * an OptimisticLockingFailureException from a versioned Wallet
     * collision) triggers Spring's inner-proxy
     * {@code setRollbackOnly()} on the SHARED outer tx BEFORE the
     * exception escapes back to our try/catch. The catch then swallows
     * the throw and the loop tries the next listing — but the outer tx
     * is already poisoned. At commit time the outer commit blows up
     * with {@code UnexpectedRollbackException} and EVERY "successful"
     * fill the loop landed (debit, listing→SOLD, Trade row, BuyOrder
     * decrement) is rolled back along with the freshly-INSERTed BuyOrder
     * from {@link #create}. The buyer's API returned 201 Created but
     * their order doesn't exist in the DB, listings the loop "bought"
     * are still ACTIVE, and no notification ever fires.
     *
     * Running each buy in its own REQUIRES_NEW sub-tx means a failed
     * fill rolls back ONLY that sub-tx — the outer tx is never
     * poisoned, the next listing iteration proceeds cleanly, and
     * successful fills durably commit. The outer pessimistic lock on
     * the BuyOrder row (acquired via findByIdForUpdate) is still held
     * by the outer tx throughout the loop, so concurrent fills against
     * the same order remain serialised.
     */
    private void runInIsolatedTx(Closure work) {
        if (transactionManager == null) {
            work()
            return
        }
        def tt = new TransactionTemplate(transactionManager)
        tt.propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        tt.executeWithoutResult { work() }
    }

    // Per-buyer ACTIVE buy-order cap. Prevents a single account from
    // queueing 100k+ standing orders that tie up the matcher (the matcher
    // scans all ACTIVE orders when a listing lands, so a large backlog
    // from one buyer would slow every listing creation). 200 is generous
    // — a serious collector might want 50-100 open orders across
    // different items, rarities, and price ceilings — while still
    // capping the attack surface. CANCELLED / FILLED / EXPIRED orders
    // don't count, so the cap is pressure to manage the active set.
    static final long MAX_ACTIVE_ORDERS_PER_BUYER = 200L

    @Transactional
    BuyOrder create(Long buyerUserId, String buyerName, Long itemId, String category,
                    String rarity, BigDecimal maxPrice, Integer quantity) {
        banGuard.assertNotBanned(buyerUserId)
        if (buyerUserId != null
                && buyOrderRepository.countActiveByBuyer(buyerUserId) >= MAX_ACTIVE_ORDERS_PER_BUYER) {
            throw new BadRequestException("BUY_ORDER_CAP",
                "You've reached the ${MAX_ACTIVE_ORDERS_PER_BUYER}-active-order cap. " +
                "Cancel one from Profile → Buy Orders before placing another.")
        }
        // Upfront trade-URL gate (batch 388). When a matching listing comes
        // along the matcher calls PurchaseService.buy, which (since batch
        // 380) refuses a buyer without a Steam trade URL. Without this
        // pre-check the buy order silently sits ACTIVE while every match
        // attempt throws and is swallowed — the buyer never sees why
        // nothing fills. Reject at creation time so the buyer fixes their
        // profile before committing to a standing order.
        def buyer = steamUserRepository?.findById(buyerUserId)?.orElse(null)
        if (buyer != null && !buyer.tradeUrl?.trim()) {
            throw new BadRequestException("TRADE_URL_MISSING",
                "Set your Steam trade URL in Profile before placing a buy order — sellers need it to send you the item when a match fills.")
        }
        // Wallet freeze gate (batch 511). A frozen buyer's buy order
        // would sit ACTIVE forever because every match attempt throws
        // WALLET_FROZEN in PurchaseService.buy and the matcher swallows
        // the exception — invisible failure. Reject at creation so the
        // buyer knows why nothing fills.
        if (buyer != null) {
            def buyerWallet = walletRepository.findByUsername("steam_${buyer.steamId64}")
            if (buyerWallet != null && Boolean.TRUE.equals(buyerWallet.frozen)) {
                throw new BadRequestException("WALLET_FROZEN",
                    "Your wallet is frozen by staff" +
                        (buyerWallet.frozenReason ? ": ${buyerWallet.frozenReason}" : '') +
                        ". Open a support ticket to resolve.")
            }
            // Dispute-hold fail-early (batch 511). Same rationale as
            // the WALLET_FROZEN check above: a buy order from a user
            // with an active chargeback would silently never fill
            // because the matcher's PurchaseService.buy rejects with
            // PURCHASE_DISPUTE_HOLD. Refuse at creation time.
            if (buyerWallet != null && transactionRepository != null) {
                long disputed = transactionRepository.countActiveDisputedDeposits(buyerWallet.id)
                if (disputed > 0L) {
                    throw new BadRequestException("PURCHASE_DISPUTE_HOLD",
                        "Buy orders are paused while you have ${disputed} unresolved deposit " +
                        "dispute${disputed == 1 ? '' : 's'} on file.")
                }
            }
        }
        if (maxPrice == null || maxPrice <= BigDecimal.ZERO) {
            throw new BadRequestException("INVALID_PRICE", "Max price must be positive")
        }
        // Defense-in-depth upper cap. The DTO layer already enforces this
        // (@DecimalMax 100000), but services can be called without going
        // through a controller (scheduled jobs, other services, tests).
        if (maxPrice > new BigDecimal("100000")) {
            throw new BadRequestException("PRICE_TOO_HIGH", "Max price must not exceed \$100,000")
        }
        // Cap quantity at a defensive maximum so a buyer can't queue a
        // 1_000_000-item order that ties up the matching engine.
        int q = Math.min(Math.max(1, quantity ?: 1), 100)
        String snapName = null
        if (itemId != null) {
            def item = itemRepository.findById(itemId).orElse(null)
            if (item != null) snapName = item.name
        }
        // Whitelist the free-text fields so attacker input never lands in
        // the DB. We only use these for display — never for filtering — so
        // they can be plain-text-only.
        def safeCategory = (category in ['Hats','Jackets','Shirts','Pants','Gloves','Boots','Accessories']) ? category : null
        def safeRarity   = (rarity in ['Limited','Off-Market','Standard']) ? rarity : null

        def order = new BuyOrder(
            buyerUserId:      buyerUserId,
            buyerName:        textSanitizer.cleanShort(buyerName),
            itemId:           itemId,
            itemName:         textSanitizer.cleanShort(snapName),
            category:         safeCategory,
            rarity:           safeRarity,
            maxPrice:         maxPrice,
            quantity:         q,
            originalQuantity: q
        )
        def saved = buyOrderRepository.save(order)
        // Auto-fill against EXISTING active listings — closes a real
        // user-visible bug where a buy order at $30 sat un-fired even
        // when a $25 listing was already on the market. The original
        // matching engine only fired on the listing-creation side, so
        // a freshly-placed buy order had to wait for a future relist
        // before it would clear. Best-effort: failure here doesn't
        // un-save the order; the standing order keeps its place.
        try {
            tryFillFromExisting(saved)
        } catch (Exception e) {
            log.warn("Buy order ${saved.id} initial fill attempt failed: ${e.message}")
        }
        saved
    }

    /**
     * Walk the cheapest-first ACTIVE listings that match the buy
     * order's filter and price ceiling, attempting an auto-purchase
     * with the buyer's wallet. Re-uses every gate `tryMatch` already
     * applies (skip self, balance check, swallow per-row failures).
     *
     * Stops as soon as the order's quantity hits zero or no more
     * candidates remain. Capped at 50 listing probes to bound cost on
     * a hot category.
     */
    @Transactional
    void tryFillFromExisting(BuyOrder order) {
        if (order == null || listingRepository == null) return
        // Re-load under a pessimistic row lock so concurrent fills of the
        // SAME order serialise (see findByIdForUpdate) — without it two
        // matching listings can both decrement a quantity-1 order and
        // over-charge the buyer. Falls back to the in-hand order when the
        // lock query is unconfigured (a Mock repo in a unit test) or the
        // order has no id yet.
        if (order.id != null) {
            order = buyOrderRepository.findByIdForUpdate(order.id) ?: order
        }
        if (order.status != 'ACTIVE' || order.quantity <= 0) return
        def candidates = listingRepository.findMatchingForBuyOrder(
            order.itemId, order.category, order.rarity, order.maxPrice,
            org.springframework.data.domain.PageRequest.of(0, 50)
        )
        // Remaining spendable balance for THIS fill loop, tracked locally.
        // Within this parent @Transactional the persistence context hands back
        // the SAME cached Wallet instance on every iteration, so its `balance`
        // never reflects the prior iteration's REQUIRES_NEW buy() debit — a
        // plain re-read would keep showing the stale pre-fill balance and let
        // the affordability check pass over-optimistically (the buy then fails
        // in its sub-tx, caught + logged, but the loop reads inconsistently and
        // churns). Seed from the first (fresh) read, decrement on each fill.
        // buy() remains the authoritative fresh-read + @Version money guard.
        // (integrity-audit fix)
        def available = null
        for (Listing listing : candidates) {
            if (order.quantity <= 0 || order.status != 'ACTIVE') break
            if (listing.sellerUserId != null && listing.sellerUserId == order.buyerUserId) continue
            // Banned-seller skip (wave 141 parity with
            // OfferService.tryAutoAccept). PurchaseService.buy gates
            // the buyer's ban state but not the seller's, so without
            // this skip the matcher could route the buyer's wallet
            // into a banned seller's escrow. Walk to the next
            // candidate so we still try unfilled inventory from
            // legitimate sellers at the same price tier. System
            // listings (sellerUserId == null) bypass.
            if (listing.sellerUserId != null && banGuard.isBanned(listing.sellerUserId)) continue

            def user = steamUserRepository.findById(order.buyerUserId).orElse(null)
            if (user == null) break
            def wallet = walletRepository.findByUsername("steam_${user.steamId64}")
            if (wallet == null) break
            if (available == null) available = wallet.balance   // first read is fresh
            if (available < listing.price) {
                // Out of money — no point checking the next listing,
                // they all cost > the cheapest unaffordable one. The
                // candidate set is sorted ASC. `available` reflects this
                // loop's prior fills (the cached wallet.balance would not).
                break
            }
            // Cap guard — never auto-buy above the order's price ceiling.
            // The candidate query already filters on maxPrice, but the
            // pessimistic re-load above can surface a maxPrice the buyer
            // lowered after the query; buy() charges whatever the listing
            // says, with no knowledge of the order's cap.
            if (listing.price == null || listing.price > order.maxPrice) continue
            try {
                // REQUIRES_NEW sub-tx around the buy (see runInIsolatedTx).
                // Without this, a single sniped/over-debited listing throws
                // ListingNotAvailableException / InsufficientBalanceException
                // out of PurchaseService.buy, Spring's inner @Transactional
                // proxy marks the SHARED outer tx rollback-only BEFORE the
                // throw escapes, the catch below swallows the exception, and
                // the loop continues — but the outer tx is poisoned. Every
                // later successful fill, the BuyOrder decrement, and the
                // freshly-INSERTed BuyOrder from create() all roll back at
                // outer commit while the buyer's API response said 201
                // Created. Running each buy in its own sub-tx isolates the
                // failure to that one listing.
                runInIsolatedTx {
                    purchaseService.buy(wallet.id, order.buyerUserId, listing.id)
                }
                available = available - listing.price   // only on a committed fill
                order.quantity = Math.max(0, order.quantity - 1)
                order.updatedAt = System.currentTimeMillis()
                if (order.quantity == 0) order.status = 'FILLED'
                buyOrderRepository.save(order)
                notificationService.push(
                    order.buyerUserId,
                    'BUY_ORDER_FILLED',
                    "Buy order auto-filled · ${listing.item?.name}",
                    "Paid \$${listing.price.toPlainString()} (cap \$${order.maxPrice.toPlainString()})",
                    listing.id,
                    '/profile?tab=buyorders'
                )
                fireBuyOrderFilledEmail(order, listing)
                log.info("Buy order ${order.id} auto-filled by EXISTING listing ${listing.id}")
            } catch (Exception e) {
                log.warn("Buy order ${order.id} initial-fill attempt against listing ${listing.id} failed: ${e.message}")
            }
        }
    }

    /** Display cap on the Profile → Buy Orders tab. 300 matches the
     *  Offers tab (OFFER_LIST_CAP, batch 1008) since buy orders have
     *  similar lifecycle churn — ACTIVE ones stay capped at 200 via
     *  MAX_ACTIVE_ORDERS_PER_BUYER (batch 1000), but CANCELLED / FILLED /
     *  EXPIRED accumulate unbounded. Display-only. */
    static final int BUY_ORDER_LIST_CAP = 300

    List<BuyOrder> listForBuyer(Long buyerUserId) {
        if (buyerUserId == null) return []
        buyOrderRepository.findByBuyerPaged(buyerUserId,
            org.springframework.data.domain.PageRequest.of(0, BUY_ORDER_LIST_CAP))
    }

    /** Total buy-order count for the X-Total-Count header on
     *  /api/buy-orders. */
    long countForBuyer(Long buyerUserId) {
        if (buyerUserId == null) return 0L
        buyOrderRepository.countByBuyer(buyerUserId)
    }

    /** Top-of-book for an item — highest maxPrice among ACTIVE buy
     *  orders pinned to the item. Returns 0 when no demand. */
    BigDecimal bestBidForItem(Long itemId) {
        if (itemId == null) return BigDecimal.ZERO
        buyOrderRepository.findBestBidForItem(itemId) ?: BigDecimal.ZERO
    }

    /** Top-N active buy orders site-wide, sorted by maxPrice — drives
     *  the homepage "Top buy orders" rail (batch 369). Decorates each
     *  row with item name + image so the frontend can render without
     *  a second round-trip. Buyer identity is deliberately omitted
     *  (aggregate demand signal only). */
    List<Map> listTopActive(int limit) {
        int lim = Math.min(Math.max(limit, 1), 20)
        def rows = buyOrderRepository.findTopActive(
            org.springframework.data.domain.PageRequest.of(0, lim))
        if (rows.isEmpty()) return []
        def itemIds = rows*.itemId.findAll { it != null }.unique()
        def itemsById = [:]
        if (!itemIds.isEmpty()) {
            itemRepository.findAllById(itemIds).each { itemsById[it.id] = it }
        }
        rows.collect { b ->
            def item = itemsById[b.itemId]
            [
                id:           b.id,
                itemId:       b.itemId,
                itemName:     item?.name ?: b.itemName,
                itemImageUrl: item?.imageUrl,
                category:     b.category ?: item?.category,
                rarity:       b.rarity   ?: item?.rarity,
                maxPrice:     b.maxPrice,
                createdAt:    b.createdAt
            ]
        }
    }

    /** Public demand-count for an item — used by the item detail modal
     *  to render a "N buyers want this" chip. Aggregate only; no
     *  counterparty identities are exposed. */
    long countActiveForItem(Long itemId) {
        if (itemId == null) return 0L
        buyOrderRepository.countActiveForItem(itemId)
    }

    /**
     * Top-N active buy orders pinned to an item (batch 639). Drives the
     * "Buy Orders" table on the item detail modal — buyers see the
     * demand curve (top 10 bids sorted highest first), sellers can size
     * their asking price to what the market is actually paying.
     *
     * Counterparty identity is NOT surfaced (no buyer handle, no
     * avatar) — this mirrors the policy on every other aggregate buy-
     * order endpoint (topActive, countForItem, bulkDemand). Exposing
     * "user X wants this at $Y" would let scrapers target high-bid
     * buyers for private trade pitches. Only price + quantity + age
     * leak; those are what sellers need to size their listing.
     */
    List<Map> listActiveForItem(Long itemId, int limit) {
        if (itemId == null) return []
        int lim = Math.min(Math.max(limit, 1), 20)
        def rows = buyOrderRepository.findActiveForItem(itemId,
            org.springframework.data.domain.PageRequest.of(0, lim))
        if (rows.isEmpty()) return []
        rows.collect { b ->
            [
                id:        b.id,
                maxPrice:  b.maxPrice,
                quantity:  b.quantity,
                createdAt: b.createdAt
            ]
        }
    }

    /** Bulk {count, bestBid} per item (batch 415). Powers the MyStall
     *  "N want · best $X" chip without N+1 per-row queries. Returns a
     *  map keyed on itemId; missing keys mean no active buy orders. */
    Map<Long, Map> bulkDemandByItemIds(List<Long> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) return [:]
        def rows = buyOrderRepository.countAndBestBidByItemIds(itemIds.findAll { it != null })
        def out = [:]
        rows.each { row ->
            def id    = row[0] as Long
            def count = (row[1] as Number)?.longValue() ?: 0L
            def best  = (row[2] as BigDecimal) ?: BigDecimal.ZERO
            out[id] = [count: count, bestBid: best > BigDecimal.ZERO ? best : null]
        }
        out
    }

    /** Number of buy orders ahead of the given (itemId, maxPrice,
     *  createdAt) under the matching engine's priority. Add 1 at the
     *  call site to get the user-facing queue position (#1, #2, …).
     *  Returns 0 when the key is null so the controller can render
     *  no chip for basket orders (category-only, null itemId). */
    long countAheadInQueue(Long itemId, BigDecimal maxPrice, Long createdAt) {
        if (itemId == null || maxPrice == null) return 0L
        buyOrderRepository.countAheadInQueue(itemId, maxPrice, createdAt ?: 0L)
    }

    /** Batched queue-rank source for the "My Buy Orders" tab — one query for
     *  all the buyer's item ids instead of a per-row countAheadInQueue (N+1).
     *  Returns [itemId, maxPrice, createdAt] for every ACTIVE non-banned order
     *  on those items; the caller ranks each of its own orders in memory with
     *  the same maxPrice DESC, createdAt ASC priority. Empty in → empty out
     *  (avoids an `IN ()` query). */
    List<Object[]> activeQueueRowsForItems(Collection<Long> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) return []
        buyOrderRepository.activeQueueRowsForItems(itemIds)
    }

    @Transactional
    BuyOrder cancel(Long buyerUserId, Long orderId) {
        // Authorisation pre-check on an unlocked read — we need to verify
        // ownership BEFORE acquiring the pessimistic row lock so a stranger
        // probing /api/buy-orders/{id} can't park on a contention-hot row
        // (cheap DoS vector on a popular item).
        def probe = buyOrderRepository.findById(orderId)
            .orElseThrow { new NotFoundException("BuyOrder", orderId) }
        if (probe.buyerUserId != buyerUserId) {
            throw new ForbiddenException("Not your buy order")
        }
        // Pessimistic re-load — serialise cancel against concurrent fills
        // (tryMatch / tryFillFromExisting both acquire the same lock via
        // findByIdForUpdate). Without this, cancel's plain-read entity races
        // a concurrent fill: fill X-locks the row, sees ACTIVE qty=1, buys
        // the listing, decrements to qty=0, sets status=FILLED, commits;
        // cancel then writes status=CANCELLED on its stale snapshot,
        // overwriting the FILLED flag. The buyer has paid and received the
        // item, but their order shows CANCELLED in the Profile tab and CSV
        // — the audit trail lies, and a paranoid buyer reading "CANCELLED"
        // on a wallet debit they didn't expect rightly opens a support
        // ticket claiming fraud. Falls back to the probe row when the lock
        // query is unconfigured (Mock repo in unit tests).
        def o = (orderId != null ? buyOrderRepository.findByIdForUpdate(orderId) : null) ?: probe
        // Re-verify ownership on the locked row — defence-in-depth in the
        // (impossible-in-practice) case the id-to-owner mapping changed
        // between the probe read and the lock acquisition.
        if (o.buyerUserId != buyerUserId) {
            throw new ForbiddenException("Not your buy order")
        }
        // Already cancelled — idempotent no-op so a double-click / retry
        // doesn't error. Return the row untouched.
        if (o.status == 'CANCELLED') {
            return o
        }
        // FILLED / EXPIRED are terminal. Blindly stamping CANCELLED here
        // would rewrite history — a fulfilled order (buyer paid, items
        // delivered) would masquerade as cancelled in the Profile tab and
        // CSV export, and an auto-expired order would lose its EXPIRED
        // reason. Only an ACTIVE order can be cancelled, mirroring
        // update()'s NOT_ACTIVE guard and OfferService.withdrawOffer.
        if (o.status != 'ACTIVE') {
            throw new BadRequestException("NOT_ACTIVE",
                "Only active buy orders can be cancelled")
        }
        o.status = "CANCELLED"
        o.updatedAt = System.currentTimeMillis()
        buyOrderRepository.save(o)
    }

    /**
     * Bulk-cancel every ACTIVE buy order belonging to the user. Mirrors
     * `BidService.cancelAllAutoBidsForUser` so a user with a long-tail
     * of forgotten orders can liquidate their queue in one click rather
     * than N separate API calls (batch 290).
     *
     * Returns the count of rows flipped to CANCELLED. Per-row save
     * failures fall through to the next row — best-effort.
     *
     * NOT @Transactional — the per-row try/catch + repository.save() pattern
     * is the same Spring rollback-only leak wave 23 closed for fan-outs.
     * Spring Data's save() proxy marks the SHARED outer tx as rollback-only
     * the moment one inner save throws — the user's catch absorbs the throw
     * but the tx is already poisoned, so on method return the commit throws
     * UnexpectedRollbackException and every "successfully" cancelled row in
     * the batch is rolled back too. Zero cross-row invariant here — each
     * cancel is independent. Drop the outer tx so each save() runs in its
     * own implicit tx and a single bad row only loses that row, matching
     * the docstring's "Per-row save failures fall through" promise.
     */
    int cancelAllForUser(Long buyerUserId) {
        if (buyerUserId == null) return 0
        def active = buyOrderRepository.findByBuyer(buyerUserId)
            .findAll { it.status == 'ACTIVE' }
        if (active.isEmpty()) return 0
        int n = 0
        def now = System.currentTimeMillis()
        active.each { o ->
            try {
                o.status = 'CANCELLED'
                o.updatedAt = now
                buyOrderRepository.save(o)
                n++
            } catch (Exception e) {
                log.warn("Bulk cancel failed for order ${o.id}: ${e.message}")
            }
        }
        log.info("Bulk-cancelled ${n} buy order(s) for user ${buyerUserId}")
        n
    }

    /**
     * Edit a still-ACTIVE buy order: change the max price and/or the
     * quantity. Price validation mirrors create() (positive, capped at
     * $100k, DTO-bounded) and quantity mirrors the 1..100 floor/ceiling.
     * Only the owner can edit. Non-ACTIVE rows are frozen — once filled
     * or cancelled, editing would let a buyer retroactively lower a
     * cap they've already paid at.
     *
     * Raising the cap is the common case (the order hasn't matched yet,
     * buyer wants to be more competitive). Lowering the cap is also
     * allowed — worst case the order simply stops matching at the old
     * price, which is the user's call.
     *
     * When the cap is RAISED we re-run the existing-listing fill probe
     * (same as create() does on a fresh order). Without this a buyer who
     * edits a $20 order up to $30 while a $25 listing is already on the
     * market would see the order sit un-fired until some future relist
     * triggers tryMatch — the exact "standing order doesn't fire against
     * listings already present" bug that batch 268 fixed for create().
     */
    @Transactional
    BuyOrder update(Long buyerUserId, Long orderId, BigDecimal newMaxPrice, Integer newQuantity) {
        banGuard.assertNotBanned(buyerUserId)
        def o = buyOrderRepository.findById(orderId)
            .orElseThrow { new NotFoundException("BuyOrder", orderId) }
        if (o.buyerUserId != buyerUserId) {
            throw new ForbiddenException("Not your buy order")
        }
        if (o.status != 'ACTIVE') {
            throw new BadRequestException("NOT_ACTIVE",
                "Only active buy orders can be edited")
        }
        // Wallet-frozen + dispute-hold gate (parity with create(), batch 511).
        // Without this, a buyer frozen / disputed AFTER they placed an order
        // can edit it (e.g. raise the cap) and the subsequent
        // tryFillFromExisting probe attempts a purchase that PurchaseService.buy
        // refuses inside the swallowed catch block — invisible failure, the
        // exact bug the create-time gate exists to prevent. Symmetric guard
        // here so a held buyer is told *why* before they bother editing.
        def buyer = steamUserRepository?.findById(buyerUserId)?.orElse(null)
        if (buyer != null) {
            def buyerWallet = walletRepository.findByUsername("steam_${buyer.steamId64}")
            if (buyerWallet != null && Boolean.TRUE.equals(buyerWallet.frozen)) {
                throw new BadRequestException("WALLET_FROZEN",
                    "Your wallet is frozen by staff" +
                        (buyerWallet.frozenReason ? ": ${buyerWallet.frozenReason}" : '') +
                        ". Open a support ticket to resolve.")
            }
            if (buyerWallet != null && transactionRepository != null) {
                long disputed = transactionRepository.countActiveDisputedDeposits(buyerWallet.id)
                if (disputed > 0L) {
                    throw new BadRequestException("PURCHASE_DISPUTE_HOLD",
                        "Buy orders are paused while you have ${disputed} unresolved deposit " +
                        "dispute${disputed == 1 ? '' : 's'} on file.")
                }
            }
        }
        BigDecimal priceBefore = o.maxPrice
        if (newMaxPrice != null) {
            if (newMaxPrice <= BigDecimal.ZERO) {
                throw new BadRequestException("INVALID_PRICE", "Max price must be positive")
            }
            if (newMaxPrice > new BigDecimal("100000")) {
                throw new BadRequestException("PRICE_TOO_HIGH",
                    "Max price must not exceed \$100,000")
            }
            o.maxPrice = newMaxPrice
        }
        if (newQuantity != null) {
            int q = Math.min(Math.max(1, newQuantity), 100)
            // Cap at the order's CURRENT remaining quantity — never the
            // original. For a partially-filled order, fills already
            // consumed (originalQuantity − quantity) are locked in, so
            // the most the buyer may still receive is the remaining
            // `o.quantity`. Capping at originalQuantity instead would let
            // a buyer who placed qty 5, took 3 fills, then edited back up
            // to 5 receive 3 + 5 = 8 items total — past the original cap
            // they committed to. Shrinking is always fine (drops the
            // remaining fills); for an untouched order quantity ==
            // originalQuantity so the ceiling is unchanged.
            int cap = (o.quantity != null && o.quantity > 0) ? o.quantity : 1
            q = Math.min(q, cap)
            o.quantity = q
        }
        o.updatedAt = System.currentTimeMillis()
        def saved = buyOrderRepository.save(o)
        // Re-probe existing listings only when the cap was RAISED — a
        // lower (or unchanged) cap can never newly enable a match, so
        // there's nothing to fill. Best-effort: a probe failure must not
        // un-do the edit, exactly as in create().
        if (priceBefore != null && saved.maxPrice != null
                && saved.maxPrice > priceBefore
                && saved.status == 'ACTIVE') {
            try {
                tryFillFromExisting(saved)
            } catch (Exception e) {
                log.warn("Buy order ${saved.id} re-fill after price raise failed: ${e.message}")
            }
        }
        saved
    }

    /**
     * Called by ListingService whenever a listing becomes visible. Walks matching
     * active orders and tries to fill them. Any exception during a single match is
     * swallowed so one failed auto-purchase never blocks the listing from going live.
     */
    @Transactional
    void tryMatch(Listing listing) {
        if (listing == null || listing.status != 'ACTIVE' || listing.listingType != 'BUY_NOW') return
        if (Boolean.TRUE.equals(listing.hidden)) return
        // Banned-seller short-circuit (wave 141 parity with
        // OfferService.tryAutoAccept). PurchaseService.buy only
        // asserts the BUYER isn't banned — it does not gate on the
        // seller. Without this check, a seller banned AFTER posting
        // a listing can still get their listing auto-purchased via
        // a matching buy order: buyer's wallet drains, listing flips
        // SOLD, and the banned seller's wallet would be credited
        // through the escrow Trade row. The matching engine MUST
        // refuse to route any new sale to a banned seller — the
        // listing should sit until the ListingService sweeper takes
        // it down. System listings (sellerUserId == null) skip.
        if (listing.sellerUserId != null && banGuard.isBanned(listing.sellerUserId)) return

        // Cap the candidate list at 50 — the first matching order with
        // enough balance wins, so iterating every single matching order
        // for a hot item wastes wallet lookups. 50 is plenty of headroom
        // for skipping insolvent candidates.
        def candidates = buyOrderRepository.findMatching(
            listing.item?.id, listing.item?.category, listing.item?.rarity, listing.price,
            org.springframework.data.domain.PageRequest.of(0, 50)
        )
        for (BuyOrder order : candidates) {
            if (order.quantity <= 0 || order.status != 'ACTIVE') continue
            if (listing.sellerUserId != null && listing.sellerUserId == order.buyerUserId) continue

            def user = steamUserRepository.findById(order.buyerUserId).orElse(null)
            if (user == null) continue
            // Batch 331 ban filter — a user banned after they placed a buy
            // order would otherwise have their wallet surprise-drained by
            // tryMatch on the next matching listing, AND purchaseService.buy
            // would throw (its own banGuard catches the real write), so the
            // order would just sit spinning its wheels every listing event.
            // Flip to EXPIRED + notify so the order doesn't keep firing and
            // the buyer has a record of why it stopped if/when they're unbanned.
            if (Boolean.TRUE.equals(user.banned)) {
                try {
                    order.status = 'EXPIRED'
                    order.updatedAt = System.currentTimeMillis()
                    buyOrderRepository.save(order)
                } catch (Exception e) {
                    log.warn("Failed to expire banned buy order ${order.id}: ${e.message}")
                }
                continue
            }
            def wallet = walletRepository.findByUsername("steam_${user.steamId64}")
            if (wallet == null || wallet.balance < listing.price) continue

            // Wallet-freeze / dispute-hold fast path (batch 517). Without
            // this, a frozen or disputed wallet's order sits ACTIVE
            // forever — every match fails silently inside the catch
            // block below. Expire + notify so the user gets told why
            // nothing fills, symmetric with the banned-user branch
            // above.
            if (Boolean.TRUE.equals(wallet.frozen)) {
                expireOrderWithHold(order, 'BUY_ORDER_EXPIRED',
                    "Buy order paused · wallet frozen",
                    "Your wallet is frozen (${wallet.frozenReason ?: 'staff action'}). Re-place the order once the hold is cleared.")
                continue
            }
            if (transactionRepository != null &&
                    transactionRepository.countActiveDisputedDeposits(wallet.id) > 0L) {
                expireOrderWithHold(order, 'BUY_ORDER_EXPIRED',
                    "Buy order paused · deposit dispute",
                    "A deposit dispute on your wallet has paused outflows. Re-place the order once the dispute closes.")
                continue
            }

            try {
                // Pessimistic re-load — serialise this order's fill against
                // any concurrent matching event so a quantity-1 order can't
                // be filled twice (see findByIdForUpdate). Re-verify on the
                // locked copy: a concurrent fill may have just drained it.
                // Falls back to the in-hand order when the lock query is
                // unconfigured (a Mock repo in a unit test).
                def locked = buyOrderRepository.findByIdForUpdate(order.id) ?: order
                if (locked.status != 'ACTIVE' || locked.quantity <= 0) continue
                // Cap guard — never auto-buy above the order's ceiling.
                // buy() charges whatever the listing says with no cap
                // knowledge; the locked re-load can also surface a
                // maxPrice the buyer lowered after the match query.
                if (listing.price == null || listing.price > locked.maxPrice) continue
                // REQUIRES_NEW sub-tx around the buy — see runInIsolatedTx
                // for the full rationale. tryMatch is called from
                // ListingService when a fresh listing lands; the caller
                // ALSO carries @Transactional, so without the sub-tx a
                // buy() throw (sniped listing, concurrent wallet drain,
                // versioned-Wallet collision) marks the shared outer tx
                // rollback-only and torches the listing's own
                // create/relist save. The catch below swallows the throw
                // so the loop tries the next candidate, but the outer
                // commit later blows up with UnexpectedRollbackException
                // and the listing the seller just published vanishes.
                runInIsolatedTx {
                    purchaseService.buy(wallet.id, locked.buyerUserId, listing.id)
                }
                locked.quantity = Math.max(0, locked.quantity - 1)
                locked.updatedAt = System.currentTimeMillis()
                if (locked.quantity == 0) locked.status = 'FILLED'
                buyOrderRepository.save(locked)
                notificationService.push(
                    locked.buyerUserId,
                    'BUY_ORDER_FILLED',
                    "Buy order auto-filled · ${listing.item?.name}",
                    "Paid \$${listing.price.toPlainString()} (cap \$${locked.maxPrice.toPlainString()})",
                    listing.id,
                    '/profile?tab=buyorders'
                )
                fireBuyOrderFilledEmail(locked, listing)
                log.info("Buy order ${locked.id} auto-filled by listing ${listing.id}")
                return // listing is now sold; stop iterating
            } catch (Exception e) {
                log.warn("Buy order ${order.id} match attempt failed: ${e.message}")
            }
        }
    }

    /** Fire BUY_ORDER_FILLED email to the buyer (batch 574). Shared by
     *  both auto-fill paths (new-listing match + existing-listing
     *  tryMatch on order create). Same opt-in + bucket-mute gate as
     *  every other transactional email. Silent-fail so a flaky SMTP
     *  relay doesn't roll back the auto-fill. */
    private void fireBuyOrderFilledEmail(BuyOrder order, com.sboxmarket.model.Listing listing) {
        if (emailService == null || order?.buyerUserId == null) return
        try {
            def buyer = steamUserRepository.findById(order.buyerUserId).orElse(null)
            if (emailService.canSendTo(buyer, 'TRADES')) {
                def itemUrl = listing?.item?.id != null ? "/item/${listing.item.id}".toString() : null
                emailService.sendBuyOrderFilled(buyer.email, buyer.displayName,
                    listing?.item?.name, listing?.price, order.maxPrice, itemUrl)
            }
        } catch (Exception e) {
            log.warn("BUY_ORDER_FILLED email failed for buyer ${order?.buyerUserId}: ${e.message}")
        }
    }

    /** Flip a buy order to EXPIRED with a user-facing explanation push.
     *  Used by the wallet-hold fast path (batch 517) so a frozen /
     *  disputed buyer knows their order stopped because of a wallet
     *  state they can fix, not because the market has nothing for them. */
    private void expireOrderWithHold(BuyOrder order, String kind, String title, String bodyText) {
        try {
            order.status = 'EXPIRED'
            order.updatedAt = System.currentTimeMillis()
            buyOrderRepository.save(order)
            notificationService?.push(order.buyerUserId, kind, title, bodyText,
                order.id, '/profile?tab=buyorders')
            log.info("Buy order ${order.id} expired on wallet-hold: ${title}")
        } catch (Exception e) {
            log.warn("Failed to expire held buy order ${order.id}: ${e.message}")
        }
    }

    /** How long an ACTIVE buy order can sit idle (no fills, no edits)
     *  before the auto-expire sweep flips it to EXPIRED. 30 days is
     *  the CSFloat default — long enough that legitimate "wait for
     *  the right one" patience is respected, short enough that
     *  abandoned orders don't snipe a buyer's wallet months later. */
    private static final long IDLE_EXPIRE_MS = 30L * 24L * 60L * 60L * 1000L

    /**
     * Daily sweep that auto-expires ACTIVE buy orders idle for >30
     * days. Without this an abandoned order at, say, $50 for a Wizard
     * Hat could surprise-fire months after the buyer forgot it
     * existed — drains their wallet on something they no longer want.
     *
     * Pushes a `BUY_ORDER_EXPIRED` notification so the user knows
     * what happened. Per-row try/catch so one failure doesn't poison
     * the loop.
     *
     * Deliberately NOT @Transactional on the outer sweep. Mirrors the
     * fix BidService.sweepExpired adopted (batch 800) and
     * TradeService.sweepReviewNudge / sweepSlowSellerWarning adopted:
     * a per-row `buyOrderRepository.save(order)` that throws (e.g. an
     * OptimisticLockingFailureException from a concurrent
     * tryFillFromExisting / tryMatch hitting the same order, or a DB
     * blip on a single row) would mark the SHARED outer tx
     * rollback-only — the per-row try/catch below swallows the
     * exception, but every `EXPIRED` flip + push the sweep had
     * already applied to SIBLING rows then silently reverts on
     * commit. Result: the next 24h tick re-finds those rows and
     * re-fires BUY_ORDER_EXPIRED pushes to buyers we already pinged
     * (and the actual EXPIRED flip never landed, so an idle order
     * could surprise-fire on a future match anyway — the exact bug
     * this sweep exists to prevent). With the outer tx removed each
     * per-row save commits in its own auto-commit, so one bad row
     * never poisons sibling flips.
     */
    @Scheduled(fixedDelay = 24L * 60L * 60L * 1000L, initialDelay = 60L * 60L * 1000L)
    void sweepStaleBuyOrders() {
        def cutoff = System.currentTimeMillis() - IDLE_EXPIRE_MS
        def stale = buyOrderRepository.findStaleActive(cutoff)
        if (stale.isEmpty()) return
        log.info("Buy-order auto-expire sweep: ${stale.size()} candidate idle order(s) past ${IDLE_EXPIRE_MS / 86_400_000L}d")
        int fired = 0
        stale.each { order ->
            try {
                // Multi-pod claim (wave 125). Same shape as wave 112
                // (WatchlistAlertService.claimForFiring), wave 120
                // (FraudAnalysisService cluster claim), and wave 124
                // (BidService.sweepEndingSoon). Without an atomic claim
                // both pods running the daily sweeper would see the same
                // ACTIVE rows, both flip status=EXPIRED, AND both fire
                // BUY_ORDER_EXPIRED email + bell push — buyer received
                // the auto-expire reminder TWICE. The conditional UPDATE
                // flips ACTIVE→EXPIRED only if status is still ACTIVE at
                // UPDATE time and returns 1 to the winning pod / 0 to the
                // losing pod (or 0 if a concurrent buyer cancel / fill
                // landed between sweeper read and claim). Losing pod
                // bails before any notify or email runs.
                int claimed = buyOrderRepository.claimExpire(order.id, System.currentTimeMillis())
                if (claimed == 0) {
                    log.debug("Buy-order expire claim lost for ${order.id} — sibling pod or status change")
                    return
                }
                fired++
                // Mirror the bell to email (batch 596). A user idle for
                // 30 days almost certainly hasn't checked the bell; the
                // email is how they actually hear about the expire.
                // Best-effort, TRADES-bucketed, verified-only.
                try {
                    if (emailService != null && order.buyerUserId != null) {
                        def buyer = steamUserRepository.findById(order.buyerUserId).orElse(null)
                        if (emailService.canSendTo(buyer, 'TRADES')) {
                            def itemUrl = order.itemId != null ? "/item/${order.itemId}".toString() : null
                            emailService.sendBuyOrderExpired(buyer.email, buyer.displayName,
                                order.itemName, order.maxPrice, itemUrl)
                        }
                    }
                } catch (Exception ee) {
                    log.warn("BUY_ORDER_EXPIRED email failed for order ${order.id}: ${ee.message}")
                }
                notificationService?.push(
                    order.buyerUserId,
                    'BUY_ORDER_EXPIRED',
                    "Buy order auto-expired",
                    "${order.itemName ?: 'Your buy order'} (\$${order.maxPrice}) sat idle for 30 days and was auto-expired. Re-create it from the Buy Orders tab if you still want it.",
                    order.id,
                    '/profile?tab=buyorders'
                )
            } catch (Exception e) {
                log.warn("Buy-order expire sweep failed on ${order.id}: ${e.message}")
            }
        }
        log.info("Buy-order auto-expire sweep: fired ${fired} of ${stale.size()} candidates (rest claimed by sibling pods or status-changed)")
    }
}
