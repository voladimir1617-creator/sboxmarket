package com.sboxmarket

import com.sboxmarket.model.Trade
import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.AuditService
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.TradeService
import com.sboxmarket.service.security.AdminAuthorization
import com.sboxmarket.service.security.BanGuard
import spock.lang.Specification
import spock.lang.Subject

/**
 * Regression pin for the TRADE_VERIFIED audit ACTOR on the auto-release
 * sweep path.
 *
 * Bug: {@link TradeService#release} logged the TRADE_VERIFIED audit row
 * with {@code actor = t.buyerUserId} UNCONDITIONALLY. On the manual
 * {@code buyerConfirm} path that's correct — the buyer clicked Confirm
 * Receipt. But {@code sweepPendingConfirm} calls {@code release(trade,
 * true)} after {@code autoReleaseDays} of buyer SILENCE: no human acted,
 * the system released the funds. Recording that money-moving release as
 * an action the BUYER performed corrupts the actor-keyed audit trail
 * ({@code AuditService.byActor}) and the buyer's own security-activity
 * feed — a privileged escrow release falsely attributed to a user who
 * did nothing.
 *
 * The sibling system events already use the correct convention
 * (actor=null, subject=sellerUserId): TRADE_AUTO_RELEASED (the companion
 * row the same sweep tick writes), TRADE_AUTO_CANCELLED. This is the same
 * audit-attribution class the platform fixed for WITHDRAW_REQUESTED /
 * DEPOSIT_COMPLETE / TRADE_AUTO_RELEASED in earlier waves; TRADE_VERIFIED
 * on the auto path was the matching outlier.
 *
 * Fix: actor is null on auto-release, buyer on manual confirm. Subject is
 * sellerUserId in both cases (the seller is who got paid).
 */
class TradeServiceAutoReleaseAuditActorSpec extends Specification {

    TradeRepository       tradeRepository       = Mock()
    WalletRepository      walletRepository      = Mock()
    TransactionRepository transactionRepository = Mock()
    NotificationService   notificationService   = Mock()
    BanGuard              banGuard              = Mock()
    AdminAuthorization    adminAuthorization    = Mock()
    AuditService          auditService          = Mock()
    TextSanitizer         textSanitizer         = Mock() {
        medium(_) >> { String s -> s ?: '' }
    }

    @Subject
    TradeService service = new TradeService(
        tradeRepository       : tradeRepository,
        walletRepository      : walletRepository,
        transactionRepository : transactionRepository,
        notificationService   : notificationService,
        banGuard              : banGuard,
        adminAuthorization    : adminAuthorization,
        auditService          : auditService,
        textSanitizer         : textSanitizer,
        autoReleaseDays       : 8L,
        sellerResponseDays    : 3L
    )

    private Trade pendingConfirm(long id = 1L) {
        new Trade(
            id:             id,
            listingId:      100L,
            itemId:         1L,
            itemName:       'Wizard Hat',
            buyerUserId:    10L,
            buyerWalletId:  500L,
            sellerUserId:   20L,
            sellerWalletId: 600L,
            price:          new BigDecimal('50.00'),
            feeAmount:      new BigDecimal('1.00'),
            state:          'PENDING_BUYER_CONFIRM'
        )
    }

    def "auto-release sweep logs TRADE_VERIFIED with a NULL actor (system released, buyer did nothing)"() {
        given: 'a trade stale in PENDING_BUYER_CONFIRM past the auto-release window'
        def stale = pendingConfirm(7L)
        def sellerWallet = new Wallet(id: 600L, balance: new BigDecimal('0.00'), currency: 'USD')
        tradeRepository.findPendingConfirmOlderThan(_) >> [stale]
        // No PlatformTransactionManager wired → runInIsolatedTx runs the
        // release inline, so release(trade, autoRelease=true) executes fully.
        walletRepository.findById(600L) >> Optional.of(sellerWallet)
        tradeRepository.save(_) >> { Trade t -> t }
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        // Seller NOT banned → the auto-release branch (not the banned-seller
        // auto-cancel branch) is taken.
        banGuard.isBanned(20L) >> false

        when:
        service.sweepPendingConfirm()

        then: 'the trade verifies and the seller is paid'
        stale.state == 'VERIFIED'
        sellerWallet.balance == new BigDecimal('49.00')

        and: 'the TRADE_VERIFIED audit attributes the release to the SYSTEM (null actor), NOT the silent buyer'
        // Pre-fix this was logged with actor = buyerUserId (10L), falsely
        // recording the system auto-release as a buyer action.
        1 * auditService.log(AuditService.TRADE_VERIFIED, null, 20L, 7L, _)
        0 * auditService.log(AuditService.TRADE_VERIFIED, 10L, _, _, _)

        and: 'the companion system row keeps its null-actor / seller-subject shape'
        1 * auditService.log(AuditService.TRADE_AUTO_RELEASED, null, 20L, 7L, _)
    }

