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

    @Subject
    SitemapController controller = new SitemapController(
        itemRepository: itemRepository,
        publicUrl:      'https://skinbox.test/'  // deliberate trailing slash
    )

    def "sitemap emits every static URL"() {
        given:
        itemRepository.findAll() >> []

        when:
        def body = controller.sitemap().body as String

        then:
        body.contains('<urlset')
        body.contains('<loc>https://skinbox.test/</loc>')
        body.contains('<loc>https://skinbox.test/search</loc>')
        body.contains('<loc>https://skinbox.test/db</loc>')
        body.contains('<loc>https://skinbox.test/loadout</loc>')
        body.contains('<loc>https://skinbox.test/help</loc>')
        body.contains('<loc>https://skinbox.test/faq</loc>')
        body.contains('<loc>https://skinbox.test/legal/terms.html</loc>')
        body.contains('<loc>https://skinbox.test/legal/cookies.html</loc>')
        body.endsWith("</urlset>\n")
    }

    def "trailing slash in public-url is normalised"() {
        given:
        itemRepository.findAll() >> []

        when:
        def body = controller.sitemap().body as String

        then:
        // Should emit https://skinbox.test/ (single slash), never
        // https://skinbox.test//search (double slash from concat).
        !body.contains('//search')
        !body.contains('//db')
    }

    def "each catalogue item contributes an /item/{id} URL"() {
        given:
        itemRepository.findAll() >> [
            new Item(id: 1L, name: 'A'),
            new Item(id: 42L, name: 'B'),
            new Item(id: 9999L, name: 'C')
        ]

        when:
        def body = controller.sitemap().body as String

        then:
        body.contains('<loc>https://skinbox.test/item/1</loc>')
        body.contains('<loc>https://skinbox.test/item/42</loc>')
        body.contains('<loc>https://skinbox.test/item/9999</loc>')
        // Dynamic URLs get changefreq=daily, priority=0.7.
        (body =~ /<loc>https:\/\/skinbox\.test\/item\/1<\/loc>\s*<changefreq>daily<\/changefreq>\s*<priority>0\.7<\/priority>/).find()
    }

    def "repository failure falls back to static URLs only"() {
        given:
        itemRepository.findAll() >> { throw new RuntimeException('database offline') }

        when:
        def response = controller.sitemap()

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
        itemRepository.findAll() >> []

        when:
        def response = controller.sitemap()

        then:
        response.headers.getFirst('Content-Type')?.toLowerCase()?.contains('xml')
        response.headers.getFirst('Cache-Control')?.contains('max-age=')
    }

    def "top-10000 cap keeps the response bounded even on huge catalogues"() {
        given:
        // Simulate a 20 000-item catalogue. Cap is enforced by take(10_000).
        def huge = (1..20_000).collect { new Item(id: it as Long, name: "Item $it") }
        itemRepository.findAll() >> huge

        when:
        def body = controller.sitemap().body as String

        then:
        // The very last item (id 20000) must NOT appear — it falls outside
        // the sitemap-spec-mandated 10k cap.
        !body.contains('<loc>https://skinbox.test/item/20000</loc>')
        // The 10 000th item must appear — it's the last one inside the cap.
        body.contains('<loc>https://skinbox.test/item/10000</loc>')
    }
}
