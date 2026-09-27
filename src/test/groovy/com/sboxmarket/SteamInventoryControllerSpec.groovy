package com.sboxmarket

import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.controller.SteamInventoryController
import com.sboxmarket.model.Item
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.ListingService
import com.sboxmarket.service.SteamInventoryService
import com.sboxmarket.service.SteamSyncService
import com.sboxmarket.service.TextSanitizer
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

/**
 * Coverage for /api/steam/inventory's stack-aware grouping — the fix
 * for the "44 rows, 327 actual items" Sell Items bug. Steam's
 * IEconService inventory returns one descriptor per
 * (classId, instanceId) and one entry in `assets[]` per physical copy,
 * so 50 Lunar Trousers come back as 50 asset rows that share a
 * descriptor. The controller must collapse those down to one row per
 * descriptor with a `quantity` field and the full `assetIds[]` array
 * preserved (so future per-asset trade flows can still target a
 * specific copy).
 *
 * Invariants pinned here:
 *
 *   1. Three identical descriptors collapse to ONE row with
 *      quantity=3 and assetIds=[..3 ids..].
 *   2. Mixed-tradable descriptors (some copies tradable, some not)
 *      still collapse to one row, and the representative `assetId`
 *      is one of the tradable ones — listing flow needs a tradable
 *      assetId or it throws NOT_TRADABLE.
 *   3. Distinct descriptors stay distinct (can't accidentally
 *      collapse two different items into one row).
 *   4. Singleton items get quantity=1 — the pre-stacking client is
 *      compatible because the row shape is unchanged otherwise.
 *   5. `assetCount` in the top-level response equals the sum of
 *      quantities (so the UI's "total" chip can use it directly
 *      without re-summing).
 */
class SteamInventoryControllerSpec extends Specification {

    SteamInventoryService steamInventoryService = Mock()
    SteamSyncService      steamSyncService      = Mock()
    SteamUserRepository   steamUserRepository   = Mock()
    ItemRepository        itemRepository        = Mock()
    ListingRepository     listingRepository     = Mock()
    ListingService        listingService        = Mock()
    TextSanitizer         textSanitizer         = Mock()

    @Subject
    SteamInventoryController controller = new SteamInventoryController(
        steamInventoryService: steamInventoryService,
        steamSyncService     : steamSyncService,
        steamUserRepository  : steamUserRepository,
        itemRepository       : itemRepository,
        listingRepository    : listingRepository,
        listingService       : listingService,
        textSanitizer        : textSanitizer
    )

    HttpServletRequest req = Mock()
    HttpSession        ses = Mock()

    def setup() {
        req.getSession() >> ses
        ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 7L
        steamUserRepository.findById(7L) >> Optional.of(
            new SteamUser(id: 7L, steamId64: '111', lastSyncedAt: 0L))
        // NOTE: `itemRepository.findByNamesLowerIn` is intentionally NOT
        // stubbed here. Spock matches an invocation to the FIRST declared
        // interaction with spare cardinality, so an unlimited stub in
        // setup() can't be overridden by a feature method's `given:` —
        // it would silently swallow the catalogue-enrichment test's
        // `>> [cat]`. Each feature method that reads an inventory stubs
        // findByNamesLowerIn itself.
    }

    private static Map asset(Map override) {
        // Realistic shape returned by SteamInventoryService.fetchInventory —
        // one row per physical asset.
        [
            assetId:    override.assetId,
            classId:    override.classId,
            instanceId: override.instanceId ?: '0',
            name:       override.name ?: 'Lunar Trousers',
            tradable:   override.tradable == null ? true : override.tradable,
            marketable: true,
            type:       'Pants',
            iconUrl:    null,
            imageUrl:   null,
            tags:       []
        ]
    }

    def "collapses 3 identical descriptors into one row with quantity=3"() {
        given:
        itemRepository.findByNamesLowerIn(_) >> []
        steamInventoryService.fetchInventory('111') >> [
            asset(assetId: '1001', classId: '500', name: 'Lunar Trousers'),
            asset(assetId: '1002', classId: '500', name: 'Lunar Trousers'),
            asset(assetId: '1003', classId: '500', name: 'Lunar Trousers')
        ]

        when:
        def resp = controller.inventory(req)
        def body = resp.body
        def items = body.items as List<Map>

        then:
        items.size() == 1
        items[0].quantity == 3
        items[0].assetIds == ['1001', '1002', '1003']
        items[0].assetId in ['1001', '1002', '1003']
        body.count == 1
        body.assetCount == 3
    }

