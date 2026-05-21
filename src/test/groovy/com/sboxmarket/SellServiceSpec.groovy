package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.ListingNotAvailableException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.Bid
import com.sboxmarket.model.Offer
import com.sboxmarket.repository.BidRepository
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.OfferRepository
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

    // ── cancelListing live-offer cleanup ──────────────────────────
    //
    // When a seller cancels a listing, every still-live offer on it
    // must reach a terminal state. "Live" is PENDING *or* COUNTERED —
    // both are negotiation states `findLiveByBuyerAndListing` reports
    // as a live offer (the ItemModal "You offered $X" chip). A
    // PENDING-only sweep left a COUNTERED buyer original dangling.

    private Offer offer(Map args = [:]) {
        new Offer(
            id:           args.id,
            listingId:    args.listingId ?: 100L,
            buyerUserId:  args.buyerUserId ?: 20L,
            sellerUserId: args.sellerUserId ?: 10L,
            amount:       args.amount ?: new BigDecimal('30'),
            askingPrice:  args.askingPrice ?: new BigDecimal('40'),
            status:       args.status ?: 'PENDING',
            author:       args.author ?: 'USER',
            parentOfferId: args.parentOfferId
        )
    }

    def "cancelListing cancels every PENDING offer on the listing and notifies the buyer"() {
        given:
        def listing = new Listing(
            id: 100L, status: 'ACTIVE', sellerUserId: 10L,
            listingType: 'BUY_NOW', item: new Item(id: 7L, name: 'Wizard Hat')
        )
        def o1 = offer(id: 1L, buyerUserId: 20L, status: 'PENDING')
        def o2 = offer(id: 2L, buyerUserId: 21L, status: 'PENDING')
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { args -> args[0] }
        offerRepository.findByListingId(100L) >> [o1, o2]

        when:
        service.cancelListing(10L, 100L)

        then:
        o1.status == 'CANCELLED'
        o2.status == 'CANCELLED'
        1 * offerRepository.saveAll({ Iterable offers -> offers*.status.every { it == 'CANCELLED' } })
        1 * notificationService.push(20L, 'OFFER_REJECTED', _, _, 1L, _)
        1 * notificationService.push(21L, 'OFFER_REJECTED', _, _, 2L, _)
        listing.status == 'SOLD'
    }

    def "cancelListing also terminates a COUNTERED buyer offer — not just PENDING"() {
        given:
        // The seller cancels a listing mid-negotiation: a buyer offer
        // the seller had countered (status COUNTERED) plus the live
        // child SELLER counter (PENDING, same listingId). Before the
        // fix only the PENDING child was cancelled; the COUNTERED
        // parent was left dangling and kept showing as a live offer.
        def listing = new Listing(
            id: 100L, status: 'ACTIVE', sellerUserId: 10L,
            listingType: 'BUY_NOW', item: new Item(id: 7L, name: 'Wizard Hat')
        )
        def counteredOriginal = offer(id: 1L, buyerUserId: 20L,
            status: 'COUNTERED', author: 'USER')
        def sellerCounter = offer(id: 2L, buyerUserId: 20L,
            status: 'PENDING', author: 'SELLER', parentOfferId: 1L)
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { args -> args[0] }
        offerRepository.findByListingId(100L) >> [counteredOriginal, sellerCounter]

        when:
        service.cancelListing(10L, 100L)

        then:
        // BOTH rows of the negotiation thread reach the terminal state.
        counteredOriginal.status == 'CANCELLED'
        sellerCounter.status == 'CANCELLED'
        1 * offerRepository.saveAll({ Iterable offers -> offers*.status.every { it == 'CANCELLED' } })
        // The buyer is pinged exactly once even though two rows on the
        // listing belong to them (COUNTERED original + PENDING counter).
        1 * notificationService.push(20L, 'OFFER_REJECTED', _, _, _, _)
        listing.status == 'SOLD'
    }

    def "cancelListing leaves terminal offers (ACCEPTED/REJECTED/CANCELLED/EXPIRED) untouched"() {
        given:
        def listing = new Listing(
            id: 100L, status: 'ACTIVE', sellerUserId: 10L,
            listingType: 'BUY_NOW', item: new Item(id: 7L, name: 'x')
        )
        def live     = offer(id: 1L, buyerUserId: 20L, status: 'PENDING')
        def accepted = offer(id: 2L, buyerUserId: 21L, status: 'ACCEPTED')
        def rejected = offer(id: 3L, buyerUserId: 22L, status: 'REJECTED')
        def expired  = offer(id: 4L, buyerUserId: 23L, status: 'EXPIRED')
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { args -> args[0] }
        offerRepository.findByListingId(100L) >> [live, accepted, rejected, expired]

        when:
        service.cancelListing(10L, 100L)

        then:
        // Only the live PENDING row flips; terminal rows are not rewritten.
        live.status == 'CANCELLED'
        accepted.status == 'ACCEPTED'
        rejected.status == 'REJECTED'
        expired.status == 'EXPIRED'
        // saveAll receives only the one live row.
        1 * offerRepository.saveAll({ Iterable offers ->
            offers.collect { it.id } == [1L]
        })
        // Only the live offer's buyer is notified.
        1 * notificationService.push(20L, 'OFFER_REJECTED', _, _, 1L, _)
        0 * notificationService.push(21L, _, _, _, _, _)
        0 * notificationService.push(22L, _, _, _, _, _)
        0 * notificationService.push(23L, _, _, _, _, _)
    }

    def "cancelListing offer-cleanup failure does not abort the cancel"() {
        given:
        // A repository blow-up in the best-effort offer sweep must not
        // stop the listing from being returned to inventory.
        def listing = new Listing(
            id: 100L, status: 'ACTIVE', sellerUserId: 10L,
            listingType: 'BUY_NOW', item: new Item(id: 7L, name: 'x')
        )
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { args -> args[0] }
        offerRepository.findByListingId(100L) >> { throw new RuntimeException("db down") }

        when:
        service.cancelListing(10L, 100L)

        then:
        // The cancel still completes — item back in seller inventory.
        listing.status == 'SOLD'
        listing.buyerUserId == 10L
        noExceptionThrown()
    }

    def "cancelListing skips the OFFER_REJECTED push for an offer with a null buyer"() {
        given:
        def listing = new Listing(
            id: 100L, status: 'ACTIVE', sellerUserId: 10L,
            listingType: 'BUY_NOW', item: new Item(id: 7L, name: 'x')
        )
        def noBuyer = offer(id: 1L, status: 'PENDING')
        noBuyer.buyerUserId = null
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { args -> args[0] }
        offerRepository.findByListingId(100L) >> [noBuyer]

        when:
        service.cancelListing(10L, 100L)

        then:
        // Still flipped + saved, just no push (no buyer to notify).
        noBuyer.status == 'CANCELLED'
        1 * offerRepository.saveAll(_)
        0 * notificationService.push(*_)
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

    // ── input-validation ordering (regression) ────────────────────
    //
    // Every input check — price bounds, listingType/duration,
    // buyNowPrice, maxDiscount — must run BEFORE the source listing is
    // flipped to RELISTED. A previous version validated maxDiscount
    // only AFTER that flip, leaning on @Transactional rollback to undo
    // a mutation that should never have been attempted. These specs
    // pin the "reject before any write" contract.

    def "relist does NOT flip the source listing to RELISTED when maxDiscount is invalid"() {
        given:
        def oldListing = owned()
        listingRepository.findById(50L) >> Optional.of(oldListing)

        when:
        service.relist(10L, 'Alice', 50L, new BigDecimal('80'),
                       'BUY_NOW', null, null, null, new BigDecimal('1.5'))

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_DISCOUNT'
        // The source listing must be untouched — still in inventory.
        oldListing.status == 'SOLD'
        // And nothing was persisted: no RELISTED save, no fresh listing.
        0 * listingRepository.save(_)
    }

    def "relist rejects an invalid maxDiscount before it even loads the source listing"() {
        when:
        // findById is left unstubbed — if validation runs in the right
        // order the bad maxDiscount is caught before the lookup, so the
        // unknown-listing 404 path is never reached.
        service.relist(10L, 'Alice', 50L, new BigDecimal('80'),
                       'BUY_NOW', null, null, null, new BigDecimal('-0.01'))

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_DISCOUNT'
        0 * listingRepository.findById(_)
        0 * listingRepository.save(_)
    }

    def "relist rejects a bad price before mutating the source listing"() {
        given:
        def oldListing = owned()
        listingRepository.findById(50L) >> Optional.of(oldListing)

        when:
        service.relist(10L, 'Alice', 50L, badPrice)

        then:
        thrown(BadRequestException)
        oldListing.status == 'SOLD'
        0 * listingRepository.save(_)

        where:
        badPrice << [BigDecimal.ZERO, new BigDecimal('-1'), new BigDecimal('100001')]
    }

    def "relist rejects a bad auction duration before mutating the source listing"() {
        given:
        def oldListing = owned()
        listingRepository.findById(50L) >> Optional.of(oldListing)

        when:
        service.relist(10L, 'Alice', 50L, new BigDecimal('80'), 'AUCTION', badHours)

        then:
        thrown(BadRequestException)
        oldListing.status == 'SOLD'
        0 * listingRepository.save(_)

        where:
        badHours << [null, 0L, 169L]
    }

    // ── auction buyNowPrice validation ────────────────────────────

    def "relist accepts an AUCTION with a buyNowPrice above the starting bid"() {
        given:
        listingRepository.findById(50L) >> Optional.of(owned())
        Listing saved = null
        listingRepository.save(_) >> { args -> def l = args[0]; if (l.id == null) saved = l; l.id = l.id ?: 100L; l }

        when:
        def fresh = service.relist(10L, 'Alice', 50L, new BigDecimal('80'),
                                   'AUCTION', 24L, null, new BigDecimal('150'))

        then:
        fresh.listingType == 'AUCTION'
        fresh.price == new BigDecimal('80')        // starting bid
        fresh.buyNowPrice == new BigDecimal('150') // buy-now ceiling
        saved?.buyNowPrice == new BigDecimal('150')
    }

    def "relist rejects an AUCTION whose buyNowPrice does not exceed the starting bid"() {
        given:
        def oldListing = owned()
        listingRepository.findById(50L) >> Optional.of(oldListing)

        when:
        // buyNowPrice <= startingPrice would let the very first bid (placed
        // at listing.price) already meet/exceed Buy-Now — BidService.buyNowAuction
        // gates on `currentBid >= buyNowPrice`, so this must be rejected at creation.
        service.relist(10L, 'Alice', 50L, new BigDecimal('80'),
                       'AUCTION', 24L, null, badBuyNow)

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_BUY_NOW'
        oldListing.status == 'SOLD'

        where:
        badBuyNow << [new BigDecimal('80'), new BigDecimal('79.99'), new BigDecimal('10')]
    }

    def "relist rejects a buyNowPrice above the \$100k ceiling"() {
        given:
        listingRepository.findById(50L) >> Optional.of(owned())

        when:
        service.relist(10L, 'Alice', 50L, new BigDecimal('80'),
                       'AUCTION', 24L, null, new BigDecimal('100001'))

        then:
        def e = thrown(BadRequestException)
        e.code == 'BUY_NOW_TOO_HIGH'
    }

    def "relist rejects buyNowPrice on a BUY_NOW listing (auction-only concept)"() {
        given:
        listingRepository.findById(50L) >> Optional.of(owned())

        when:
        service.relist(10L, 'Alice', 50L, new BigDecimal('80'),
                       'BUY_NOW', null, null, new BigDecimal('150'))

        then:
        def e = thrown(BadRequestException)
        e.code == 'BUY_NOW_ON_BUY_NOW'
    }

    def "relist leaves buyNowPrice null on a BUY_NOW listing when none is supplied"() {
        given:
        listingRepository.findById(50L) >> Optional.of(owned())
        Listing saved = null
        listingRepository.save(_) >> { args -> def l = args[0]; if (l.id == null) saved = l; l.id = l.id ?: 100L; l }

        when:
        def fresh = service.relist(10L, 'Alice', 50L, new BigDecimal('80'))

        then:
        fresh.listingType == 'BUY_NOW'
        fresh.buyNowPrice == null
    }

    // ── auction state consistency with BidService ─────────────────
    //
    // BidService.placeBid uses `listing.price` as the first-bid floor
    // and `listing.currentBid` (null until the first bid) to gate
    // subsequent bids. A freshly relisted auction must therefore have
    // currentBid == null and price == the seller's starting bid, or
    // the first bid's minimum would be wrong.

    def "relist creates an auction with currentBid null and no bidder so the first bid floors at price"() {
        given:
        listingRepository.findById(50L) >> Optional.of(owned())
        Listing saved = null
        listingRepository.save(_) >> { args -> def l = args[0]; if (l.id == null) saved = l; l.id = l.id ?: 100L; l }

        when:
        def fresh = service.relist(10L, 'Alice', 50L, new BigDecimal('80'), 'AUCTION', 12L)

        then:
        fresh.currentBid == null
        fresh.currentBidderId == null
        fresh.currentBidderName == null
        fresh.bidCount == 0
        fresh.price == new BigDecimal('80')
        // expiresAt is in the future so BidService's `now > expiresAt`
        // expiry guard does not immediately reject bids.
        fresh.expiresAt > System.currentTimeMillis()
    }

    def "relist sets auction expiresAt exactly durationHours into the future"() {
        given:
        listingRepository.findById(50L) >> Optional.of(owned())
        listingRepository.save(_) >> { args -> def l = args[0]; l.id = l.id ?: 100L; l }
        def before = System.currentTimeMillis()

        when:
        def fresh = service.relist(10L, 'Alice', 50L, new BigDecimal('80'), 'AUCTION', 1L)

        then:
        // 1h = 3_600_000 ms — allow a generous slack for slow CI.
        fresh.expiresAt >= before + (3_600_000L - 5_000L)
        fresh.expiresAt <= System.currentTimeMillis() + 3_600_000L + 5_000L
    }

    def "relist does not stamp expiresAt on a BUY_NOW listing"() {
        given:
        listingRepository.findById(50L) >> Optional.of(owned())
        listingRepository.save(_) >> { args -> def l = args[0]; l.id = l.id ?: 100L; l }

        when:
        // durationHours is ignored for BUY_NOW — it must not leak an expiry.
        def fresh = service.relist(10L, 'Alice', 50L, new BigDecimal('80'), 'BUY_NOW', 48L)

        then:
        fresh.expiresAt == null
    }

    // ── cancelAllActive ───────────────────────────────────────────

    def "cancelAllActive cancels every active BUY_NOW listing and reports the count"() {
        given:
        def l1 = new Listing(id: 101L, status: 'ACTIVE', sellerUserId: 10L,
                             listingType: 'BUY_NOW', item: new Item(id: 1L, name: 'a'))
        def l2 = new Listing(id: 102L, status: 'ACTIVE', sellerUserId: 10L,
                             listingType: 'BUY_NOW', item: new Item(id: 2L, name: 'b'))
        listingRepository.findActiveBySeller(10L) >> [l1, l2]
        listingRepository.findById(101L) >> Optional.of(l1)
        listingRepository.findById(102L) >> Optional.of(l2)
        listingRepository.save(_) >> { args -> args[0] }

        when:
        def result = service.cancelAllActive(10L)

        then:
        result.cancelled == 2
        result.skippedAuctions == 0
        result.failed == 0
        l1.status == 'SOLD'
        l2.status == 'SOLD'
    }

    def "cancelAllActive skips auctions with live bids unless includeAuctions is set"() {
        given:
        def buyNow  = new Listing(id: 101L, status: 'ACTIVE', sellerUserId: 10L,
                                  listingType: 'BUY_NOW', item: new Item(id: 1L, name: 'a'))
        def auction = new Listing(id: 102L, status: 'ACTIVE', sellerUserId: 10L,
                                  listingType: 'AUCTION', bidCount: 3,
                                  item: new Item(id: 2L, name: 'b'))
        listingRepository.findActiveBySeller(10L) >> [buyNow, auction]
        listingRepository.findById(101L) >> Optional.of(buyNow)
        listingRepository.save(_) >> { args -> args[0] }

        when:
        def result = service.cancelAllActive(10L)

        then:
        result.cancelled == 1
        result.skippedAuctions == 1
        result.failed == 0
        buyNow.status == 'SOLD'
        // The bid-bearing auction was left untouched.
        auction.status == 'ACTIVE'
    }

    def "cancelAllActive cancels a bid-less auction without skipping it"() {
        given:
        def auction = new Listing(id: 102L, status: 'ACTIVE', sellerUserId: 10L,
                                  listingType: 'AUCTION', bidCount: 0,
                                  item: new Item(id: 2L, name: 'b'))
        listingRepository.findActiveBySeller(10L) >> [auction]
        listingRepository.findById(102L) >> Optional.of(auction)
        listingRepository.save(_) >> { args -> args[0] }
        bidRepository.findByListing(102L) >> []

        when:
        def result = service.cancelAllActive(10L)

        then:
        result.cancelled == 1
        result.skippedAuctions == 0
        auction.status == 'SOLD'
    }

    def "cancelAllActive is ban-guarded"() {
        given:
        banGuard.assertNotBanned(10L) >> { throw new ForbiddenException("banned") }

        when:
        service.cancelAllActive(10L)

        then:
        thrown(ForbiddenException)
        0 * listingRepository.findActiveBySeller(_)
    }

    def "relist is ban-guarded before any work"() {
        given:
        banGuard.assertNotBanned(10L) >> { throw new ForbiddenException("banned") }

        when:
        service.relist(10L, 'Alice', 50L, new BigDecimal('80'))

        then:
        thrown(ForbiddenException)
        0 * listingRepository.findById(_)
        0 * listingRepository.save(_)
    }
}
