package com.sboxmarket

import com.sboxmarket.model.BuyOrder
import com.sboxmarket.model.Item
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.BuyOrderRepository
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.BuyOrderService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

/**
 * Defect pass 32: the buy-order "#N in queue" figure ignored category /
 * rarity basket orders, which the matcher fills first when they rank higher.
 */
@SpringBootTest
@ActiveProfiles("test")
class DefectPassThirtyTwoSpec extends Specification {

    @Autowired BuyOrderService buyOrderService
    @Autowired BuyOrderRepository buyOrderRepo
    @Autowired ItemRepository itemRepo
    @Autowired SteamUserRepository userRepo

    static int seq = 0
    String uniq = String.valueOf(System.nanoTime())

    private SteamUser user() {
        seq++
        def tail = (uniq + seq).reverse().take(9).reverse()
        userRepo.save(new SteamUser(steamId64: '7656119' + tail.padLeft(10, '0'),
            displayName: "Pass32-${seq}-${uniq}", role: 'USER'))
    }

    def "a higher basket order that fits the item counts ahead in the queue; one that does not fit is ignored"() {
        given:
        String cat = "P32Cat-${uniq}"
        def item = itemRepo.save(new Item(name: "Pass32 ${uniq}", category: cat, rarity: 'Limited',
            supply: 0, totalSold: 0, lowestPrice: BigDecimal.ZERO, isListed: false, iconEmoji: '🎩'))
        def mine = new BigDecimal('45.00')
        long now = System.currentTimeMillis()
        long before = buyOrderService.countAheadInQueue(item.id, mine, now)

        when: 'another buyer posts a category order for this item type, priced higher'
        buyOrderRepo.save(new BuyOrder(buyerUserId: user().id, itemName: 'Basket', category: cat,
            rarity: null, maxPrice: new BigDecimal('50.00'), quantity: 1, originalQuantity: 1,
            status: 'ACTIVE', createdAt: now - 1000L, updatedAt: now - 1000L))
        // ...and one for a different category, which the matcher would never use here
        buyOrderRepo.save(new BuyOrder(buyerUserId: user().id, itemName: 'Other basket', category: cat + '-x',
            rarity: null, maxPrice: new BigDecimal('60.00'), quantity: 1, originalQuantity: 1,
            status: 'ACTIVE', createdAt: now - 1000L, updatedAt: now - 1000L))

        then:
        buyOrderService.countAheadInQueue(item.id, mine, now) == before + 1
    }
}
