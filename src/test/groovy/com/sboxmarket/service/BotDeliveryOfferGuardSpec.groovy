package com.sboxmarket.service

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.model.SteamDeliveryAttempt
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Trade
import com.sboxmarket.model.TradeProtection
import com.sboxmarket.repository.SteamDeliveryAttemptRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TradeRepository
import org.springframework.data.domain.Pageable
import spock.lang.Specification
import spock.lang.Unroll

/**
 * BotDeliveryOfferGuard: is it safe to refund this buyer now? The bot's live
 * Steam delivery offer is cancelled first; an accepted one refuses the refund.
 */
class BotDeliveryOfferGuardSpec extends Specification {

    SteamTradeBotService bot = Mock()
    SteamDeliveryAttemptRepository attempts = Mock()
    SteamEscrowService escrow = Mock()
    BotDeliveryOfferGuard guard = new BotDeliveryOfferGuard(steamTradeBotService: bot,
            attemptRepository: attempts, steamEscrowService: escrow)
    Trade trade = new Trade(id: 7L, listingId: 10L, buyerUserId: 2L, sellerUserId: 1L, state: 'PENDING_BUYER_CONFIRM')

    def setup() {
        bot.enabled >> true
    }

    private static List<SteamDeliveryAttempt> row(String state, String offerId = '987') {
        [new SteamDeliveryAttempt(id: 1L, tradeId: 7L, steamOfferId: offerId, offerState: state, phase: 'POLL', success: true)]
    }

    private static SteamBotResult accepted(String status) {
        def r = SteamBotResult.error(SteamBotResult.ALREADY_ACCEPTED, 'already',
                [ok: false, error: 'ALREADY_ACCEPTED', offerId: '987', status: status, retryable: false])
        r.status = status
        r
    }

    def "no bot offer at all: safe, and Steam is not asked"() {
        when:
        def r = guard.cancelLiveOffer(trade)

        then:
        1 * attempts.findLatestWithOffer(7L, _ as Pageable) >> []
        0 * bot.cancelOffer(_)
        r.outcome == BotDeliveryOfferGuard.Outcome.NO_LIVE_OFFER
        r.safeToRefund
    }

    @Unroll
    def "a #state offer is already dead: safe without a cancel"() {
        when:
        def r = guard.cancelLiveOffer(trade)

        then:
        1 * attempts.findLatestWithOffer(7L, _ as Pageable) >> row(state)
        0 * bot.cancelOffer(_)
        r.safeToRefund

        where:
        state << ['declined', 'expired', 'canceled', 'invalid_items']
    }

    def "a live offer is cancelled in Steam and the cancel is written to the delivery log"() {
        when:
        def r = guard.cancelLiveOffer(trade)

        then:
        1 * attempts.findLatestWithOffer(7L, _ as Pageable) >> row('active')
        1 * bot.cancelOffer('987') >> SteamBotResult.success([ok: true, offerId: '987', status: 'canceled', cancelled: true])
        1 * attempts.save({ SteamDeliveryAttempt a ->
            a.tradeId == 7L && a.steamOfferId == '987' && a.offerState == 'canceled' && a.phase == 'POLL' && a.success })
        r.outcome == BotDeliveryOfferGuard.Outcome.CANCELLED
        r.safeToRefund
    }

    def "an offer Steam says was accepted is recorded, custody marked delivered, and refused"() {
        when:
        def r = guard.cancelLiveOffer(trade)

        then:
        1 * attempts.findLatestWithOffer(7L, _ as Pageable) >> row('active')
        1 * bot.cancelOffer('987') >> accepted('accepted')
        1 * attempts.save({ SteamDeliveryAttempt a -> a.steamOfferId == '987' && a.offerState == 'accepted' })
        1 * escrow.markDelivered(10L)
        r.outcome == BotDeliveryOfferGuard.Outcome.ACCEPTED
        !r.safeToRefund
    }