    def "mixed-tradable stack picks a tradable asset as the representative"() {
        given:
        // Two non-tradable copies BEFORE a tradable one — the controller
        // must skip past the locked rows to land on a real assetId we
        // can actually list. Otherwise /api/steam/list would 400 with
        // NOT_TRADABLE the moment the user clicked the card.
        itemRepository.findByNamesLowerIn(_) >> []
        steamInventoryService.fetchInventory('111') >> [
            asset(assetId: '2001', classId: '600', tradable: false),
            asset(assetId: '2002', classId: '600', tradable: false),
            asset(assetId: '2003', classId: '600', tradable: false),
            asset(assetId: '2004', classId: '600', tradable: true),
            asset(assetId: '2005', classId: '600', tradable: false)
        ]

        when:
        def resp = controller.inventory(req)
        def items = resp.body.items as List<Map>

        then:
        items.size() == 1
        items[0].quantity == 5
        items[0].assetId == '2004'  // the lone tradable one
        items[0].assetIds.size() == 5
    }

    def "fully-locked stack still renders, falling back to first asset"() {
        given:
        itemRepository.findByNamesLowerIn(_) >> []
        steamInventoryService.fetchInventory('111') >> [
            asset(assetId: '3001', classId: '700', tradable: false),
            asset(assetId: '3002', classId: '700', tradable: false)
        ]

        when:
        def resp = controller.inventory(req)
        def items = resp.body.items as List<Map>

        then:
        items.size() == 1
        items[0].quantity == 2
        items[0].assetId == '3001'
        items[0].tradable == false
    }

    def "distinct descriptors stay distinct"() {
        given:
        itemRepository.findByNamesLowerIn(_) >> []
        steamInventoryService.fetchInventory('111') >> [
            asset(assetId: '4001', classId: '800', name: 'Lunar Mask 2026'),
            asset(assetId: '4002', classId: '800', name: 'Lunar Mask 2026'),
            asset(assetId: '4003', classId: '801', name: 'Crossbody Bag Shirt')
        ]

        when:
        def resp = controller.inventory(req)
        def items = (resp.body.items as List<Map>).sort { it.assetId }

        then:
        items.size() == 2
        items.find { it.assetId == '4001' }?.quantity == 2
        items.find { it.assetId == '4003' }?.quantity == 1
        resp.body.assetCount == 3
    }

    def "singleton gets quantity=1 and a single-element assetIds array"() {
        given:
        itemRepository.findByNamesLowerIn(_) >> []
        steamInventoryService.fetchInventory('111') >> [
            asset(assetId: '5001', classId: '900', name: 'Just One Hat')
        ]

        when:
        def resp = controller.inventory(req)
        def items = resp.body.items as List<Map>

        then:
        items.size() == 1
        items[0].quantity == 1
        items[0].assetIds == ['5001']
        items[0].assetId == '5001'
        resp.body.assetCount == 1
    }

    def "empty inventory still returns count=0 and assetCount=0"() {
        given:
        steamInventoryService.fetchInventory('111') >> []

        when:
        def resp = controller.inventory(req)

        then:
        resp.body.items == []
        resp.body.count == 0
        resp.body.assetCount == 0
    }

    // ── ownership: a user only ever reads their OWN inventory ──────

    def "inventory fetches against the SIGNED-IN user's steamId64, never an arbitrary one"() {
        given: "the session user resolves to steamId64 '111'"
        steamInventoryService.fetchInventory(_) >> []

        when:
        controller.inventory(req)

        then: "the fetch is keyed on the session user's id — no request param can redirect it"
        1 * steamInventoryService.fetchInventory('111')
    }

    def "inventory rejects an unauthenticated session with 401"() {
        given:
        HttpServletRequest anon = Mock()
        HttpSession anonSes = Mock()
        anon.getSession() >> anonSes
        anonSes.getAttribute(SteamAuthController.SESSION_USER_ID) >> null

        when:
        controller.inventory(anon)

        then:
        thrown(com.sboxmarket.exception.UnauthorizedException)
    }

    def "inventory 401s when the session user id no longer maps to a row"() {
        given:
        HttpServletRequest ghost = Mock()
        HttpSession ghostSes = Mock()
        ghost.getSession() >> ghostSes
        ghostSes.getAttribute(SteamAuthController.SESSION_USER_ID) >> 404L
        steamUserRepository.findById(404L) >> Optional.empty()

        when:
        controller.inventory(ghost)

        then:
        thrown(com.sboxmarket.exception.UnauthorizedException)
    }

    // ── rate-limit / private-inventory signalling ─────────────────

    def "inventory surfaces a rate_limited block when the fetch came back empty + blocked"() {
        given: "fetchInventory returns [] AND the negative cache reports a block"
        long until = System.currentTimeMillis() + 200_000L
        steamInventoryService.fetchInventory('111') >> []
        // Pre-fetch probe null, post-fetch probe trips (first 429 of the window).
        steamInventoryService.blockedUntilMs('111') >>> [null, until]

        when:
        def body = controller.inventory(req).body

        then:
        body.blocked == true
        body.blockedUntil == until
        body.reason == 'rate_limited'
        body.retryInSec >= 1
    }

    def "inventory does NOT mark blocked when the empty result is a genuine empty inventory"() {
        given:
        steamInventoryService.fetchInventory('111') >> []
        steamInventoryService.blockedUntilMs('111') >> null

        when:
        def body = controller.inventory(req).body

        then: "no false 'rate limited' banner on a legitimately empty inventory"
        body.blocked == null
        body.reason == null
        body.count == 0
    }

