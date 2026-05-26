package com.sboxmarket

import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.PriceHistory
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.PriceHistoryRepository
import com.sboxmarket.service.ItemService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification
import spock.lang.Subject

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ItemControllerSpec extends Specification {

    @LocalServerPort int port
    @Autowired TestRestTemplate rest
    @Autowired ItemRepository itemRepository
    @Autowired ListingRepository listingRepository
    @Autowired PriceHistoryRepository priceHistoryRepository
    @Subject @Autowired ItemService itemService

    /** Seeds a fresh catalogue item with a unique name so the row never
     *  collides with the SeedService catalogue or another spec sharing
     *  the H2 context (additive-isolation pattern from StallAnalyticsHttpSpec). */
    private Item newItem(Map overrides = [:]) {
        def uniq = String.valueOf(System.nanoTime())
        itemRepository.save(new Item([
            name:         "Detail Item " + uniq,
            category:     "Hats",
            rarity:       "Limited",
            imageUrl:     "https://example.com/i.png",
            iconEmoji:    "🎩",
            accentColor:  "#1a0a3a",
            supply:       500,
            totalSold:    0,
            trendPercent: 0,
            viewCount:    0L,
            lowestPrice:  new BigDecimal("9.99")
        ] + overrides))
    }

    /** Seeds a SOLD listing for an item — drives /recent-sales + /velocity. */
    private Listing soldListing(Item item, BigDecimal price, long soldAt) {
        listingRepository.save(new Listing(
            item:        item,
            price:       price,
            sellerName:  "SeedSeller",
            sellerAvatar:"SS",
            status:      "SOLD",
            soldAt:      soldAt,
            listingType: "BUY_NOW",
            rarityScore: new BigDecimal("0.5")
        ))
    }

    def "GET /api/items returns 200 with a list"() {
        when:
        def response = rest.getForEntity("http://localhost:$port/api/items", List)

        then:
        response.statusCode == HttpStatus.OK
        response.body instanceof List
    }

    def "GET /api/items?category=Hats returns only hat items"() {
        when:
        def response = rest.getForEntity("http://localhost:$port/api/items?category=Hats", List)

        then:
        response.statusCode == HttpStatus.OK
        response.body.every { it.category == "Hats" }
    }

    def "GET /api/items?rarity=Limited returns only limited items"() {
        when:
        def response = rest.getForEntity("http://localhost:$port/api/items?rarity=Limited", List)

        then:
        response.statusCode == HttpStatus.OK
        response.body.every { it.rarity == "Limited" }
    }

    def "GET /api/items/{id} returns 200 with a notFound sentinel for a missing item"() {
        when:
        def response = rest.getForEntity("http://localhost:$port/api/items/99999", Map)

        then:
        // Contract change: missing item reads now return 200 with
        // `{notFound: true}` instead of 404. Reason: Chrome auto-logs every
        // fetch 404 to the browser console regardless of JS handling, which
        // made every /item/{deadId} landing read as a phantom bug. The SPA's
        // `fetchItem` translates the sentinel back to null.
        response.statusCode == HttpStatus.OK
        response.body?.notFound == true
    }

    def "GET /api/items/batch returns the items for the given ids in one response"() {
        given: "two real catalogue ids"
        def all = itemRepository.findAll().toList()
        // The seeded test catalogue always has at least a couple of items.
        def ids = all.take(2).collect { it.id }

        when:
        def response = rest.getForEntity(
            "http://localhost:$port/api/items/batch?ids=${ids.join(',')}", List)

        then:
        response.statusCode == HttpStatus.OK
        response.body instanceof List
        response.body.size() == ids.size()
        response.body.collect { it.id }.toSet() == ids.toSet()
    }

    def "GET /api/items/batch omits ids that do not resolve instead of a notFound sentinel"() {
        given:
        def real = itemRepository.findAll().toList().first().id

        when: "one real id mixed with a missing id"
        def response = rest.getForEntity(
            "http://localhost:$port/api/items/batch?ids=${real},99999999", List)

        then: "only the real item comes back — the missing id is silently dropped"
        response.statusCode == HttpStatus.OK
        response.body.size() == 1
        response.body[0].id == real
    }

    def "GET /api/items/batch returns an empty list for blank or missing ids"() {
        expect:
        rest.getForEntity("http://localhost:$port/api/items/batch", List).body == []
        rest.getForEntity("http://localhost:$port/api/items/batch?ids=", List).body == []
        rest.getForEntity("http://localhost:$port/api/items/batch?ids=notanumber", List).body == []
    }

    def "GET /api/items/batch caps the number of ids at 50"() {
        given: "more than 50 ids requested"
        def ids = (1..120).collect { it as String }

        when:
        def response = rest.getForEntity(
            "http://localhost:$port/api/items/batch?ids=${ids.join(',')}", List)

        then: "request still succeeds and never returns more than the cap"
        response.statusCode == HttpStatus.OK
        response.body.size() <= 50
    }

    def "GET /api/items/{id}/similar returns an empty 200 for a missing item (no phantom 404)"() {
        when: "the item id does not resolve"
        def response = rest.getForEntity(
            "http://localhost:$port/api/items/99999999/similar", List)

        then: "consistent with /velocity, /history, /recent-sales and the {id} sentinel"
        // Previously routed through ItemService.getById() which threw
        // NotFoundException → 404; the ItemModal fires /similar on open so a
        // dead-link landing logged a phantom console error. Now degrades to
        // an empty 200 like every sibling sub-resource.
        response.statusCode == HttpStatus.OK
        response.body == []
    }

    def "GET /api/items/{id}/similar returns 200 with a list for a real item"() {
        given:
        def real = itemRepository.findAll().toList().first().id

        when:
        def response = rest.getForEntity(
            "http://localhost:$port/api/items/${real}/similar", List)

        then:
        response.statusCode == HttpStatus.OK
        response.body instanceof List
    }

    def "GET /api/items/{id}/velocity returns the trade-velocity envelope"() {
        given:
        def real = itemRepository.findAll().toList().first().id

        when:
        def response = rest.getForEntity(
            "http://localhost:$port/api/items/${real}/velocity", Map)

        then:
        response.statusCode == HttpStatus.OK
        response.body.containsKey("soldLast24h")
        response.body.containsKey("soldLast7d")
        response.body.containsKey("soldLast30d")
        response.body.containsKey("volumeLast30d")
    }

    def "GET /api/items/stats returns market stats structure"() {
        when:
        def response = rest.getForEntity("http://localhost:$port/api/items/stats", Map)

        then:
        response.statusCode == HttpStatus.OK
        response.body.containsKey("totalItems")
        response.body.containsKey("floorPrice")
        response.body.containsKey("categories")
    }

    def "GET /api/items/stats exposes lastSyncedAt so the footer can render Catalog-updated-X-ago (batch 956)"() {
        when:
        def response = rest.getForEntity("http://localhost:$port/api/items/stats", Map)

        then:
        response.statusCode == HttpStatus.OK
        response.body.containsKey("lastSyncedAt")
        // Value is a Long (epoch ms) or 0 on first boot. Never null.
        response.body.lastSyncedAt != null
    }

    // ── GET /api/items/{id} — happy path ─────────────────────────────
    // The existing suite only pinned the {notFound:true} sentinel for a
    // missing id. A real-item read carries the csfloat item-detail fields
    // (lowest price = floor, supply = circulation, view count) so those
    // need a regression pin too.

    def "GET /api/items/{id} returns the item with the csfloat detail fields for a real id"() {
        given:
        def item = newItem(supply: 1234, lowestPrice: new BigDecimal("7.50"))

        when:
        def response = rest.getForEntity("http://localhost:$port/api/items/${item.id}", Map)

        then:
        response.statusCode == HttpStatus.OK
        response.body.id == item.id
        response.body.name == item.name
        // lowestPrice = the floor the item-detail page anchors on.
        new BigDecimal(response.body.lowestPrice.toString()) == new BigDecimal("7.50")
        // supply = "N in circulation" on the detail page.
        response.body.supply == 1234
        !response.body.containsKey("notFound")
    }

    def "GET /api/items/{id} increments the view count, deduped per IP"() {
        given: "a fresh item starts at zero views"
        def item = newItem(viewCount: 0L)

        when: "the same client loads the item page three times in a row"
        3.times { rest.getForEntity("http://localhost:$port/api/items/${item.id}", Map) }

        then: "the view counter advanced by exactly one — the per-(ip,item) "
        // dedupe window (30 min) collapses a refresh-spam into a single
        // genuine view. A broken dedupe would show 3 here.
        itemRepository.findById(item.id).get().viewCount == 1L
    }

    // ── GET /api/items/{id}/history ──────────────────────────────────
    // Price-history series for the sparkline. No HTTP coverage existed.

    def "GET /api/items/{id}/history returns the recorded price points oldest-first"() {
        given: "two history rows for the item, recorded a day apart"
        def item = newItem()
        long now = System.currentTimeMillis()
        priceHistoryRepository.save(new PriceHistory(
            item: item, price: new BigDecimal("5.00"), volume: 1,
            recordedAt: now - 86_400_000L, dayLabel: 'older'))
        priceHistoryRepository.save(new PriceHistory(
            item: item, price: new BigDecimal("8.00"), volume: 2,
            recordedAt: now, dayLabel: 'newer'))

        when:
        def response = rest.getForEntity(
            "http://localhost:$port/api/items/${item.id}/history", List)

        then: "200 + both rows in recordedAt-ASC order so the chart can slice the tail"
        response.statusCode == HttpStatus.OK
        response.body.size() == 2
        response.body*.dayLabel == ['older', 'newer']
        new BigDecimal(response.body[0].price.toString()) == new BigDecimal("5.00")
    }

    def "GET /api/items/{id}/history returns an empty 200 for an item with no history"() {
        given:
        def item = newItem()

        when:
        def response = rest.getForEntity(
            "http://localhost:$port/api/items/${item.id}/history", List)

        then: "empty list, not a 404 — the chart renders a blank strip"
        response.statusCode == HttpStatus.OK
        response.body == []
    }

    def "GET /api/items/{id}/history returns an empty 200 for a missing item"() {
        when:
        def response = rest.getForEntity(
            "http://localhost:$port/api/items/99999999/history", List)

        then: "consistent degradation with /similar and the {id} sentinel"
        response.statusCode == HttpStatus.OK
        response.body == []
    }

    // ── GET /api/items/{id}/recent-sales ─────────────────────────────
    // "Last N sales" strip on the item-detail modal. No HTTP coverage
    // existed for the endpoint, its field shape, or the ?limit cap.

    def "GET /api/items/{id}/recent-sales returns SOLD rows newest-first with price + soldAt + type"() {
        given: "three sold listings for the item at increasing soldAt timestamps"
        def item = newItem()
        long now = System.currentTimeMillis()
        soldListing(item, new BigDecimal("10.00"), now - 3_000L)
        soldListing(item, new BigDecimal("11.00"), now - 2_000L)
        def newest = soldListing(item, new BigDecimal("12.00"), now - 1_000L)

        when:
        def response = rest.getForEntity(
            "http://localhost:$port/api/items/${item.id}/recent-sales", List)

        then: "200 + all three rows, most-recently-sold first"
        response.statusCode == HttpStatus.OK
        response.body.size() == 3
        response.body[0].listingId == newest.id

        and: "each row carries exactly the csfloat-parity fields — price, soldAt, type"
        // No buyer identity is leaked: the projection is price + timestamp only.
        def row = response.body[0]
        new BigDecimal(row.price.toString()) == new BigDecimal("12.00")
        row.soldAt != null
        row.listingType == 'BUY_NOW'
        !row.containsKey('buyerUserId')
        !row.containsKey('sellerName')
    }

    def "GET /api/items/{id}/recent-sales excludes ACTIVE listings — only settled sales count"() {
        given: "the item has one SOLD and one still-ACTIVE listing"
        def item = newItem()
        soldListing(item, new BigDecimal("10.00"), System.currentTimeMillis())
        listingRepository.save(new Listing(
            item: item, price: new BigDecimal("9.00"), sellerName: 'Active',
            status: 'ACTIVE', listingType: 'BUY_NOW', rarityScore: BigDecimal.ZERO))

        when:
        def response = rest.getForEntity(
            "http://localhost:$port/api/items/${item.id}/recent-sales", List)

        then: "only the SOLD row surfaces — an active listing is not a sale"
        response.statusCode == HttpStatus.OK
        response.body.size() == 1
        new BigDecimal(response.body[0].price.toString()) == new BigDecimal("10.00")
    }

    def "GET /api/items/{id}/recent-sales defaults to 10 rows and caps ?limit at 50"() {
        given: "an item with 60 sold listings"
        def item = newItem()
        long now = System.currentTimeMillis()
        (1..60).each { i -> soldListing(item, new BigDecimal("5.00"), now - i) }

        expect: "no limit → CSFloat-style last-10 default"
        rest.getForEntity(
            "http://localhost:$port/api/items/${item.id}/recent-sales", List)
            .body.size() == 10

        and: "limit=25 → honoured verbatim"
        rest.getForEntity(
            "http://localhost:$port/api/items/${item.id}/recent-sales?limit=25", List)
            .body.size() == 25

        and: "limit=999 → clamped to the 50-row server cap"
        rest.getForEntity(
            "http://localhost:$port/api/items/${item.id}/recent-sales?limit=999", List)
            .body.size() == 50
    }

    def "GET /api/items/{id}/recent-sales returns an empty 200 for an item with no sales"() {
        given:
        def item = newItem()

        when:
        def response = rest.getForEntity(
            "http://localhost:$port/api/items/${item.id}/recent-sales", List)

        then:
        response.statusCode == HttpStatus.OK
        response.body == []
    }

    // ── GET /api/items/{id}/velocity — count correctness ─────────────

    def "GET /api/items/{id}/velocity counts sales into the right 24h / 7d / 30d buckets"() {
        given: "sales at 12h, 3d and 20d ago — one lands in each progressively wider window"
        def item = newItem()
        long now = System.currentTimeMillis()
        soldListing(item, new BigDecimal("10.00"), now - (12L  * 3600_000L))   // < 24h
        soldListing(item, new BigDecimal("20.00"), now - (3L   * 86400_000L))  // < 7d
        soldListing(item, new BigDecimal("30.00"), now - (20L  * 86400_000L))  // < 30d

        when:
        def response = rest.getForEntity(
            "http://localhost:$port/api/items/${item.id}/velocity", Map)

        then: "each bucket is cumulative — 24h⊂7d⊂30d"
        response.statusCode == HttpStatus.OK
        response.body.soldLast24h == 1
        response.body.soldLast7d  == 2
        response.body.soldLast30d == 3
        // volumeLast30d sums the gross price of every sale in the 30d window.
        new BigDecimal(response.body.volumeLast30d.toString()) == new BigDecimal("60.00")
        // lastSoldPrice/At reflect the single most-recent sale (the 12h-ago one).
        new BigDecimal(response.body.lastSoldPrice.toString()) == new BigDecimal("10.00")
        response.body.lastSoldAt != null
    }

    // ── GET /api/items/{id}/similar ──────────────────────────────────

    def "GET /api/items/{id}/similar never includes the subject item itself"() {
        given: "two items sharing a category so each is a similar candidate of the other"
        def base    = newItem(category: 'Hats', rarity: 'Limited')
        newItem(category: 'Hats', rarity: 'Limited')

        when:
        def response = rest.getForEntity(
            "http://localhost:$port/api/items/${base.id}/similar", List)

        then: "the subject id is excluded from its own similar feed"
        response.statusCode == HttpStatus.OK
        response.body.every { it.id != base.id }
    }

    // ── GET /api/items/{id} — view-count IP resolution edge cases ─────
    // The view-count dedupe keys on the resolved client IP (CF-Connecting-IP
    // → X-Forwarded-For → remoteAddr). A crafted X-Forwarded-For made of
    // only commas / empty tokens is NON-blank, so the old
    // `xff.split(',')[0]` indexed into Java's zero-length split result and
    // threw ArrayIndexOutOfBoundsException — swallowed by the best-effort
    // catch, but it silently killed the view bump for that request.

    /** GETs an item carrying an explicit X-Forwarded-For header. */
    private getItemWithHeaders(Long id, Map<String, String> headers) {
        def h = new HttpHeaders()
        headers.each { k, v -> h.add(k, v) }
        rest.exchange(
            "http://localhost:$port/api/items/${id}",
            HttpMethod.GET, new HttpEntity<Void>(h), Map)
    }

    def "GET /api/items/{id} survives an all-comma X-Forwarded-For header and still bumps the view"() {
        given: "a fresh item at zero views"
        def item = newItem(viewCount: 0L)

        when: "a request arrives with a degenerate X-Forwarded-For: ',' header"
        // Pre-fix this threw ArrayIndexOutOfBoundsException inside clientIp().
        def response = getItemWithHeaders(item.id, ['X-Forwarded-For': ','])

        then: "the response is a clean 200 with the real item — no crash, no sentinel"
        response.statusCode == HttpStatus.OK
        response.body.id == item.id
        !response.body.containsKey('notFound')

        and: "the view still counted — clientIp fell through to remoteAddr"
        itemRepository.findById(item.id).get().viewCount == 1L
    }

    def "GET /api/items/{id} tolerates an empty-token X-Forwarded-For (',,') without erroring"() {
        given:
        def item = newItem(viewCount: 0L)

        when:
        def response = getItemWithHeaders(item.id, ['X-Forwarded-For': ',,'])

        then: "still a clean item read"
        response.statusCode == HttpStatus.OK
        response.body.id == item.id
    }

    def "GET /api/items/{id} dedupes per real X-Forwarded-For IP, not per shared proxy peer"() {
        given: "a fresh item — two distinct clients reach it behind one proxy"
        def item = newItem(viewCount: 0L)

        when: "client A loads it twice, client B loads it once — distinct XFF IPs"
        getItemWithHeaders(item.id, ['X-Forwarded-For': '198.51.100.7'])
        getItemWithHeaders(item.id, ['X-Forwarded-For': '198.51.100.7'])
        getItemWithHeaders(item.id, ['X-Forwarded-For': '198.51.100.8'])

        then: "two genuine viewers counted — A's refresh deduped, B counted separately"
        // A broken resolver keying on the proxy peer would collapse B into
        // A's bucket (count 1); ignoring dedupe entirely would show 3.
        itemRepository.findById(item.id).get().viewCount == 2L
    }

    def "GET /api/items/{id} resolves the first real X-Forwarded-For hop when leading tokens are blank"() {
        given:
        def item = newItem(viewCount: 0L)

        when: "the XFF list has a blank leading entry before the real client IP"
        getItemWithHeaders(item.id, ['X-Forwarded-For': ' , 203.0.113.55'])
        getItemWithHeaders(item.id, ['X-Forwarded-For': ' , 203.0.113.55'])

        then: "both requests resolved to the SAME real IP → deduped to one view"
        itemRepository.findById(item.id).get().viewCount == 1L
    }

    def "GET /api/items/{id} ignores a blank CF-Connecting-IP and falls through to X-Forwarded-For"() {
        given:
        def item = newItem(viewCount: 0L)

        when: "CF header is whitespace-only; the real IP lives in X-Forwarded-For"
        getItemWithHeaders(item.id,
            ['CF-Connecting-IP': '   ', 'X-Forwarded-For': '192.0.2.30'])
        getItemWithHeaders(item.id,
            ['CF-Connecting-IP': '   ', 'X-Forwarded-For': '192.0.2.30'])

        then: "a blank CF header no longer collapses every caller into one empty-string bucket"
        // Pre-fix `clientIp` returned "" for a whitespace CF header, which
        // shouldBumpView treats as unidentifiable → bump EVERY time. The
        // fall-through to the real XFF IP restores proper per-IP dedupe.
        itemRepository.findById(item.id).get().viewCount == 1L
    }

    def "GET /api/items/{id} prefers a valid CF-Connecting-IP over X-Forwarded-For"() {
        given:
        def item = newItem(viewCount: 0L)

        when: "two requests share a CF IP but differ in XFF — CF must win the dedupe key"
        getItemWithHeaders(item.id,
            ['CF-Connecting-IP': '198.51.100.200', 'X-Forwarded-For': '10.0.0.1'])
        getItemWithHeaders(item.id,
            ['CF-Connecting-IP': '198.51.100.200', 'X-Forwarded-For': '10.0.0.2'])

        then: "same CF IP → one view; the differing XFF is correctly ignored"
        itemRepository.findById(item.id).get().viewCount == 1L
    }

    // ── GET /api/items?q= — SQL LIKE wildcard escaping ───────────────
    // ItemRepository.searchByName's JPQL carries `LIKE … ESCAPE '\'` —
    // the same posture as DatabaseController/ListingController — but
    // ItemController used to forward the raw `q` unescaped, so a user-
    // typed `_` or `%` matched as a wildcard instead of literally. A
    // search for a literal underscore returned every catalogue row
    // whose name contained ANY single character at that position;
    // `%` returned everything. Same defence applied to /api/database
    // (DatabaseController.escapeLike) + /api/listings (ListingController.
    // escapeLike) — this brings /api/items into parity.

    def "GET /api/items?q= treats SQL LIKE wildcards as literal characters"() {
        given: "two items — one whose name actually contains an underscore"
        def uniq = String.valueOf(System.nanoTime())
        def hit  = newItem(name: "Underscore_${uniq}")
        // A distractor whose name does NOT contain an underscore but
        // would match if `_` were treated as a single-char wildcard.
        // The wildcard interpretation of `Underscore_${uniq}` matches
        // any name with `Underscore` + ANY single char + `${uniq}` —
        // which `UnderscoreX${uniq}` satisfies but the literal form
        // does not.
        def miss = newItem(name: "UnderscoreX${uniq}")

        when: "the search term contains a literal underscore"
        def response = rest.getForEntity(
            "http://localhost:$port/api/items?q=Underscore_${uniq}", List)

        then: "only the row with the literal underscore matches"
        response.statusCode == HttpStatus.OK
        def ids = response.body*.id as Set
        ids.contains(hit.id)
        !ids.contains(miss.id)
    }
}
