package com.sboxmarket

import com.sboxmarket.model.Trade
import com.sboxmarket.repository.TradeRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

/**
 * The buy dialog promises: "if they have not sent it within 3 days the
 * purchase cancels itself and you are refunded in full". The auto-cancel
 * sweep picked its trades by updatedAt, which accepting the trade resets, so
 * a seller who accepted on day 2.9 got a fresh 3 days and the buyer's money
 * sat in escrow for up to ~6. The sweep queries now run on createdAt.
 */
@SpringBootTest
@ActiveProfiles("test")
class SellerWindowRunsFromPurchaseSpec extends Specification {

    static final long DAY = 24L * 60L * 60L * 1000L

    @Autowired TradeRepository tradeRepo

    def "a trade bought 4 days ago and accepted an hour ago is past the seller's 3-day window"() {
        given:
        long now = System.currentTimeMillis()
        long seller = 9_100_000L + (System.nanoTime() % 100_000L)
        def late = tradeRepo.save(new Trade(listingId: 1L, itemId: 1L, price: BigDecimal.ONE,
            buyerUserId: seller + 1, sellerUserId: seller, state: 'PENDING_SELLER_SEND',
            createdAt: now - 4 * DAY, updatedAt: now - 3_600_000L))
        def fresh = tradeRepo.save(new Trade(listingId: 2L, itemId: 1L, price: BigDecimal.ONE,
            buyerUserId: seller + 1, sellerUserId: seller, state: 'PENDING_SELLER_ACCEPT',
            createdAt: now - 3_600_000L, updatedAt: now - 3_600_000L))

        when:
        def staleIds = tradeRepo.findStaleSellerPending(now - 3 * DAY)*.id
        def warnIds  = tradeRepo.findSlowSellerUnwarned(now - DAY)*.id

        then: 'the accept an hour ago does not buy the seller another full window'
        staleIds.contains(late.id)
        !staleIds.contains(fresh.id)
        warnIds.contains(late.id)
        !warnIds.contains(fresh.id)
    }
}