    def "an offer in a Steam hold counts as received, but custody is not marked delivered yet"() {
        when:
        def r = guard.cancelLiveOffer(trade)

        then:
        1 * attempts.findLatestWithOffer(7L, _ as Pageable) >> row('sent')
        1 * bot.cancelOffer('987') >> accepted('in_escrow')
        0 * escrow.markDelivered(_)
        r.outcome == BotDeliveryOfferGuard.Outcome.ACCEPTED
    }

    @Unroll
    def "a recorded #state offer is refused without asking Steam"() {
        when:
        def r = guard.cancelLiveOffer(trade)

        then:
        1 * attempts.findLatestWithOffer(7L, _ as Pageable) >> row(state)
        0 * bot.cancelOffer(_)
        r.outcome == BotDeliveryOfferGuard.Outcome.ACCEPTED

        where:
        state << ['accepted', 'in_escrow', 'verified']
    }

    def "a failed cancel is unsafe, and its log row carries no offer id so it never becomes the latest offer"() {
        when:
        def r = guard.cancelLiveOffer(trade)

        then:
        1 * attempts.findLatestWithOffer(7L, _ as Pageable) >> row('active')
        1 * bot.cancelOffer('987') >> SteamBotResult.error('RATE_LIMITED', 'slow down')
        1 * attempts.save({ SteamDeliveryAttempt a -> a.steamOfferId == null && !a.success })
        r.outcome == BotDeliveryOfferGuard.Outcome.FAILED
        !r.safeToRefund
    }

    def "a live offer with the bot switched off cannot be cancelled: unsafe"() {
        given:
        def off = Mock(SteamTradeBotService) { isEnabled() >> false }
        guard.steamTradeBotService = off

        when:
        def r = guard.cancelLiveOffer(trade)

        then:
        1 * attempts.findLatestWithOffer(7L, _ as Pageable) >> row('active')
        0 * off.cancelOffer(_)
        r.outcome == BotDeliveryOfferGuard.Outcome.FAILED
    }

    def "an unreadable delivery log is unsafe"() {
        when:
        def r = guard.cancelLiveOffer(trade)

        then:
        1 * attempts.findLatestWithOffer(7L, _ as Pageable) >> { throw new RuntimeException('db down') }
        r.outcome == BotDeliveryOfferGuard.Outcome.FAILED
    }

    def "requireNoLiveOfferBeforeRefund throws a distinct code for accepted and for failed"() {
        when:
        guard.requireNoLiveOfferBeforeRefund(trade)

        then:
        1 * attempts.findLatestWithOffer(7L, _ as Pageable) >> row(state)
        bot.cancelOffer('987') >> answer
        def e = thrown(BadRequestException)
        e.code == code

        where:
        state    | answer                                               || code
        'active' | accepted('accepted')                                 || 'OFFER_ALREADY_ACCEPTED'
        'active' | SteamBotResult.error('TIMEOUT', 'request timeout')   || 'OFFER_CANCEL_FAILED'
    }

    // ── force-release ──────────────────────────────────────────────────────

    def "force-release: an offer the buyer never accepted blocks it"() {
        when:
        def why = guard.undeliveredReason(trade)

        then:
        1 * attempts.findLatestWithOffer(7L, _ as Pageable) >> row('expired')
        1 * bot.getOfferStatus('987') >> SteamBotResult.success([ok: true, offerId: '987', status: 'expired'])
        why.contains('never accepted')
    }

    def "force-release: an accept the poller has not recorded yet is read live and allows it"() {
        when:
        def why = guard.undeliveredReason(trade)

        then:
        1 * attempts.findLatestWithOffer(7L, _ as Pageable) >> row('active')
        1 * bot.getOfferStatus('987') >> SteamBotResult.success([ok: true, offerId: '987', status: 'accepted'])
        1 * attempts.save({ SteamDeliveryAttempt a -> a.offerState == 'accepted' })
        1 * escrow.markDelivered(10L)
        why == null
    }

    def "force-release: a manual trade (no bot offer) or a recorded accept is allowed"() {
        given:
        attempts.findLatestWithOffer(7L, _ as Pageable) >>> [[], row('accepted')]

        expect:
        guard.undeliveredReason(trade) == null
        guard.undeliveredReason(trade) == null
    }

