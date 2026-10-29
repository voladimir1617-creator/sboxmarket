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
 * Pass 96: a buyer with two finished trades got two identical
 * "How did the trade go? / Leave a review for the seller" bell rows,
 * with nothing saying which purchase each one was about.
 */
class ReviewReminderNamesTradeSpec extends Specification {

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

    def "the review reminder names the item the buyer is asked to review"() {
        given:
        def t = new Trade(id: 2L, listingId: 70L, itemName: 'Sports Bandage', buyerUserId: 3L, sellerUserId: 1L,
                sellerWalletId: 600L, price: new BigDecimal('1.10'), feeAmount: new BigDecimal('0.02'),
                state: 'PENDING_BUYER_CONFIRM')
        tradeRepository.findById(2L) >> Optional.of(t)
        walletRepository.findById(600L) >> Optional.of(new Wallet(id: 600L, balance: BigDecimal.ZERO, currency: 'USD'))
        walletRepository.save(_) >> { Wallet w -> w }
        String title = null, body = null

        when:
        service.buyerConfirm(3L, 2L)

        then:
        1 * notificationService.safePush(3L, 'REVIEW_REMINDER', _, _, 2L, _) >> { args -> title = args[2]; body = args[3]; null }
        "${title} ${body}".contains('Sports Bandage')
    }
}
