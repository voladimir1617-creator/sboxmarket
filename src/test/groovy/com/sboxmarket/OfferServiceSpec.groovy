package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.InsufficientBalanceException
import com.sboxmarket.exception.ListingNotAvailableException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.exception.OfferNotPendingException
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.Offer
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.OfferRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.OfferService
import com.sboxmarket.service.PurchaseService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.security.BanGuard
import spock.lang.Specification
import spock.lang.Subject

/**
 * Unit coverage for the offer / counter-offer lifecycle.
 *
 * Branches covered: makeOffer validation (zero/above asking/self-listing),
 * counterOffer guard rails (must be higher, must not exceed asking, seller-
 * only), accept path (price swap, insufficient balance rollback, expire
 * competing offers), reject/cancel ownership checks. Purchase is mocked —
 * the monetary transaction itself is the job of PurchaseServiceSpec.
 */
class OfferServiceSpec extends Specification {

    OfferRepository     offerRepository     = Mock()
    ListingRepository   listingRepository   = Mock()
    WalletRepository    walletRepository    = Mock()
    SteamUserRepository steamUserRepository = Mock()
    PurchaseService     purchaseService     = Mock()
    BanGuard            banGuard            = Mock()
    TextSanitizer       textSanitizer       = Mock() {
        cleanShort(_) >> { String s -> s }
    }
    NotificationService notificationService = Mock()

    @Subject
    OfferService service = new OfferService(
        offerRepository     : offerRepository,
        listingRepository   : listingRepository,
        walletRepository    : walletRepository,
        steamUserRepository : steamUserRepository,
        purchaseService     : purchaseService,
        banGuard            : banGuard,
        textSanitizer       : textSanitizer,
        notificationService : notificationService
    )

    private Listing activeListing(Map args = [:]) {
        def item = new Item(id: 1L, name: 'Wizard Hat', imageUrl: 'https://example.com/x.png')
        new Listing(
            id:           args.id ?: 100L,
            item:         item,
            price:        args.price ?: new BigDecimal("50.00"),
            sellerUserId: args.seller ?: 99L,
            status:       args.status ?: 'ACTIVE'
        )
    }

    // ── makeOffer ─────────────────────────────────────────────────

    def "makeOffer creates a PENDING row for a valid buyer"() {
        given:
        listingRepository.findById(100L) >> Optional.of(activeListing())
        offerRepository.save(_) >> { Offer o -> o.id = 1L; o }

        when:
        def offer = service.makeOffer(10L, 'Alice', 100L, new BigDecimal("40"))

        then:
        1 * banGuard.assertNotBanned(10L)
        offer.status == 'PENDING'
        offer.amount == new BigDecimal("40")
        offer.buyerName == 'Alice'
        offer.askingPrice == new BigDecimal("50.00")
    }

    def "makeOffer rejects zero/negative/null amounts"() {
        when:
        service.makeOffer(10L, 'Alice', 100L, amount)

        then:
        thrown(BadRequestException)

        where:
        amount << [null, BigDecimal.ZERO, new BigDecimal("-1")]
    }

    def "makeOffer 404s for unknown listing"() {
        given:
        listingRepository.findById(_) >> Optional.empty()

        when:
        service.makeOffer(10L, 'Alice', 999L, new BigDecimal("40"))

        then:
        thrown(NotFoundException)
    }

    def "makeOffer refuses sold listings"() {
        given:
        listingRepository.findById(_) >> Optional.of(activeListing(status: 'SOLD'))

        when:
        service.makeOffer(10L, 'Alice', 100L, new BigDecimal("40"))

        then:
        thrown(ListingNotAvailableException)
    }

    def "makeOffer blocks self-offers (buyer == seller)"() {
        given:
        listingRepository.findById(_) >> Optional.of(activeListing(seller: 10L))

        when:
        service.makeOffer(10L, 'Alice', 100L, new BigDecimal("40"))

        then:
        thrown(ForbiddenException)
    }

    def "makeOffer refuses offers at or above the asking price"() {
        given:
        listingRepository.findById(_) >> Optional.of(activeListing(price: new BigDecimal("50")))

        when:
        service.makeOffer(10L, 'Alice', 100L, new BigDecimal("55"))

        then:
        thrown(BadRequestException)
    }

