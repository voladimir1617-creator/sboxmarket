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

    // ── Regression: malformed numeric fields must NOT abort the sync ──
    // Before the safeLong/safeInt fix a non-numeric value in any price or
    // count field threw GroovyCastException/NumberFormatException straight
    // out of the @Transactional syncFromScmm, rolling the whole catalogue
    // sync back. The priceMovement/sellEnd garbage path was already guarded;
    // buyNowPrice / originalPrice / supply / supplyTotalKnown / subscriptions
    // were not.

    def "syncFromScmm survives a non-numeric buyNowPrice without throwing"() {
        given:
        stubRemote([[name: 'Bad Price', itemType: 'Hat', buyNowPrice: 'n/a']])
        itemRepository.findAll() >> []
        def saved
        itemRepository.save(_) >> { args -> saved = args[0]; args[0] }

        when:
        def result = service.syncFromScmm()

        then:
        noExceptionThrown()
        result.created == 1
        saved.lowestPrice == BigDecimal.ZERO   // garbage price → 0, not a crash
    }

    def "syncFromScmm survives a non-numeric originalPrice (falls back to floor)"() {
        given:
        stubRemote([[name: 'Bad Orig', itemType: 'Hat',
                     buyNowPrice: 500, originalPrice: 'garbage']])
        itemRepository.findAll() >> []
        def saved
        itemRepository.save(_) >> { args -> saved = args[0]; args[0] }

        when:
        def result = service.syncFromScmm()

        then:
        noExceptionThrown()
        result.created == 1
        saved.lowestPrice == new BigDecimal('5.00')
        // Elvis fallback: unparseable retail-reference defaults to the floor.
        saved.steamPrice  == new BigDecimal('5.00')
    }

    def "syncFromScmm survives non-numeric supply / supplyTotalKnown / subscriptions"() {
        given:
        stubRemote([[
            name: 'Bad Counts', itemType: 'Hat', buyNowPrice: 100,
            supply: 'lots', supplyTotalKnown: [1, 2], subscriptions: 'many'
        ]])
        itemRepository.findAll() >> []
        def saved
        itemRepository.save(_) >> { args -> saved = args[0]; args[0] }

        when:
        def result = service.syncFromScmm()

        then:
        noExceptionThrown()
        result.created == 1
        saved.supply    == 0   // garbage → 0
        saved.totalSold == 0
        saved.rarity    == 'Standard'  // no usable supply signal → Standard
    }

    def "syncFromScmm coerces a numeric String price (SCMM sometimes stringifies)"() {
        given:
        stubRemote([[name: 'Str Price', itemType: 'Hat',
                     buyNowPrice: '1499', originalPrice: '2000', supply: '7']])
        itemRepository.findAll() >> []
        def saved
        itemRepository.save(_) >> { args -> saved = args[0]; args[0] }

        when:
        service.syncFromScmm()

        then:
        noExceptionThrown()
        saved.lowestPrice == new BigDecimal('14.99')
        saved.steamPrice  == new BigDecimal('20.00')
        saved.supply      == 7
    }

    def "syncFromScmm isolates one bad row — the rest of the batch still imports"() {
        given:
        stubRemote([
            [name: 'Good One', itemType: 'Hat', buyNowPrice: 100],
            [name: 'Bad One',  itemType: 'Hat', buyNowPrice: ['nested': 'object']],
            [name: 'Good Two', itemType: 'Boots', buyNowPrice: 200],
        ])
        itemRepository.findAll() >> []
        def saved = []
        itemRepository.save(_) >> { args -> saved << args[0]; args[0] }

        when:
        def result = service.syncFromScmm()

        then:
        noExceptionThrown()
        // even with a structured-object price on the middle row, the two
        // good rows import; the bad row degrades to a zero-price import
        // rather than aborting the whole @Transactional sync.
        result.created == 3
        saved*.name.containsAll(['Good One', 'Good Two'])
    }

    def "syncFromScmm tolerates a null row in the remote list"() {
        given:
        stubRemote([
            [name: 'Real One', itemType: 'Hat', buyNowPrice: 100],
            null,
        ])
        itemRepository.findAll() >> []
        itemRepository.save(_) >> { args -> args[0] }

        when:
        def result = service.syncFromScmm()

        then:
        noExceptionThrown()
        result.created == 1
        // null row → r?.name null-safe navigation yields an empty name and
        // hits the missing-name skip path; it is counted, never fatal.
        result.skipped == 1
    }

    // ── lastSyncedAt telemetry + scheduledSync ────────────────────

    def "lastSyncedAt is zero until the first successful sync completes"() {
        expect: 'fresh service has not yet run'
        service.lastSyncedAt == 0L
    }

    def "lastSyncedAt is stamped at the end of a syncFromScmm pass"() {
        given:
        stubRemote([[name: 'One', itemType: 'Hat', buyNowPrice: 100]])
        itemRepository.findAll() >> []
        itemRepository.save(_) >> { args -> args[0] }
        long before = System.currentTimeMillis()

        when:
        service.syncFromScmm()

        then: 'the volatile timestamp is set inside the [before..now] window'
        // The footer "Catalog updated X ago" stat reads this value — pin
        // that it actually moves on a real sync so a future refactor can't
        // silently disable the freshness signal.
        service.lastSyncedAt >= before
        service.lastSyncedAt <= System.currentTimeMillis()
    }

    def "lastSyncedAt is NOT updated when the remote feed comes back empty"() {
        given: 'fetchRemoteCatalogue returns [] (network error / SCMM down)'
        stubRemote([])

        when:
        def result = service.syncFromScmm()

        then: 'empty result short-circuits before the lastSyncedAt write'
        // Important: an empty remote is the failure path. If we stamped
        // lastSyncedAt here, the freshness chip would say "1 minute ago"
        // while we silently haven't actually fetched anything in hours.
        // The early-return must precede the timestamp update.
        result.totalRemote == 0
        service.lastSyncedAt == 0L
    }

    def "scheduledSync is intentionally inert — does NOT call out to SCMM"() {
        // The operator explicitly removed SCMM scheduling. scheduledSync()
        // still exists as a hook (a removed @Scheduled stays a public
        // method) but it MUST be a no-op — wiring it back accidentally
        // (e.g. by reattaching @Scheduled) would resume catalogue churn
        // the operator opted out of.
        given:
        boolean fetched = false
        service.metaClass.fetchRemoteCatalogue = { -> fetched = true; [] }

        when:
        service.scheduledSync()

        then: 'no remote fetch, no repo write, no exception'
        !fetched
        0 * itemRepository.findAll()
        0 * itemRepository.save(_)
        noExceptionThrown()
    }

    // ── Off-Market threshold — the 5% supply boundary ─────────────

    def "syncFromScmm derives Standard rarity exactly at the 5% boundary"() {
        // Off-Market triggers when `supply < total * 0.05`. Pin the
        // boundary semantics: 5/100 == 0.05 is NOT strictly less than
        // 5.0, so it must remain Standard. One past — 4/100 — flips.
        given:
        stubRemote([
            [name: 'Exact-5pct',   itemType: 'Hat', buyNowPrice: 100, supply: 5, supplyTotalKnown: 100],
            [name: 'Just-under',   itemType: 'Hat', buyNowPrice: 100, supply: 4, supplyTotalKnown: 100],
        ])
        itemRepository.findAll() >> []
        def saved = []
        itemRepository.save(_) >> { args -> saved << args[0]; args[0] }

        when:
        service.syncFromScmm()

        then:
        saved.find { it.name == 'Exact-5pct' }.rarity == 'Standard'
        saved.find { it.name == 'Just-under' }.rarity == 'Off-Market'
    }

    // ── existing item update guards ───────────────────────────────

    def "syncFromScmm preserves an existing Item.steamPrice when SCMM reports equal-to-floor (batch 637)"() {
        // The "equal-to-floor" path was the bug: SCMM reports both
        // buyNowPrice and originalPrice as the same number when the
        // item is not on active sale, which would otherwise clobber a
        // legitimate higher steamPrice with a flat reference. Pin the
        // guard so that fix can't regress.
        given:
        def existing = new Item(
            id: 1L, name: 'Wizard Hat', category: 'Hats',
            steamPrice: new BigDecimal('10.00'),
            lowestPrice: new BigDecimal('5.00')
        )
        stubRemote([[name: 'Wizard Hat', itemType: 'Hat',
                     buyNowPrice: 700, originalPrice: 700]])
        itemRepository.findAll() >> [existing]
        itemRepository.save(_) >> { args -> args[0] }

        when:
        service.syncFromScmm()

        then: 'steamPrice (10) survives the equal-to-floor sync (7 == 7)'
        existing.steamPrice == new BigDecimal('10.00')
    }

    def "syncFromScmm WILL bump steamPrice when SCMM reports a HIGHER retail-reference than the floor"() {
        // The complement to the guard above: when steamPrice <= price
        // wins for the existing row, the new higher reference is
        // accepted — otherwise the discount chip never gets an
        // accurate retail anchor for items SCMM marks down.
        given:
        def existing = new Item(
            id: 2L, name: 'Top Hat', category: 'Hats',
            steamPrice: new BigDecimal('5.00'),
            lowestPrice: new BigDecimal('3.00')
        )
        stubRemote([[name: 'Top Hat', itemType: 'Hat',
                     buyNowPrice: 300, originalPrice: 800]])
        itemRepository.findAll() >> [existing]
        itemRepository.save(_) >> { args -> args[0] }

        when:
        service.syncFromScmm()

        then: 'steamPrice (5) jumps to the new retail reference (8) because 8 > 3'
        existing.steamPrice == new BigDecimal('8.00')
    }
}
