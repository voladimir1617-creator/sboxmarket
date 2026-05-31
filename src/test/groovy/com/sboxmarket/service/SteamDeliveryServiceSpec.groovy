package com.sboxmarket.service

import com.sboxmarket.model.SteamDeliveryAttempt
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Trade
import com.sboxmarket.repository.SteamDeliveryAttemptRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TradeRepository
import org.springframework.data.domain.Pageable
import spock.lang.Specification

/**
 * Unit tests for the automated Steam-delivery orchestrator. The bot service,
 * TradeService, repositories and notifications are all mocked, so no sidecar /
 * DB is needed. Covers: disabled-mode no-op, the send-success path, the
 * accepted -> credit path (driven through the EXISTING TradeService
 * transitions), and the decline/expire/escrow/transient branches.
 */
class SteamDeliveryServiceSpec extends Specification {

    SteamDeliveryService service
    SteamTradeBotService bot = Mock()
    TradeService tradeService = Mock()
    TradeRepository tradeRepository = Mock()
    SteamDeliveryAttemptRepository attemptRepository = Mock()
    SteamUserRepository steamUserRepository = Mock()
    NotificationService notificationService = Mock()

    def setup() {
        service = new SteamDeliveryService()
        service.steamTradeBotService = bot
        service.tradeService = tradeService
        service.tradeRepository = tradeRepository
        service.attemptRepository = attemptRepository
        service.steamUserRepository = steamUserRepository
        service.notificationService = notificationService
        service.pollerEnabled = true
        service.offerMessage = 'sboxmarket delivery'
        service.batchSize = 50
        // By default the platform has no asset id for the sold item (honest
        // limitation). Tests exercising the send path set this override.
        service.testAssetIdOverride = '555'
    }

    private static Trade trade(Map o = [:]) {
        new Trade(
                id: (Long) (o.id ?: 7L),
                state: (o.state ?: SteamDeliveryService.STATE_AWAITING_SEND) as String,
                buyerUserId: o.containsKey('buyerUserId') ? (o.buyerUserId as Long) : 2L,
                sellerUserId: o.containsKey('sellerUserId') ? (o.sellerUserId as Long) : 1L,
                listingId: 10L,
                itemId: 20L,
                itemName: 'AK-47 | Redline',
                price: new BigDecimal('100.00')
        )
    }

    private SteamUser buyerWithUrl(String url = 'https://steamcommunity.com/tradeoffer/new/?partner=2&token=abc') {
        new SteamUser(id: 2L, steamId64: '76561190000000002', tradeUrl: url)
    }

    private static SteamDeliveryAttempt offerRow(String offerId = '987', String state = 'sent') {
        new SteamDeliveryAttempt(id: 1L, tradeId: 7L, steamOfferId: offerId, offerState: state, phase: 'SEND', success: true)
    }

    // ── disabled-mode ──────────────────────────────────────────────────────

    def "poller is a no-op when the bot is disabled"() {
        given:
        bot.enabled >> false

        when:
        service.pollDeliveries()

        then:
        0 * tradeRepository.findByStateIn(_, _)
        0 * bot.sendOffer(_, _, _)
    }

    def "poller is a no-op when pollerEnabled is false even if the bot is enabled"() {
        given:
        service.pollerEnabled = false

        when:
        service.pollDeliveries()

        then:
        0 * bot._
        0 * tradeRepository._
    }

    // ── send path ──────────────────────────────────────────────────────────

    def "send: SELLER_SEND with no prior offer sends an offer, records it, and marks sent"() {
        given:
        def t = trade(state: SteamDeliveryService.STATE_AWAITING_SEND)

        when:
        service.processTrade(7L)

        then:
        1 * tradeRepository.findById(7L) >> Optional.of(t)
        // no prior offer row
        1 * attemptRepository.findLatestWithOffer(7L, _ as Pageable) >> []
        // buyer trade URL resolved once and reused for the mark-sent offer url
        1 * steamUserRepository.findById(2L) >> Optional.of(buyerWithUrl())
        1 * bot.sendOffer('https://steamcommunity.com/tradeoffer/new/?partner=2&token=abc', ['555'], 'sboxmarket delivery') >>
                SteamBotResult.success([ok: true, offerId: '987', status: 'sent'])
        // offer recorded as an attempt row (authoritative offer store)
        1 * attemptRepository.save({ SteamDeliveryAttempt a -> a.steamOfferId == '987' && a.phase == 'SEND' && a.success })
        // drives the EXISTING transition on the seller's behalf
        1 * tradeService.sellerMarkSent(1L, 7L, 'https://steamcommunity.com/tradeoffer/new/?partner=2&token=abc')
        1 * notificationService.safePush(2L, _, _, _, _, _)
    }

