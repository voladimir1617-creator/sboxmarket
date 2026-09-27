package com.sboxmarket

import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.Item
import com.sboxmarket.model.PriceHistory
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.PriceHistoryRepository
import com.sboxmarket.service.ItemService
import spock.lang.Specification
import spock.lang.Subject

/**
 * Pure unit coverage for ItemService.search — complements the existing
 * ItemControllerSpec which exercises the same paths over real HTTP.
 *
 * Covers: each branch of the filter precedence (q > cat+rarity > cat >
 * rarity > all), min/max price filters, every sort mode, and stats
 * rollup behaviour.
 */
class ItemServiceSpec extends Specification {

    ItemRepository         itemRepository         = Mock()
    PriceHistoryRepository priceHistoryRepository = Mock()

    @Subject
    ItemService service = new ItemService(
        itemRepository        : itemRepository,
        priceHistoryRepository: priceHistoryRepository
    )

    private Item item(long id, String name, String cat, String rarity, BigDecimal price, int sold = 0, long created = 0L) {
        new Item(id: id, name: name, category: cat, rarity: rarity,
                 lowestPrice: price, totalSold: sold, supply: 1, createdAt: created)
    }

    // ── search: filter precedence ────────────────────────────────

    def "search by q calls searchByName"() {
        given:
        itemRepository.searchByName('wiz') >> [item(1L, 'Wizard Hat', 'Hats', 'Limited', new BigDecimal("50"))]

        when:
        def result = service.search('wiz', null, null, null, null, null)

        then:
        result.size() == 1
        result[0].name == 'Wizard Hat'
        0 * itemRepository.findByCategoryAndRarity(*_)
    }

    def "search with category+rarity (both != All) uses findByCategoryAndRarity"() {
        given:
        itemRepository.findByCategoryAndRarity('Hats', 'Limited') >> [item(1L, 'H', 'Hats', 'Limited', new BigDecimal("10"))]

        when:
        def result = service.search(null, 'Hats', 'Limited', null, null, null)

        then:
        result.size() == 1
    }

    def "search with only category uses findByCategory"() {
        given:
        itemRepository.findByCategory('Hats') >> [item(1L, 'H', 'Hats', 'Standard', new BigDecimal("10"))]

        when:
        def result = service.search(null, 'Hats', null, null, null, null)

        then:
        result.size() == 1
        result[0].category == 'Hats'
    }

    def "search with only rarity uses findByRarity"() {
        given:
        itemRepository.findByRarity('Limited') >> [item(1L, 'H', 'Hats', 'Limited', new BigDecimal("10"))]

        when:
        def result = service.search(null, null, 'Limited', null, null, null)

        then:
        result.size() == 1
        result[0].rarity == 'Limited'
    }

    def "search with category='All' falls through to findAll"() {
        given:
        itemRepository.findAll() >> [item(1L, 'H', 'Hats', 'Limited', new BigDecimal("10"))]

        when:
        def result = service.search(null, 'All', 'All', null, null, null)

        then:
        result.size() == 1
    }

    // ── search: price filters ─────────────────────────────────────

    def "minPrice filter drops items below the floor (output is price_desc-sorted by default)"() {
        given:
        itemRepository.findAll() >> [
            item(1L, 'A', 'Hats', 'Standard', new BigDecimal("5")),
            item(2L, 'B', 'Hats', 'Standard', new BigDecimal("15")),
            item(3L, 'C', 'Hats', 'Standard', new BigDecimal("50")),
        ]

        when:
        def result = service.search(null, null, null, null, new BigDecimal("10"), null)

        then:
        result.size() == 2
        // Default sort is price_desc so C (50) comes first, then B (15)
        result*.name == ['C', 'B']
    }

    def "maxPrice filter drops items above the ceiling (output is price_desc-sorted by default)"() {
        given:
        itemRepository.findAll() >> [
            item(1L, 'A', 'Hats', 'Standard', new BigDecimal("5")),
            item(2L, 'B', 'Hats', 'Standard', new BigDecimal("15")),
            item(3L, 'C', 'Hats', 'Standard', new BigDecimal("50")),
        ]

        when:
        def result = service.search(null, null, null, null, null, new BigDecimal("20"))

        then:
        result.size() == 2
        // Default sort is price_desc → B (15) first, then A (5)
        result*.name == ['B', 'A']
    }