    def "makeOffer refuses AUCTION listings (bug #54)"() {
        given:
        def auction = activeListing()
        auction.listingType = 'AUCTION'
        listingRepository.findById(_) >> Optional.of(auction)

        when:
        service.makeOffer(10L, 'Alice', 100L, new BigDecimal("40"))

        then:
        def ex = thrown(BadRequestException)
        ex.code == 'NOT_BUY_NOW'
        0 * offerRepository.save(_)
    }

    // ── counterOffer ──────────────────────────────────────────────

    private Offer pendingOffer(Map args = [:]) {
        new Offer(
            id:            args.id ?: 1L,
            listingId:     args.listing ?: 100L,
            buyerUserId:   args.buyer ?: 10L,
            sellerUserId:  args.seller ?: 99L,
            amount:        args.amount ?: new BigDecimal("30"),
            askingPrice:   new BigDecimal("50"),
            status:        args.status ?: 'PENDING',
            author:        'USER'
        )
    }

    def "counterOffer creates a linked counter from the seller"() {
        given:
        def original = pendingOffer()
        offerRepository.findById(1L) >> Optional.of(original)
        listingRepository.findById(100L) >> Optional.of(activeListing())
        offerRepository.save(_) >> { Offer o -> o }

        when:
        def counter = service.counterOffer(99L, 1L, new BigDecimal("40"))

        then:
        counter.parentOfferId == 1L
        counter.author == 'SELLER'
        counter.amount == new BigDecimal("40")
        counter.status == 'PENDING'
    }

    def "counterOffer refuses counters lower than or equal to the buyer's offer"() {
        given:
        offerRepository.findById(_) >> Optional.of(pendingOffer(amount: new BigDecimal("30")))
        listingRepository.findById(_) >> Optional.of(activeListing())

        when:
        service.counterOffer(99L, 1L, new BigDecimal("30"))

        then:
        thrown(BadRequestException)
    }

    def "counterOffer refuses counters higher than the asking price"() {
        given:
        offerRepository.findById(_) >> Optional.of(pendingOffer())
        listingRepository.findById(_) >> Optional.of(activeListing(price: new BigDecimal("50")))

        when:
        service.counterOffer(99L, 1L, new BigDecimal("60"))

        then:
        thrown(BadRequestException)
    }

    def "counterOffer forbids non-seller authors"() {
        given:
        offerRepository.findById(_) >> Optional.of(pendingOffer())

        when:
        service.counterOffer(77L, 1L, new BigDecimal("40"))

        then:
        thrown(ForbiddenException)
    }

    def "counterOffer refuses offers that aren't PENDING"() {
        given:
        offerRepository.findById(_) >> Optional.of(pendingOffer(status: 'ACCEPTED'))

        when:
        service.counterOffer(99L, 1L, new BigDecimal("40"))

        then:
        thrown(OfferNotPendingException)
    }

    // ── buyerRaise ────────────────────────────────────────────────

    def "buyerRaise cancels the original and opens a new PENDING at the higher amount"() {
        given:
        def original = pendingOffer()  // buyer 10L at $30, ask $50
        offerRepository.findById(1L) >> Optional.of(original)
        listingRepository.findById(100L) >> Optional.of(activeListing(price: new BigDecimal("50")))
        offerRepository.save(_) >> { Offer o -> o }

        when:
        def raised = service.buyerRaise(10L, 1L, new BigDecimal("40"))

        then:
        1 * banGuard.assertNotBanned(10L)
        original.status == 'CANCELLED'
        raised.parentOfferId == 1L
        raised.author == 'USER'
        raised.amount == new BigDecimal("40")
        raised.status == 'PENDING'
        raised.buyerUserId == 10L
        raised.sellerUserId == 99L
    }

    def "buyerRaise refuses amounts less than or equal to the original"() {
        given:
        offerRepository.findById(_) >> Optional.of(pendingOffer(amount: new BigDecimal("30")))

        when:
        service.buyerRaise(10L, 1L, new BigDecimal("30"))

        then:
        thrown(BadRequestException)
    }

