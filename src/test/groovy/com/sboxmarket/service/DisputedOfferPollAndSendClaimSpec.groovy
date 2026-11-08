package com.sboxmarket.service

import com.sboxmarket.model.SteamDeliveryAttempt
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Trade
import com.sboxmarket.repository.SteamDeliveryAttemptRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TradeRepository
import spock.lang.Specification

/**
 * Two delivery follow-ups from the offer-cancel work:
 *  - a buyer who accepts the bot's still-open offer after opening a dispute
 *    is recorded (custody DELIVERED), with no money moved;
 *  - the poller writes a SENDING claim before asking the bot to send, and the
 *    refund guard refuses to cancel while that claim is fresh.
 */
class DisputedOfferPollAndSendClaimSpec extends Specification {

    SteamDeliveryService service
    SteamTradeBotService bot = Mock()
    TradeService tradeService = Mock()
    TradeRepository tradeRepository = Mock()
    SteamDeliveryAttemptRepository attemptRepository = Mock()
    SteamUserRepository steamUserRepository = Mock()
    NotificationService notificationService = Mock()
    SteamEscrowService escrowService = Mock()

    def setup() {
        service = new SteamDeliveryService(
                steamTradeBotService: bot, tradeService: tradeService, tradeRepository: tradeRepository,
                attemptRepository: attemptRepository, steamUserRepository: steamUserRepository,
                notificationService: notificationService, steamEscrowService: escrowService,
                pollerEnabled: true, offerMessage: 'sboxmarket delivery', batchSize: 50,
                testAssetIdOverride: '555')
        escrowService.heldAssetIdForListing(_) >> null
    }

    private static Trade trade(String state) {
        new Trade(id: 7L, state: state, buyerUserId: 2L, sellerUserId: 1L, listingId: 10L, itemId: 20L,
                itemName: 'Jam Hat', price: 5.00G)
    }

    private static SteamDeliveryAttempt offerRow(String state) {
        new SteamDeliveryAttempt(id: 1L, tradeId: 7L, steamOfferId: '987', offerState: state, phase: 'POLL', success: true)
    }

    def "a buyer accepting a disputed trade's open offer is recorded and custody marked delivered, with no money moved"() {
        given:
        tradeRepository.findById(7L) >> Optional.of(trade('DISPUTED'))
        attemptRepository.findLatestWithOffer(7L, _) >> [offerRow('active')]
        bot.getOfferStatus('987') >> SteamBotResult.success([status: 'accepted'])

        when:
        service.processTrade(7L)

        then:
        1 * attemptRepository.save({ SteamDeliveryAttempt a -> a.offerState == 'accepted' && a.steamOfferId == '987' })
        1 * escrowService.markDelivered(10L)
        0 * tradeService.buyerConfirm(*_)
        0 * tradeService.sellerMarkSent(*_)
        0 * tradeService.cancel(*_)
    }

    def "a disputed trade's unchanged offer state writes no row"() {
        given:
        tradeRepository.findById(7L) >> Optional.of(trade('DISPUTED'))
        attemptRepository.findLatestWithOffer(7L, _) >> [offerRow('active')]
        bot.getOfferStatus('987') >> SteamBotResult.success([status: 'active'])

        when:
        service.processTrade(7L)

        then:
        0 * attemptRepository.save(_)
        0 * escrowService.markDelivered(_)
    }

    def "a disputed trade whose offer already settled is not polled"() {
        given:
        tradeRepository.findById(7L) >> Optional.of(trade('DISPUTED'))
        attemptRepository.findLatestWithOffer(7L, _) >> [offerRow(state)]

        when:
        service.processTrade(7L)

        then:
        0 * bot.getOfferStatus(_)

        where:
        state << ['accepted', 'declined', 'expired', 'canceled']
    }

    def "the poller picks up disputed trades with an open offer"() {
        given:
        bot.enabled >> true
        tradeRepository.findBotDeliveryCandidates(_, _) >> []
        tradeRepository.findById(7L) >> Optional.of(trade('DISPUTED'))
        attemptRepository.findLatestWithOffer(7L, _) >> [offerRow('active')]

        when:
        service.pollDeliveries()

        then:
        1 * tradeRepository.findDisputedWithOpenBotOffer(SteamDeliveryService.SETTLED_OFFER_STATES, _) >> [trade('DISPUTED')]
        1 * bot.getOfferStatus('987') >> SteamBotResult.success([status: 'active'])
    }

    def "a SENDING claim is written before the bot is asked to send"() {
        given:
        tradeRepository.findById(7L) >> Optional.of(trade('PENDING_SELLER_SEND'))
        attemptRepository.findLatestWithOffer(7L, _) >> []
        attemptRepository.findByTrade(7L, _) >> []
        steamUserRepository.findById(2L) >> Optional.of(new SteamUser(id: 2L, steamId64: '76561197960265730',
                tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=2&token=abc'))

        when:
        service.processTrade(7L)

        then:
        1 * attemptRepository.save({ SteamDeliveryAttempt a -> a.offerState == SteamDeliveryService.STATE_SENDING && a.steamOfferId == null && !a.success })

        then:
        1 * bot.sendOffer(*_) >> SteamBotResult.error('RATE_LIMITED', 'slow down')
    }

    def "the refund guard refuses while a fresh send claim is the trade's latest row, and ignores a stale one"() {
        given:
        def attempts = Stub(SteamDeliveryAttemptRepository)
        def guardBot = Mock(SteamTradeBotService)
        def guard = new BotDeliveryOfferGuard(steamTradeBotService: guardBot, attemptRepository: attempts)
        attempts.findByTrade(7L, _) >> [new SteamDeliveryAttempt(tradeId: 7L, offerState: SteamDeliveryService.STATE_SENDING,
                phase: 'SEND', success: false, createdAt: System.currentTimeMillis() - ageMs)]
        attempts.findLatestWithOffer(7L, _) >> []

        when:
        def r = guard.cancelLiveOffer(trade('PENDING_SELLER_SEND'))

        then:
        r.outcome == expected
        0 * guardBot.cancelOffer(_)

        where:
        ageMs                                          | expected
        1_000L                                         | BotDeliveryOfferGuard.Outcome.FAILED
        SteamDeliveryService.SEND_CLAIM_TTL_MS + 1_000 | BotDeliveryOfferGuard.Outcome.NO_LIVE_OFFER
    }

    def "a buyer's saved trade URL of another Steam account is not sent to, and the send halts"() {
        given:
        tradeRepository.findById(7L) >> Optional.of(trade('PENDING_SELLER_SEND'))
        attemptRepository.findLatestWithOffer(7L, _) >> []
        attemptRepository.findByTrade(7L, _) >> []
        steamUserRepository.findById(2L) >> Optional.of(new SteamUser(id: 2L, steamId64: '76561197960265730',
                tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=999&token=abc'))

        when:
        service.processTrade(7L)

        then:
        0 * bot.sendOffer(*_)
        1 * attemptRepository.save({ SteamDeliveryAttempt a -> a.offerState == 'TRADE_URL_NOT_OWNED' && !a.success &&
                a.errorMessage.startsWith(SteamBotResult.HALT_PREFIX) })
    }
}
