package com.sboxmarket

import com.sboxmarket.model.Item
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.WatchlistAlert
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.WatchlistAlertRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

/**
 * The alerts list is capped, and CANCELLED / FIRED rows are never purged.
 * An old ACTIVE alert used to fall off the end once enough newer history
 * piled up, so it kept firing with no way to see or cancel it.
 */
@SpringBootTest
@ActiveProfiles("test")
class WatchlistAlertListCapSpec extends Specification {

    @Autowired ApplicationContext ctx

    def "the capped alerts list keeps an old ACTIVE alert ahead of newer history"() {
        given:
        def items  = ctx.getBean(ItemRepository)
        def users  = ctx.getBean(SteamUserRepository)
        def alerts = ctx.getBean(WatchlistAlertRepository)
        def uniq = String.valueOf(System.nanoTime())
        def user = users.save(new SteamUser(
            steamId64: "76561198" + uniq.substring(uniq.length() - 9), displayName: "CapUser-" + uniq))
        def mkItem = { int i ->
            items.save(new Item(name: "CapItem-" + uniq + '-' + i, category: 'Hats', rarity: 'Standard',
                supply: 10, totalSold: 0, lowestPrice: new BigDecimal('9.00'), isListed: false, iconEmoji: '🎩'))
        }
        long t0 = System.currentTimeMillis() - 100_000L
        def live = alerts.save(new WatchlistAlert(userId: user.id, itemId: mkItem(0).id,
            targetPrice: new BigDecimal('5.00'), status: 'ACTIVE', createdAt: t0))
        (1..3).each { i ->
            alerts.save(new WatchlistAlert(userId: user.id, itemId: mkItem(i).id,
                targetPrice: new BigDecimal('5.00'), status: 'CANCELLED', createdAt: t0 + i * 1000L))
        }

        when: "the list is capped below the number of rows"
        def rows = alerts.findByUserIdPaged(user.id, PageRequest.of(0, 2))

        then:
        rows.size() == 2
        rows*.id.contains(live.id)
    }
}