    def "inventory does NOT mark blocked when items came back even if a block is also cached"() {
        given: "a stale block can linger after a successful refetch — items present wins"
        itemRepository.findByNamesLowerIn(_) >> []
        steamInventoryService.fetchInventory('111') >> [
            asset(assetId: '9001', classId: '999', name: 'Wizard Hat')
        ]
        steamInventoryService.blockedUntilMs('111') >> (System.currentTimeMillis() + 100_000L)

        when:
        def body = controller.inventory(req).body

        then: "the block signal only applies to an EMPTY result set"
        body.blocked == null
        body.count == 1
    }

    // ── catalogue enrichment ──────────────────────────────────────

    def "inventory enriches a known item with its catalogue category / rarity / price"() {
        given:
        def cat = new Item(id: 55L, name: 'Wizard Hat', category: 'Hats',
            rarity: 'Limited', lowestPrice: new BigDecimal('12.50'))
        steamInventoryService.fetchInventory('111') >> [
            asset(assetId: '6001', classId: '600', name: 'Wizard Hat')
        ]
        itemRepository.findByNamesLowerIn(_) >> [cat]

        when:
        def item = (controller.inventory(req).body.items as List<Map>)[0]

        then:
        item.catalogueId == 55L
        item.category == 'Hats'
        item.rarity == 'Limited'
        item.suggestedPrice == new BigDecimal('12.50')
    }

    def "inventory falls back to inferred category + Standard rarity for an unknown item"() {
        given:
        steamInventoryService.fetchInventory('111') >> [
            asset(assetId: '7001', classId: '700', name: 'Brand New Cap')
        ]
        itemRepository.findByNamesLowerIn(_) >> []
        steamInventoryService.inferCategory(_) >> 'Hats'

        when:
        def item = (controller.inventory(req).body.items as List<Map>)[0]

        then:
        item.catalogueId == null
        item.category == 'Hats'
        item.rarity == 'Standard'
        item.suggestedPrice == BigDecimal.ZERO
    }

    // ── POST /sync delegates to SteamSyncService.syncNow ──────────

    def "sync delegates to syncNow with the signed-in user id"() {
        when:
        def resp = controller.sync(req)

        then:
        1 * steamSyncService.syncNow(7L) >> [ok: true, inventorySize: 3]
        resp.body.ok == true
    }

    // ── POST /list: validation + ownership ────────────────────────

    def "list rejects a missing assetId"() {
        when:
        controller.listFromSteam([price: '5'], req)

        then:
        def e = thrown(com.sboxmarket.exception.BadRequestException)
        e.code == 'INVALID_ASSET'
    }

    def "list rejects a non-numeric assetId before any inventory lookup"() {
        when:
        controller.listFromSteam([assetId: '12; DROP TABLE', price: '5'], req)

        then:
        def e = thrown(com.sboxmarket.exception.BadRequestException)
        e.code == 'INVALID_ASSET'
        // The malformed id must never reach the inventory probe.
        0 * steamInventoryService.fetchInventory(_)
    }

    @Unroll
    def "list rejects price '#price' with #expectedCode"() {
        when:
        controller.listFromSteam([assetId: '1001', price: price], req)

        then:
        def e = thrown(com.sboxmarket.exception.BadRequestException)
        e.code == expectedCode

        where:
        price       | expectedCode
        null        | 'INVALID_PRICE'
        '0'         | 'INVALID_PRICE'
        '-5'        | 'INVALID_PRICE'
        '0.004'     | 'INVALID_PRICE'   // sub-cent → would round to $0.00 in NUMERIC(10,2); floored at $0.01
        '0.009'     | 'INVALID_PRICE'
        'abc'       | 'INVALID_PRICE'
        '100000.01' | 'PRICE_TOO_HIGH'
    }

    def "list rejects an asset the signed-in user does not currently own"() {
        given: "the user's live inventory does not contain the requested asset"
        steamInventoryService.fetchInventory('111') >> [
            asset(assetId: '1001', classId: '500', name: 'Wizard Hat')
        ]

        when:
        controller.listFromSteam([assetId: '9999', price: '5'], req)

        then:
        def e = thrown(com.sboxmarket.exception.BadRequestException)
        e.code == 'NOT_IN_INVENTORY'
    }

    def "list rejects a non-tradable asset"() {
        given:
        steamInventoryService.fetchInventory('111') >> [
            asset(assetId: '1001', classId: '500', name: 'Wizard Hat', tradable: false)
        ]

        when:
        controller.listFromSteam([assetId: '1001', price: '5'], req)

        then:
        def e = thrown(com.sboxmarket.exception.BadRequestException)
        e.code == 'NOT_TRADABLE'
    }

