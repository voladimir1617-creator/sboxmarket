package com.sboxmarket

import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.WatchlistAlert
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.WatchlistAlertRepository
import com.sboxmarket.service.WatchlistAlertService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

/**
 * The scheduled sweep runs outside a transaction and claims each alert with
 * a @Modifying UPDATE. Without a transaction of its own that UPDATE threw,
 * the per-row catch swallowed it, and no price alert ever fired from the
 * sweep. The unit specs mock the claim, so only a real run catches this.
 */
@SpringBootTest
@ActiveProfiles("test")
class WatchlistAlertSweepFiresSpec extends Specification {

    @Autowired ItemRepository items
    @Autowired ListingRepository listings
    @Autowired SteamUserRepository users
    @Autowired WatchlistAlertRepository alerts
    @Autowired WatchlistAlertService service

    def "the sweep fires an alert whose listed floor is under the target"() {
        given:
        def uniq = String.valueOf(System.nanoTime())
        def user = users.save(new SteamUser(
            steamId64: "76561198" + uniq.substring(uniq.length() - 9), displayName: "SweepUser-" + uniq))
        def item = items.save(new Item(
            name: "SweepItem-" + uniq, category: 'Hats', rarity: 'Standard', supply: 1, totalSold: 0,
            lowestPrice: new BigDecimal('4.00'), isListed: true, iconEmoji: '🎩'))
        listings.save(new Listing(item: item, price: new BigDecimal('4.00'), status: 'ACTIVE',
            sellerName: "SweepSeller-" + uniq, rarityScore: BigDecimal.ZERO))
        def alert = alerts.save(new WatchlistAlert(userId: user.id, itemId: item.id, targetPrice: new BigDecimal('5.00')))

        when:
        service.sweep()

        then:
        alerts.findById(alert.id).get().status == 'FIRED'
    }
}
