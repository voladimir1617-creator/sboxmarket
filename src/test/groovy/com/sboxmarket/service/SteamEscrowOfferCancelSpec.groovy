package com.sboxmarket.service

import com.sboxmarket.model.EscrowedItem
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.EscrowedItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import spock.lang.Specification

/**
 * The bot's Steam offers stay acceptable for 14 days. Before this, nothing on
 * the backend could withdraw one, so:
 *  - a deposit the 24h sweeper gave up on could still be accepted on day 2,
 *    moving the skin into the bot with only a FAILED row pointing at it (C1);
 *  - a listing cancelled while its deposit offer was open left the offer
 *    live, and a later accept told the seller his cancelled listing was live (C2);
 *  - a declined / expired deposit left the listing PENDING_ESCROW, so the
 *    "Re-list to try again" the seller was told to do failed (H1).
 */
class SteamEscrowOfferCancelSpec extends Specification {

    SteamEscrowService service
    SteamTradeBotService bot = Mock()
    EscrowedItemRepository escrowRepository = Mock()
    ListingRepository listingRepository = Mock()
    SteamUserRepository steamUserRepository = Mock()
    NotificationService notificationService = Mock()

    static final long HOUR = 60L * 60L * 1000L

    static SteamBotResult cancelled(String id = '444', boolean already = false, String status = 'canceled') {
        SteamBotResult.success([ok: true, offerId: id, status: status, cancelled: true, alreadyFinal: already])
    }

    static SteamBotResult alreadyAccepted(String id = '444', String status = 'accepted') {
        def r = SteamBotResult.error(SteamBotResult.ALREADY_ACCEPTED, "offer #${id} was already ${status}".toString(),
                [ok: false, error: 'ALREADY_ACCEPTED', offerId: id, status: status, retryable: false])
        r.status = status
        r
    }

    def setup() {
        service = new SteamEscrowService()
        service.steamTradeBotService = bot
        service.escrowRepository = escrowRepository
        service.listingRepository = listingRepository
        service.steamUserRepository = steamUserRepository
        service.notificationService = notificationService
        service.escrowSweepEnabled = true
        service.offerMessage = 'sboxmarket escrow'
        service.tradeProtectionHours = 0
        service.batchSize = 50
        service.depositTimeoutHours = 24
        service.holdGraceHours = 48
        service.returnRetryBackoffMs = 600000L
        service.returnAlertAttempts = 5
        service.depositRetryBackoffMs = 300000L
        bot.enabled >> true
    }

    private static EscrowedItem custody(Map o = [:]) {
        new EscrowedItem(
                id: (Long) (o.id ?: 9L),
                listingId: (Long) (o.listingId ?: 11L),
                sellerUserId: 1L,
                assetId: '555',
                marketHashName: 'AK-47 | Redline',
                depositOfferId: (o.containsKey('depositOfferId') ? o.depositOfferId : '444') as String,
                custodyState: (o.custodyState ?: EscrowedItem.PENDING_DEPOSIT) as String,
                depositAcceptedAt: o.depositAcceptedAt as Long,
                returnRequestedAt: o.returnRequestedAt as Long,
                returnAttempts: 0,
                createdAt: (Long) (o.createdAt ?: System.currentTimeMillis() - 48L * HOUR),
                updatedAt: 1_000L)
    }

    private static Listing held(String status = SteamEscrowService.STATUS_PENDING_ESCROW) {
        new Listing(id: 11L, price: new BigDecimal('100.00'), sellerName: 'S', status: status, sellerUserId: 1L)
    }

    // ── C1: the 24h deposit timeout ─────────────────────────────────────────

    def "timeout: the Steam offer is cancelled BEFORE the row is failed and the listing cancelled"() {
        given:
        def stale = custody()

        when:
        service.sweepStalePendingDeposits()

        then:
        1 * escrowRepository.findStalePendingDeposits(_ as Long, _) >> [stale]

        then: "cancel first"
        1 * bot.cancelOffer('444') >> cancelled()

        then: "only then the FAILED claim, the listing cancel and the seller notice"
        1 * escrowRepository.claimTimeoutPendingDeposit(9L, _, _ as Long) >> 1
        1 * listingRepository.findById(11L) >> Optional.of(held())
        1 * listingRepository.save({ Listing l -> l.status == SteamEscrowService.STATUS_CANCELLED })
        1 * notificationService.safePush(1L, _, _, { String b -> b.contains('withdrawn') }, _, _)
    }