    def "list auto-creates a catalogue item for a brand-new cosmetic and lists it"() {
        given:
        steamInventoryService.fetchInventory('111') >> [
            asset(assetId: '1001', classId: '500', name: 'Wizard Hat', tradable: true)
        ]
        steamInventoryService.inferCategory(_) >> 'Hats'
        itemRepository.findByNameIgnoreCase('Wizard Hat') >> null
        listingService.createListing(_) >> { args -> args[0].tap { it.id = 7001L } }

        when:
        def resp = controller.listFromSteam([assetId: '1001', price: '9.99'], req)

        then: "a new Item row is persisted before the listing is created"
        1 * itemRepository.save({ it.name == 'Wizard Hat' && it.category == 'Hats' }) >> { args -> args[0].tap { it.id = 88L } }
        resp.body.itemId == 88L
        resp.body.listingId == 7001L
        resp.body.listingType == 'BUY_NOW'
    }

    def "list persists the Steam item's real render URL onto the auto-created catalogue item"() {
        // The Steam-listing path is where REAL renders enter the catalogue:
        // listFromSteam copies the inventory row's iconUrl (already a full
        // Steam economy CDN URL from SteamInventoryService.mapInventoryJson)
        // onto the new Item.imageUrl so the card + detail view show the genuine
        // render instead of the emoji fallback. Without this the field would be
        // null and every freshly-listed Steam item would fall back to a glyph.
        given:
        def cdnUrl = 'https://steamcommunity-a.akamaihd.net/economy/image/tok_abc/330x192'
        steamInventoryService.fetchInventory('111') >> [
            asset(assetId: '1001', classId: '500', name: 'Wizard Hat', tradable: true)
                .tap { it.iconUrl = cdnUrl; it.imageUrl = cdnUrl }
        ]
        steamInventoryService.inferCategory(_) >> 'Hats'
        itemRepository.findByNameIgnoreCase('Wizard Hat') >> null
        listingService.createListing(_) >> { args -> args[0].tap { it.id = 7011L } }

        when:
        def resp = controller.listFromSteam([assetId: '1001', price: '9.99'], req)

        then: "the auto-created Item carries the Steam render URL as its imageUrl"
        1 * itemRepository.save({ it.name == 'Wizard Hat' && it.imageUrl == cdnUrl }) >> { args -> args[0].tap { it.id = 90L } }
        resp.statusCode.value() == 200
        resp.body.itemId == 90L
    }

    def "list reuses an existing catalogue item rather than creating a duplicate"() {
        given:
        def existing = new Item(id: 12L, name: 'Wizard Hat', category: 'Hats', rarity: 'Standard')
        steamInventoryService.fetchInventory('111') >> [
            asset(assetId: '1001', classId: '500', name: 'Wizard Hat', tradable: true)
        ]
        itemRepository.findByNameIgnoreCase('Wizard Hat') >> existing
        listingService.createListing(_) >> { args -> args[0].tap { it.id = 7002L } }

        when:
        def resp = controller.listFromSteam([assetId: '1001', price: '4.00'], req)

        then: "no Item.save — the catalogue row already exists"
        0 * itemRepository.save(_)
        resp.body.itemId == 12L
    }

    def "list AUCTION requires a durationHours in [1,168]"() {
        given:
        steamInventoryService.fetchInventory('111') >> [
            asset(assetId: '1001', classId: '500', name: 'Wizard Hat', tradable: true)
        ]
        itemRepository.findByNameIgnoreCase(_) >> new Item(id: 12L, name: 'Wizard Hat')

        when:
        controller.listFromSteam([assetId: '1001', price: '4.00',
            listingType: 'AUCTION', durationHours: dur], req)

        then:
        def e = thrown(com.sboxmarket.exception.BadRequestException)
        e.code == expectedCode

        where:
        dur   | expectedCode
        null  | 'DURATION_REQUIRED'
        '0'   | 'INVALID_DURATION'
        '169' | 'INVALID_DURATION'
        'xx'  | 'INVALID_DURATION'
    }

    // ── POST /list-bulk: per-asset isolation ──────────────────────

    def "list-bulk rejects a non-array assetIds payload"() {
        when:
        controller.listBulkFromSteam([assetIds: 'not-a-list', price: '5'], req)

        then:
        def e = thrown(com.sboxmarket.exception.BadRequestException)
        e.code == 'INVALID_BODY'
    }

    def "list-bulk rejects more than 20 assets"() {
        when:
        controller.listBulkFromSteam([assetIds: (1..21).collect { it.toString() }, price: '5'], req)

        then:
        def e = thrown(com.sboxmarket.exception.BadRequestException)
        e.code == 'TOO_MANY'
    }

    def "list-bulk filters non-numeric ids and rejects a wholly-invalid list"() {
        when:
        controller.listBulkFromSteam([assetIds: ['abc', '', null, '1; DROP'], price: '5'], req)

        then:
        def e = thrown(com.sboxmarket.exception.BadRequestException)
        e.code == 'INVALID_BODY'
    }

