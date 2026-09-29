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
import org.springframework.data.domain.PageRequest
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import spock.lang.Specification

/**
 * Pre-launch security check, 2026-09-28. Each feature here failed on main
 * before the fix, through the real filter chain and database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityCheckHttpSpec extends Specification {

    @Autowired ApplicationContext ctx

    MockMvc             mockMvc
    ItemRepository      itemRepo
    ListingRepository   listingRepo
    SteamUserRepository userRepo

    Item      item
    SteamUser seller
    SteamUser buyer
    MockHttpSession sellerSession

    def setup() {
        mockMvc     = ctx.getBean(MockMvc)
        itemRepo    = ctx.getBean(ItemRepository)
        listingRepo = ctx.getBean(ListingRepository)
        userRepo    = ctx.getBean(SteamUserRepository)

        def uniq = String.valueOf(System.nanoTime())
        item = itemRepo.save(new Item(
            name:        "SecurityCheckItem-${uniq}",
            category:    'Hats',
            rarity:      'Standard',
            supply:      10,
            totalSold:   0,
            lowestPrice: new BigDecimal('5.00'),
            iconEmoji:   '🎩'
        ))
        seller = userRepo.save(new SteamUser(steamId64: '76561197' + uniq.substring(uniq.length() - 10),
                                             displayName: "SecSeller-${uniq}"))
        buyer  = userRepo.save(new SteamUser(steamId64: '76561196' + uniq.substring(uniq.length() - 10),
                                             displayName: "SecBuyer-${uniq}"))
        sellerSession = new MockHttpSession()
        sellerSession.setAttribute(SteamAuthController.SESSION_USER_ID, seller.id)
    }

    private Listing listing(Map args) {
        listingRepo.save(new Listing([
            item:         item,
            price:        new BigDecimal('5.00'),
            status:       'ACTIVE',
            sellerName:   seller.displayName,
            sellerUserId: seller.id,
            rarityScore:  BigDecimal.ZERO
        ] + args))
    }

    def "a ;matrix or percent-encoded spelling of an /api path is refused before any controller"() {
        expect:
        mockMvc.perform(MockMvcRequestBuilders.get('/api;x/wallet').session(sellerSession))
            .andReturn().response.status == 400
        mockMvc.perform(MockMvcRequestBuilders.get('/%61pi/wallet').session(sellerSession))
            .andReturn().response.status == 400
        mockMvc.perform(MockMvcRequestBuilders.get('/api/wallet').session(sellerSession))
            .andReturn().response.status == 200
    }

    def "the old seller cannot rewrite the price of a listing that has sold"() {
        given:
        def sold = listing(status: 'SOLD', buyerUserId: buyer.id, soldAt: System.currentTimeMillis())

        when:
        def res = mockMvc.perform(MockMvcRequestBuilders.put("/api/listings/${sold.id}/stall")
            .session(sellerSession)
            .contentType(MediaType.APPLICATION_JSON)
            .content('{"price":99999}')).andReturn().response

        then:
        res.status == 400
        res.contentAsString.contains('LISTING_NOT_ACTIVE')
        listingRepo.findById(sold.id).get().price == new BigDecimal('5.00')
    }

    def "the seller can still edit a live listing"() {
        given:
        def live = listing([:])

        when:
        def res = mockMvc.perform(MockMvcRequestBuilders.put("/api/listings/${live.id}/stall")
            .session(sellerSession)
            .contentType(MediaType.APPLICATION_JSON)
            .content('{"price":6.25}')).andReturn().response

        then:
        res.status == 200
        listingRepo.findById(live.id).get().price == new BigDecimal('6.25')
    }

    def "a cancelled listing (handed back to its seller) is not a sale in history or sold counts"() {
        given: 'a $99,999 listing the seller cancelled, and one real $5 sale'
        long now = System.currentTimeMillis()
        listing(status: 'SOLD', buyerUserId: seller.id, soldAt: now, price: new BigDecimal('99999.00'))
        def real = listing(status: 'SOLD', buyerUserId: buyer.id, soldAt: now - 1000)

        expect:
        listingRepo.countSoldBySeller(seller.id) == 1
        listingRepo.findRecentSalesForItem(item.id, PageRequest.of(0, 10))*.id == [real.id]
        listingRepo.sumRevenueBySeller(seller.id) == new BigDecimal('5.00')

        and: 'the returned item is still in the seller inventory'
        listingRepo.countOwnedBy(seller.id) == 1
    }

    def "GET /api/listings/{id} no longer says who bought a sold listing"() {
        given:
        def sold = listing(status: 'SOLD', buyerUserId: buyer.id, soldAt: System.currentTimeMillis())

        when:
        def res = mockMvc.perform(MockMvcRequestBuilders.get("/api/listings/${sold.id}")).andReturn().response

        then:
        res.status == 200
        !res.contentAsString.contains('buyerUserId')
    }

    def "check-active does not hand out a hidden listing's price"() {
        given:
        def hidden = listing(hidden: true, price: new BigDecimal('42.00'))

        when:
        def res = mockMvc.perform(MockMvcRequestBuilders.post('/api/listings/check-active')
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"ids":[${hidden.id}]}""")).andReturn().response

        then:
        res.status == 200
        res.contentAsString.contains('"active":false')
        !res.contentAsString.contains('42')
    }
}
