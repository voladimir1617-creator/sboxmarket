package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.model.SteamDeliveryAttempt
import com.sboxmarket.model.Trade
import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamDeliveryAttemptRepository
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.TradeService
import com.sboxmarket.service.security.AdminAuthorization
import com.sboxmarket.service.security.BanGuard
import spock.lang.Specification

/**
 * A buyer who DECLINED the bot's Steam delivery offer never received the
 * item, yet the trade stayed PENDING_BUYER_CONFIRM and "Confirm receipt"
 * released the seller's payout. buyerConfirm must refuse while the trade's
 * latest bot offer ended without delivery.
 */
class BuyerConfirmDeclinedBotOfferSpec extends Specification {

    TradeRepository       tradeRepository       = Mock()
    WalletRepository      walletRepository      = Mock()
    TransactionRepository transactionRepository = Mock()
    ListingRepository     listingRepository     = Mock()
    NotificationService   notificationService   = Mock()
    BanGuard              banGuard              = Mock()
    AdminAuthorization    adminAuthorization    = Mock()
    SteamDeliveryAttemptRepository attemptRepository = Mock()
    com.sboxmarket.repository.ItemRepository itemRepository = Mock()
    com.sboxmarket.repository.SteamUserRepository steamUserRepository = Mock() {
        findAllById(_) >> []
    }
    TextSanitizer textSanitizer = Mock() {
        medium(_) >> { String s -> s ?: '' }
    }

    TradeService service = new TradeService(
        tradeRepository       : tradeRepository,
        walletRepository      : walletRepository,
        transactionRepository : transactionRepository,
        listingRepository     : listingRepository,
        notificationService   : notificationService,
        banGuard              : banGuard,
        adminAuthorization    : adminAuthorization,
        steamUserRepository   : steamUserRepository,
        textSanitizer         : textSanitizer,
        itemRepository        : itemRepository,
        steamDeliveryAttemptRepository: attemptRepository,
        autoReleaseDays       : 8L,
        sellerResponseDays    : 3L
    )

    private Trade awaitingConfirm() {
        new Trade(id: 1L, listingId: 100L, itemId: 1L, itemName: 'Wizard Hat',
            buyerUserId: 10L, buyerWalletId: 500L, sellerUserId: 20L, sellerWalletId: 600L,
            price: new BigDecimal('50.00'), feeAmount: new BigDecimal('1.00'),
            state: 'PENDING_BUYER_CONFIRM')
    }

    private static SteamDeliveryAttempt offerRow(String state) {
        new SteamDeliveryAttempt(tradeId: 1L, steamOfferId: 'offer_1', offerState: state, phase: 'POLL')
    }

    def "confirm receipt is refused when the buyer declined the bot's Steam offer (state #state)"() {
        given:
        def t = awaitingConfirm()
        tradeRepository.findById(1L) >> Optional.of(t)
        attemptRepository.findLatestWithOffer(1L, _) >> [offerRow(state)]

        when:
        service.buyerConfirm(10L, 1L)

        then: 'refused with a clear code; the seller is not paid and the trade does not move'
        def e = thrown(BadRequestException)
        e.code == 'BOT_OFFER_NOT_DELIVERED'
        t.state == 'PENDING_BUYER_CONFIRM'
        t.deliveredAt == null
        0 * walletRepository.save(_)
        0 * transactionRepository.save(_)

        where:
        state << ['declined', 'expired', 'canceled', 'invalid_items']
    }

    def "confirm receipt still releases once the bot offer was accepted"() {
        given:
        def t = awaitingConfirm()
        def sellerWallet = new Wallet(id: 600L, balance: new BigDecimal('0.00'), currency: 'USD')
        tradeRepository.findById(1L) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade x -> x }
        attemptRepository.findLatestWithOffer(1L, _) >> [offerRow('accepted')]
        walletRepository.findById(600L) >> Optional.of(sellerWallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }

        when:
        service.buyerConfirm(10L, 1L)

        then:
        t.state == 'VERIFIED'
        sellerWallet.balance == new BigDecimal('49.00')
    }
}
