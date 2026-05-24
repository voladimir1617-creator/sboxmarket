package com.sboxmarket

import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import spock.lang.Specification

/**
 * HTTP-level pinning for POST /api/listings/check-active — the cart's
 * bulk freshness probe. The endpoint accepts a JSON `{ids:[...]}` body
 * and returns one `{id, active, price}` row per requested id.
 *
 * Regression for a 500 INTERNAL_ERROR on a malformed body. Pre-fix the
 * controller coerced every element with a bare `(it as Long)` Groovy
 * cast — a non-numeric String token (`{"ids":["abc"]}`), a JSON boolean,
 * or any other shape that Jackson maps to a non-numeric Object threw a
 * GroovyCastException that wrapped a NumberFormatException. The
 * GlobalExceptionHandler catch-all then surfaced it as a 500 even though
 * the real classification is a 4xx malformed-body client mistake.
 *
 * Post-fix the coerce is wrapped in a defensive try/catch that silently
 * drops bad tokens, matching the bulkMerge/bulkCounts family
 * (WatchlistController, BuyOrderController, SavedSearchController). A
 * mixed body of `[validId, "abc", null]` is treated as `[validId]` and
 * the probe returns a partial result rather than 500ing the whole call.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CheckActiveHttpSpec extends Specification {

    @Autowired ApplicationContext ctx

    MockMvc           mockMvc
    ItemRepository    itemRepo
    ListingRepository listingRepo
    Item              seededItem
    Listing           activeListing

    def setup() {
        mockMvc     = ctx.getBean(MockMvc)
        itemRepo    = ctx.getBean(ItemRepository)
        listingRepo = ctx.getBean(ListingRepository)

        def uniq = String.valueOf(System.nanoTime())
        seededItem = itemRepo.save(new Item(
            name:         "CheckActiveItem-${uniq}",
            category:     'Hats',
            rarity:       'Standard',
            supply:       10,
            totalSold:    0,
            lowestPrice:  new BigDecimal("12.50"),
            iconEmoji:    '🎩'
        ))
        activeListing = listingRepo.save(new Listing(
            item:         seededItem,
            price:        new BigDecimal("12.50"),
            status:       'ACTIVE',
            sellerName:   "CheckActiveSeller-${uniq}",
            rarityScore:  BigDecimal.ZERO
        ))
    }

    def "POST /check-active with a non-numeric ids element does not 500 (regression for bare-cast crash)"() {
        given:
        // Mixed payload: one real id + one junk string token + one null.
        // Pre-fix the `"abc" as Long` cast threw GroovyCastException and
        // the catch-all turned the whole request into a 500 INTERNAL_ERROR
        // — masking the legit row that should still have been resolved.
        def body = """{"ids":[${activeListing.id},"abc",null]}"""

        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.post('/api/listings/check-active')
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        ).andReturn()

        then:
        // 200, not 500 — bad tokens are silently dropped, the real id is
        // still probed and the row's active state surfaces normally.
        result.response.status == 200
        def text = result.response.contentAsString
        text.contains("\"id\":${activeListing.id}")
        text.contains('"active":true')
    }

    def "POST /check-active with a boolean ids element also degrades cleanly"() {
        given:
        // A JSON `true` lands as a Boolean Object — also a non-numeric
        // shape that the bare cast couldn't handle. Confirms the
        // try/catch is broad enough to drop any non-numeric token.
        def body = """{"ids":[${activeListing.id},true]}"""

        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.post('/api/listings/check-active')
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        ).andReturn()

        then:
        result.response.status == 200
        result.response.contentAsString.contains("\"id\":${activeListing.id}")
    }

    def "POST /check-active with only non-numeric tokens returns an empty array, not 500"() {
        given:
        // Every token is junk — after dropping bad ones the ids list is
        // empty, which the existing `if (ids.isEmpty())` branch already
        // handles. Pre-fix this body still 500'd because the cast threw
        // before the empty-check ran.
        def body = '{"ids":["abc","xyz"]}'

        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.post('/api/listings/check-active')
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        ).andReturn()

        then:
        result.response.status == 200
        result.response.contentAsString.trim() == '[]'
    }
}
