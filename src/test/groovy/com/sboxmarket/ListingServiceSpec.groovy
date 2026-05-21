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
            id:          args.id ?: 100L,
            item:        args.item ?: itemFor(),
            price:       args.price ?: new BigDecimal("10"),
            status:      args.status ?: 'ACTIVE',
            hidden:      args.hidden ?: false,
            listedAt:    args.listedAt ?: 1000L,
            listingType: args.listingType ?: 'BUY_NOW',
            expiresAt:   args.expiresAt
        )
    }

    // ── getActiveListings: filters are pushed into findActivePublic ──

    def "getActiveListings forwards search as the q param"() {
        given:
        listingRepository.findActivePublic('hat', '', '', '', null, null) >> [listingFor()]

        when:
        def result = service.getActiveListings(null, null, null, null, null, 'hat', null)

        then:
        result.size() == 1
    }

    def "getActiveListings forwards category when != All"() {
        given:
        listingRepository.findActivePublic('', 'Hats', '', '', null, null) >> [listingFor()]

        when:
        def result = service.getActiveListings(null, 'Hats', null, null, null, null, null)

        then:
        result.size() == 1
    }

    def "getActiveListings passes empty sentinels when no filters are set"() {
        given:
        listingRepository.findActivePublic('', '', '', '', null, null) >> [listingFor()]

        when:
        def result = service.getActiveListings(null, 'All', null, null, null, null, null)

        then:
        result.size() == 1
    }

    def "getActiveListings forwards rarity when != All"() {
        given:
        listingRepository.findActivePublic('', '', 'Limited', '', null, null) >> [
            listingFor(id: 1L, item: itemFor(1L, 'A', 'Limited')),
        ]

        when:
        def result = service.getActiveListings(null, null, 'Limited', null, null, null, null)

        then:
        result.size() == 1
        result[0].id == 1L
    }

    def "getActiveListings forwards listingType when set to a whitelisted value"() {
        given:
        listingRepository.findActivePublic('', '', '', 'AUCTION', null, null) >> [listingFor(id: 9L)]

        when:
        def result = service.getActiveListings(null, null, null, null, null, null, 'AUCTION')

        then:
        result.size() == 1
        result[0].id == 9L
    }

    def "getActiveListings collapses a junk listingType to the empty sentinel"() {
        given:
        // Anything outside the whitelist becomes '' so the WHERE clause
        // is effectively disabled — matches the guard behaviour of
        // category/rarity.
        listingRepository.findActivePublic('', '', '', '', null, null) >> [listingFor()]

        when:
        def result = service.getActiveListings(null, null, null, null, null, null, '<script>')

        then:
        result.size() == 1
    }

    def "getActiveListings forwards minPrice and maxPrice into the query"() {
        given:
        def min = new BigDecimal("10")
        def max = new BigDecimal("50")
        listingRepository.findActivePublic('', '', '', '', min, max) >> [
            listingFor(id: 2L, price: new BigDecimal("15")),
        ]

        when:
        def result = service.getActiveListings(null, null, null, min, max, null, null)

        then:
        result.size() == 1
        result[0].id == 2L
    }

    // ── sort modes (applied in-memory after the indexed fetch) ────

    def "sort=price_desc flips the default ASC order"() {
        given:
        listingRepository.findActivePublic('', '', '', '', null, null) >> [
            listingFor(id: 1L, price: new BigDecimal("10")),
            listingFor(id: 2L, price: new BigDecimal("50")),
        ]

        when:
        def result = service.getActiveListings('price_desc', null, null, null, null, null, null)

        then:
        result*.id == [2L, 1L]
    }

    def "sort=newest orders by listedAt desc"() {
        given:
        listingRepository.findActivePublic('', '', '', '', null, null) >> [
            listingFor(id: 1L, listedAt: 1000L),
            listingFor(id: 2L, listedAt: 5000L),
        ]

        when:
        def result = service.getActiveListings('newest', null, null, null, null, null, null)

        then:
        result*.id == [2L, 1L]
    }

    private Item itemWithSteamPrice(long id, BigDecimal steamPrice) {
        def it = itemFor(id, 'Item ' + id, 'Standard', 100)
        it.steamPrice = steamPrice
        it
    }

    def "sort=discount ranks deepest %-off-Steam first, 0% / no-steam rows drop to the bottom"() {
        given:
        // a: $4 listed, $10 steam → 60% off.
        // b: $9 listed, $10 steam → 10% off.
        // c: $5 listed, no steam reference → 0% (tie-breaker: lowest price wins among 0%).
        // d: $12 listed, $10 steam → price > steam, ratio 0, drops to bottom.
        def a = listingFor(id: 1L, item: itemWithSteamPrice(1L, new BigDecimal('10.00')), price: new BigDecimal('4.00'))
        def b = listingFor(id: 2L, item: itemWithSteamPrice(2L, new BigDecimal('10.00')), price: new BigDecimal('9.00'))
        def c = listingFor(id: 3L, item: itemFor(3L, 'No Steam', 'Standard', 100),        price: new BigDecimal('5.00'))
        def d = listingFor(id: 4L, item: itemWithSteamPrice(4L, new BigDecimal('10.00')), price: new BigDecimal('12.00'))
        listingRepository.findActivePublic('', '', '', '', null, null) >> [a, b, c, d]

        when:
        def result = service.getActiveListings('discount', null, null, null, null, null, null)

        then:
        // a (60%) > b (10%) > c,d (both 0%, tie-broken by cheaper price first).
        result*.id == [1L, 2L, 3L, 4L]
    }

    def "sort=discount treats an AUCTION as 0% off so a low-opener can't top the deals view"() {
        given:
        // An AUCTION with a $0.01 opening bid on a $10-steam item would,
        // if its starting price were treated as a real discount, read as
        // ~100% off and dominate. The auction's price is just the opening
        // bid — not a binding sale price — so it must rank as 0%.
        def auction = new Listing(id: 1L, item: itemWithSteamPrice(1L, new BigDecimal('10.00')),
            price: new BigDecimal('0.01'), status: 'ACTIVE', hidden: false, listingType: 'AUCTION')
        def buyNow  = listingFor(id: 2L, item: itemWithSteamPrice(2L, new BigDecimal('10.00')),
            price: new BigDecimal('6.00'))  // a real 40%-off BUY_NOW
        listingRepository.findActivePublic('', '', '', '', null, null) >> [auction, buyNow]

        when:
        def result = service.getActiveListings('discount', null, null, null, null, null, null)

        then:
        // The genuine BUY_NOW discount outranks the auction (0%).
        result*.id == [2L, 1L]
    }

    def "sort=ending_soon orders AUCTION listings by expiresAt asc, BUY_NOW rows last"() {
        given:
        // Two auctions (one ending sooner) plus a BUY_NOW. The BUY_NOW has
        // the lowest price so if ending_soon fell through to price ASC
        // (the JPQL default) it would come first — proving it really is
        // the expiresAt comparator that's running.
        def auctionSoon  = listingFor(id: 1L, price: new BigDecimal('50'),
            listingType: 'AUCTION', expiresAt: 2000L)
        def auctionLater = listingFor(id: 2L, price: new BigDecimal('40'),
            listingType: 'AUCTION', expiresAt: 9000L)
        def buyNow       = listingFor(id: 3L, price: new BigDecimal('5'))
        listingRepository.findActivePublic('', '', '', '', null, null) >> [auctionLater, buyNow, auctionSoon]

        when:
        def result = service.getActiveListings('ending_soon', null, null, null, null, null, null)

        then:
        result*.id == [1L, 2L, 3L]
    }

    def "sort=rarity orders by item.supply ascending (lowest supply first)"() {
        given:
        listingRepository.findActivePublic('', '', '', '', null, null) >> [
            listingFor(id: 1L, item: itemFor(1L, 'Common', 'Standard', 1000)),
            listingFor(id: 2L, item: itemFor(2L, 'Rare',   'Limited',    10)),
        ]

        when:
        def result = service.getActiveListings('rarity', null, null, null, null, null, null)

        then:
        result*.id == [2L, 1L]
    }

    def "sort=price_asc keeps the JPQL price-ASC order untouched"() {
        given:
        // The repo already returns price ASC. sort=price_asc must be a
        // no-op pass-through — proves the explicit case doesn't reorder.
        listingRepository.findActivePublic('', '', '', '', null, null) >> [
            listingFor(id: 1L, price: new BigDecimal("10")),
            listingFor(id: 2L, price: new BigDecimal("25")),
            listingFor(id: 3L, price: new BigDecimal("99")),
        ]

        when:
        def result = service.getActiveListings('price_asc', null, null, null, null, null, null)

        then:
        result*.id == [1L, 2L, 3L]
    }

    def "an unrecognised sort value falls through to the JPQL price-ASC order"() {
        given:
        // The controller whitelists sort before this point, but the
        // service switch must still degrade gracefully on the default
        // branch rather than throw.
        listingRepository.findActivePublic('', '', '', '', null, null) >> [
            listingFor(id: 1L, price: new BigDecimal("3")),
            listingFor(id: 2L, price: new BigDecimal("7")),
        ]

        when:
        def result = service.getActiveListings('totally_bogus', null, null, null, null, null, null)

        then:
        result*.id == [1L, 2L]
    }

    def "a null sort value falls through to the JPQL price-ASC order"() {
        given:
        listingRepository.findActivePublic('', '', '', '', null, null) >> [
            listingFor(id: 1L, price: new BigDecimal("3")),
            listingFor(id: 2L, price: new BigDecimal("7")),
        ]

        when:
        def result = service.getActiveListings(null, null, null, null, null, null, null)

        then:
        result*.id == [1L, 2L]
    }

    private Item itemWithCounters(long id, long totalSold, long viewCount) {
        def it = itemFor(id, 'Item ' + id, 'Standard', 100)
        it.totalSold = totalSold as int
        it.viewCount = viewCount
        it
    }

    def "sort=popularity orders by item.totalSold desc, zero-sales rows last"() {
        given:
        // c has the most sales, a is mid, b has none. b also has the
        // lowest price — if popularity fell through to price ASC it
        // would lead, proving the totalSold comparator really runs.
        def a = listingFor(id: 1L, item: itemWithCounters(1L, 5L, 0L),  price: new BigDecimal("50"))
        def b = listingFor(id: 2L, item: itemWithCounters(2L, 0L, 0L),  price: new BigDecimal("5"))
        def c = listingFor(id: 3L, item: itemWithCounters(3L, 99L, 0L), price: new BigDecimal("80"))
        listingRepository.findActivePublic('', '', '', '', null, null) >> [a, b, c]

        when:
        def result = service.getActiveListings('popularity', null, null, null, null, null, null)

        then:
        result*.id == [3L, 1L, 2L]
    }

    def "sort=popularity breaks ties by ascending price"() {
        given:
        // Two listings on equally-popular items — the cheaper one wins.
        def dear  = listingFor(id: 1L, item: itemWithCounters(1L, 10L, 0L), price: new BigDecimal("40"))
        def cheap = listingFor(id: 2L, item: itemWithCounters(2L, 10L, 0L), price: new BigDecimal("12"))
        listingRepository.findActivePublic('', '', '', '', null, null) >> [dear, cheap]

        when:
        def result = service.getActiveListings('popularity', null, null, null, null, null, null)

        then:
        result*.id == [2L, 1L]
    }

    def "sort=views orders by item.viewCount desc, zero-view rows last"() {
        given:
        // c is most-viewed, a mid, b unviewed. b is cheapest so a
        // price-ASC fall-through would surface it first.
        def a = listingFor(id: 1L, item: itemWithCounters(1L, 0L, 30L),  price: new BigDecimal("50"))
        def b = listingFor(id: 2L, item: itemWithCounters(2L, 0L, 0L),   price: new BigDecimal("5"))
        def c = listingFor(id: 3L, item: itemWithCounters(3L, 0L, 500L), price: new BigDecimal("80"))
        listingRepository.findActivePublic('', '', '', '', null, null) >> [a, b, c]

        when:
        def result = service.getActiveListings('views', null, null, null, null, null, null)

        then:
        result*.id == [3L, 1L, 2L]
    }

    def "sort=views breaks ties by ascending price"() {
        given:
        def dear  = listingFor(id: 1L, item: itemWithCounters(1L, 0L, 7L), price: new BigDecimal("40"))
        def cheap = listingFor(id: 2L, item: itemWithCounters(2L, 0L, 7L), price: new BigDecimal("12"))
        listingRepository.findActivePublic('', '', '', '', null, null) >> [dear, cheap]

        when:
        def result = service.getActiveListings('views', null, null, null, null, null, null)

        then:
        result*.id == [2L, 1L]
    }

    def "sort=price_desc breaks an equal-price pair without throwing"() {
        given:
        // Two listings at the exact same price — the comparator must be
        // a total order (no IllegalArgumentException from Collections.sort).
        listingRepository.findActivePublic('', '', '', '', null, null) >> [
            listingFor(id: 1L, price: new BigDecimal("10")),
            listingFor(id: 2L, price: new BigDecimal("10")),
        ]

        when:
        def result = service.getActiveListings('price_desc', null, null, null, null, null, null)

        then:
        result.size() == 2
        noExceptionThrown()
    }

    def "getActiveListings returns an empty list when the query matches nothing"() {
        given:
        listingRepository.findActivePublic('zzz', '', '', '', null, null) >> []

        when:
        def result = service.getActiveListings('discount', null, null, null, null, 'zzz', null)

        then:
        result == []
        noExceptionThrown()
    }

    def "getActiveListings forwards only minPrice when maxPrice is absent"() {
        given:
        def min = new BigDecimal("25")
        listingRepository.findActivePublic('', '', '', '', min, null) >> [listingFor(id: 3L)]

        when:
        def result = service.getActiveListings(null, null, null, min, null, null, null)

        then:
        result*.id == [3L]
    }

    def "getActiveListings forwards only maxPrice when minPrice is absent"() {
        given:
        def max = new BigDecimal("75")
        listingRepository.findActivePublic('', '', '', '', null, max) >> [listingFor(id: 4L)]

        when:
        def result = service.getActiveListings(null, null, null, null, max, null, null)

        then:
        result*.id == [4L]
    }

    def "getActiveListings collapses listingType 'All' to the empty sentinel"() {
        given:
        // 'All' is the frontend's "no filter" value — must not be passed
        // through as a literal listingType (no listing has type 'All').
        listingRepository.findActivePublic('', '', '', '', null, null) >> [listingFor()]

        when:
        def result = service.getActiveListings(null, null, null, null, null, null, 'All')

        then:
        result.size() == 1
    }

    def "getActiveListings forwards BUY_NOW as a whitelisted listingType"() {
        given:
        listingRepository.findActivePublic('', '', '', 'BUY_NOW', null, null) >> [listingFor(id: 6L)]

        when:
        def result = service.getActiveListings(null, null, null, null, null, null, 'BUY_NOW')

        then:
        result*.id == [6L]
    }

    def "getActiveListings collapses category 'All' to the empty sentinel"() {
        given:
        listingRepository.findActivePublic('', '', '', '', null, null) >> [listingFor()]

        when:
        def result = service.getActiveListings(null, 'All', null, null, null, null, null)

        then:
        result.size() == 1
    }

    def "getActiveListings collapses rarity 'All' to the empty sentinel"() {
        given:
        listingRepository.findActivePublic('', '', '', '', null, null) >> [listingFor()]

        when:
        def result = service.getActiveListings(null, null, 'All', null, null, null, null)

        then:
        result.size() == 1
    }

    def "getActiveListings collapses an empty-string search to the empty sentinel"() {
        given:
        // An empty (but non-null) search must not become a LIKE '%%' that
        // the service treats as a real filter token.
        listingRepository.findActivePublic('', '', '', '', null, null) >> [listingFor()]

        when:
        def result = service.getActiveListings(null, null, null, null, null, '', null)

        then:
        result.size() == 1
    }

    // ── findTopDeals / findNewestActive passthroughs ──────────────

    def "findTopDeals forwards a capped page request to the repo"() {
        given:
        org.springframework.data.domain.Pageable seen = null
        listingRepository.findTopDeals(_) >> { args -> seen = args[0]; [listingFor(id: 1L)] }

        when:
        def rows = service.findTopDeals(12)

        then:
        seen != null
        seen.pageSize == 12
        rows.size() == 1
    }

    def "findNewestActive truncates the repo result to the requested cap"() {
        given:
        listingRepository.findActiveOrderByNewest() >> [
            listingFor(id: 1L), listingFor(id: 2L), listingFor(id: 3L), listingFor(id: 4L),
        ]

        when:
        def rows = service.findNewestActive(2)

        then:
        rows.size() == 2
        rows*.id == [1L, 2L]
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

    def "setAwayMode recomputes Item.lowestPrice for every item it touched (batch 306 bug fix)"() {
        given:
        // Two listings on DIFFERENT items. Batch hide must re-run the
        // floor-price aggregate for each distinct item.id so hidden
        // listings drop out of the public floor.
        def itemA = itemFor(1L)
        def itemB = itemFor(2L)
        def a = listingFor(id: 10L, item: itemA, hidden: false)
        def b = listingFor(id: 11L, item: itemB, hidden: false)
        listingRepository.findActiveBySeller(10L) >> [a, b]
        itemRepository.findById(1L) >> Optional.of(itemA)
        itemRepository.findById(2L) >> Optional.of(itemB)
        listingRepository.minPriceForItem(1L) >> new BigDecimal("15")
        listingRepository.minPriceForItem(2L) >> new BigDecimal("20")

        when:
        service.setAwayMode(10L, true)

        then:
        // Both items triggered a floor recompute.
        1 * listingRepository.minPriceForItem(1L)
        1 * listingRepository.minPriceForItem(2L)
    }

    def "setAwayMode dedupes the floor-recompute when two listings share one item"() {
        given:
        def shared = itemFor(1L)
        def a = listingFor(id: 10L, item: shared, hidden: false)
        def b = listingFor(id: 11L, item: shared, hidden: false)
        listingRepository.findActiveBySeller(10L) >> [a, b]
        itemRepository.findById(1L) >> Optional.of(shared)
        listingRepository.minPriceForItem(1L) >> new BigDecimal("10")

        when:
        service.setAwayMode(10L, true)

        then:
        // One call, not two.
        1 * listingRepository.minPriceForItem(1L)
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

    // ── updateItemFloorPrice keeps item.isListed in sync (batch 237 / bug #104) ──

    def "createListing updates item.lowestPrice AND flips isListed=true when floor > 0"() {
        given:
        def item = itemFor()
        item.isListed = false  // start false to prove the sync sets it true
        item.lowestPrice = BigDecimal.ZERO
        def listing = listingFor(item: item)
        listingRepository.save(_) >> { args -> args[0] }
        // Repo returns a real floor — simulates at least one active listing.
        listingRepository.minPriceForItem(1L) >> new BigDecimal('7.50')
        itemRepository.findById(1L) >> Optional.of(item)
        itemRepository.save(_) >> { args -> args[0] }

        when:
        service.createListing(listing)

        then:
        item.lowestPrice == new BigDecimal('7.50')
        item.isListed == true
    }

    // ── findHottest fallback chain (batch 955) ────────────────────

    def "findHottest returns recent sales when the window has activity"() {
        given:
        def listing = listingFor()
        listingRepository.findTopSoldItemIds(_, _) >> [[1L, 5L] as Object[]]
        // Batch 1089 — rails now bulk-fetch the cheapest active listing
        // per item id in a single findActiveForItemIds round-trip.
        listingRepository.findActiveForItemIds([1L]) >> [listing]

        when:
        def rows = service.findHottest(8)

        then:
        rows.size() == 1
        rows[0].id == listing.id
    }

    def "findHottest falls back to most-watched when no recent sales"() {
        given:
        def item = itemFor(9L)
        def listing = listingFor(id: 77L, item: item)
        // No recent sales:
        listingRepository.findTopSoldItemIds(_, _) >> []
        // Most-watched has one item — exercised via service.findMostWatched()
        // which is called internally. Mock the repo it depends on:
        def watchRepo = Mock(com.sboxmarket.repository.WatchlistItemRepository)
        service.watchlistItemRepository = watchRepo
        watchRepo.findTopWatchedItemIds(_) >> [[9L, 3L] as Object[]]
        listingRepository.findActiveForItemIds([9L]) >> [listing]

        when:
        def rows = service.findHottest(8)

        then:
        rows.size() == 1
        rows[0].id == 77L
    }

    def "findHottest falls back to most-viewed when neither sales nor watches exist"() {
        given:
        def item = itemFor(12L)
        def listing = listingFor(id: 88L, item: item)
        listingRepository.findTopSoldItemIds(_, _) >> []
        def watchRepo = Mock(com.sboxmarket.repository.WatchlistItemRepository)
        service.watchlistItemRepository = watchRepo
        watchRepo.findTopWatchedItemIds(_) >> []
        // Most-viewed has one item:
        itemRepository.findTopViewedItemIds(_) >> [[12L] as Object[]]
        listingRepository.findActiveForItemIds([12L]) >> [listing]

        when:
        def rows = service.findHottest(8)

        then:
        rows.size() == 1
        rows[0].id == 88L
    }

    def "findHottest de-duplicates across the fallback chain"() {
        // Same item surfaced by both sales AND watches — must appear only
        // once in the projected output.
        given:
        def item = itemFor(5L)
        def listing = listingFor(id: 101L, item: item)
        listingRepository.findTopSoldItemIds(_, _) >> [[5L, 2L] as Object[]]
        def watchRepo = Mock(com.sboxmarket.repository.WatchlistItemRepository)
        service.watchlistItemRepository = watchRepo
        watchRepo.findTopWatchedItemIds(_) >> [[5L, 4L] as Object[]]
        listingRepository.findActiveForItemIds([5L]) >> [listing]

        when:
        def rows = service.findHottest(8)

        then:
        rows.size() == 1
    }

    // ── homepage rails: bulk cheapest-per-item projection (batch 1089) ──

    def "findMostWatched projects the cheapest active listing per item in one bulk query"() {
        given:
        def cheap = listingFor(id: 1L, item: itemFor(7L), price: new BigDecimal('5'))
        def dear  = listingFor(id: 2L, item: itemFor(7L), price: new BigDecimal('9'))
        def other = listingFor(id: 3L, item: itemFor(8L), price: new BigDecimal('4'))
        def watchRepo = Mock(com.sboxmarket.repository.WatchlistItemRepository)
        service.watchlistItemRepository = watchRepo
        watchRepo.findTopWatchedItemIds(_) >> [[7L, 9L] as Object[], [8L, 3L] as Object[]]
        // Repo returns the flat (item.id, price)-ordered list; service
        // picks the first row per item id.
        1 * listingRepository.findActiveForItemIds([7L, 8L]) >> [cheap, dear, other]

        when:
        def rows = service.findMostWatched(8)

        then:
        // One row per item, candidate order preserved, cheapest picked.
        rows*.id == [1L, 3L]
    }

    def "findMostWatched skips items with no active listing"() {
        given:
        def listing = listingFor(id: 1L, item: itemFor(7L))
        def watchRepo = Mock(com.sboxmarket.repository.WatchlistItemRepository)
        service.watchlistItemRepository = watchRepo
        watchRepo.findTopWatchedItemIds(_) >> [[7L, 9L] as Object[], [8L, 3L] as Object[]]
        // Item 8 has no active listing — only item 7 comes back.
        listingRepository.findActiveForItemIds([7L, 8L]) >> [listing]

        when:
        def rows = service.findMostWatched(8)

        then:
        rows*.id == [1L]
    }

    def "findMostViewed projects via the bulk query too"() {
        given:
        def listing = listingFor(id: 5L, item: itemFor(3L))
        itemRepository.findTopViewedItemIds(_) >> [[3L] as Object[]]
        listingRepository.findActiveForItemIds([3L]) >> [listing]

        when:
        def rows = service.findMostViewed(8)

        then:
        rows*.id == [5L]
    }

    def "a freshly-constructed Item defaults isListed=false (bug #105 regression)"() {
        // Previously Item.isListed defaulted to true, which meant catalogue
        // rows imported from the SCMM sync were flagged listed even though
        // no one had ever created a user listing against them. Fixed by the
        // model default flip + V54 reconciliation migration.
        expect:
        !(new Item().isListed)
    }

    def "updateItemFloorPrice flips isListed=false when the last active listing is gone"() {
        given:
        def item = itemFor()
        item.isListed = true  // start true — the last active listing just sold
        def listing = listingFor(item: item)
        listingRepository.save(_) >> { args -> args[0] }
        // Repo returns null floor — no active listings remain for this item.
        listingRepository.minPriceForItem(1L) >> null
        itemRepository.findById(1L) >> Optional.of(item)
        itemRepository.save(_) >> { args -> args[0] }

        when:
        service.createListing(listing)

        then:
        item.lowestPrice == BigDecimal.ZERO
        item.isListed == false
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

    // Batch 561 — a second call within the TTL window returns the cached
    // snapshot without re-hitting the repo. Prevents regressions that
    // silently drop the cache (e.g. a refactor that nukes the volatile
    // fields) and restores the 4-round-trip-per-call tax on /stats.
    def "getMarketStats caches for 30s — second call inside window skips the DB"() {
        when: "two back-to-back calls"
        def a = service.getMarketStats()
        def b = service.getMarketStats()

        then: "each repo-backed counter fired exactly once despite two gets"
        1 * listingRepository.countActive() >> 42L
        // Batch 1048 — sumVolumeAfter is now called twice per snapshot
        // build (24h window + 7d window), but STILL only on the first
        // call of the pair; the second getMarketStats within the 30s
        // TTL returns the cached snapshot. 2 * invocations, NOT 4.
        2 * listingRepository.sumVolumeAfter(_) >> new BigDecimal("100.00")
        1 * listingRepository.findMinActivePrice() >> new BigDecimal("1.00")
        1 * listingRepository.countActiveAuctions(_) >> 0L

        and: "both return the same snapshot"
        a.activeListings == 42L
        b.activeListings == 42L
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

    def "bulkAdjustPrices fans out PRICE_DROPPED to cart-holders of every reduced listing (batch 539)"() {
        given:
        def l1 = listingFor(id: 1L, price: new BigDecimal('10.00'))
        def l2 = listingFor(id: 2L, price: new BigDecimal('20.00'))
        def cartRepo = Mock(com.sboxmarket.repository.CartItemRepository)
        def notifier = Mock(com.sboxmarket.service.NotificationService)
        service.cartItemRepository  = cartRepo
        service.notificationService = notifier
        listingRepository.findActiveBySeller(99L) >> [l1, l2]
        itemRepository.findById(_) >> Optional.empty()
        // listing#1 is in uid=42's cart; listing#2 has two cart-holders
        cartRepo.findOtherUsersWithListing(1L, 99L) >> [42L]
        cartRepo.findOtherUsersWithListing(2L, 99L) >> [42L, 77L]

        when:
        service.bulkAdjustPrices(99L, new BigDecimal('-10'))

        then:
        // Three total PRICE_DROPPED pushes — one per (listing, holder) pair.
        1 * notifier.push(42L, 'PRICE_DROPPED', _, _, 1L, _)
        1 * notifier.push(42L, 'PRICE_DROPPED', _, _, 2L, _)
        1 * notifier.push(77L, 'PRICE_DROPPED', _, _, 2L, _)
    }

    def "bulkAdjustPrices does NOT fire PRICE_DROPPED on a price increase (batch 539)"() {
        given:
        def l1 = listingFor(id: 1L, price: new BigDecimal('10.00'))
        def cartRepo = Mock(com.sboxmarket.repository.CartItemRepository)
        def notifier = Mock(com.sboxmarket.service.NotificationService)
        service.cartItemRepository  = cartRepo
        service.notificationService = notifier
        listingRepository.findActiveBySeller(99L) >> [l1]
        itemRepository.findById(_) >> Optional.empty()

        when:
        service.bulkAdjustPrices(99L, new BigDecimal('10'))  // +10%

        then:
        0 * notifier.push(_, 'PRICE_DROPPED', _, _, _, _)
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

    // ── findRecentSales (platform-wide Just Sold feed) ────────────

    def "findRecentSales caps the limit at 30 and projects to compact maps"() {
        given:
        def l = listingFor(id: 50L, price: new BigDecimal('12.34'))
        l.soldAt = 1_700_000_000_000L
        l.sellerName = 'Alice'
        // Service over-fetches at 2x the requested cap so banned-seller
        // eviction has headroom; the public cap of 30 still applies to
        // the returned size, but the page request widens to 60.
        org.springframework.data.domain.Pageable seen = null
        listingRepository.findRecentlySold(_) >> { args -> seen = args[0]; [l] }

        when:
        def rows = service.findRecentSales(100)  // above cap

        then:
        seen != null
        seen.pageSize == 60
        rows.size() == 1
        rows[0].listingId == 50L
        rows[0].price == new BigDecimal('12.34')
        rows[0].soldAt == 1_700_000_000_000L
        rows[0].sellerName == 'Alice'
    }

    def "findRecentSales floors the limit at 1"() {
        given:
        org.springframework.data.domain.Pageable seen = null
        listingRepository.findRecentlySold(_) >> { args -> seen = args[0]; [] }

        when:
        service.findRecentSales(0)  // below floor

        then:
        seen != null
        // Floor of 1 still applies; over-fetch multiplier doubles to 2.
        seen.pageSize == 2
    }

    def "findRecentSales returns an empty list when no sales exist"() {
        given:
        listingRepository.findRecentlySold(_ as org.springframework.data.domain.Pageable) >> []

        when:
        def rows = service.findRecentSales(12)

        then:
        rows == []
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

    // ── Vacation-mode scheduling (V33 / batch 265) ──────────────────

    def "setAwayMode with a future 'until' stores the timestamp on the user row"() {
        given:
        def steamUserRepo = Mock(com.sboxmarket.repository.SteamUserRepository)
        service.steamUserRepository = steamUserRepo
        def user = new com.sboxmarket.model.SteamUser(id: 5L)
        steamUserRepo.findById(5L) >> Optional.of(user)
        listingRepository.findActiveBySeller(5L) >> [listingFor(id: 1L), listingFor(id: 2L)]
        def future = System.currentTimeMillis() + (3L * 24L * 60L * 60L * 1000L)

        when:
        def n = service.setAwayMode(5L, true, future)

        then:
        1 * steamUserRepo.save({ it.awayModeUntil == future })
        1 * listingRepository.saveAll(_)
        user.awayModeUntil == future
        n == 2
    }

    def "setAwayMode rejects a past 'until' so a clock-skewed client can't auto-expire instantly"() {
        given:
        def steamUserRepo = Mock(com.sboxmarket.repository.SteamUserRepository)
        service.steamUserRepository = steamUserRepo
        steamUserRepo.findById(5L) >> Optional.of(new com.sboxmarket.model.SteamUser(id: 5L))
        def past = System.currentTimeMillis() - 60_000L

        when:
        service.setAwayMode(5L, true, past)

        then:
        thrown(com.sboxmarket.exception.BadRequestException)
        0 * listingRepository.saveAll(_)
    }

    def "setAwayMode rejects a 'until' more than 90 days out"() {
        given:
        def steamUserRepo = Mock(com.sboxmarket.repository.SteamUserRepository)
        service.steamUserRepository = steamUserRepo
        steamUserRepo.findById(5L) >> Optional.of(new com.sboxmarket.model.SteamUser(id: 5L))
        def tooFar = System.currentTimeMillis() + (95L * 24L * 60L * 60L * 1000L)

        when:
        service.setAwayMode(5L, true, tooFar)

        then:
        thrown(com.sboxmarket.exception.BadRequestException)
    }

    def "setAwayMode hidden=false clears any pending awayModeUntil"() {
        given:
        def steamUserRepo = Mock(com.sboxmarket.repository.SteamUserRepository)
        service.steamUserRepository = steamUserRepo
        def user = new com.sboxmarket.model.SteamUser(id: 5L,
            awayModeUntil: System.currentTimeMillis() + 86400_000L)
        steamUserRepo.findById(5L) >> Optional.of(user)
        listingRepository.findActiveBySeller(5L) >> []

        when:
        service.setAwayMode(5L, false, null)

        then:
        1 * steamUserRepo.save({ it.awayModeUntil == null })
        user.awayModeUntil == null
    }

    def "sweepExpiredAwayMode un-hides every active listing and clears the column"() {
        given:
        def steamUserRepo = Mock(com.sboxmarket.repository.SteamUserRepository)
        service.steamUserRepository = steamUserRepo
        def user = new com.sboxmarket.model.SteamUser(id: 7L,
            awayModeUntil: System.currentTimeMillis() - 60_000L)
        steamUserRepo.findExpiredAwayMode(_) >> [user]
        def listings = [listingFor(id: 1L, hidden: true), listingFor(id: 2L, hidden: true)]
        listingRepository.findActiveBySeller(7L) >> listings

        when:
        service.sweepExpiredAwayMode()

        then:
        listings.every { it.hidden == false }
        1 * listingRepository.saveAll(_)
        1 * steamUserRepo.save({ it.id == 7L && it.awayModeUntil == null })
    }

    def "sweepExpiredAwayMode is a no-op when nothing is expired"() {
        given:
        def steamUserRepo = Mock(com.sboxmarket.repository.SteamUserRepository)
        service.steamUserRepository = steamUserRepo
        steamUserRepo.findExpiredAwayMode(_) >> []

        when:
        service.sweepExpiredAwayMode()

        then:
        0 * listingRepository.saveAll(_)
        0 * steamUserRepo.save(_)
    }

    def "countHiddenActive forwards to the indexed COUNT query"() {
        given:
        listingRepository.countHiddenActiveBySeller(5L) >> 2L

        expect:
        service.countHiddenActive(5L) == 2L
    }

    // ── sort comparator total-order safety (no Collections.sort contract break) ──

    def "sort=ending_soon breaks an equal-expiry tie by ascending price without throwing"() {
        given:
        // Two auctions ending the exact same ms — the comparator must fall
        // through to the price tie-break and stay a valid total order.
        def dear  = listingFor(id: 1L, price: new BigDecimal('80'),
            listingType: 'AUCTION', expiresAt: 5000L)
        def cheap = listingFor(id: 2L, price: new BigDecimal('20'),
            listingType: 'AUCTION', expiresAt: 5000L)
        listingRepository.findActivePublic('', '', '', '', null, null) >> [dear, cheap]

        when:
        def result = service.getActiveListings('ending_soon', null, null, null, null, null, null)

        then:
        noExceptionThrown()
        // Same expiry → cheaper one first.
        result*.id == [2L, 1L]
    }

    def "sort=ending_soon sends a BUY_NOW row carrying a stray expiresAt to the bottom"() {
        given:
        // A BUY_NOW row should NEVER be treated as ending-soon even if it
        // somehow carries an expiresAt — only listingType=='AUCTION' rows
        // get a real deadline; everything else sorts as MAX_VALUE.
        def auction = listingFor(id: 1L, price: new BigDecimal('90'),
            listingType: 'AUCTION', expiresAt: 3000L)
        def buyNowWithExpiry = listingFor(id: 2L, price: new BigDecimal('5'),
            listingType: 'BUY_NOW', expiresAt: 1000L)
        listingRepository.findActivePublic('', '', '', '', null, null) >> [buyNowWithExpiry, auction]

        when:
        def result = service.getActiveListings('ending_soon', null, null, null, null, null, null)

        then:
        // The auction leads despite the BUY_NOW's earlier expiresAt + lower price.
        result*.id == [1L, 2L]
    }

    def "sort=ending_soon places an AUCTION with a null expiresAt after one with a real deadline"() {
        given:
        def withDeadline = listingFor(id: 1L, price: new BigDecimal('70'),
            listingType: 'AUCTION', expiresAt: 8000L)
        def noDeadline   = listingFor(id: 2L, price: new BigDecimal('3'),
            listingType: 'AUCTION', expiresAt: null)
        listingRepository.findActivePublic('', '', '', '', null, null) >> [noDeadline, withDeadline]

        when:
        def result = service.getActiveListings('ending_soon', null, null, null, null, null, null)

        then:
        // Null-expiry auction is treated as MAX_VALUE → sorts last.
        result*.id == [1L, 2L]
    }

    def "sort=discount breaks an equal-discount tie by ascending price"() {
        given:
        // Both listings are exactly 50% off Steam — the cheaper one wins.
        def dear  = listingFor(id: 1L, item: itemWithSteamPrice(1L, new BigDecimal('100.00')),
            price: new BigDecimal('50.00'))
        def cheap = listingFor(id: 2L, item: itemWithSteamPrice(2L, new BigDecimal('20.00')),
            price: new BigDecimal('10.00'))
        listingRepository.findActivePublic('', '', '', '', null, null) >> [dear, cheap]

        when:
        def result = service.getActiveListings('discount', null, null, null, null, null, null)

        then:
        noExceptionThrown()
        result*.id == [2L, 1L]
    }

    def "sort=rarity is a stable total order on an equal-supply pair"() {
        given:
        // Two items with identical supply — single-key comparator, must
        // not throw and must keep both rows.
        def a = listingFor(id: 1L, item: itemFor(1L, 'A', 'Standard', 50))
        def b = listingFor(id: 2L, item: itemFor(2L, 'B', 'Standard', 50))
        listingRepository.findActivePublic('', '', '', '', null, null) >> [a, b]

        when:
        def result = service.getActiveListings('rarity', null, null, null, null, null, null)

        then:
        noExceptionThrown()
        result.size() == 2
    }

    def "sort=popularity is total-order safe when totalSold is null on the item"() {
        given:
        // Legacy items can carry a null totalSold; the `?: 0` coalesce must
        // keep the comparator from NPEing or breaking transitivity.
        def withNull = listingFor(id: 1L, item: itemFor(1L, 'Legacy', 'Standard', 100),
            price: new BigDecimal('9'))
        withNull.item.totalSold = null
        def withSales = listingFor(id: 2L, item: itemWithCounters(2L, 7L, 0L),
            price: new BigDecimal('40'))
        listingRepository.findActivePublic('', '', '', '', null, null) >> [withNull, withSales]

        when:
        def result = service.getActiveListings('popularity', null, null, null, null, null, null)

        then:
        noExceptionThrown()
        // The 7-sales item outranks the null-sales (treated as 0) item.
        result*.id == [2L, 1L]
    }

    def "sort=views is total-order safe when viewCount is null on the item"() {
        given:
        def withNull = listingFor(id: 1L, item: itemFor(1L, 'Legacy', 'Standard', 100),
            price: new BigDecimal('9'))
        withNull.item.viewCount = null
        def withViews = listingFor(id: 2L, item: itemWithCounters(2L, 0L, 12L),
            price: new BigDecimal('40'))
        listingRepository.findActivePublic('', '', '', '', null, null) >> [withNull, withViews]

        when:
        def result = service.getActiveListings('views', null, null, null, null, null, null)

        then:
        noExceptionThrown()
        result*.id == [2L, 1L]
    }

    // ── filter param plumbing: combined filters reach findActivePublic ──

    def "getActiveListings forwards category + rarity + listingType + price band together"() {
        given:
        // Every filter set at once must arrive in findActivePublic as the
        // exact canonical args — proves no filter is dropped in the chain.
        def min = new BigDecimal('5')
        def max = new BigDecimal('500')
        listingRepository.findActivePublic('hat', 'Hats', 'Limited', 'AUCTION', min, max) >>
            [listingFor(id: 7L)]

        when:
        def result = service.getActiveListings('price_asc', 'Hats', 'Limited',
            min, max, 'hat', 'AUCTION')

        then:
        result*.id == [7L]
    }

    def "getActiveListings sorts the filtered result — discount sort applied after the query"() {
        given:
        // The repo returns price-ASC; discount sort must re-order in memory
        // even when a filter (here listingType) is also active.
        def a = listingFor(id: 1L, item: itemWithSteamPrice(1L, new BigDecimal('10.00')),
            price: new BigDecimal('9.00'))   // 10% off
        def b = listingFor(id: 2L, item: itemWithSteamPrice(2L, new BigDecimal('10.00')),
            price: new BigDecimal('2.00'))   // 80% off
        listingRepository.findActivePublic('', '', '', 'BUY_NOW', null, null) >> [a, b]

        when:
        def result = service.getActiveListings('discount', null, null, null, null, null, 'BUY_NOW')

        then:
        // Deepest discount first despite the repo's price-ASC ordering.
        result*.id == [2L, 1L]
    }

    def "getActiveListings does not mutate the list returned by the repository"() {
        given:
        // The service copies into a new ArrayList before sorting; a shared
        // mock-returned list must not be reordered under the caller.
        def original = [
            listingFor(id: 1L, price: new BigDecimal('10')),
            listingFor(id: 2L, price: new BigDecimal('50')),
        ]
        listingRepository.findActivePublic('', '', '', '', null, null) >> original

        when:
        service.getActiveListings('price_desc', null, null, null, null, null, null)

        then:
        // The repo's own list is still in its original order.
        original*.id == [1L, 2L]
    }
}
