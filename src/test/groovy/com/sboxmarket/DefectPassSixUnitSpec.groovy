package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.OfferNotPendingException
import com.sboxmarket.model.BuyOrder
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.Offer
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.BuyOrderRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.OfferRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.BuyOrderService
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.OfferService
import com.sboxmarket.service.PurchaseService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.security.BanGuard
import spock.lang.Specification

/**
 * Defect pass 6, unit level: offer acceptance against a lowered price
 * and past its auto-decline time, the buyer-cancel message to the
 * seller, and buy-order edits racing a fill.
 */
class DefectPassSixUnitSpec extends Specification {

    OfferRepository     offerRepository     = Mock()
    ListingRepository   listingRepository   = Mock()
    WalletRepository    walletRepository    = Mock()
    SteamUserRepository steamUserRepository = Mock()
    PurchaseService     purchaseService     = Mock()
    BanGuard            banGuard            = Mock()
    TextSanitizer       textSanitizer       = Mock() {
        cleanShort(_) >> { String s -> s }
        clean(_, _) >> { String s, int n -> s }
    }
    NotificationService notificationService = Mock()

    OfferService offers = new OfferService(
        offerRepository: offerRepository, listingRepository: listingRepository,
        walletRepository: walletRepository, steamUserRepository: steamUserRepository,
        purchaseService: purchaseService, banGuard: banGuard,
        textSanitizer: textSanitizer, notificationService: notificationService)

    private Offer offer(Map args = [:]) {
        new Offer(id: 1L, listingId: 100L, buyerUserId: 10L, sellerUserId: 99L,
            amount: args.amount ?: new BigDecimal('40'), askingPrice: new BigDecimal('50'),
            status: args.status ?: 'PENDING', author: args.author ?: 'USER',
            itemName: 'Wizard Hat', updatedAt: args.updatedAt ?: System.currentTimeMillis())
    }

    private void wireAccept(Offer o, Listing listing) {
        offerRepository.findById(1L) >> Optional.of(o)
        listingRepository.findById(100L) >> Optional.of(listing)
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, steamId64: '111'))
        walletRepository.findByUsername('steam_111') >> new Wallet(id: 500L, balance: new BigDecimal('500'))
        offerRepository.findPendingForListing(100L) >> []
        offerRepository.save(_) >> { Offer x -> x }
        listingRepository.save(_) >> { Listing l -> l }
    }

    def "accepting an offer above a since-lowered price charges the current price"() {
        given:
        def o = offer(amount: new BigDecimal('40'))
        def listing = new Listing(id: 100L, item: new Item(id: 1L, name: 'Wizard Hat'),
            price: new BigDecimal('30'), sellerUserId: 99L, status: 'ACTIVE')
        wireAccept(o, listing)
        BigDecimal priceAtPurchase = null

        when:
        def result = offers.acceptOffer(99L, 1L)

        then:
        1 * purchaseService.buy(500L, 10L, 100L) >> { priceAtPurchase = listing.price; null }
        priceAtPurchase == new BigDecimal('30')
        result.finalPrice == new BigDecimal('30')
    }

    def "an offer past its auto-decline time cannot be accepted"() {
        given:
        offers.autoDeclineDays = 7
        def o = offer(updatedAt: System.currentTimeMillis() - 8L * 24 * 3600 * 1000)
        wireAccept(o, new Listing(id: 100L, item: new Item(id: 1L, name: 'Wizard Hat'),
            price: new BigDecimal('50'), sellerUserId: 99L, status: 'ACTIVE'))

        when:
        offers.acceptOffer(99L, 1L)

        then:
        thrown(OfferNotPendingException)
        0 * purchaseService.buy(*_)
    }

    def "declining a seller counter tells the seller their counter was declined"() {
        given:
        def counter = offer(author: 'SELLER', amount: new BigDecimal('45'))
        offerRepository.findById(1L) >> Optional.of(counter)
        offerRepository.save(_) >> { Offer x -> x }

        when:
        offers.cancelOffer(10L, 1L)

        then:
        1 * notificationService.push(99L, 'OFFER_REJECTED',
            { it.toString().startsWith('Buyer declined your counter') },
            'They declined your $45 counter.', 1L, '/offers')
    }

    def "withdrawing an offer the seller already countered does not say they never responded"() {
        given:
        def countered = offer(status: 'COUNTERED')
        offerRepository.findById(1L) >> Optional.of(countered)
        offerRepository.findByParentOfferId(1L) >> []
        offerRepository.save(_) >> { Offer x -> x }

        when:
        offers.cancelOffer(10L, 1L)

        then:
        1 * notificationService.push(99L, 'OFFER_REJECTED', _,
            'They withdrew their $40 offer after your counter.', 1L, '/offers')
    }

    // ── Buy orders ────────────────────────────────────────────────

    BuyOrderRepository buyOrderRepository = Mock()
    BuyOrderService buyOrders = new BuyOrderService(
        buyOrderRepository: buyOrderRepository, notificationService: notificationService,
        textSanitizer: textSanitizer, banGuard: banGuard)

    def "editing a buy order re-reads it under lock so a fill that just landed is not undone"() {
        given:
        buyOrderRepository.findById(7L) >> Optional.of(new BuyOrder(id: 7L, buyerUserId: 10L, status: 'ACTIVE', quantity: 1))
        buyOrderRepository.findByIdForUpdate(7L) >> new BuyOrder(id: 7L, buyerUserId: 10L, status: 'FILLED', quantity: 0)

        when:
        buyOrders.update(10L, 7L, new BigDecimal('20'), null)

        then:
        def e = thrown(BadRequestException)
        e.code == 'NOT_ACTIVE'
        0 * buyOrderRepository.save(_)
    }

    def "cancel-all skips an order that filled after the list was read"() {
        given:
        buyOrderRepository.findByBuyer(10L) >> [new BuyOrder(id: 7L, buyerUserId: 10L, status: 'ACTIVE', quantity: 1)]
        buyOrderRepository.findById(7L) >> Optional.of(new BuyOrder(id: 7L, buyerUserId: 10L, status: 'FILLED', quantity: 0))

        when:
        def n = buyOrders.cancelAllForUser(10L)

        then:
        n == 0
        0 * buyOrderRepository.save(_)
    }
}
