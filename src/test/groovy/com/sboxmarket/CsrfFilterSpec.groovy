package com.sboxmarket

import com.sboxmarket.config.CsrfFilter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.Cookie
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import spock.lang.Specification
import spock.lang.Subject

/**
 * Unit-level coverage for the double-submit-cookie CSRF filter.
 *
 * We exercise it through raw Servlet mocks rather than a running Spring
 * context — one filter instance, a plain `MockHttpServletRequest`, a
 * stub `FilterChain`. This keeps the spec fast and focused on the CSRF
 * policy only: cookie planting, method exemptions, token matching,
 * exempt-prefix handling, Bearer-token bypass.
 *
 * The integration specs run with `security.csrf-enabled=false` so they
 * can drive the HTTP layer without the double-submit dance; this file
 * is the place to prove CSRF actually works.
 */
class CsrfFilterSpec extends Specification {

    @Subject
    CsrfFilter filter = new CsrfFilter(enabled: true, secureCookie: false)

    FilterChain chain = Mock()

    def "plants a sbox_csrf cookie on the first response when none is present"() {
        given:
        def req = new MockHttpServletRequest('GET', '/api/listings')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(req, resp)
        def cookie = resp.getCookie('sbox_csrf')
        cookie != null
        cookie.value.length() > 20
        cookie.path == '/'
        cookie.maxAge == 7 * 24 * 60 * 60
    }

    def "GET requests are never CSRF-checked, even without a token header"() {
        given:
        def req = new MockHttpServletRequest('GET', '/api/listings')
        req.setCookies(new Cookie('sbox_csrf', 'tok-abc-123'))
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(req, resp)
        resp.status == 200
    }

    def "POST to /api/listings/buy without a CSRF header returns 403"() {
        given:
        def req = new MockHttpServletRequest('POST', '/api/listings/42/buy')
        req.setCookies(new Cookie('sbox_csrf', 'tok-abc-123'))
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        0 * chain.doFilter(_, _)
        resp.status == 403
        resp.contentAsString.contains('"code":"CSRF_MISMATCH"')
    }

    def "POST to /api/listings/buy with a matching header passes through"() {
        given:
        def req = new MockHttpServletRequest('POST', '/api/listings/42/buy')
        req.setCookies(new Cookie('sbox_csrf', 'tok-abc-123'))
        req.addHeader('X-CSRF-Token', 'tok-abc-123')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(req, resp)
        resp.status == 200
    }

    def "POST with a non-matching header returns 403"() {
        given:
        def req = new MockHttpServletRequest('POST', '/api/listings/42/buy')
        req.setCookies(new Cookie('sbox_csrf', 'tok-abc-123'))
        req.addHeader('X-CSRF-Token', 'tok-different-xyz')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        0 * chain.doFilter(_, _)
        resp.status == 403
        resp.contentAsString.contains('"code":"CSRF_MISMATCH"')
    }

    def "POST to /api/stripe/webhook is exempt — no CSRF check"() {
        given:
        def req = new MockHttpServletRequest('POST', '/api/stripe/webhook')
        req.setCookies(new Cookie('sbox_csrf', 'tok-abc-123'))
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(req, resp)
        resp.status == 200
    }

    def "Bearer header must NOT bypass CSRF — bug #25 regression guard"() {
        given:
        // The old code treated `Authorization: Bearer …` as a machine-to-
        // machine marker and skipped CSRF, but no filter actually validated
        // the bearer token, so an attacker could set any value and sneak
        // past the double-submit-cookie check. The fix: bearer has no
        // special meaning here — CSRF applies to every /api write.
        def req = new MockHttpServletRequest('POST', '/api/listings/42/buy')
        req.setCookies(new Cookie('sbox_csrf', 'tok-abc-123'))
        req.addHeader('Authorization', 'Bearer literally-anything')
        // No X-CSRF-Token header → must be rejected.
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        0 * chain.doFilter(_, _)
        resp.status == 403
        resp.contentAsString.contains('"code":"CSRF_MISMATCH"')
    }

    def "disabling the filter short-circuits every check"() {
        given:
        filter.enabled = false
        def req = new MockHttpServletRequest('POST', '/api/listings/42/buy')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(req, resp)
        resp.status == 200
    }

    def "non-API POST is not CSRF-checked (only /api/** is gated)"() {
        given:
        def req = new MockHttpServletRequest('POST', '/some/html/form')
        req.setCookies(new Cookie('sbox_csrf', 'tok-abc-123'))
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(req, resp)
        resp.status == 200
    }
}
