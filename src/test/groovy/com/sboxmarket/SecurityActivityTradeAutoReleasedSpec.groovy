package com.sboxmarket

import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.model.AuditLog
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.AuditLogRepository
import com.sboxmarket.repository.SteamUserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import spock.lang.Specification

/**
 * HTTP pin for GET /api/profile/security-activity covering the
 * TRADE_AUTO_RELEASED gap closed in this commit.
 *
 * Wave 109/110/111 fixed the producer side (TradeService:1558 now sets
 * subjectUserId = sellerUserId on the sweeper write, so the row is
 * scoped to the seller's history). The consumer-side whitelist in
 * ProfileController.securityActivity then needed to include the event
 * type — otherwise the bySubject() fetch returns the row and the
 * findAll filter drops it, leaving the seller blind to the fact their
 * escrowed funds were auto-released.
 *
 * Also pins the negative — an unrelated AuditService event type
 * (ADMIN_NOTES_UPDATED) is still suppressed so a future contributor
 * extending the producer side can't accidentally leak internal-only
 * rows by piggybacking on a subject scope.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityActivityTradeAutoReleasedSpec extends Specification {

    @Autowired ApplicationContext ctx

    MockMvc             mockMvc
    SteamUserRepository steamUserRepository
    AuditLogRepository  auditLogRepository

    SteamUser       seller
    MockHttpSession session

    def setup() {
        mockMvc             = ctx.getBean(MockMvc)
        steamUserRepository = ctx.getBean(SteamUserRepository)
        auditLogRepository  = ctx.getBean(AuditLogRepository)

        def uniq = String.valueOf(System.nanoTime())
        seller = steamUserRepository.save(new SteamUser(
            steamId64:   "76561199" + uniq.substring(uniq.length() - 9),
            displayName: "SecActSpec-${uniq}"
        ))

        long now = System.currentTimeMillis()
        // Sweeper-style row: actor=null (system), subject=seller,
        // matches the literal AuditService.log() call at
        // TradeService:1558. Summary text is the load-bearing assertion
        // — without the whitelist patch, the row exists in the DB and
        // bySubject() returns it, but the consumer-side findAll drops
        // it because TRADE_AUTO_RELEASED wasn't in the set.
        auditLogRepository.save(new AuditLog(
            eventType:     'TRADE_AUTO_RELEASED',
            actorUserId:   null,
            subjectUserId: seller.id,
            resourceId:    999L,
            createdAt:     now,
            summary:       'Auto-released after 5d no-confirm window'))

        // Internal-only event the user should NEVER see — pin the
        // negative so a future whitelist contributor can't broaden the
        // filter to e.g. `r.eventType.startsWith('TRADE_')` and leak
        // staff workflow rows.
        auditLogRepository.save(new AuditLog(
            eventType:     'ADMIN_NOTES_UPDATED',
            actorUserId:   42L,
            subjectUserId: seller.id,
            createdAt:     now - 1_000L,
            summary:       'private-internal-staff-only-notes'))

        session = new MockHttpSession()
        session.setAttribute(SteamAuthController.SESSION_USER_ID, seller.id)
    }

    def "GET /security-activity surfaces TRADE_AUTO_RELEASED to the subject seller"() {
        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/profile/security-activity').session(session)
        ).andReturn()

        then:
        result.response.status == 200
        def body = result.response.contentAsString
        // Wave 109/110/111 fix — the row must surface so the seller can
        // see "your escrow auto-released" in their own security feed.
        body.contains('TRADE_AUTO_RELEASED')
        body.contains('Auto-released after 5d no-confirm window')
        // Whitelist-negative pin — ADMIN_NOTES_UPDATED must NOT leak,
        // even though it shares the subjectUserId scope.
        !body.contains('ADMIN_NOTES_UPDATED')
        !body.contains('private-internal-staff-only-notes')
    }

    def "GET /security-activity 401s for anonymous callers"() {
        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/profile/security-activity')
        ).andReturn()

        then:
        result.response.status == 401
    }
}