    def "buyerRaise refuses amounts at or above the asking price"() {
        given:
        offerRepository.findById(_) >> Optional.of(pendingOffer())
        listingRepository.findById(_) >> Optional.of(activeListing(price: new BigDecimal("50")))

        when:
        service.buyerRaise(10L, 1L, new BigDecimal("50"))

        then:
        thrown(BadRequestException)
    }

    def "buyerRaise forbids raises from someone other than the buyer"() {
        given:
        offerRepository.findById(_) >> Optional.of(pendingOffer())

        when:
        service.buyerRaise(77L, 1L, new BigDecimal("40"))

        then:
        thrown(ForbiddenException)
    }

    def "buyerRaise refuses offers that aren't PENDING"() {
        given:
        offerRepository.findById(_) >> Optional.of(pendingOffer(status: 'ACCEPTED'))

        when:
        service.buyerRaise(10L, 1L, new BigDecimal("40"))

        then:
        thrown(OfferNotPendingException)
    }

    def "buyerRaise notifies the seller of the new amount"() {
        given:
        def original = pendingOffer(amount: new BigDecimal("25"))
        offerRepository.findById(_) >> Optional.of(original)
        listingRepository.findById(_) >> Optional.of(activeListing(price: new BigDecimal("50")))
        offerRepository.save(_) >> { Offer o -> o.id = o.id ?: 2L; o }

        when:
        service.buyerRaise(10L, 1L, new BigDecimal("40"))

        then:
        1 * notificationService.push(99L, 'OFFER_RECEIVED', _, _, _, '/offers')
    }

    def "counterOffer blocks any user from countering on a system listing (bug #53)"() {
        given:
        def original = pendingOffer(seller: null)
        offerRepository.findById(1L) >> Optional.of(original)

        when:
        service.counterOffer(77L, 1L, new BigDecimal("40"))

        then:
        thrown(ForbiddenException)
        0 * offerRepository.save(_)
    }

    def "rejectOffer blocks any user from rejecting on a system listing (bug #53)"() {
        given:
        def offer = pendingOffer(seller: null)
        offerRepository.findById(1L) >> Optional.of(offer)

        when:
        service.rejectOffer(77L, 1L)

        then:
        thrown(ForbiddenException)
        0 * offerRepository.save(_)
    }

    // ── acceptOffer ───────────────────────────────────────────────

    def "acceptOffer runs a purchase at the offer price and marks ACCEPTED"() {
        given:
        def offer   = pendingOffer(amount: new BigDecimal("40"))
        def listing = activeListing(price: new BigDecimal("50"))
        def buyer   = new SteamUser(id: 10L, steamId64: '111')
        def wallet  = new Wallet(id: 500L, balance: new BigDecimal("500"))
        offerRepository.findById(1L) >> Optional.of(offer)
        listingRepository.findById(100L) >> Optional.of(listing)
        steamUserRepository.findById(10L) >> Optional.of(buyer)
        walletRepository.findByUsername('steam_111') >> wallet
        offerRepository.findPendingForListing(100L) >> []
        offerRepository.save(_) >> { Offer o -> o }
        listingRepository.save(_) >> { Listing l -> l }

        when:
        def result = service.acceptOffer(99L, 1L)

        then:
        1 * purchaseService.buy(500L, 10L, 100L)
        result.accepted == true
        result.finalPrice == new BigDecimal("40")
        offer.status == 'ACCEPTED'
    }

    def "acceptOffer flips offer to EXPIRED when buyer's wallet is short"() {
        given:
        def offer   = pendingOffer(amount: new BigDecimal("40"))
        def listing = activeListing()
        def buyer   = new SteamUser(id: 10L, steamId64: '111')
        def wallet  = new Wallet(id: 500L, balance: new BigDecimal("10"))
        offerRepository.findById(1L) >> Optional.of(offer)
        listingRepository.findById(100L) >> Optional.of(listing)
        steamUserRepository.findById(10L) >> Optional.of(buyer)
        walletRepository.findByUsername('steam_111') >> wallet

        when:
        service.acceptOffer(99L, 1L)

        then:
        thrown(InsufficientBalanceException)
        1 * offerRepository.save({ Offer o -> o.status == 'EXPIRED' })
        0 * purchaseService.buy(*_)
    }

