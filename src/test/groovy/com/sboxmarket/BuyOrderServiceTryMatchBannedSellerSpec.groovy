package com.sboxmarket

import com.sboxmarket.model.Listing
import com.sboxmarket.repository.BuyOrderRepository
import com.sboxmarket.service.BuyOrderService
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.PurchaseService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.security.BanGuard
import spock.lang.Specification

/**
 * Wave 142 regression pin for {@link BuyOrderService#tryMatch}.
 *
 * Same banned-counterparty gap as wave 141 closed in
 * OfferService.acceptOffer for the buyer-accepts-counter path.
 *
 * Scenario: seller posts an ACTIVE BUY_NOW listing. Buy order in the
 * matching engine queue ALREADY EXISTS at a price that triggers a
 * fill. Staff bans the seller mid-flight (before tryMatch finds the
 * listing). PurchaseService.buy only checks the BUYER ban — the seller
 * is not re-checked at fill time. Pre-fix: buyer's wallet drains, the
 * listing flips SOLD, banned seller's wallet would be credited via the
 * Trade escrow. The matching engine MUST refuse to route new sales to
 * a banned seller; the listing should sit until ListingService's
 * sweeper takes it down.
 *
 * System listings (sellerUserId == null) skip the gate — they have no
 * seller wallet to ban.
 */
class BuyOrderServiceTryMatchBannedSellerSpec extends Specification {

    BuyOrderRepository buyOrderRepository = Mock()
    NotificationService notificationService = Mock()
    PurchaseService purchaseService = Mock()
    BanGuard banGuard = Mock()
    TextSanitizer textSanitizer = Mock()

    BuyOrderService service = new BuyOrderService(
        buyOrderRepository:  buyOrderRepository,
        notificationService: notificationService,
        purchaseService:     purchaseService,
        banGuard:            banGuard,
        textSanitizer:       textSanitizer
    )

    private Listing makeListing(Long sellerUserId) {
        new Listing(
            id:           1001L,
            sellerUserId: sellerUserId,
            status:       'ACTIVE',
            listingType:  'BUY_NOW',
            price:        new BigDecimal('10.00')
        )
    }

    def "tryMatch refuses to route a fill to a banned seller"() {
        given:
        def listing = makeListing(7L)
        banGuard.isBanned(7L) >> true

        when:
        service.tryMatch(listing)

        then: "no matching engine work runs — buyer is safe from inadvertent purchase from a banned account"
        0 * buyOrderRepository.findMatching(_, _, _, _, _)
        0 * purchaseService.buy(_, _, _)
    }

    def "tryMatch still runs the engine for an unbanned seller"() {
        given:
        def listing = makeListing(7L)
        banGuard.isBanned(7L) >> false
        buyOrderRepository.findMatching(_, _, _, _, _) >> []

        when:
        service.tryMatch(listing)

        then: "engine runs (it returns empty here, so no buy fires — but the matching probe DID happen)"
        1 * buyOrderRepository.findMatching(_, _, _, _, _) >> []
    }

    def "tryMatch skips system listings (sellerUserId == null) without ban probing — no seller to ban"() {
        given:
        def listing = makeListing(null)
        buyOrderRepository.findMatching(_, _, _, _, _) >> []

        when:
        service.tryMatch(listing)

        then: "the banGuard.isBanned() probe never fires for system listings"
        0 * banGuard.isBanned(_)

        and: "the engine still runs"
        1 * buyOrderRepository.findMatching(_, _, _, _, _) >> []
    }
}