    def "list-bulk isolates a failing asset — one bad item never aborts the batch"() {
        given: "two assets requested; only one is in the live inventory"
        steamInventoryService.fetchInventory('111') >> [
            asset(assetId: '1001', classId: '500', name: 'Wizard Hat', tradable: true)
        ]
        itemRepository.findByNameIgnoreCase('Wizard Hat') >> new Item(id: 12L, name: 'Wizard Hat')
        listingService.createListing(_) >> { args -> args[0].tap { it.id = 7003L } }

        when:
        def body = controller.listBulkFromSteam([assetIds: ['1001', '2002'], price: '5'], req).body

        then: "the good asset lists; the missing one lands in failed[] with a code"
        body.ok.size() == 1
        body.ok[0].assetId == '1001'
        body.failed.size() == 1
        body.failed[0].assetId == '2002'
        body.failed[0].code == 'NOT_IN_INVENTORY'
    }

    def "list-bulk reports NOT_TRADABLE for a locked asset without aborting the rest"() {
        given:
        steamInventoryService.fetchInventory('111') >> [
            asset(assetId: '1001', classId: '500', name: 'Wizard Hat', tradable: true),
            asset(assetId: '2002', classId: '500', name: 'Wizard Hat', tradable: false)
        ]
        itemRepository.findByNameIgnoreCase('Wizard Hat') >> new Item(id: 12L, name: 'Wizard Hat')
        listingService.createListing(_) >> { args -> args[0].tap { it.id = 7004L } }

        when:
        def body = controller.listBulkFromSteam([assetIds: ['1001', '2002'], price: '5'], req).body

        then:
        body.ok.size() == 1
        body.failed.size() == 1
        body.failed[0].code == 'NOT_TRADABLE'
    }

    def "list-bulk fetches the inventory exactly ONCE for the whole batch"() {
        given:
        itemRepository.findByNameIgnoreCase(_) >> { args -> new Item(id: 12L, name: args[0] as String) }
        listingService.createListing(_) >> { args -> args[0].tap { it.id = 7005L } }

        when:
        controller.listBulkFromSteam([assetIds: ['1001', '2002'], price: '5'], req)

        then: "one outbound inventory probe, not one per asset"
        1 * steamInventoryService.fetchInventory('111') >> [
            asset(assetId: '1001', classId: '500', name: 'Wizard Hat', tradable: true),
            asset(assetId: '2002', classId: '501', name: 'Cargo Pants', tradable: true)
        ]
    }

    // ── /list: assetId hardening ──────────────────────────────────

    def "list rejects an over-long numeric assetId before the inventory probe"() {
        given: "a 33-digit numeric string — passes the digits regex but exceeds the 32-char cap"
        def longId = '1' * 33

        when:
        controller.listFromSteam([assetId: longId, price: '5'], req)

        then:
        def e = thrown(com.sboxmarket.exception.BadRequestException)
        e.code == 'INVALID_ASSET'
        // The crafted id must never reach the upstream inventory probe.
        0 * steamInventoryService.fetchInventory(_)
    }

    // ── /list: AUCTION happy path ─────────────────────────────────

    def "list AUCTION with a valid durationHours sets expiresAt in the future"() {
        given:
        steamInventoryService.fetchInventory('111') >> [
            asset(assetId: '1001', classId: '500', name: 'Wizard Hat', tradable: true)
        ]
        itemRepository.findByNameIgnoreCase('Wizard Hat') >> new Item(id: 12L, name: 'Wizard Hat')
        long before = System.currentTimeMillis()

        when:
        def resp = controller.listFromSteam([assetId: '1001', price: '4.00',
            listingType: 'AUCTION', durationHours: '24'], req)

        then: "the persisted listing carries an AUCTION type and an expiry ~24h out"
        1 * listingService.createListing({
            it.listingType == 'AUCTION' &&
            it.expiresAt != null &&
            it.expiresAt >= before + (24L * 60L * 60L * 1000L)
        }) >> { args -> args[0].tap { it.id = 8001L } }
        resp.body.listingType == 'AUCTION'
        resp.body.expiresAt != null
    }

    // ── /list: buyNowPrice rules (auction-only ceiling) ───────────

    def "list rejects buyNowPrice on a BUY_NOW listing"() {
        given:
        steamInventoryService.fetchInventory('111') >> [
            asset(assetId: '1001', classId: '500', name: 'Wizard Hat', tradable: true)
        ]
        itemRepository.findByNameIgnoreCase(_) >> new Item(id: 12L, name: 'Wizard Hat')

        when: "buyNowPrice supplied but listingType is the default BUY_NOW"
        controller.listFromSteam([assetId: '1001', price: '5', buyNowPrice: '20'], req)

        then:
        def e = thrown(com.sboxmarket.exception.BadRequestException)
        e.code == 'BUY_NOW_ON_BUY_NOW'
    }

    def "list rejects an auction buyNowPrice that does not exceed the starting bid"() {
        given:
        steamInventoryService.fetchInventory('111') >> [
            asset(assetId: '1001', classId: '500', name: 'Wizard Hat', tradable: true)
        ]
        itemRepository.findByNameIgnoreCase(_) >> new Item(id: 12L, name: 'Wizard Hat')

        when: "buyNowPrice equals the starting bid — Buy Now would never beat the first bid"
        controller.listFromSteam([assetId: '1001', price: '10',
            listingType: 'AUCTION', durationHours: '24', buyNowPrice: '10'], req)

        then:
        def e = thrown(com.sboxmarket.exception.BadRequestException)
        e.code == 'INVALID_BUY_NOW'
    }

