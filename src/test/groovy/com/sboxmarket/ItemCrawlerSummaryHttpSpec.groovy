package com.sboxmarket

import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import spock.lang.Specification

/**
 * Over real HTTP: the /item/{id} shell carries a crawler-readable summary
 * (name, lowest price, active listing count) in a noscript outside #root,
 * with the name HTML-escaped; robots.txt opens the two item APIs; and
 * crawler fetches of those APIs never count as item views.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ItemCrawlerSummaryHttpSpec extends Specification {

    static final String GOOGLEBOT = 'Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)'
    static final String GOOGLE_OTHER = 'GoogleOther'
    static final String INSPECTION = 'Mozilla/5.0 (compatible; Google-InspectionTool/1.0;)'
    static final String CHROME = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36'

    @Autowired MockMvc mockMvc
    @Autowired ItemRepository itemRepo
    @Autowired ListingRepository listingRepo

    String uniq = String.valueOf(System.nanoTime())

    private Item item(String name, BigDecimal lowest) {
        itemRepo.save(new Item(name: name, category: 'Hats', rarity: 'Standard', supply: 0, totalSold: 0,
            lowestPrice: lowest, isListed: true, iconEmoji: '🎩', viewCount: 0L))
    }

    private void listing(Item it, BigDecimal price, String status = 'ACTIVE') {
        listingRepo.save(new Listing(item: it, sellerUserId: 1L, sellerName: 'seller', price: price,
            status: status, listingType: 'BUY_NOW'))
    }

    private long views(Long id) {
        (itemRepo.findById(id).get().viewCount ?: 0L) as long
    }

    private String get(String path, String ua = null) {
        def b = MockMvcRequestBuilders.get(path)
        if (ua) b = b.header('User-Agent', ua)
        def r = mockMvc.perform(b).andReturn()
        assert r.response.status == 200
        r.response.contentAsString
    }

    def "the item page carries an escaped name, the lowest price and the active listing count outside #root"() {
        given:
        def itm = item("Crown <script>alert(1)</script> \"Q\" & 'A' ${uniq}", 12.5G)
        listing(itm, 12.5G)
        listing(itm, 30G)
        listing(itm, 5G, 'SOLD')

        when:
        def body = get('/item/' + itm.id)
        int at = body.indexOf('<noscript data-item-summary>')
        def summary = body.substring(at, body.indexOf('</noscript>', at))

        then: 'the summary sits after the React root and before the shell\'s own noscript'
        at > body.indexOf('boot-skel-main')
        at < body.indexOf('JavaScript required')
        body.count('<noscript data-item-summary>') == 1

        and: 'name escaped, real numbers'
        summary.contains("<h1 style=\"margin:0 0 8px;font-size:20px;\">Crown &lt;script&gt;alert(1)&lt;/script&gt; &quot;Q&quot; &amp; &#39;A&#39; ${uniq}</h1>")
        summary.contains('Lowest price: $12.50 USD')
        summary.contains('Active listings: 2')
        summary.contains('href="http://localhost/item/' + itm.id + '"')

        and: 'no raw markup from the name anywhere in the page'
        !body.contains('<script>alert(1)</script>')
    }

    def "an out-of-stock item says so and counts zero listings"() {
        given:
        def itm = item("Empty Hat ${uniq}", 0G)

        when:
        def body = get('/item/' + itm.id)

        then:
        body.contains('<noscript data-item-summary>')
        body.contains('Lowest price: out of stock')
        body.contains('Active listings: 0')
    }

    def "an unknown item gets no summary"() {
        expect:
        !get('/item/999999998').contains('data-item-summary')
    }

    def "every item API a crawler may fetch leaves the view count alone"() {
        given:
        def itm = item("Crawled ${uniq}", 4G)
        listing(itm, 4G)

        when: 'Google fetches everything robots.txt now allows for this item'
        [GOOGLEBOT, GOOGLE_OTHER, INSPECTION].each { ua ->
            ['/api/items/' + itm.id, '/api/listings/item/' + itm.id, '/api/items/' + itm.id + '/history',
             '/api/items/' + itm.id + '/recent-sales', '/api/items/' + itm.id + '/similar'].each { p -> get(p, ua) }
        }

        then:
        views(itm.id) == 0L

        when: 'a person opens the same item'
        get('/api/items/' + itm.id, CHROME)

        then:
        views(itm.id) == 1L
    }

    def "robots.txt allows the two item APIs ahead of the /api/ disallow"() {
        when:
        def body = get('/robots.txt')
        def lines = body.readLines()*.trim()

        then:
        lines.contains('Allow: /api/listings/item/')
        lines.contains('Allow: /api/items/')
        lines.contains('Disallow: /api/')
        lines.indexOf('Allow: /api/listings/item/') < lines.indexOf('Disallow: /api/')
        lines.indexOf('Allow: /api/items/') < lines.indexOf('Disallow: /api/')

        and: 'the private API surfaces stay closed'
        !lines.any { it.startsWith('Allow: /api/') && !(it in ['Allow: /api/listings/item/', 'Allow: /api/items/']) }
    }
}
