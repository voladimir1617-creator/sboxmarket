package com.sboxmarket

import com.sboxmarket.model.Item
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.service.SboxApiService
import spock.lang.Specification
import spock.lang.Subject

/**
 * Unit coverage for the SCMM → Item upsert logic. The actual HTTP call
 * (`fetchRemoteCatalogue()`) is out of scope — it's a public endpoint
 * with well-defined failure modes (returns [] on error). What we DO
 * cover is the mapping pipeline:
 *
 *   - itemType → category lookup (7 categories, catch-all = Accessories)
 *   - cents → decimal price conversion
 *   - emoji fallback by itemType
 *   - upsert by exact-match name
 *   - supply / subscriptions / priceMovement parsing
 *   - robustness against missing/garbage fields (never throws)
 */
class SboxApiServiceSpec extends Specification {

    ItemRepository    itemRepository    = Mock()
    ListingRepository listingRepository = Mock()

    @Subject
    SboxApiService service = new SboxApiService(
        itemRepository   : itemRepository,
        listingRepository: listingRepository
    )

    /** Replace fetchRemoteCatalogue with a deterministic provider per test. */
    private void stubRemote(List<Map> rows) {
        service.metaClass.fetchRemoteCatalogue = { -> rows }
    }

    def "syncFromScmm returns zeroes when the remote is empty"() {
        given:
        stubRemote([])

        when:
        def result = service.syncFromScmm()

        then:
        result.created == 0
        result.updated == 0
    }

    def "syncFromScmm maps itemType to internal category"() {
        given:
        stubRemote([
            [name: 'A Hat',   itemType: 'Hat',    buyNowPrice: 500,  originalPrice: 500],
            [name: 'A Jacket', itemType: 'Jacket', buyNowPrice: 1500, originalPrice: 1500],
            [name: 'A Shirt',  itemType: 'TShirt', buyNowPrice: 200,  originalPrice: 200],
            [name: 'Some Gloves', itemType: 'Gloves', buyNowPrice: 300,  originalPrice: 300],
            [name: 'Some Boots',  itemType: 'Boots',  buyNowPrice: 900,  originalPrice: 900],
            [name: 'A Tattoo', itemType: 'Tattoo', buyNowPrice: 100,  originalPrice: 100],
            [name: 'Mystery', itemType: 'ZQZXC',   buyNowPrice: 100,  originalPrice: 100],
        ])
        itemRepository.findAll() >> []
        def saved = []
        itemRepository.save(_) >> { args -> saved << args[0]; args[0] }

        when:
        service.syncFromScmm()

        then:
        saved.find { it.name == 'A Hat' }.category      == 'Hats'
        saved.find { it.name == 'A Jacket' }.category   == 'Jackets'
        saved.find { it.name == 'A Shirt' }.category    == 'Shirts'
        saved.find { it.name == 'Some Gloves' }.category == 'Gloves'
        saved.find { it.name == 'Some Boots' }.category  == 'Boots'
        saved.find { it.name == 'A Tattoo' }.category    == 'Accessories'
        // unknown itemType falls through to Accessories
        saved.find { it.name == 'Mystery' }.category     == 'Accessories'
    }

    def "syncFromScmm converts cents to dollars on buyNowPrice and originalPrice"() {
        given:
        stubRemote([[name: 'X', itemType: 'Hat', buyNowPrice: 1234, originalPrice: 2000]])
        itemRepository.findAll() >> []
        def saved
        itemRepository.save(_) >> { args -> saved = args[0]; args[0] }

        when:
        service.syncFromScmm()

        then:
        saved.lowestPrice == new BigDecimal("12.34")
        saved.steamPrice  == new BigDecimal("20.00")
    }

    def "syncFromScmm upserts by exact name — doesn't duplicate on rerun"() {
        given:
        stubRemote([[name: 'Wizard Hat', itemType: 'Hat', buyNowPrice: 500, originalPrice: 500]])
        def existing = new Item(id: 1L, name: 'Wizard Hat', category: 'Hats',
                                 lowestPrice: new BigDecimal("3.00"))
        itemRepository.findAll() >> [existing]
        itemRepository.save(_) >> { args -> args[0] }

        when:
        def result = service.syncFromScmm()

        then:
        result.created == 0
        result.updated == 1
        // SCMM no longer overwrites lowestPrice — that comes from
        // SteamMarketPriceService. SCMM only updates metadata.
        existing.lowestPrice == new BigDecimal("3.00")  // unchanged — prices come from Steam now
    }

    def "syncFromScmm is case-insensitive on the name key"() {
        given:
        stubRemote([[name: 'WIZARD HAT', itemType: 'Hat', buyNowPrice: 999, originalPrice: 999]])
        def existing = new Item(id: 1L, name: 'Wizard Hat', category: 'Hats')
        itemRepository.findAll() >> [existing]
        itemRepository.save(_) >> { args -> args[0] }

        when:
        def result = service.syncFromScmm()

        then:
        result.updated == 1
        result.created == 0
    }

    def "syncFromScmm skips rows with missing name"() {
        given:
        stubRemote([
            [name: '',      itemType: 'Hat', buyNowPrice: 500],
            [name: null,    itemType: 'Hat', buyNowPrice: 500],
            [name: 'Valid', itemType: 'Hat', buyNowPrice: 500],
        ])
        itemRepository.findAll() >> []
        itemRepository.save(_) >> { args -> args[0] }

        when:
        def result = service.syncFromScmm()

        then:
        result.created == 1
        result.skipped == 2
    }

    def "syncFromScmm survives garbage in priceMovement and sellEnd"() {
        given:
        stubRemote([[
            name: 'X', itemType: 'Hat', buyNowPrice: 100,
            priceMovement: 'not-a-number',
            sellEnd: '2020-garbage-date',
            supply: 5,
            supplyTotalKnown: 100
        ]])
        itemRepository.findAll() >> []
        def saved
        itemRepository.save(_) >> { args -> saved = args[0]; args[0] }

        when:
        def result = service.syncFromScmm()

        then:
        result.created == 1
        saved.trendPercent == 0  // bad priceMovement → 0, not crash
    }

    def "syncFromScmm derives Off-Market rarity when supply is <5% of total"() {
        given:
        stubRemote([[
            name: 'Rare One', itemType: 'Hat', buyNowPrice: 10000,
            supply: 3, supplyTotalKnown: 100
        ]])
        itemRepository.findAll() >> []
        def saved
        itemRepository.save(_) >> { args -> saved = args[0]; args[0] }

        when:
        service.syncFromScmm()

        then:
        saved.rarity == 'Off-Market'
    }

    def "syncFromScmm defaults to Standard rarity when supply is healthy"() {
        given:
        stubRemote([[
            name: 'Common One', itemType: 'Hat', buyNowPrice: 100,
            supply: 500, supplyTotalKnown: 1000
        ]])
        itemRepository.findAll() >> []
        def saved
        itemRepository.save(_) >> { args -> saved = args[0]; args[0] }

        when:
        service.syncFromScmm()

        then:
        saved.rarity == 'Standard'
    }
}