    def "acceptOffer expires other pending offers on the same listing after a successful sale"() {
        given:
        def offer   = pendingOffer(amount: new BigDecimal("40"))
        def competing = pendingOffer(id: 2L, amount: new BigDecimal("35"))
        def listing = activeListing(price: new BigDecimal("50"))
        def buyer   = new SteamUser(id: 10L, steamId64: '111')
        def wallet  = new Wallet(id: 500L, balance: new BigDecimal("500"))
        offerRepository.findById(1L) >> Optional.of(offer)
        listingRepository.findById(100L) >> Optional.of(listing)
        steamUserRepository.findById(10L) >> Optional.of(buyer)
        walletRepository.findByUsername('steam_111') >> wallet
        offerRepository.findPendingForListing(100L) >> [competing]
        offerRepository.save(_) >> { Offer o -> o }
        listingRepository.save(_) >> { Listing l -> l }

        when:
        service.acceptOffer(99L, 1L)

        then:
        1 * purchaseService.buy(*_)
        1 * offerRepository.save({ Offer o -> o.id == 2L && o.status == 'EXPIRED' })
    }

    def "acceptOffer 404s on unknown offer id"() {
        given:
        offerRepository.findById(_) >> Optional.empty()

        when:
        service.acceptOffer(99L, 999L)

        then:
        thrown(NotFoundException)
    }

    def "acceptOffer refuses offers that aren't PENDING"() {
        given:
        offerRepository.findById(_) >> Optional.of(pendingOffer(status: 'CANCELLED'))

        when:
        service.acceptOffer(99L, 1L)

        then:
        thrown(OfferNotPendingException)
    }

    // ── rejectOffer / cancelOffer ─────────────────────────────────

    def "rejectOffer flips status to REJECTED for the seller"() {
        given:
        def offer = pendingOffer()
        offerRepository.findById(1L) >> Optional.of(offer)
        offerRepository.save(_) >> { Offer o -> o }

        when:
        def result = service.rejectOffer(99L, 1L)

        then:
        result.status == 'REJECTED'
    }

    def "rejectOffer forbids non-seller callers"() {
        given:
        offerRepository.findById(_) >> Optional.of(pendingOffer())

        when:
        service.rejectOffer(77L, 1L)

        then:
        thrown(ForbiddenException)
    }

    def "cancelOffer flips status to CANCELLED for the buyer"() {
        given:
        def offer = pendingOffer()
        offerRepository.findById(1L) >> Optional.of(offer)
        offerRepository.save(_) >> { Offer o -> o }

        when:
        def result = service.cancelOffer(10L, 1L)

        then:
        result.status == 'CANCELLED'
    }

    def "cancelOffer forbids non-buyer callers"() {
        given:
        offerRepository.findById(_) >> Optional.of(pendingOffer())

        when:
        service.cancelOffer(77L, 1L)

        then:
        thrown(ForbiddenException)
    }

    // ── thread (redaction) ───────────────────────────────────────

    private Offer threadOffer(Map args) {
        new Offer(
            id:            args.id,
            listingId:     args.listingId ?: 100L,
            buyerUserId:   args.buyerUserId,
            sellerUserId:  args.sellerUserId ?: 99L,
            amount:        args.amount ?: new BigDecimal("30"),
            askingPrice:   new BigDecimal("50"),
            buyerName:     args.buyerName ?: 'RealName',
            itemName:      'Wizard Hat',
            status:        args.status ?: 'PENDING',
            author:        args.author ?: 'USER',
            parentOfferId: args.parentOfferId
        )
    }

    def "thread returns empty list when no offers exist"() {
        given:
        offerRepository.findByListingId(100L) >> []

        expect:
        service.thread(100L, 10L) == []
    }

