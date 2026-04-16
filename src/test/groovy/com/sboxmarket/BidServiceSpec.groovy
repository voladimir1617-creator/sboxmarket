package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.Bid
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.repository.BidRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.BidService
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.security.BanGuard
import spock.lang.Specification
import spock.lang.Subject

/**
 * Unit coverage for placeBid. The scheduled sweeper (sweepExpired/settle)
 * is integration-level territory and is better tested end-to-end when the
 * auction flow gets its own spec; here we focus on every guard rail for
 * the hot path every bid goes through.
 */
class BidServiceSpec extends Specification {

    ListingRepository     listingRepository     = Mock()
    BidRepository         bidRepository         = Mock()
    WalletRepository      walletRepository      = Mock()
    TransactionRepository transactionRepository = Mock()
    SteamUserRepository   steamUserRepository   = Mock()
    NotificationService   notificationService   = Mock()
    BanGuard              banGuard              = Mock()
    TextSanitizer         textSanitizer         = Mock() {
        cleanShort(_) >> { String s -> s }
    }

    @Subject
    BidService service = new BidService(
        listingRepository    : listingRepository,
        bidRepository        : bidRepository,
        walletRepository     : walletRepository,
        transactionRepository: transactionRepository,
        steamUserRepository  : steamUserRepository,
        notificationService  : notificationService,
        banGuard             : banGuard,
        textSanitizer        : textSanitizer
    )

    private Listing auctionListing(Map args = [:]) {
        new Listing(
            id:                args.id ?: 100L,
            item:              new Item(id: 1L, name: 'Wizard Hat'),
            price:             args.price ?: new BigDecimal("10"),
            sellerUserId:      args.seller ?: 99L,
            status:            args.status ?: 'ACTIVE',
            listingType:       args.type ?: 'AUCTION',
            expiresAt:         args.expiresAt ?: (System.currentTimeMillis() + 3600_000L),
            currentBid:        args.currentBid,
            currentBidderId:   args.currentBidderId,
            bidCount:          args.bidCount ?: 0
        )
    }

    // ── happy path ────────────────────────────────────────────────

    def "placeBid succeeds when amount meets the floor"() {
        given:
        def listing = auctionListing()
        listingRepository.findById(100L) >> Optional.of(listing)
        bidRepository.save(_) >> { Bid b -> b.id = 1L; b }
        listingRepository.save(_) >> { Listing l -> l }

        when:
        def bid = service.placeBid(10L, 'Alice', 100L, new BigDecimal("15"), null)

        then:
        1 * banGuard.assertNotBanned(10L)
        bid.amount == new BigDecimal("15")
        bid.kind == 'MANUAL'
        bid.status == 'WINNING'
        listing.currentBid == new BigDecimal("15")
        listing.currentBidderId == 10L
        listing.bidCount == 1
    }

    def "placeBid with maxAmount > amount flags kind AUTO"() {
        given:
        listingRepository.findById(_) >> Optional.of(auctionListing())
        bidRepository.save(_) >> { Bid b -> b }
        listingRepository.save(_) >> { Listing l -> l }

        when:
        def bid = service.placeBid(10L, 'Alice', 100L, new BigDecimal("15"), new BigDecimal("50"))

        then:
        bid.kind == 'AUTO'
        bid.maxAmount == new BigDecimal("50")
    }

    def "placeBid notifies the previous top bidder when they're outbid"() {
        given:
        def listing = auctionListing(currentBid: new BigDecimal("20"), currentBidderId: 7L)
        listingRepository.findById(_) >> Optional.of(listing)
        bidRepository.save(_) >> { Bid b -> b }
        listingRepository.save(_) >> { Listing l -> l }

        when:
        service.placeBid(10L, 'Alice', 100L, new BigDecimal("25"), null)

        then:
        1 * notificationService.push(7L, 'AUCTION_OUTBID', _, _, 100L)
    }

    def "placeBid does NOT notify when the same user raises their own bid"() {
        given:
        def listing = auctionListing(currentBid: new BigDecimal("20"), currentBidderId: 10L)
        listingRepository.findById(_) >> Optional.of(listing)
        bidRepository.save(_) >> { Bid b -> b }
        listingRepository.save(_) >> { Listing l -> l }

        when:
        service.placeBid(10L, 'Alice', 100L, new BigDecimal("25"), null)

        then:
        0 * notificationService.push(*_)
    }

