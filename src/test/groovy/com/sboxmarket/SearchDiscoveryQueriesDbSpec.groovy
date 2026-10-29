package com.sboxmarket

import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

/** The two listing queries behind the item page's price range and the sitemap's lastmod, on the real schema. */
@SpringBootTest
@ActiveProfiles("test")
class SearchDiscoveryQueriesDbSpec extends Specification {

    @Autowired ItemRepository itemRepo
    @Autowired ListingRepository listingRepo

    String uniq = String.valueOf(System.nanoTime())

    def "maxPriceForItem and latestActivityByItem skip hidden rows; max counts only ACTIVE"() {
        given:
        def item = itemRepo.save(new Item(name: "Range ${uniq}", category: 'Hats', rarity: 'Standard',
            supply: 1, totalSold: 0, lowestPrice: 3G, isListed: true, iconEmoji: '🎩'))
        def mk = { String price, String status, boolean hidden, Long listedAt, Long soldAt ->
            listingRepo.save(new Listing(item: item, sellerUserId: 1L, sellerName: 'r', price: new BigDecimal(price), status: status,
                hidden: hidden, listingType: 'BUY_NOW', listedAt: listedAt, soldAt: soldAt))
        }
        mk('3', 'ACTIVE', false, 1000L, null)
        mk('9', 'ACTIVE', false, 2000L, null)
        mk('50', 'ACTIVE', true, 9000L, null)     // hidden: neither the range nor the date
        mk('70', 'SOLD', false, 1500L, 5000L)      // sold: dates the item, not in the range

        when:
        def max = listingRepo.maxPriceForItem(item.id)
        def row = listingRepo.latestActivityByItem().find { it[0] == item.id }

        then:
        max == 9G
        listingRepo.countActiveForItem(item.id) == 2L
        row[1] == 2000L
        row[2] == 5000L
    }
}