    // ── search: sort modes ────────────────────────────────────────

    def "sort=price_asc orders lowest-first"() {
        given:
        itemRepository.findAll() >> [
            item(1L, 'B', 'Hats', 'Standard', new BigDecimal("50")),
            item(2L, 'A', 'Hats', 'Standard', new BigDecimal("10")),
        ]

        when:
        def result = service.search(null, null, null, 'price_asc', null, null)

        then:
        result*.name == ['A', 'B']
    }

    def "sort=price_desc orders highest-first"() {
        given:
        itemRepository.findAll() >> [
            item(1L, 'A', 'Hats', 'Standard', new BigDecimal("10")),
            item(2L, 'B', 'Hats', 'Standard', new BigDecimal("50")),
        ]

        when:
        def result = service.search(null, null, null, 'price_desc', null, null)

        then:
        result*.name == ['B', 'A']
    }

    def "sort=popular orders by totalSold desc"() {
        given:
        itemRepository.findAll() >> [
            item(1L, 'A', 'Hats', 'Standard', new BigDecimal("10"), 5),
            item(2L, 'B', 'Hats', 'Standard', new BigDecimal("10"), 100),
        ]

        when:
        def result = service.search(null, null, null, 'popular', null, null)

        then:
        result*.name == ['B', 'A']
    }

    def "default sort falls back to price_desc"() {
        given:
        itemRepository.findAll() >> [
            item(1L, 'A', 'Hats', 'Standard', new BigDecimal("10")),
            item(2L, 'B', 'Hats', 'Standard', new BigDecimal("50")),
        ]

        when:
        def result = service.search(null, null, null, null, null, null)

        then:
        result*.name == ['B', 'A']
    }

    def "search does NOT mutate the repository result (defensive copy)"() {
        given:
        def original = [
            item(1L, 'B', 'Hats', 'Standard', new BigDecimal("50")),
            item(2L, 'A', 'Hats', 'Standard', new BigDecimal("10")),
        ]
        def originalOrder = original*.name
        itemRepository.findAll() >> original

        when:
        service.search(null, null, null, 'price_asc', null, null)

        then:
        // Original list still in original order — search made a copy
        original*.name == originalOrder
    }

    // ── getById + getPriceHistory ─────────────────────────────────

    def "getById returns item or throws NotFound"() {
        given:
        itemRepository.findById(1L) >> Optional.of(item(1L, 'A', 'Hats', 'Standard', new BigDecimal("10")))
        itemRepository.findById(999L) >> Optional.empty()

        expect:
        service.getById(1L).name == 'A'

        when:
        service.getById(999L)

        then:
        thrown(NotFoundException)
    }

    def "getPriceHistory delegates to repository in date order (400-day window, batch 1022)"() {
        given:
        priceHistoryRepository.findByItemIdSince(1L, _) >> [
            new PriceHistory(id: 1L, price: new BigDecimal("10")),
            new PriceHistory(id: 2L, price: new BigDecimal("12"))
        ]

        when:
        def history = service.getPriceHistory(1L)

        then:
        history.size() == 2
    }

    // ── getStats ──────────────────────────────────────────────────

    def "getStats rolls up totals, rarity counts, and price extremes"() {
        given:
        // Batch 618: ItemService.getStats now uses indexed aggregates
        // (catalogueSummary + countByCategory) instead of findAll() +
        // in-memory groupings. Mock returns List<Object[]> per the
        // Spring Data JPA convention.
        itemRepository.catalogueSummary() >> [([3L, 1L, new BigDecimal("5"), new BigDecimal("100")] as Object[])]
        itemRepository.countByCategory() >> [
            (['Hats',  2L] as Object[]),
            (['Pants', 1L] as Object[])
        ]

        when:
        def stats = service.getStats()

        then:
        stats.totalItems == 3
        stats.limitedCount == 1
        stats.floorPrice == new BigDecimal("5")
        stats.highestPrice == new BigDecimal("100")
        stats.categories == [Hats: 2L, Pants: 1L]
    }

