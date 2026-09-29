package com.sboxmarket

import com.sboxmarket.config.CanonicalPathFilter
import jakarta.servlet.FilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import spock.lang.Specification
import spock.lang.Unroll

/**
 * `/api;x/wallet` and `/%61pi/wallet` routed to WalletController while the
 * CSRF, session-epoch, rate-limit and API-key filters (all prefix checks on
 * the raw requestURI) saw a non-/api path and let them through. A session
 * revoked by "Sign out everywhere" kept working on those spellings. The
 * filter now refuses them before any of those filters run.
 */
class CanonicalPathFilterSpec extends Specification {

    CanonicalPathFilter filter = new CanonicalPathFilter()
    FilterChain chain = Mock()

    @Unroll
    def "rejects #path with 400 and never reaches the chain"() {
        given:
        def req = new MockHttpServletRequest('POST', path)
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        0 * chain.doFilter(_, _)
        resp.status == 400
        resp.contentAsString.contains('BAD_PATH')

        where:
        path << [
            '/api;x/wallet',
            '/api;/profile/sign-out-everywhere',
            '/api/wallet;jsessionid=abc/withdraw',
            '/api/wallet/withdraw;x',
            '/%61pi/wallet',
            '/%41pi/wallet',
            '/api/w%61llet/withdraw',
            '/api/%2e/wallet',
            '/api/unsubscribe/%2E%2E/wallet',
            '/api/%7ewallet',
            '/api/%5fx',
            '/api/%2d',
            '/api/%30',
            '/api/%2561pi',
        ]
    }

    @Unroll
    def "lets canonical path #path through"() {
        given:
        def req = new MockHttpServletRequest('GET', path)
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(req, resp)
        resp.status == 200

        where:
        path << [
            '/',
            '/api/wallet',
            '/api/items/by-name/AK%20Redline',
            '/api/items/by-name/Hat%20%7C%20Blue',
            '/api/items/by-name/%E2%98%85%20Knife',
            '/js/app.js',
            '/profile/transactions',
        ]
    }
}
