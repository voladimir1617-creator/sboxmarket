package com.sboxmarket.service

import com.sboxmarket.model.EscrowedItem
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.EscrowedItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import spock.lang.Specification

/**
 * Steam Trade Protection on bot deposits, and the bot inventory check.
 *
 * Steam lets the sender of a trade reverse it for 7 days and stops the
 * receiver moving the item on. Skin.Club holds skin deposits for 8 days for
 * that reason. Before this, SkinBox put a deposited skin on sale the moment it
 * reached the bot: a buyer could pay for a skin the bot could not send, and a
 * seller who reversed the deposit kept both the skin and a live listing. The
 * sidecar also asked Steam for tradable items only, so a protected deposit
 * never showed up at all.
 */
class SteamEscrowTradeProtectionSpec extends Specification {

    SteamEscrowService service
    SteamTradeBotService bot = Mock()
    EscrowedItemRepository escrowRepository = Mock()
    ListingRepository listingRepository = Mock()
    SteamUserRepository steamUserRepository = Mock()
    NotificationService notificationService = Mock()

    static final long HOUR = 60L * 60L * 1000L
    static final long DAY = 24L * HOUR

    def setup() {
        service = new SteamEscrowService()
        service.steamTradeBotService = bot
        service.escrowRepository = escrowRepository
        service.listingRepository = listingRepository
        service.steamUserRepository = steamUserRepository
        service.notificationService = notificationService
        service.escrowSweepEnabled = true
        service.offerMessage = 'sboxmarket escrow'
        service.batchSize = 50
        service.tradeProtectionHours = 192
        service.missingConfirmMs = 30L * 60L * 1000L
        bot.enabled >> true
    }

    private static EscrowedItem row(Map o = [:]) {
        new EscrowedItem(
                id: 7L, listingId: 10L, sellerUserId: 1L,
                assetId: (o.assetId ?: '555') as String,
                heldAssetId: o.heldAssetId as String,
                marketHashName: 'Wizard Hat',
                depositOfferId: '444',
                custodyState: (o.custodyState ?: EscrowedItem.PENDING_DEPOSIT) as String,
                escrowEndsAt: o.escrowEndsAt as Long,
                depositAcceptedAt: o.depositAcceptedAt as Long,
                protectedUntil: o.protectedUntil as Long,
                missingSince: o.missingSince as Long,
                returnRequestedAt: o.returnRequestedAt as Long,
                createdAt: System.currentTimeMillis(), updatedAt: 1_000L)
    }

    private static SteamBotResult inventory(List<Map> items) {
        SteamBotResult.success([ok: true, items: items])
    }

    private Listing listing(String status) {
        def l = new Listing(status: status)
        l.id = 10L
        l
    }

    // ── Entering protection ────────────────────────────────────────────────

    def "a deposit that just reached the bot is held, not put on sale"() {
        given:
        def e = row()
        def l = listing('PENDING_ESCROW')
        escrowRepository.findById(7L) >> Optional.of(e)
        listingRepository.findById(10L) >> Optional.of(l)
        bot.getOfferStatus('444') >> SteamBotResult.success([ok: true, status: 'accepted'])
        bot.fetchBotInventory() >> inventory([[assetId: '901', marketHashName: 'Wizard Hat', tradable: true]])

        when:
        long before = System.currentTimeMillis()
        service.confirmDeposit(7L)

        then: "the bot holds it, the listing stays off sale, and the hold ends 8 days after the trade"
        e.custodyState == EscrowedItem.IN_TRADE_PROTECTION
        e.heldAssetId == '901'
        e.protectedUntil >= before + 8 * DAY
        l.status == 'PENDING_ESCROW'
        1 * notificationService.safePush(1L, _, _, { it.contains('goes live on') }, 10L, _)
    }

    def "with no window configured, Steam's own non-tradable flag still holds the deposit"() {
        given:
        service.tradeProtectionHours = 0
        def e = row()
        def l = listing('PENDING_ESCROW')
        escrowRepository.findById(7L) >> Optional.of(e)
        listingRepository.findById(10L) >> Optional.of(l)
        bot.getOfferStatus('444') >> SteamBotResult.success([ok: true, status: 'accepted'])
        bot.fetchBotInventory() >> inventory([[assetId: '901', marketHashName: 'Wizard Hat', tradable: false]])

        when:
        service.confirmDeposit(7L)

        then:
        e.custodyState == EscrowedItem.IN_TRADE_PROTECTION
        e.protectedUntil == null
        l.status == 'PENDING_ESCROW'
    }