    // ── TradeService.dispute: the protection auto-claim ────────────────────

    def "dispute: an ACTIVE cover pays only once the live offer is cancelled"() {
        given:
        def protection = Mock(TradeProtectionService)
        def guardMock = Mock(BotDeliveryOfferGuard)
        def svc = new TradeService(botDeliveryOfferGuard: guardMock, tradeProtectionService: protection)

        when:
        boolean pay = svc.deliveryOfferDeadBeforeClaim(trade)

        then:
        1 * protection.findForTrade(7L) >> new TradeProtection(status: TradeProtection.ACTIVE)
        1 * guardMock.cancelLiveOffer(trade) >> new BotDeliveryOfferGuard.Result(outcome: outcome)
        pay == expected

        where:
        outcome                                     || expected
        BotDeliveryOfferGuard.Outcome.NO_LIVE_OFFER || true
        BotDeliveryOfferGuard.Outcome.CANCELLED     || true
        BotDeliveryOfferGuard.Outcome.ACCEPTED      || false
        BotDeliveryOfferGuard.Outcome.FAILED        || false
    }

    def "dispute: an unprotected trade's offer is left alone"() {
        given:
        def protection = Mock(TradeProtectionService)
        def guardMock = Mock(BotDeliveryOfferGuard)
        def svc = new TradeService(botDeliveryOfferGuard: guardMock, tradeProtectionService: protection)

        when:
        boolean pay = svc.deliveryOfferDeadBeforeClaim(trade)

        then:
        1 * protection.findForTrade(7L) >> null
        0 * guardMock.cancelLiveOffer(_)
        pay
    }

    // ── SteamDeliveryService: a cancel that lands during the send ──────────

    def "delivery: a trade cancelled while the bot was sending gets the new offer withdrawn"() {
        given:
        def tradeRepo = Mock(TradeRepository)
        def users = Mock(SteamUserRepository)
        def tradeService = Mock(TradeService)
        def notifications = Mock(NotificationService)
        def delivery = new SteamDeliveryService(steamTradeBotService: bot, tradeService: tradeService,
                tradeRepository: tradeRepo, attemptRepository: attempts, steamUserRepository: users,
                notificationService: notifications, steamEscrowService: escrow, pollerEnabled: true,
                offerMessage: 'sboxmarket delivery', batchSize: 50)
        delivery.testAssetIdOverride = '555'
        escrow.heldAssetIdForListing(_) >> null
        def sending = new Trade(id: 7L, listingId: 10L, buyerUserId: 2L, sellerUserId: 1L,
                state: SteamDeliveryService.STATE_AWAITING_SEND)
        def cancelledMeanwhile = new Trade(id: 7L, listingId: 10L, buyerUserId: 2L, sellerUserId: 1L, state: 'CANCELLED')

        when:
        delivery.processTrade(7L)

        then:
        2 * tradeRepo.findById(7L) >>> [Optional.of(sending), Optional.of(cancelledMeanwhile)]
        attempts.findLatestWithOffer(7L, _ as Pageable) >> []
        attempts.findByTrade(7L, _ as Pageable) >> []
        users.findById(2L) >> Optional.of(new SteamUser(id: 2L,
                tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=2&token=abc'))
        1 * bot.sendOffer(_, ['555'], _) >> SteamBotResult.success([ok: true, offerId: '987', status: 'sent'])
        1 * bot.cancelOffer('987') >> SteamBotResult.success([ok: true, offerId: '987', status: 'canceled', cancelled: true])
        1 * attempts.save({ SteamDeliveryAttempt a -> a.phase == 'SEND' && a.steamOfferId == '987' })
        1 * attempts.save({ SteamDeliveryAttempt a -> a.phase == 'POLL' && a.offerState == 'canceled' })

        and: "the cancelled trade is not marked sent and the buyer is not told to accept"
        0 * tradeService.sellerMarkSent(_, _, _)
        0 * notifications.safePush(_, _, _, _, _, _)
    }
}