    def "getStats returns 0 extremes when the catalogue is empty"() {
        given:
        itemRepository.catalogueSummary() >> [([0L, 0L, null, null] as Object[])]
        itemRepository.countByCategory() >> []

        when:
        def stats = service.getStats()

        then:
        stats.totalItems == 0
        stats.floorPrice == BigDecimal.ZERO
        stats.highestPrice == BigDecimal.ZERO
    }

    // ── getStats — defensive branches ─────────────────────────────
    // A scalar `SELECT COUNT(),SUM(),MIN(),MAX()` always returns one
    // row, but the service still guards an empty/null aggregate result.
    // Pin those branches so a future refactor can't NPE on them.

    def "getStats survives an empty catalogueSummary result (no head row)"() {
        given:
        itemRepository.catalogueSummary() >> []
        itemRepository.countByCategory() >> []

        when:
        def stats = service.getStats()

        then:
        stats.totalItems == 0L
        stats.limitedCount == 0L
        stats.floorPrice == BigDecimal.ZERO
        stats.highestPrice == BigDecimal.ZERO
        stats.categories == [:]
    }

    def "getStats survives a null catalogueSummary result"() {
        given:
        itemRepository.catalogueSummary() >> null
        itemRepository.countByCategory() >> []

        when:
        def stats = service.getStats()

        then:
        stats.totalItems == 0L
        stats.floorPrice == BigDecimal.ZERO
        stats.highestPrice == BigDecimal.ZERO
    }

    def "getStats keeps shipping totals when the category breakdown query throws"() {
        given: "the GROUP BY query fails — stats must still ship without the breakdown"
        itemRepository.catalogueSummary() >> [([4L, 2L, new BigDecimal("3"), new BigDecimal("80")] as Object[])]
        itemRepository.countByCategory() >> { throw new RuntimeException("boom") }

        when:
        def stats = service.getStats()

        then: "the aggregate row still surfaces; categories degrades to empty"
        stats.totalItems == 4L
        stats.limitedCount == 2L
        stats.floorPrice == new BigDecimal("3")
        stats.highestPrice == new BigDecimal("80")
        stats.categories == [:]
    }

    def "getStats skips category rows with a null/blank category key"() {
        given:
        itemRepository.catalogueSummary() >> [([2L, 0L, new BigDecimal("1"), new BigDecimal("9")] as Object[])]
        itemRepository.countByCategory() >> [
            (['Hats', 2L] as Object[]),
            ([null,  5L] as Object[]),   // null category — must be dropped
        ]

        when:
        def stats = service.getStats()

        then:
        stats.categories == [Hats: 2L]
    }

    def "getStats tolerates a null count in a category row (coerced to 0)"() {
        given:
        itemRepository.catalogueSummary() >> [([1L, 0L, new BigDecimal("2"), new BigDecimal("2")] as Object[])]
        itemRepository.countByCategory() >> [
            (['Workshop', null] as Object[]),
        ]

        when:
        def stats = service.getStats()

        then:
        stats.categories == [Workshop: 0L]
    }

    def "getStats handles a non-null high with a null floor"() {
        given: "MIN came back null but MAX did not — each extreme resolved independently"
        itemRepository.catalogueSummary() >> [([3L, 1L, null, new BigDecimal("42")] as Object[])]
        itemRepository.countByCategory() >> []

        when:
        def stats = service.getStats()

        then:
        stats.floorPrice == BigDecimal.ZERO
        stats.highestPrice == new BigDecimal("42")
    }

    // ── search — additional sort modes + filters ──────────────────

    def "sort=rarity orders by ascending supply (rarest first)"() {
        given:
        itemRepository.findAll() >> [
            new Item(id: 1L, name: 'Common', category: 'Hats', rarity: 'Standard',
                     lowestPrice: new BigDecimal("10"), supply: 9000, totalSold: 0, createdAt: 0L),
            new Item(id: 2L, name: 'Rare', category: 'Hats', rarity: 'Limited',
                     lowestPrice: new BigDecimal("10"), supply: 50, totalSold: 0, createdAt: 0L),
        ]

        when:
        def result = service.search(null, null, null, 'rarity', null, null)

        then:
        result*.name == ['Rare', 'Common']
    }

