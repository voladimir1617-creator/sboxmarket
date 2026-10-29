package com.sboxmarket

import com.sboxmarket.controller.ItemController
import com.sboxmarket.controller.OpenGraphController
import com.sboxmarket.controller.SitemapController
import com.sboxmarket.model.Item
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.LoadoutRepository
import com.sboxmarket.repository.SteamUserRepository
import groovy.json.JsonSlurper
import org.springframework.mock.web.MockHttpServletRequest
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Item pages describe many sellers' offers (AggregateOffer), the sitemap
 * dates an item by its last listing or sale, and crawler loads don't count
 * as item views.
 */
class SearchDiscoverySignalsSpec extends Specification {

    ItemRepository itemRepository = Mock()
    ListingRepository listingRepository = Mock()

    private static final String TEMPLATE = '''<!DOCTYPE html>
<html>
<head>
  <title>SkinBox</title>
  <meta name="description" content="x">
  <meta name="robots" content="index, follow">
  <meta property="og:title" content="SkinBox">
  <meta property="og:description" content="x">
  <meta property="og:image" content="https://skinbox.test/img/favicon-512.png">
  <meta property="og:url" content="https://skinbox.test">
  <meta property="og:type" content="website">
  <meta name="twitter:title" content="SkinBox">
</head>
<body></body>
</html>'''

    private MockHttpServletRequest req(String path) {
        def r = new MockHttpServletRequest('GET', path)
        r.scheme = 'https'
        r.serverName = 'skinbox.test'
        r.addHeader('Host', 'skinbox.test')
        r
    }

    private OpenGraphController og() {
        def c = new OpenGraphController(itemRepository: itemRepository, steamUserRepository: Mock(SteamUserRepository),
            loadoutRepository: Mock(LoadoutRepository), listingRepository: listingRepository,
            publicUrl: 'https://skinbox.test')
        c.template = TEMPLATE
        c.templatePinned = true
        c
    }

    private static Map offers(String body) {
        def start = body.indexOf('<script type="application/ld+json">') + '<script type="application/ld+json">'.length()
        def json = body.substring(start, body.indexOf('</script>', start))
        new JsonSlurper().parseText(json).offers as Map
    }

    // ── AggregateOffer ──────────────────────────────────────────

    def "an in-stock item emits a valid AggregateOffer with range and seller count"() {
        given:
        itemRepository.findById(7L) >> Optional.of(new Item(id: 7L, name: 'Wizard "Hat"', lowestPrice: 4.98G))
        listingRepository.countActiveForItem(7L) >> 3L
        listingRepository.maxPriceForItem(7L) >> 12.5G

        when:
        def o = offers(og().itemPage('7', req('/item/7')).body as String)

        then:
        o['@type'] == 'AggregateOffer'
        o.lowPrice == '4.98'
        o.highPrice == '12.50'
        o.offerCount == 3
        o.priceCurrency == 'USD'
        o.availability == 'https://schema.org/InStock'
        !o.containsKey('price')
    }

    def "one listing gives low equal to high; a stale max below the floor is lifted to it"() {
        given:
        itemRepository.findById(7L) >> Optional.of(new Item(id: 7L, name: 'Cap', lowestPrice: 5G))
        listingRepository.countActiveForItem(7L) >> 1L
        listingRepository.maxPriceForItem(7L) >> max

        expect:
        offers(og().itemPage('7', req('/item/7')).body as String).highPrice == '5.00'

        where:
        max << [5G, 4G, null]
    }

    def "no live listing, an out-of-stock item or a failing query keeps the single Offer"() {
        given:
        itemRepository.findById(7L) >> Optional.of(new Item(id: 7L, name: 'Cap', lowestPrice: floor))
        listingRepository.countActiveForItem(7L) >> { if (boom) throw new RuntimeException('db'); count }
        listingRepository.maxPriceForItem(7L) >> 9G

        when:
        def o = offers(og().itemPage('7', req('/item/7')).body as String)

        then:
        o['@type'] == 'Offer'
        o.availability == availability

        where:
        floor | count | boom  || availability
        5G    | 0L    | false || 'https://schema.org/InStock'
        null  | 2L    | false || 'https://schema.org/OutOfStock'
        5G    | 2L    | true  || 'https://schema.org/InStock'
    }

    // ── sitemap lastmod ─────────────────────────────────────────

    def "an item's lastmod is its newest listing or sale, else its catalogue date, from one query"() {
        given:
        def created = 1704067200000L                       // 2024-01-01
        itemRepository.findAllForSitemap(_) >> [
            new Item(id: 1L, name: 'A', createdAt: created),
            new Item(id: 2L, name: 'B', createdAt: created),
            new Item(id: 3L, name: 'C', createdAt: created)
        ]
        def sitemap = new SitemapController(itemRepository: itemRepository, listingRepository: listingRepository,
            publicUrl: 'https://skinbox.test/')

        when:
        def body = sitemap.sitemap(req('/sitemap.xml')).body as String

        then:
        1 * listingRepository.latestActivityByItem() >> [
            [1L, 1717200000000L, 1719792000000L] as Object[],  // listed 2024-06-01, sold 2024-07-01
            [2L, 1709251200000L, null] as Object[],            // listed 2024-03-01
            [3L, 1600000000000L, null] as Object[]             // older than the item: ignored
        ]
        lastmod(body, 1) == '2024-07-01'
        lastmod(body, 2) == '2024-03-01'
        lastmod(body, 3) == '2024-01-01'
    }

    def "a failing activity query falls back to the catalogue date"() {
        given:
        itemRepository.findAllForSitemap(_) >> [new Item(id: 1L, name: 'A', createdAt: 1704067200000L)]
        listingRepository.latestActivityByItem() >> { throw new RuntimeException('db') }
        def sitemap = new SitemapController(itemRepository: itemRepository, listingRepository: listingRepository,
            publicUrl: 'https://skinbox.test/')

        expect:
        lastmod(sitemap.sitemap(req('/sitemap.xml')).body as String, 1) == '2024-01-01'
    }

    private static String lastmod(String body, long id) {
        def m = body =~ /<loc>https:\/\/skinbox\.test\/item\/${id}<\/loc>\s*<lastmod>([^<]+)<\/lastmod>/
        m.find() ? m.group(1) : null
    }

    // ── crawler views ───────────────────────────────────────────

    @Unroll
    def "#ua is #verdict"() {
        given:
        def r = new MockHttpServletRequest()
        if (ua != null) r.addHeader('User-Agent', ua)

        expect:
        ItemController.isCrawler(r) == crawler

        where:
        ua                                                                                   || crawler
        'Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)'          || true
        'Mozilla/5.0 (compatible; bingbot/2.0; +http://www.bing.com/bingbot.htm)'           || true
        'Mozilla/5.0 (compatible; Discordbot/2.0; +https://discordapp.com)'                 || true
        'facebookexternalhit/1.1'                                                           || true
        'curl/8.4.0'                                                                        || true
        'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0 Safari/537.36' || false
        'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148' || false
        null                                                                                 || false
        verdict = crawler ? 'a crawler' : 'a person'
    }
}
