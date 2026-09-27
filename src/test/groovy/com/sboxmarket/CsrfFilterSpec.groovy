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
        // Batch 959 — cookie is set via a raw Set-Cookie header so we can
        // pin SameSite=Lax (the Servlet 5 Cookie API has no first-class
        // SameSite setter). Assert against the header string.
        def setCookie = resp.getHeader('Set-Cookie')
        setCookie != null
        setCookie.startsWith('sbox_csrf=')
        setCookie.contains('Path=/')
        setCookie.contains('Max-Age=' + (7 * 24 * 60 * 60))
        setCookie.contains('SameSite=Lax')
        // Token value is long + URL-safe base64.
        def token = setCookie.split(';')[0].substring('sbox_csrf='.length())
        token.length() > 20
    }

    def "SameSite=Lax is set on the planted csrf cookie (batch 959 regression)"() {
        given:
        // Browsers default an unset SameSite to Lax, but relying on
        // browser defaults for a security-critical cookie is a footgun.
        // Pin the attribute explicitly.
        def req = new MockHttpServletRequest('GET', '/api/listings')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        resp.getHeader('Set-Cookie')?.contains('SameSite=Lax')
    }

    def "Secure attribute tracks secureCookie config flag"() {
        given:
        def secureFilter = new CsrfFilter(enabled: true, secureCookie: true)
        def req = new MockHttpServletRequest('GET', '/api/listings')
        def resp = new MockHttpServletResponse()

        when:
        secureFilter.doFilter(req, resp, chain)

        then:
        resp.getHeader('Set-Cookie')?.contains('Secure')
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

    def "POST to /api/client-errors is exempt (batch 688) — no CSRF check"() {
        given:
        // Crash-reporting endpoint needs to accept POSTs even when the
        // cookie-parse side of the ErrorBoundary hasn't run yet. Worst-
        // case abuse is a noisy log line, not a privilege escalation,
        // so the exemption is intentional.
        def req = new MockHttpServletRequest('POST', '/api/client-errors')
        // No CSRF cookie, no X-CSRF-Token header at all.
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

    def "sbox.apiAuth attribute bypasses the double-submit check (batch 676 bearer-auth)"() {
        given:
        // ApiKeyAuthFilter (Order 2) has already validated a real
        // sbx_live_… bearer token and marked the request as api-
        // authenticated. CsrfFilter then runs at Order 3 and must
        // skip the double-submit CSRF check — a bearer caller has no
        // session cookie to pair with an X-CSRF-Token header, and
        // possession of the token IS the auth factor. Critical that
        // the bypass only fires on the validated attribute, NOT on a
        // raw Bearer header (see the sibling regression test — bug #25).
        def req = new MockHttpServletRequest('POST', '/api/listings/42/buy')
        req.setCookies(new Cookie('sbox_csrf', 'tok-abc-123'))
        req.setAttribute('sbox.apiAuth', Boolean.TRUE)
        // Deliberately NO X-CSRF-Token header — the api-auth bypass
        // should make that irrelevant.
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(req, resp)
        resp.status == 200
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

    def "CSRF compare is length-independent constant time (timing-leak regression)"() {
        given:
        // A plain `header != cookieValue` shorts-circuits on the first
        // byte mismatch AND returns immediately on length difference,
        // leaking matching-prefix bytes and the token length via response
        // timing. The fix routes the compare through
        // MessageDigest.isEqual. This test pins the invariant:
        //   - a header that shares the cookie's prefix but differs in
        //     the last byte must be rejected
        //   - a header strictly shorter than the cookie must be rejected
        //   - a header strictly longer than the cookie must be rejected
        // All three reject with the same 403 + CSRF_MISMATCH body — no
        // oracle. This mirrors the timing-leak fix shipped for the
        // email-verification token compare in ProfileController.verifyEmail.
        def cookie = 'tok-abc-123'

        when: "header shares the prefix but differs in the last byte"
        def req1 = new MockHttpServletRequest('POST', '/api/listings/42/buy')
        req1.setCookies(new Cookie('sbox_csrf', cookie))
        req1.addHeader('X-CSRF-Token', 'tok-abc-124')
        def resp1 = new MockHttpServletResponse()
        filter.doFilter(req1, resp1, chain)

        and: "header is the cookie's prefix (one byte shorter)"
        def req2 = new MockHttpServletRequest('POST', '/api/listings/42/buy')
        req2.setCookies(new Cookie('sbox_csrf', cookie))
        req2.addHeader('X-CSRF-Token', 'tok-abc-12')
        def resp2 = new MockHttpServletResponse()
        filter.doFilter(req2, resp2, chain)

        and: "header extends the cookie (one byte longer)"
        def req3 = new MockHttpServletRequest('POST', '/api/listings/42/buy')
        req3.setCookies(new Cookie('sbox_csrf', cookie))
        req3.addHeader('X-CSRF-Token', 'tok-abc-1234')
        def resp3 = new MockHttpServletResponse()
        filter.doFilter(req3, resp3, chain)

        then: "all three reject identically — no length-or-prefix oracle"
        0 * chain.doFilter(_, _)
        resp1.status == 403 && resp1.contentAsString.contains('"code":"CSRF_MISMATCH"')
        resp2.status == 403 && resp2.contentAsString.contains('"code":"CSRF_MISMATCH"')
        resp3.status == 403 && resp3.contentAsString.contains('"code":"CSRF_MISMATCH"')
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
