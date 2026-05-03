package com.sboxmarket

import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.controller.SteamInventoryController
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.ListingService
import com.sboxmarket.service.SteamInventoryService
import com.sboxmarket.service.SteamSyncService
import com.sboxmarket.service.TextSanitizer
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import spock.lang.Specification
import spock.lang.Subject

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
    ListingService        listingService        = Mock()
    TextSanitizer         textSanitizer         = Mock()

    @Subject
    SteamInventoryController controller = new SteamInventoryController(
        steamInventoryService: steamInventoryService,
        steamSyncService     : steamSyncService,
        steamUserRepository  : steamUserRepository,
        itemRepository       : itemRepository,
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
        // Default — no catalogue matches. Specific tests can override.
        itemRepository.findByNamesLowerIn(_) >> []
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
}
