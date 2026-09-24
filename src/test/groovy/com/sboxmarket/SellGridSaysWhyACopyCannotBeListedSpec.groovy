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

/**
 * <b>The Sell grid must say which copies are already on sale — and must hand
 * the pick flow a copy that is actually free.</b>
 *
 * <h3>What a seller hit before this</h3>
 *
 * A listed asset does not leave the seller's Steam inventory, so every copy
 * keeps coming back from {@code GET /api/steam/inventory}. The stack-grouping
 * added for the "44 rows, 327 items" fix nominated ONE representative asset
 * per descriptor — {@code g.tradableAssetId}, the FIRST tradable copy — and
 * recomputed the same one on every fetch.
 *
 * So a seller holding fifty Lunar Trousers lists one. The grid still shows
 * "×50", and the quantity badge's own tooltip says <i>"Listing creates one
 * listing per click — repeat to list more"</i>. Every repeat POSTs the same
 * assetId, and {@code listFromSteam}'s double-list guard answers
 * {@code ALREADY_LISTED — "You already have an active listing for this item.
 * Cancel it before listing it again."} for forty-nine copies he has never
 * listed. The error is true of the asset and false of the question.
 *
 * Nothing on the grid distinguished "this copy is on sale" from "this copy is
 * available", which is this codebase's recurring defect in its sell-side form:
 * a missing signal rendered as a healthy one.
 *
 * <h3>What is pinned here</h3>
 * <ol>
 *   <li>A partly-listed stack nominates a FREE copy, and reports how many are
 *       listed and how many remain listable.</li>
 *   <li>A fully-listed stack says so up front ({@code unlistableReason}) rather
 *       than letting the seller price it and be refused on submit.</li>
 *   <li>A locked stack keeps saying NOT_TRADABLE — the pre-existing reason must
 *       not be swallowed by the new one.</li>
 *   <li>The grid asks about exactly the statuses the double-list guard counts,
 *       so the two cannot drift apart.</li>
 *   <li>The row carries {@code steamPrice}, without which the sell form has no
 *       second price anchor and prints "Suggested price: $0.00".</li>
 * </ol>
 */
class SellGridSaysWhyACopyCannotBeListedSpec extends Specification {

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
    }

    private static Map asset(Map override) {
        [
            assetId:    override.assetId,
            classId:    override.classId ?: '500',
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

    def "a stack with one copy already listed nominates a DIFFERENT copy"() {
        given: 'three identical copies, the first of which is already on sale'
        itemRepository.findByNamesLowerIn(_) >> []
        steamInventoryService.fetchInventory('111') >> [
            asset(assetId: '1001'), asset(assetId: '1002'), asset(assetId: '1003')
        ]
        listingRepository.findLiveAssetIdsBySeller(7L, _) >> ['1001']

        when:
        def row = (controller.inventory(req).body.items as List<Map>)[0]

        then: 'the representative is a copy he can actually list'
        row.assetId != '1001'
        row.assetId in ['1002', '1003']

        and: 'and the row says how much of the stack is already up'
        row.quantity == 3
        row.listedCount == 1
        row.listableQuantity == 2
        row.unlistableReason == null
    }

    def "a fully-listed stack is marked ALREADY_LISTED before the seller prices it"() {
        given:
        itemRepository.findByNamesLowerIn(_) >> []
        steamInventoryService.fetchInventory('111') >> [
            asset(assetId: '2001'), asset(assetId: '2002')
        ]
        listingRepository.findLiveAssetIdsBySeller(7L, _) >> ['2001', '2002']

        when:
        def row = (controller.inventory(req).body.items as List<Map>)[0]

        then:
        row.listedCount == 2
        row.listableQuantity == 0
        row.unlistableReason == 'ALREADY_LISTED'

        and: 'it still nominates a real asset, so the row renders and the POST\'s own refusal stays the truthful one'
        row.assetId in ['2001', '2002']
    }

    def "a locked stack still reports NOT_TRADABLE, not ALREADY_LISTED"() {
        given: 'nothing of his is listed; Steam simply will not move these'
        itemRepository.findByNamesLowerIn(_) >> []
        steamInventoryService.fetchInventory('111') >> [
            asset(assetId: '3001', tradable: false),
            asset(assetId: '3002', tradable: false)
        ]
        listingRepository.findLiveAssetIdsBySeller(7L, _) >> []

        when:
        def row = (controller.inventory(req).body.items as List<Map>)[0]

        then:
        row.unlistableReason == 'NOT_TRADABLE'
        row.listedCount == 0
        row.listableQuantity == 0
    }

    def "a listed copy is not counted as listable even when other copies are locked"() {
        given: 'one tradable copy (already listed) and one locked copy'
        itemRepository.findByNamesLowerIn(_) >> []
        steamInventoryService.fetchInventory('111') >> [
            asset(assetId: '4001', tradable: true),
            asset(assetId: '4002', tradable: false)
        ]
        listingRepository.findLiveAssetIdsBySeller(7L, _) >> ['4001']

        when:
        def row = (controller.inventory(req).body.items as List<Map>)[0]

        then: 'nothing here can be listed, and the reason is the listing, not the lock'
        row.listableQuantity == 0
        row.unlistableReason == 'ALREADY_LISTED'
        row.listedCount == 1
    }

    def "the grid asks about exactly the statuses the double-list guard counts"() {
        given:
        itemRepository.findByNamesLowerIn(_) >> []
        steamInventoryService.fetchInventory('111') >> [asset(assetId: '5001')]
        Collection<String> asked = null

        when:
        controller.inventory(req)

        then: 'ACTIVE and PENDING_ESCROW — the same pair listFromSteam refuses on'
        1 * listingRepository.findLiveAssetIdsBySeller(7L, _) >> { Long uid, Collection<String> st ->
            asked = st
            return []
        }
        asked as Set == ['ACTIVE', 'PENDING_ESCROW'] as Set
    }

    def "the row carries the Steam reference price so the form has a second anchor"() {
        given: 'a catalogued item with NO live listing (lowestPrice 0) but a Steam price'
        def cat = new Item(id: 55L, name: 'Wizard Hat', category: 'Hats', rarity: 'Limited',
                           lowestPrice: BigDecimal.ZERO, steamPrice: new BigDecimal('3.50'))
        steamInventoryService.fetchInventory('111') >> [asset(assetId: '6001', classId: '600', name: 'Wizard Hat')]
        itemRepository.findByNamesLowerIn(_) >> [cat]
        listingRepository.findLiveAssetIdsBySeller(7L, _) >> []

        when:
        def row = (controller.inventory(req).body.items as List<Map>)[0]

        then: 'the floor is genuinely zero — and the payload still carries a price the seller can anchor on'
        row.suggestedPrice == BigDecimal.ZERO
        row.steamPrice == new BigDecimal('3.50')
    }

    def "an uncatalogued row reports a null Steam price, not a zero"() {
        given: 'nothing in the catalogue matches this name'
        itemRepository.findByNamesLowerIn(_) >> []
        steamInventoryService.fetchInventory('111') >> [asset(assetId: '7001', name: 'Brand New Thing')]
        listingRepository.findLiveAssetIdsBySeller(7L, _) >> []

        when:
        def row = (controller.inventory(req).body.items as List<Map>)[0]

        then: 'null is "we have no reference", which the client renders in words; 0 would be a price'
        row.steamPrice == null
    }
}
