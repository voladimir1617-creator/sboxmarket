package com.sboxmarket

import com.fasterxml.jackson.databind.ObjectMapper
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.Offer
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.OfferRepository
import com.sboxmarket.repository.SteamUserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import spock.lang.Specification

/**
 * Anonymous shop on the offer reads: an offer is not a trade, so making one
 * (and cancelling it) must not tell a buyer who the anonymous seller is.
 * The public offer thread, loaded on every item page, must not carry the
 * seller's id either. The seller still sees their own thread unmasked.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnonymousShopOfferDbSpec extends Specification {

    @Autowired MockMvc mockMvc
    @Autowired ObjectMapper objectMapper
    @Autowired SteamUserRepository userRepo
    @Autowired ItemRepository itemRepo
    @Autowired ListingRepository listingRepo
    @Autowired OfferRepository offerRepo

    String uniq = String.valueOf(System.nanoTime())
    int seq = 0

    private SteamUser user(String name, boolean anonymous) {
        def tail = (uniq + (seq++)).reverse().take(9).reverse()
        userRepo.save(new SteamUser(steamId64: '7656118' + tail.padLeft(10, '0'),
            displayName: "${name}-${uniq}", role: 'USER', anonymousShop: anonymous))
    }

    private Object get(String path, Long asUser = null) {
        def b = MockMvcRequestBuilders.get(path)
        if (asUser != null) b = b.sessionAttr(SteamAuthController.SESSION_USER_ID, asUser)
        def r = mockMvc.perform(b).andReturn()
        assert r.response.status == 200
        objectMapper.readValue(r.response.contentAsString, Object)
    }

    private Map offerOnListing(boolean anonymous) {
        SteamUser seller = user('offerseller', anonymous)
        SteamUser buyer = user('offerbuyer', false)
        Item hat = itemRepo.save(new Item(name: "OfferHat ${uniq}", category: 'Hats', rarity: 'Standard',
            supply: 1, totalSold: 0, lowestPrice: new BigDecimal('5'), isListed: true, iconEmoji: '🎩'))
        Listing l = listingRepo.save(new Listing(item: hat, sellerUserId: seller.id, sellerName: seller.displayName,
            sellerAvatar: 'XY', price: new BigDecimal('5.00'), status: 'ACTIVE', listingType: 'BUY_NOW', hidden: false))
        offerRepo.save(new Offer(listingId: l.id, buyerUserId: buyer.id, sellerUserId: seller.id,
            amount: new BigDecimal('4.00'), askingPrice: new BigDecimal('5.00'), buyerName: buyer.displayName,
            itemName: hat.name, itemId: hat.id))
        [seller: seller, buyer: buyer, listing: l]
    }

    def "a buyer's outgoing offers and the public thread don't name or number an anonymous seller"() {
        given:
        def s = offerOnListing(true)

        when:
        List outgoing = get('/api/offers/outgoing', s.buyer.id) as List
        List threadVisitor = get("/api/offers/thread/${s.listing.id}") as List
        List threadBuyer = get("/api/offers/thread/${s.listing.id}", s.buyer.id) as List
        List threadSeller = get("/api/offers/thread/${s.listing.id}", s.seller.id) as List

        then:
        outgoing.size() == 1
        outgoing[0].sellerUserId == 0
        outgoing[0].sellerName == null
        outgoing[0].sellerAnonymous == true
        !objectMapper.writeValueAsString(outgoing).contains(s.seller.displayName)
        threadVisitor*.sellerUserId == [0]
        threadBuyer*.sellerUserId == [0]
        threadSeller*.sellerUserId == [s.seller.id]

        and: 'the stored offer keeps the real seller'
        offerRepo.findByListingId(s.listing.id)*.sellerUserId == [s.seller.id]
    }

    def "a seller who doesn't list anonymously is still named on the buyer's offers"() {
        given:
        def s = offerOnListing(false)

        when:
        List outgoing = get('/api/offers/outgoing', s.buyer.id) as List
        List threadVisitor = get("/api/offers/thread/${s.listing.id}") as List

        then:
        outgoing[0].sellerUserId == s.seller.id
        outgoing[0].sellerName == s.seller.displayName
        threadVisitor*.sellerUserId == [s.seller.id]
    }
}
