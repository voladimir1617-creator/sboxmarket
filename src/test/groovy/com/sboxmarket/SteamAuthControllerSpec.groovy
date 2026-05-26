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
        // No `next` provided → trivial home destination → no session attribute write.
        0 * req.getSession(_)

        when:
        controller.login(null, req, resp)

        then:
        1 * resp.sendRedirect('https://steamcommunity.com/openid/login?...')
    }

    // ── /login post-login destination (Codex 18:07Z owner finding) ──

    def "login() stashes a sanitized internal `next` path on the pre-login session"() {
        given:
        1 * steamAuthService.buildLoginUrl() >> 'https://steamcommunity.com/openid/login?...'
        1 * req.getSession(true) >> ses

        when:
        controller.login('/sell', req, resp)

        then:
        1 * ses.setAttribute(SteamAuthController.SESSION_NEXT, '/sell')
        1 * resp.sendRedirect('https://steamcommunity.com/openid/login?...')
    }

    def "login() preserves query strings and hashes when stashing the next path"() {
        given:
        1 * steamAuthService.buildLoginUrl() >> 'https://steamcommunity.com/openid/login?...'
        1 * req.getSession(true) >> ses

        when:
        controller.login('/profile?tab=trades', req, resp)

        then:
        1 * ses.setAttribute(SteamAuthController.SESSION_NEXT, '/profile?tab=trades')
    }

    def "login() drops a malformed `next` and stashes nothing"() {
        given:
        1 * steamAuthService.buildLoginUrl() >> 'https://steamcommunity.com/openid/login?...'
        // `https://evil.com` sanitises to `/` → trivial → no session attribute write.
        0 * req.getSession(_)

        when:
        controller.login('https://evil.com/steal', req, resp)

        then:
        1 * resp.sendRedirect('https://steamcommunity.com/openid/login?...')
    }

    def "sanitizeNext rejects every off-site / header-injection vector"() {
        expect:
        SteamAuthController.sanitizeNext(input) == expected

        where:
        input                            | expected
        // Defaults.
        null                             | '/'
        ''                               | '/'
        '   '                            | '/'
        // Allowed shapes.
        '/'                              | '/'
        '/sell'                          | '/sell'
        '/profile/transactions'          | '/profile/transactions'
        '/profile?tab=trades'            | '/profile?tab=trades'
        '/profile?tab=trades&q=x#anchor' | '/profile?tab=trades&q=x#anchor'
        // Off-site / protocol-relative URLs.
        'https://evil.com/steal'         | '/'
        'http://evil.com'                | '/'
        '//evil.com'                     | '/'
        'javascript:alert(1)'            | '/'
        // Header-injection guards (CR/LF). Spaces in a path are odd but
        // not in themselves dangerous; CR/LF are the canonical Location-
        // header smuggling vectors.
        '/sell\nLocation: https://evil'  | '/'
        '/sell\rLocation: https://evil'  | '/'
        // Path-must-start-with-/ guard.
        'sell'                           | '/'
        'profile?tab=trades'             | '/'
        // Backslash + scheme-smuggling guards.
        '/\\evil.com'                    | '/'
        '/foo://bar'                     | '/'
        // Length cap (NEXT_MAX_LEN = 200).
        ('/' + ('a' * 250))              | '/'
    }

    // ── /me ─────────────────────────────────────────────────────

    def "me() returns {signedIn:false} when no session user"() {
        given:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> null

        when:
        def r = controller.me(req)

        then:
        // Was 401; switched to 200 + {signedIn:false} so anonymous
        // visitors don't see a red "Failed to load resource: 401" in
        // their browser DevTools console on every page load.
        r.statusCode.value() == 200
        r.body == [signedIn: false]
        0 * steamUserRepository.findById(_)
    }

    def "me() returns the SteamUser body when session is valid"() {
        given:
        def user = new SteamUser(id: 100L, displayName: 'alice', sessionEpoch: 0L)
        // Three reads: uid, then epoch (live==stashed→pass), and no invalidate.
        2 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 100L
        1 * steamUserRepository.findById(100L) >> Optional.of(user)
        1 * ses.getAttribute(SteamAuthController.SESSION_EPOCH) >> 0L

        when:
        def r = controller.me(req)

        then:
        r.statusCode.value() == 200
        r.body.is(user)
    }

    // ── /me enforces logout-all (sessionEpoch) ────────────────────
    //
    // SessionEpochFilter SKIPS /api/auth/steam/me to avoid
    // chicken-and-egg issues. Without an inline check, a session
    // killed by `/logout-all` on another device kept getting its
    // full identity back from /me forever (or until the SPA happened
    // to hit a non-skipped endpoint), leaving the abandoned device's
    // navbar rendering as "signed in" indefinitely. This pins the
    // fix: /me must compare stashed epoch vs live epoch and report
    // signed-out on mismatch, the same shape SessionEpochFilter uses.
    def "me() reports signedIn=false when the live sessionEpoch is ahead of the stashed one (logout-all from another device)"() {
        given: 'a session stashed at epoch 5, but the user has since hit /logout-all elsewhere'
        def user = new SteamUser(id: 100L, displayName: 'alice', sessionEpoch: 1_700_000_000_000L)
        // Three reads of req.session: uid, epoch, invalidate.
        3 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 100L
        1 * steamUserRepository.findById(100L) >> Optional.of(user)
        1 * ses.getAttribute(SteamAuthController.SESSION_EPOCH) >> 5L
        1 * ses.invalidate()

        when:
        def r = controller.me(req)

        then: 'the stale session is torn down and /me reports signed-out'
        r.statusCode.value() == 200
        r.body == [signedIn: false]
    }

    def "me() returns the user body when stashed sessionEpoch matches the live value"() {
        given: 'a session whose epoch is current'
        def user = new SteamUser(id: 100L, displayName: 'alice', sessionEpoch: 42L)
        // Happy path: two reads of req.session (uid + epoch), no invalidate.
        2 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 100L
        1 * steamUserRepository.findById(100L) >> Optional.of(user)
        1 * ses.getAttribute(SteamAuthController.SESSION_EPOCH) >> 42L
        0 * ses.invalidate()

        when:
        def r = controller.me(req)

        then:
        r.statusCode.value() == 200
        r.body.is(user)
    }

    def "me() invalidates session + returns signedIn=false when session uid points to a deleted user"() {
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
        // Was 401; the new contract is 200 + {signedIn:false} for any
        // not-currently-signed-in caller — including a stale session
        // pointing at a deleted user.
        r.statusCode.value() == 200
        r.body == [signedIn: false]
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

    // ── /return: verification + session-fixation rotation ───────────
    //
    // Unit-level coverage for the OpenID callback handler. The OpenID
    // signature check itself lives in SteamAuthService (mocked here); what
    // these pin is the controller's contract: rotate the session on
    // success, never set auth state on failure, and keep the post-login
    // redirect locked to a sanitized same-origin path.

    def "steamReturn rotates the session on a successful login (session-fixation defense)"() {
        given: 'a verified login for an existing user'
        HttpSession preLogin  = Mock()
        HttpSession freshLogin = Mock()
        def user = new SteamUser(id: 100L, steamId64: '76561197960287930',
                                 displayName: 'Alice', sessionEpoch: 7L)
        req.queryString >> 'openid.mode=id_res&openid.claimed_id=x'
        req.getParameter('openid.claimed_id') >> 'x'
        req.getSession(false) >> preLogin
        preLogin.getAttribute(SteamAuthController.SESSION_NEXT) >> null
        1 * steamAuthService.verifyReturn(_, _) >> '76561197960287930'
        1 * steamAuthService.upsertUser('76561197960287930') >> user

        when:
        controller.steamReturn(req, resp)

        then: 'the PRE-login session is invalidated...'
        1 * req.session >> preLogin
        1 * preLogin.invalidate()

        and: '...and a brand-new session is issued carrying the auth state'
        1 * req.getSession(true) >> freshLogin
        1 * freshLogin.setAttribute(SteamAuthController.SESSION_USER_ID, 100L)
        1 * freshLogin.setAttribute(SteamAuthController.SESSION_EPOCH, 7L)

        and: 'the user lands back on / with the success flag'
        1 * resp.sendRedirect('/?login=success')
    }

    def "steamReturn never establishes a session when verification fails"() {
        given: 'OpenID verification returns null (bad/forged/replayed assertion)'
        req.queryString >> 'openid.mode=id_res&tampered=1'
        req.getParameter('openid.claimed_id') >> 'x'
        req.getSession(false) >> null
        1 * steamAuthService.verifyReturn(_, _) >> null

        when:
        controller.steamReturn(req, resp)

        then: 'no session is created, no auth attribute is written, no user is upserted'
        0 * req.getSession(true)
        0 * steamAuthService.upsertUser(_)
        0 * ses.setAttribute(SteamAuthController.SESSION_USER_ID, _)

        and: 'the browser is redirected with login=failed'
        1 * resp.sendRedirect('/?login=failed')
    }

    def "steamReturn carries a stashed `next` into the post-login redirect"() {
        given:
        HttpSession preLogin  = Mock()
        HttpSession freshLogin = Mock()
        def user = new SteamUser(id: 100L, steamId64: '76561197960287930', sessionEpoch: 0L)
        req.queryString >> 'openid.mode=id_res'
        req.getParameter('openid.claimed_id') >> null
        req.getSession(false) >> preLogin
        1 * preLogin.getAttribute(SteamAuthController.SESSION_NEXT) >> '/sell'
        1 * steamAuthService.verifyReturn(_, _) >> '76561197960287930'
        1 * steamAuthService.upsertUser(_) >> user
        req.session >> preLogin
        req.getSession(true) >> freshLogin

        when:
        controller.steamReturn(req, resp)

        then: 'the user lands back on the page they started the auth flow from'
        1 * resp.sendRedirect('/sell?login=success')
    }

    def "steamReturn re-sanitizes a poisoned `next` stash before redirecting"() {
        given: 'a hostile value somehow present in the session attribute'
        HttpSession preLogin = Mock()
        req.queryString >> 'openid.mode=id_res&bad=1'
        req.getParameter('openid.claimed_id') >> null
        req.getSession(false) >> preLogin
        1 * preLogin.getAttribute(SteamAuthController.SESSION_NEXT) >> 'https://evil.com/phish'
        1 * steamAuthService.verifyReturn(_, _) >> null

        when:
        controller.steamReturn(req, resp)

        then: 'the off-site destination is collapsed to / — never echoed into Location'
        1 * resp.sendRedirect('/?login=failed')
    }

    def "steamReturn redirects with reason=upsert when upsertUser throws"() {
        given:
        HttpSession preLogin = Mock()
        req.queryString >> 'openid.mode=id_res'
        req.getParameter('openid.claimed_id') >> null
        req.getSession(false) >> preLogin
        preLogin.getAttribute(SteamAuthController.SESSION_NEXT) >> null
        1 * steamAuthService.verifyReturn(_, _) >> '76561197960287930'
        1 * steamAuthService.upsertUser(_) >> { throw new RuntimeException('DB down') }

        when:
        controller.steamReturn(req, resp)

        then: 'failure is surfaced to the SPA without leaking a session'
        0 * preLogin.invalidate()
        1 * resp.sendRedirect('/?login=failed&reason=upsert')
    }
}
