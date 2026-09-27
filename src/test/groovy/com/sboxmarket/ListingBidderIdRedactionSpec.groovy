package com.sboxmarket

import com.fasterxml.jackson.databind.ObjectMapper
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
 * Pins the privacy contract on Listing serialization: the top bidder's
 * SteamUser.id must NEVER leave the server — not on the public listing
 * endpoints, not on item-detail, not on the seller's stall.
 *
 * Without the @JsonIgnore on Listing.currentBidderId, every anonymous
 * marketplace browser could harvest the user id of whoever is winning
 * each auction by walking /api/listings — which bypasses both the
 * BidService.historyFor handle-aliasing (Bidder #1 / Bidder #2) and the
 * AuctionEventBus.onBid SSE redaction (which deliberately omits the field
 * for the same reason).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ListingBidderIdRedactionSpec extends Specification {

    @Autowired ApplicationContext ctx

    MockMvc           mockMvc
    ObjectMapper      mapper
    ItemRepository    itemRepo
    ListingRepository listingRepo

    def setup() {
        mockMvc     = ctx.getBean(MockMvc)
        mapper      = ctx.getBean(ObjectMapper)
        itemRepo    = ctx.getBean(ItemRepository)
        listingRepo = ctx.getBean(ListingRepository)
    }

    def "Listing.currentBidderId is NEVER serialized to JSON"() {
        given:
        def uniq = String.valueOf(System.nanoTime())
        def item = itemRepo.save(new Item(
            name:        "BidderRedactItem-${uniq}",
            category:    'Hats',
            rarity:      'Limited',
            supply:      10,
            totalSold:   0,
            lowestPrice: new BigDecimal('5.00'),
            iconEmoji:   '🎩'
        ))
        def auction = listingRepo.save(new Listing(
            item:              item,
            price:             new BigDecimal('5.00'),
            status:            'ACTIVE',
            sellerName:        "AuctionSeller-${uniq}",
            rarityScore:       BigDecimal.ZERO,
            listingType:       'AUCTION',
            expiresAt:         System.currentTimeMillis() + 3_600_000L,
            currentBid:        new BigDecimal('7.50'),
            currentBidderId:   424242L,
            currentBidderName: 'TopBidder',
            bidCount:          3
        ))

        when: "Jackson serializes the Listing entity directly"
        def json = mapper.writeValueAsString(auction)

        then: "the bidder's display name leaks (intended) but the user id does not"
        json.contains('"currentBidderName":"TopBidder"')
        !json.contains('"currentBidderId"')
        !json.contains('424242')

        when: "the same auction is fetched via the public /api/listings endpoint"
        def httpJson = mockMvc.perform(MockMvcRequestBuilders.get("/api/listings/${auction.id}"))
            .andReturn().response.contentAsString

        then: "the HTTP response also has no currentBidderId field"
        !httpJson.contains('"currentBidderId"')
        !httpJson.contains('424242')
        // sanity: the response is the auction we asked for
        httpJson.contains("BidderRedactItem-${uniq}")
    }
}