    // ── guard rails ───────────────────────────────────────────────

    def "placeBid refuses zero/negative/null amounts"() {
        when:
        service.placeBid(10L, 'Alice', 100L, amount, null)

        then:
        thrown(BadRequestException)

        where:
        amount << [null, BigDecimal.ZERO, new BigDecimal("-1")]
    }

    def "placeBid 404s for unknown listing"() {
        given:
        listingRepository.findById(_) >> Optional.empty()

        when:
        service.placeBid(10L, 'Alice', 999L, new BigDecimal("10"), null)

        then:
        thrown(NotFoundException)
    }

    def "placeBid refuses non-ACTIVE listings"() {
        given:
        listingRepository.findById(_) >> Optional.of(auctionListing(status: 'SOLD'))

        when:
        service.placeBid(10L, 'Alice', 100L, new BigDecimal("15"), null)

        then:
        thrown(BadRequestException)
    }

    def "placeBid refuses BUY_NOW listings"() {
        given:
        listingRepository.findById(_) >> Optional.of(auctionListing(type: 'BUY_NOW'))

        when:
        service.placeBid(10L, 'Alice', 100L, new BigDecimal("15"), null)

        then:
        thrown(BadRequestException)
    }

    def "placeBid refuses expired auctions"() {
        given:
        listingRepository.findById(_) >> Optional.of(auctionListing(expiresAt: System.currentTimeMillis() - 1000L))

        when:
        service.placeBid(10L, 'Alice', 100L, new BigDecimal("15"), null)

        then:
        thrown(BadRequestException)
    }

    def "placeBid forbids seller bidding on their own auction"() {
        given:
        listingRepository.findById(_) >> Optional.of(auctionListing(seller: 10L))

        when:
        service.placeBid(10L, 'Alice', 100L, new BigDecimal("15"), null)

        then:
        thrown(ForbiddenException)
    }

    def "placeBid refuses bids below the floor (currentBid or starting price)"() {
        given:
        listingRepository.findById(_) >> Optional.of(auctionListing(currentBid: new BigDecimal("20"), currentBidderId: 7L))

        when:
        service.placeBid(10L, 'Alice', 100L, new BigDecimal("19"), null)

        then:
        thrown(BadRequestException)
    }

    def "placeBid refuses a tied bid that would silently displace the current top bidder (bug #30)"() {
        given:
        // Current high bid is $20. Without the increment guard, a new
        // bidder could POST amount=$20 and become the new top bidder
        // even though they aren't actually outbidding anyone.
        listingRepository.findById(_) >> Optional.of(
            auctionListing(currentBid: new BigDecimal("20"), currentBidderId: 7L)
        )

        when:
        service.placeBid(10L, 'Alice', 100L, new BigDecimal("20"), null)

        then:
        def ex = thrown(BadRequestException)
        ex.code == 'BID_TOO_LOW'
        ex.message.contains('increment')
        0 * bidRepository.save(_)
        0 * listingRepository.save(_)
    }

    def "placeBid refuses a bid that's the currentBid + a penny (below the 5c increment)"() {
        given:
        listingRepository.findById(_) >> Optional.of(
            auctionListing(currentBid: new BigDecimal("20.00"), currentBidderId: 7L)
        )

        when:
        service.placeBid(10L, 'Alice', 100L, new BigDecimal("20.01"), null)

        then:
        thrown(BadRequestException)
    }

    def "placeBid accepts exactly currentBid + 0.05 increment"() {
        given:
        listingRepository.findById(_) >> Optional.of(
            auctionListing(currentBid: new BigDecimal("20.00"), currentBidderId: 7L)
        )
        bidRepository.save(_) >> { Bid b -> b }
        listingRepository.save(_) >> { Listing l -> l }

        when:
        def result = service.placeBid(10L, 'Alice', 100L, new BigDecimal("20.05"), null)

        then:
        result != null
        result.amount == new BigDecimal("20.05")
    }

    // ── historyFor (redaction) ─────────────────────────────────────

    private Bid bid(Map args) {
        new Bid(
            id:           args.id,
            listingId:    args.listingId ?: 100L,
            bidderUserId: args.bidderUserId,
            bidderName:   args.bidderName ?: 'Real',
            amount:       args.amount ?: new BigDecimal("20"),
            maxAmount:    args.maxAmount,
            kind:         args.kind ?: 'MANUAL',
            status:       args.status ?: 'WINNING'
        )
    }

