package com.sboxmarket

import com.sboxmarket.controller.ListingController
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

/**
 * Defect pass 12: the item page's Active Listings panel.
 */
@SpringBootTest
@ActiveProfiles("test")
class DefectPassTwelveSpec extends Specification {

    @Autowired ListingController listingController
    @Autowired ItemRepository itemRepo
    @Autowired ListingRepository listingRepo

    String uniq = String.valueOf(System.nanoTime())

    def "an auction sorts at its current bid in the item's listings panel, not its start price"() {
        given:
        def it = itemRepo.save(new Item(name: "Pass12 ${uniq}", category: 'Accessories', rarity: 'Standard',
            supply: 1, totalSold: 0, lowestPrice: new BigDecimal('1.00'), isListed: true, iconEmoji: '🎩'))
        def auction = listingRepo.save(new Listing(item: it, price: new BigDecimal('1.00'), listingType: 'AUCTION',
            status: 'ACTIVE', sellerName: 'p12', rarityScore: BigDecimal.ZERO, currentBid: new BigDecimal('50.00'),
            bidCount: 3, expiresAt: System.currentTimeMillis() + 86_400_000L))
        def buyNow = listingRepo.save(new Listing(item: it, price: new BigDecimal('5.00'), listingType: 'BUY_NOW',
            status: 'ACTIVE', sellerName: 'p12', rarityScore: BigDecimal.ZERO))

        when:
        def rows = listingController.getForItem(it.id, new MockHttpServletRequest()).body

        then:
        rows*.id == [buyNow.id, auction.id]
    }
}
