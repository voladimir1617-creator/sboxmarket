package com.sboxmarket

import com.sboxmarket.config.ClientIpResolver
import org.springframework.mock.web.MockHttpServletRequest
import spock.lang.Specification
import spock.lang.Unroll

/**
 * The single source of truth for spoofing-resistant client-IP resolution
 * (task #234 logic, extracted so AuditService + ItemController share it). The
 * security-critical property: forwarded headers (CF-Connecting-IP /
 * X-Forwarded-For) are honoured ONLY when the immediate TCP peer (remoteAddr)
 * is itself a trusted reverse proxy — otherwise a direct client could forge its
 * IP. Default trusted set = loopback only (prod widens it via the property).
 */
class ClientIpResolverSpec extends Specification {

    ClientIpResolver resolver = new ClientIpResolver()   // default trusted-proxies = 127.0.0.0/8,::1/128

    private MockHttpServletRequest req(String remote, Map<String, String> headers = [:]) {
        def r = new MockHttpServletRequest()
        // MockHttpServletRequest defaults remoteAddr to 127.0.0.1; null-out
        // explicitly when a test needs the no-address case.
        r.setRemoteAddr(remote)
        headers.each { k, v -> r.addHeader(k, v) }
        r
    }

    def "behind a trusted (loopback) proxy, CF-Connecting-IP wins over XFF and remoteAddr"() {
        expect:
        resolver.resolve(req('127.0.0.1',
            ['CF-Connecting-IP': '203.0.113.5', 'X-Forwarded-For': '198.51.100.7, 10.0.0.1'])) == '203.0.113.5'
    }

    def "behind a trusted proxy, a blank CF header falls through to the first non-blank XFF token"() {
        expect:
        resolver.resolve(req('127.0.0.1',
            ['CF-Connecting-IP': '   ', 'X-Forwarded-For': '198.51.100.7, 10.0.0.1, 10.0.0.2'])) == '198.51.100.7'
    }

    def "behind a trusted proxy, a leading blank XFF token is skipped"() {
        expect:
        resolver.resolve(req('127.0.0.1', ['X-Forwarded-For': ', 198.51.100.7, 10.0.0.1'])) == '198.51.100.7'
    }

    @Unroll
    def "behind a trusted proxy, a crafted all-comma XFF (#xff) yields no token and falls through to remoteAddr — never throws"() {
        when:
        def ip = resolver.resolve(req('127.0.0.1', ['X-Forwarded-For': xff]))
        then:
        noExceptionThrown()
        ip == '127.0.0.1'
        where:
        xff << [',', ',,', ',,,', ' , , , ']
    }

    def "SECURITY: a NON-trusted peer's spoofed CF-Connecting-IP is IGNORED — the real socket IP is returned"() {
        expect:
        resolver.resolve(req('1.2.3.4',
            ['CF-Connecting-IP': '203.0.113.5', 'X-Forwarded-For': '9.9.9.9'])) == '1.2.3.4'
    }

    def "SECURITY: a NON-trusted private-range peer also can't spoof (default trusts loopback only)"() {
        expect:
        resolver.resolve(req('10.0.0.50', ['CF-Connecting-IP': '203.0.113.5'])) == '10.0.0.50'
    }

    def "a widened trusted-proxy CIDR honours the header from that range"() {
        given:
        resolver.trustedProxiesProp = '10.0.0.0/8'
        expect: 'now 10.0.0.50 is a trusted peer, so its forwarded header is honoured'
        resolver.resolve(req('10.0.0.50', ['CF-Connecting-IP': '203.0.113.5'])) == '203.0.113.5'
    }

    def "no headers, any peer → remoteAddr"() {
        expect:
        resolver.resolve(req('192.168.1.42')) == '192.168.1.42'
    }

    def "null request → empty string"() {
        expect:
        resolver.resolve(null) == ''
    }

    def "missing remoteAddr → empty string, no NPE"() {
        expect:
        resolver.resolve(req(null)) == ''
    }

    def "isTrustedProxy: loopback is always trusted, an arbitrary public IP is not"() {
        expect:
        resolver.isTrustedProxy('127.0.0.1')
        resolver.isTrustedProxy('::1')
        !resolver.isTrustedProxy('8.8.8.8')
        !resolver.isTrustedProxy(null)
        !resolver.isTrustedProxy('not-an-ip')
    }
}
