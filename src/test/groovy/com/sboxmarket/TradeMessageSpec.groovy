package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.model.Trade
import com.sboxmarket.model.TradeMessage
import com.sboxmarket.repository.TradeMessageRepository
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.TradeService
import com.sboxmarket.service.security.AdminAuthorization
import com.sboxmarket.service.security.BanGuard
import spock.lang.Specification
import spock.lang.Subject

/**
 * Focused coverage for the trade-chat path added in batch 78.
 * Happy path, auth, state guards, rate limit.
 */
class TradeMessageSpec extends Specification {

    TradeRepository           tradeRepository        = Mock()
    TradeMessageRepository    tradeMessageRepository = Mock()
    NotificationService       notificationService    = Mock()
    AdminAuthorization        adminAuthorization     = Mock()
    BanGuard                  banGuard               = Mock()
    TextSanitizer             textSanitizer          = Mock() {
        // Default pass-through; specific tests override when needed.
        clean(_, _) >> { args -> args[0] }
    }

    @Subject
    TradeService service = new TradeService(
        tradeRepository:        tradeRepository,
        tradeMessageRepository: tradeMessageRepository,
        notificationService:    notificationService,
        adminAuthorization:     adminAuthorization,
        banGuard:               banGuard,
        textSanitizer:          textSanitizer
    )

    private Trade tradeFor(long id, long buyer, long seller, String state = 'PENDING_SELLER_SEND') {
        new Trade(id: id, buyerUserId: buyer, sellerUserId: seller, state: state,
            itemName: 'Wizard Hat')
    }

    // ── postMessage ─────────────────────────────────────────────

    def "postMessage persists + notifies the counterparty"() {
        given:
        tradeRepository.findById(100L) >> Optional.of(tradeFor(100L, 42L, 99L))
        tradeMessageRepository.countBySenderUserIdAndCreatedAtGreaterThan(42L, _) >> 0L
        tradeMessageRepository.save(_) >> { args -> args[0].id = 1L; args[0] }

        when:
        def m = service.postMessage(100L, 42L, 'Hey, sending the offer now')

        then:
        m.tradeId == 100L
        m.senderUserId == 42L
        m.body == 'Hey, sending the offer now'
        // Counterparty = seller 99
        1 * notificationService.push(99L, 'TRADE_MESSAGE', _, _, 100L, _)
    }

    def "postMessage refuses non-participants"() {
        given:
        tradeRepository.findById(100L) >> Optional.of(tradeFor(100L, 42L, 99L))

        when:
        service.postMessage(100L, 500L, 'hi')

        then:
        thrown(ForbiddenException)
    }

    def "postMessage refuses after the trade is VERIFIED or CANCELLED"() {
        given:
        tradeRepository.findById(100L) >> Optional.of(tradeFor(100L, 42L, 99L, state))

        when:
        service.postMessage(100L, 42L, 'hi')

        then:
        thrown(BadRequestException)
        0 * tradeMessageRepository.save(_)

        where:
        state << ['VERIFIED', 'CANCELLED']
    }

    def "postMessage refuses an empty body"() {
        given:
        tradeRepository.findById(100L) >> Optional.of(tradeFor(100L, 42L, 99L))
        textSanitizer.clean(_, _) >> ''

        when:
        service.postMessage(100L, 42L, '   ')

        then:
        thrown(BadRequestException)
    }

    def "postMessage enforces the per-sender 10-minute rate limit"() {
        given:
        tradeRepository.findById(100L) >> Optional.of(tradeFor(100L, 42L, 99L))
        tradeMessageRepository.countBySenderUserIdAndCreatedAtGreaterThan(42L, _) >> 30L

        when:
        service.postMessage(100L, 42L, 'hi')

        then:
        def ex = thrown(BadRequestException)
        ex.code == 'CHAT_RATE_LIMITED'
        0 * tradeMessageRepository.save(_)
    }

    def "postMessage truncates long previews in the notification body"() {
        given:
        tradeRepository.findById(100L) >> Optional.of(tradeFor(100L, 42L, 99L))
        tradeMessageRepository.countBySenderUserIdAndCreatedAtGreaterThan(_, _) >> 0L
        tradeMessageRepository.save(_) >> { args -> args[0].id = 1L; args[0] }
        def longBody = 'a' * 200

        when:
        service.postMessage(100L, 42L, longBody)

        then:
        1 * notificationService.push(99L, 'TRADE_MESSAGE', _, { String preview ->
            preview.length() <= 80 && preview.endsWith('…')
        }, 100L, _)
    }

    // ── listMessages ────────────────────────────────────────────

    def "listMessages returns the full thread to a participant"() {
        given:
        tradeRepository.findById(100L) >> Optional.of(tradeFor(100L, 42L, 99L))
        def msgs = [
            new TradeMessage(id: 1L, tradeId: 100L, senderUserId: 42L, body: 'hi'),
            new TradeMessage(id: 2L, tradeId: 100L, senderUserId: 99L, body: 'hey')
        ]
        tradeMessageRepository.findByTrade(100L) >> msgs

        when:
        def out = service.listMessages(100L, 99L)

        then:
        out.size() == 2
    }

    def "listMessages refuses non-participants + non-admins"() {
        given:
        tradeRepository.findById(100L) >> Optional.of(tradeFor(100L, 42L, 99L))
        adminAuthorization.isAdmin(500L) >> false

        when:
        service.listMessages(100L, 500L)

        then:
        thrown(ForbiddenException)
    }

    def "listMessages lets admins read any trade's thread"() {
        given:
        tradeRepository.findById(100L) >> Optional.of(tradeFor(100L, 42L, 99L))
        adminAuthorization.isAdmin(1L) >> true
        tradeMessageRepository.findByTrade(100L) >> []

        when:
        def out = service.listMessages(100L, 1L)

        then:
        out == []
    }
}
