package com.sboxmarket

import com.sboxmarket.config.ApiKeyAuthFilter
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.service.ApiKeyService
import jakarta.servlet.FilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import spock.lang.Specification
import spock.lang.Subject

/**
 * Batch 676 — ApiKeyAuthFilter unit coverage. Runs the filter against
 * Servlet mocks with a Spock-mocked ApiKeyService so we can drive the
 * scope/write-gate policy without spinning up Spring.
 */
class ApiKeyAuthFilterSpec extends Specification {

    ApiKeyService apiKeyService = Mock()

    @Subject
    ApiKeyAuthFilter filter = new ApiKeyAuthFilter(apiKeyService: apiKeyService)

    FilterChain chain = Mock()

    def "non-/api paths skip the filter entirely (static assets, SPA shell, sitemap)"() {
        given:
        def req = new MockHttpServletRequest('GET', '/js/app.js')
        req.addHeader('Authorization', 'Bearer sbx_live_doesnt-matter')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(req, resp)
        0 * apiKeyService._    // service is never even consulted
        resp.status == 200
    }

    def "requests without an Authorization header pass through untouched"() {
        given:
        def req = new MockHttpServletRequest('GET', '/api/listings')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(req, resp)
        0 * apiKeyService._
        req.getAttribute('sbox.apiAuth') == null
    }

    def "non-Bearer Authorization values fall through (Cloudflare Access etc.)"() {
        given:
        def req = new MockHttpServletRequest('GET', '/api/listings')
        req.addHeader('Authorization', 'Basic dXNlcjpwYXNz')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(req, resp)
        0 * apiKeyService._
    }

    def "Bearer values without the sbx_live_ prefix fall through (third-party middleware)"() {
        given:
        def req = new MockHttpServletRequest('GET', '/api/listings')
        req.addHeader('Authorization', 'Bearer random-cloudflare-access-jwt')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(req, resp)
        0 * apiKeyService._
    }

    def "invalid sbx_live_ token returns 401 INVALID_API_KEY and halts the chain"() {
        given:
        def req = new MockHttpServletRequest('GET', '/api/listings')
        req.addHeader('Authorization', 'Bearer sbx_live_unknowntoken')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * apiKeyService.authenticateWithScope('sbx_live_unknowntoken') >> null
        0 * chain.doFilter(_, _)
        resp.status == 401
        resp.contentAsString.contains('"code":"INVALID_API_KEY"')
    }

    def "valid RW key populates the session + continues the chain"() {
        given:
        def req = new MockHttpServletRequest('GET', '/api/wallet')
        req.addHeader('Authorization', 'Bearer sbx_live_validtoken')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * apiKeyService.authenticateWithScope('sbx_live_validtoken') >> [userId: 42L, scope: 'RW']
        1 * chain.doFilter(req, resp)
        req.session.getAttribute(SteamAuthController.SESSION_USER_ID) == 42L
        req.getAttribute('sbox.apiAuth') == Boolean.TRUE
        req.getAttribute('sbox.apiScope') == 'RW'
        req.getAttribute('sbox.apiUserId') == 42L
    }

    def "valid RO key passes a GET through (read is allowed)"() {
        given:
        def req = new MockHttpServletRequest('GET', '/api/listings')
        req.addHeader('Authorization', 'Bearer sbx_live_rotoken')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * apiKeyService.authenticateWithScope(_) >> [userId: 10L, scope: 'RO']
        1 * chain.doFilter(req, resp)
        req.session.getAttribute(SteamAuthController.SESSION_USER_ID) == 10L
    }

    def "RO key is REFUSED on #method with 403 RO_KEY_WRITE_FORBIDDEN"() {
        given:
        def req = new MockHttpServletRequest(method, '/api/listings')
        req.addHeader('Authorization', 'Bearer sbx_live_rotoken')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * apiKeyService.authenticateWithScope(_) >> [userId: 10L, scope: 'RO']
        0 * chain.doFilter(_, _)
        resp.status == 403
        resp.contentAsString.contains('"code":"RO_KEY_WRITE_FORBIDDEN"')

        where:
        method   | _
        'POST'   | _
        'PUT'    | _
        'PATCH'  | _
        'DELETE' | _
    }

    def "RO key is ALLOWED on safe methods (GET, HEAD, OPTIONS)"() {
        given:
        def req = new MockHttpServletRequest(method, '/api/listings')
        req.addHeader('Authorization', 'Bearer sbx_live_rotoken')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * apiKeyService.authenticateWithScope(_) >> [userId: 10L, scope: 'RO']
        1 * chain.doFilter(req, resp)
        resp.status == 200

        where:
        method    | _
        'GET'     | _
        'HEAD'    | _
        'OPTIONS' | _
    }

