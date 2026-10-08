package com.sboxmarket

import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.WatchlistAlert
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.WatchlistAlertRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

/**
 * A price alert must only fire when something is actually for sale here.
 * For an item with no listings the Steam price sync writes the Steam market
 * price into Item.lowestPrice (until the floor refresh zeroes it), so a
 * "notify when listed" or sold-out alert used to fire on a price nobody on
 * the site was asking.
 */
@SpringBootTest
@ActiveProfiles("test")
class WatchlistAlertListedOnlySpec extends Specification {

    @Autowired ApplicationContext ctx

    def "an alert does not fire on an unlisted item's Steam price, and does once it is listed"() {
        given:
        def items  = ctx.getBean(ItemRepository)
        def users  = ctx.getBean(SteamUserRepository)
        def alerts = ctx.getBean(WatchlistAlertRepository)
        def uniq = String.valueOf(System.nanoTime())
        def user = users.save(new SteamUser(
            steamId64: "76561197" + uniq.substring(uniq.length() - 9), displayName: "AlertUser-" + uniq))
        def item = items.save(new Item(
            name: "AlertItem-" + uniq, category: 'Hats', rarity: 'Standard', supply: 10, totalSold: 0,
            lowestPrice: new BigDecimal('4.00'), isListed: false, iconEmoji: '🎩'))
        alerts.save(new WatchlistAlert(userId: user.id, itemId: item.id, targetPrice: new BigDecimal('5.00')))

        expect: "Steam price under the target, but nothing listed: no alert"
        alerts.findTriggeredForItem(item.id).isEmpty()

        when: "a real listing makes the item listed at that floor"
        // A real ACTIVE row, so the scheduled floor refresh agrees with
        // isListed=true instead of racing the assertion back to false.
        ctx.getBean(ListingRepository).save(new Listing(item: item, price: new BigDecimal('4.00'),
            status: 'ACTIVE', sellerName: "AlertSeller-" + uniq, rarityScore: BigDecimal.ZERO))
        item.isListed = true
        item.lowestPrice = new BigDecimal('4.00')
        items.save(item)

        then:
        alerts.findTriggeredForItem(item.id).size() == 1
    }
}