    def "list accepts an auction buyNowPrice above the starting bid"() {
        given:
        steamInventoryService.fetchInventory('111') >> [
            asset(assetId: '1001', classId: '500', name: 'Wizard Hat', tradable: true)
        ]
        itemRepository.findByNameIgnoreCase(_) >> new Item(id: 12L, name: 'Wizard Hat')

        when:
        def resp = controller.listFromSteam([assetId: '1001', price: '10',
            listingType: 'AUCTION', durationHours: '48', buyNowPrice: '50'], req)

        then: "the buyNowPrice survives onto the persisted listing"
        1 * listingService.createListing({
            it.buyNowPrice == new BigDecimal('50')
        }) >> { args -> args[0].tap { it.id = 8002L } }
        resp.body.buyNowPrice == new BigDecimal('50')
    }

    def "list rejects an auction buyNowPrice over the 100k ceiling"() {
        given:
        steamInventoryService.fetchInventory('111') >> [
            asset(assetId: '1001', classId: '500', name: 'Wizard Hat', tradable: true)
        ]
        itemRepository.findByNameIgnoreCase(_) >> new Item(id: 12L, name: 'Wizard Hat')

        when:
        controller.listFromSteam([assetId: '1001', price: '10',
            listingType: 'AUCTION', durationHours: '24', buyNowPrice: '100000.01'], req)

        then:
        def e = thrown(com.sboxmarket.exception.BadRequestException)
        e.code == 'BUY_NOW_TOO_HIGH'
    }

    // ── /list: description + maxDiscount validation ───────────────

    def "list rejects a description longer than 500 chars"() {
        given:
        steamInventoryService.fetchInventory('111') >> [
            asset(assetId: '1001', classId: '500', name: 'Wizard Hat', tradable: true)
        ]
        itemRepository.findByNameIgnoreCase(_) >> new Item(id: 12L, name: 'Wizard Hat')

        when:
        controller.listFromSteam([assetId: '1001', price: '5', description: 'x' * 501], req)

        then:
        def e = thrown(com.sboxmarket.exception.BadRequestException)
        e.code == 'DESCRIPTION_TOO_LONG'
        // An over-long note must be rejected before it reaches the sanitiser.
        0 * textSanitizer.clean(_, _)
    }

    def "list sanitises an in-range description before persisting it"() {
        given:
        steamInventoryService.fetchInventory('111') >> [
            asset(assetId: '1001', classId: '500', name: 'Wizard Hat', tradable: true)
        ]
        itemRepository.findByNameIgnoreCase(_) >> new Item(id: 12L, name: 'Wizard Hat')
        textSanitizer.clean('Great hat', 500) >> 'Great hat (clean)'

        when:
        controller.listFromSteam([assetId: '1001', price: '5', description: 'Great hat'], req)

        then: "the sanitiser output — not the raw text — lands on the listing"
        1 * listingService.createListing({
            it.description == 'Great hat (clean)'
        }) >> { args -> args[0].tap { it.id = 8003L } }
    }

    @Unroll
    def "list rejects maxDiscount '#disc' with INVALID_DISCOUNT"() {
        given:
        steamInventoryService.fetchInventory('111') >> [
            asset(assetId: '1001', classId: '500', name: 'Wizard Hat', tradable: true)
        ]
        itemRepository.findByNameIgnoreCase(_) >> new Item(id: 12L, name: 'Wizard Hat')

        when:
        controller.listFromSteam([assetId: '1001', price: '5', maxDiscount: disc], req)

        then:
        def e = thrown(com.sboxmarket.exception.BadRequestException)
        e.code == 'INVALID_DISCOUNT'

        where:
        disc << ['-0.1', '1', '1.5', 'notanumber']
    }

    def "list treats a zero maxDiscount as 'no auto-accept' (null on the listing)"() {
        given:
        steamInventoryService.fetchInventory('111') >> [
            asset(assetId: '1001', classId: '500', name: 'Wizard Hat', tradable: true)
        ]
        itemRepository.findByNameIgnoreCase(_) >> new Item(id: 12L, name: 'Wizard Hat')

        when:
        controller.listFromSteam([assetId: '1001', price: '5', maxDiscount: '0'], req)

        then: "0 collapses to null — it is not stored as an active auto-accept threshold"
        1 * listingService.createListing({ it.maxDiscount == null }) >> { args -> args[0].tap { it.id = 8004L } }
    }

    // ── /list-bulk: maxDiscount propagation ───────────────────────

