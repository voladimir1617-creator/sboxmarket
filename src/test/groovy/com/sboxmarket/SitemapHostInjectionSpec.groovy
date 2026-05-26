package com.sboxmarket

import com.sboxmarket.controller.SitemapController
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import org.springframework.mock.web.MockHttpServletRequest
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

/**
 * Host-header injection guard for the public sitemap.xml.
 *
 * The `Host` and `X-Forwarded-Host` headers are attacker-controllable on
 * any request that bypasses the Cloudflare WAF (or any non-prod proxy
 * hop). Before this guard the resolveBaseUrl() result was concatenated
 * straight into `<loc>` XML and the whole response was cached by
 * Cloudflare for an hour via `Cache-Control: public, max-age=3600` —
 * so a single poisoned request could plant phishing URLs in the
 * sitemap that Googlebot subsequently indexed as SkinBox-owned.
 *
 * Threat model verified here:
 *   1. `X-Forwarded-Host: a.com</loc><loc>https://phishing.com` —
 *      attempts to break out of the `<loc>` tag and inject another URL.
 *   2. `Host: javascript:alert(1)` — attempts to ship a javascript:
 *      scheme entry into the sitemap.
 *   3. `Host: skinbox.market\r\nX-Injected: 1` — CRLF injection.
 *   4. `Host:   skinbox.market` with leading whitespace — accepted by
 *      some servers, would skew the equalsIgnoreCase("skinbox.market")
 *      check.
 *   5. `X-Forwarded-Proto: javascript` — scheme injection.
 *
 * Each case must fall back to the trusted `publicUrl` constant; none
 * may leak the malicious value into the response body.
 */
class SitemapHostInjectionSpec extends Specification {

    ItemRepository itemRepository = Mock()
    ListingRepository listingRepository = Mock()

    @Subject
    SitemapController controller = new SitemapController(
        itemRepository:    itemRepository,
        listingRepository: listingRepository,
        publicUrl:         'https://skinbox.market'
    )

    private MockHttpServletRequest reqWithHeader(String name, String value) {
        def r = new MockHttpServletRequest('GET', '/sitemap.xml')
        r.scheme = 'http'
        r.serverName = 'localhost'
        r.addHeader(name, value)
        return r
    }

    @Unroll
    def "malicious X-Forwarded-Host value '#payload' must NOT appear in sitemap body"() {
        given:
        itemRepository.findAllForSitemap(_) >> []
        def r = reqWithHeader('X-Forwarded-Host', payload)

        when:
        def body = controller.sitemap(r).body as String

        then:
        // The malicious payload must not be reflected anywhere in the body.
        !body.contains(payload)
        // Fallback to publicUrl — every static URL still anchored at the
        // trusted host so the sitemap is still well-formed.
        body.contains('<loc>https://skinbox.market/</loc>')
        // Crucially: no second <loc>...</loc> spawned by an injection.
        !body.contains('phishing.com')
        !body.contains('javascript:')
        !body.contains('X-Injected')

        where:
        payload << [
            'a.com</loc><loc>https://phishing.com',
            'javascript:alert(1)',
            'evil.com">attacker',
            'skinbox.market\r\nX-Injected: 1',
            '  skinbox.market', // leading whitespace
            'skinbox.market/extra/path',
            'skinbox.market?evil=1',
            '<script>alert(1)</script>',
            '../../../etc/passwd'
        ]
    }

    def "malicious Host header (no X-Forwarded-Host) also rejected"() {
        given:
        itemRepository.findAllForSitemap(_) >> []
        def r = reqWithHeader('Host', 'a.com</loc><loc>https://phishing.com')

        when:
        def body = controller.sitemap(r).body as String

        then:
        !body.contains('phishing.com')
        !body.contains('</loc><loc>')  // no broken-out tag pair
        // Falls back to publicUrl
        body.contains('<loc>https://skinbox.market/</loc>')
    }

    def "X-Forwarded-Proto value other than http/https is rejected"() {
        given:
        itemRepository.findAllForSitemap(_) >> []
        def r = new MockHttpServletRequest('GET', '/sitemap.xml')
        r.scheme = 'http'
        r.addHeader('Host', 'skinbox.market')
        r.addHeader('X-Forwarded-Proto', 'javascript')

        when:
        def body = controller.sitemap(r).body as String

        then:
        // Production-host whitelist still kicks in, so we get https; but
        // critically the malicious scheme value never appears in the body.
        !body.contains('javascript:')
        body.contains('<loc>https://skinbox.market/</loc>')
    }

    def "legitimate Cloudflare-shaped request still resolves the right base"() {
        // Regression — the guard must not break the normal CF→nginx hop.
        given:
        itemRepository.findAllForSitemap(_) >> []
        def r = new MockHttpServletRequest('GET', '/sitemap.xml')
        r.scheme = 'http'
        r.addHeader('Host', 'localhost:8082')
        r.addHeader('X-Forwarded-Proto', 'https')
        r.addHeader('X-Forwarded-Host', 'skinbox.market')

        when:
        def body = controller.sitemap(r).body as String

        then:
        body.contains('<loc>https://skinbox.market/</loc>')
        !body.contains('localhost')
    }

    def "legitimate localhost:port request still resolves for local dev"() {
        // Regression — port-suffixed hostnames are still valid Hosts.
        given:
        itemRepository.findAllForSitemap(_) >> []
        def r = new MockHttpServletRequest('GET', '/sitemap.xml')
        r.scheme = 'http'
        r.addHeader('Host', 'localhost:8082')

        when:
        def body = controller.sitemap(r).body as String

        then:
        body.contains('<loc>http://localhost:8082/</loc>')
    }
}