    def "with no window and a tradable skin, the listing goes live at once as before"() {
        given:
        service.tradeProtectionHours = 0
        def e = row()
        def l = listing('PENDING_ESCROW')
        escrowRepository.findById(7L) >> Optional.of(e)
        listingRepository.findById(10L) >> Optional.of(l)
        listingRepository.save(_) >> { it[0] }
        bot.getOfferStatus('444') >> SteamBotResult.success([ok: true, status: 'accepted'])
        bot.fetchBotInventory() >> inventory([[assetId: '901', marketHashName: 'Wizard Hat', tradable: true]])

        when:
        service.confirmDeposit(7L)

        then:
        e.custodyState == EscrowedItem.IN_CUSTODY
        l.status == 'ACTIVE'
    }

    def "protection runs from the end of a Steam trade hold, not from acceptance"() {
        given:
        long accepted = System.currentTimeMillis() - 10 * DAY
        long holdEnded = System.currentTimeMillis() - DAY

        expect:
        service.protectionEndFor(row(depositAcceptedAt: accepted, escrowEndsAt: holdEnded)) == holdEnded + 8 * DAY
    }

    def "an older sidecar that sends no tradable flag reads as tradable"() {
        given:
        bot.fetchBotInventory() >> inventory([[assetId: '901', marketHashName: 'Wizard Hat']])

        when:
        def idx = service.fetchBotInventoryIndex()

        then:
        idx.ok
        idx.nonTradable.isEmpty()
    }

    def "a failed inventory read is not an empty inventory"() {
        given:
        bot.fetchBotInventory() >> SteamBotResult.error('TRANSPORT_ERROR', 'down')

        expect:
        !service.fetchBotInventoryIndex().ok
    }

    // ── Releasing it ───────────────────────────────────────────────────────

    def "when the window is over and Steam says tradable, the listing goes live"() {
        given:
        def e = row(custodyState: EscrowedItem.IN_TRADE_PROTECTION, heldAssetId: '901',
                protectedUntil: System.currentTimeMillis() - 1000)
        def l = listing('PENDING_ESCROW')
        escrowRepository.findTradeProtectionDue(_, _) >> [e]
        escrowRepository.findById(7L) >> Optional.of(e)
        listingRepository.findById(10L) >> Optional.of(l)
        listingRepository.save(_) >> { it[0] }
        bot.fetchBotInventory() >> inventory([[assetId: '901', marketHashName: 'Wizard Hat', tradable: true]])

        when:
        service.releaseTradeProtection()

        then:
        e.custodyState == EscrowedItem.IN_CUSTODY
        l.status == 'ACTIVE'
    }

    def "Steam still saying non-tradable keeps it waiting past the window"() {
        given:
        def e = row(custodyState: EscrowedItem.IN_TRADE_PROTECTION, heldAssetId: '901',
                protectedUntil: System.currentTimeMillis() - 1000)
        escrowRepository.findTradeProtectionDue(_, _) >> [e]
        escrowRepository.findById(7L) >> Optional.of(e)
        bot.fetchBotInventory() >> inventory([[assetId: '901', marketHashName: 'Wizard Hat', tradable: false]])

        when:
        service.releaseTradeProtection()

        then:
        e.custodyState == EscrowedItem.IN_TRADE_PROTECTION
        0 * listingRepository.save(_)
    }

    def "a seller who cancelled during protection gets the skin back, the listing is not revived"() {
        given:
        def e = row(custodyState: EscrowedItem.IN_TRADE_PROTECTION, heldAssetId: '901',
                protectedUntil: System.currentTimeMillis() - 1000, returnRequestedAt: 5_000L)
        escrowRepository.findTradeProtectionDue(_, _) >> [e]
        escrowRepository.findById(7L) >> Optional.of(e)
        bot.fetchBotInventory() >> inventory([[assetId: '901', marketHashName: 'Wizard Hat', tradable: true]])

        when:
        service.releaseTradeProtection()

        then: "IN_CUSTODY with a return asked for is exactly what the return sweep sends back"
        e.custodyState == EscrowedItem.IN_CUSTODY
        e.returnRequestedAt == 5_000L
        0 * listingRepository.save(_)
    }

    def "cancelling during protection queues the return instead of failing against Steam"() {
        given:
        def e = row(custodyState: EscrowedItem.IN_TRADE_PROTECTION, heldAssetId: '901',
                protectedUntil: System.currentTimeMillis() + DAY)
        escrowRepository.findByListingId(10L) >> e

        when:
        boolean sent = service.returnToSeller(10L)

        then:
        !sent
        e.returnRequestedAt != null
        0 * bot.sendOffer(*_)
    }

