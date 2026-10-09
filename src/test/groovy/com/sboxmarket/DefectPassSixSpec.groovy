package com.sboxmarket

import com.sboxmarket.controller.ListingController
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.model.Bid
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SavedSearch
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.BidRepository
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.NotificationRepository
import com.sboxmarket.repository.SavedSearchRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.BidService
import com.sboxmarket.service.BuyOrderService
import com.sboxmarket.service.ListingService
import com.sboxmarket.service.SavedSearchService
import com.sboxmarket.service.SellerFollowService
import groovy.json.JsonSlurper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import spock.lang.Specification

/**
 * Defect pass 6: auctions and bidding, the market price sort, buy
 * orders, the trade-protection quote, the seller stall's sale figures,
 * follower and saved-search notifications.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DefectPassSixSpec extends Specification {

    @Autowired MockMvc mockMvc
    @Autowired BidService bidService
    @Autowired ListingService listingService
    @Autowired ListingController listingController
    @Autowired BuyOrderService buyOrderService
    @Autowired SellerFollowService sellerFollowService
    @Autowired SavedSearchService savedSearchService
    @Autowired SteamUserRepository userRepo
    @Autowired ItemRepository itemRepo
    @Autowired ListingRepository listingRepo
    @Autowired BidRepository bidRepo
    @Autowired NotificationRepository notificationRepo
    @Autowired SavedSearchRepository savedSearchRepo

    static int seq = 0
    String uniq = String.valueOf(System.nanoTime())

    private SteamUser user(String name = 'u') {
        seq++
        def tail = (uniq + seq).reverse().take(9).reverse()
        userRepo.save(new SteamUser(steamId64: '7656118' + tail.padLeft(10, '0'),
            displayName: "Pass6-${name}-${seq}", tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc'))
    }

    private Item item(String name = 'item') {
        itemRepo.save(new Item(name: "Pass6 ${name} ${uniq}", category: 'Accessories', rarity: 'Standard',
            supply: 1, totalSold: 0, lowestPrice: new BigDecimal('1.00'), isListed: true, iconEmoji: '🎩'))
    }

    private Listing auction(Long sellerUserId = null, BigDecimal start = new BigDecimal('10.00')) {
        listingRepo.save(new Listing(item: item('auction'), price: start, listingType: 'AUCTION',
            status: 'ACTIVE', sellerUserId: sellerUserId, sellerName: 'p6-seller',
            expiresAt: System.currentTimeMillis() + 3_600_000L, rarityScore: BigDecimal.ZERO))
    }

    private List<String> bodies(Long uid, String kind) {
        notificationRepo.findForUser(uid, PageRequest.of(0, 50)).findAll { it.kind == kind }*.body
    }

    private MockHttpServletRequest signedIn(Long uid) {
        def req = new MockHttpServletRequest()
        def session = new MockHttpSession()
        session.setAttribute(SteamAuthController.SESSION_USER_ID, uid)
        req.session = session
        req
    }

    // ── Auctions ──────────────────────────────────────────────────

    def "a leader raising their own bid keeps their auto-bid cap"() {
        given:
        def alice = user('alice')
        def bob = user('bob')
        def l = auction()
        bidService.placeBid(alice.id, alice.displayName, l.id, new BigDecimal('20.00'), new BigDecimal('100.00'))
        bidService.placeBid(alice.id, alice.displayName, l.id, new BigDecimal('21.00'))

        when:
        bidService.placeBid(bob.id, bob.displayName, l.id, new BigDecimal('30.00'))
        def after = listingRepo.findById(l.id).get()

        then: "Alice's cap still defends her lead"
        after.currentBidderId == alice.id
        after.currentBid > new BigDecimal('30.00')
    }

    def "the outbid notice names the price the new bidder's cap raised to"() {
        given:
        def alice = user('alice')
        def bob = user('bob')
        def l = auction()
        bidService.placeBid(alice.id, alice.displayName, l.id, new BigDecimal('20.00'), new BigDecimal('30.00'))

        when:
        bidService.placeBid(bob.id, bob.displayName, l.id, new BigDecimal('20.25'), new BigDecimal('60.00'))
        def after = listingRepo.findById(l.id).get()

        then:
        after.currentBidderId == bob.id
        after.currentBid > new BigDecimal('30.00')
        bodies(alice.id, 'AUCTION_OUTBID').any { it == "New top bid: \$${after.currentBid.toPlainString()}" }
    }

    def "cancelling an auto-bid clears the cap from every live row of yours on that auction"() {
        given:
        def alice = user('alice')
        def l = auction()
        bidService.placeBid(alice.id, alice.displayName, l.id, new BigDecimal('20.00'), new BigDecimal('50.00'))
        def top = bidService.placeBid(alice.id, alice.displayName, l.id, new BigDecimal('25.00'))

        when:
        bidService.cancelAutoBid(alice.id, top.id)

        then:
        bidRepo.findByListing(l.id).findAll { it.bidderUserId == alice.id && it.status in ['WINNING', 'OUTBID'] }
            .every { it.maxAmount == null && it.kind == 'MANUAL' }
    }

    def "a seller cannot hide an auction that has bids, and away mode leaves it visible"() {
        given:
        def seller = user('seller')
        def bidder = user('bidder')
        def withBids = auction(seller.id)
        def noBids = auction(seller.id)
        bidService.placeBid(bidder.id, bidder.displayName, withBids.id, new BigDecimal('10.00'))

        when:
        listingController.updateStall(withBids.id, [hidden: true], signedIn(seller.id))

        then:
        def e = thrown(BadRequestException)
        e.code == 'AUCTION_HAS_BIDS'

        when:
        listingService.setAwayMode(seller.id, true)

        then:
        !Boolean.TRUE.equals(listingRepo.findById(withBids.id).get().hidden)
        listingRepo.findById(noBids.id).get().hidden == true
    }

    def "market price sort places a bid-up auction by its current bid"() {
        given:
        def tag = "Pass6sort${uniq}"
        def i = itemRepo.save(new Item(name: "${tag} hat", category: 'Accessories', rarity: 'Standard',
            supply: 1, totalSold: 0, lowestPrice: new BigDecimal('1.00'), isListed: true, iconEmoji: '🎩'))
        def buyNow = listingRepo.save(new Listing(item: i, price: new BigDecimal('100.00'), listingType: 'BUY_NOW',
            status: 'ACTIVE', sellerName: 's', rarityScore: BigDecimal.ZERO))
        def bidUp = listingRepo.save(new Listing(item: i, price: new BigDecimal('1.00'), currentBid: new BigDecimal('500.00'),
            bidCount: 3, listingType: 'AUCTION', status: 'ACTIVE', sellerName: 's', rarityScore: BigDecimal.ZERO,
            expiresAt: System.currentTimeMillis() + 3_600_000L))

        expect:
        listingService.getActiveListings('price_asc', null, null, null, null, tag, null)*.id == [buyNow.id, bidUp.id]
        listingService.getActiveListings('price_desc', null, null, null, null, tag, null)*.id == [bidUp.id, buyNow.id]
    }

    // ── Buy orders ────────────────────────────────────────────────

    def "a category-only buy order with an unknown category is refused, not widened to every category"() {
        given:
        def buyer = user('buyer')

        when:
        buyOrderService.create(buyer.id, buyer.displayName, null, 'hats', null, new BigDecimal('50'), 1)

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_CATEGORY'
    }

    def "buy-order prices are rounded to cents and sub-cent prices are refused"() {
        given:
        def buyer = user('buyer')
        def i = item('bo')

        when:
        def o = buyOrderService.create(buyer.id, buyer.displayName, i.id, null, null, new BigDecimal('0.999'), 1)

        then:
        o.maxPrice == new BigDecimal('1.00')

        when:
        buyOrderService.update(buyer.id, o.id, new BigDecimal('0.004'), null)

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_PRICE'
    }

    // ── Trade protection quote ────────────────────────────────────

    def "the protection quote refuses huge exponents and out-of-range prices"() {
        expect:
        mockMvc.perform(MockMvcRequestBuilders.get('/api/trade-protection/quote').param('price', price))
            .andReturn().response.status == status

        where:
        price        | status
        '1e3000000'  | 400
        '-5'         | 400
        '0'          | 400
        '100001'     | 400
        '64.00'      | 200
    }

    // ── Seller stall ──────────────────────────────────────────────

    def "stall revenue, recent sales and the sold CSV count an auction at its winning bid"() {
        given:
        def seller = user('seller')
        def buyer = user('buyer')
        listingRepo.save(new Listing(item: item('sold'), price: new BigDecimal('1.00'), currentBid: new BigDecimal('25.00'),
            listingType: 'AUCTION', status: 'SOLD', soldAt: System.currentTimeMillis() - 60_000L,
            sellerUserId: seller.id, buyerUserId: buyer.id, sellerName: 'p6', rarityScore: BigDecimal.ZERO))

        when:
        def sales = new JsonSlurper().parseText(mockMvc.perform(
            MockMvcRequestBuilders.get("/api/listings/stall/${seller.id}/recent-sales")).andReturn().response.contentAsString)
        def csv = listingController.myStallSoldCsv(null, null, signedIn(seller.id)).body

        then:
        listingRepo.sumRevenueBySeller(seller.id) == new BigDecimal('25.00')
        listingRepo.sumRevenueBySellerSince(seller.id, 0L) == new BigDecimal('25.00')
        new BigDecimal(sales[0].price.toString()) == new BigDecimal('25.00')
        csv.contains(',AUCTION,25.00,')
    }

    // ── Followers and saved searches ──────────────────────────────

    def "followers hear nothing about a listing still waiting on its escrow deposit"() {
        given:
        def seller = user('seller')
        def fan = user('fan')
        sellerFollowService.follow(fan.id, seller.id)
        def pending = listingRepo.save(new Listing(item: item('escrow'), price: new BigDecimal('5.00'),
            listingType: 'BUY_NOW', status: 'PENDING_ESCROW', hidden: true, sellerUserId: seller.id,
            sellerName: 'p6', rarityScore: BigDecimal.ZERO))

        when:
        sellerFollowService.notifyFollowersOfNewListing(pending)

        then:
        notificationRepo.findForUser(fan.id, PageRequest.of(0, 50)).findAll { it.refId == pending.id }.isEmpty()
    }

    def "unfollow and follow again does not ping the seller twice in a day"() {
        given:
        def seller = user('seller')
        def fan = user('fan')

        when:
        sellerFollowService.follow(fan.id, seller.id)
        sellerFollowService.unfollow(fan.id, seller.id)
        sellerFollowService.follow(fan.id, seller.id)

        then:
        notificationRepo.findForUser(seller.id, PageRequest.of(0, 50))
            .findAll { it.kind == 'SELLER_FOLLOWED' && it.refId == fan.id }.size() == 1
    }

    def "one listing matching two of a user's saved searches sends one notification"() {
        given:
        def owner = user('owner')
        def i = item('match')
        savedSearchRepo.save(new SavedSearch(userId: owner.id, name: 'hats', q: "Pass6 match ${uniq}"))
        savedSearchRepo.save(new SavedSearch(userId: owner.id, name: 'cheap', q: "Pass6 match ${uniq}", maxPrice: '50'))
        def l = listingRepo.save(new Listing(item: i, price: new BigDecimal('5.00'), listingType: 'BUY_NOW',
            status: 'ACTIVE', sellerName: 'p6', rarityScore: BigDecimal.ZERO))

        when:
        savedSearchService.notifyMatchingForListing(l)

        then:
        notificationRepo.findForUser(owner.id, PageRequest.of(0, 50))
            .findAll { it.kind == 'LISTING_MATCH' && it.refId == l.id }.size() == 1
    }
}
