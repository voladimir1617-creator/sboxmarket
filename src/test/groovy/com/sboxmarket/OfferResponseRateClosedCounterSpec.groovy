package com.sboxmarket

import com.sboxmarket.model.Offer
import com.sboxmarket.repository.OfferRepository
import com.sboxmarket.service.OfferService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

/**
 * A seller who counters an offer answers it; once the counter thread ends
 * (buyer accepts or declines the counter, it lapses, the buyer raises) the
 * buyer's original goes COUNTERED -> CLOSED. The response-rate counts only
 * knew ACCEPTED / REJECTED / COUNTERED / EXPIRED, so every finished counter
 * thread dropped out of the stall's "% offer response rate", and a seller
 * who bargained a lot read as if they had ignored most offers.
 */
@SpringBootTest
@ActiveProfiles("test")
class OfferResponseRateClosedCounterSpec extends Specification {

    @Autowired ApplicationContext ctx

    def "a CLOSED counter thread counts as a seller response"() {
        given:
        def repo = ctx.getBean(OfferRepository)
        long seller = 8_000_000_000L + (System.nanoTime() % 1_000_000_000L)
        def seed = { String status ->
            repo.save(new Offer(listingId: 1L, buyerUserId: 42L, sellerUserId: seller,
                amount: new BigDecimal('5.00'), askingPrice: new BigDecimal('6.00'),
                status: status, author: 'USER'))
        }
        // Four countered threads that finished, one offer left to expire.
        4.times { seed('CLOSED') }
        seed('EXPIRED')
        def service = new OfferService(offerRepository: repo)

        expect:
        repo.countSellerEngagedTotal(seller) == 5L
        repo.countSellerResponded(seller) == 4L
        service.responseRatePct(seller) == 80.0d
    }
}
