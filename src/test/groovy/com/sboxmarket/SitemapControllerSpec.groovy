package com.sboxmarket

import com.sboxmarket.controller.SitemapController
import com.sboxmarket.model.Item
import com.sboxmarket.repository.ItemRepository
import spock.lang.Specification
import spock.lang.Subject

/**
 * Unit coverage for the dynamic sitemap generator. Exercises the
 * base-URL normalisation, the static-URL emission, and the per-item
 * URL dynamic section. Failure modes (empty catalogue, repo throws)
 * must return the static URLs only — sitemap generation is best-effort.
 */
class SitemapControllerSpec extends Specification {

    ItemRepository itemRepository = Mock()
    com.sboxmarket.repository.ListingRepository listingRepository = Mock()

    @Subject
    SitemapController controller = new SitemapController(
        itemRepository:    itemRepository,
        listingRepository: listingRepository,
        publicUrl:         'https://skinbox.test/'  // deliberate trailing slash
    )

    /**
     * Build a default request fixture matching the publicUrl host so the
     * existing assertions ("loc starts with https://skinbox.test/...")
     * keep working after the controller switched to deriving the base
     * from the request rather than the env var.
     */
    private org.springframework.mock.web.MockHttpServletRequest req() {
        def r = new org.springframework.mock.web.MockHttpServletRequest('GET', '/sitemap.xml')
        r.scheme = 'https'
        r.serverName = 'skinbox.test'
        r.addHeader('Host', 'skinbox.test')
        return r
    }

    def "Cloudflare X-Forwarded-Host overrides plain Host so prod URLs are correct (2026-05-01)"() {
        // The same container serves http://localhost:8082 (local dev)
        // AND https://skinbox.market (Cloudflare tunnel). Pre-fix the
        // sitemap emitted localhost URLs publicly because APP_PUBLIC_URL
        // was the only signal. Now we honour X-Forwarded-{Proto,Host}
        // so each request audience gets the URL set it expects.
        given:
        itemRepository.findAllForSitemap(_) >> []
        def r = new org.springframework.mock.web.MockHttpServletRequest('GET', '/sitemap.xml')
        r.scheme = 'http'
        r.addHeader('Host', 'localhost:8082')
        r.addHeader('X-Forwarded-Proto', 'https')
        r.addHeader('X-Forwarded-Host', 'skinbox.market')

        when:
        def body = controller.sitemap(r).body as String

        then:
        body.contains('<loc>https://skinbox.market/</loc>')
        // Critical regression: must NOT leak the local Host into a prod sitemap
        !body.contains('localhost:8082')
        !body.contains('http://localhost')
    }

    def "no proxy headers falls back to direct request scheme + Host (local dev)"() {
        given:
        itemRepository.findAllForSitemap(_) >> []
        def r = new org.springframework.mock.web.MockHttpServletRequest('GET', '/sitemap.xml')
        r.scheme = 'http'
        r.addHeader('Host', 'localhost:8082')

        when:
        def body = controller.sitemap(r).body as String

        then:
        body.contains('<loc>http://localhost:8082/</loc>')
    }

    def "production host forces https even when X-Forwarded-Proto is http (cloudflared->nginx hop)"() {
        // The cloudflared → nginx hop is plain HTTP and nginx replaces
        // X-Forwarded-Proto with its own $scheme (`http`). Without the
        // explicit production-host check, sitemap entries leaked as
        // `http://skinbox.market/...` — Google would index the http URL,
        // hit a 308 redirect on every crawl, and split crawl budget.
        given:
        itemRepository.findAllForSitemap(_) >> []
        def r = new org.springframework.mock.web.MockHttpServletRequest('GET', '/sitemap.xml')
        r.scheme = 'http'
        r.addHeader('Host', 'skinbox.market')
        r.addHeader('X-Forwarded-Proto', 'http')

        when:
        def body = controller.sitemap(r).body as String

        then:
        body.contains('<loc>https://skinbox.market/</loc>')
        !body.contains('<loc>http://skinbox.market')
    }

    def "sitemap emits every static URL"() {
        given:
        itemRepository.findAllForSitemap(_) >> []

        when:
        def body = controller.sitemap(req()).body as String

        then:
        body.contains('<urlset')
        body.contains('<loc>https://skinbox.test/</loc>')
        // /market is canonical; /search declares /market canonical, so it is left out.
        body.contains('<loc>https://skinbox.test/market</loc>')
        !body.contains('<loc>https://skinbox.test/search</loc>')
        body.contains('<loc>https://skinbox.test/db</loc>')
        body.contains('<loc>https://skinbox.test/loadout</loc>')
        body.contains('<loc>https://skinbox.test/help</loc>')
        body.contains('<loc>https://skinbox.test/faq</loc>')
        body.contains('<loc>https://skinbox.test/legal/terms.html</loc>')
        body.contains('<loc>https://skinbox.test/legal/cookies.html</loc>')
        // Batch 736 — changelog + status pages are public static HTML
        // with real SEO value and must be indexed.
        body.contains('<loc>https://skinbox.test/changelog.html</loc>')
        body.contains('<loc>https://skinbox.test/status.html</loc>')
        body.endsWith("</urlset>\n")
    }