    def "sort=newest orders by descending createdAt"() {
        given:
        itemRepository.findAll() >> [
            item(1L, 'Old', 'Hats', 'Standard', new BigDecimal("10"), 0, 1_000L),
            item(2L, 'New', 'Hats', 'Standard', new BigDecimal("10"), 0, 9_000L),
        ]

        when:
        def result = service.search(null, null, null, 'newest', null, null)

        then:
        result*.name == ['New', 'Old']
    }

    def "an unknown sort token falls through to price_desc"() {
        given:
        itemRepository.findAll() >> [
            item(1L, 'A', 'Hats', 'Standard', new BigDecimal("10")),
            item(2L, 'B', 'Hats', 'Standard', new BigDecimal("50")),
        ]

        when: "a garbage sort value — the controller lowercases but does not whitelist"
        def result = service.search(null, null, null, 'sideways', null, null)

        then:
        result*.name == ['B', 'A']
    }

    def "minPrice and maxPrice together keep only the in-band items"() {
        given:
        itemRepository.findAll() >> [
            item(1L, 'Below', 'Hats', 'Standard', new BigDecimal("5")),
            item(2L, 'In', 'Hats', 'Standard', new BigDecimal("25")),
            item(3L, 'Above', 'Hats', 'Standard', new BigDecimal("90")),
        ]

        when:
        def result = service.search(null, null, null, null, new BigDecimal("10"), new BigDecimal("50"))

        then:
        result*.name == ['In']
    }

    def "price filter bounds are inclusive on both ends"() {
        given:
        itemRepository.findAll() >> [
            item(1L, 'Floor', 'Hats', 'Standard', new BigDecimal("10")),
            item(2L, 'Ceiling', 'Hats', 'Standard', new BigDecimal("20")),
        ]

        when: "an item priced exactly at the floor or the ceiling survives"
        def result = service.search(null, null, null, 'price_asc', new BigDecimal("10"), new BigDecimal("20"))

        then:
        result*.name == ['Floor', 'Ceiling']
    }

    def "a q-search still honours the price filter"() {
        given: "searchByName is the source; the price filter runs on top of it"
        itemRepository.searchByName('hat') >> [
            item(1L, 'Cheap Hat', 'Hats', 'Standard', new BigDecimal("5")),
            item(2L, 'Pricey Hat', 'Hats', 'Standard', new BigDecimal("80")),
        ]

        when:
        def result = service.search('hat', null, null, null, new BigDecimal("10"), null)

        then: "the sub-floor hat is dropped even though it matched the name query"
        result*.name == ['Pricey Hat']
    }

    def "search returns an empty list when the repository yields nothing"() {
        given:
        itemRepository.findAll() >> []

        when:
        def result = service.search(null, null, null, 'price_asc', null, null)

        then:
        result == []
    }

    // ── getPriceHistory — 400-day cutoff window (batch 1022) ───────

    def "getPriceHistory passes a cutoff roughly 400 days in the past"() {
        given:
        Long seenCutoff = null
        priceHistoryRepository.findByItemIdSince(7L, _) >> { args ->
            seenCutoff = args[1] as Long
            []
        }
        long expected = System.currentTimeMillis() - (400L * 24L * 60L * 60L * 1000L)

        when:
        service.getPriceHistory(7L)

        then: "cutoff is the 400-day window — within a few seconds of the expected value"
        seenCutoff != null
        Math.abs(seenCutoff - expected) < 5_000L
        // Sanity: a 400-day window is well clear of any overflow/underflow.
        seenCutoff > 0L
        seenCutoff < System.currentTimeMillis()
    }

    def "getPriceHistory keys the lookup off the requested item id"() {
        when:
        service.getPriceHistory(123L)

        then:
        1 * priceHistoryRepository.findByItemIdSince(123L, _) >> []
        0 * priceHistoryRepository.findByItemIdSince({ it != 123L }, _)
    }

    // ── getAll + save passthroughs ────────────────────────────────

    def "getAll delegates straight to the repository"() {
        given:
        def rows = [item(1L, 'A', 'Hats', 'Standard', new BigDecimal("10"))]
        itemRepository.findAll() >> rows

        expect:
        service.getAll() == rows
    }

    def "save delegates to the repository and returns the persisted item"() {
        given:
        def toSave = item(1L, 'A', 'Hats', 'Standard', new BigDecimal("10"))
        itemRepository.save(toSave) >> toSave

        expect:
        service.save(toSave).is(toSave)
    }
}