    def "a skin gone from the bot is noted once, and called reversed only on a second read"() {
        given:
        def e = row(custodyState: EscrowedItem.IN_TRADE_PROTECTION, heldAssetId: '901',
                protectedUntil: System.currentTimeMillis() - 1000)
        def l = listing('PENDING_ESCROW')
        escrowRepository.findTradeProtectionDue(_, _) >> [e]
        escrowRepository.findById(7L) >> Optional.of(e)
        listingRepository.findById(10L) >> Optional.of(l)
        bot.fetchBotInventory() >> inventory([])

        when: "first read without it"
        service.releaseTradeProtection()

        then:
        e.custodyState == EscrowedItem.IN_TRADE_PROTECTION
        e.missingSince != null

        when: "a later read, past the confirm window, still without it"
        e.missingSince = System.currentTimeMillis() - service.missingConfirmMs - 1
        service.releaseTradeProtection()

        then:
        e.custodyState == EscrowedItem.REVERSED
        l.status == 'CANCELLED'
        1 * notificationService.safePush(1L, _, _, { it.contains('reversed') }, 10L, _)
    }

    def "an unreadable inventory changes nothing"() {
        given:
        def e = row(custodyState: EscrowedItem.IN_TRADE_PROTECTION, heldAssetId: '901',
                protectedUntil: System.currentTimeMillis() - 1000)
        escrowRepository.findTradeProtectionDue(_, _) >> [e]
        bot.fetchBotInventory() >> SteamBotResult.error('TRANSPORT_ERROR', 'down')

        when:
        service.releaseTradeProtection()

        then:
        e.custodyState == EscrowedItem.IN_TRADE_PROTECTION
        e.missingSince == null
        0 * escrowRepository.save(_)
    }

    def "the seller's held-listing view says when the listing goes live"() {
        given:
        long until = System.currentTimeMillis() + 3 * DAY
        escrowRepository.findByListingIds([10L]) >> [row(custodyState: EscrowedItem.IN_TRADE_PROTECTION,
                heldAssetId: '901', protectedUntil: until)]

        when:
        def view = service.custodyViewForListings([10L])[10L]

        then:
        view.inTradeProtection == true
        view.protectedUntil == until
        view.protectedUntilOn != null
        view.message.contains('goes live on')
    }

    // ── The bot inventory check ────────────────────────────────────────────

    def "the inventory check pulls a live listing whose skin left the bot, and reports orphans"() {
        given:
        def reconcile = new SteamBotReconcileService(escrowService: service,
                escrowRepository: escrowRepository, listingRepository: listingRepository)
        def held = row(custodyState: EscrowedItem.IN_CUSTODY, heldAssetId: '901')
        def l = listing('ACTIVE')
        escrowRepository.findExpectedInBot() >> [held]
        listingRepository.findById(10L) >> Optional.of(l)
        bot.fetchBotInventory() >> inventory([[assetId: '777', marketHashName: 'Stray Jacket', tradable: true]])

        when:
        Map report = reconcile.reconcile()

        then:
        report.ok
        report.missing*.listingId == [10L]
        report.orphans*.assetId == ['777']
        report.listingsPulled == 1
        l.status == 'PENDING_ESCROW'
        held.custodyState == EscrowedItem.IN_CUSTODY
        held.missingSince != null
        0 * bot.sendOffer(*_)
    }

    def "a skin that turns up again puts its listing back on sale"() {
        given:
        def reconcile = new SteamBotReconcileService(escrowService: service,
                escrowRepository: escrowRepository, listingRepository: listingRepository)
        def held = row(custodyState: EscrowedItem.IN_CUSTODY, heldAssetId: '901', missingSince: 1_000L)
        def l = listing('PENDING_ESCROW')
        escrowRepository.findExpectedInBot() >> [held]
        listingRepository.findById(10L) >> Optional.of(l)
        bot.fetchBotInventory() >> inventory([[assetId: '901', marketHashName: 'Wizard Hat', tradable: true]])

        when:
        Map report = reconcile.reconcile()

        then:
        report.missing.isEmpty()
        report.listingsRestored == 1
        held.missingSince == null
        l.status == 'ACTIVE'
    }

    def "a listing with a sale in progress is never touched, even if its skin just left"() {
        given:
        def reconcile = new SteamBotReconcileService(escrowService: service,
                escrowRepository: escrowRepository, listingRepository: listingRepository)
        def held = row(custodyState: EscrowedItem.IN_CUSTODY, heldAssetId: '901')
        def l = listing('SOLD')
        escrowRepository.findExpectedInBot() >> [held]
        listingRepository.findById(10L) >> Optional.of(l)
        bot.fetchBotInventory() >> inventory([])

        when:
        reconcile.reconcile()

        then:
        l.status == 'SOLD'
        held.missingSince == null
        0 * listingRepository.save(_)
    }

    def "the check does nothing when the bot inventory cannot be read"() {
        given:
        def reconcile = new SteamBotReconcileService(escrowService: service,
                escrowRepository: escrowRepository, listingRepository: listingRepository)
        bot.fetchBotInventory() >> SteamBotResult.error('TRANSPORT_ERROR', 'down')

        when:
        Map report = reconcile.reconcile()

        then:
        !report.ok
        0 * escrowRepository.findExpectedInBot()
        0 * listingRepository.save(_)
    }
}