    def "RW key permits write methods"() {
        given:
        def req = new MockHttpServletRequest('POST', '/api/cart/42')
        req.addHeader('Authorization', 'Bearer sbx_live_rwtoken')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * apiKeyService.authenticateWithScope(_) >> [userId: 99L, scope: 'RW']
        1 * chain.doFilter(req, resp)
        resp.status == 200
    }

    def "missing scope defaults to RW (back-compat with pre-V51 keys if any slip through)"() {
        given:
        def req = new MockHttpServletRequest('POST', '/api/cart/42')
        req.addHeader('Authorization', 'Bearer sbx_live_legacy')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * apiKeyService.authenticateWithScope(_) >> [userId: 99L, scope: null]
        1 * chain.doFilter(req, resp)
        resp.status == 200
    }

    def "service exception returns 500 INTERNAL_ERROR rather than leaking"() {
        given:
        def req = new MockHttpServletRequest('GET', '/api/listings')
        req.addHeader('Authorization', 'Bearer sbx_live_boom')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * apiKeyService.authenticateWithScope(_) >> { throw new RuntimeException('db down') }
        0 * chain.doFilter(_, _)
        resp.status == 500
        resp.contentAsString.contains('"code":"INTERNAL_ERROR"')
    }

    // --- Regression: cross-user session contamination / fixation ---------
    // A bearer call that arrives carrying a pre-existing browser session
    // belonging to a DIFFERENT user must not silently rewrite that
    // session's steamUserId. The filter rotates the session (invalidate +
    // fresh) so the API user lands on a clean, isolated session and the
    // old session id is killed (it cannot be ridden afterwards).
    def "bearer call rotates a pre-existing session owned by a different user"() {
        given:
        def req = new MockHttpServletRequest('GET', '/api/wallet')
        req.addHeader('Authorization', 'Bearer sbx_live_validtoken')
        // Simulate a logged-in browser session for user 7 (+ its epoch).
        def stale = req.getSession(true)
        stale.setAttribute(SteamAuthController.SESSION_USER_ID, 7L)
        stale.setAttribute(SteamAuthController.SESSION_EPOCH, 123456789L)
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * apiKeyService.authenticateWithScope('sbx_live_validtoken') >> [userId: 42L, scope: 'RW']
        1 * chain.doFilter(req, resp)
        // Old session was invalidated — a fresh one now serves the request.
        stale.isInvalid()
        def fresh = req.getSession(false)
        !fresh.is(stale)
        // Fresh session carries ONLY the API user — no contamination, and
        // crucially no stale epoch riding along.
        fresh.getAttribute(SteamAuthController.SESSION_USER_ID) == 42L
        fresh.getAttribute(SteamAuthController.SESSION_EPOCH) == null
        req.getAttribute('sbox.apiUserId') == 42L
    }

    def "bearer call reuses a pre-existing session already owned by the same user"() {
        given:
        def req = new MockHttpServletRequest('GET', '/api/wallet')
        req.addHeader('Authorization', 'Bearer sbx_live_validtoken')
        def existing = req.getSession(true)
        existing.setAttribute(SteamAuthController.SESSION_USER_ID, 42L)
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * apiKeyService.authenticateWithScope('sbx_live_validtoken') >> [userId: 42L, scope: 'RW']
        1 * chain.doFilter(req, resp)
        // Same user — no need to rotate; the existing session is reused.
        !existing.isInvalid()
        req.getSession(false).is(existing)
        req.getSession(false).getAttribute(SteamAuthController.SESSION_USER_ID) == 42L
    }

    def "bearer call with no pre-existing session creates a fresh one"() {
        given:
        def req = new MockHttpServletRequest('GET', '/api/wallet')
        req.addHeader('Authorization', 'Bearer sbx_live_validtoken')
        def resp = new MockHttpServletResponse()
        assert req.getSession(false) == null   // no session before the filter runs

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * apiKeyService.authenticateWithScope('sbx_live_validtoken') >> [userId: 42L, scope: 'RW']
        1 * chain.doFilter(req, resp)
        req.getSession(false) != null
        req.getSession(false).getAttribute(SteamAuthController.SESSION_USER_ID) == 42L
    }

    // --- Regression: corrupt ctx with a null userId fails closed ---------
    // authenticateWithScope is documented to return null on a bad key, but
    // a resolved context with a null userId is corrupt state — the filter
    // must reject it (401) rather than stamp a null SESSION_USER_ID.
    def "resolved context with a null userId is rejected 401, not stamped"() {
        given:
        def req = new MockHttpServletRequest('GET', '/api/wallet')
        req.addHeader('Authorization', 'Bearer sbx_live_corrupt')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * apiKeyService.authenticateWithScope(_) >> [userId: null, scope: 'RW']
        0 * chain.doFilter(_, _)
        resp.status == 401
        resp.contentAsString.contains('"code":"INVALID_API_KEY"')
        req.getSession(false) == null
        req.getAttribute('sbox.apiAuth') == null
    }
}
