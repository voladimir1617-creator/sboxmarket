package com.sboxmarket

import com.sboxmarket.controller.BuyOrderController
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.model.BuyOrder
import com.sboxmarket.model.Item
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.BuyOrderRepository
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.SteamUserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

/**
 * Against the real queries: a PAUSED buy order drops out of matching, the
 * public demand counts and the 30-day expiry sweep, and comes back on resume.
 */
@SpringBootTest
@ActiveProfiles("test")
class BuyOrderPauseResumeDbSpec extends Specification {

    @Autowired BuyOrderController buyOrderController
    @Autowired SteamUserRepository userRepo
    @Autowired BuyOrderRepository buyOrderRepo
    @Autowired ItemRepository itemRepo

    String uniq = String.valueOf(System.nanoTime())

    private MockHttpServletRequest signedIn(Long uid) {
        def req = new MockHttpServletRequest()
        def session = new MockHttpSession()
        session.setAttribute(SteamAuthController.SESSION_USER_ID, uid)
        req.session = session
        req
    }

    def "pause hides an order from matching, demand and expiry; resume brings it back"() {
        given:
        def tail = uniq.reverse().take(9).reverse()
        def buyer = userRepo.save(new SteamUser(steamId64: '7656119' + tail.padLeft(10, '0'),
            displayName: "Pause-${uniq}", role: 'USER'))
        def item = itemRepo.save(new Item(name: "Pause ${uniq}", category: 'Accessories', rarity: 'Standard',
            supply: 1, totalSold: 0, lowestPrice: new BigDecimal('10'), isListed: true, iconEmoji: '🎩'))
        def old = System.currentTimeMillis() - 40L * 24 * 3600 * 1000
        def o = buyOrderRepo.save(new BuyOrder(buyerUserId: buyer.id, buyerName: 'p', itemId: item.id,
            itemName: item.name, maxPrice: new BigDecimal('9'), quantity: 1, originalQuantity: 1,
            status: 'ACTIVE', createdAt: old, updatedAt: old))
        def req = signedIn(buyer.id)
        def matching = { buyOrderRepo.findMatching(item.id, 'Accessories', 'Standard', new BigDecimal('8'), PageRequest.of(0, 50))*.id }

        expect:
        o.id in matching()
        buyOrderRepo.countActiveForItem(item.id) == 1L
        buyOrderRepo.findBestBidForItem(item.id) == 9

        when:
        def paused = buyOrderController.pause(o.id, req).body

        then:
        paused.status == 'PAUSED'
        !(o.id in matching())
        buyOrderRepo.countActiveForItem(item.id) == 0L
        buyOrderRepo.findBestBidForItem(item.id) == 0  // COALESCE(MAX, 0): no live bid
        !(o.id in buyOrderRepo.findStaleActive(System.currentTimeMillis())*.id)
        buyOrderController.mine(req).body.find { it.id == o.id }.status == 'PAUSED'

        when:
        def resumed = buyOrderController.resume(o.id, req).body

        then:
        resumed.status == 'ACTIVE'
        o.id in matching()
        buyOrderRepo.countActiveForItem(item.id) == 1L
        // Resume restarts the 30-day clock, so a long pause can't expire it on the spot.
        !(o.id in buyOrderRepo.findStaleActive(System.currentTimeMillis() - 60_000L)*.id)
    }

    def "the demand count is buyers, not orders: one buyer with three orders counts once"() {
        given:
        def tail = uniq.reverse().take(9).reverse()
        def a = userRepo.save(new SteamUser(steamId64: '7656118' + tail.padLeft(10, '0'),
            displayName: "DemandA-${uniq}", role: 'USER'))
        def b = userRepo.save(new SteamUser(steamId64: '7656117' + tail.padLeft(10, '0'),
            displayName: "DemandB-${uniq}", role: 'USER'))
        def item = itemRepo.save(new Item(name: "Demand ${uniq}", category: 'Accessories', rarity: 'Standard',
            supply: 1, totalSold: 0, lowestPrice: new BigDecimal('10'), isListed: true, iconEmoji: '🎩'))
        def now = System.currentTimeMillis()
        ['5', '6', '7'].each { p ->
            buyOrderRepo.save(new BuyOrder(buyerUserId: a.id, buyerName: 'a', itemId: item.id, itemName: item.name,
                maxPrice: new BigDecimal(p), quantity: 1, originalQuantity: 1, status: 'ACTIVE', createdAt: now, updatedAt: now))
        }
        buyOrderRepo.save(new BuyOrder(buyerUserId: b.id, buyerName: 'b', itemId: item.id, itemName: item.name,
            maxPrice: new BigDecimal('4'), quantity: 1, originalQuantity: 1, status: 'ACTIVE', createdAt: now, updatedAt: now))

        expect:
        buyOrderRepo.countActiveForItem(item.id) == 2L
        buyOrderController.countForItem(item.id).body.count == 2L
        buyOrderController.countBulk(String.valueOf(item.id)).body[item.id].count == 2L
    }
}
