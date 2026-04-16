package com.sboxmarket

import com.sboxmarket.config.CorrelationIdFilter
import jakarta.servlet.FilterChain
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import spock.lang.Specification
import spock.lang.Subject

/**
 * Coverage for the first-in-chain filter: correlation id generation +
 * MDC binding + security headers + cache-control on sensitive endpoints.
 */
class CorrelationIdFilterSpec extends Specification {

    @Subject
    CorrelationIdFilter filter = new CorrelationIdFilter(enableHsts: false)

    FilterChain chain = Mock()

    def "generates a fresh correlation id when none was sent"() {
        given:
        def req = new MockHttpServletRequest('GET', '/api/listings')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(req, resp)
        def cid = resp.getHeader('X-Correlation-Id')
        cid != null
        cid.length() == 8
    }

    def "echoes back a client-supplied correlation id"() {
        given:
        def req = new MockHttpServletRequest('GET', '/api/listings')
        req.addHeader('X-Correlation-Id', 'client-abc-123')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        resp.getHeader('X-Correlation-Id') == 'client-abc-123'
    }

    def "rejects an absurdly long client-supplied correlation id and generates one instead"() {
        given:
        def req = new MockHttpServletRequest('GET', '/api/listings')
        req.addHeader('X-Correlation-Id', 'a' * 1000)
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        resp.getHeader('X-Correlation-Id') != 'a' * 1000
        resp.getHeader('X-Correlation-Id').length() == 8
    }

    def "plants baseline security headers on every response"() {
        given:
        def req = new MockHttpServletRequest('GET', '/')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        resp.getHeader('X-Content-Type-Options') == 'nosniff'
        resp.getHeader('X-Frame-Options') == 'DENY'
        resp.getHeader('Referrer-Policy') == 'strict-origin-when-cross-origin'
        resp.getHeader('Permissions-Policy')?.contains('geolocation=()')
        resp.getHeader('Cross-Origin-Opener-Policy') == 'same-origin'
        resp.getHeader('Cross-Origin-Resource-Policy') == 'same-origin'
        def csp = resp.getHeader('Content-Security-Policy')
        csp?.contains("default-src 'self'")
        // img-src must include api.qrserver.com so the 2FA enrollment QR
        // image loads — regression caught once, pinned here.
        csp?.contains('api.qrserver.com')
        // Every Steam CDN variant we have seen for item/avatar art:
        csp?.contains('steamcommunity-a.akamaihd.net')
        csp?.contains('avatars.steamstatic.com')
    }

    def "HSTS only emitted when enableHsts=true"() {
        given:
        def hstsFilter = new CorrelationIdFilter(enableHsts: true)
        def req = new MockHttpServletRequest('GET', '/')
        def resp = new MockHttpServletResponse()

        when:
        hstsFilter.doFilter(req, resp, chain)

        then:
        resp.getHeader('Strict-Transport-Security')?.contains('max-age=31536000')
    }

    def "HSTS NOT emitted when enableHsts=false"() {
        given:
        def req = new MockHttpServletRequest('GET', '/')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        resp.getHeader('Strict-Transport-Security') == null
    }

    def "cache-control=no-store on sensitive paths: #path"() {
        given:
        def req = new MockHttpServletRequest('GET', path)
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        resp.getHeader('Cache-Control')?.contains('no-store')
        resp.getHeader('Pragma') == 'no-cache'

        where:
        path << [
            '/api/wallet',
            '/api/auth/steam/me',
            '/api/profile/me',
            '/api/trades',
            '/api/notifications',
            '/api/admin/stats',
            '/api/csr/tickets',
            '/api/support/tickets'
        ]
    }

    def "no cache-control on public read paths: #path"() {
        given:
        def req = new MockHttpServletRequest('GET', path)
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        // either unset or at least not "no-store"
        !(resp.getHeader('Cache-Control')?.contains('no-store'))

        where:
        path << ['/', '/api/listings', '/api/items/1', '/api/database']
    }

    def "MDC is cleared even when downstream throws"() {
        given:
        def req = new MockHttpServletRequest('GET', '/api/listings')
        def resp = new MockHttpServletResponse()
        chain.doFilter(_, _) >> { throw new RuntimeException('boom') }

        when:
        try { filter.doFilter(req, resp, chain) } catch (RuntimeException ignored) {}

        then:
        MDC.get('cid') == null
    }
}
