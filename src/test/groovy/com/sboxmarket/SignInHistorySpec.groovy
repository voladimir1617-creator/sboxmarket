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
 * HTTP-level pinning for GET /api/profile/sign-in-history (batch 569).
 *
 * Verifies:
 *   - the endpoint is auth-gated
 *   - returns up to 20 of the caller's OWN USER_SIGN_IN audit rows,
 *     newest-first
 *   - does NOT leak other users' sign-ins (subjectUserId scoping)
 *   - each row carries timestamp + IP + user-agent for UX rendering
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SignInHistorySpec extends Specification {

    @Autowired ApplicationContext ctx

    MockMvc             mockMvc
    SteamUserRepository steamUserRepository
    AuditLogRepository  auditLogRepository

    SteamUser       me
    SteamUser       someoneElse
    MockHttpSession session

    def setup() {
        mockMvc             = ctx.getBean(MockMvc)
        steamUserRepository = ctx.getBean(SteamUserRepository)
        auditLogRepository  = ctx.getBean(AuditLogRepository)

        def uniq = String.valueOf(System.nanoTime())
        me = steamUserRepository.save(new SteamUser(
            steamId64:   "76561199" + uniq.substring(uniq.length() - 9),
            displayName: "SignInSpec-${uniq}"
        ))
        someoneElse = steamUserRepository.save(new SteamUser(
            steamId64:   "76561198" + uniq.substring(uniq.length() - 9),
            displayName: "OtherUser-${uniq}"
        ))

        // Plant three sign-ins for me + one for somebody else — the
        // endpoint should return my three, in newest-first order,
        // and never leak theirs.
        long now = System.currentTimeMillis()
        auditLogRepository.save(new AuditLog(
            eventType: 'USER_SIGN_IN', actorUserId: me.id, subjectUserId: me.id,
            createdAt: now - 10_000L, ipAddress: '203.0.113.1', userAgent: 'Chrome/120'))
        auditLogRepository.save(new AuditLog(
            eventType: 'USER_SIGN_IN', actorUserId: me.id, subjectUserId: me.id,
            createdAt: now - 5_000L,  ipAddress: '203.0.113.2', userAgent: 'Firefox/121'))
        auditLogRepository.save(new AuditLog(
            eventType: 'USER_SIGN_IN', actorUserId: me.id, subjectUserId: me.id,
            createdAt: now,           ipAddress: '203.0.113.3', userAgent: 'Edg/120'))
        auditLogRepository.save(new AuditLog(
            eventType:     'USER_SIGN_IN',
            actorUserId:   someoneElse.id,
            subjectUserId: someoneElse.id,
            createdAt: now, ipAddress: '10.0.0.99', userAgent: 'SecretBrowser/99'))

        session = new MockHttpSession()
        session.setAttribute(SteamAuthController.SESSION_USER_ID, me.id)
    }

    def "GET /sign-in-history 401s for anonymous callers"() {
        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/profile/sign-in-history')
        ).andReturn()

        then:
        result.response.status == 401
    }

    def "GET /sign-in-history returns the caller's own USER_SIGN_IN rows newest-first"() {
        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/profile/sign-in-history').session(session)
        ).andReturn()

        then:
        result.response.status == 200
        def body = result.response.contentAsString
        // My three IPs must be there, in newest-first order.
        body.indexOf('203.0.113.3') < body.indexOf('203.0.113.2')
        body.indexOf('203.0.113.2') < body.indexOf('203.0.113.1')
        // Other user's row must NOT appear — subjectUserId scoping.
        !body.contains('10.0.0.99')
        !body.contains('SecretBrowser')
        // Each row surfaces UA so the UI can render "Chrome" / "Firefox" etc.
        body.contains('Chrome/120')
        body.contains('Firefox/121')
    }
}
