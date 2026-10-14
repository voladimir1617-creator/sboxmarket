package com.sboxmarket

import com.sboxmarket.controller.BuyOrderController
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.model.BuyOrder
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.BuyOrderRepository
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

/**
 * Defect pass 37: Profile -> Buy Orders measured its floor gap against the
 * item floor, which counts auctions, while a buy order only ever fills
 * against Buy Now listings. An auction-only item showed a green "= floor"
 * for an order that could never fill.
 */
@SpringBootTest
@ActiveProfiles("test")
class DefectPassThirtySevenSpec extends Specification {

    @Autowired BuyOrderController buyOrderController
    @Autowired SteamUserRepository userRepo
    @Autowired BuyOrderRepository buyOrderRepo
    @Autowired ItemRepository itemRepo
    @Autowired ListingRepository listingRepo

    static int seq = 0
    String uniq = String.valueOf(System.nanoTime())

    private SteamUser user() {
        seq++
        def tail = (uniq + seq).reverse().take(9).reverse()
        userRepo.save(new SteamUser(steamId64: '7656119' + tail.padLeft(10, '0'),
            displayName: "Pass37-${seq}-${uniq}", role: 'USER'))
    }

    private MockHttpServletRequest signedIn(Long uid) {
        def req = new MockHttpServletRequest()
        def session = new MockHttpSession()
        session.setAttribute(SteamAuthController.SESSION_USER_ID, uid)
        req.session = session
        req
    }

    private Item item(String floor) {
        itemRepo.save(new Item(name: "Pass37 ${uniq} ${++seq}", category: 'Accessories', rarity: 'Standard',
            supply: 1, totalSold: 0, lowestPrice: new BigDecimal(floor), isListed: true, iconEmoji: '🎩'))
    }

    private Listing listing(Item it, String price, Map extra = [:]) {
        listingRepo.save(new Listing([item: it, price: new BigDecimal(price), listingType: 'BUY_NOW',
            status: 'ACTIVE', sellerName: 'p37', rarityScore: BigDecimal.ZERO] + extra))
    }

    def "an auction-only item reports no floor on the buyer's order"() {
        given:
        def buyer = user()
        def it = item('5.00')
        listing(it, '5.00', [listingType: 'AUCTION'])
        buyOrderRepo.save(new BuyOrder(buyerUserId: buyer.id, itemId: it.id, itemName: it.name,
            maxPrice: new BigDecimal('5.00'), status: 'ACTIVE'))

        when:
        def row = buyOrderController.mine(signedIn(buyer.id)).body[0]

        then:
        row.currentFloor == null
        row.floorGap == null
    }

    def "the floor is the cheapest Buy Now listing, not a cheaper auction"() {
        given:
        def buyer = user()
        def it = item('3.00')
        listing(it, '3.00', [listingType: 'AUCTION'])
        listing(it, '8.00')
        listing(it, '7.00', [hidden: true])
        buyOrderRepo.save(new BuyOrder(buyerUserId: buyer.id, itemId: it.id, itemName: it.name,
            maxPrice: new BigDecimal('6.00'), status: 'ACTIVE'))

        when:
        def row = buyOrderController.mine(signedIn(buyer.id)).body[0]

        then:
        row.currentFloor == new BigDecimal('8.00')
        row.floorGap == new BigDecimal('2.00')
    }
}
