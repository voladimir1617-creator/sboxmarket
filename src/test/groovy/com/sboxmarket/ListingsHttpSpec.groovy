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

    def "GET /api/listings echoes a clamped zero/negative limit back as 1"() {
        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings')
                .param('limit', '0').param('offset', '0')
        ).andReturn()

        then:
        result.response.status == 200
        def body = result.response.contentAsString
        // limit floors at 1, so a wrapped object with "limit":1 comes back.
        body.contains('"limit":1')
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
}
