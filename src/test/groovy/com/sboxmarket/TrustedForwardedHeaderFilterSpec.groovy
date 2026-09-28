package com.sboxmarket

import com.sboxmarket.config.ClientIpResolver
import com.sboxmarket.config.TrustedForwardedHeaderFilter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.filter.ForwardedHeaderFilter
import spock.lang.Specification

/**
 * The stock ForwardedHeaderFilter took the leftmost X-Forwarded-For entry as
 * the client IP, from anyone, so a fresh fake header per request reset every
 * IP rate-limit bucket in prod. The replacement only believes forwarded
 * headers from a trusted proxy and then takes Cloudflare's CF-Connecting-IP
 * or the rightmost untrusted hop.
 */
class TrustedForwardedHeaderFilterSpec extends Specification {

    ClientIpResolver resolver = new ClientIpResolver(
        trustedProxiesProp: '127.0.0.0/8,::1/128,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16')

    private Map run(ForwardedHeaderFilter filter, MockHttpServletRequest req) {
        def seen = [:]
        FilterChain chain = { ServletRequest r, ServletResponse s ->
            def hr = r as HttpServletRequest
            seen.remoteAddr = hr.remoteAddr
            seen.scheme = hr.scheme
            seen.secure = hr.secure
            seen.serverName = hr.serverName
        } as FilterChain
        filter.doFilter(req, new MockHttpServletResponse(), chain)
        seen
    }

    private static MockHttpServletRequest request(String peer, Map<String, String> headers) {
        def req = new MockHttpServletRequest('GET', '/api/auth/steam/login')
        req.remoteAddr = peer
        req.scheme = 'http'
        req.serverName = 'localhost'
        req.serverPort = 8082
        headers.each { k, v -> req.addHeader(k, v) }
        req
    }

    def "the stock filter trusts a client-typed X-Forwarded-For (the hole)"() {
        expect:
        run(new ForwardedHeaderFilter(), request('127.0.0.1', [
            'X-Forwarded-For': '6.6.6.6, 203.0.113.9'])).remoteAddr == '6.6.6.6'
    }

    def "behind Cloudflare the client IP is CF-Connecting-IP, not the spoofed leftmost hop"() {
        when:
        def seen = run(new TrustedForwardedHeaderFilter(resolver), request('127.0.0.1', [
            'X-Forwarded-For'  : '6.6.6.6, 203.0.113.9',
            'CF-Connecting-IP' : '203.0.113.9',
            'X-Forwarded-Proto': 'https',
            'X-Forwarded-Host' : 'skinbox.market']))

        then:
        seen.remoteAddr == '203.0.113.9'
        seen.scheme == 'https'
        seen.secure
        seen.serverName == 'skinbox.market'
    }

    def "without CF-Connecting-IP the rightmost untrusted hop wins"() {
        expect:
        run(new TrustedForwardedHeaderFilter(resolver), request('172.18.0.1', [
            'X-Forwarded-For': '6.6.6.6, 203.0.113.9, 10.0.0.5'])).remoteAddr == '203.0.113.9'
    }

    def "an RFC 7239 Forwarded header is ignored even from a trusted proxy"() {
        expect:
        run(new TrustedForwardedHeaderFilter(resolver), request('127.0.0.1', [
            'Forwarded'      : 'for=6.6.6.6;proto=https',
            'X-Forwarded-For': '203.0.113.9'])).remoteAddr == '203.0.113.9'
    }

    def "a direct (untrusted) client cannot set any forwarded header"() {
        when:
        def seen = run(new TrustedForwardedHeaderFilter(resolver), request('198.51.100.7', [
            'X-Forwarded-For'  : '6.6.6.6',
            'CF-Connecting-IP' : '6.6.6.6',
            'Forwarded'        : 'for=6.6.6.6',
            'X-Forwarded-Proto': 'https',
            'X-Forwarded-Host' : 'evil.example']))

        then:
        seen.remoteAddr == '198.51.100.7'
        seen.scheme == 'http'
        seen.serverName == 'localhost'
    }

    def "a trusted proxy with no forwarded headers leaves the peer address"() {
        expect:
        run(new TrustedForwardedHeaderFilter(resolver), request('127.0.0.1', [:])).remoteAddr == '127.0.0.1'
    }
}
