package com.sboxmarket

import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.AuditService
import com.sboxmarket.service.EmailService
import com.sboxmarket.service.SteamAuthService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpSession
import spock.lang.Specification
import spock.lang.Subject

/**
 * Coverage for the Steam OpenID adapter. Focus on the security-
 * sensitive /me / /logout / /logout-all paths — the /return handler's
 * OpenID verification + session fixation defense is covered by the
 * integration harness because it needs a real servlet container.
 *
 * Invariants pinned here:
 *
 *   1. `/me` 401s for anon AND for a stale session whose uid points
 *      to a deleted SteamUser row — and on that path, the stale
 *      session is `invalidate()`d so the client isn't left with a
 *      ghost cookie.
 *
 *   2. `/logout` is unconditionally 204 + session invalidated — never
 *      leaks whether the caller was signed in.
 *
 *   3. `/logout-all` bumps `sessionEpoch = System.currentTimeMillis()`,
 *      saves, invalidates the current session, and writes an audit
 *      log — matches CSFloat Visual §23's "log out all devices" flow.
 *
 *   4. `/login` redirects to the service's OpenID URL. The service
 *      builds the real URL; controller is a thin passthrough.
 *
 * Batch 1068 — added to close the coverage gap identified by walking
 * the controller list vs. the test directory.
 */
class SteamAuthControllerSpec extends Specification {

    SteamAuthService    steamAuthService    = Mock()
    SteamUserRepository steamUserRepository = Mock()
    AuditService        auditService        = Mock()
    EmailService        emailService        = Mock()

    @Subject
    SteamAuthController controller = new SteamAuthController(
        steamAuthService   : steamAuthService,
        steamUserRepository: steamUserRepository,
        auditService       : auditService,
        emailService       : emailService
    )

    HttpServletRequest  req  = Mock()
    HttpServletResponse resp = Mock()
    HttpSession         ses  = Mock()

    // ── /login redirects to Steam ───────────────────────────────

    def "login() redirects the browser to the service's OpenID URL"() {
        given:
        1 * steamAuthService.buildLoginUrl() >> 'https://steamcommunity.com/openid/login?...'

        when:
        controller.login(resp)

        then:
        1 * resp.sendRedirect('https://steamcommunity.com/openid/login?...')
    }

    // ── /me ─────────────────────────────────────────────────────

    def "me() returns 401 when no session user"() {
        given:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> null

        when:
        def r = controller.me(req)

        then:
        r.statusCode.value() == 401
        0 * steamUserRepository.findById(_)
    }

    def "me() returns the SteamUser body when session is valid"() {
        given:
        def user = new SteamUser(id: 100L, displayName: 'alice')
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 100L
        1 * steamUserRepository.findById(100L) >> Optional.of(user)

        when:
        def r = controller.me(req)

        then:
        r.statusCode.value() == 200
        r.body.is(user)
    }

    def "me() invalidates session + 401s when session uid points to a deleted user"() {
        given: 'session cookie outlived the account — e.g. admin deleted a user'
        // Controller reads `req.session` twice: once to get the uid,
        // once to invalidate.
        2 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 100L
        1 * steamUserRepository.findById(100L) >> Optional.empty()
        1 * ses.invalidate()

        when:
        def r = controller.me(req)

        then: 'session is torn down so the client stops sending the stale cookie'
        r.statusCode.value() == 401
    }

    // ── /logout ────────────────────────────────────────────────

    def "logout() always returns 204 + invalidates session (no leak of prior auth state)"() {
        given:
        1 * req.session >> ses
        1 * ses.invalidate()

        when:
        def r = controller.logout(req)

        then: 'never touches the repo — invalidate fires even for already-anon callers'
        0 * steamUserRepository.findById(_)
        r.statusCode.value() == 204
        r.body == null
    }

    // ── /logout-all ────────────────────────────────────────────

    def "logoutAll() 401s for anon"() {
        given:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> null

        when:
        def r = controller.logoutAll(req)

        then:
        r.statusCode.value() == 401
        0 * steamUserRepository.findById(_)
        0 * steamUserRepository.save(_)
    }

    def "logoutAll() invalidates session + 401s when session points to deleted user"() {
        given:
        2 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 100L
        1 * steamUserRepository.findById(100L) >> Optional.empty()
        1 * ses.invalidate()

        when:
        def r = controller.logoutAll(req)

        then:
        r.statusCode.value() == 401
        0 * steamUserRepository.save(_)
    }

    def "logoutAll() bumps sessionEpoch + saves + audits + invalidates (happy path)"() {
        given:
        def user = new SteamUser(id: 100L, sessionEpoch: 1700_000_000_000L)
        2 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 100L
        1 * steamUserRepository.findById(100L) >> Optional.of(user)

        long t0 = System.currentTimeMillis()

        when:
        def r = controller.logoutAll(req)

        then: 'user row is saved with a fresh epoch >= now'
        1 * steamUserRepository.save({ SteamUser s ->
            s.id == 100L && s.sessionEpoch >= t0
        })

        and: 'audit log writes the SESSION_LOGOUT_ALL row'
        1 * auditService.log(AuditService.SESSION_LOGOUT_ALL, 100L, 100L, null, _ as String)

        and: 'current session is invalidated so the UI falls back to anon'
        1 * ses.invalidate()

        and: 'response is 204'
        r.statusCode.value() == 204
    }

    def "logoutAll() does NOT fail when audit service throws (best-effort)"() {
        given:
        def user = new SteamUser(id: 100L, sessionEpoch: 1L)
        2 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 100L
        1 * steamUserRepository.findById(100L) >> Optional.of(user)
        1 * steamUserRepository.save(_)
        1 * auditService.log(_, _, _, _, _) >> { throw new RuntimeException('audit DB down') }
        1 * ses.invalidate()

        when:
        def r = controller.logoutAll(req)

        then: 'audit failure is warn-logged but the logout-all still succeeds'
        r.statusCode.value() == 204
    }

    def "logoutAll() epoch is fresh enough to invalidate every other live session"() {
        given:
        long savedEpoch = -1L
        def user = new SteamUser(id: 100L, sessionEpoch: 500L)
        2 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 100L
        1 * steamUserRepository.findById(100L) >> Optional.of(user)
        1 * steamUserRepository.save(_) >> { args ->
            savedEpoch = (args[0] as SteamUser).sessionEpoch
            args[0]
        }
        _ * auditService.log(_, _, _, _, _)
        1 * ses.invalidate()

        long t0 = System.currentTimeMillis()

        when:
        controller.logoutAll(req)

        then: 'every other session with a stashed epoch <= savedEpoch - 1 fails the filter'
        savedEpoch >= t0
        savedEpoch > 500L  // strictly greater than the prior value
    }
}
