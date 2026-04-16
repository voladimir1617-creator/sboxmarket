package com.sboxmarket

import com.sboxmarket.config.BodySizeLimitFilter
import jakarta.servlet.FilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import spock.lang.Specification
import spock.lang.Subject

/**
 * Pins the 2MB request body cap: POST/PUT/PATCH whose declared
 * Content-Length exceeds 2MB get a 413 before the body is drained.
 * GETs are always permitted. Under-cap POSTs fall through.
 */
class BodySizeLimitFilterSpec extends Specification {

    @Subject
    BodySizeLimitFilter filter = new BodySizeLimitFilter()

    FilterChain chain = Mock()

    def "GET is never body-size-checked"() {
        given:
        def req = new MockHttpServletRequest('GET', '/api/listings')
        req.setContent(new byte[100_000_000])  // 100MB — ignored for GET
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(req, resp)
        resp.status == 200
    }

    def "POST with content-length 1MB falls through (under cap)"() {
        given:
        def req = new MockHttpServletRequest('POST', '/api/cart/checkout')
        req.setContent(new byte[1_000_000])
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(req, resp)
        resp.status == 200
    }

    def "POST with content-length 3MB is rejected with 413"() {
        given:
        def req = new MockHttpServletRequest('POST', '/api/cart/checkout')
        req.setContent(new byte[3 * 1024 * 1024])
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        0 * chain.doFilter(_, _)
        resp.status == 413
        resp.contentAsString.contains('"code":"PAYLOAD_TOO_LARGE"')
    }

    def "PUT with oversize body is rejected"() {
        given:
        def req = new MockHttpServletRequest('PUT', '/api/profile/email')
        req.setContent(new byte[5 * 1024 * 1024])
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        0 * chain.doFilter(_, _)
        resp.status == 413
    }

    def "PATCH with oversize body is rejected"() {
        given:
        def req = new MockHttpServletRequest('PATCH', '/api/listings/42')
        req.setContent(new byte[5 * 1024 * 1024])
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        0 * chain.doFilter(_, _)
        resp.status == 413
    }

    def "POST with exactly 2MB body passes through (cap is exclusive)"() {
        given:
        def req = new MockHttpServletRequest('POST', '/api/offers')
        req.setContent(new byte[2 * 1024 * 1024])
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(req, resp)
    }
}
