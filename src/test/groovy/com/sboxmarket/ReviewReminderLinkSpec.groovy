package com.sboxmarket

import com.sboxmarket.model.Trade
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.TradeService
import com.sboxmarket.service.security.BanGuard
import spock.lang.Specification

/**
 * "How did the trade go? Leave a review for the seller" opened the bare stall,
 * where the review form sits below the seller's whole listing grid. The stall
 * already opens the form on a trade with ?leaveReview=<tradeId>.
 */
class ReviewReminderLinkSpec extends Specification {

    TradeRepository tradeRepository = Mock() { save(_) >> { Trade t -> t } }
    WalletRepository walletRepository = Mock()
    NotificationService notificationService = Mock()

    TradeService service = new TradeService(
        tradeRepository: tradeRepository,
        walletRepository: walletRepository,
        transactionRepository: Mock(TransactionRepository),
        notificationService: notificationService,
        banGuard: Mock(BanGuard)
    )

    def "the review reminder opens the review form for the verified trade"() {
        given:
        def t = new Trade(id: 2L, listingId: 252L, itemName: 'Grand Ball Gown', buyerUserId: 3L, sellerUserId: 2L,
                sellerWalletId: 600L, price: new BigDecimal('230.00'), feeAmount: new BigDecimal('4.60'),
                state: 'PENDING_BUYER_CONFIRM')
        tradeRepository.findById(2L) >> Optional.of(t)
        walletRepository.findById(600L) >> Optional.of(new Wallet(id: 600L, balance: BigDecimal.ZERO, currency: 'USD'))
        walletRepository.save(_) >> { Wallet w -> w }
        String path = null

        when:
        service.buyerConfirm(3L, 2L)

        then:
        1 * notificationService.safePush(3L, 'REVIEW_REMINDER', _, _, 2L, _) >> { args -> path = args[5]; null }
        path == '/stall/2?leaveReview=2'
    }
}
