package com.sboxmarket

import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.controller.SteamInventoryController
import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.ListingService
import com.sboxmarket.service.SteamEscrowService
import com.sboxmarket.service.SteamInventoryService
import com.sboxmarket.service.SteamSyncService
import com.sboxmarket.service.TextSanitizer
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import spock.lang.Specification
import spock.lang.Subject

/**
 * The seller-side trade-URL pre-flight gate on /api/steam/list and
 * /api/steam/list-bulk.
 *
 * ── What this prevents ────────────────────────────────────────────────────
 * Under bot-escrow a listing is created PENDING_ESCROW and the bot is asked to
 * request the asset from the seller. A seller with no trade URL gives the bot
 * no address to send that request to, so the deposit never goes out.
 *
 * The seller's experience without this gate was a success response and a green
 * toast, followed by nothing: PENDING_ESCROW is filtered out of every
 * seller-facing query (findActiveBySeller, countActiveBySeller, cancelAllActive
 * all pin status='ACTIVE'), so the listing appeared in no stall and no count
 * and could not be reached by the bulk-cancel button — while still being solid
 * enough for the ALREADY_LISTED guard, which DOES count PENDING_ESCROW, to
 * refuse a re-list of the same asset. The seller was told they already had a
 * listing for an item they could not see and could not cancel, for 24 hours,
 * until the deposit-timeout sweeper cleared it.
 *
 * The BUY side already refuses this shape: PurchaseService.buy throws
 * TRADE_URL_MISSING before debiting rather than taking the money and stranding
 * the trade. This is the same code, the same posture, on the sell side.
 *
 * The gate must NOT fire when the bot is unconfigured — on the legacy path the
 * listing goes straight to ACTIVE and the seller delivers by hand from their
 * own Steam client, so demanding a trade URL at list time would be a
 * regression that blocks listing on today's production config.
 */
class SteamListingSellerTradeUrlGateSpec extends Specification {

    SteamInventoryService steamInventoryService = Mock()
    SteamSyncService      steamSyncService      = Mock()
    SteamUserRepository   steamUserRepository   = Mock()
    ItemRepository        itemRepository        = Mock()
    ListingRepository     listingRepository     = Mock()
    ListingService        listingService        = Mock()
    TextSanitizer         textSanitizer         = Mock()
    SteamEscrowService    steamEscrowService    = Mock()

    @Subject
    SteamInventoryController controller = new SteamInventoryController(
        steamInventoryService: steamInventoryService,
        steamSyncService     : steamSyncService,
        steamUserRepository  : steamUserRepository,
        itemRepository       : itemRepository,
        listingRepository    : listingRepository,
        listingService       : listingService,
        textSanitizer        : textSanitizer,
        steamEscrowService   : steamEscrowService
    )

    HttpServletRequest req = Mock()
    HttpSession        ses = Mock()

    def setup() {
        req.getSession() >> ses
        ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 1L
    }

    private void seller(String tradeUrl) {
        steamUserRepository.findById(1L) >> Optional.of(
            new SteamUser(id: 1L, steamId64: '76561190000000001',
                          displayName: 'Seller', tradeUrl: tradeUrl))
    }

    def "escrow live + no trade URL: /list is refused BEFORE any listing row exists"() {
        given:
        steamEscrowService.escrowEnabled >> true
        seller(null)

        when:
        controller.listFromSteam([assetId: '555', price: 10.00], req)

        then:
        def e = thrown(BadRequestException)
        e.message.contains('trade URL')

        and: 'nothing was created, and the bot was never asked for a deposit it could not address'
        0 * listingService.createListing(_)
        0 * steamEscrowService.requestDepositForListing(_, _, _)

        and: 'we did not even burn a Steam inventory fetch on a doomed listing'
        0 * steamInventoryService.fetchInventory(_)
    }

    def "escrow live + blank-whitespace trade URL is treated as missing"() {
        given:
        steamEscrowService.escrowEnabled >> true
        seller('   ')

        when:
        controller.listFromSteam([assetId: '555', price: 10.00], req)

        then:
        thrown(BadRequestException)
        0 * listingService.createListing(_)
    }

    def "escrow live + no trade URL: /list-bulk cannot create 20 dead listings in one call"() {
        given:
        steamEscrowService.escrowEnabled >> true
        seller(null)

        when:
        controller.listBulkFromSteam([assetIds: ['1', '2', '3'], price: 10.00], req)

        then:
        thrown(BadRequestException)
        0 * listingService.createListing(_)
    }

    def "escrow DISABLED: a seller with no trade URL can still list (legacy hand-delivery path)"() {
        given: 'the bot is unconfigured — exactly production today'
        steamEscrowService.escrowEnabled >> false
        seller(null)

        when:
        controller.listFromSteam([assetId: '555', price: 10.00], req)

        then: 'the gate does NOT fire — we get past it and fail later on inventory, not on the trade URL'
        def e = thrown(BadRequestException)
        !e.message.contains('trade URL')

        and: 'proof we reached the real listing flow rather than short-circuiting'
        1 * steamInventoryService.fetchInventory('76561190000000001') >> []
    }

    def "escrow live + a real trade URL: the gate lets the seller straight through"() {
        given:
        steamEscrowService.escrowEnabled >> true
        seller('https://steamcommunity.com/tradeoffer/new/?partner=1&token=xyz')

        when:
        controller.listFromSteam([assetId: '555', price: 10.00], req)

        then: 'the gate is transparent — the flow proceeds and fails on its own terms'
        def e = thrown(BadRequestException)
        !e.message.contains('trade URL')

        and:
        1 * steamInventoryService.fetchInventory('76561190000000001') >> []
    }

    def "no escrow bean at all: the gate is inert rather than exploding"() {
        given: 'older wiring / CI contexts construct this controller without the escrow bean'
        def bare = new SteamInventoryController(
            steamInventoryService: steamInventoryService,
            steamSyncService     : steamSyncService,
            steamUserRepository  : steamUserRepository,
            itemRepository       : itemRepository,
            listingRepository    : listingRepository,
            listingService       : listingService,
            textSanitizer        : textSanitizer)
        seller(null)

        when:
        bare.listFromSteam([assetId: '555', price: 10.00], req)

        then: 'no NPE from the null bean — we reach the ordinary flow'
        def e = thrown(BadRequestException)
        !e.message.contains('trade URL')

        and:
        1 * steamInventoryService.fetchInventory('76561190000000001') >> []
    }
}
