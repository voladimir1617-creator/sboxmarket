package com.sboxmarket

import com.sboxmarket.controller.ListingController
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Trade
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.NotificationRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.service.AdminService
import com.sboxmarket.service.BuyOrderService
import com.sboxmarket.service.ZeroResultSearchTracker
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

/**
 * Against the real wiring and queries: a buy order that doesn't fill pings
 * the sellers above it, an empty search lands in the staff tally, and the
 * seller-revenue queries behind the top-5 share tile add up.
 */
@SpringBootTest
@ActiveProfiles("test")
class SellerMarketSignalsDbSpec extends Specification {

    @Autowired BuyOrderService buyOrderService
    @Autowired ListingController listingController
    @Autowired ZeroResultSearchTracker tracker
    @Autowired SteamUserRepository userRepo
    @Autowired ItemRepository itemRepo
    @Autowired ListingRepository listingRepo
    @Autowired NotificationRepository notificationRepo
    @Autowired TradeRepository tradeRepo
    @Autowired AdminService adminService

    String uniq = String.valueOf(System.nanoTime())

    private SteamUser user(String tag) {
        def tail = (uniq + tag.hashCode().abs()).reverse().take(10).reverse()
        userRepo.save(new SteamUser(steamId64: '7656119' + tail.padLeft(10, '0'), displayName: "${tag}-${uniq}", role: 'USER',
            tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc'))
    }

    private List kinds(Long uid) {
        notificationRepo.findForUser(uid, PageRequest.of(0, 50))*.kind
    }

    def "a buy order below every ask pings each seller above it once"() {
        given:
        def buyer = user('buyer')
        def s1 = user('s1')
        def s2 = user('s2')
        def item = itemRepo.save(new Item(name: "Ping ${uniq}", category: 'Hats', rarity: 'Standard',
            supply: 1, totalSold: 0, lowestPrice: 20G, isListed: true, iconEmoji: '🎩'))
        [s1, s2].each { s ->
            listingRepo.save(new Listing(item: item, sellerUserId: s.id, sellerName: s.displayName,
                price: 20G, status: 'ACTIVE', listingType: 'BUY_NOW'))
        }

        when:
        buyOrderService.create(buyer.id, buyer.displayName, item.id, null, null, 5G, 1)
        buyOrderService.create(buyer.id, buyer.displayName, item.id, null, null, 6G, 1)

        then:
        kinds(s1.id).count('BUY_ORDER_ON_YOUR_ITEM') == 1
        kinds(s2.id).count('BUY_ORDER_ON_YOUR_ITEM') == 1
        !kinds(buyer.id).contains('BUY_ORDER_ON_YOUR_ITEM')
    }

    def "a first-page search with no results lands in the staff tally and the dashboard"() {
        given:
        def term = "nothing-sells-${uniq}"
        def req = new MockHttpServletRequest()
        req.remoteAddr = '10.0.0.9'

        when:
        def first = listingController.getListings('price_asc', 'All', 'All', null, null, term, null, 'All', 100, 0, req)
        listingController.getListings('price_asc', 'All', 'All', null, null, term, null, 'All', 100, 0, req)
        listingController.getListings('price_asc', 'All', 'All', null, null, term, null, 'All', 24, 24, req)

        then:
        first.body == []
        tracker.top(ZeroResultSearchTracker.MAX_TERMS).find { it.term == term }?.count == 1L
        adminService.dashboardStats().containsKey('zeroResultSearches')
    }

    def "seller revenue queries rank by revenue and sum only VERIFIED trades in the window"() {
        given:
        // Settled far in the future so no other spec's trades fall in the window.
        long at = System.currentTimeMillis() + 3650L * 86_400_000L
        def mk = { Long seller, String price, String state ->
            tradeRepo.save(new Trade(listingId: 1L, itemId: 1L, itemName: 'x', buyerUserId: 1L, sellerUserId: seller,
                price: new BigDecimal(price), state: state, settledAt: at))
        }
        mk(901L, '10', 'VERIFIED'); mk(901L, '10', 'VERIFIED')
        mk(902L, '50', 'VERIFIED')
        mk(903L, '5', 'VERIFIED')
        mk(904L, '999', 'CANCELLED')

        when:
        def rows = tradeRepo.findSellerRevenueSince(at - 1, PageRequest.of(0, 2))
        def total = tradeRepo.sumVerifiedRevenueSince(at - 1)

        then:
        rows*.sellerUserId == [902L, 901L]
        (rows*.totalRevenue).collect { it as BigDecimal } == [50G, 20G]
        total == 75G
        AdminService.sharePercent(total, rows) == 93.3G
    }

    def "sharePercent is null with no volume and never above 100"() {
        expect:
        AdminService.sharePercent(null, []) == null
        AdminService.sharePercent(0G, [[totalRevenue: 5G]]) == null
        AdminService.sharePercent(10G, []) == 0.0G
        AdminService.sharePercent(10G, [[totalRevenue: 12G]]) == 100G
    }
}
