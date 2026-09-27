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
}
