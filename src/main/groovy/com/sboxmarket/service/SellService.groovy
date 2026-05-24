package com.sboxmarket.service

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.ListingNotAvailableException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.Listing
import com.sboxmarket.repository.BidRepository
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.OfferRepository
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.service.security.BanGuard
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Orchestrates a sale-listing: a user takes an item they own (a previously
 * purchased Listing) and lists it back on the market at their own price.
 *
 * Single-Responsibility: only handles the "list from inventory" flow.
 */
@Service
@Slf4j
class SellService {

    @Autowired ListingRepository listingRepository
    @Autowired ItemRepository itemRepository
    @Autowired TradeRepository tradeRepository
    @Autowired @Lazy BuyOrderService buyOrderService
    @Autowired @Lazy TradeService tradeService
    @Autowired BanGuard banGuard
    @Autowired TextSanitizer textSanitizer
    @Autowired(required = false) SellerFollowService sellerFollowService
    @Autowired(required = false) SavedSearchService savedSearchService
    @Autowired(required = false) BidRepository bidRepository
    @Autowired(required = false) OfferRepository offerRepository
    @Autowired(required = false) NotificationService notificationService
    @Autowired(required = false) WatchlistAlertService watchlistAlertService
    @Autowired(required = false) com.sboxmarket.repository.CartItemRepository cartItemRepository

