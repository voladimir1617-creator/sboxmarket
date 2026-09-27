package com.sboxmarket

import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.Trade
import com.sboxmarket.repository.BidRepository
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.OfferRepository
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.service.BuyOrderService
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.SellService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.TradeService
import com.sboxmarket.service.security.BanGuard
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionStatus
import spock.lang.Specification
import spock.lang.Subject

/**
 * Pin for the rollback-only-leak fix on SellService.cancelListing.
 *
 * THE BUG (pre-fix): cancelListing is @Transactional(REQUIRED). Inside it,
 * tradeService.cancel was called BARE — that method is itself
 * @Transactional(REQUIRED) so the call JOINED the outer cancelListing
 * transaction. A failing save inside cancel (a Listing optimistic-lock
 * collision from a concurrent dispute, a wallet refund hiccup, a
 * transitionTo race) marked the SHARED outer tx rollback-only BEFORE the
 * throw reached the catch in cancelListing. The catch swallowed the throw
 * and cancelListing continued through offer cleanup, bid cleanup, cart
 * fanout and the final listing.status = 'SOLD' flip — but at commit time
 * Spring threw UnexpectedRollbackException and EVERY write in
 * cancelListing silently rolled back while the caller's HTTP response said
 * 200. The trade was stuck in mid-cancel, the listing stayed ACTIVE on
 * the marketplace, and the cart/bid pings about a "cancelled" listing
 * had already fired. Same bug class as wave 60 / TradeProtectionService
 * and the relist tryMatch deferral.
 *
 * THE FIX: wrap tradeService.cancel in runInIsolatedTx so it runs in a
 * REQUIRES_NEW sub-tx. A failure inside cancel rolls back only that
 * sub-tx; the outer cancelListing tx is never poisoned and the catch
 * cleanly swallows + logs the failure.
 *
 * Pre-fix this spec FAILS — the bare tradeService.cancel call never
 * touches the TransactionManager, so the "exactly one REQUIRES_NEW
 * commit per cancel" expectation registers zero invocations.
 */
class SellServiceCancelListingTxIsolationSpec extends Specification {

    ListingRepository   listingRepository   = Mock()
    ItemRepository      itemRepository      = Mock()
    TradeRepository     tradeRepository     = Mock()
    BidRepository       bidRepository       = Mock()
    OfferRepository     offerRepository     = Mock()
    BuyOrderService     buyOrderService     = Mock()
    TradeService        tradeService        = Mock()
    NotificationService notificationService = Mock()
    BanGuard            banGuard            = Mock()
    TextSanitizer       textSanitizer       = Mock() {
        cleanShort(_) >> { String s -> s }
        clean(_, _) >> { args -> args[0] as String }
    }

    @Subject
    SellService service = new SellService(
        listingRepository:   listingRepository,
        itemRepository:      itemRepository,
        tradeRepository:     tradeRepository,
        bidRepository:       bidRepository,
        offerRepository:     offerRepository,
        buyOrderService:     buyOrderService,
        tradeService:        tradeService,
        notificationService: notificationService,
        banGuard:            banGuard,
        textSanitizer:       textSanitizer
    )

    def "cancelListing wraps tradeService.cancel in a REQUIRES_NEW sub-tx (rollback-only-leak fix)"() {
        given: "an active listing with an open trade in escrow"
        def listing = new Listing(
            id: 100L, status: 'ACTIVE', sellerUserId: 10L,
            item: new Item(id: 1L, name: 'x')
        )
        def openTrade = new Trade(
            id: 5L, listingId: 100L, state: 'PENDING_SELLER_SEND',
            buyerUserId: 20L, sellerUserId: 10L
        )
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { args -> args[0] }
        tradeRepository.findByListingId(100L) >> openTrade

        and: "a real-shape PlatformTransactionManager that records each sub-tx open/commit"
        def txStatus  = Mock(TransactionStatus)
        def txManager = Mock(PlatformTransactionManager) {
            getTransaction(_) >> txStatus
        }
        service.transactionManager = txManager

        when:
        service.cancelListing(10L, 100L)

        then: "the trade cancel ran INSIDE its own REQUIRES_NEW commit — the outer cancelListing tx is shielded"
        1 * txManager.commit(txStatus)
        1 * tradeService.cancel(10L, 5L, "Seller cancelled listing")
        and: "the listing is still returned to the seller's inventory"
        listing.status == 'SOLD'
        listing.buyerUserId == 10L
    }

    def "cancelListing sub-tx isolates a failing tradeService.cancel — outer flow still flips the listing to SOLD"() {
        given: "an active listing with an open trade whose cancel will explode mid-flight"
        def listing = new Listing(
            id: 100L, status: 'ACTIVE', sellerUserId: 10L,
            item: new Item(id: 1L, name: 'x')
        )
        def openTrade = new Trade(
            id: 5L, listingId: 100L, state: 'PENDING_SELLER_SEND',
            buyerUserId: 20L, sellerUserId: 10L
        )
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { args -> args[0] }
        tradeRepository.findByListingId(100L) >> openTrade

        and: "a tx manager — the failing inner sub-tx must rollback on the SAME status"
        def txStatus  = Mock(TransactionStatus)
        def txManager = Mock(PlatformTransactionManager) {
            getTransaction(_) >> txStatus
        }
        service.transactionManager = txManager
        // Simulate a real-world failure inside cancel: an optimistic-lock
        // from a concurrent dispute. Pre-fix this would have marked the
        // SHARED outer cancelListing tx rollback-only.
        tradeService.cancel(10L, 5L, _) >> {
            throw new org.springframework.dao.OptimisticLockingFailureException(
                "Listing version race in tradeService.cancel")
        }

        when:
        service.cancelListing(10L, 100L)

        then: "the failed cancel's sub-tx rolls back; the OUTER cancelListing tx is never poisoned"
        1 * txManager.rollback(txStatus)
        0 * txManager.commit(_)
        and: "cancelListing's outer flow still completes — listing returned to inventory"
        listing.status == 'SOLD'
        listing.buyerUserId == 10L
    }

    def "cancelListing falls back to inline trade-cancel when no PlatformTransactionManager is wired (unit-test path)"() {
        // Defensive: existing SellServiceSpec instantiates the service via
        // the property-map constructor with NO transactionManager. The
        // runInIsolatedTx fallback must still execute the closure inline so
        // every existing tradeService.cancel assertion in SellServiceSpec
        // (e.g. "cancelListing auto-cancels open trades on the listing
        // (bug #69)") keeps firing. Without this fallback, every such
        // spec becomes a silent no-op the moment we adopt the wrapper.
        given:
        def listing = new Listing(
            id: 100L, status: 'ACTIVE', sellerUserId: 10L,
            item: new Item(id: 1L, name: 'x')
        )
        def openTrade = new Trade(
            id: 5L, listingId: 100L, state: 'PENDING_SELLER_SEND',
            buyerUserId: 20L, sellerUserId: 10L
        )
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { args -> args[0] }
        tradeRepository.findByListingId(100L) >> openTrade
        // Deliberately do NOT wire a transactionManager — null is the
        // inline-fallback signal.
        service.transactionManager = null

        when:
        service.cancelListing(10L, 100L)

        then: "the trade-cancel call still lands on the underlying mock through the inline fallback"
        1 * tradeService.cancel(10L, 5L, "Seller cancelled listing")
        listing.status == 'SOLD'
    }
}