    def "send: missing buyer trade URL does not send and records NO_TRADE_URL"() {
        given:
        def t = trade(state: SteamDeliveryService.STATE_AWAITING_SEND)

        when:
        service.processTrade(7L)

        then:
        1 * tradeRepository.findById(7L) >> Optional.of(t)
        1 * attemptRepository.findLatestWithOffer(7L, _ as Pageable) >> []
        1 * steamUserRepository.findById(2L) >> Optional.of(buyerWithUrl(null))
        0 * bot.sendOffer(_, _, _)
        1 * attemptRepository.save({ SteamDeliveryAttempt a -> a.offerState == 'NO_TRADE_URL' && !a.success })
        0 * tradeService.sellerMarkSent(_, _, _)
    }

    def "send: missing asset id does not send and records NO_ASSET_ID"() {
        given:
        service.testAssetIdOverride = ''   // platform has no asset id (honest limitation)
        def t = trade(state: SteamDeliveryService.STATE_AWAITING_SEND)

        when:
        service.processTrade(7L)

        then:
        1 * tradeRepository.findById(7L) >> Optional.of(t)
        1 * attemptRepository.findLatestWithOffer(7L, _ as Pageable) >> []
        1 * steamUserRepository.findById(2L) >> Optional.of(buyerWithUrl())
        0 * bot.sendOffer(_, _, _)
        1 * attemptRepository.save({ SteamDeliveryAttempt a -> a.offerState == 'NO_ASSET_ID' && !a.success })
        0 * tradeService.sellerMarkSent(_, _, _)
    }

    def "send: transient send failure records the error and does NOT mark sent"() {
        given:
        def t = trade(state: SteamDeliveryService.STATE_AWAITING_SEND)

        when:
        service.processTrade(7L)

        then:
        1 * tradeRepository.findById(7L) >> Optional.of(t)
        1 * attemptRepository.findLatestWithOffer(7L, _ as Pageable) >> []
        1 * steamUserRepository.findById(2L) >> Optional.of(buyerWithUrl())
        1 * bot.sendOffer(_, _, _) >> SteamBotResult.error('RATE_LIMITED', 'slow down')
        1 * attemptRepository.save({ SteamDeliveryAttempt a -> a.offerState == 'RATE_LIMITED' && !a.success })
        0 * tradeService.sellerMarkSent(_, _, _)
    }

    // ── poll -> accepted -> credit (existing TradeService release path) ──────

    def "poll: accepted on a BUYER_CONFIRM trade credits the seller via buyerConfirm"() {
        given:
        def t = trade(state: SteamDeliveryService.STATE_AWAITING_CONFIRM)

        when:
        service.processTrade(7L)

        then:
        1 * tradeRepository.findById(7L) >> Optional.of(t)
        1 * attemptRepository.findLatestWithOffer(7L, _ as Pageable) >> [offerRow('987', 'sent')]
        1 * bot.getOfferStatus('987') >> SteamBotResult.success([ok: true, offerId: '987', status: 'accepted'])
        // THE CREDIT PATH: existing public transition with the real buyer id.
        1 * tradeService.buyerConfirm(2L, 7L)
        // attempt rows: one for the poll status, one for the verified outcome
        (1.._) * attemptRepository.save(_)
    }

    def "poll: accepted on a still-SELLER_SEND trade first marks sent, then credits"() {
        given:
        def t = trade(state: SteamDeliveryService.STATE_AWAITING_SEND)
        def advanced = trade(state: SteamDeliveryService.STATE_AWAITING_CONFIRM)

        when:
        service.processTrade(7L)

        then:
        // First findById (processTrade) -> SELLER_SEND; second (post-markSent
        // re-load) -> BUYER_CONFIRM. Sequential >>> is order-deterministic.
        2 * tradeRepository.findById(7L) >>> [Optional.of(t), Optional.of(advanced)]
        1 * attemptRepository.findLatestWithOffer(7L, _ as Pageable) >> [offerRow('987', 'active')]
        1 * bot.getOfferStatus('987') >> SteamBotResult.success([ok: true, offerId: '987', status: 'accepted'])
        _ * attemptRepository.save(_)
        // mark-sent runs first (needs the buyer trade URL for the offer-url arg)
        1 * steamUserRepository.findById(2L) >> Optional.of(buyerWithUrl())
        1 * tradeService.sellerMarkSent(1L, 7L, _ as String)
        // then buyerConfirm on the re-loaded BUYER_CONFIRM trade
        1 * tradeService.buyerConfirm(2L, 7L)
    }

    def "poll: buyerConfirm benign race is swallowed (no throw out of poller)"() {
        given:
        def t = trade(state: SteamDeliveryService.STATE_AWAITING_CONFIRM)

        when:
        service.processTrade(7L)

        then:
        1 * tradeRepository.findById(7L) >> Optional.of(t)
        1 * attemptRepository.findLatestWithOffer(7L, _ as Pageable) >> [offerRow('987', 'sent')]
        1 * bot.getOfferStatus('987') >> SteamBotResult.success([ok: true, status: 'accepted'])
        _ * attemptRepository.save(_)
        1 * tradeService.buyerConfirm(2L, 7L) >> { throw new IllegalStateException('Trade cannot be confirmed in state VERIFIED') }
        noExceptionThrown()
    }

