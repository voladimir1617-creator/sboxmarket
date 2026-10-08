package com.sboxmarket

import com.sboxmarket.model.Item
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.PriceHistoryRepository
import com.sboxmarket.service.ItemService
import spock.lang.Specification
import spock.lang.Subject

/**
 * Search-relevance ordering for ItemService.search.
 *
 * `ItemRepository.searchByName` is a bare `LIKE %q%` with no ORDER BY, and
 * the per-mode sort in `search` keys purely off price / popularity / supply
 * / createdAt. That means a free-text query like `?q=Sniper` would bury the
 * item literally named "Sniper" wherever its price happened to land in the
 * price-sorted list, ranking pricier *substring* matches ("Golden Sniper
 * Rifle") above the exact match.
 *
 * These specs pin the fix: when `q` is supplied, an exact (case-insensitive)
 * name match must rank ABOVE substring matches, with the requested sort kept
 * as the tiebreaker WITHIN each relevance group. They FAIL before the fix
 * (exact match sorts purely by price) and PASS after.
 *
 * Complements the existing ItemServiceSpec which already pins filter
 * precedence, price filters, and every sort mode for the no-`q` paths.
 */
class ItemServiceSearchRelevanceSpec extends Specification {

    ItemRepository         itemRepository         = Mock()
    PriceHistoryRepository priceHistoryRepository = Mock()

    @Subject
    ItemService service = new ItemService(
        itemRepository        : itemRepository,
        priceHistoryRepository: priceHistoryRepository
    )

    private Item item(long id, String name, BigDecimal price, int sold = 0) {
        new Item(id: id, name: name, category: 'Hats', rarity: 'Standard',
                 lowestPrice: price, totalSold: sold, supply: 1, createdAt: 0L)
    }

    def "exact name match ranks first even when a pricier substring match exists (default price_desc)"() {
        given: "searchByName returns the cheap exact match plus a pricier substring match"
        itemRepository.searchByName('Sniper') >> [
            item(1L, 'Golden Sniper Rifle', new BigDecimal("500")),
            item(2L, 'Sniper',              new BigDecimal("10")),
        ]

        when: "no explicit sort — controller default is price_desc, so the pricey one would normally win"
        def result = service.search('Sniper', null, null, null, null, null)

        then: "the exact 'Sniper' is surfaced first despite being the cheapest"
        result*.name == ['Sniper', 'Golden Sniper Rifle']
    }

    def "exact match is case-insensitive and still beats a pricier substring match"() {
        given:
        itemRepository.searchByName('sniper') >> [
            item(1L, 'Golden Sniper Rifle', new BigDecimal("500")),
            item(2L, 'Sniper',              new BigDecimal("10")),
        ]

        when:
        def result = service.search('sniper', null, null, 'price_desc', null, null)

        then:
        result*.name == ['Sniper', 'Golden Sniper Rifle']
    }

    def "the requested sort is preserved as the tiebreaker within each relevance group"() {
        given: "two substring matches at different prices; no exact match present"
        itemRepository.searchByName('hat') >> [
            item(1L, 'Pricey Hat Deluxe', new BigDecimal("80")),
            item(2L, 'Cheap Hat Basic',   new BigDecimal("5")),
        ]

        when: "price_asc requested — within the (all-substring) group, cheapest first"
        def result = service.search('hat', null, null, 'price_asc', null, null)

        then:
        result*.name == ['Cheap Hat Basic', 'Pricey Hat Deluxe']
    }

    def "exact matches keep the requested sort order among themselves"() {
        given: "two exact matches (same name, different price) plus a substring match"
        itemRepository.searchByName('Hat') >> [
            item(1L, 'Hat Stand',  new BigDecimal("99")),  // substring
            item(2L, 'Hat',        new BigDecimal("40")),  // exact, pricier
            item(3L, 'hat',        new BigDecimal("20")),  // exact (case-insensitive), cheaper
        ]

        when: "price_asc — exact group first, ordered cheapest-first within it, then the substring match"
        def result = service.search('Hat', null, null, 'price_asc', null, null)

        then:
        result*.name == ['hat', 'Hat', 'Hat Stand']
    }

    def "no-q search is unaffected — pure price_desc default still holds"() {
        given:
        itemRepository.findAll() >> [
            item(1L, 'A', new BigDecimal("10")),
            item(2L, 'B', new BigDecimal("50")),
        ]

        when: "no free-text query — relevance pass must not engage"
        def result = service.search(null, null, null, null, null, null)

        then: "unchanged: highest price first"
        result*.name == ['B', 'A']
    }

    def "a null-named row is treated as a non-match and never NPEs the relevance pass"() {
        given: "a defensive null name slips through alongside an exact match"
        itemRepository.searchByName('Hat') >> [
            new Item(id: 1L, name: null, category: 'Hats', rarity: 'Standard',
                     lowestPrice: new BigDecimal("99"), totalSold: 0, supply: 1, createdAt: 0L),
            item(2L, 'Hat', new BigDecimal("5")),
        ]

        when:
        def result = service.search('Hat', null, null, 'price_desc', null, null)

        then: "exact match first; the null-named row sorts as a non-match without throwing"
        result.size() == 2
        result[0].name == 'Hat'
        result[1].name == null
    }

    def "an exact name with _ or % still ranks first when q arrives LIKE-escaped"() {
        given: "ItemController escapes _ and % before calling search"
        itemRepository.searchByName('Hat\\_100\\%') >> [
            item(1L, 'Big Hat_100% Gold', new BigDecimal("500")),
            item(2L, 'Hat_100%',          new BigDecimal("10")),
        ]

        when:
        def result = service.search('Hat\\_100\\%', null, null, null, null, null)

        then:
        result*.name == ['Hat_100%', 'Big Hat_100% Gold']
    }
}
