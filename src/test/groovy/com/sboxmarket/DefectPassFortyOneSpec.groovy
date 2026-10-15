package com.sboxmarket

import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

/**
 * Defect pass 41: the homepage "live auctions" count included auctions in
 * stalls set to Away, which the auction grid it links to does not show.
 */
@SpringBootTest
@ActiveProfiles("test")
class DefectPassFortyOneSpec extends Specification {

    @Autowired ItemRepository itemRepo
    @Autowired ListingRepository listingRepo

    def "a hidden live auction is not counted as running"() {
        given:
        long now = System.currentTimeMillis()
        def it = itemRepo.save(new Item(name: "Pass41 ${System.nanoTime()}", category: 'Accessories',
            rarity: 'Standard', supply: 1, totalSold: 0, lowestPrice: new BigDecimal('2.00'),
            isListed: true, iconEmoji: '🎩'))
        def before = listingRepo.countActiveAuctions(now)

        when:
        listingRepo.save(new Listing(item: it, price: new BigDecimal('2.00'), listingType: 'AUCTION',
            status: 'ACTIVE', hidden: true, expiresAt: now + 86_400_000L, sellerName: 'p41',
            sellerUserId: 4101L, rarityScore: BigDecimal.ZERO))

        then:
        listingRepo.countActiveAuctions(now) == before
    }
}
