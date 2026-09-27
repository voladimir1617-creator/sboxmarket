package com.sboxmarket

import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import spock.lang.Specification

/**
 * Signed-in coverage for /api/listings/my-stall/analytics.
 *
 * Pre-fix the controller mapped two non-existent properties on the
 * Listing entity: `kind` (the real field is `listingType`) and
 * `createdAt` (the real field is `listedAt`). Groovy's MOP threw
 * `MissingPropertyException` on the first miss, which the global
 * error handler surfaced as a 500 on the Analytics tab for any
 * seller with ≥1 active listing — while the existing anon-401 spec
 * stayed green because anon never reaches the row-mapping branch.
 *
 * This spec seeds a signed-in seller + listing and asserts:
 *   1. response is 200 + JSON array
 *   2. each row carries the documented fields (price, floorDelta,
 *      itemSales30d, listingType, listedAt, etc.)
 *   3. the renamed `listingType` + `listedAt` fields actually echo
 *      the backing Listing properties (not the broken `kind` /
 *      `createdAt` accessors).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StallAnalyticsHttpSpec extends Specification {

    @Autowired ApplicationContext ctx

    MockMvc             mockMvc
    SteamUserRepository steamUserRepository
    ItemRepository      itemRepository
    ListingRepository   listingRepository

    SteamUser seller
    Item      item
    Listing   listing
    MockHttpSession session

    def setup() {
        mockMvc             = ctx.getBean(MockMvc)
        steamUserRepository = ctx.getBean(SteamUserRepository)
        itemRepository      = ctx.getBean(ItemRepository)
        listingRepository   = ctx.getBean(ListingRepository)

        // Unique-id pattern matches the additive isolation used in
        // BuyFlowIntegrationSpec — multiple specs share the H2 context.
        def uniq = String.valueOf(System.nanoTime())
        def steamId = "76561199" + uniq.substring(uniq.length() - 9)

        seller = steamUserRepository.save(new SteamUser(
            steamId64:   steamId,
            displayName: "TestSeller-" + uniq
        ))
        item = itemRepository.save(new Item(
            name:         "Analytics Hat " + uniq,
            category:     "Hats",
            rarity:       "Limited",
            imageUrl:     "https://example.com/hat.png",
            iconEmoji:    "🎩",
            accentColor:  "#1a0a3a",
            supply:       1000,
            totalSold:    0,
            trendPercent: 0,
            viewCount:    42,
            lowestPrice:  new BigDecimal("9.99")
        ))
        listing = listingRepository.save(new Listing(
            item:          item,
            price:         new BigDecimal("12.50"),
            sellerName:    "TestSeller-" + uniq,
            sellerAvatar:  "TS",
            sellerUserId:  seller.id,
            status:        "ACTIVE",
            listingType:   "BUY_NOW",
            rarityScore:   new BigDecimal("0.7")
        ))

        session = new MockHttpSession()
        session.setAttribute(SteamAuthController.SESSION_USER_ID, seller.id)
    }

    def "GET /api/listings/my-stall/analytics — 200 + analytics row for the seller's active listing"() {
        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings/my-stall/analytics').session(session)
        ).andReturn()

        then:
        result.response.status == 200
        def body = result.response.contentAsString
        body.startsWith('[')

        and: "row carries the documented analytics fields"
        body.contains('"listingId":' + listing.id)
        body.contains('"itemId":' + item.id)
        body.contains('"itemName":"Analytics Hat')
        body.contains('"itemRarity":"Limited"')
        body.contains('"category":"Hats"')
        body.contains('"price":12.50')
        body.contains('"floorPrice":9.99')
        // myPrice ($12.50) − floor ($9.99) = +$2.51 above floor (over-priced).
        body.contains('"floorDelta":2.51')
        body.contains('"viewCount":42')
        body.contains('"supply":1000')
        body.contains('"itemSales30d":0')

        and: "listingType field exposes the underlying BUY_NOW/AUCTION enum"
        // Pre-fix this read `l.kind?.toString()` and threw 500. Spec
        // pins the renamed `listingType` field so any future rename
        // back to `kind` (or a typo) trips CI before reaching prod.
        body.contains('"listingType":"BUY_NOW"')

        and: "listedAt field is a non-null long timestamp"
        // Pre-fix this read `l.createdAt` (no such field on Listing
        // — the column is `listedAt`). Same MissingPropertyException
        // class of bug. Pinning the right property name here.
        body =~ /"listedAt":\d{12,}/
    }

    def "GET /api/listings/my-stall/analytics — empty array when seller has no active listings"() {
        given: "seller's listing flipped to SOLD so the active query returns []"
        listing.status = "SOLD"
        listing.soldAt = System.currentTimeMillis()
        listingRepository.save(listing)

        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings/my-stall/analytics').session(session)
        ).andReturn()

        then: "200 + empty array (not 404 / no body) so the UI renders the empty-state"
        result.response.status == 200
        result.response.contentAsString == '[]'
    }

    def "GET /api/listings/my-stall/analytics.csv — 200 + CSV header + row for the seller's active listing"() {
        // Companion CSV export — mirrors /sold.csv + /active.csv pattern
        // so a seller running >50 listings can rank-sort offline in
        // Excel / build pivot tables on category-level demand.
        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings/my-stall/analytics.csv').session(session)
        ).andReturn()

        then: "200 + text/csv + Content-Disposition attachment so browsers prompt a download"
        result.response.status == 200
        result.response.contentType?.startsWith('text/csv')
        result.response.getHeader('Content-Disposition')?.contains('mystall-analytics.csv')

        and: "Cache-Control: no-store so a shared-machine browser cache can't surface User A's analytics CSV to User B after a session swap"
        // CorrelationIdFilter's isMyStall predicate covers every
        // /api/listings/my-stall* path. The full filter value is
        // `no-store, no-cache, must-revalidate, private` — pinning
        // the no-store substring is enough to assert "uncacheable".
        // Without this header a browser disk cache could persist a
        // CSV across user sessions on a shared machine.
        result.response.getHeader('Cache-Control')?.contains('no-store')

        and: "header row exposes the documented per-listing analytics columns"
        def body = result.response.contentAsString
        def lines = body.split('\n')
        lines.length >= 2
        lines[0] == 'listing_id,item_id,item_name,category,rarity,listing_type,my_price,floor_price,floor_delta,view_count,supply,item_sales_30d,listed_at'

        and: "data row reflects the seeded listing — price/floor/delta/viewCount/supply land where the column says they should"
        def cols = lines[1].split(',', -1)
        cols[0] == String.valueOf(listing.id)
        cols[1] == String.valueOf(item.id)
        cols[3] == 'Hats'
        cols[4] == 'Limited'
        cols[5] == 'BUY_NOW'
        cols[6] == '12.50'
        cols[7] == '9.99'
        // myPrice − floor = +2.51 above floor (over-priced relative to
        // the cheapest listing in the marketplace for this item).
        cols[8] == '2.51'
        cols[9] == '42'
        cols[10] == '1000'
        cols[11] == '0'
    }
}
