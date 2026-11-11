package com.sboxmarket.service

import com.sboxmarket.model.BuyOrder
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.NotificationRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.WalletRepository
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

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
 * outbid notice. One ping per seller per item per day, banned sellers
 * skipped, capped like the follower fanout. Never names the buyer or the
 * other seller. Best-effort: never throws to its caller.
 *
 * Each bell that is actually pushed also sends a short email, under the
 * same 24h dedupe, when EmailService.canSendTo(seller, EMAIL_BUCKET)
 * passes. The email goes out after the caller's transaction commits (the
 * bell row is saved then too), so a rolled-back listing never emails.
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

    /**
     * Email opt-out bucket for these market alerts. MATCHES, not TRADES:
     * TRADES carries "you sold an item, send it" and payout emails, so a
     * seller muting market noise through the one-click unsubscribe would
     * also lose the emails that get their sales delivered. MATCHES is the
     * low-priority "market signal" bucket (the bell's filter already files
     * price drops and new-listing pings there), and muting it costs no
     * operational mail. Not WATCHLIST: that one is the buyer's own price
     * targets on items they watch.
     */
    static final String EMAIL_BUCKET = 'MATCHES'

    @Autowired ListingRepository listingRepository
    @Autowired(required = false) NotificationService notificationService
    @Autowired(required = false) NotificationRepository notificationRepository
    @Autowired(required = false) SteamUserRepository steamUserRepository
    @Autowired(required = false) UserBlockService userBlockService
    @Autowired(required = false) WalletRepository walletRepository
    @Autowired(required = false) EmailService emailService

    /** Call after a buy order is saved and its first fill attempt ran. */
    int notifyBuyOrderPlaced(BuyOrder order) {
        try {
            if (order?.itemId == null || order.maxPrice == null) return 0
            if (order.status != 'ACTIVE' || (order.quantity ?: 0) <= 0) return 0
            // A seller who lowers to the bid only sells if the matcher will
            // take the order, and it skips a buyer short of the price.
            if (!buyerCanAfford(order)) return 0
            def above = activeBuyNow(order.itemId).findAll {
                it.price != null && it.price > order.maxPrice && it.sellerUserId != order.buyerUserId
            }
            if (above.isEmpty()) return 0
            def itemName = order.itemName ?: above.first().item?.name ?: 'an item you list'
            def title = "A buyer wants ${itemName} at \$${order.maxPrice.toPlainString()}".toString()
            def body = 'You list it higher. Lower your price or sell to the buy order to make the sale.'
            BigDecimal bid = order.maxPrice
            String path = "/item/${order.itemId}".toString()
            return fanout(above, KIND_BUY_ORDER, order.itemId, title, body, { Long sellerId ->
                // A seller who blocked this buyer shouldn't be nudged toward them.
                blocked(sellerId, order.buyerUserId)
            }, { EmailService es, String to, String name ->
                es.sendBuyOrderOnYourItem(to, name, itemName as String, bid, path)
            })
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
            BigDecimal newPrice = listing.price
            String path = "/item/${itemId}".toString()
            return fanout(above, KIND_UNDERCUT, itemId, title, body, { Long sellerId -> false },
                { EmailService es, String to, String name ->
                    es.sendListingUndercut(to, name, itemName as String, newPrice, path)
                })
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
                       Closure<Boolean> skip, Closure email = null) {
        if (notificationService == null) return 0
        def sellerIds = listings*.sellerUserId.unique()
        def banned = [] as Set<Long>
        Map<Long, SteamUser> sellers = [:]
        if (steamUserRepository != null) {
            try {
                steamUserRepository.findAllById(sellerIds).each {
                    if (it?.id != null) sellers[it.id] = it
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
                continue
            }
            // Only a seller whose bell was just pushed gets the email, so it
            // shares the bell's 24h dedupe.
            emailAfterCommit(kind, sellers[sellerId], email)
        }
        sent
    }

    private void emailAfterCommit(String kind, SteamUser seller, Closure email) {
        if (email == null || emailService == null || seller == null) return
        try {
            if (!emailService.canSendTo(seller, EMAIL_BUCKET)) return
        } catch (Exception e) {
            log.debug("${kind} email gate failed for seller ${seller.id}: ${e.message}")
            return
        }
        String to = seller.email
        String name = seller.displayName
        Closure work = {
            try {
                email.call(emailService, to, name)
            } catch (Exception e) {
                log.warn("${kind} email failed for seller ${seller.id}: ${e.message}")
            }
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override void afterCommit() { work.call() }
            })
        } else {
            work.call()
        }
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

    /**
     * Read-only twin of BuyOrderService.tryMatch's wallet gate
     * (`wallet == null || wallet.balance < listing.price` skips the order,
     * as does an unknown buyer), applied at the bid itself: the lowest
     * price a pinged seller would drop to. With no wallet repository wired
     * the gate can't be read, so it stays open (prior behavior).
     */
    private boolean buyerCanAfford(BuyOrder order) {
        if (walletRepository == null || steamUserRepository == null) return true
        def user = steamUserRepository.findById(order.buyerUserId)?.orElse(null)
        if (user == null) return false
        def wallet = walletRepository.findByUsername("steam_${user.steamId64}".toString())
        wallet != null && wallet.balance != null && wallet.balance >= order.maxPrice
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