    def "list-bulk applies a valid maxDiscount uniformly to every listing in the batch"() {
        given:
        steamInventoryService.fetchInventory('111') >> [
            asset(assetId: '1001', classId: '500', name: 'Wizard Hat', tradable: true),
            asset(assetId: '2002', classId: '501', name: 'Cargo Pants', tradable: true)
        ]
        itemRepository.findByNameIgnoreCase(_) >> { args -> new Item(id: 12L, name: args[0] as String) }

        when:
        def body = controller.listBulkFromSteam(
            [assetIds: ['1001', '2002'], price: '5', maxDiscount: '0.25'], req).body

        then: "both listings carry the batch-wide discount fraction"
        2 * listingService.createListing({
            it.maxDiscount == new BigDecimal('0.25')
        }) >> { args -> args[0].tap { it.id = 9100L } }
        body.ok.size() == 2
    }

    def "list-bulk rejects an out-of-range maxDiscount before doing any work"() {
        when:
        controller.listBulkFromSteam([assetIds: ['1001'], price: '5', maxDiscount: '1.5'], req)

        then:
        def e = thrown(com.sboxmarket.exception.BadRequestException)
        e.code == 'INVALID_DISCOUNT'
        // A bad batch-wide param must abort up front — no inventory probe,
        // no partial listings.
        0 * steamInventoryService.fetchInventory(_)
        0 * listingService.createListing(_)
    }

    // ── auth: every write path is session-gated ───────────────────

    def "sync rejects an unauthenticated session with 401"() {
        given:
        HttpServletRequest anon = Mock()
        HttpSession anonSes = Mock()
        anon.getSession() >> anonSes
        anonSes.getAttribute(SteamAuthController.SESSION_USER_ID) >> null

        when:
        controller.sync(anon)

        then:
        thrown(com.sboxmarket.exception.UnauthorizedException)
        // An anonymous caller must never trigger a sync.
        0 * steamSyncService.syncNow(_)
    }

    def "list rejects an unauthenticated session before touching the inventory"() {
        given:
        HttpServletRequest anon = Mock()
        HttpSession anonSes = Mock()
        anon.getSession() >> anonSes
        anonSes.getAttribute(SteamAuthController.SESSION_USER_ID) >> null

        when:
        controller.listFromSteam([assetId: '1001', price: '5'], anon)

        then:
        thrown(com.sboxmarket.exception.UnauthorizedException)
        0 * steamInventoryService.fetchInventory(_)
        0 * listingService.createListing(_)
    }

    def "list-bulk rejects an unauthenticated session before touching the inventory"() {
        given:
        HttpServletRequest anon = Mock()
        HttpSession anonSes = Mock()
        anon.getSession() >> anonSes
        anonSes.getAttribute(SteamAuthController.SESSION_USER_ID) >> null

        when:
        controller.listBulkFromSteam([assetIds: ['1001'], price: '5'], anon)

        then:
        thrown(com.sboxmarket.exception.UnauthorizedException)
        0 * steamInventoryService.fetchInventory(_)
        0 * listingService.createListing(_)
    }

    def "list 401s when the session user id no longer maps to a row"() {
        given:
        HttpServletRequest ghost = Mock()
        HttpSession ghostSes = Mock()
        ghost.getSession() >> ghostSes
        ghostSes.getAttribute(SteamAuthController.SESSION_USER_ID) >> 404L
        steamUserRepository.findById(404L) >> Optional.empty()

        when:
        controller.listFromSteam([assetId: '1001', price: '5'], ghost)

        then:
        thrown(com.sboxmarket.exception.UnauthorizedException)
    }

    def "list lists strictly against the SIGNED-IN user's steamId64 — body cannot redirect it"() {
        given: "the request body carries no user identifier; the session decides steamId64='111'"
        itemRepository.findByNameIgnoreCase(_) >> new Item(id: 12L, name: 'Wizard Hat')
        listingService.createListing(_) >> { args -> args[0].tap { it.id = 8100L } }

        when:
        controller.listFromSteam([assetId: '1001', price: '5'], req)

        then: "the ownership probe is keyed on the session user's steamId64 only"
        1 * steamInventoryService.fetchInventory('111') >> [
            asset(assetId: '1001', classId: '500', name: 'Wizard Hat', tradable: true)
        ]
    }

    def "created listing is always stamped with the session user's id as the seller"() {
        given: "no sellerUserId anywhere in the request body"
        steamInventoryService.fetchInventory('111') >> [
            asset(assetId: '1001', classId: '500', name: 'Wizard Hat', tradable: true)
        ]
        itemRepository.findByNameIgnoreCase(_) >> new Item(id: 12L, name: 'Wizard Hat')

        when:
        controller.listFromSteam([assetId: '1001', price: '5', sellerUserId: 999L], req)

        then: "a forged sellerUserId in the body is ignored — the seller is the session user (7)"
        1 * listingService.createListing({ it.sellerUserId == 7L }) >> { args -> args[0].tap { it.id = 8101L } }
    }

    // ── ban gate: SellService.relist's banGuard does NOT cover the ─
    //    Steam-inventory list paths because those call
    //    listingService.createListing() directly, skipping SellService.
    //    The controller has to apply its own guard or a banned user can
    //    keep posting fresh inventory while suspended. Pin the contract.

