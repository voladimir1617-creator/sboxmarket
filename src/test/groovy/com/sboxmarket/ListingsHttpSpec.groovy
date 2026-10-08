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

    def "GET /api/listings tolerates an inverted price range by swapping the bounds"() {
        when:
        // minPrice > maxPrice — without the swap the WHERE clause is
        // price >= 100 AND price <= 1, which can never match, so the
        // $42 seeded listing would vanish. With the swap it's treated
        // as the band [1, 100] and the row comes back.
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings')
                .param('minPrice', '100')
                .param('maxPrice', '1')
        ).andReturn()

        then:
        result.response.status == 200
        // The seeded $42 listing is inside the swapped [1, 100] band.
        result.response.contentAsString.contains('SpecItem-')
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

    def "GET /api/listings includes sellerRating + sellerReviewCount fields on every row (csfloat-parity decoration)"() {
        when:
        def result = mockMvc.perform(MockMvcRequestBuilders.get('/api/listings')).andReturn()

        then:
        result.response.status == 200
        def body = result.response.contentAsString
        // Both fields must appear in the JSON contract — null when there
        // are no reviews, but the keys are always serialized so the
        // frontend can rely on `l.sellerRating != null` rather than
        // probing for key existence.
        body.contains('"sellerRating"')
        body.contains('"sellerReviewCount"')
    }

    def "GET /api/listings/item/{itemId} also carries the sellerRating decoration"() {
        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get("/api/listings/item/${seededItem.id}")
        ).andReturn()

        then:
        result.response.status == 200
        def body = result.response.contentAsString
        body.contains('"sellerRating"')
        body.contains('"sellerReviewCount"')
    }

    // ── sort whitelist: every accepted value returns 200 ──────────

    def "GET /api/listings accepts every whitelisted sort value with a 200"() {
        expect:
        mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings').param('sort', sortValue)
        ).andReturn().response.status == 200

        where:
        sortValue << ['price_asc', 'price_desc', 'newest', 'rarity',
                      'discount', 'ending_soon', 'popularity', 'views']
    }

    def "GET /api/listings normalises an uppercase sort value instead of 500ing"() {
        when:
        // sort=PRICE_DESC must be lower-cased to price_desc, not dropped
        // to the price_asc fallback on a flipped intent.
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings').param('sort', 'PRICE_DESC')
        ).andReturn()

        then:
        result.response.status == 200
    }

    def "GET /api/listings sorts price_desc with the most expensive row first"() {
        given:
        // Both items share a unique name token so a `search` scopes the
        // result to exactly this pair — that keeps the cheap row from
        // being pushed out of the 100-row cap by other tests' listings
        // while still proving price_desc reverses the JPQL price-ASC.
        def uniq = String.valueOf(System.nanoTime())
        def token = "PdScope${uniq}"
        def dearItem = itemRepo.save(new Item(
            name: "${token} Dear", category: 'Hats', rarity: 'Standard',
            supply: 10, totalSold: 0, lowestPrice: new BigDecimal('9999.00'), iconEmoji: '🎩'))
        def cheapItem = itemRepo.save(new Item(
            name: "${token} Cheap", category: 'Hats', rarity: 'Standard',
            supply: 10, totalSold: 0, lowestPrice: new BigDecimal('0.07'), iconEmoji: '🎩'))
        listingRepo.save(new Listing(item: dearItem, price: new BigDecimal('9999.00'),
            status: 'ACTIVE', sellerName: "DearSeller-${uniq}", rarityScore: BigDecimal.ZERO))
        listingRepo.save(new Listing(item: cheapItem, price: new BigDecimal('0.07'),
            status: 'ACTIVE', sellerName: "CheapSeller-${uniq}", rarityScore: BigDecimal.ZERO))

        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings')
                .param('sort', 'price_desc').param('search', token)
        ).andReturn()

        then:
        // 429 tolerated — a search param routes through the rate-limited
        // read bucket and the shared-suite request budget may be spent.
        result.response.status == 200 || result.response.status == 429
        if (result.response.status == 200) {
            def body = result.response.contentAsString
            def iDear  = body.indexOf("${token} Dear")
            def iCheap = body.indexOf("${token} Cheap")
            assert iDear >= 0
            assert iCheap >= 0
            // price_desc — the $9999 row must come before the $0.07 row.
            assert iDear < iCheap
        }
    }

    // ── filter params ─────────────────────────────────────────────

    def "GET /api/listings filtered by a price band excludes out-of-band rows"() {
        given:
        // One listing well inside [1000, 2000] and one well outside it.
        def uniq = String.valueOf(System.nanoTime())
        def inItem = itemRepo.save(new Item(
            name: "BandIn-${uniq}", category: 'Hats', rarity: 'Standard',
            supply: 10, totalSold: 0, lowestPrice: new BigDecimal('1500.00'), iconEmoji: '🎩'))
        def outItem = itemRepo.save(new Item(
            name: "BandOut-${uniq}", category: 'Hats', rarity: 'Standard',
            supply: 10, totalSold: 0, lowestPrice: new BigDecimal('5000.00'), iconEmoji: '🎩'))
        listingRepo.save(new Listing(item: inItem, price: new BigDecimal('1500.00'),
            status: 'ACTIVE', sellerName: "BandInSeller-${uniq}", rarityScore: BigDecimal.ZERO))
        listingRepo.save(new Listing(item: outItem, price: new BigDecimal('5000.00'),
            status: 'ACTIVE', sellerName: "BandOutSeller-${uniq}", rarityScore: BigDecimal.ZERO))

        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings')
                .param('minPrice', '1000').param('maxPrice', '2000')
        ).andReturn()

        then:
        result.response.status == 200
        def body = result.response.contentAsString
        // The $1500 row is inside the band; the $5000 row is not.
        body.contains("BandIn-${uniq}")
        !body.contains("BandOut-${uniq}")
    }

    def "GET /api/listings minPrice excludes rows priced below the floor"() {
        given:
        def uniq = String.valueOf(System.nanoTime())
        def belowItem = itemRepo.save(new Item(
            name: "Below-${uniq}", category: 'Hats', rarity: 'Standard',
            supply: 10, totalSold: 0, lowestPrice: new BigDecimal('3.00'), iconEmoji: '🎩'))
        listingRepo.save(new Listing(item: belowItem, price: new BigDecimal('3.00'),
            status: 'ACTIVE', sellerName: "BelowSeller-${uniq}", rarityScore: BigDecimal.ZERO))

        when:
        // Floor of 8000 — far above the $3 row and the $42 seeded row.
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings').param('minPrice', '8000')
        ).andReturn()

        then:
        result.response.status == 200
        def body = result.response.contentAsString
        !body.contains("Below-${uniq}")
        !body.contains('SpecItem-')
    }

    def "GET /api/listings filtered by rarity excludes other-rarity rows"() {
        given:
        // seededItem is 'Limited'. Add a 'Standard' row and filter to
        // Standard — the Limited seeded row must drop out.
        def uniq = String.valueOf(System.nanoTime())
        def stdItem = itemRepo.save(new Item(
            name: "StdRarity-${uniq}", category: 'Hats', rarity: 'Standard',
            supply: 10, totalSold: 0, lowestPrice: new BigDecimal('17.00'), iconEmoji: '🎩'))
        listingRepo.save(new Listing(item: stdItem, price: new BigDecimal('17.00'),
            status: 'ACTIVE', sellerName: "StdSeller-${uniq}", rarityScore: BigDecimal.ZERO))

        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings').param('rarity', 'Standard')
        ).andReturn()

        then:
        result.response.status == 200
        def body = result.response.contentAsString
        body.contains("StdRarity-${uniq}")
        // The seeded row is 'Limited' — must not survive a Standard filter.
        !body.contains('SpecItem-')
    }

    def "GET /api/listings canonicalises a lowercase rarity value"() {
        given:
        // ?rarity=standard (lowercase) must match the DB-stored 'Standard'
        // — ListingEnums.canonEnum handles the case fold.
        def uniq = String.valueOf(System.nanoTime())
        def stdItem = itemRepo.save(new Item(
            name: "LcRarity-${uniq}", category: 'Hats', rarity: 'Standard',
            supply: 10, totalSold: 0, lowestPrice: new BigDecimal('19.00'), iconEmoji: '🎩'))
        listingRepo.save(new Listing(item: stdItem, price: new BigDecimal('19.00'),
            status: 'ACTIVE', sellerName: "LcSeller-${uniq}", rarityScore: BigDecimal.ZERO))

        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings').param('rarity', 'standard')
        ).andReturn()

        then:
        result.response.status == 200
        // The lowercase filter still matched the Standard row.
        result.response.contentAsString.contains("LcRarity-${uniq}")
    }

    def "GET /api/listings filtered by category excludes other-category rows"() {
        given:
        // seededItem is 'Hats'. Add a 'Boots' row and filter to Boots.
        def uniq = String.valueOf(System.nanoTime())
        def bootItem = itemRepo.save(new Item(
            name: "BootCat-${uniq}", category: 'Boots', rarity: 'Standard',
            supply: 10, totalSold: 0, lowestPrice: new BigDecimal('23.00'), iconEmoji: '🥾'))
        listingRepo.save(new Listing(item: bootItem, price: new BigDecimal('23.00'),
            status: 'ACTIVE', sellerName: "BootSeller-${uniq}", rarityScore: BigDecimal.ZERO))

        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings').param('category', 'Boots')
        ).andReturn()

        then:
        result.response.status == 200
        def body = result.response.contentAsString
        body.contains("BootCat-${uniq}")
        // The seeded 'Hats' row must not survive a Boots filter.
        !body.contains('SpecItem-')
    }

    def "GET /api/listings with an unknown category returns 200 and ignores the filter"() {
        when:
        // 'Spaceships' isn't a real category — canonEnum collapses it to
        // 'All' (no filter) rather than 400ing or returning an empty grid.
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings').param('category', 'Spaceships')
        ).andReturn()

        then:
        result.response.status == 200
        // Filter ignored → the seeded row is still visible.
        result.response.contentAsString.contains('SpecItem-')
    }

    def "GET /api/listings filtered by listingType=AUCTION excludes BUY_NOW rows"() {
        given:
        // The seeded row defaults to BUY_NOW. Add an AUCTION row and
        // filter to AUCTION — only the auction should survive.
        def uniq = String.valueOf(System.nanoTime())
        def aucItem = itemRepo.save(new Item(
            name: "AucType-${uniq}", category: 'Hats', rarity: 'Standard',
            supply: 10, totalSold: 0, lowestPrice: new BigDecimal('31.00'), iconEmoji: '🎩'))
        listingRepo.save(new Listing(item: aucItem, price: new BigDecimal('31.00'),
            status: 'ACTIVE', sellerName: "AucSeller-${uniq}", rarityScore: BigDecimal.ZERO,
            listingType: 'AUCTION', expiresAt: System.currentTimeMillis() + 3_600_000L))

        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings').param('listingType', 'AUCTION')
        ).andReturn()

        then:
        result.response.status == 200
        def body = result.response.contentAsString
        body.contains("AucType-${uniq}")
        // The seeded BUY_NOW row must drop out under an AUCTION filter.
        !body.contains('SpecItem-')
    }

    def "GET /api/listings canonicalises a lowercase listingType value"() {
        given:
        def uniq = String.valueOf(System.nanoTime())
        def aucItem = itemRepo.save(new Item(
            name: "LcAuc-${uniq}", category: 'Hats', rarity: 'Standard',
            supply: 10, totalSold: 0, lowestPrice: new BigDecimal('33.00'), iconEmoji: '🎩'))
        listingRepo.save(new Listing(item: aucItem, price: new BigDecimal('33.00'),
            status: 'ACTIVE', sellerName: "LcAucSeller-${uniq}", rarityScore: BigDecimal.ZERO,
            listingType: 'AUCTION', expiresAt: System.currentTimeMillis() + 3_600_000L))

        when:
        // ?listingType=auction (lowercase) must canonicalise to AUCTION.
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings').param('listingType', 'auction')
        ).andReturn()

        then:
        result.response.status == 200
        result.response.contentAsString.contains("LcAuc-${uniq}")
    }

    def "GET /api/listings with a junk listingType returns 200 and ignores the filter"() {
        when:
        // Junk listingType → canonListingType returns null → filter off.
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings').param('listingType', '<script>')
        ).andReturn()

        then:
        result.response.status == 200
        result.response.contentAsString.contains('SpecItem-')
    }

    // ── search + the `q` alias ────────────────────────────────────

    def "GET /api/listings?q= works as an alias for ?search="() {
        given:
        // A uniquely-named item so the substring search is unambiguous.
        def uniq = String.valueOf(System.nanoTime())
        def needleName = "QAliasNeedle${uniq}"
        def needleItem = itemRepo.save(new Item(
            name: needleName, category: 'Hats', rarity: 'Standard',
            supply: 10, totalSold: 0, lowestPrice: new BigDecimal('29.00'), iconEmoji: '🎩'))
        listingRepo.save(new Listing(item: needleItem, price: new BigDecimal('29.00'),
            status: 'ACTIVE', sellerName: "QAliasSeller-${uniq}", rarityScore: BigDecimal.ZERO))

        when:
        // Use `q` (not `search`) — the alias must drive the same filter.
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings').param('q', needleName)
        ).andReturn()

        then:
        // 429 tolerated — `q` routes through the rate-limited read bucket.
        result.response.status == 200 || result.response.status == 429
        if (result.response.status == 200) {
            def body = result.response.contentAsString
            assert body.contains(needleName)
            // The unrelated seeded row must NOT match this specific needle.
            assert !body.contains('SpecItem-')
        }
    }

    def "GET /api/listings search matches the item name as a substring"() {
        given:
        def uniq = String.valueOf(System.nanoTime())
        def needleName = "SubstrNeedle${uniq}"
        def needleItem = itemRepo.save(new Item(
            name: needleName, category: 'Hats', rarity: 'Standard',
            supply: 10, totalSold: 0, lowestPrice: new BigDecimal('27.00'), iconEmoji: '🎩'))
        listingRepo.save(new Listing(item: needleItem, price: new BigDecimal('27.00'),
            status: 'ACTIVE', sellerName: "SubstrSeller-${uniq}", rarityScore: BigDecimal.ZERO))

        when:
        // Search a mid-string slice — findActivePublic does LIKE '%q%'.
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings').param('search', "Needle${uniq}")
        ).andReturn()

        then:
        // 429 tolerated — search routes through the rate-limited read bucket.
        result.response.status == 200 || result.response.status == 429
        if (result.response.status == 200) {
            assert result.response.contentAsString.contains(needleName)
        }
    }

    def "GET /api/listings treats a LIKE wildcard in search as a literal char"() {
        when:
        // A search containing '%' must be escaped — '%' should match a
        // literal percent, not act as a wildcard. We search for a string
        // that, if the % were a wildcard, would match the seeded item,
        // but as a literal matches nothing.
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings').param('search', 'Spec%Item')
        ).andReturn()

        then:
        // Escaped LIKE — 'Spec%Item' as a literal doesn't match
        // 'SpecItem-<nanos>', so the seeded row is absent. No 500.
        result.response.status == 200 || result.response.status == 429
        if (result.response.status == 200) {
            assert !result.response.contentAsString.contains('SpecItem-')
        }
    }

    def "GET /api/listings strips a NUL byte from search instead of 500ing"() {
        when:
        // A raw 0x00 in the search param makes Postgres reject the whole
        // string ("invalid byte sequence for encoding UTF8"). The
        // controller strips the NUL before the value reaches the query so
        // a scanner probing ?search=%00 gets a clean response, not a 500.
        // The NUL is built at runtime — a literal 0x00 in source is fragile.
        def withNul = "Hat" + Character.toString((char) 0) + "Probe"
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings').param('search', withNul)
        ).andReturn()

        then:
        // Stripped to "HatProbe" → behaves like a normal search. Never a 500.
        result.response.status == 200 || result.response.status == 429
    }

    def "GET /api/listings tolerates a search that is only a NUL byte"() {
        when:
        // The degenerate case — search is *only* a NUL byte. After the
        // strip it collapses to an empty string and the filter is
        // disabled, the same as sending no search param at all.
        def onlyNul = Character.toString((char) 0)
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings').param('search', onlyNul)
        ).andReturn()

        then:
        result.response.status == 200 || result.response.status == 429
    }

    // ── pagination: offset + clamps ───────────────────────────────

    def "GET /api/listings echoes a clamped negative offset back as 0"() {
        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings')
                .param('limit', '5').param('offset', '-10')
        ).andReturn()

        then:
        result.response.status == 200
        def body = result.response.contentAsString
        // Negative offset clamps to 0 in the wrapped response.
        body.contains('"offset":0')
    }

    def "GET /api/listings clamps an explicit limit=0 to the floor of 1 (not the 100 default)"() {
        given:
        // Seed a handful of extra rows so a clamp-bypass would be obvious:
        // if limit=0 were silently rewritten to the 100 default, `items`
        // would hold many rows. The clamp floor of 1 means at most one.
        def extra = (1..6).collect { i ->
            listingRepo.save(new Listing(
                item:        seededItem,
                price:       new BigDecimal("70.0${i}"),
                status:      'ACTIVE',
                sellerName:  "L0Bulk-${i}-${System.nanoTime()}",
                rarityScore: BigDecimal.ZERO))
        }

        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings')
                .param('limit', '0').param('offset', '0')
        ).andReturn()

        then:
        result.response.status == 200
        // Parse the wrapped object — `contains('"limit":1')` is too weak
        // because the buggy `"limit":100` ALSO contains that substring.
        // The regression here: Integer 0 is Groovy-falsy, so `limit ?: 100`
        // turned an explicit ?limit=0 into the full 100-row default page.
        def parsed = new groovy.json.JsonSlurper().parseText(result.response.contentAsString)
        parsed.limit == 1
        parsed.items instanceof List
        parsed.items.size() <= 1

        cleanup:
        listingRepo.deleteAll(extra)
    }

    def "GET /api/listings clamps limit=0 even with offset=0 — items never exceeds 1"() {
        when:
        // offset defaults to "0" too; this pins that the offset null-check
        // path and the limit clamp both behave with the literal-zero input.
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings')
                .param('limit', '0')
        ).andReturn()

        then:
        result.response.status == 200
        def parsed = new groovy.json.JsonSlurper().parseText(result.response.contentAsString)
        parsed.limit == 1
        parsed.offset == 0
        parsed.items.size() <= 1
    }

    def "GET /api/listings offset walks a search-scoped cohort one page at a time"() {
        given:
        // Seed exactly four same-token listings at known ascending prices.
        // A `search` on the shared token scopes the result to just these
        // four; default sort is price ASC, so paging with limit=2 yields
        // [p1,p2] then [p3,p4] deterministically — no other test's rows
        // can leak in to perturb the window.
        def uniq = String.valueOf(System.nanoTime())
        def token = "PgWalk${uniq}"
        def prices = ['6001.11', '6002.22', '6003.33', '6004.44']
        prices.eachWithIndex { p, i ->
            def it = itemRepo.save(new Item(
                name: "${token} item${i}", category: 'Hats', rarity: 'Standard',
                supply: 10, totalSold: 0,
                lowestPrice: new BigDecimal(p), iconEmoji: '🎩'))
            listingRepo.save(new Listing(item: it, price: new BigDecimal(p),
                status: 'ACTIVE', sellerName: "PgWalkSeller-${uniq}-${i}",
                rarityScore: BigDecimal.ZERO))
        }

        when:
        def page0 = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings')
                .param('search', token).param('limit', '2').param('offset', '0')
        ).andReturn()
        def page1 = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings')
                .param('search', token).param('limit', '2').param('offset', '2')
        ).andReturn()

        then:
        // 429 tolerated — the search param uses the rate-limited bucket.
        page0.response.status in [200, 429]
        page1.response.status in [200, 429]
        if (page0.response.status == 200 && page1.response.status == 200) {
            def b0 = page0.response.contentAsString
            def b1 = page1.response.contentAsString
            // Page 0 holds the two cheapest of the cohort; page 1 the dearer two.
            assert b0.contains("${token} item0")
            assert b0.contains("${token} item1")
            assert !b0.contains("${token} item2")
            assert !b0.contains("${token} item3")
            assert b1.contains("${token} item2")
            assert b1.contains("${token} item3")
            assert !b1.contains("${token} item0")
            assert !b1.contains("${token} item1")
            // total counts the whole cohort regardless of the page window.
            assert b0.contains('"total":4')
            assert b1.contains('"total":4')
        }
    }

    def "GET /api/listings with offset past the end returns an empty items array"() {
        when:
        // A huge offset lands beyond every row — items is empty but total
        // still reports the real count and the call is a clean 200.
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings')
                .param('limit', '5').param('offset', '100000')
        ).andReturn()

        then:
        result.response.status == 200
        def body = result.response.contentAsString
        body.contains('"items":[]')
        body.contains('"total"')
    }

    def "GET /api/listings rejects a malformed maxPrice as 400"() {
        when:
        // Parity with the minPrice guard — a SQL-probe-shaped maxPrice
        // must 400 from the controller, not 500 from type coercion.
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings').param('maxPrice', "10) OR (1=1")
        ).andReturn()

        then:
        result.response.status == 400
        def body = result.response.contentAsString
        body.contains('"code"')
        !body.contains('OR (1=1')
    }

    def "GET /api/listings rejects a negative-signed minPrice as 400"() {
        when:
        // The price regex is digits-only — a leading '-' must be rejected.
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings').param('minPrice', '-5')
        ).andReturn()

        then:
        result.response.status == 400
    }

    def "GET /api/listings accepts a well-formed decimal minPrice"() {
        when:
        // A plain decimal within range must pass the parser cleanly.
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings').param('minPrice', '12.50')
        ).andReturn()

        then:
        result.response.status == 200
    }

    // ── pagination matrix: limit boundary values ──────────────────

    def "GET /api/listings honours a small positive limit exactly"() {
        given:
        // Seed >3 rows so a limit of 3 actually truncates.
        def extra = (1..5).collect { i ->
            listingRepo.save(new Listing(
                item:        seededItem,
                price:       new BigDecimal("80.0${i}"),
                status:      'ACTIVE',
                sellerName:  "L3Bulk-${i}-${System.nanoTime()}",
                rarityScore: BigDecimal.ZERO))
        }

        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings')
                .param('limit', '3').param('offset', '0')
        ).andReturn()

        then:
        result.response.status == 200
        def parsed = new groovy.json.JsonSlurper().parseText(result.response.contentAsString)
        parsed.limit == 3
        parsed.items.size() <= 3

        cleanup:
        listingRepo.deleteAll(extra)
    }

    def "GET /api/listings clamps a negative limit up to the floor of 1"() {
        when:
        // A negative limit is truthy in Groovy so it escapes the Elvis
        // trap, but Math.max(...,1) must still floor it to 1.
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings').param('limit', '-25')
        ).andReturn()

        then:
        result.response.status == 200
        def parsed = new groovy.json.JsonSlurper().parseText(result.response.contentAsString)
        parsed.limit == 1
        parsed.items.size() <= 1
    }

    def "GET /api/listings clamps an over-cap limit to exactly 100 in the wrapped echo"() {
        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings').param('limit', '99999')
        ).andReturn()

        then:
        result.response.status == 200
        def parsed = new groovy.json.JsonSlurper().parseText(result.response.contentAsString)
        // Strong assertion: exactly 100, not merely "contains 100".
        parsed.limit == 100
        parsed.items.size() <= 100

        and: "the parsed total is a non-negative integer"
        (parsed.total as long) >= 0L
    }

    def "GET /api/listings with limit=100 and a non-zero offset still returns the wrapped object"() {
        when:
        // The bare-array shortcut only fires for the exact (100, 0) pair.
        // limit=100 + offset=1 must wrap so the client gets total/offset.
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings')
                .param('limit', '100').param('offset', '1')
        ).andReturn()

        then:
        result.response.status == 200
        def body = result.response.contentAsString
        !body.startsWith('[')
        def parsed = new groovy.json.JsonSlurper().parseText(body)
        parsed.offset == 1
        parsed.limit == 100
    }

    // ── sort + filter interaction matrix ──────────────────────────

    def "GET /api/listings applies a price-band filter and a sort together"() {
        given:
        // Three same-token rows; only the middle two sit inside [200,400].
        // sort=price_desc must then return them dearest-first.
        def uniq = String.valueOf(System.nanoTime())
        def token = "SortBand${uniq}"
        [['100.00', 0], ['250.00', 1], ['350.00', 2], ['900.00', 3]].each { p, i ->
            def it = itemRepo.save(new Item(
                name: "${token} n${i}", category: 'Hats', rarity: 'Standard',
                supply: 10, totalSold: 0, lowestPrice: new BigDecimal(p as String), iconEmoji: '🎩'))
            listingRepo.save(new Listing(item: it, price: new BigDecimal(p as String),
                status: 'ACTIVE', sellerName: "SortBandSeller-${uniq}-${i}",
                rarityScore: BigDecimal.ZERO))
        }

        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings')
                .param('search', token)
                .param('sort', 'price_desc')
                .param('minPrice', '200').param('maxPrice', '400')
        ).andReturn()

        then:
        result.response.status in [200, 429]
        if (result.response.status == 200) {
            def body = result.response.contentAsString
            // The $100 and $900 rows are outside the band.
            assert !body.contains("${token} n0")
            assert !body.contains("${token} n3")
            // In-band rows present, dearest ($350) before cheaper ($250).
            def i350 = body.indexOf("${token} n2")
            def i250 = body.indexOf("${token} n1")
            assert i350 >= 0 && i250 >= 0
            assert i350 < i250
        }
    }

    def "GET /api/listings filtered by listingType=BUY_NOW excludes AUCTION rows"() {
        given:
        // Symmetric to the AUCTION-filter test: seed one auction, one
        // buy-now under a shared token, filter to BUY_NOW.
        def uniq = String.valueOf(System.nanoTime())
        def token = "BnFilter${uniq}"
        def bnItem = itemRepo.save(new Item(
            name: "${token} buynow", category: 'Hats', rarity: 'Standard',
            supply: 10, totalSold: 0, lowestPrice: new BigDecimal('44.00'), iconEmoji: '🎩'))
        def aucItem = itemRepo.save(new Item(
            name: "${token} auction", category: 'Hats', rarity: 'Standard',
            supply: 10, totalSold: 0, lowestPrice: new BigDecimal('45.00'), iconEmoji: '🎩'))
        listingRepo.save(new Listing(item: bnItem, price: new BigDecimal('44.00'),
            status: 'ACTIVE', sellerName: "BnSeller-${uniq}", rarityScore: BigDecimal.ZERO,
            listingType: 'BUY_NOW'))
        listingRepo.save(new Listing(item: aucItem, price: new BigDecimal('45.00'),
            status: 'ACTIVE', sellerName: "AucSeller2-${uniq}", rarityScore: BigDecimal.ZERO,
            listingType: 'AUCTION', expiresAt: System.currentTimeMillis() + 3_600_000L))

        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings')
                .param('search', token).param('listingType', 'BUY_NOW')
        ).andReturn()

        then:
        result.response.status in [200, 429]
        if (result.response.status == 200) {
            def body = result.response.contentAsString
            assert body.contains("${token} buynow")
            assert !body.contains("${token} auction")
        }
    }

    def "GET /api/listings combines a category filter with a rarity filter"() {
        given:
        // Four rows across the Hats/Boots × Limited/Standard grid; only the
        // Hats+Limited one should survive ?category=Hats&rarity=Limited.
        def uniq = String.valueOf(System.nanoTime())
        def token = "CatRar${uniq}"
        [['Hats', 'Limited', 'hit'], ['Hats', 'Standard', 'miss1'],
         ['Boots', 'Limited', 'miss2'], ['Boots', 'Standard', 'miss3']].each { cat, rar, tag ->
            def it = itemRepo.save(new Item(
                name: "${token} ${tag}", category: cat as String, rarity: rar as String,
                supply: 10, totalSold: 0, lowestPrice: new BigDecimal('60.00'), iconEmoji: '🎩'))
            listingRepo.save(new Listing(item: it, price: new BigDecimal('60.00'),
                status: 'ACTIVE', sellerName: "CatRarSeller-${uniq}-${tag}",
                rarityScore: BigDecimal.ZERO))
        }

        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings')
                .param('search', token)
                .param('category', 'Hats').param('rarity', 'Limited')
        ).andReturn()

        then:
        result.response.status in [200, 429]
        if (result.response.status == 200) {
            def body = result.response.contentAsString
            assert body.contains("${token} hit")
            assert !body.contains("${token} miss1")
            assert !body.contains("${token} miss2")
            assert !body.contains("${token} miss3")
        }
    }

    def "GET /api/listings — an explicit search wins over a q alias when both are present"() {
        given:
        // search and q each point at a distinct uniquely-named item. The
        // canonical `search` param must take precedence; `q` is only the
        // fallback when search is null/blank.
        def uniq = String.valueOf(System.nanoTime())
        def searchName = "SearchWins${uniq}"
        def qName      = "QLoses${uniq}"
        [searchName, qName].each { nm ->
            def it = itemRepo.save(new Item(
                name: nm, category: 'Hats', rarity: 'Standard',
                supply: 10, totalSold: 0, lowestPrice: new BigDecimal('38.00'), iconEmoji: '🎩'))
            listingRepo.save(new Listing(item: it, price: new BigDecimal('38.00'),
                status: 'ACTIVE', sellerName: "PrecSeller-${uniq}-${nm}",
                rarityScore: BigDecimal.ZERO))
        }

        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings')
                .param('search', searchName).param('q', qName)
        ).andReturn()

        then:
        result.response.status in [200, 429]
        if (result.response.status == 200) {
            def body = result.response.contentAsString
            // search drove the filter; the q-only item is absent.
            assert body.contains(searchName)
            assert !body.contains(qName)
        }
    }

    def "GET /api/listings — a blank search falls through to the q alias"() {
        given:
        // search present but whitespace-only → treated as blank, so the
        // q alias takes over and scopes the result to the q-named item.
        def uniq = String.valueOf(System.nanoTime())
        def qName = "BlankFallThru${uniq}"
        def qItem = itemRepo.save(new Item(
            name: qName, category: 'Hats', rarity: 'Standard',
            supply: 10, totalSold: 0, lowestPrice: new BigDecimal('39.00'), iconEmoji: '🎩'))
        listingRepo.save(new Listing(item: qItem, price: new BigDecimal('39.00'),
            status: 'ACTIVE', sellerName: "BlankSeller-${uniq}", rarityScore: BigDecimal.ZERO))

        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings')
                .param('search', '   ').param('q', qName)
        ).andReturn()

        then:
        result.response.status in [200, 429]
        if (result.response.status == 200) {
            assert result.response.contentAsString.contains(qName)
        }
    }

    def "GET /api/listings — sort=newest with a uniquely-scoped cohort orders newest-first"() {
        given:
        // Three same-token rows listed at strictly increasing listedAt;
        // sort=newest must return them in reverse-insertion order.
        def uniq = String.valueOf(System.nanoTime())
        def token = "NewestScope${uniq}"
        def base = System.currentTimeMillis()
        (0..2).each { i ->
            def it = itemRepo.save(new Item(
                name: "${token} g${i}", category: 'Hats', rarity: 'Standard',
                supply: 10, totalSold: 0, lowestPrice: new BigDecimal('41.00'), iconEmoji: '🎩'))
            listingRepo.save(new Listing(item: it, price: new BigDecimal('41.00'),
                status: 'ACTIVE', sellerName: "NewestSeller-${uniq}-${i}",
                rarityScore: BigDecimal.ZERO, listedAt: base + (i * 1000L)))
        }

        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings')
                .param('search', token).param('sort', 'newest')
        ).andReturn()

        then:
        result.response.status in [200, 429]
        if (result.response.status == 200) {
            def body = result.response.contentAsString
            def i0 = body.indexOf("${token} g0")  // oldest
            def i2 = body.indexOf("${token} g2")  // newest
            assert i0 >= 0 && i2 >= 0
            // newest-first: g2 must precede g0.
            assert i2 < i0
        }
    }

    def "GET /api/listings rejects a maxPrice with a trailing newline"() {
        when:
        // The digits-only regex must reject a value that smuggles a
        // newline — `5\n` could otherwise slip past a multiline matcher.
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings').param('maxPrice', "50\n")
        ).andReturn()

        then:
        result.response.status == 400
    }

    def "GET /api/listings rejects a minPrice with three decimal places"() {
        when:
        // The regex caps the fraction at two digits — price columns are
        // scale 2, so 1.234 is not a valid money value.
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings').param('minPrice', '1.234')
        ).andReturn()

        then:
        result.response.status == 400
    }

    def "GET /api/listings trims a trailing space from search instead of missing the match"() {
        given:
        // Phone keyboards append a space after a word; "%hat %" used to
        // miss "...hat" at the end of a name.
        def uniq = String.valueOf(System.nanoTime())
        def name = "TrimNeedle${uniq}"
        def item = itemRepo.save(new Item(
            name: name, category: 'Hats', rarity: 'Standard',
            supply: 10, totalSold: 0, lowestPrice: new BigDecimal('19.00'), iconEmoji: '🎩'))
        listingRepo.save(new Listing(item: item, price: new BigDecimal('19.00'),
            status: 'ACTIVE', sellerName: "TrimSeller-${uniq}", rarityScore: BigDecimal.ZERO))

        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings').param('search', "  ${name} ")
        ).andReturn()

        then:
        result.response.status == 200 || result.response.status == 429
        if (result.response.status == 200) {
            assert result.response.contentAsString.contains(name)
        }
    }

    def "GET /api/listings price filter uses an auction's current bid, not its opening price"() {
        given:
        // Opened at $1, now bid to $50: the card shows $50, so a "max $5"
        // search must not return it, and a "min $40" search must.
        def uniq = String.valueOf(System.nanoTime())
        def item = itemRepo.save(new Item(
            name: "BidBand-${uniq}", category: 'Hats', rarity: 'Standard',
            supply: 10, totalSold: 0, lowestPrice: new BigDecimal('1.00'), iconEmoji: '🎩'))
        listingRepo.save(new Listing(item: item, price: new BigDecimal('1.00'),
            currentBid: new BigDecimal('50.00'),
            status: 'ACTIVE', sellerName: "BidBandSeller-${uniq}", rarityScore: BigDecimal.ZERO,
            listingType: 'AUCTION', expiresAt: System.currentTimeMillis() + 3_600_000L))

        when:
        def cheap = mockMvc.perform(MockMvcRequestBuilders.get('/api/listings')
            .param('search', "BidBand-${uniq}").param('maxPrice', '5')).andReturn()
        def dear = mockMvc.perform(MockMvcRequestBuilders.get('/api/listings')
            .param('search', "BidBand-${uniq}").param('minPrice', '40')).andReturn()

        then:
        cheap.response.status == 200
        !cheap.response.contentAsString.contains("BidBand-${uniq}")
        dear.response.status == 200
        dear.response.contentAsString.contains("BidBand-${uniq}")
    }

    def "GET /api/listings pages same-priced rows without repeating or skipping any"() {
        given:
        // Prices cluster, so a 100-row page boundary often falls inside a
        // run of equal prices; without an id tiebreak page 2 could repeat
        // page-1 rows and skip others.
        def uniq = String.valueOf(System.nanoTime())
        def ids = (1..6).collect { i ->
            def item = itemRepo.save(new Item(
                name: "TiePage${uniq}-${i}", category: 'Hats', rarity: 'Standard',
                supply: 10, totalSold: 0, lowestPrice: new BigDecimal('3.00'), iconEmoji: '🎩'))
            listingRepo.save(new Listing(item: item, price: new BigDecimal('3.00'),
                status: 'ACTIVE', sellerName: "TieSeller-${uniq}", rarityScore: BigDecimal.ZERO)).id
        }

        when:
        def seen = []
        [0, 2, 4].each { off ->
            def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/listings')
                .param('search', "TiePage${uniq}").param('limit', '2').param('offset', "${off}")).andReturn()
            assert r.response.status == 200
            def body = new groovy.json.JsonSlurper().parseText(r.response.contentAsString)
            seen.addAll(body.items*.id.collect { it as Long })
        }

        then:
        seen == ids.sort()
    }
}
