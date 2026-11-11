package com.sboxmarket

import com.sboxmarket.controller.ListingController
import com.sboxmarket.controller.SellerStatsController
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Trade
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.service.SellerVerifiedRule
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

/**
 * The ✓ Verified seller badge counts DELIVERED trades (state VERIFIED) as the
 * seller, not listings marked SOLD. A listing flips to SOLD at purchase, so a
 * sale that was later cancelled or refunded used to keep counting forever.
 * Every surface reads the same rule (SellerVerifiedRule).
 */
@SpringBootTest
@ActiveProfiles("test")
class SellerVerifiedCountsDeliveredTradesSpec extends Specification {

    @Autowired ListingController listingController
    @Autowired SellerStatsController sellerStatsController
    @Autowired SteamUserRepository userRepo
    @Autowired ItemRepository itemRepo
    @Autowired ListingRepository listingRepo
    @Autowired TradeRepository tradeRepo

    static int seq = 0
    String uniq = String.valueOf(System.nanoTime())

    private SteamUser user() {
        seq++
        def tail = (uniq + seq).reverse().take(9).reverse()
        userRepo.save(new SteamUser(steamId64: '7656122' + tail.padLeft(10, '0'),
            displayName: "Verif-${seq}-${uniq}"))
    }

    private Item item() {
        itemRepo.save(new Item(name: "Verif ${uniq} ${++seq}", category: 'Accessories', rarity: 'Standard',
            supply: 1, totalSold: 0, lowestPrice: new BigDecimal('1.00'), isListed: true, iconEmoji: '🎩'))
    }

    /** A SOLD listing plus its trade in the given state. */
    private void sale(SteamUser seller, SteamUser buyer, String tradeState, String cancelledBy = null) {
        def itm = item()
        def l = listingRepo.save(new Listing(item: itm, price: new BigDecimal('1.00'), listingType: 'BUY_NOW',
            status: 'SOLD', sellerName: 'verif', rarityScore: BigDecimal.ZERO, sellerUserId: seller.id,
            buyerUserId: buyer.id, soldAt: System.currentTimeMillis()))
        tradeRepo.save(new Trade(buyerUserId: buyer.id, sellerUserId: seller.id, itemName: itm.name,
            itemId: itm.id, listingId: l.id, price: new BigDecimal('1.00'), state: tradeState,
            cancelledBy: cancelledBy,
            settledAt: tradeState in ['VERIFIED', 'CANCELLED'] ? System.currentTimeMillis() : null))
    }

    private MockHttpServletRequest signedIn(Long uid) {
        def req = new MockHttpServletRequest()
        req.session.setAttribute(SteamAuthController.SESSION_USER_ID, uid)
        req
    }

    def "10 SOLD listings with one trade cancelled and one refunded by staff is not Verified anywhere"() {
        given:
        def seller = user(); def buyer = user()
        8.times { sale(seller, buyer, 'VERIFIED') }
        sale(seller, buyer, 'CANCELLED', 'SELLER')
        sale(seller, buyer, 'CANCELLED', 'STAFF')

        expect: 'the listing table still says 10 sold'
        listingRepo.countSoldBySeller(seller.id) == 10L

        and: 'but only 8 trades were delivered, so no badge'
        tradeRepo.countVerifiedBySeller(seller.id) == 8L
        sellerStatsController.verifiedBulk("${seller.id}").body == [:]
        listingController.publicStall(seller.id, null).body.seller.verified == false
        with(sellerStatsController.myVerificationProgress(signedIn(seller.id)).body) {
            verified == false
            soldCount == 8L
            salesNeeded == 2L
        }
    }

    def "in-flight and disputed trades do not count until delivered"() {
        given:
        def seller = user(); def buyer = user()
        9.times { sale(seller, buyer, 'VERIFIED') }
        sale(seller, buyer, 'PENDING_BUYER_CONFIRM')
        sale(seller, buyer, 'DISPUTED')

        expect:
        tradeRepo.countVerifiedBySeller(seller.id) == 9L
        sellerStatsController.verifiedBulk("${seller.id}").body == [:]
    }

    def "10 delivered trades earns the badge on the stall, the bulk lookup and the progress card"() {
        given:
        def seller = user(); def buyer = user()
        10.times { sale(seller, buyer, 'VERIFIED') }

        expect:
        sellerStatsController.verifiedBulk("${seller.id}").body == [(seller.id): true]
        listingController.publicStall(seller.id, null).body.seller.verified == true
        with(sellerStatsController.myVerificationProgress(signedIn(seller.id)).body) {
            verified == true
            soldCount == 10L
            salesNeeded == 0L
            thresholdSales == SellerVerifiedRule.MIN_DELIVERED_TRADES
        }
    }

    def "a trade a seller makes with themselves never counts"() {
        given:
        def seller = user(); def buyer = user()
        9.times { sale(seller, buyer, 'VERIFIED') }
        sale(seller, seller, 'VERIFIED')

        expect:
        tradeRepo.countVerifiedBySeller(seller.id) == 9L
        SellerVerifiedRule.deliveredTradesBySeller(tradeRepo, [seller.id])[seller.id] == 9L
    }
}
