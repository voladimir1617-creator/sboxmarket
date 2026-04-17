package com.sboxmarket

import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.service.BuyOrderService
import com.sboxmarket.service.ListingService
import spock.lang.Specification
import spock.lang.Subject

/**
 * Covers the listing-fetch + filter + sort pipeline, the hidden-listing
 * filter (away mode), buy/cancel side-effects on item floor price, and
 * the market-stats rollup. Buy-order auto-match is mocked — its own
 * unit tests are in BuyOrderServiceSpec.
 */
class ListingServiceSpec extends Specification {

    ListingRepository listingRepository = Mock()
    ItemRepository    itemRepository    = Mock()
    BuyOrderService   buyOrderService   = Mock()
    com.sboxmarket.repository.ListingReportRepository listingReportRepository = Mock()

    @Subject
    ListingService service = new ListingService(
        listingRepository:       listingRepository,
        itemRepository:          itemRepository,
        buyOrderService:         buyOrderService,
        listingReportRepository: listingReportRepository
    )

    private Item itemFor(long id = 1L, String name = 'Wizard Hat', String rarity = 'Limited', int supply = 100) {
        new Item(id: id, name: name, category: 'Hats', rarity: rarity, supply: supply, lowestPrice: new BigDecimal("10"))
    }

    private Listing listingFor(Map args = [:]) {
        new Listing(
            id:       args.id ?: 100L,
            item:     args.item ?: itemFor(),
            price:    args.price ?: new BigDecimal("10"),
            status:   args.status ?: 'ACTIVE',
            hidden:   args.hidden ?: false,
            listedAt: args.listedAt ?: 1000L
        )
    }

    // ── getActiveListings: filters are pushed into findActivePublic ──

    def "getActiveListings forwards search as the q param"() {
        given:
        listingRepository.findActivePublic('hat', '', '', null, null) >> [listingFor()]

        when:
        def result = service.getActiveListings(null, null, null, null, null, 'hat')

        then:
        result.size() == 1
    }

    def "getActiveListings forwards category when != All"() {
        given:
        listingRepository.findActivePublic('', 'Hats', '', null, null) >> [listingFor()]

        when:
        def result = service.getActiveListings(null, 'Hats', null, null, null, null)

        then:
        result.size() == 1
    }

    def "getActiveListings passes empty sentinels when no filters are set"() {
        given:
        listingRepository.findActivePublic('', '', '', null, null) >> [listingFor()]

        when:
        def result = service.getActiveListings(null, 'All', null, null, null, null)

        then:
        result.size() == 1
    }

    def "getActiveListings forwards rarity when != All"() {
        given:
        listingRepository.findActivePublic('', '', 'Limited', null, null) >> [
            listingFor(id: 1L, item: itemFor(1L, 'A', 'Limited')),
        ]

        when:
        def result = service.getActiveListings(null, null, 'Limited', null, null, null)

        then:
        result.size() == 1
        result[0].id == 1L
    }

    def "getActiveListings forwards minPrice and maxPrice into the query"() {
        given:
        def min = new BigDecimal("10")
        def max = new BigDecimal("50")
        listingRepository.findActivePublic('', '', '', min, max) >> [
            listingFor(id: 2L, price: new BigDecimal("15")),
        ]

        when:
        def result = service.getActiveListings(null, null, null, min, max, null)

        then:
        result.size() == 1
        result[0].id == 2L
    }

    // ── sort modes (applied in-memory after the indexed fetch) ────

    def "sort=price_desc flips the default ASC order"() {
        given:
        listingRepository.findActivePublic('', '', '', null, null) >> [
            listingFor(id: 1L, price: new BigDecimal("10")),
            listingFor(id: 2L, price: new BigDecimal("50")),
        ]

        when:
        def result = service.getActiveListings('price_desc', null, null, null, null, null)

        then:
        result*.id == [2L, 1L]
    }

    def "sort=newest orders by listedAt desc"() {
        given:
        listingRepository.findActivePublic('', '', '', null, null) >> [
            listingFor(id: 1L, listedAt: 1000L),
            listingFor(id: 2L, listedAt: 5000L),
        ]

        when:
        def result = service.getActiveListings('newest', null, null, null, null, null)

        then:
        result*.id == [2L, 1L]
    }

    def "sort=rarity orders by item.supply ascending (lowest supply first)"() {
        given:
        listingRepository.findActivePublic('', '', '', null, null) >> [
            listingFor(id: 1L, item: itemFor(1L, 'Common', 'Standard', 1000)),
            listingFor(id: 2L, item: itemFor(2L, 'Rare',   'Limited',    10)),
        ]

        when:
        def result = service.getActiveListings('rarity', null, null, null, null, null)

        then:
        result*.id == [2L, 1L]
    }