    /**
     * Per-listing transaction wrapper for cancelAllActive (boss-QA cycle 33).
     * Required = false so Spock specs that build the service via property
     * map without a Spring context still work: runInIsolatedTx falls back
     * to executing the closure inline when there is no transaction manager.
     *
     * Why this exists: cancelAllActive was `@Transactional` and wrapped the
     * whole batch in ONE outer tx. Each per-listing `cancelListing` call was
     * also `@Transactional` BUT Spring's default CGLIB-proxy mode does NOT
     * intercept self-invocation, so the inner annotation was dead code and
     * the per-listing work ran in the SHARED outer tx. A thrown ApiException
     * from one row (e.g. ListingNotAvailableException because someone bought
     * between findActiveBySeller and cancelListing) marked the shared tx
     * rollback-only. The try/catch above swallowed the exception, but the
     * @Transactional commit would then throw UnexpectedRollbackException at
     * the very end, surfacing a 500 even though the user-facing
     * `{cancelled:N, failed:K}` summary was already populated.
     *
     * Mirrors the TradeService.sweepPendingConfirm + BidService.sweepExpired
     * pattern: the outer batch is unannotated, and each per-listing
     * cancelListing runs in a REQUIRES_NEW transaction via TransactionTemplate
     * so one rollback can't poison sibling cancels in the batch.
     */
    @Autowired(required = false) PlatformTransactionManager transactionManager
    private void runInIsolatedTx(Closure work) {
        if (transactionManager == null) {
            // No Spring context (Spock plain-prop test) — run inline.
            work()
            return
        }
        def tt = new TransactionTemplate(transactionManager)
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW)
        tt.execute({ status -> work() ; null } as org.springframework.transaction.support.TransactionCallback)
    }

    /**
     * Run {@code work} after the caller's transaction commits — or
     * immediately when there is no active transaction. Mirrors the
     * pattern in PurchaseService / AuditService / NotificationService /
     * PriceHistoryService (wave 23) — same docstring lineage as those.
     *
     * Why this matters for the RELIST path: `buyOrderService.tryMatch`
     * is itself `@Transactional` with default REQUIRED propagation, so
     * when called from inside relist's own @Transactional method it
     * JOINS the relist transaction. tryMatch then calls
     * `purchaseService.buy`, which writes to wallet / listing / trade /
     * transaction rows. If ANY of those repository.save() calls throws
     * (DB blip, optimistic-lock race, dialect quirk), Spring Data's
     * @Transactional save proxy marks the SHARED relist transaction
     * rollback-only. The wrapping try/catch in relist absorbs the
     * exception and the method appears to succeed — but on commit
     * Spring throws UnexpectedRollbackException and the relist's
     * RELISTED flip on the old row, the fresh ACTIVE listing INSERT,
     * AND every fan-out side-effect all silently roll back while the
     * caller's HTTP response says 200. Money-path catastrophe — the
     * seller sees their inventory unchanged, the new listing never
     * appears on the marketplace, and any followers / saved-search /
     * watchlist pushes about a listing that doesn't exist have already
     * fired. Exact same bug class as wave 60 (ebc1b45) on
     * TradeProtectionService.
     *
     * A simple `@Transactional(propagation = REQUIRES_NEW)` flip on
     * tryMatch won't work — purchaseService.buy reads the fresh listing
     * by id, and inside a REQUIRES_NEW tx the parent's uncommitted
     * INSERT is invisible (READ_COMMITTED isolation), so tryMatch
     * would see no candidate listing and silently no-op every time.
     *
     * Deferring tryMatch to afterCommit fixes both problems at once:
     * the relist transaction durably commits FIRST (the new listing
     * row is visible to anything that queries it), THEN tryMatch runs
     * in its own fresh REQUIRES_NEW tx and reads the now-committed
     * listing. A failure inside the deferred body can only roll back
     * the buy + buy-order-save (which is the correct scope — the
     * relist already landed), and it can never poison the relist tx
     * because the relist tx no longer exists.
     */
    private void deferOrRun(Closure work) {
        if (transactionManager != null && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override void afterCommit() {
                    try {
                        def tt = new TransactionTemplate(transactionManager)
                        tt.propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
                        tt.executeWithoutResult { work() }
                    } catch (Exception e) {
                        log.warn("Deferred best-effort write failed: ${e.message}")
                    }
                }
            })
        } else {
            // No active transaction (unit tests / non-transactional caller).
            // Still swallow exceptions so a best-effort side-effect can't
            // fail the caller — matches the inline try/catch semantic the
            // original wrapper provided.
            try {
                work()
            } catch (Exception e) {
                log.warn("Best-effort write failed (no active tx): ${e.message}")
            }
        }
    }

    @Transactional
    Listing relist(Long sellerUserId, String sellerName, Long ownedListingId, BigDecimal newPrice,
                   String listingType = 'BUY_NOW', Long durationHours = null, String description = null,
                   BigDecimal buyNowPrice = null, BigDecimal maxDiscount = null) {
        banGuard.assertNotBanned(sellerUserId)
        sellerName = textSanitizer.cleanShort(sellerName)
        // Defensive: validation belongs in the DTO but we keep it here too for
        // callers that bypass the HTTP boundary (e.g. the offer-accept flow).
        if (newPrice == null || newPrice <= BigDecimal.ZERO) {
            throw new BadRequestException("INVALID_PRICE", "Price must be greater than 0")
        }
        if (newPrice > new BigDecimal('100000')) {
            throw new BadRequestException("PRICE_TOO_HIGH", "Price must not exceed \$100,000")
        }
        // Whitelist listingType — null / unknown values fall back to BUY_NOW
        // so an older client that doesn't send the field still works.
        def resolvedType = (listingType in ['BUY_NOW', 'AUCTION']) ? listingType : 'BUY_NOW'
        if (resolvedType == 'AUCTION') {
            if (durationHours == null) {
                throw new BadRequestException("DURATION_REQUIRED",
                    "durationHours is required for AUCTION listings")
            }
            if (durationHours < 1L || durationHours > 168L) {
                throw new BadRequestException("INVALID_DURATION",
                    "durationHours must be between 1 and 168")
            }
        }
        // Auction Buy-Now ceiling (batch 371). Optional. Must exceed
        // the starting bid to be meaningful. Rejected on BUY_NOW
        // listings because the `price` field is already the Buy-Now
        // amount for those.
        if (buyNowPrice != null) {
            if (resolvedType != 'AUCTION') {
                throw new BadRequestException("BUY_NOW_ON_BUY_NOW",
                    "buyNowPrice only applies to AUCTION listings")
            }
            if (buyNowPrice <= newPrice) {
                throw new BadRequestException("INVALID_BUY_NOW",
                    "buyNowPrice must be greater than the starting bid (\$${newPrice})")
            }
            if (buyNowPrice > new BigDecimal('100000')) {
                throw new BadRequestException("BUY_NOW_TOO_HIGH",
                    "buyNowPrice must not exceed \$100,000")
            }
        }
        // Optional auto-accept threshold (batch 646). BigDecimal 0..1 —
        // 0.20 means "auto-accept any offer >= 80% of the ask". Invalid
        // values are rejected here so callers that bypass the controller
        // (e.g. integration tests) still get validated. BUY_NOW-only
        // semantically; we accept it on auctions too but the auction
        // flow never calls makeOffer so the threshold has no effect —
        // same policy the UI follows.
        //
        // Validated up-front alongside every other input check: this
        // range guard MUST run before the `owned.status = 'RELISTED'`
        // mutation below. Previously it sat after that flip, so a bad
        // maxDiscount only got caught once the source listing had
        // already been mutated — relying on @Transactional rollback to
        // undo it instead of rejecting the request before any write.
        BigDecimal resolvedMaxDiscount = null
        if (maxDiscount != null) {
            if (maxDiscount < BigDecimal.ZERO || maxDiscount >= BigDecimal.ONE) {
                throw new BadRequestException("INVALID_DISCOUNT",
                    "maxDiscount must be between 0 (no auto-accept) and 1 (100% off) exclusive")
            }
            resolvedMaxDiscount = maxDiscount.signum() == 0 ? null : maxDiscount
        }

        def owned = listingRepository.findById(ownedListingId)
                .orElseThrow { new NotFoundException("Listing", ownedListingId) }

        if (owned.buyerUserId == null || owned.buyerUserId != sellerUserId) {
            throw new ForbiddenException("You do not own this item")
        }
        if (owned.status != 'SOLD') {
            throw new BadRequestException("NOT_IN_INVENTORY", "Item is not in your inventory")
        }

        // Mark the old listing as RELISTED (removes it from inventory queries)
        owned.status = 'RELISTED'
        listingRepository.save(owned)

        // Optional seller note. Sanitised + 500-char capped — matches
        // the column size and the MyStall edit-form cap (batch 304).
        def cleanDesc = null
        if (description != null && description.trim()) {
            cleanDesc = textSanitizer.clean(description, 500)
        }
        // Create a new ACTIVE listing under this user
        def fresh = new Listing(
            item        : owned.item,
            price       : newPrice,
            sellerName  : sellerName,
            sellerAvatar: (sellerName ?: 'US').take(2).toUpperCase(),
            condition   : '',
            rarityScore : owned.rarityScore,
            status      : 'ACTIVE',
            sellerUserId: sellerUserId,
            listingType : resolvedType,
            description : cleanDesc,
            buyNowPrice : resolvedType == 'AUCTION' ? buyNowPrice : null,
            maxDiscount : resolvedMaxDiscount
        )
        if (resolvedType == 'AUCTION') {
            fresh.expiresAt = System.currentTimeMillis() + (durationHours * 60L * 60L * 1000L)
        }
        def saved = listingRepository.save(fresh)
        log.info("User $sellerUserId relisted item ${owned.item.name} as ${resolvedType} listing ${saved.id} for \$${newPrice}")

        // Try to auto-fulfil any standing buy order that matches this fresh
        // listing — DEFERRED to afterCommit (wave 60 follow-up). Failures
        // here must never fail the parent transaction. A plain try/catch
        // around tryMatch is NOT enough: tryMatch is `@Transactional` and
        // joins the relist tx, so when its inner purchaseService.buy ->
        // wallet/listing/trade save throws and the catch swallows it,
        // Spring has already marked the SHARED tx rollback-only and the
        // relist commit blows up with UnexpectedRollbackException. The
        // committed RELISTED flip + ACTIVE INSERT silently roll back
        // while the caller's HTTP response says 200. Deferring the entire
        // tryMatch call to afterCommit means the relist tx durably
        // commits FIRST (the fresh listing is visible), then tryMatch
        // runs in its own REQUIRES_NEW tx and reads the now-committed
        // row — a buy() failure can only roll back its own inner tx, the
        // relist is unaffected. See `deferOrRun` docstring for the full
        // failure-mode walk-through.
        final Listing _savedForMatch = saved
        deferOrRun {
            try {
                buyOrderService.tryMatch(_savedForMatch)
            } catch (Exception e) {
                log.warn("Buy-order match failed for listing ${_savedForMatch.id}: ${e.message}")
            }
        }
        // Fan out NEW_LISTING_FROM_SELLER to every user following this
        // seller. Wrapped in try/catch so one bad subscription doesn't
        // roll back the relist. Best-effort notification delivery.
        try {
            sellerFollowService?.notifyFollowersOfNewListing(saved)
        } catch (Exception e) {
            log.warn("Follower fanout failed for listing ${saved.id}: ${e.message}")
        }
        // Saved-search match fanout — same isolation contract as above.
        try {
            savedSearchService?.notifyMatchingForListing(saved)
        } catch (Exception e) {
            log.warn("Saved-search fanout failed for listing ${saved.id}: ${e.message}")
        }
        // Synchronous watchlist-alert sweep (batch 389). The scheduled
        // sweeper runs every 5 min, which is fine for ambient listings but
        // feels sluggish for the user who has a $10 alert and the seller
        // just listed at $8. Firing here fans the alert out within seconds.
        // Scoped to this one item so a thousand alerts on OTHER items
        // don't get re-scanned on every relist. Isolated try/catch.
        try {
            watchlistAlertService?.sweepForItem(saved.item?.id)
        } catch (Exception e) {
            log.warn("Watchlist alert sync-sweep failed for listing ${saved.id}: ${e.message}")
        }
        saved
    }

    /**
     * Bulk-cancel every active listing the seller owns (batch 375).
     * Powers the MyStall "Cancel all active" shortcut — heavy sellers
     * quitting the platform / taking a long break shouldn't have to
     * click ✕ on each row. Per-row try/catch so one failure doesn't
     * abort the rest. Auctions with live bids are SKIPPED by default
     * (fanout noise to every bidder; the seller almost never wants
     * to blow up a running auction as collateral damage); caller
     * passes `includeAuctions=true` to override.
     *
     * Returns `{cancelled: N, skippedAuctions: M, failed: K}` so the
     * UI can toast a meaningful summary.
     */
    /**
     * Boss-QA cycle 33: outer @Transactional REMOVED. The previous
     * decoration was actively harmful — it wrapped every per-listing
     * cancelListing call in the SAME tx, so a single rollback-only
     * marker from a row that lost the buy-race (ListingNotAvailableException)
     * poisoned the WHOLE batch. The try/catch swallowed the per-row
     * exception but the @Transactional commit then threw
     * UnexpectedRollbackException, surfacing a 500 even though the
     * `{cancelled, skippedAuctions, failed}` summary was already populated.
     *
     * Each per-listing cancelListing now runs in its own REQUIRES_NEW
     * tx via runInIsolatedTx — one rollback can't poison siblings. The
     * outer banGuard.assertNotBanned is the only mutation-adjacent
     * operation outside the loop and it's a read-only guard (throws or
     * returns; doesn't write), so it doesn't need its own tx.
     */
    Map cancelAllActive(Long sellerUserId, boolean includeAuctions = false) {
        banGuard.assertNotBanned(sellerUserId)
        def active = listingRepository.findActiveBySeller(sellerUserId)
        int cancelled = 0, skippedAuctions = 0, failed = 0
        active.each { l ->
            if (!includeAuctions && l.listingType == 'AUCTION' && (l.bidCount ?: 0) > 0) {
                skippedAuctions++
                return
            }
            try {
                runInIsolatedTx { cancelListing(sellerUserId, l.id) }
                cancelled++
            } catch (Exception e) {
                failed++
                log.warn("Bulk-cancel: listing ${l.id} skipped (${e.message})")
            }
        }
        log.info("Bulk-cancel: user ${sellerUserId} · cancelled=${cancelled} skippedAuctions=${skippedAuctions} failed=${failed}")
        [cancelled: cancelled, skippedAuctions: skippedAuctions, failed: failed]
    }

    @Transactional
    void cancelListing(Long sellerUserId, Long listingId) {
        def listing = listingRepository.findById(listingId)
                .orElseThrow { new NotFoundException("Listing", listingId) }
        if (listing.sellerUserId != sellerUserId) {
            throw new ForbiddenException("You can only cancel your own listings")
        }
        if (listing.status != 'ACTIVE') {
            throw new ListingNotAvailableException(listingId)
        }
        // If there's an active trade in escrow for this listing, cancel it
        // and refund the buyer before we return the item to inventory.
        // Without this, a buyer's funds could be trapped in escrow forever
        // once the listing is removed from the marketplace.
        def openTrade = tradeRepository.findByListingId(listingId)
        if (openTrade != null && openTrade.state != 'VERIFIED' && openTrade.state != 'CANCELLED') {
            try {
                tradeService.cancel(sellerUserId, openTrade.id, "Seller cancelled listing")
            } catch (Exception e) {
                log.warn("Failed to auto-cancel trade {} on listing cancel: {}", openTrade.id, e.message)
            }
        }
        // Live offers on the cancelled listing — flip every still-live
        // offer to CANCELLED and notify each buyer. Without this, offer
        // rows orphan: the buyer sees a live offer on a listing that no
        // longer exists, and if the offer somehow got accepted later the
        // server's listing-status check would 400 with a confusing
        // "listing not active" error. Clean cleanup here, with a
        // courtesy notification so the buyer knows to pick a different
        // listing or place a buy order.
        //
        // "Live" is PENDING *or* COUNTERED — both are negotiation states
        // the buyer can still act on, and `findLiveByBuyerAndListing`
        // (which powers the ItemModal "You offered $X" chip) treats them
        // as one. A PENDING-only sweep left a COUNTERED buyer original
        // dangling: its child SELLER counter (PENDING, same listingId)
        // got cancelled but the COUNTERED parent never reached a terminal
        // state, so the buyer kept seeing a live offer on a deleted
        // listing. `findByListingId` returns every offer on the listing
        // in one indexed query, and we filter to the live pair here.
        if (offerRepository != null) {
            try {
                def live = offerRepository.findByListingId(listingId)
                    .findAll { it.status == 'PENDING' || it.status == 'COUNTERED' }
                def itemName = listing.item?.name ?: 'this item'
                def itemId = listing.item?.id
                live.each { o ->
                    o.status = 'CANCELLED'
                    o.updatedAt = System.currentTimeMillis()
                }
                if (!live.isEmpty()) offerRepository.saveAll(live)
                if (notificationService != null) {
                    // Dedup per buyer — a buyer with a COUNTERED original
                    // AND its PENDING child counter has two rows on this
                    // listing but should only get one "offer cancelled"
                    // ping for the single negotiation thread.
                    def notified = new HashSet<Long>()
                    live.each { o ->
                        if (o.buyerUserId == null || !notified.add(o.buyerUserId as Long)) return
                        try {
                            notificationService.push(o.buyerUserId, 'OFFER_REJECTED',
                                "Offer cancelled · ${itemName}",
                                "The seller removed the listing your offer was tied to.",
                                o.id,
                                itemId != null ? "/item/${itemId}" : '/offers')
                        } catch (Exception e) {
                            log.warn("Offer-cancelled push failed for buyer ${o.buyerUserId}: ${e.message}")
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Live-offer cleanup failed for listing ${listingId}: ${e.message}")
            }
        }

        // Auction with live bids — notify every bidder that their bid is
        // void and flip bid rows to CANCELLED so they stop counting in the
        // bidder's ProfileAutoBidsTab and "Active bids" UI. Funds aren't
        // held for bids (charging only happens on auction settle), so no
        // refund is needed — just a clear "auction was cancelled by the
        // seller" signal so bidders aren't left wondering.
        if (listing.listingType == 'AUCTION' && bidRepository != null) {
            try {
                def bids = bidRepository.findByListing(listingId)
                def uniqueBidders = new LinkedHashSet<Long>()
                bids.each { b ->
                    if (b.status == 'WINNING' || b.status == 'OUTBID') {
                        b.status = 'CANCELLED'
                    }
                    if (b.bidderUserId != null) uniqueBidders.add(b.bidderUserId as Long)
                }
                if (!bids.isEmpty()) bidRepository.saveAll(bids)
                if (notificationService != null) {
                    def itemName = listing.item?.name ?: 'an auction'
                    uniqueBidders.each { uid ->
                        try {
                            notificationService.push(uid, 'AUCTION_CANCELLED',
                                "Auction cancelled · ${itemName}",
                                'The seller withdrew this auction before it settled. No charge was made.',
                                listing.id,
                                listing.item?.id != null ? "/item/${listing.item.id}" : null)
                        } catch (Exception e) {
                            log.warn("AUCTION_CANCELLED push failed for uid=${uid}: ${e.message}")
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Auction cancel fan-out failed for listing {}: {}", listingId, e.message)
            }
        }
        // Cart-holder fan-out (batch 504). Mirrors the cart-item-sold
        // path in PurchaseService.buy (batch 503): when this listing
        // disappears from the marketplace, every OTHER user with it
        // queued in their cart sees a stale-grey row next time they
        // open the cart unless we ping them now. Same CART_ITEM_SOLD
        // kind so existing UI wiring covers it; body says "removed by
        // seller" instead of "bought by another user". Capped at 50
        // for fan-out cost. Bulk-delete the row from every cart at
        // the end so the next /api/cart fetch is clean.
        if (cartItemRepository != null && notificationService != null) {
            try {
                def others = cartItemRepository.findOtherUsersWithListing(listingId, sellerUserId) ?: []
                if (!others.isEmpty()) {
                    def itemName = listing.item?.name ?: 'an item'
                    def itemId = listing.item?.id
                    others.take(50).each { uid ->
                        try {
                            notificationService.push(uid, 'CART_ITEM_SOLD',
                                "Cart item removed · ${itemName}",
                                "${itemName} was withdrawn by the seller. Other listings may still be available — find a similar one in the marketplace.",
                                listingId,
                                itemId != null ? "/item/${itemId}" : '/cart')
                        } catch (Exception e) {
                            log.warn("CART_ITEM_SOLD (seller-cancel) push failed for uid=${uid}: ${e.message}")
                        }
                    }
                }
                try {
                    cartItemRepository.deleteAllByListing(listingId)
                } catch (Exception e) {
                    log.warn("CART scrub failed for listing=${listingId} on seller-cancel: ${e.message}")
                }
            } catch (Exception e) {
                log.warn("CART fan-out failed for listing=${listingId} on seller-cancel: ${e.message}")
            }
        }

        // Return the item to inventory. The ListingRepository.findOwnedBy
        // query orders by `soldAt DESC`, so stamping the cancellation time
        // here ensures the returned item shows up at the top of the
        // user's inventory view instead of falling to the bottom of the
        // null-soldAt bucket.
        listing.status = 'SOLD'
        listing.buyerUserId = sellerUserId
        listing.soldAt = System.currentTimeMillis()
        listingRepository.save(listing)
    }
}
