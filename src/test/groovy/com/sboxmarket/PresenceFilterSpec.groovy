package com.sboxmarket

import com.sboxmarket.config.PresenceFilter
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.repository.SteamUserRepository
import jakarta.servlet.FilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockHttpSession
import spock.lang.Specification
import spock.lang.Subject

/**
 * Unit coverage for the online-presence filter (V61).
 *
 * Drives the filter through raw Servlet mocks with a Spock-mocked
 * SteamUserRepository so the throttle policy exercises without a running
 * Spring context or a real DB.
 *
 * Invariants under test:
 *   - one DB write per authenticated user per throttle window — a second
 *     request inside the window must NOT re-persist;
 *   - anonymous / session-less / auth-lifecycle / health-probe requests
 *     never touch the DB and never throw;
 *   - a DB blip during the throttled write is swallowed (the page load
 *     must not 5xx) AND the throttle is rolled back so a retry can land.
 */
class PresenceFilterSpec extends Specification {

    SteamUserRepository steamUserRepository = Mock()

    @Subject
    PresenceFilter filter = new PresenceFilter(steamUserRepository: steamUserRepository)

    FilterChain chain = Mock()

    /** Build an /api request carrying an authenticated session. */
    private MockHttpServletRequest authedReq(String path, Long userId) {
        def r = new MockHttpServletRequest('GET', path)
        def sess = new MockHttpSession()
        if (userId != null) sess.setAttribute(SteamAuthController.SESSION_USER_ID, userId)
        r.session = sess
        r
    }

    // ── pass-through / no-bump cases ─────────────────────────────────

    def "non-API path never bumps presence"() {
        given:
        def req = new MockHttpServletRequest('GET', '/market')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(req, resp)
        0 * steamUserRepository._
    }

    def "request with no session falls through without a DB write"() {
        given:
        def req = new MockHttpServletRequest('GET', '/api/listings')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(req, resp)
        0 * steamUserRepository._
    }

    def "anonymous session (no userId attribute) falls through without a DB write or NPE"() {
        given:
        def req = new MockHttpServletRequest('GET', '/api/listings')
        req.session = new MockHttpSession()   // session exists, no steamUserId
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(req, resp)
        0 * steamUserRepository._
    }

    def "skipped prefix #path is never presence-bumped"() {
        given:
        def req = authedReq(path, 7L)
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(req, resp)
        0 * steamUserRepository._

        where:
        path << [
            '/api/auth/steam/login',
            '/api/auth/steam/return',
            '/api/auth/steam/logout',
            '/api/stripe/webhook',
            '/api/health'
        ]
    }

    // ── bump cases ───────────────────────────────────────────────────

    def "first authenticated /api request bumps last_seen_at exactly once"() {
        given:
        def req = authedReq('/api/listings', 42L)
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * steamUserRepository.updateLastSeenAt(42L, _ as Long)
        1 * chain.doFilter(req, resp)
    }

    def "second request inside the throttle window does NOT re-persist"() {
        given:
        // Two requests for the same user, back-to-back — far inside the
        // 60s throttle window. Only the first should hit the DB.
        def first  = authedReq('/api/listings', 42L)
        def second = authedReq('/api/offers', 42L)
        def resp   = new MockHttpServletResponse()

        when:
        filter.doFilter(first, resp, chain)
        filter.doFilter(second, new MockHttpServletResponse(), chain)

        then:
        1 * steamUserRepository.updateLastSeenAt(42L, _ as Long)   // exactly once
        2 * chain.doFilter(_, _)
    }

    def "distinct users each get their own bump"() {
        given:
        def reqA = authedReq('/api/listings', 1L)
        def reqB = authedReq('/api/listings', 2L)

        when:
        filter.doFilter(reqA, new MockHttpServletResponse(), chain)
        filter.doFilter(reqB, new MockHttpServletResponse(), chain)

        then:
        1 * steamUserRepository.updateLastSeenAt(1L, _ as Long)
        1 * steamUserRepository.updateLastSeenAt(2L, _ as Long)
        2 * chain.doFilter(_, _)
    }

    // ── DB-resilience cases ──────────────────────────────────────────

    def "a DB blip during the bump is swallowed — the request still succeeds"() {
        given:
        def req = authedReq('/api/listings', 42L)
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * steamUserRepository.updateLastSeenAt(42L, _ as Long) >> { throw new RuntimeException('db down') }
        1 * chain.doFilter(req, resp)   // request is NOT tanked
        resp.status == 200
    }

    def "a failed bump rolls back the throttle so the next request retries"() {
        given:
        // First bump throws — the filter must remove the throttle entry so
        // the immediately-following request is free to re-attempt the
        // write rather than being suppressed for 60s after a failure.
        def first  = authedReq('/api/listings', 42L)
        def second = authedReq('/api/offers', 42L)

        when:
        filter.doFilter(first, new MockHttpServletResponse(), chain)
        filter.doFilter(second, new MockHttpServletResponse(), chain)

        then:
        2 * steamUserRepository.updateLastSeenAt(42L, _ as Long) >> { throw new RuntimeException('db down') }
        2 * chain.doFilter(_, _)
    }
}
