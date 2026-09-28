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

    /** The request the filter handed down the chain. */
    def forwarded

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
        1 * chain.doFilter(_, resp) >> { args -> forwarded = args[0] }
        forwarded.getSession(false).getAttribute(SteamAuthController.SESSION_USER_ID) == 42L
        forwarded.getAttribute('sbox.apiAuth') == Boolean.TRUE
        forwarded.getAttribute('sbox.apiScope') == 'RW'
        forwarded.getAttribute('sbox.apiUserId') == 42L
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
        1 * chain.doFilter(_, resp) >> { args -> forwarded = args[0] }
        forwarded.getSession(false).getAttribute(SteamAuthController.SESSION_USER_ID) == 10L
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
        1 * chain.doFilter(_ as ApiKeyAuthFilter.ApiKeyRequest, resp)
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
        1 * chain.doFilter(_ as ApiKeyAuthFilter.ApiKeyRequest, resp)
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
        1 * chain.doFilter(_ as ApiKeyAuthFilter.ApiKeyRequest, resp)
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

    // --- Regression: bearer calls never touch the container session ----
    // A bearer call used to stamp a real servlet session. The container
    // answered with a session cookie that, sent alone (no bearer header,
    // so no RO write-gate and no CSRF bypass needed beyond double-submit),
    // was a year-long read-write login surviving key revocation. The
    // filter now serves a request-scoped session the container never sees.
    def "bearer call never creates a container session (no session cookie can leak)"() {
        given:
        def req = new MockHttpServletRequest('GET', '/api/wallet')
        req.addHeader('Authorization', 'Bearer sbx_live_rotoken')
        def resp = new MockHttpServletResponse()
        assert req.getSession(false) == null

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * apiKeyService.authenticateWithScope('sbx_live_rotoken') >> [userId: 42L, scope: 'RO']
        1 * chain.doFilter(_, resp) >> { args ->
            forwarded = args[0]
            // Downstream code that asks for a session gets the scoped one.
            forwarded.getSession(true).setAttribute('touched', true)
        }
        forwarded.getSession(false).getAttribute(SteamAuthController.SESSION_USER_ID) == 42L
        // The container request has no session at all, before or after.
        req.getSession(false) == null
        resp.cookies.length == 0
    }

    def "bearer call leaves a pre-existing browser session untouched"() {
        given:
        def req = new MockHttpServletRequest('GET', '/api/wallet')
        req.addHeader('Authorization', 'Bearer sbx_live_validtoken')
        def browser = req.getSession(true)
        browser.setAttribute(SteamAuthController.SESSION_USER_ID, 7L)
        browser.setAttribute(SteamAuthController.SESSION_EPOCH, 123456789L)
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * apiKeyService.authenticateWithScope('sbx_live_validtoken') >> [userId: 42L, scope: 'RW']
        1 * chain.doFilter(_, resp) >> { args -> forwarded = args[0] }
        // Downstream sees only the API user, never the browser's user or epoch.
        !forwarded.getSession(false).is(browser)
        forwarded.getSession(false).getAttribute(SteamAuthController.SESSION_USER_ID) == 42L
        forwarded.getSession(false).getAttribute(SteamAuthController.SESSION_EPOCH) == null
        // The browser session is neither rewritten nor killed.
        !browser.isInvalid()
        browser.getAttribute(SteamAuthController.SESSION_USER_ID) == 7L
        forwarded.getAttribute('sbox.apiUserId') == 42L
    }

    def "invalidating the scoped session (logout over the API) drops the user for the rest of the request"() {
        given:
        def req = new MockHttpServletRequest('POST', '/api/auth/logout')
        req.addHeader('Authorization', 'Bearer sbx_live_validtoken')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * apiKeyService.authenticateWithScope(_) >> [userId: 42L, scope: 'RW']
        1 * chain.doFilter(_, resp) >> { args ->
            forwarded = args[0]
            forwarded.getSession(false).invalidate()
        }
        forwarded.getSession(false) == null
        forwarded.getSession(true).getAttribute(SteamAuthController.SESSION_USER_ID) == null
        req.getSession(false) == null
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