    // ── setAwayMode ───────────────────────────────────────────────

    def "setAwayMode flips hidden on every active listing for the seller"() {
        given:
        def a = listingFor(id: 1L, hidden: false)
        def b = listingFor(id: 2L, hidden: false)
        listingRepository.findActiveBySeller(10L) >> [a, b]

        when:
        def count = service.setAwayMode(10L, true)

        then:
        count == 2
        a.hidden == true
        b.hidden == true
        1 * listingRepository.saveAll({ List<Listing> list -> list.size() == 2 })
    }

    // buyListing + cancelListing removed — both were dead code superseded
    // by PurchaseService.buy and SellService.cancelListing respectively.

    // ── createListing ─────────────────────────────────────────────

    def "createListing calls through to buyOrderService.tryMatch"() {
        given:
        def listing = listingFor()
        listingRepository.save(_) >> { args -> args[0] }
        listingRepository.findCheapestForItem(_) >> [listing]
        itemRepository.findById(_) >> Optional.of(listing.item)
        itemRepository.save(_) >> { args -> args[0] }

        when:
        service.createListing(listing)

        then:
        1 * buyOrderService.tryMatch(listing)
    }

    def "createListing swallows a failing tryMatch so the save still lands"() {
        given:
        def listing = listingFor()
        listingRepository.save(_) >> { args -> args[0] }
        listingRepository.findCheapestForItem(_) >> [listing]
        itemRepository.findById(_) >> Optional.of(listing.item)
        itemRepository.save(_) >> { args -> args[0] }
        buyOrderService.tryMatch(_) >> { throw new RuntimeException('boom') }

        when:
        def saved = service.createListing(listing)

        then:
        saved != null
        noExceptionThrown()
    }

    // ── getMarketStats ────────────────────────────────────────────

    def "getMarketStats returns rolled-up volume, active count and floor"() {
        given:
        listingRepository.sumVolumeAfter(_) >> new BigDecimal("1234.5")
        listingRepository.countActive() >> 42L
        listingRepository.findMinActivePrice() >> new BigDecimal("5")

        when:
        def stats = service.getMarketStats()

        then:
        stats.volume24h == new BigDecimal("1234.50")
        stats.activeListings == 42L
        stats.floorPrice == new BigDecimal("5.00")
    }

    def "getMarketStats returns zeroes when nothing is listed"() {
        given:
        listingRepository.sumVolumeAfter(_) >> null
        listingRepository.countActive() >> 0L
        listingRepository.findMinActivePrice() >> null

        when:
        def stats = service.getMarketStats()

        then:
        stats.volume24h == new BigDecimal("0.00")
        stats.activeListings == 0L
        stats.floorPrice == new BigDecimal("0.00")
    }

    // ── bulkAdjustPrices ──────────────────────────────────────────

    def "bulkAdjustPrices applies -5% to every active non-auction listing"() {
        given:
        def l1 = listingFor(id: 1L, price: new BigDecimal("10.00"))
        def l2 = listingFor(id: 2L, price: new BigDecimal("20.00"))
        listingRepository.findActiveBySeller(99L) >> [l1, l2]
        itemRepository.findById(_) >> Optional.empty()  // skip floor recompute

        when:
        def result = service.bulkAdjustPrices(99L, new BigDecimal("-5"))

        then:
        result.touched == 2
        result.skipped == 0
        l1.price == new BigDecimal("9.50")
        l2.price == new BigDecimal("19.00")
        1 * listingRepository.saveAll(_)
    }

    def "bulkAdjustPrices skips AUCTION listings"() {
        given:
        def buyNow   = listingFor(id: 1L, price: new BigDecimal("10"))
        def auction  = new Listing(id: 2L, item: itemFor(2L), price: new BigDecimal("10"),
            status: 'ACTIVE', hidden: false, listingType: 'AUCTION')
        listingRepository.findActiveBySeller(99L) >> [buyNow, auction]
        itemRepository.findById(_) >> Optional.empty()

        when:
        def result = service.bulkAdjustPrices(99L, new BigDecimal("10"))

        then:
        result.touched == 1
        result.skipped == 1
        // Auction price untouched, buy-now bumped +10%
        auction.price == new BigDecimal("10")
        buyNow.price  == new BigDecimal("11.00")
    }

