package com.sboxmarket

import com.sboxmarket.config.SessionEpochFilter
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.SteamUserRepository
import jakarta.servlet.FilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockHttpSession
import spock.lang.Specification
import spock.lang.Subject

/**
 * Unit coverage for the "log out everywhere" session-epoch filter.
 *
 * Drives the filter through raw Servlet mocks with a Spock-mocked
 * SteamUserRepository so the stale-epoch policy exercises without a
 * running Spring context or a real DB.
 *
 * Invariant under test: a session whose stashed epoch is OLDER than the
 * user's live steam_users.session_epoch is invalidated + 401'd; an
 * up-to-date session passes through. API-key-authenticated requests are
 * exempt — they carry no browser-session epoch and are governed by
 * API-key revocation instead.
 */
class SessionEpochFilterSpec extends Specification {

    SteamUserRepository steamUserRepository = Mock()

    @Subject
    SessionEpochFilter filter = new SessionEpochFilter(steamUserRepository: steamUserRepository)

    FilterChain chain = Mock()

    /** Build an /api request carrying an authenticated session. */
    private MockHttpServletRequest apiReq(String path, Long userId, Long stashedEpoch) {
        def r = new MockHttpServletRequest('POST', path)
        def sess = new MockHttpSession()
        if (userId != null) sess.setAttribute(SteamAuthController.SESSION_USER_ID, userId)
        if (stashedEpoch != null) sess.setAttribute(SteamAuthController.SESSION_EPOCH, stashedEpoch)
        r.session = sess
        r
    }

    // ── pass-through cases ───────────────────────────────────────────

    def "non-API paths skip the filter entirely"() {
        given:
        def req = new MockHttpServletRequest('POST', '/some/html/form')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(req, resp)
        0 * steamUserRepository._
        resp.status == 200
    }

    def "skipped auth endpoint #path is never epoch-checked (chicken-and-egg guard)"() {
        given:
        def req = apiReq(path, 7L, 0L)
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
            '/api/auth/steam/me',
            '/api/auth/steam/logout',
            '/api/stripe/webhook'
        ]
    }

    def "request with no session falls through without a DB lookup"() {
        given:
        def req = new MockHttpServletRequest('POST', '/api/offers')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(req, resp)
        0 * steamUserRepository._
    }

    def "anonymous session (no userId attribute) falls through without a DB lookup"() {
        given:
        def req = new MockHttpServletRequest('POST', '/api/offers')
        req.session = new MockHttpSession()   // session exists, but no steamUserId
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(req, resp)
        0 * steamUserRepository._
    }

    def "fresh session whose stashed epoch equals the live epoch passes through"() {
        given:
        def req = apiReq('/api/offers', 42L, 1000L)
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * steamUserRepository.findById(42L) >> Optional.of(new SteamUser(id: 42L, sessionEpoch: 1000L))
        1 * chain.doFilter(req, resp)
        resp.status == 200
        req.getSession(false) != null   // session NOT invalidated
    }

    def "session whose stashed epoch is NEWER than live passes through (only older trips)"() {
        given:
        // A `<` comparison means only a strictly-older stash is stale.
        def req = apiReq('/api/offers', 42L, 5000L)
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * steamUserRepository.findById(42L) >> Optional.of(new SteamUser(id: 42L, sessionEpoch: 1000L))
        1 * chain.doFilter(req, resp)
        resp.status == 200
    }

    def "live epoch of zero never trips even with a null stashed epoch (brand-new account)"() {
        given:
        // A user who has never used logout-all has session_epoch = 0.
        def req = apiReq('/api/offers', 42L, null)
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * steamUserRepository.findById(42L) >> Optional.of(new SteamUser(id: 42L, sessionEpoch: 0L))
        1 * chain.doFilter(req, resp)
        resp.status == 200
    }

    // ── revocation cases ─────────────────────────────────────────────

    def "stale session (stashed epoch older than live) is invalidated and 401'd"() {
        given:
        def req = apiReq('/api/offers', 42L, 1000L)
        def session = req.getSession(false)
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * steamUserRepository.findById(42L) >> Optional.of(new SteamUser(id: 42L, sessionEpoch: 9999L))
        0 * chain.doFilter(_, _)
        resp.status == 401
        resp.contentAsString.contains('"code":"SESSION_REVOKED"')
        session.isInvalid()
    }

    def "session that pre-dates a logout-all (null stash, non-zero live) is revoked"() {
        given:
        // The exact shape of the bug class: a session carrying no
        // SESSION_EPOCH at all. `(null ?: 0L) < live` must trip when the
        // user has used logout-all (live = wall-clock millis).
        def req = apiReq('/api/offers', 42L, null)
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * steamUserRepository.findById(42L) >> Optional.of(new SteamUser(id: 42L, sessionEpoch: 1_700_000_000_000L))
        0 * chain.doFilter(_, _)
        resp.status == 401
        resp.contentAsString.contains('"code":"SESSION_REVOKED"')
    }

    // ── DB-resilience cases ──────────────────────────────────────────

    def "DB lookup failure falls through rather than 401-ing every request"() {
        given:
        def req = apiReq('/api/offers', 42L, 1000L)
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * steamUserRepository.findById(42L) >> { throw new RuntimeException('db down') }
        1 * chain.doFilter(req, resp)
        resp.status == 200
    }

    def "missing user row (live epoch null) falls through — other layers handle the gone user"() {
        given:
        def req = apiReq('/api/offers', 42L, 1000L)
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * steamUserRepository.findById(42L) >> Optional.empty()
        1 * chain.doFilter(req, resp)
        resp.status == 200
    }

    // ── API-key exemption (regression guard) ─────────────────────────

    def "API-key-authenticated request is exempt even when the stashed epoch is stale"() {
        given:
        // ApiKeyAuthFilter (Order 2) authenticates a `Bearer sbx_live_…`
        // request by creating a session with SESSION_USER_ID but NO
        // SESSION_EPOCH, and marks the request `sbox.apiAuth=true`. If
        // this filter ran the epoch check it would 401 the request the
        // moment the owning user has ever used logout-all. The bearer
        // token is its own auth factor with its own revoke flow — the
        // epoch check must be skipped.
        def req = apiReq('/api/offers', 42L, null)   // API-key path stashes no epoch
        req.setAttribute('sbox.apiAuth', Boolean.TRUE)
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        0 * steamUserRepository._     // DB is never even consulted
        1 * chain.doFilter(req, resp)
        resp.status == 200
        req.getSession(false) != null // session NOT invalidated
    }

    def "API-key exemption holds even if the user has bumped their epoch via logout-all"() {
        given:
        def req = apiReq('/api/offers', 42L, null)
        req.setAttribute('sbox.apiAuth', Boolean.TRUE)
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        // Even though a DB lookup WOULD return a huge live epoch, the
        // exemption short-circuits before the lookup happens.
        0 * steamUserRepository._
        1 * chain.doFilter(req, resp)
        resp.status != 401
    }

    def "a plain browser session (no sbox.apiAuth attribute) is still epoch-checked"() {
        given:
        // Negative control for the exemption: the bypass must fire ONLY
        // on the server-set attribute, never by default.
        def req = apiReq('/api/offers', 42L, 1000L)
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * steamUserRepository.findById(42L) >> Optional.of(new SteamUser(id: 42L, sessionEpoch: 9999L))
        0 * chain.doFilter(_, _)
        resp.status == 401
    }
}
