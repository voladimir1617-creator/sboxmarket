package com.sboxmarket

import com.sboxmarket.config.ApiKeyAuthFilter
import com.sboxmarket.config.ClientIpResolver
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.model.SteamDeliveryAttempt
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Trade
import com.sboxmarket.repository.SteamDeliveryAttemptRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.SteamBotResult
import com.sboxmarket.service.SteamDeliveryService
import com.sboxmarket.service.SteamEscrowService
import com.sboxmarket.service.SteamTradeBotService
import com.sboxmarket.service.TradeService
import org.springframework.mock.web.MockHttpServletRequest
import spock.lang.Specification

/**
 * Defect pass 11 (unit level): bot delivery polling a dead offer, the
 * buyer's offer link, API keys reaching logout-all, and the sign-in alert
 * trusting spoofed forwarding headers.
 */
class DefectPassElevenDeliverySpec extends Specification {

    SteamDeliveryService service
    SteamTradeBotService bot = Mock()
    TradeService tradeService = Mock()
    TradeRepository tradeRepository = Mock()
    SteamDeliveryAttemptRepository attemptRepository = Mock()
    SteamUserRepository steamUserRepository = Mock()
    NotificationService notificationService = Mock()
    SteamEscrowService escrowService = Mock()

    def setup() {
        service = new SteamDeliveryService()
        service.steamTradeBotService = bot
        service.tradeService = tradeService
        service.tradeRepository = tradeRepository
        service.attemptRepository = attemptRepository
        service.steamUserRepository = steamUserRepository
        service.notificationService = notificationService
        service.steamEscrowService = escrowService
        service.pollerEnabled = true
        service.offerMessage = 'delivery'
        service.batchSize = 50
        escrowService.heldAssetIdForListing(_) >> null
        service.testAssetIdOverride = '555'
    }

    private static Trade trade(String state) {
        new Trade(id: 7L, state: state, buyerUserId: 2L, sellerUserId: 1L, listingId: 10L, itemId: 20L,
            itemName: 'Hat', price: new BigDecimal('10.00'))
    }

    def "a declined offer is not re-polled and re-announced every tick"() {
        given: "the last poll already recorded the decline"
        tradeRepository.findById(7L) >> Optional.of(trade(SteamDeliveryService.STATE_AWAITING_CONFIRM))
        attemptRepository.findLatestWithOffer(7L, _) >> [new SteamDeliveryAttempt(id: 2L, tradeId: 7L,
            steamOfferId: '987', offerState: 'declined', phase: 'POLL', success: true)]

        when:
        service.processTrade(7L)

        then:
        0 * bot.getOfferStatus(_)
        0 * notificationService._
    }

    def "the buyer's offer link points at the bot's Steam offer, not their own trade URL"() {
        given:
        tradeRepository.findById(7L) >> Optional.of(trade(SteamDeliveryService.STATE_AWAITING_SEND))
        attemptRepository.findLatestWithOffer(7L, _) >> []
        steamUserRepository.findById(2L) >> Optional.of(new SteamUser(id: 2L, steamId64: '76561190000000002',
            tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=2&token=abc'))
        bot.sendOffer(_, _, _) >> new SteamBotResult(ok: true, offerId: '987', status: 'sent')

        when:
        service.processTrade(7L)

        then:
        1 * tradeService.sellerMarkSent(1L, 7L, 'https://steamcommunity.com/tradeoffer/987/')
    }

    def "an API key cannot call the sign-out-everywhere endpoint"() {
        expect:
        ApiKeyAuthFilter.forbiddenForApiKey('/api/auth/steam/logout-all', true)
    }

    def "the new sign-in alert uses the trusted client IP, not a spoofable header"() {
        given:
        def controller = new SteamAuthController()
        ClientIpResolver resolver = Mock()
        resolver.resolve(_) >> '203.0.113.7'
        controller.clientIpResolver = resolver
        def req = new MockHttpServletRequest()
        req.addHeader('CF-Connecting-IP', '198.51.100.99')

        expect:
        controller.clientIp(req) == '203.0.113.7'
    }
}
