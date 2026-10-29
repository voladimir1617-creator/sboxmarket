package com.sboxmarket.service

import com.sboxmarket.model.BuyOrder
import com.sboxmarket.model.Listing
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.NotificationRepository
import com.sboxmarket.repository.SteamUserRepository
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

/**
 * Bell pings that tell a SELLER the market moved on an item they list:
 *
 *  - BUY_ORDER_ON_YOUR_ITEM: a buyer placed an item buy order that did not
 *    fill, so every seller of that item priced above the bid hears what a
 *    buyer will pay now (they can lower, or quick-sell to the best bid).
 *  - LISTING_UNDERCUT: a cheaper BUY_NOW listing of the same item went
 *    live, so the sellers now priced above it hear their ask is stale.
 *
 * Only the buyer heard about a buy order before, and only auctions had an
 * outbid notice. Bell only (no email), one ping per seller per item per
 * day, banned sellers skipped, capped like the follower fanout. Never names
 * the buyer or the other seller. Best-effort: never throws to its caller.
 */
@Slf4j
@Service
class SellerMarketPingService {

    static final String KIND_BUY_ORDER = 'BUY_ORDER_ON_YOUR_ITEM'
    static final String KIND_UNDERCUT  = 'LISTING_UNDERCUT'

    /** One ping per seller per item per kind in this window. */
    static final long DEDUPE_WINDOW_MS = 24L * 60 * 60 * 1000
    /** Same cap as SellerFollowService's follower fanout. */
    static final int FANOUT_MAX = 500

    @Autowired ListingRepository listingRepository
    @Autowired(required = false) NotificationService notificationService
    @Autowired(required = false) NotificationRepository notificationRepository
    @Autowired(required = false) SteamUserRepository steamUserRepository
    @Autowired(required = false) UserBlockService userBlockService

    /** Call after a buy order is saved and its first fill attempt ran. */
    int notifyBuyOrderPlaced(BuyOrder order) {
        try {
            if (order?.itemId == null || order.maxPrice == null) return 0
            if (order.status != 'ACTIVE' || (order.quantity ?: 0) <= 0) return 0
            def above = activeBuyNow(order.itemId).findAll {
                it.price != null && it.price > order.maxPrice && it.sellerUserId != order.buyerUserId
            }
            if (above.isEmpty()) return 0
            def itemName = order.itemName ?: above.first().item?.name ?: 'an item you list'
            def title = "A buyer wants ${itemName} at \$${order.maxPrice.toPlainString()}".toString()
            def body = 'You list it higher. Lower your price or sell to the buy order to make the sale.'
            return fanout(above, KIND_BUY_ORDER, order.itemId, title, body) { Long sellerId ->
                // A seller who blocked this buyer shouldn't be nudged toward them.
                blocked(sellerId, order.buyerUserId)
            }
        } catch (Exception e) {
            log.warn("Buy-order seller ping failed for order ${order?.id}: ${e.message}")
            return 0
        }
    }

    /** Call when a listing becomes buyable (ACTIVE and not hidden). */
    int notifyUndercut(Listing listing) {
        try {
            if (listing?.item?.id == null || listing.price == null) return 0
            if (listing.status != 'ACTIVE' || Boolean.TRUE.equals(listing.hidden)) return 0
            if ((listing.listingType ?: 'BUY_NOW') != 'BUY_NOW') return 0
            Long itemId = listing.item.id
            def above = activeBuyNow(itemId).findAll {
                it.id != listing.id && it.price != null && it.price > listing.price &&
                    it.sellerUserId != listing.sellerUserId
            }
            if (above.isEmpty()) return 0
            def itemName = listing.item.name ?: 'an item you list'
            def title = "A cheaper ${itemName} just went on sale".toString()
            def body = "Another seller listed it at \$${listing.price.toPlainString()}, below your price.".toString()
            return fanout(above, KIND_UNDERCUT, itemId, title, body) { Long sellerId -> false }
        } catch (Exception e) {
            log.warn("Undercut seller ping failed for listing ${listing?.id}: ${e.message}")
            return 0
        }
    }

    private List<Listing> activeBuyNow(Long itemId) {
        (listingRepository.findActiveForItemIds([itemId]) ?: []).findAll {
            (it.listingType ?: 'BUY_NOW') == 'BUY_NOW' && it.sellerUserId != null
        }
    }

    private int fanout(List<Listing> listings, String kind, Long itemId, String title, String body,
                       Closure<Boolean> skip) {
        if (notificationService == null) return 0
        def sellerIds = listings*.sellerUserId.unique()
        def banned = [] as Set<Long>
        if (steamUserRepository != null) {
            try {
                steamUserRepository.findAllById(sellerIds).each {
                    if (Boolean.TRUE.equals(it.banned)) banned << it.id
                }
            } catch (Exception e) {
                log.debug("Seller ban lookup failed: ${e.message}")
            }
        }
        long since = System.currentTimeMillis() - DEDUPE_WINDOW_MS
        int sent = 0
        for (Long sellerId in sellerIds) {
            if (sent >= FANOUT_MAX) {
                log.info("${kind} fanout capped at ${FANOUT_MAX} for item ${itemId}")
                break
            }
            if (sellerId in banned || skip.call(sellerId)) continue
            if (recentlyPinged(sellerId, kind, itemId, since)) continue
            try {
                notificationService.push(sellerId, kind, title, body, itemId, "/item/${itemId}".toString())
                sent++
            } catch (Exception e) {
                log.warn("${kind} push failed for seller ${sellerId}: ${e.message}")
            }
        }
        sent
    }

    private boolean recentlyPinged(Long sellerId, String kind, Long itemId, long since) {
        if (notificationRepository == null) return false
        try {
            return notificationRepository.countRecentByKindAndRef(sellerId, kind, itemId, since) > 0L
        } catch (Exception e) {
            log.debug("${kind} dedupe lookup failed: ${e.message}")
            return false
        }
    }

    private boolean blocked(Long blockerId, Long blockedId) {
        if (userBlockService == null || blockerId == null || blockedId == null) return false
        try {
            return userBlockService.isBlocked(blockerId, blockedId)
        } catch (Exception e) {
            return false
        }
    }
}
