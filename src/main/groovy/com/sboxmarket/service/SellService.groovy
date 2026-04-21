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
import org.springframework.transaction.annotation.Transactional

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
        // Optional auto-accept threshold (batch 646). BigDecimal 0..1 —
        // 0.20 means "auto-accept any offer >= 80% of the ask". Invalid
        // values are rejected here so callers that bypass the controller
        // (e.g. integration tests) still get validated. BUY_NOW-only
        // semantically; we accept it on auctions too but the auction
        // flow never calls makeOffer so the threshold has no effect —
        // same policy the UI follows.
        BigDecimal resolvedMaxDiscount = null
        if (maxDiscount != null) {
            if (maxDiscount < BigDecimal.ZERO || maxDiscount >= BigDecimal.ONE) {
                throw new BadRequestException("INVALID_DISCOUNT",
                    "maxDiscount must be between 0 (no auto-accept) and 1 (100% off) exclusive")
            }
            resolvedMaxDiscount = maxDiscount.signum() == 0 ? null : maxDiscount
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

        // Try to auto-fulfil any standing buy order that matches this fresh listing.
        // Failures here must never fail the parent transaction.
        try {
            buyOrderService.tryMatch(saved)
        } catch (Exception e) {
            log.warn("Buy-order match failed for listing ${saved.id}: ${e.message}")
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
    @Transactional
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
                cancelListing(sellerUserId, l.id)
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
        // Pending offers on the cancelled listing — flip every PENDING
        // offer to CANCELLED and notify each buyer. Without this, offer
        // rows orphan: the buyer sees a PENDING offer on a listing that
        // no longer exists, and if the offer somehow got accepted later
        // the server's listing-status check would 400 with a confusing
        // "listing not active" error. Clean cleanup here, with a
        // courtesy notification so the buyer knows to pick a different
        // listing or place a buy order.
        if (offerRepository != null) {
            try {
                def pending = offerRepository.findPendingForListing(listingId)
                def itemName = listing.item?.name ?: 'this item'
                def itemId = listing.item?.id
                pending.each { o ->
                    o.status = 'CANCELLED'
                    o.updatedAt = System.currentTimeMillis()
                }
                if (!pending.isEmpty()) offerRepository.saveAll(pending)
                if (notificationService != null) {
                    pending.each { o ->
                        if (o.buyerUserId == null) return
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
                log.warn("Pending-offer cleanup failed for listing ${listingId}: ${e.message}")
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