    def "list rejects a banned caller before touching inventory or listings"() {
        given:
        def banGuard = Mock(com.sboxmarket.service.security.BanGuard)
        controller.banGuard = banGuard

        when:
        controller.listFromSteam([assetId: '1001', price: '5'], req)

        then: "the guard fires for the session user before any work"
        1 * banGuard.assertNotBanned(7L) >> { throw new com.sboxmarket.exception.ForbiddenException("Your account is banned") }
        thrown(com.sboxmarket.exception.ForbiddenException)
        0 * steamInventoryService.fetchInventory(_)
        0 * listingService.createListing(_)
    }

    def "list-bulk rejects a banned caller before touching inventory or listings"() {
        given:
        def banGuard = Mock(com.sboxmarket.service.security.BanGuard)
        controller.banGuard = banGuard

        when:
        controller.listBulkFromSteam([assetIds: ['1001', '2002'], price: '5'], req)

        then:
        1 * banGuard.assertNotBanned(7L) >> { throw new com.sboxmarket.exception.ForbiddenException("Your account is banned") }
        thrown(com.sboxmarket.exception.ForbiddenException)
        0 * steamInventoryService.fetchInventory(_)
        0 * listingService.createListing(_)
    }

    // ── double-list → double-sell guard ───────────────────────────
    //   A seller must not be able to create two LIVE listings for the SAME
    //   physical asset (with escrow disabled both go ACTIVE → both could sell
    //   → paid twice for one undeliverable copy). Guard keys on assetId.

    def "list rejects a SECOND live listing of the same asset (ALREADY_LISTED)"() {
        given: "the asset is owned + tradable, but a live listing for it already exists"
        steamInventoryService.fetchInventory('111') >> [
            asset(assetId: '1001', classId: '500', name: 'Wizard Hat', tradable: true)
        ]
        listingRepository.existsBySellerUserIdAndAssetIdAndStatusIn(7L, '1001', _) >> true

        when:
        controller.listFromSteam([assetId: '1001', price: '5'], req)

        then: "rejected before any catalogue/listing write"
        def e = thrown(com.sboxmarket.exception.BadRequestException)
        e.code == 'ALREADY_LISTED'
        0 * listingService.createListing(_)
    }

    def "list persists the Steam assetId onto the created listing"() {
        given:
        steamInventoryService.fetchInventory('111') >> [
            asset(assetId: '1001', classId: '500', name: 'Wizard Hat', tradable: true)
        ]
        itemRepository.findByNameIgnoreCase(_) >> new Item(id: 12L, name: 'Wizard Hat')

        when:
        controller.listFromSteam([assetId: '1001', price: '5'], req)

        then: "the assetId is recorded so the duplicate guard can see it next time"
        1 * listingService.createListing({ it.assetId == '1001' }) >> { args -> args[0].tap { it.id = 8200L } }
    }

    def "list-bulk reports ALREADY_LISTED for an already-listed asset without aborting the batch"() {
        given: "both owned + tradable; only 1001 already has a live listing"
        steamInventoryService.fetchInventory('111') >> [
            asset(assetId: '1001', classId: '500', name: 'Wizard Hat', tradable: true),
            asset(assetId: '2002', classId: '501', name: 'Cargo Pants', tradable: true)
        ]
        itemRepository.findByNameIgnoreCase(_) >> { args -> new Item(id: 12L, name: args[0] as String) }
        listingService.createListing(_) >> { args -> args[0].tap { it.id = 8300L } }
        listingRepository.existsBySellerUserIdAndAssetIdAndStatusIn(7L, '1001', _) >> true
        listingRepository.existsBySellerUserIdAndAssetIdAndStatusIn(7L, '2002', _) >> false

        when:
        def body = controller.listBulkFromSteam([assetIds: ['1001', '2002'], price: '5'], req).body

        then: "2002 lists; 1001 lands in failed[] as ALREADY_LISTED"
        body.ok.size() == 1
        body.ok[0].assetId == '2002'
        body.failed.size() == 1
        body.failed[0].assetId == '1001'
        body.failed[0].code == 'ALREADY_LISTED'
    }

    def "list-bulk collapses a duplicate assetId in the payload to a single listing"() {
        given: "the same asset id appears twice in the request; no prior live listing"
        steamInventoryService.fetchInventory('111') >> [
            asset(assetId: '1001', classId: '500', name: 'Wizard Hat', tradable: true)
        ]
        itemRepository.findByNameIgnoreCase(_) >> new Item(id: 12L, name: 'Wizard Hat')
        listingRepository.existsBySellerUserIdAndAssetIdAndStatusIn(_, _, _) >> false

        when: "['1001','1001'] is de-duplicated up front by the .unique() on the id list"
        def body = controller.listBulkFromSteam([assetIds: ['1001', '1001'], price: '5'], req).body

        then: "exactly one listing is created — the duplicate never reaches the loop"
        1 * listingService.createListing(_) >> { args -> args[0].tap { it.id = 8301L } }
        body.ok.size() == 1
        body.failed.size() == 0
    }
}