    def "thread returns raw offers (no redaction) to the listing seller"() {
        given:
        def a = threadOffer(id: 1L, buyerUserId: 10L, buyerName: 'Alice')
        def b = threadOffer(id: 2L, buyerUserId: 20L, buyerName: 'Bob')
        offerRepository.findByListingId(100L) >> [a, b]
        listingRepository.findById(100L) >> Optional.of(activeListing(seller: 99L))

        when:
        def out = service.thread(100L, 99L)

        then:
        out == [a, b]
        out[0].buyerName == 'Alice'
        out[0].buyerUserId == 10L
        out[1].buyerName == 'Bob'
        out[1].buyerUserId == 20L
    }

    def "thread returns raw offers (no redaction) to any participating buyer"() {
        given:
        def a = threadOffer(id: 1L, buyerUserId: 10L, buyerName: 'Alice')
        def b = threadOffer(id: 2L, buyerUserId: 20L, buyerName: 'Bob')
        offerRepository.findByListingId(100L) >> [a, b]
        listingRepository.findById(100L) >> Optional.of(activeListing(seller: 99L))

        when:
        def out = service.thread(100L, 20L)  // Bob is viewing

        then:
        out == [a, b]
        out[0].buyerName == 'Alice'
        out[1].buyerName == 'Bob'
    }

    def "thread redacts buyer identities for anonymous (null viewer) callers"() {
        given:
        def a = threadOffer(id: 1L, buyerUserId: 10L, buyerName: 'Alice')
        def b = threadOffer(id: 2L, buyerUserId: 20L, buyerName: 'Bob')
        offerRepository.findByListingId(100L) >> [a, b]
        listingRepository.findById(100L) >> Optional.of(activeListing(seller: 99L))

        when:
        def out = service.thread(100L, null)

        then:
        out.size() == 2
        out*.buyerName == ['Buyer #1', 'Buyer #2']
        out*.buyerUserId == [null, null]
        // Original entities untouched — redaction returns fresh detached copies
        a.buyerName == 'Alice'
        b.buyerName == 'Bob'
    }

    def "thread redacts buyer identities for a logged-in third party"() {
        given:
        def a = threadOffer(id: 1L, buyerUserId: 10L, buyerName: 'Alice')
        def b = threadOffer(id: 2L, buyerUserId: 20L, buyerName: 'Bob')
        offerRepository.findByListingId(100L) >> [a, b]
        listingRepository.findById(100L) >> Optional.of(activeListing(seller: 99L))

        when:
        def out = service.thread(100L, 77L)  // Unrelated user

        then:
        out*.buyerName == ['Buyer #1', 'Buyer #2']
        out*.buyerUserId == [null, null]
    }

    def "thread gives the same handle to the same buyer across a counter chain"() {
        given:
        def original = threadOffer(id: 1L, buyerUserId: 10L, buyerName: 'Alice')
        def counter  = threadOffer(id: 2L, buyerUserId: 10L, buyerName: 'Alice',
                                   author: 'SELLER', parentOfferId: 1L, amount: new BigDecimal("40"))
        def thirdParty = threadOffer(id: 3L, buyerUserId: 20L, buyerName: 'Bob')
        offerRepository.findByListingId(100L) >> [original, counter, thirdParty]
        listingRepository.findById(100L) >> Optional.of(activeListing(seller: 99L))

        when:
        def out = service.thread(100L, null)

        then:
        out*.buyerName == ['Buyer #1', 'Buyer #1', 'Buyer #2']
    }

    def "thread redacts when listing has been deleted (listingRepository returns empty)"() {
        given:
        def a = threadOffer(id: 1L, buyerUserId: 10L, buyerName: 'Alice')
        offerRepository.findByListingId(100L) >> [a]
        listingRepository.findById(100L) >> Optional.empty()

        when:
        def out = service.thread(100L, 99L)  // "seller" but no listing to prove it

        then:
        out.size() == 1
        out[0].buyerName == 'Buyer #1'
        out[0].buyerUserId == null
    }

    // ── typicalResponseMs ──────────────────────────────────────────

    private Offer resp(Map a) {
        new Offer(
            id:           a.id ?: 1L,
            sellerUserId: a.sellerUserId ?: 99L,
            author:       a.author ?: 'USER',
            status:       a.status ?: 'ACCEPTED',
            createdAt:    a.createdAt,
            updatedAt:    a.updatedAt
        )
    }

