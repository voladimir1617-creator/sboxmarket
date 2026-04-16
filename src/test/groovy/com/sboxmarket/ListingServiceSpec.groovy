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

    @Subject
    ListingService service = new ListingService(
        listingRepository: listingRepository,
        itemRepository:    itemRepository,
        buyOrderService:   buyOrderService
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

    // ── buyListing ────────────────────────────────────────────────

    def "buyListing marks listing SOLD and bumps item totalSold"() {
        given:
        def item = itemFor(1L, 'Wizard', 'Limited')
        item.totalSold = 3
        def listing = listingFor(item: item, status: 'ACTIVE')
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { args -> args[0] }
        listingRepository.findCheapestForItem(1L) >> []
        itemRepository.findById(1L) >> Optional.of(item)
        itemRepository.save(_) >> { args -> args[0] }

        when:
        def result = service.buyListing(100L)

        then:
        result.status == 'SOLD'
        result.soldAt != null
        item.totalSold == 4
    }

    def "buyListing refuses non-ACTIVE listings"() {
        given:
        listingRepository.findById(_) >> Optional.of(listingFor(status: 'SOLD'))

        when:
        service.buyListing(100L)

        then:
        thrown(IllegalStateException)
    }

    // ── cancelListing ─────────────────────────────────────────────

    def "cancelListing marks CANCELLED and refreshes item floor"() {
        given:
        def item = itemFor(1L)
        def listing = listingFor(item: item, status: 'ACTIVE')
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { args -> args[0] }
        listingRepository.findCheapestForItem(1L) >> []
        itemRepository.findById(1L) >> Optional.of(item)
        itemRepository.save(_) >> { args -> args[0] }

        when:
        service.cancelListing(100L)

        then:
        listing.status == 'CANCELLED'
        item.lowestPrice == BigDecimal.ZERO  // no more active listings
    }

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
}
