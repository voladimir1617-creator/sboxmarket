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

    def "getPriceHistory delegates to repository in date order"() {
        given:
        priceHistoryRepository.findByItemIdOrdered(1L) >> [
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
        itemRepository.findAll() >> [
            item(1L, 'A', 'Hats',  'Standard', new BigDecimal("5")),
            item(2L, 'B', 'Hats',  'Limited',  new BigDecimal("100")),
            item(3L, 'C', 'Pants', 'Standard', new BigDecimal("20")),
        ]

        when:
        def stats = service.getStats()

        then:
        stats.totalItems == 3
        stats.limitedCount == 1
        stats.floorPrice == new BigDecimal("5")
        stats.highestPrice == new BigDecimal("100")
        stats.categories == [Hats: 2, Pants: 1]
    }

    def "getStats returns 0 extremes when the catalogue is empty"() {
        given:
        itemRepository.findAll() >> []

        when:
        def stats = service.getStats()

        then:
        stats.totalItems == 0
        stats.floorPrice == 0
        stats.highestPrice == 0
    }
}