    def "typicalResponseMs returns null for a null seller id"() {
        expect:
        service.typicalResponseMs(null) == null
    }

    def "typicalResponseMs returns null when fewer than three resolved responses exist"() {
        given:
        offerRepository.findRecentSellerResponses(99L, _) >> [
            resp(id: 1L, createdAt: 0L,   updatedAt: 60_000L),
            resp(id: 2L, createdAt: 100L, updatedAt: 120_000L)
        ]

        expect:
        service.typicalResponseMs(99L) == null
    }

    def "typicalResponseMs returns the median delta when at least three responses exist"() {
        given:
        // Deltas: 60s, 300s, 900s → median is 300s = 300_000ms
        offerRepository.findRecentSellerResponses(99L, _) >> [
            resp(id: 1L, createdAt: 0L, updatedAt: 60_000L),
            resp(id: 2L, createdAt: 0L, updatedAt: 300_000L),
            resp(id: 3L, createdAt: 0L, updatedAt: 900_000L)
        ]

        expect:
        service.typicalResponseMs(99L) == 300_000L
    }

    def "typicalResponseMs ignores rows whose delta is non-positive (clock skew / same ms)"() {
        given:
        offerRepository.findRecentSellerResponses(99L, _) >> [
            resp(id: 1L, createdAt: 1_000L, updatedAt: 1_000L),   // delta=0, dropped
            resp(id: 2L, createdAt: 2_000L, updatedAt: 1_500L),   // delta=-500, dropped
            resp(id: 3L, createdAt: 0L,     updatedAt: 60_000L),
            resp(id: 4L, createdAt: 0L,     updatedAt: 120_000L),
            resp(id: 5L, createdAt: 0L,     updatedAt: 180_000L)
        ]

        expect:
        // After filtering, deltas = [60_000, 120_000, 180_000] → median 120_000
        service.typicalResponseMs(99L) == 120_000L
    }

    // ── responseRatePct ────────────────────────────────────────────

    def "responseRatePct returns null for a null seller id"() {
        expect:
        service.responseRatePct(null) == null
    }

    def "responseRatePct returns null below the five-offer noise floor without hitting the numerator query"() {
        given:
        offerRepository.countSellerEngagedTotal(99L) >> 4L

        when:
        def result = service.responseRatePct(99L)

        then:
        result == null
        0 * offerRepository.countSellerResponded(_)
    }

    def "responseRatePct returns 100 when every engaged offer was resolved"() {
        given:
        offerRepository.countSellerEngagedTotal(99L) >> 10L
        offerRepository.countSellerResponded(99L)    >> 10L

        expect:
        service.responseRatePct(99L) == 100.0d
    }

    def "responseRatePct returns the responded-over-total fraction as a percentage"() {
        given:
        offerRepository.countSellerEngagedTotal(99L) >> 20L
        offerRepository.countSellerResponded(99L)    >> 15L

        expect:
        service.responseRatePct(99L) == 75.0d
    }

    // ── pendingOfferSummaryForSeller (MyStall best-offer chip) ─────

    def "pendingOfferSummaryForSeller returns an empty map for a null id without hitting the repo"() {
        when:
        def out = service.pendingOfferSummaryForSeller(null)

        then:
        out == [:]
        0 * offerRepository.aggregatePendingBySeller(_)
    }

    def "pendingOfferSummaryForSeller unpacks repo rows into a per-listing summary"() {
        given:
        offerRepository.aggregatePendingBySeller(99L) >> [
            [100L as Long, new BigDecimal("45.00"), 3L as Long, 1700000000000L as Long] as Object[],
            [200L as Long, new BigDecimal("10.00"), 1L as Long, 1700000001000L as Long] as Object[]
        ]

        when:
        def out = service.pendingOfferSummaryForSeller(99L)

        then:
        out.size() == 2
        out[100L].bestAmount == new BigDecimal("45.00")
        out[100L].count      == 3L
        out[100L].newestAt   == 1700000000000L
        out[200L].bestAmount == new BigDecimal("10.00")
        out[200L].count      == 1L
    }
}