    def "historyFor returns empty list when no bids exist"() {
        given:
        bidRepository.findByListing(100L) >> []

        expect:
        service.historyFor(100L, 10L) == []
    }

    def "historyFor returns raw bids (no redaction) to the listing seller"() {
        given:
        def a = bid(id: 1L, bidderUserId: 10L, bidderName: 'Alice', maxAmount: new BigDecimal("50"))
        def b = bid(id: 2L, bidderUserId: 20L, bidderName: 'Bob')
        bidRepository.findByListing(100L) >> [a, b]
        listingRepository.findById(100L) >> Optional.of(auctionListing(seller: 99L))

        when:
        def out = service.historyFor(100L, 99L)

        then:
        out == [a, b]
        out[0].bidderName == 'Alice'
        out[0].bidderUserId == 10L
        out[0].maxAmount == new BigDecimal("50")
    }

    def "historyFor returns raw bids (no redaction) to any participating bidder"() {
        given:
        def a = bid(id: 1L, bidderUserId: 10L, bidderName: 'Alice')
        def b = bid(id: 2L, bidderUserId: 20L, bidderName: 'Bob')
        bidRepository.findByListing(100L) >> [a, b]
        listingRepository.findById(100L) >> Optional.of(auctionListing(seller: 99L))

        when:
        def out = service.historyFor(100L, 20L)  // Bob viewing

        then:
        out == [a, b]
        out[0].bidderName == 'Alice'
    }

    def "historyFor redacts bidder identities for anonymous viewers"() {
        given:
        def a = bid(id: 1L, bidderUserId: 10L, bidderName: 'Alice', maxAmount: new BigDecimal("50"))
        def b = bid(id: 2L, bidderUserId: 20L, bidderName: 'Bob')
        bidRepository.findByListing(100L) >> [a, b]
        listingRepository.findById(100L) >> Optional.of(auctionListing(seller: 99L))

        when:
        def out = service.historyFor(100L, null)

        then:
        out.size() == 2
        out*.bidderName == ['Bidder #1', 'Bidder #2']
        out*.bidderUserId == [null, null]
        // maxAmount is strategic — never leak it to third parties
        out*.maxAmount == [null, null]
        // Original entities untouched
        a.bidderName == 'Alice'
        a.maxAmount == new BigDecimal("50")
    }

    def "historyFor redacts bidder identities for a logged-in third party"() {
        given:
        bidRepository.findByListing(100L) >> [
            bid(id: 1L, bidderUserId: 10L, bidderName: 'Alice'),
            bid(id: 2L, bidderUserId: 20L, bidderName: 'Bob')
        ]
        listingRepository.findById(100L) >> Optional.of(auctionListing(seller: 99L))

        when:
        def out = service.historyFor(100L, 77L)  // unrelated user

        then:
        out*.bidderName == ['Bidder #1', 'Bidder #2']
        out*.bidderUserId == [null, null]
    }

    def "historyFor gives the same handle to repeat bids from the same bidder"() {
        given:
        bidRepository.findByListing(100L) >> [
            bid(id: 1L, bidderUserId: 10L, bidderName: 'Alice', amount: new BigDecimal("30")),
            bid(id: 2L, bidderUserId: 20L, bidderName: 'Bob',   amount: new BigDecimal("25")),
            bid(id: 3L, bidderUserId: 10L, bidderName: 'Alice', amount: new BigDecimal("20"))
        ]
        listingRepository.findById(100L) >> Optional.of(auctionListing(seller: 99L))

        when:
        def out = service.historyFor(100L, null)

        then:
        out*.bidderName == ['Bidder #1', 'Bidder #2', 'Bidder #1']
    }

    def "historyFor redacts even when the listing row has been deleted"() {
        given:
        bidRepository.findByListing(100L) >> [bid(id: 1L, bidderUserId: 10L, bidderName: 'Alice')]
        listingRepository.findById(100L) >> Optional.empty()

        when:
        def out = service.historyFor(100L, 99L)

        then:
        out.size() == 1
        out[0].bidderName == 'Bidder #1'
        out[0].bidderUserId == null
    }
}