    def "manual buyerConfirm still logs TRADE_VERIFIED with the BUYER as actor (they clicked Confirm Receipt)"() {
        given:
        def t = pendingConfirm(9L)
        def sellerWallet = new Wallet(id: 600L, balance: new BigDecimal('0.00'), currency: 'USD')
        tradeRepository.findById(9L) >> Optional.of(t)
        walletRepository.findById(600L) >> Optional.of(sellerWallet)
        tradeRepository.save(_) >> { Trade x -> x }
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        banGuard.assertNotBanned(10L) >> {}

        when: 'the buyer confirms receipt'
        service.buyerConfirm(10L, 9L)

        then: 'the manual path correctly records the buyer as the actor; seller is the subject'
        t.state == 'VERIFIED'
        1 * auditService.log(AuditService.TRADE_VERIFIED, 10L, 20L, 9L, _)
        // The manual path never writes the system auto-release row.
        0 * auditService.log(AuditService.TRADE_AUTO_RELEASED, _, _, _, _)
    }

    def "staff force-release does not name the buyer as having verified the trade"() {
        given:
        def t = pendingConfirm(11L)
        t.state = 'DISPUTED'
        def sellerWallet = new Wallet(id: 600L, balance: new BigDecimal('0.00'), currency: 'USD')
        tradeRepository.findById(11L) >> Optional.of(t)
        walletRepository.findById(600L) >> Optional.of(sellerWallet)
        tradeRepository.save(_) >> { Trade x -> x }
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }

        when: 'admin #1 force-releases'
        service.adminRelease(1L, 11L, 'checked Steam history')

        then:
        t.state == 'VERIFIED'
        1 * auditService.log(AuditService.TRADE_VERIFIED, null, 20L, 11L, { it.contains('force-released by staff') })
        0 * auditService.log(AuditService.TRADE_VERIFIED, 10L, _, _, _)
        1 * auditService.log(AuditService.TRADE_FORCE_RELEASED, 1L, 20L, 11L, _)
    }

    def "staff force-release tells the disputing buyer staff closed it, with the staff note"() {
        given:
        def t = pendingConfirm(12L)
        t.state = 'DISPUTED'
        t.note = 'Never got it'
        def sellerWallet = new Wallet(id: 600L, balance: new BigDecimal('0.00'), currency: 'USD')
        tradeRepository.findById(12L) >> Optional.of(t)
        walletRepository.findById(600L) >> Optional.of(sellerWallet)
        tradeRepository.save(_) >> { Trade x -> x }
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }

        when:
        service.adminRelease(1L, 12L, reason)

        then: 'not "Trade verified" with no body, as if the buyer had confirmed'
        1 * notificationService.safePush(10L, 'TRADE_VERIFIED',
            'Trade closed by staff · Wizard Hat', body, 12L, '/profile?tab=trades')
        0 * notificationService.safePush(10L, 'TRADE_VERIFIED', 'Trade verified · Wizard Hat', _, _, _)

        where: 'with no staff reason the buyer\'s own dispute text is not echoed back as a staff note'
        reason                         | body
        'Steam history shows delivery' | 'Staff note: Steam history shows delivery'
        null                           | 'Staff reviewed this trade and closed it.'
    }

    def "buyer confirm keeps the plain Trade verified bell"() {
        given:
        def t = pendingConfirm(13L)
        def sellerWallet = new Wallet(id: 600L, balance: new BigDecimal('0.00'), currency: 'USD')
        tradeRepository.findById(13L) >> Optional.of(t)
        walletRepository.findById(600L) >> Optional.of(sellerWallet)
        tradeRepository.save(_) >> { Trade x -> x }
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }

        when:
        service.buyerConfirm(10L, 13L)

        then:
        1 * notificationService.safePush(10L, 'TRADE_VERIFIED', 'Trade verified · Wizard Hat', null, 13L, '/profile?tab=trades')
    }
}
