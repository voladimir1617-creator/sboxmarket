package com.sboxmarket

import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Trade
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.SteamDeliveryAttemptRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.AuditService
import com.sboxmarket.service.EmailService
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.SteamDeliveryService
import com.sboxmarket.service.TradeService
import com.sboxmarket.service.security.BanGuard
import spock.lang.Specification

/**
 * When our bot's delivery offer is accepted in Steam, SteamDeliveryService
 * releases the sale. It used to do that through
 * TradeService.buyerConfirm(buyerUserId, ...) "on the buyer's behalf", so
 *   - the TRADE_VERIFIED audit row named the BUYER as actor, though the buyer
 *     pressed nothing (the 8-day auto-release already records actor = null), and
 *   - the seller was emailed "The buyer confirmed receipt of X".
 * Seen live on trade #1 of the pass-96 run (fee-path findings, B1).
 * The money moved must stay exactly the same.
 */
class BotAutoVerifyAttributionSpec extends Specification {

    TradeRepository tradeRepository = Mock() { save(_) >> { Trade t -> t } }
    WalletRepository walletRepository = Mock()
    AuditService auditService = Mock()
    EmailService emailService = Mock()
    SteamUserRepository steamUserRepository = Mock()

    TradeService tradeService = new TradeService(
        tradeRepository: tradeRepository,
        walletRepository: walletRepository,
        transactionRepository: Mock(TransactionRepository),
        notificationService: Mock(NotificationService),
        auditService: auditService,
        emailService: emailService,
        steamUserRepository: steamUserRepository,
        banGuard: Mock(BanGuard)
    )
    SteamDeliveryService delivery = new SteamDeliveryService(
        tradeService: tradeService,
        tradeRepository: tradeRepository,
        attemptRepository: Mock(SteamDeliveryAttemptRepository)
    )

    def "a bot-verified trade is not recorded or emailed as the buyer's confirmation"() {
        given:
        def t = new Trade(id: 1L, listingId: 86L, itemName: 'Scientist Respirator', buyerUserId: 3L, sellerUserId: 2L,
                sellerWalletId: 3L, price: new BigDecimal('10.37'), feeAmount: new BigDecimal('0.21'),
                state: 'PENDING_BUYER_CONFIRM')
        tradeRepository.findById(1L) >> Optional.of(t)
        def sellerWallet = new Wallet(id: 3L, balance: BigDecimal.ZERO, currency: 'USD')
        walletRepository.findById(3L) >> Optional.of(sellerWallet)
        walletRepository.save(_) >> { Wallet w -> w }
        def seller = new SteamUser(id: 2L, displayName: 'AtlasTrades', email: 'seller@example.com')
        steamUserRepository.findById(2L) >> Optional.of(seller)
        emailService.canSendTo(seller, 'TRADES') >> true
        Long actor = -1L
        String summary = null

        when:
        delivery.advanceToVerified(t)

        then:
        1 * auditService.log(AuditService.TRADE_VERIFIED, _, 2L, 1L, _) >> { args -> actor = args[1]; summary = args[4]; null }
        1 * emailService.sendSaleCompleted('seller@example.com', 'AtlasTrades', 'Scientist Respirator',
                new BigDecimal('10.16'), '/wallet', null, false, true)
        0 * emailService.sendSaleCompleted(_, _, _, _, _, _, _)
        t.state == 'VERIFIED'
        t.deliveredAt != null

        and: 'no actor, like the 8-day auto-release; the buyer pressed nothing'
        actor == null
        summary.contains('verified by the Steam bot')

        and: 'money is unchanged: the seller gets price minus fee, once'
        sellerWallet.balance == new BigDecimal('10.16')
    }

    def "the seller email says the Steam bot verified the delivery, not that the buyer confirmed"() {
        given:
        def mail = Mock(org.springframework.mail.javamail.JavaMailSender)
        mail.createMimeMessage() >> { new jakarta.mail.internet.MimeMessage((jakarta.mail.Session) null) }
        def svc = new EmailService(mailSender: mail, smtpHost: 'smtp.example.com', fromAddress: 'no-reply@skinbox.local',
                fromName: 'SkinBox', replyToAddress: 'support@skinbox.market',
                unsubscribeSecret: 'test-unsubscribe-secret', publicUrl: 'http://localhost:8080')
        svc.init()
        String text = null

        when:
        svc.sendSaleCompleted('seller@example.com', 'Sam', 'Wizard Hat', new BigDecimal('10.16'), '/wallet', null, false, true)
        svc.awaitSmtpForTests()

        then:
        1 * mail.send(_ as jakarta.mail.internet.MimeMessage) >>{ args -> text = args[0].content.toString(); null }
        text.contains('Our Steam bot verified that the buyer received Wizard Hat')
        !text.contains('The buyer confirmed receipt')
        text.contains('$10.16')
    }
}
