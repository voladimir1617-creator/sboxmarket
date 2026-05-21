package com.sboxmarket

import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT

/**
 * End-to-end HTTP integration spec for the anonymous marketplace journeys
 * a real visitor walks: HTTP → controllers → services → repositories → H2,
 * driven over a real socket on a RANDOM_PORT with {@link TestRestTemplate}
 * (the existing integration specs reach for MockMvc; this one exercises the
 * actual Servlet/filter stack — including the rate-limit filter — to prove
 * the wired application serves a coherent browse-to-detail journey).
 *
 * Scope is deliberately ANONYMOUS-only: every endpoint touched here is one
 * a signed-out browser can reach. Authenticated flows (buy, sell, wallet)
 * are covered by BuyFlowIntegrationSpec and intentionally not retried here
 * — without a real Steam session they can only ever 401.
 *
 * Isolation: like ListingsHttpSpec / BuyFlowIntegrationSpec, every test
 * seeds rows with a unique nanoTime suffix and asserts only against those
 * tokens, so this spec coexists with the rest of the suite on the shared
 * Spring context with no cross-test bleed.
 *
 * Rate-limit tolerance: a burst of reads through the real filter chain can
 * trip the per-IP read bucket, so — exactly as the existing HTTP specs do
 * for search-bucket calls — every assertion treats HTTP 429 as an
 * acceptable, non-failing outcome and only makes body claims on a 200.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@ActiveProfiles("test")
class MarketplaceJourneyIntegrationSpec extends Specification {

    @Autowired ApplicationContext ctx
    @LocalServerPort int port

    TestRestTemplate  rest
    ItemRepository    itemRepo
    ListingRepository listingRepo

    // The journey fixture: one catalogue item with one active listing,
    // both carrying a unique token so every assertion is unambiguous.
    String  token
    Item    item
    Listing listing

    def setup() {
        // Resolve beans from the context — rock-solid under Groovy field
        // injection, matching BuyFlowIntegrationSpec's belt-and-braces.
        // TestRestTemplate is a registered bean under the RANDOM_PORT web
        // environment (see ItemControllerSpec), so it is resolved the same
        // way as the repositories rather than hand-constructed.
        rest        = ctx.getBean(TestRestTemplate)
        itemRepo    = ctx.getBean(ItemRepository)
        listingRepo = ctx.getBean(ListingRepository)

        def uniq = String.valueOf(System.nanoTime())
        token = "JourneyItem-${uniq}"

        item = itemRepo.save(new Item(
            name:         token,
            category:     'Hats',
            rarity:       'Limited',
            imageUrl:     'https://example.com/journey.png',
            iconEmoji:    '🎩',
            accentColor:  '#1a0a3a',
            supply:       5000,
            totalSold:    0,
            trendPercent: 0,
            lowestPrice:  new BigDecimal('64.00'),
            steamPrice:   new BigDecimal('80.00')
        ))
        listing = listingRepo.save(new Listing(
            item:        item,
            price:       new BigDecimal('64.00'),
            sellerName:  "JourneySeller-${uniq}",
            sellerAvatar:'JS',
            status:      'ACTIVE',
            rarityScore: new BigDecimal('0.5')
        ))
    }

    /** Absolute URL for a path on the random test port. */
    private String url(String path) {
        "http://localhost:${port}${path}"
    }

    /** True when a response is a clean 200; 429 (rate-limited read bucket)
     *  is tolerated as a non-failure exactly as the existing HTTP specs do. */
    private static boolean okOr429(ResponseEntity<?> r) {
        r.statusCode == HttpStatus.OK || r.statusCode.value() == 429
    }

    // ── Journey 1: browse → item → its listings → price history →
    //               recent sales → similar items ─────────────────────

    def "anonymous discovery journey — grid → item detail → sub-resources all serve coherently"() {
        when: "the visitor lands on the marketplace grid"
        def grid = rest.getForEntity(url('/api/listings'), String)

        then: "the grid is public and either serves the catalogue or is rate-limited"
        okOr429(grid)
        if (grid.statusCode == HttpStatus.OK) {
            // Default params → a bare JSON array. The shared @SpringBootTest
            // context holds many listings (catalogue seed + every other
            // spec's rows), so a specific seeded row is NOT guaranteed on
            // the default page — item-level coherence is asserted via the
            // by-id lookups below, which are deterministic.
            assert grid.body.startsWith('[')
        }

        when: "the visitor opens the seeded item by id"
        def detail = rest.getForEntity(url("/api/items/${item.id}"), String)

        then: "the item detail resolves with the seeded item, not a notFound sentinel"
        okOr429(detail)
        if (detail.statusCode == HttpStatus.OK) {
            assert detail.body.contains(token)
            assert !detail.body.contains('"notFound":true')
        }

        when: "the visitor views the listings panel for that item"
        def itemListings = rest.getForEntity(url("/api/listings/item/${item.id}"), String)

        then: "the panel is a JSON array carrying the seeded active listing + seller decoration"
        okOr429(itemListings)
        if (itemListings.statusCode == HttpStatus.OK) {
            assert itemListings.body.startsWith('[')
            assert itemListings.body.contains('"sellerRating"')
            assert itemListings.body.contains('"sellerReviewCount"')
        }

        when: "the visitor opens the price-history chart"
        def history = rest.getForEntity(url("/api/items/${item.id}/history"), String)

        then: "price history is a public JSON array (empty when no samples seeded)"
        okOr429(history)
        if (history.statusCode == HttpStatus.OK) {
            assert history.body.startsWith('[')
        }

        when: "the visitor reads the recent-sales strip for the item"
        def recentSales = rest.getForEntity(url("/api/items/${item.id}/recent-sales"), String)

        then: "recent sales is a public JSON array — nothing sold yet, so empty"
        okOr429(recentSales)
        if (recentSales.statusCode == HttpStatus.OK) {
            assert recentSales.body.startsWith('[')
        }

        when: "the visitor reads the trade-velocity aggregate for the item"
        def velocity = rest.getForEntity(url("/api/items/${item.id}/velocity"), String)

        then: "velocity is a public JSON object with the 24h/7d/30d sold buckets"
        okOr429(velocity)
        if (velocity.statusCode == HttpStatus.OK) {
            assert velocity.body.startsWith('{')
            assert velocity.body.contains('soldLast24h')
            assert velocity.body.contains('soldLast7d')
            assert velocity.body.contains('soldLast30d')
        }

        when: "the visitor scrolls to the similar-items rail"
        def similar = rest.getForEntity(url("/api/items/${item.id}/similar"), String)

        then: "similar items is a public JSON array that never echoes the base item itself"
        okOr429(similar)
        if (similar.statusCode == HttpStatus.OK) {
            assert similar.body.startsWith('[')
            // findSimilar excludes the base id — the seeded item must not
            // appear in its own similar feed.
            assert !similar.body.contains(token)
        }
    }

    // ── Journey 2: the sort / filter matrix as one continuous browse ──

    def "anonymous sort + filter matrix journey — every whitelisted knob keeps the grid healthy"() {
        given: "the full set of sort ids the controller whitelists"
        def sortValues = ['price_asc', 'price_desc', 'newest', 'rarity',
                          'discount', 'ending_soon', 'popularity', 'views']

        expect: "each whitelisted sort serves a 200 (or is rate-limited), never a 500"
        sortValues.every { String sort ->
            def r = rest.getForEntity(url("/api/listings?sort=${sort}"), String)
            okOr429(r)
        }

        and: "the category facet narrows to the seeded item's own category"
        def hats = rest.getForEntity(url('/api/listings?category=Hats'), String)
        okOr429(hats)
        if (hats.statusCode == HttpStatus.OK) {
            assert hats.body.contains(token)
        }

        and: "a non-matching category facet drops the seeded Hats row"
        def boots = rest.getForEntity(url('/api/listings?category=Boots'), String)
        okOr429(boots)
        if (boots.statusCode == HttpStatus.OK) {
            assert !boots.body.contains(token)
        }

        and: "the rarity facet matches the seeded Limited item"
        def limited = rest.getForEntity(url('/api/listings?rarity=Limited'), String)
        okOr429(limited)
        if (limited.statusCode == HttpStatus.OK) {
            assert limited.body.contains(token)
        }

        and: "a price band that brackets the \$64 listing keeps it visible"
        def inBand = rest.getForEntity(url('/api/listings?minPrice=50&maxPrice=100'), String)
        okOr429(inBand)
        if (inBand.statusCode == HttpStatus.OK) {
            assert inBand.body.contains(token)
        }

        and: "a price band entirely above the \$64 listing excludes it"
        def aboveBand = rest.getForEntity(url('/api/listings?minPrice=9000&maxPrice=9999'), String)
        okOr429(aboveBand)
        if (aboveBand.statusCode == HttpStatus.OK) {
            assert !aboveBand.body.contains(token)
        }

        and: "paginated params flip the response into the wrapped {items,total,limit,offset} shape"
        def paged = rest.getForEntity(url('/api/listings?limit=5&offset=0'), String)
        okOr429(paged)
        if (paged.statusCode == HttpStatus.OK) {
            assert paged.body.contains('"items"')
            assert paged.body.contains('"total"')
            assert paged.body.contains('"limit":5')
        }

        and: "a SQL-probe-shaped minPrice is rejected as 400 without leaking the value"
        // /api/listings with no search/q param is not in the rate-limited
        // read bucket, so this must be a hard 400 from the price-regex
        // guard — never a 200/500 — and must not echo the injected
        // fragment back to the caller.
        def malformed = rest.getForEntity(url("/api/listings?minPrice=1';SELECT 1"), String)
        malformed.statusCode == HttpStatus.BAD_REQUEST
        !(malformed.body?.contains('SELECT'))
    }

    // ── Journey 3: the public trade-protection quote endpoint ─────────

    def "anonymous trade-protection quote journey — fee is quoted before any commitment"() {
        when: "a signed-out checkout UI asks for the protection fee on the \$64 item"
        def quote = rest.getForEntity(url('/api/trade-protection/quote?price=64.00'), String)

        then: "the quote is public, 200, and carries the full fee breakdown contract"
        okOr429(quote)
        if (quote.statusCode == HttpStatus.OK) {
            assert quote.body.startsWith('{')
            assert quote.body.contains('"fee"')
            // 2% headline rate + the coverage echo the checkout modal renders.
            assert quote.body.contains('"ratePercent":2')
            assert quote.body.contains('"minFee"')
            assert quote.body.contains('"coverageAmount"')
        }

        when: "a cheap item quote exercises the \$0.25 minimum-fee floor"
        def floored = rest.getForEntity(url('/api/trade-protection/quote?price=1.00'), String)

        then: "the floor still resolves to a healthy 200 quote object"
        okOr429(floored)
        if (floored.statusCode == HttpStatus.OK) {
            assert floored.body.contains('"fee"')
            assert floored.body.contains('"minFee"')
        }

        when: "a malformed price is supplied"
        def bad = rest.getForEntity(url('/api/trade-protection/quote?price=not-a-number'), String)

        then: "the endpoint rejects it as a typed 400 INVALID_PRICE, never a 500"
        bad.statusCode == HttpStatus.BAD_REQUEST
        bad.body?.contains('INVALID_PRICE')
    }

    // ── Journey 4: a dead-link / not-found journey ────────────────────

    def "anonymous not-found journey — dead links degrade gracefully without phantom 404s"() {
        given: "an id far beyond anything the seed data could occupy"
        long deadId = System.nanoTime()

        when: "a dead /api/items/{id} link is opened (e.g. a stale share URL)"
        def deadItem = rest.getForEntity(url("/api/items/${deadId}"), String)

        then: "it returns 200 with the notFound sentinel — Chrome logs no phantom 404"
        okOr429(deadItem)
        if (deadItem.statusCode == HttpStatus.OK) {
            assert deadItem.body.contains('"notFound":true')
        }

        when: "the item modal's sub-resources fire against the same dead id"
        def deadHistory = rest.getForEntity(url("/api/items/${deadId}/history"), String)
        def deadSales   = rest.getForEntity(url("/api/items/${deadId}/recent-sales"), String)
        def deadSimilar = rest.getForEntity(url("/api/items/${deadId}/similar"), String)

        then: "each sub-resource degrades to an empty 200 array, never a 404"
        okOr429(deadHistory)
        okOr429(deadSales)
        okOr429(deadSimilar)
        if (deadHistory.statusCode == HttpStatus.OK) assert deadHistory.body.startsWith('[')
        if (deadSales.statusCode   == HttpStatus.OK) assert deadSales.body.startsWith('[')
        if (deadSimilar.statusCode == HttpStatus.OK) assert deadSimilar.body.startsWith('[')

        when: "a genuinely unmapped /api/** path is requested"
        def unknown = rest.getForEntity(url('/api/this-endpoint-does-not-exist'), String)

        then: "the /api catch-all returns a hard 404 with the structured NOT_FOUND body"
        unknown.statusCode == HttpStatus.NOT_FOUND
        unknown.body?.contains('"code":"NOT_FOUND"')

        when: "a single listing is fetched by a non-existent id"
        def deadListing = rest.getForEntity(url("/api/listings/${deadId}"), String)

        then: "an unknown listing id is a real 404 (no sentinel on the single-listing read)"
        deadListing.statusCode == HttpStatus.NOT_FOUND
    }
}
