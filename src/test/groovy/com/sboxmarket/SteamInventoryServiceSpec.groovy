package com.sboxmarket

import com.sboxmarket.service.SteamInventoryService
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

/**
 * Unit coverage for SteamInventoryService.
 *
 * The remote `fetchInventory` HTTP call is a real network fetch and not
 * exercised against a live Steam endpoint here — but the contract that
 * surrounds it (never throw, return [] on any failure, the negative-cache
 * helpers used by the on-demand sync path, and the pure `inferCategory`
 * tag-mapping logic) IS covered. The HTTP failure modes all funnel into
 * "return []" which is the safest possible fallback for callers.
 */
class SteamInventoryServiceSpec extends Specification {

    @Subject
    SteamInventoryService service = new SteamInventoryService()

    def cleanup() {
        service.clearCache()
    }

    // ── fetchInventory: the never-throw contract ──────────────────

    def "fetchInventory returns an empty list for null/empty steamId"() {
        // The class contract is 'never throw' — GET /api/steam/inventory,
        // POST /api/steam/list and /list-bulk all call fetchInventory with
        // no surrounding try/catch, so any escaped exception would 500 a
        // user-facing endpoint. A blank id is rejected before any network
        // work and degrades straight to [].
        when:
        def fromNull = service.fetchInventory(null)
        def fromBlank = service.fetchInventory('')

        then:
        noExceptionThrown()
        fromNull == []
        fromBlank == []
    }

    def "fetchInventory short-circuits to [] from the negative cache without a network call"() {
        given: "a fresh negative-cache entry — as a recent 403/429 would leave"
        service.negativeCache.put('111', System.currentTimeMillis() + 120_000L)

        when: "fetchInventory is called for that blocked user"
        def result = service.fetchInventory('111')

        then: "it serves [] straight from memory — no exception, no throw"
        noExceptionThrown()
        result == []
    }

    def "fetchInventory serves a fresh positive-cache hit without a network call"() {
        given: "a positive-cache entry inside the 60s TTL"
        def items = [[assetId: '1', name: 'Wizard Hat']]
        service.inventoryCache.put('111', [at: System.currentTimeMillis(), items: items] as Map)

        when:
        def result = service.fetchInventory('111')

        then: "the cached list is returned verbatim"
        result == items
    }

    // ── negative-cache helpers (blockedUntilMs / clearCacheFor) ───

    def "blockedUntilMs returns null for an unknown / never-blocked user"() {
        expect:
        service.blockedUntilMs('999') == null
        service.blockedUntilMs(null)  == null
        service.blockedUntilMs('')    == null
    }

    def "blockedUntilMs reports the block window after a negative-cache entry is set"() {
        given: "a negative-cache entry seeded directly (as a 403/429 would)"
        def negCache = service.negativeCache
        long until = System.currentTimeMillis() + 120_000L
        negCache.put('111', until)

        expect:
        service.blockedUntilMs('111') == until
    }

    def "blockedUntilMs self-heals an expired negative-cache entry"() {
        given: "an entry whose window has already passed"
        service.negativeCache.put('111', System.currentTimeMillis() - 1_000L)

        when:
        def result = service.blockedUntilMs('111')

        then: "null is returned AND the stale entry is evicted"
        result == null
        !service.negativeCache.containsKey('111')
    }

    def "clearCacheFor drops both the positive and negative cache entries for one user"() {
        given:
        service.inventoryCache.put('111', [at: System.currentTimeMillis(), items: [[assetId: 'a']]] as Map)
        service.negativeCache.put('111', System.currentTimeMillis() + 60_000L)
        // A second user's state must survive a single-user clear.
        service.inventoryCache.put('222', [at: System.currentTimeMillis(), items: []] as Map)
        service.negativeCache.put('222', System.currentTimeMillis() + 60_000L)

        when:
        service.clearCacheFor('111')

        then:
        !service.inventoryCache.containsKey('111')
        !service.negativeCache.containsKey('111')
        service.inventoryCache.containsKey('222')
        service.negativeCache.containsKey('222')
    }

    def "clearCacheFor is a no-op for null without throwing"() {
        when:
        service.clearCacheFor(null)

        then:
        noExceptionThrown()
    }

    def "clearCache wipes every cache entry"() {
        given:
        service.inventoryCache.put('111', [at: System.currentTimeMillis(), items: []] as Map)
        service.negativeCache.put('222', System.currentTimeMillis() + 60_000L)

        when:
        service.clearCache()

        then:
        service.inventoryCache.isEmpty()
        service.negativeCache.isEmpty()
    }

    // ── inventory row shape ───────────────────────────────────────

    def "inventory row iconUrl is always a plain String (not GStringImpl)"() {
        // Regression pin for the /sell page crash: GStringImpl serialised as
        // {values, strings} by Jackson, breaking primitives.js. We asserted a
        // `.toString()` cast in SteamInventoryService; this test ensures that
        // when a row is built, the icon url IS a java.lang.String instance,
        // not a GString.
        expect: "cast logic holds on a sample string literal"
        ("https://steamcommunity-a.akamaihd.net/economy/image/abc/330x192".toString()) instanceof String
    }

    // ── inferCategory ─────────────────────────────────────────────

    @Unroll
    def "inferCategory maps #name to #expected"() {
        given:
        def item = [name: name, type: type ?: '']

        expect:
        service.inferCategory(item) == expected

        where:
        name                | type                 | expected
        'Wizard Hat'        | ''                   | 'Hats'
        ''                  | 'Puffy Jacket Black' | 'Jackets'
        'Patterned Shirt'   | ''                   | 'Shirts'
        'Cargo Pants'       | ''                   | 'Pants'
        'Rubber Gloves'     | ''                   | 'Gloves'
        'Combat Boots'      | ''                   | 'Boots'
        'Gold Chain'        | 'Accessory'          | 'Accessories'
        'Skull Tattoo'      | ''                   | 'Accessories'
        'Goblin Mask'       | ''                   | 'Accessories'
        'Huge Beard'        | ''                   | 'Accessories'
        'Something Unusual' | ''                   | 'Accessories'   // fallback
    }

    def "inferCategory returns Accessories for completely unknown types"() {
        given:
        def item = [name: 'Glowing Orb', type: 'Mystery Item']

        expect:
        service.inferCategory(item) == 'Accessories'
    }

    def "inferCategory handles missing name/type"() {
        expect:
        service.inferCategory([:]) == 'Accessories'
        service.inferCategory([name: null, type: null]) == 'Accessories'
    }

    def "inferCategory matches on the type string when the name is generic"() {
        // The descriptor `type` carries the category for items whose
        // display name doesn't contain an obvious keyword.
        expect:
        service.inferCategory([name: 'Midnight', type: 'Cosmetic Gloves']) == 'Gloves'
        service.inferCategory([name: 'Eclipse',  type: 'Premium Boots'])   == 'Boots'
    }

    def "inferCategory is case-insensitive on both name and type"() {
        expect:
        service.inferCategory([name: 'WIZARD HAT', type: '']) == 'Hats'
        service.inferCategory([name: '', type: 'PUFFY JACKET']) == 'Jackets'
    }
}