    def 'bulkAdjustPrices clamps to $0.01 floor and $100k ceiling'() {
        given:
        def cheap = listingFor(id: 1L, price: new BigDecimal("0.10"))
        def costly = listingFor(id: 2L, price: new BigDecimal("90000"))
        listingRepository.findActiveBySeller(99L) >> [cheap, costly]
        itemRepository.findById(_) >> Optional.empty()

        when:
        service.bulkAdjustPrices(99L, new BigDecimal("50"))  // +50%

        then:
        // cheap: 0.10 * 1.5 = 0.15 (within bounds)
        cheap.price == new BigDecimal("0.15")
        // costly: 90000 * 1.5 = 135000, clamped to 100000
        costly.price == new BigDecimal("100000")
    }

    // ── reportListing ─────────────────────────────────────────────

    def "reportListing increments report_count and saves a ListingReport row"() {
        given:
        def l = listingFor(id: 1L, price: new BigDecimal("10"))
        l.sellerUserId = 99L
        l.reportCount = 0
        listingRepository.findById(1L) >> Optional.of(l)
        listingRepository.save(_) >> { args -> args[0] }
        listingReportRepository.findByListingIdAndReporterUserId(1L, 42L) >> Optional.empty()
        listingReportRepository.countByReporterUserIdAndCreatedAtGreaterThan(42L, _) >> 0L

        when:
        def res = service.reportListing(1L, 42L, 'Suspicious pricing', 'looks fake')

        then:
        res.reportCount == 1
        l.reportCount == 1
        l.lastReportedAt != null
        1 * listingReportRepository.save({ r -> r.listingId == 1L && r.reporterUserId == 42L && r.reason == 'Suspicious pricing' })
    }

    def "reportListing refuses self-reports"() {
        given:
        def l = listingFor(id: 1L); l.sellerUserId = 42L
        listingRepository.findById(1L) >> Optional.of(l)

        when:
        service.reportListing(1L, 42L, 'Other', null)

        then:
        thrown(com.sboxmarket.exception.BadRequestException)
        0 * listingReportRepository.save(_)
    }

    def "reportListing refuses duplicate reports from the same user"() {
        given:
        def l = listingFor(id: 1L); l.sellerUserId = 99L
        listingRepository.findById(1L) >> Optional.of(l)
        listingReportRepository.findByListingIdAndReporterUserId(1L, 42L) >> Optional.of(new com.sboxmarket.model.ListingReport())

        when:
        service.reportListing(1L, 42L, 'Other', null)

        then:
        thrown(com.sboxmarket.exception.BadRequestException)
        0 * listingReportRepository.save(_)
    }

    def "reportListing enforces the per-user per-hour rate limit"() {
        given:
        def l = listingFor(id: 1L); l.sellerUserId = 99L
        listingRepository.findById(1L) >> Optional.of(l)
        listingReportRepository.findByListingIdAndReporterUserId(1L, 42L) >> Optional.empty()
        listingReportRepository.countByReporterUserIdAndCreatedAtGreaterThan(42L, _) >> 25L  // over limit

        when:
        service.reportListing(1L, 42L, 'Other', null)

        then:
        thrown(com.sboxmarket.exception.BadRequestException)
        0 * listingReportRepository.save(_)
    }

    def "reportListing 404s for unknown listing id"() {
        given:
        listingRepository.findById(99L) >> Optional.empty()

        when:
        service.reportListing(99L, 42L, 'Other', null)

        then:
        thrown(com.sboxmarket.exception.NotFoundException)
    }

    def "reportListing falls back to Other when the reason isn't whitelisted"() {
        given:
        def l = listingFor(id: 1L); l.sellerUserId = 99L; l.reportCount = 0
        listingRepository.findById(1L) >> Optional.of(l)
        listingRepository.save(_) >> { args -> args[0] }
        listingReportRepository.findByListingIdAndReporterUserId(1L, 42L) >> Optional.empty()
        listingReportRepository.countByReporterUserIdAndCreatedAtGreaterThan(42L, _) >> 0L

        when:
        service.reportListing(1L, 42L, 'DROP TABLE listings', null)

        then:
        1 * listingReportRepository.save({ r -> r.reason == 'Other' })
    }

    // ── findSoldBySeller passthrough ──────────────────────────────

    def "findSoldBySeller forwards the pageable to the repo"() {
        given:
        def page = org.springframework.data.domain.PageRequest.of(0, 10)
        listingRepository.findSoldBySeller(42L, page) >> [listingFor(status: 'SOLD')]

        when:
        def rows = service.findSoldBySeller(42L, page)

        then:
        rows.size() == 1
    }
}