    def "timeout: an offer that is already dead (declined / expired) is failed the same way"() {
        when:
        service.sweepStalePendingDeposits()

        then:
        1 * escrowRepository.findStalePendingDeposits(_ as Long, _) >> [custody()]
        1 * bot.cancelOffer('444') >> cancelled('444', true, 'expired')
        1 * escrowRepository.claimTimeoutPendingDeposit(9L, _, _ as Long) >> 1
        1 * listingRepository.findById(11L) >> Optional.of(held())
    }

    def "timeout: a deposit the seller ALREADY ACCEPTED is confirmed as received, never failed"() {
        given:
        def stale = custody()
        def listing = held()

        when:
        service.sweepStalePendingDeposits()

        then:
        1 * escrowRepository.findStalePendingDeposits(_ as Long, _) >> [stale]
        1 * bot.cancelOffer('444') >> alreadyAccepted()
        0 * escrowRepository.claimTimeoutPendingDeposit(_, _, _)

        and: "the ordinary accept path runs: the skin is in the bot, the listing goes live"
        1 * escrowRepository.findById(9L) >> Optional.of(stale)
        1 * bot.getOfferStatus('444') >> SteamBotResult.success([ok: true, offerId: '444', status: 'accepted'])
        1 * bot.fetchBotInventory() >> SteamBotResult.success([ok: true,
                items: [[assetId: '555', marketHashName: 'AK-47 | Redline', tradable: true]]])
        1 * listingRepository.findById(11L) >> Optional.of(listing)
        1 * listingRepository.save({ Listing l -> l.status == SteamEscrowService.STATUS_ACTIVE })
        stale.custodyState == EscrowedItem.IN_CUSTODY
        stale.depositAcceptedAt != null
        stale.heldAssetId == '555'
    }

    def "timeout: when the cancel fails the row is left PENDING_DEPOSIT and the listing untouched"() {
        given:
        def stale = custody()

        when:
        service.sweepStalePendingDeposits()

        then:
        1 * escrowRepository.findStalePendingDeposits(_ as Long, _) >> [stale]
        1 * bot.cancelOffer('444') >> SteamBotResult.error('TRANSPORT_ERROR', 'connection refused')
        0 * escrowRepository.claimTimeoutPendingDeposit(_, _, _)
        0 * listingRepository.save(_)
        0 * notificationService.safePush(_, _, _, _, _, _)
        stale.custodyState == EscrowedItem.PENDING_DEPOSIT
        stale.lastError.contains('not cancelled')
    }

    def "timeout: a row whose offer was never sent needs no cancel"() {
        when:
        service.sweepStalePendingDeposits()

        then:
        1 * escrowRepository.findStalePendingDeposits(_ as Long, _) >> [custody(depositOfferId: null)]
        0 * bot.cancelOffer(_)
        1 * escrowRepository.claimTimeoutPendingDeposit(9L, _, _ as Long) >> 1
        1 * listingRepository.findById(11L) >> Optional.of(held())
    }

    // ── C2: the seller cancels before the deposit landed ────────────────────

    def "listing cancel: a live deposit offer is cancelled in Steam and the row FAILED"() {
        given:
        def e = custody()

        when:
        def sent = service.returnToSeller(11L, 'Seller cancelled listing')

        then:
        1 * escrowRepository.findByListingId(11L) >> e
        1 * bot.cancelOffer('444') >> cancelled()
        0 * bot.sendOffer(_, _, _)
        !sent
        e.custodyState == EscrowedItem.FAILED
        e.returnRequestedAt != null
    }

    def "listing cancel: an offer already accepted keeps the row and queues the skin to go back"() {
        given:
        def e = custody()

        when:
        service.returnToSeller(11L, 'Seller cancelled listing')

        then:
        1 * escrowRepository.findByListingId(11L) >> e
        1 * bot.cancelOffer('444') >> alreadyAccepted()
        e.custodyState == EscrowedItem.PENDING_DEPOSIT
        e.returnRequestedAt != null
        1 * notificationService.safePush(1L, _, _, { String b -> b.contains('straight back') }, _, _)
    }

    def "listing cancel then a late accept: the skin lands IN_CUSTODY for return and the listing is NOT made live"() {
        given: "the seller cancelled, the cancel found the offer accepted"
        def e = custody(returnRequestedAt: System.currentTimeMillis() - HOUR)
        def listing = held(SteamEscrowService.STATUS_CANCELLED)

        when:
        service.confirmDeposit(9L, [ok: true, byAsset: ['555': '555'], byName: [:], nonTradable: [] as Set])

        then:
        1 * escrowRepository.findById(9L) >> Optional.of(e)
        1 * bot.getOfferStatus('444') >> SteamBotResult.success([ok: true, offerId: '444', status: 'accepted'])
        e.custodyState == EscrowedItem.IN_CUSTODY
        e.returnRequestedAt != null

        and: "no activation, and the seller is not told his listing is live"
        0 * listingRepository.save(_)
        1 * notificationService.safePush(1L, _, _, { String b -> b.contains('sending it back') && !b.contains('now live') }, _, _)
    }

