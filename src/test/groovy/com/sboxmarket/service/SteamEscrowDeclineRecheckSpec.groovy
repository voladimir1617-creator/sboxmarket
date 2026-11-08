package com.sboxmarket.service

import com.sboxmarket.model.EscrowedItem
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.EscrowedItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import spock.lang.Specification

/**
 * The declined-deposit re-check: a `declined` deposit offer is stamped, and
 * about ten minutes later the seller's Steam inventory is read once. Asset
 * gone and not in the bot means a DEPOSIT_DECLINED_ITEM_GONE audit row (the
 * Steam Web API key scam). Any unreadable inventory is UNKNOWN, no alert.
 */
class SteamEscrowDeclineRecheckSpec extends Specification {

    SteamEscrowService service
    SteamTradeBotService bot = Mock()
    EscrowedItemRepository escrowRepository = Mock()
    ListingRepository listingRepository = Mock()
    SteamUserRepository steamUserRepository = Mock()
    NotificationService notificationService = Mock()
    SteamInventoryService inventory = Mock()
    AuditService auditService = Mock()

    static final String STEAM_ID = '76561190000000001'

    def setup() {
        service = new SteamEscrowService()
        service.steamTradeBotService = bot
        service.escrowRepository = escrowRepository
        service.listingRepository = listingRepository
        service.steamUserRepository = steamUserRepository
        service.notificationService = notificationService
        service.steamInventoryService = inventory
        service.auditService = auditService
        service.escrowSweepEnabled = true
        service.offerMessage = 'sboxmarket escrow'
        service.tradeProtectionHours = 0
        service.batchSize = 50
        bot.enabled >> true
        steamUserRepository.findById(1L) >> Optional.of(new SteamUser(id: 1L, steamId64: STEAM_ID,
                tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=xyz'))
    }

    private static EscrowedItem declinedRow(Map o = [:]) {
        new EscrowedItem(id: 5L, listingId: 10L, sellerUserId: 1L, assetId: '555',
                marketHashName: 'Wizard Hat', depositOfferId: '444',
                custodyState: EscrowedItem.FAILED,
                depositOfferSentAt: System.currentTimeMillis() - 15L * 60_000L,
                depositDeclinedAt: (o.declinedAt ?: System.currentTimeMillis() - 11L * 60_000L) as Long)
    }

    private static Map botIndex(Map byAsset = [:]) {
        [ok: true, byAsset: byAsset, byName: [:], nonTradable: [] as Set, items: []]
    }

    // ── recording the decline ────────────────────────────────────────────

    def "a declined deposit is stamped with its decline time; the seller notice is unchanged"() {
        given:
        def e = new EscrowedItem(id: 5L, listingId: 10L, sellerUserId: 1L, assetId: '555',
                depositOfferId: '444', custodyState: EscrowedItem.PENDING_DEPOSIT)
        long before = System.currentTimeMillis()

        when:
        service.confirmDeposit(5L, botIndex())

        then:
        1 * escrowRepository.findById(5L) >> Optional.of(e)
        1 * bot.getOfferStatus('444') >> SteamBotResult.success([ok: true, status: 'declined'])
        1 * notificationService.safePush(1L, 'TRADE_SENT', 'Steam escrow',
                "Your deposit trade offer was declined, so your listing didn't go live. Re-list to try again.", 10L, '/me/stall')
        e.custodyState == EscrowedItem.FAILED
        e.depositDeclinedAt != null && e.depositDeclinedAt >= before
        e.declineCheck == null
    }

    def "an expired deposit offer is not stamped as declined"() {
        given:
        def e = new EscrowedItem(id: 5L, listingId: 10L, sellerUserId: 1L, assetId: '555',
                depositOfferId: '444', custodyState: EscrowedItem.PENDING_DEPOSIT)

        when:
        service.confirmDeposit(5L, botIndex())

        then:
        1 * escrowRepository.findById(5L) >> Optional.of(e)
        1 * bot.getOfferStatus('444') >> SteamBotResult.success([ok: true, status: 'expired'])
        e.custodyState == EscrowedItem.FAILED
        e.depositDeclinedAt == null
    }

    def "the deposit offer's send time is stamped when the bot sends it"() {
        given:
        def l = new com.sboxmarket.model.Listing(id: 10L, price: 1.0G, sellerName: 'S', status: 'ACTIVE', sellerUserId: 1L)
        listingRepository.findById(10L) >> Optional.of(l)
        escrowRepository.save(_) >> { args -> args[0] }
        long before = System.currentTimeMillis()

        when:
        def row = service.requestDepositForListing(l, '555', 'Wizard Hat')

        then:
        1 * bot.requestItems(_, ['555'], _) >> SteamBotResult.success([ok: true, offerId: '444'])
        row.depositOfferSentAt != null && row.depositOfferSentAt >= before
    }

    // ── the sweep ────────────────────────────────────────────────────────

    def "sweep: asset gone from the seller and not in the bot writes DEPOSIT_DECLINED_ITEM_GONE"() {
        given:
        def e = declinedRow()

        when:
        service.sweepDeclinedDepositRechecks()

        then:
        1 * escrowRepository.findDeclineRechecksDue({ long c -> c <= System.currentTimeMillis() - 600_000L + 1000L }, _) >> [e]
        1 * escrowRepository.claimDeclineRecheck(5L) >> 1
        1 * bot.fetchBotInventory() >> SteamBotResult.success([ok: true, items: [[assetId: '777', marketHashName: 'Other']]])
        _ * escrowRepository.findHeldAssetIdsClaimedByOthers(_, _) >> []
        1 * inventory.blockedUntilMs(STEAM_ID) >> null
        1 * inventory.fetchInventory(STEAM_ID) >> [[assetId: '999', name: 'Something else']]
        1 * inventory.lastOutcomeFor(STEAM_ID) >> [outcome: SteamInventoryService.OUTCOME_OK]
        1 * inventory.truncationFor(STEAM_ID) >> null
        1 * auditService.log(AuditService.DEPOSIT_DECLINED_ITEM_GONE, null, 1L, 5L,
                { String s -> s.contains('555') && s.contains('444') })
        1 * escrowRepository.recordDeclineRecheck(5L, EscrowedItem.DECLINE_GONE)
        0 * notificationService._
    }

    def "sweep: asset still in the seller's inventory is PRESENT and raises nothing"() {
        when:
        service.sweepDeclinedDepositRechecks()

        then:
        1 * escrowRepository.findDeclineRechecksDue(_, _) >> [declinedRow()]
        1 * escrowRepository.claimDeclineRecheck(5L) >> 1
        _ * bot.fetchBotInventory() >> SteamBotResult.success([ok: true, items: []])
        1 * inventory.fetchInventory(STEAM_ID) >> [[assetId: '555', name: 'Wizard Hat']]
        1 * inventory.lastOutcomeFor(STEAM_ID) >> [outcome: SteamInventoryService.OUTCOME_OK]
        0 * auditService.log(*_)
        1 * escrowRepository.recordDeclineRecheck(5L, EscrowedItem.DECLINE_PRESENT)
    }

    def "sweep: an unreadable seller inventory (#outcome) is UNKNOWN and raises nothing"() {
        when:
        service.sweepDeclinedDepositRechecks()

        then:
        1 * escrowRepository.findDeclineRechecksDue(_, _) >> [declinedRow()]
        1 * escrowRepository.claimDeclineRecheck(5L) >> 1
        _ * bot.fetchBotInventory() >> SteamBotResult.success([ok: true, items: []])
        _ * inventory.fetchInventory(STEAM_ID) >> []
        _ * inventory.lastOutcomeFor(STEAM_ID) >> [outcome: outcome]
        0 * auditService.log(*_)
        1 * escrowRepository.recordDeclineRecheck(5L, EscrowedItem.DECLINE_UNKNOWN)

        where:
        outcome << [SteamInventoryService.OUTCOME_PRIVATE, SteamInventoryService.OUTCOME_RATE_LIMITED,
                    SteamInventoryService.OUTCOME_UPSTREAM, SteamInventoryService.OUTCOME_MALFORMED,
                    SteamInventoryService.OUTCOME_NETWORK, null]
    }

    def "sweep: a backed-off inventory is UNKNOWN without asking Steam"() {
        when:
        service.sweepDeclinedDepositRechecks()

        then:
        1 * escrowRepository.findDeclineRechecksDue(_, _) >> [declinedRow()]
        1 * escrowRepository.claimDeclineRecheck(5L) >> 1
        _ * bot.fetchBotInventory() >> SteamBotResult.success([ok: true, items: []])
        1 * inventory.blockedUntilMs(STEAM_ID) >> System.currentTimeMillis() + 60_000L
        0 * inventory.fetchInventory(_)
        0 * auditService.log(*_)
        1 * escrowRepository.recordDeclineRecheck(5L, EscrowedItem.DECLINE_UNKNOWN)
    }

    def "sweep: absence from a truncated inventory is UNKNOWN"() {
        when:
        service.sweepDeclinedDepositRechecks()

        then:
        1 * escrowRepository.findDeclineRechecksDue(_, _) >> [declinedRow()]
        1 * escrowRepository.claimDeclineRecheck(5L) >> 1
        _ * bot.fetchBotInventory() >> SteamBotResult.success([ok: true, items: []])
        1 * inventory.fetchInventory(STEAM_ID) >> [[assetId: '1']]
        1 * inventory.lastOutcomeFor(STEAM_ID) >> [outcome: SteamInventoryService.OUTCOME_OK]
        1 * inventory.truncationFor(STEAM_ID) >> [total: 900, shown: 500]
        0 * auditService.log(*_)
        1 * escrowRepository.recordDeclineRecheck(5L, EscrowedItem.DECLINE_UNKNOWN)
    }

    def "sweep: an unreadable bot inventory is UNKNOWN, not an alert"() {
        when:
        service.sweepDeclinedDepositRechecks()

        then:
        1 * escrowRepository.findDeclineRechecksDue(_, _) >> [declinedRow()]
        1 * escrowRepository.claimDeclineRecheck(5L) >> 1
        1 * bot.fetchBotInventory() >> SteamBotResult.error('BOT_DOWN', 'unreachable')
        1 * inventory.fetchInventory(STEAM_ID) >> []
        1 * inventory.lastOutcomeFor(STEAM_ID) >> [outcome: SteamInventoryService.OUTCOME_EMPTY]
        0 * auditService.log(*_)
        1 * escrowRepository.recordDeclineRecheck(5L, EscrowedItem.DECLINE_UNKNOWN)
    }

    def "sweep: the bot holding the asset is BOT_HELD, not an alert"() {
        when:
        service.sweepDeclinedDepositRechecks()

        then:
        1 * escrowRepository.findDeclineRechecksDue(_, _) >> [declinedRow()]
        1 * escrowRepository.claimDeclineRecheck(5L) >> 1
        1 * bot.fetchBotInventory() >> SteamBotResult.success([ok: true, items: [[assetId: '555', marketHashName: 'Wizard Hat']]])
        1 * inventory.fetchInventory(STEAM_ID) >> []
        1 * inventory.lastOutcomeFor(STEAM_ID) >> [outcome: SteamInventoryService.OUTCOME_EMPTY]
        0 * auditService.log(*_)
        1 * escrowRepository.recordDeclineRecheck(5L, EscrowedItem.DECLINE_BOT_HELD)
    }

    def "sweep: a row another pod already claimed is not checked again"() {
        when:
        service.sweepDeclinedDepositRechecks()

        then:
        1 * escrowRepository.findDeclineRechecksDue(_, _) >> [declinedRow()]
        1 * escrowRepository.claimDeclineRecheck(5L) >> 0
        0 * inventory._
        0 * bot.fetchBotInventory()
        0 * auditService.log(*_)
        0 * escrowRepository.recordDeclineRecheck(_, _)
    }

    def "sweep: a seller with no Steam id is UNKNOWN"() {
        given:
        def e = declinedRow()
        e.sellerUserId = 2L
        steamUserRepository.findById(2L) >> Optional.empty()

        when:
        service.sweepDeclinedDepositRechecks()

        then:
        1 * escrowRepository.findDeclineRechecksDue(_, _) >> [e]
        1 * escrowRepository.claimDeclineRecheck(5L) >> 1
        _ * bot.fetchBotInventory() >> SteamBotResult.success([ok: true, items: []])
        0 * inventory.fetchInventory(_)
        1 * escrowRepository.recordDeclineRecheck(5L, EscrowedItem.DECLINE_UNKNOWN)
    }

    def "sweep: the delay is configurable"() {
        given:
        service.declineRecheckDelayMs = 60_000L
        long now = System.currentTimeMillis()

        when:
        service.sweepDeclinedDepositRechecks()

        then:
        1 * escrowRepository.findDeclineRechecksDue({ long c -> c >= now - 60_000L && c <= now }, _) >> []
    }

    def "sweep: inert when escrow is disabled"() {
        given:
        service.escrowSweepEnabled = false

        when:
        service.sweepDeclinedDepositRechecks()

        then:
        0 * escrowRepository.findDeclineRechecksDue(_, _)
    }
}
