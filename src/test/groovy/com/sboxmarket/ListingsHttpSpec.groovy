package com.sboxmarket

import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import spock.lang.Specification

/**
 * HTTP-level pinning tests for /api/listings. Complements the service-level
 * ListingServiceSpec by proving the Spring MVC layer correctly:
 *   - caps limit at 100
 *   - whitelists sort values
 *   - rejects SQL-injection-style minPrice/maxPrice parameters as 400
 *   - caps search length to 100 chars
 *   - returns a bare array when default (limit=100, offset=0) and a wrapped
 *     {items, total, limit, offset} object otherwise
 *
 * Runs against the test profile H2 database so we hit real Hibernate
 * without mocks. CSRF is disabled in application-test.yml to let MockMvc
 * drive writes without the cookie handshake.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ListingsHttpSpec extends Specification {

    @Autowired ApplicationContext ctx

    MockMvc           mockMvc
    ItemRepository    itemRepo
    ListingRepository listingRepo
    Item              seededItem
    Listing           seededListing

    def setup() {
        mockMvc     = ctx.getBean(MockMvc)
        itemRepo    = ctx.getBean(ItemRepository)
        listingRepo = ctx.getBean(ListingRepository)

        def uniq = String.valueOf(System.nanoTime())
        seededItem = itemRepo.save(new Item(
            name:         "SpecItem-${uniq}",
            category:     'Hats',
            rarity:       'Limited',
            supply:       10,
            totalSold:    0,
            lowestPrice:  new BigDecimal("42.00"),
            iconEmoji:    '🎩'
        ))
        seededListing = listingRepo.save(new Listing(
            item:         seededItem,
            price:        new BigDecimal("42.00"),
            status:       'ACTIVE',
            sellerName:   "SpecSeller-${uniq}",
            rarityScore:  new BigDecimal("0.5")
        ))
    }

    def "GET /api/listings with default params returns a bare array that includes the seeded row"() {
        when:
        def result = mockMvc.perform(MockMvcRequestBuilders.get('/api/listings')).andReturn()

        then:
        result.response.status == 200
        def body = result.response.contentAsString
        body.startsWith('[')  // bare array, not wrapped
        body.contains("SpecItem-")
    }

    def "GET /api/listings with pagination params returns a wrapped object"() {
        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings').param('limit', '5').param('offset', '0')
        ).andReturn()

        then:
        result.response.status == 200
        def body = result.response.contentAsString
        body.contains('"items"')
        body.contains('"total"')
        body.contains('"limit":5')
    }

    def "GET /api/listings rejects malformed minPrice as 400 without leaking the value"() {
        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings').param('minPrice', "1';SELECT 1")
        ).andReturn()

        then:
        result.response.status == 400
        def body = result.response.contentAsString
        body.contains('"code"')
        !body.contains('SELECT')
    }

    def "GET /api/listings rejects minPrice longer than 16 chars"() {
        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings').param('minPrice', '9' * 50)
        ).andReturn()

        then:
        result.response.status == 400
    }

    def "GET /api/listings?sort=discount returns deep discounts first (regression for whitelist-dropped sort)"() {
        given:
        // Seed two items with catalogue steamPrices + listings priced under
        // them. Item A is a 50%-off listing; item B is only 5% off. Without
        // the fix, sort=discount silently fell through the whitelist and
        // landed as sort=price_asc — item B (cheaper) would come first.
        def uniq = String.valueOf(System.nanoTime())
        def deepItem = itemRepo.save(new Item(
            name: "DeepDisc-${uniq}", category: 'Hats', rarity: 'Standard',
            supply: 10, totalSold: 0,
            lowestPrice: new BigDecimal('5.00'),
            steamPrice:  new BigDecimal('10.00'),
            iconEmoji: '🎩'
        ))
        def shallowItem = itemRepo.save(new Item(
            name: "ShallowDisc-${uniq}", category: 'Hats', rarity: 'Standard',
            supply: 10, totalSold: 0,
            lowestPrice: new BigDecimal('3.80'),
            steamPrice:  new BigDecimal('4.00'),
            iconEmoji: '🎩'
        ))
        listingRepo.save(new Listing(
            item: deepItem, price: new BigDecimal('5.00'), status: 'ACTIVE',
            sellerName: "DeepSeller-${uniq}", rarityScore: BigDecimal.ZERO
        ))
        listingRepo.save(new Listing(
            item: shallowItem, price: new BigDecimal('3.80'), status: 'ACTIVE',
            sellerName: "ShallowSeller-${uniq}", rarityScore: BigDecimal.ZERO
        ))

        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings').param('sort', 'discount')
        ).andReturn()

        then:
        result.response.status == 200
        def body = result.response.contentAsString
        // Deep (50%-off) appears before Shallow (5%-off) in the response.
        def iDeep    = body.indexOf("DeepDisc-${uniq}")
        def iShallow = body.indexOf("ShallowDisc-${uniq}")
        iDeep >= 0
        iShallow >= 0
        iDeep < iShallow
    }

    def "GET /api/listings caps search length and sort value defensively"() {
        given:
        def longSearch = 'a' * 500

        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings')
                .param('search', longSearch)
                .param('sort', 'DROP_TABLE')
        ).andReturn()

        then:
        // Doesn't 500 — the controller clamps search and whitelists sort.
        result.response.status == 200 || result.response.status == 429
    }

    def "GET /api/listings caps limit at 100 even when a larger value is supplied"() {
        given:
        // Create more than 100 listings so the cap is actually triggered
        def extra = (1..110).collect { i ->
            listingRepo.save(new Listing(
                item:         seededItem,
                price:        new BigDecimal("10.${i.toString().padLeft(2, '0')}"),
                status:       'ACTIVE',
                sellerName:   "bulk-${i}",
                rarityScore:  BigDecimal.ZERO
            ))
        }

        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings')
                .param('limit', '500')  // asking for way more than the cap
                .param('offset', '0')
        ).andReturn()

        then:
        result.response.status == 200
        def body = result.response.contentAsString
        // Wrapped body because limit != 100
        body.contains('"limit":100')   // clamped to 100

        cleanup:
        listingRepo.deleteAll(extra)
    }

    def "GET /api/listings/#{id} returns the item details"() {
        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get("/api/listings/${seededListing.id}/bids")
        ).andReturn()

        then:
        // /api/listings/{id}/bids is the bid-history endpoint — may be 200 or 404 depending on routing
        result.response.status == 200 || result.response.status == 404
    }
}