    def "listing cancel then a late accept into Trade Protection: the notice does not promise a live listing"() {
        given:
        service.tradeProtectionHours = 192
        def e = custody(returnRequestedAt: System.currentTimeMillis() - HOUR)

        when:
        service.confirmDeposit(9L, [ok: true, byAsset: ['555': '555'], byName: [:], nonTradable: [] as Set])

        then:
        1 * escrowRepository.findById(9L) >> Optional.of(e)
        1 * bot.getOfferStatus('444') >> SteamBotResult.success([ok: true, offerId: '444', status: 'accepted'])
        e.custodyState == EscrowedItem.IN_TRADE_PROTECTION
        1 * notificationService.safePush(1L, _, _, { String b -> b.contains('after you cancelled') && !b.contains('goes live') }, _, _)
    }

    def "listing cancel: a cancel that fails keeps the row PENDING_DEPOSIT with the return recorded"() {
        given:
        def e = custody()

        when:
        service.returnToSeller(11L, 'Seller cancelled listing')

        then:
        1 * escrowRepository.findByListingId(11L) >> e
        1 * bot.cancelOffer('444') >> SteamBotResult.error('NOT_READY', 'bot not ready')
        e.custodyState == EscrowedItem.PENDING_DEPOSIT
        e.returnRequestedAt != null
        e.lastError.contains('not cancelled yet')
    }

    def "listing cancel: a row whose offer was never sent is FAILED without asking the bot"() {
        given:
        def e = custody(depositOfferId: null)

        when:
        service.returnToSeller(11L, 'Seller cancelled listing')

        then:
        1 * escrowRepository.findByListingId(11L) >> e
        0 * bot.cancelOffer(_)
        e.custodyState == EscrowedItem.FAILED
    }

    def "listing cancel after the deposit was accepted only records the return"() {
        given:
        def e = custody(depositAcceptedAt: System.currentTimeMillis() - HOUR)

        when:
        service.returnToSeller(11L, 'Seller cancelled listing')

        then:
        1 * escrowRepository.findByListingId(11L) >> e
        0 * bot.cancelOffer(_)
        e.custodyState == EscrowedItem.PENDING_DEPOSIT
        e.returnRequestedAt != null
    }

    def "unsent-deposit retry: a row cancelled while the offer was being sent gets that offer withdrawn"() {
        given:
        def unsent = custody(depositOfferId: null, createdAt: System.currentTimeMillis() - HOUR)
        def afterCancel = custody(depositOfferId: null, custodyState: EscrowedItem.FAILED)
        steamUserRepository.findById(1L) >> Optional.of(new SteamUser(id: 1L,
                tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=11&token=abc'))

        when:
        service.sweepUnsentDeposits()

        then:
        1 * escrowRepository.findUnsentDeposits(_ as Long, _) >> [unsent]
        1 * escrowRepository.claimDepositRetry(9L, _, _) >> 1
        2 * escrowRepository.findById(9L) >>> [Optional.of(unsent), Optional.of(afterCancel)]
        1 * bot.requestItems(_, ['555'], _) >> SteamBotResult.success([ok: true, offerId: '777', status: 'sent'])
        1 * bot.cancelOffer('777') >> cancelled('777')

        and: "the FAILED row is not resurrected and the seller is not asked to accept anything"
        0 * escrowRepository.save(_)
        0 * notificationService.safePush(_, _, _, _, _, _)
    }

    // ── H1: a declined / expired deposit frees the listing ─────────────────

    def "a declined deposit offer fails the row AND cancels the held listing so the seller can re-list"() {
        given:
        def e = custody(createdAt: System.currentTimeMillis() - HOUR)
        def listing = held()

        when:
        service.confirmDeposit(9L, [ok: true, byAsset: [:], byName: [:], nonTradable: [] as Set])

        then:
        1 * escrowRepository.findById(9L) >> Optional.of(e)
        1 * bot.getOfferStatus('444') >> SteamBotResult.success([ok: true, offerId: '444', status: status])
        e.custodyState == EscrowedItem.FAILED
        1 * listingRepository.findById(11L) >> Optional.of(listing)
        1 * listingRepository.save({ Listing l -> l.status == SteamEscrowService.STATUS_CANCELLED })
        1 * notificationService.safePush(1L, _, _, { String b -> b.contains('Re-list') }, _, _)

        where:
        status << ['declined', 'expired']
    }
}
