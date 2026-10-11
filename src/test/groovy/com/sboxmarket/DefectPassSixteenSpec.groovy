package com.sboxmarket

import com.sboxmarket.model.Trade
import com.sboxmarket.model.TradeMessage
import com.sboxmarket.repository.TradeMessageRepository
import com.sboxmarket.repository.TradeRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

/**
 * Defect pass 16: trade chat unread count.
 */
@SpringBootTest
@ActiveProfiles("test")
class DefectPassSixteenSpec extends Specification {

    @Autowired TradeRepository tradeRepo
    @Autowired TradeMessageRepository messageRepo

    def "an unread message on a cancelled trade does not keep the nav chat badge lit"() {
        given:
        long seller = 8_000_000L + (System.nanoTime() % 100_000L)
        long buyer = seller + 1
        def cancelled = tradeRepo.save(new Trade(listingId: 1L, itemId: 1L, price: BigDecimal.ONE,
            buyerUserId: buyer, sellerUserId: seller, state: 'CANCELLED'))
        def live = tradeRepo.save(new Trade(listingId: 2L, itemId: 1L, price: BigDecimal.ONE,
            buyerUserId: buyer, sellerUserId: seller, state: 'PENDING_SELLER_ACCEPT'))
        messageRepo.save(new TradeMessage(tradeId: cancelled.id, senderUserId: buyer, body: 'did you send it?'))
        messageRepo.save(new TradeMessage(tradeId: live.id, senderUserId: buyer, body: 'hello'))

        expect: 'only the live trade counts; the cancelled one has no chat button to clear it'
        messageRepo.countUnreadForUser(seller) == 1L
    }
}
