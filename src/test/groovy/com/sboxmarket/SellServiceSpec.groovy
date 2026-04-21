package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.ListingNotAvailableException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.Bid
import com.sboxmarket.repository.BidRepository
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.service.BuyOrderService
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.SellService
import com.sboxmarket.service.TradeService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.security.BanGuard
import spock.lang.Specification
import spock.lang.Subject

/**
 * Unit coverage for the relist (sell-from-inventory) + cancelListing
 * flows. BuyOrderService is mocked — the matching engine is covered in
 * BuyOrderServiceSpec and we only want to prove SellService invokes it
 * without failing the parent transaction.
 */
class SellServiceSpec extends Specification {

    ListingRepository   listingRepository   = Mock()
    ItemRepository      itemRepository      = Mock()
    TradeRepository     tradeRepository     = Mock()
    BidRepository       bidRepository       = Mock()
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
        buyOrderService:     buyOrderService,
        tradeService:        tradeService,
        notificationService: notificationService,
        banGuard:            banGuard,
        textSanitizer:       textSanitizer
    )

    private Listing owned(Map args = [:]) {
        new Listing(
            id:           args.id ?: 50L,
            item:         new Item(id: 1L, name: 'Wizard Hat'),
            price:        new BigDecimal("40"),
            buyerUserId:  args.owner ?: 10L,
            sellerUserId: args.originalSeller ?: 99L,
            status:       args.status ?: 'SOLD',
            rarityScore:  new BigDecimal("0.5")
        )
    }

    // ── relist ────────────────────────────────────────────────────

    def "relist creates a fresh ACTIVE listing under the seller"() {
        given:
        def oldListing = owned()
        listingRepository.findById(50L) >> Optional.of(oldListing)
        listingRepository.save(_) >> { args -> def l = args[0]; l.id = l.id ?: 100L; l }

        when:
        def fresh = service.relist(10L, 'Alice', 50L, new BigDecimal("80"))

        then:
        1 * banGuard.assertNotBanned(10L)
        fresh.status == 'ACTIVE'
        fresh.price == new BigDecimal("80")
        fresh.sellerUserId == 10L
        fresh.sellerName == 'Alice'
        oldListing.status == 'RELISTED'
        1 * buyOrderService.tryMatch(_)
    }

    def "relist swallows buy-order-match exceptions"() {
        given:
        listingRepository.findById(_) >> Optional.of(owned())
        listingRepository.save(_) >> { args -> args[0] }
        buyOrderService.tryMatch(_) >> { throw new RuntimeException("boom") }

        when:
        def fresh = service.relist(10L, 'Alice', 50L, new BigDecimal("80"))

        then:
        // Still returns the fresh listing even though the match engine blew up
        fresh != null
        fresh.status == 'ACTIVE'
    }

    def "relist refuses zero/negative/null prices"() {
        when:
        service.relist(10L, 'Alice', 50L, price)

        then:
        thrown(BadRequestException)

        where:
        price << [null, BigDecimal.ZERO, new BigDecimal("-1")]
    }

    def 'relist refuses prices above the $100k ceiling'() {
        when:
        service.relist(10L, 'Alice', 50L, new BigDecimal("100001"))

        then:
        thrown(BadRequestException)
    }

    def "relist 404s for unknown listing id"() {
        given:
        listingRepository.findById(_) >> Optional.empty()

        when:
        service.relist(10L, 'Alice', 999L, new BigDecimal("80"))

        then:
        thrown(NotFoundException)
    }

    def "relist forbids non-owner"() {
        given:
        listingRepository.findById(_) >> Optional.of(owned(owner: 99L))

        when:
        service.relist(10L, 'Alice', 50L, new BigDecimal("80"))

        then:
        thrown(ForbiddenException)
    }

    def "relist refuses items still ACTIVE (not in inventory)"() {
        given:
        listingRepository.findById(_) >> Optional.of(owned(status: 'ACTIVE'))

        when:
        service.relist(10L, 'Alice', 50L, new BigDecimal("80"))

        then:
        thrown(BadRequestException)
    }

    def "relist creates an AUCTION listing with expiresAt set when listingType=AUCTION"() {
        given:
        def oldListing = owned()
        listingRepository.findById(50L) >> Optional.of(oldListing)
        listingRepository.save(_) >> { args -> def l = args[0]; l.id = l.id ?: 100L; l }
        def before = System.currentTimeMillis()

        when:
        def fresh = service.relist(10L, 'Alice', 50L, new BigDecimal('80'), 'AUCTION', 24L)

        then:
        fresh.listingType == 'AUCTION'
        fresh.expiresAt != null
        // expiresAt should be within +24h (give a generous window for slow test execution).
        fresh.expiresAt >= before + (23L * 60L * 60L * 1000L)
        fresh.expiresAt <= System.currentTimeMillis() + (25L * 60L * 60L * 1000L)
        // Buy-order match never runs for auctions (tryMatch gates on BUY_NOW),
        // but SellService still calls it unconditionally — the gate lives
        // inside tryMatch. We don't assert 0 calls because that lives in
        // BuyOrderService's own coverage.
    }

    def "relist refuses AUCTION listingType without durationHours"() {
        given:
        listingRepository.findById(50L) >> Optional.of(owned())

        when:
        service.relist(10L, 'Alice', 50L, new BigDecimal('80'), 'AUCTION', null)

        then:
        thrown(BadRequestException)
    }

    def "relist refuses AUCTION with out-of-range duration"() {
        given:
        listingRepository.findById(50L) >> Optional.of(owned())

        when:
        service.relist(10L, 'Alice', 50L, new BigDecimal('80'), 'AUCTION', hours)

        then:
        thrown(BadRequestException)

        where:
        hours << [0L, 169L, -1L]
    }

    def "relist defaults to BUY_NOW when listingType is null / unknown"() {
        given:
        listingRepository.findById(50L) >> Optional.of(owned())
        listingRepository.save(_) >> { args -> def l = args[0]; l.id = l.id ?: 100L; l }

        when:
        def fresh = service.relist(10L, 'Alice', 50L, new BigDecimal('80'), type, null)

        then:
        fresh.listingType == 'BUY_NOW'
        fresh.expiresAt == null

        where:
        type << [null, 'GIBBERISH', '']
    }

    // ── maxDiscount (batch 646) ──────────────────────────────────────

    def "relist stores maxDiscount when in-range"() {
        given:
        listingRepository.findById(50L) >> Optional.of(owned())
        listingRepository.save(_) >> { args -> def l = args[0]; l.id = l.id ?: 100L; l }

        when:
        def fresh = service.relist(10L, 'Alice', 50L, new BigDecimal('80'),
                                   'BUY_NOW', null, null, null, new BigDecimal('0.20'))

        then:
        fresh.maxDiscount == new BigDecimal('0.20')
    }

    def "relist collapses maxDiscount=0 to null (no auto-accept)"() {
        given:
        listingRepository.findById(50L) >> Optional.of(owned())
        listingRepository.save(_) >> { args -> def l = args[0]; l.id = l.id ?: 100L; l }

        when:
        def fresh = service.relist(10L, 'Alice', 50L, new BigDecimal('80'),
                                   'BUY_NOW', null, null, null, BigDecimal.ZERO)

        then:
        // 0 means "no auto-accept" — service should normalise to null
        // rather than persisting a sentinel that later readers have to
        // special-case.
        fresh.maxDiscount == null
    }

    def "relist rejects maxDiscount outside [0, 1)"() {
        given:
        listingRepository.findById(50L) >> Optional.of(owned())

        when:
        service.relist(10L, 'Alice', 50L, new BigDecimal('80'),
                       'BUY_NOW', null, null, null, bad)

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_DISCOUNT'

        where:
        bad << [new BigDecimal('-0.01'), new BigDecimal('1'), new BigDecimal('1.5')]
    }

    // ── cancelListing ─────────────────────────────────────────────

    def "cancelListing returns the item to seller inventory"() {
        given:
        def listing = new Listing(
            id: 100L, status: 'ACTIVE', sellerUserId: 10L,
            item: new Item(id: 1L, name: 'x')
        )
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { args -> args[0] }

        when:
        service.cancelListing(10L, 100L)

        then:
        listing.status == 'SOLD'
        listing.buyerUserId == 10L
    }

    def "cancelListing forbids non-seller"() {
        given:
        listingRepository.findById(_) >> Optional.of(new Listing(id: 100L, status: 'ACTIVE', sellerUserId: 99L))

        when:
        service.cancelListing(10L, 100L)

        then:
        thrown(ForbiddenException)
    }

    def "cancelListing refuses already-sold listings"() {
        given:
        listingRepository.findById(_) >> Optional.of(new Listing(id: 100L, status: 'SOLD', sellerUserId: 10L))

        when:
        service.cancelListing(10L, 100L)

        then:
        thrown(ListingNotAvailableException)
    }

    def "cancelListing auto-cancels open trades on the listing (bug #69)"() {
        given:
        def listing = new Listing(
            id: 100L, status: 'ACTIVE', sellerUserId: 10L,
            item: new Item(id: 1L, name: 'x')
        )
        def openTrade = new com.sboxmarket.model.Trade(
            id: 5L, listingId: 100L, state: 'PENDING_SELLER_SEND',
            buyerUserId: 20L, sellerUserId: 10L
        )
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { args -> args[0] }
        tradeRepository.findByListingId(100L) >> openTrade

        when:
        service.cancelListing(10L, 100L)

        then:
        1 * tradeService.cancel(10L, 5L, "Seller cancelled listing")
        listing.status == 'SOLD'
    }

    def "cancelListing on an AUCTION with live bids cancels the bids and pings each distinct bidder"() {
        given:
        def listing = new Listing(
            id: 100L, status: 'ACTIVE', sellerUserId: 10L,
            listingType: 'AUCTION',
            item: new Item(id: 7L, name: 'Wizard Hat')
        )
        def b1 = new Bid(id: 1L, listingId: 100L, bidderUserId: 20L, status: 'WINNING')
        def b2 = new Bid(id: 2L, listingId: 100L, bidderUserId: 21L, status: 'OUTBID')
        // Same bidder bids twice — should only get one notification.
        def b3 = new Bid(id: 3L, listingId: 100L, bidderUserId: 20L, status: 'OUTBID')
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { args -> args[0] }
        bidRepository.findByListing(100L) >> [b1, b2, b3]

        when:
        service.cancelListing(10L, 100L)

        then:
        1 * bidRepository.saveAll({ Iterable bids -> bids*.status.every { it == 'CANCELLED' } })
        1 * notificationService.push(20L, 'AUCTION_CANCELLED', _, _, 100L, _)
        1 * notificationService.push(21L, 'AUCTION_CANCELLED', _, _, 100L, _)
        // Deduped — no third push for the same bidder.
        0 * notificationService.push(20L, 'AUCTION_CANCELLED', _, _, _, _)
        listing.status == 'SOLD'
    }

    def "cancelListing on a BUY_NOW listing never touches bid/notification services"() {
        given:
        def listing = new Listing(
            id: 100L, status: 'ACTIVE', sellerUserId: 10L,
            listingType: 'BUY_NOW',
            item: new Item(id: 7L, name: 'Wizard Hat')
        )
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { args -> args[0] }

        when:
        service.cancelListing(10L, 100L)

        then:
        0 * bidRepository.findByListing(_)
        0 * notificationService.push(*_)
    }

    def "cancelListing skips already-settled trades (bug #69)"() {
        given:
        def listing = new Listing(
            id: 100L, status: 'ACTIVE', sellerUserId: 10L,
            item: new Item(id: 1L, name: 'x')
        )
        def settledTrade = new com.sboxmarket.model.Trade(
            id: 5L, listingId: 100L, state: 'VERIFIED',
            buyerUserId: 20L, sellerUserId: 10L
        )
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { args -> args[0] }
        tradeRepository.findByListingId(100L) >> settledTrade

        when:
        service.cancelListing(10L, 100L)

        then:
        0 * tradeService.cancel(_, _, _)
        listing.status == 'SOLD'
    }

    // ── relist description (batch 304) ────────────────────────────

    def "relist carries the description through to the fresh listing"() {
        given:
        listingRepository.findById(_) >> Optional.of(owned())
        Listing saved = null
        listingRepository.save(_) >> { args -> def l = args[0]; if (l.id == null) saved = l; l.id = l.id ?: 100L; l }

        when:
        service.relist(10L, 'Alice', 50L, new BigDecimal("80"),
            'BUY_NOW', null, 'Quick sale, open to offers')

        then:
        saved != null
        saved.description == 'Quick sale, open to offers'
    }

    def "relist leaves description null when the caller omits it"() {
        given:
        listingRepository.findById(_) >> Optional.of(owned())
        Listing saved = null
        listingRepository.save(_) >> { args -> def l = args[0]; if (l.id == null) saved = l; l.id = l.id ?: 100L; l }

        when:
        service.relist(10L, 'Alice', 50L, new BigDecimal("80"))

        then:
        saved != null
        saved.description == null
    }

    def "relist treats a whitespace-only description as null"() {
        given:
        listingRepository.findById(_) >> Optional.of(owned())
        Listing saved = null
        listingRepository.save(_) >> { args -> def l = args[0]; if (l.id == null) saved = l; l.id = l.id ?: 100L; l }

        when:
        service.relist(10L, 'Alice', 50L, new BigDecimal("80"),
            'BUY_NOW', null, '   \t  ')

        then:
        saved != null
        saved.description == null
    }
}
