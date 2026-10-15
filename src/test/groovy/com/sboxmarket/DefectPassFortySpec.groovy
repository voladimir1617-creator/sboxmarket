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
 * Defect pass 40: the homepage 24h / 7d volume summed each sold listing's
 * `price`, which on a settled auction is still the starting bid. An auction
 * that opened at $1 and sold for $50 added $1 to the volume.
 */
@SpringBootTest
@ActiveProfiles("test")
class DefectPassFortySpec extends Specification {

    @Autowired ItemRepository itemRepo
    @Autowired ListingRepository listingRepo

    def "sold volume counts a settled auction at its winning bid"() {
        given:
        // A window far in the future so no other spec's sales fall inside it.
        long since = System.currentTimeMillis() + 3_650L * 86_400_000L
        def it = itemRepo.save(new Item(name: "Pass40 ${System.nanoTime()}", category: 'Accessories',
            rarity: 'Standard', supply: 1, totalSold: 1, lowestPrice: new BigDecimal('1.00'),
            isListed: false, iconEmoji: '🎩'))
        listingRepo.save(new Listing(item: it, price: new BigDecimal('1.00'), currentBid: new BigDecimal('50.00'),
            listingType: 'AUCTION', status: 'SOLD', sellerName: 'p40', sellerUserId: 4001L, buyerUserId: 4002L,
            soldAt: since + 1000L, rarityScore: BigDecimal.ZERO))
        listingRepo.save(new Listing(item: it, price: new BigDecimal('3.00'),
            listingType: 'BUY_NOW', status: 'SOLD', sellerName: 'p40', sellerUserId: 4001L, buyerUserId: 4003L,
            soldAt: since + 2000L, rarityScore: BigDecimal.ZERO))

        expect:
        listingRepo.sumVolumeAfter(since) == new BigDecimal('53.00')
    }
}