    // ── poll -> terminal / escrow / transient ───────────────────────────────

    def "poll: declined notifies both sides and does NOT credit"() {
        given:
        def t = trade(state: SteamDeliveryService.STATE_AWAITING_CONFIRM)

        when:
        service.processTrade(7L)

        then:
        1 * tradeRepository.findById(7L) >> Optional.of(t)
        1 * attemptRepository.findLatestWithOffer(7L, _ as Pageable) >> [offerRow('987', 'active')]
        1 * bot.getOfferStatus('987') >> SteamBotResult.success([ok: true, offerId: '987', status: 'declined'])
        1 * attemptRepository.save({ SteamDeliveryAttempt a -> a.offerState == 'declined' })
        0 * tradeService.buyerConfirm(_, _)
        0 * tradeService.sellerMarkSent(_, _, _)
        1 * notificationService.safePush(1L, _, _, _, _, _)  // seller
        1 * notificationService.safePush(2L, _, _, _, _, _)  // buyer
    }

    def "poll: expired does NOT credit"() {
        given:
        def t = trade(state: SteamDeliveryService.STATE_AWAITING_CONFIRM)

        when:
        service.processTrade(7L)

        then:
        1 * tradeRepository.findById(7L) >> Optional.of(t)
        1 * attemptRepository.findLatestWithOffer(7L, _ as Pageable) >> [offerRow('987', 'active')]
        1 * bot.getOfferStatus('987') >> SteamBotResult.success([ok: true, status: 'expired'])
        _ * attemptRepository.save(_)
        0 * tradeService.buyerConfirm(_, _)
    }

    def "poll: in_escrow keeps waiting, no credit"() {
        given:
        def t = trade(state: SteamDeliveryService.STATE_AWAITING_CONFIRM)

        when:
        service.processTrade(7L)

        then:
        1 * tradeRepository.findById(7L) >> Optional.of(t)
        1 * attemptRepository.findLatestWithOffer(7L, _ as Pageable) >> [offerRow('987', 'active')]
        1 * bot.getOfferStatus('987') >> SteamBotResult.success([ok: true, status: 'in_escrow'])
        1 * attemptRepository.save({ SteamDeliveryAttempt a -> a.offerState == 'in_escrow' })
        0 * tradeService.buyerConfirm(_, _)
    }

    def "poll: transient failure records error and does not credit"() {
        given:
        def t = trade(state: SteamDeliveryService.STATE_AWAITING_CONFIRM)

        when:
        service.processTrade(7L)

        then:
        1 * tradeRepository.findById(7L) >> Optional.of(t)
        1 * attemptRepository.findLatestWithOffer(7L, _ as Pageable) >> [offerRow('987', 'active')]
        1 * bot.getOfferStatus('987') >> SteamBotResult.error('TRANSPORT_ERROR', 'connection refused')
        1 * attemptRepository.save({ SteamDeliveryAttempt a -> a.offerState == 'TRANSPORT_ERROR' && !a.success })
        0 * tradeService.buyerConfirm(_, _)
    }

    // ── state guards ────────────────────────────────────────────────────────

    def "processTrade ignores BUYER_CONFIRM trades with no bot offer (manual/legacy)"() {
        given:
        def t = trade(state: SteamDeliveryService.STATE_AWAITING_CONFIRM)

        when:
        service.processTrade(7L)

        then:
        1 * tradeRepository.findById(7L) >> Optional.of(t)
        1 * attemptRepository.findLatestWithOffer(7L, _ as Pageable) >> []
        0 * bot._
        0 * tradeService._
    }

    def "processTrade ignores terminal and other states"() {
        given:
        def t = trade(state: 'VERIFIED')

        when:
        service.processTrade(7L)

        then:
        1 * tradeRepository.findById(7L) >> Optional.of(t)
        1 * attemptRepository.findLatestWithOffer(7L, _ as Pageable) >> []
        0 * bot._
        0 * tradeService._
    }

    def "pollDeliveries iterates send + confirm queues and isolates per-trade errors"() {
        given:
        bot.enabled >> true
        def t1 = trade(id: 11L, state: SteamDeliveryService.STATE_AWAITING_SEND)
        def t2 = trade(id: 12L, state: SteamDeliveryService.STATE_AWAITING_CONFIRM)

        when:
        service.pollDeliveries()

        then:
        1 * tradeRepository.findByStateIn([SteamDeliveryService.STATE_AWAITING_SEND], _) >> [t1]
        1 * tradeRepository.findByStateIn([SteamDeliveryService.STATE_AWAITING_CONFIRM], _) >> [t2]
        // t1 processing blows up but must not abort the loop
        1 * tradeRepository.findById(11L) >> { throw new RuntimeException('boom') }
        // t2 processed normally (no bot offer -> ignored)
        1 * tradeRepository.findById(12L) >> Optional.of(t2)
        1 * attemptRepository.findLatestWithOffer(12L, _ as Pageable) >> []
        noExceptionThrown()
    }
}
