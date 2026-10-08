package com.sboxmarket

import com.sboxmarket.model.Offer
import com.sboxmarket.repository.OfferRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

/**
 * The seller's "offers waiting" badge counted their own pending counters,
 * which wait on the buyer, so countering an offer made the badge go UP.
 */
@SpringBootTest
@ActiveProfiles("test")
class OfferPendingIncomingCountSpec extends Specification {

    @Autowired OfferRepository offers

    def "a seller's own pending counter does not count as an incoming offer"() {
        given:
        long seller = 880_000_000L + (System.nanoTime() % 1_000_000L)
        long now = System.currentTimeMillis()
        def base = [listingId: 1L, buyerUserId: 5L, sellerUserId: seller,
                    askingPrice: new BigDecimal('50.00'), createdAt: now, updatedAt: now]
        offers.save(new Offer(base + [amount: new BigDecimal('30.00'), status: 'COUNTERED', author: 'USER']))
        offers.save(new Offer(base + [amount: new BigDecimal('40.00'), status: 'PENDING', author: 'SELLER']))
        offers.save(new Offer(base + [amount: new BigDecimal('35.00'), status: 'PENDING', author: 'USER']))

        expect: 'only the buyer-authored pending offer is waiting on the seller'
        offers.countPendingBySeller(seller) == 1L
    }
}