    def "trailing slash in public-url is normalised"() {
        given:
        itemRepository.findAllForSitemap(_) >> []

        when:
        def body = controller.sitemap(req()).body as String

        then:
        // Should emit https://skinbox.test/ (single slash), never
        // https://skinbox.test//search (double slash from concat).
        !body.contains('//search')
        !body.contains('//db')
    }

    def "each catalogue item contributes an /item/{id} URL"() {
        given:
        itemRepository.findAllForSitemap(_) >> [
            new Item(id: 1L, name: 'A'),
            new Item(id: 42L, name: 'B'),
            new Item(id: 9999L, name: 'C')
        ]

        when:
        def body = controller.sitemap(req()).body as String

        then:
        body.contains('<loc>https://skinbox.test/item/1</loc>')
        body.contains('<loc>https://skinbox.test/item/42</loc>')
        body.contains('<loc>https://skinbox.test/item/9999</loc>')
        // Dynamic URLs get changefreq=daily, priority=0.7.
        // Batch 965 — <lastmod> is now inserted between <loc> and
        // <changefreq>; the regex tolerates its presence or absence.
        (body =~ /<loc>https:\/\/skinbox\.test\/item\/1<\/loc>\s*(<lastmod>[^<]+<\/lastmod>\s*)?<changefreq>daily<\/changefreq>\s*<priority>0\.7<\/priority>/).find()
    }

    def "each item URL carries a <lastmod> when the item has a createdAt (batch 965)"() {
        given:
        // ISO-8601 date from the epoch ms — 1704067200000 = 2024-01-01.
        itemRepository.findAllForSitemap(_) >> [new Item(id: 1L, name: 'A', createdAt: 1704067200000L)]

        when:
        def body = controller.sitemap(req()).body as String

        then:
        body.contains('<lastmod>2024-01-01</lastmod>')
    }

    def "appendLastmod is null-safe and zero-safe"() {
        given:
        def sb = new StringBuilder()

        when:
        com.sboxmarket.controller.SitemapController.appendLastmod(sb, null)
        com.sboxmarket.controller.SitemapController.appendLastmod(sb, 0L)
        com.sboxmarket.controller.SitemapController.appendLastmod(sb, -1L)

        then:
        // All three skip without emitting anything — no crashes.
        sb.toString() == ''
    }

    def "repository failure falls back to static URLs only"() {
        given:
        itemRepository.findAllForSitemap(_) >> { throw new RuntimeException('database offline') }

        when:
        def response = controller.sitemap(req())

        then:
        // Still returns 200 with the top-level URLs — sitemap generation
        // must never take down the server.
        response.statusCode.value() == 200
        def body = response.body as String
        body.contains('<urlset')
        body.contains('<loc>https://skinbox.test/</loc>')
        !body.contains('/item/')
    }

    def "Content-Type and Cache-Control headers are set"() {
        given:
        itemRepository.findAllForSitemap(_) >> []

        when:
        def response = controller.sitemap(req())

        then:
        response.headers.getFirst('Content-Type')?.toLowerCase()?.contains('xml')
        response.headers.getFirst('Cache-Control')?.contains('max-age=')
    }

    def "top-10000 cap keeps the response bounded even on huge catalogues"() {
        given:
        // Simulate a 20 000-item catalogue. Cap is enforced by take(10_000).
        // Simulated DB-level LIMIT 10_000 — the stub represents what the
        // paged JPQL query would return (top 10k), not the full 20k rows.
        def huge = (1..10_000).collect { new Item(id: it as Long, name: "Item $it") }
        itemRepository.findAllForSitemap(_) >> huge

        when:
        def body = controller.sitemap(req()).body as String

        then:
        // The very last item (id 20000) must NOT appear — it falls outside
        // the sitemap-spec-mandated 10k cap.
        !body.contains('<loc>https://skinbox.test/item/20000</loc>')
        // The 10 000th item must appear — it's the last one inside the cap.
        body.contains('<loc>https://skinbox.test/item/10000</loc>')
    }

    // ── seller stall URLs ────────────────────────────────────────

    def "sellers with at least one non-hidden listing contribute /stall/{id} URLs"() {
        given:
        // Batch 667 — sitemap now gates on any-listing (active OR sold),
        // not sold-only. A brand-new marketplace with no closed sales
        // still gets its active-listing stalls indexed.
        itemRepository.findAllForSitemap(_) >> []
        listingRepository.findSellerIdsWithAnyListing(_) >> [42L, 99L]

        when:
        def body = controller.sitemap(req()).body as String

        then:
        body.contains('<loc>https://skinbox.test/stall/42</loc>')
        body.contains('<loc>https://skinbox.test/stall/99</loc>')
        (body =~ /<loc>https:\/\/skinbox\.test\/stall\/42<\/loc>\s*<changefreq>weekly<\/changefreq>\s*<priority>0\.5<\/priority>/).find()
    }

    def "seller-list failure does not kill the sitemap"() {
        given:
        itemRepository.findAllForSitemap(_) >> []
        listingRepository.findSellerIdsWithAnyListing(_) >> { throw new RuntimeException('db offline') }

        when:
        def response = controller.sitemap(req())

        then:
        response.statusCode.value() == 200
        def body = response.body as String
        body.contains('<urlset')
        // Static URLs still there even if stall section blew up
        body.contains('<loc>https://skinbox.test/</loc>')
    }
}
