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
        // Cloudflare auto-injects a RUM beacon from static.cloudflareinsights.com
        // on every HTML response when the zone has Web Analytics enabled.
        // Tester flagged a red-X network entry when CSP blocked it; this
        // assertion pins the allowlist so it can't silently regress.
        csp?.contains('static.cloudflareinsights.com')
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
            '/api/support/tickets',
            // Seller dashboards — personal data. The /api/listings/* prefix
            // is mostly public (browse/search), but /my-stall* is per-user
            // and was slipping through without no-store before isMyStall
            // was added to the filter predicate.
            '/api/listings/my-stall',
            '/api/listings/my-stall/sold',
            '/api/listings/my-stall/active.csv',
            '/api/listings/my-stall/analytics.csv',
            '/api/listings/my-stall/sold.csv',
            '/api/listings/my-stall/earnings',
            // Same coverage for the seller's owned inventory + their
            // own verification-progress snapshot (KYC milestones).
            '/api/listings/inventory',
            '/api/sellers/me/verification-progress'
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

    def "filter sets NO Cache-Control on OpenGraphController routes: #path"() {
        // OpenGraphController serves an HTML shell for these routes and
        // stamps its OWN Cache-Control via ResponseEntity.header(), which
        // APPENDS. If the filter also set one, the response would carry
        // two conflicting Cache-Control values and a CDN/browser merges
        // them so `no-cache` defeats the controller's `public, max-age`.
        // The filter must leave Cache-Control entirely alone here and let
        // the controller own it. Covers the SEO browse pages + their
        // trailing-slash variants + the bare /loadout index.
        given:
        def req = new MockHttpServletRequest('GET', path)
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        resp.getHeaderNames().every { it != 'Cache-Control' }

        where:
        path << [
            '/market', '/market/', '/search', '/search/',
            '/db', '/db/', '/help', '/help/',
            '/loadout', '/affiliate', '/affiliate/',
            '/faq', '/faq/',
            // privateShell() routes — controller sets its own
            // `no-cache, must-revalidate`; filter must not duplicate it.
            '/profile', '/wallet', '/cart', '/offers', '/buy-orders',
            '/notifications', '/watchlist', '/settings', '/me/stall'
        ]
    }

    def "CORP=cross-origin on public-CORS read endpoints so the WebConfig allowlist actually works: #path"() {
        // Regression: a blanket Cross-Origin-Resource-Policy: same-origin
        // overruled the public CORS allowlist for /api/listings/*,
        // /api/items/*, /api/database/** — the CORS preflight passed,
        // Access-Control-Allow-Origin: * landed on the response, but the
        // browser then refused to hand the body to the cross-origin
        // caller (the Steam community extension, third-party curl) due
        // to CORP. The WebConfig allowlist promises the extension can
        // read these paths; this assertion pins that promise.
        given:
        def req = new MockHttpServletRequest('GET', path)
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        resp.getHeader('Cross-Origin-Resource-Policy') == 'cross-origin'

        where:
        path << [
            '/api/listings',
            '/api/listings/item/123',
            '/api/listings/seller/456/other',
            '/api/listings/stall/789',
            '/api/listings/stats',
            '/api/listings/just-listed',
            '/api/listings/top-deals',
            '/api/items',
            '/api/items/42',
            '/api/database',
            '/api/database/items'
        ]
    }

    def "CORP stays same-origin on every non-public path so authenticated surfaces are not embedded cross-origin: #path"() {
        given:
        def req = new MockHttpServletRequest('GET', path)
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        resp.getHeader('Cross-Origin-Resource-Policy') == 'same-origin'

        where:
        path << [
            '/',
            '/api/wallet',
            '/api/auth/steam/me',
            '/api/profile/me',
            '/api/listings/my-stall',
            '/api/listings/inventory',
            '/api/listings/sell',
            '/api/cart',
            '/api/admin/stats'
        ]
    }

    def "filter still sets Cache-Control on a genuine SPA shell route with no OG handler"() {
        // Negative control: routes WITHOUT a dedicated OpenGraphController
        // handler still need the filter's revalidation header so a fresh
        // deploy's non-hashed bundles land immediately.
        given:
        def req = new MockHttpServletRequest('GET', path)
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        resp.getHeader('Cache-Control') == 'no-cache, must-revalidate'

        where:
        path << ['/some-spa-route', '/leaderboard', '/deals']
    }

    def "Vary: Cookie set on /api/** so a per-viewer cached response isn't served to the next user on a shared browser: #path"() {
        // Regression: ListingController emits `Cache-Control: private,
        // max-age=30` on blocklist-filtered rails (/just-listed,
        // /top-deals, /ending-soon, /listings/item/{id} etc.). Without
        // `Vary: Cookie` in the response, the browser's HTTP cache is
        // keyed by request URI alone — so user A's blocklist-filtered
        // rail can be replayed to user B if B signs in on the same
        // browser within the cache window. The filter must add the Vary
        // key on every /api/** response so the cache differentiates per
        // session cookie value.
        given:
        def req = new MockHttpServletRequest('GET', path)
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        resp.getHeader('Vary') == 'Cookie'

        where:
        path << [
            '/api/listings/just-listed',          // private, max-age=30
            '/api/listings/top-deals',            // private, max-age=60
            '/api/listings/ending-soon',          // private, max-age=30
            '/api/listings/item/42',              // private, max-age=15
            '/api/wallet',                        // no-store (still wants Vary for correctness on shared caches)
            '/api/profile/me',                    // no-store
            '/api/reviews/user/5',                // public/private split
            '/api/buy-orders/top',                // public read aggregate
            '/api/items/42'                       // public catalogue
        ]
    }

    def "Vary: Cookie NOT set on non-/api/ paths so the SPA shell + static assets can be edge-cached by URL: #path"() {
        // SPA shell HTML and static assets are identical for every
        // viewer — adding Vary: Cookie would fragment the edge cache
        // per session unnecessarily and tank CDN hit-rate. Limit Vary
        // to the API surface where it actually matters.
        given:
        def req = new MockHttpServletRequest('GET', path)
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        resp.getHeader('Vary') == null

        where:
        path << ['/', '/market', '/profile', '/css/design.css', '/js/main.js', '/img/favicon-512.png']
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
