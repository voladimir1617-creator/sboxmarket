package com.sboxmarket

import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.AuditService
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.StripeService
import spock.lang.Specification

/**
 * Wave 127 regression pin for the same-instant race between
 * {@link StripeService#cancelPendingWithdrawal} and
 * {@link com.sboxmarket.service.AdminService#rejectWithdrawal} on the
 * SAME withdrawal id.
 *
 * Pre-fix bug: both paths read the tx via findById, saw status=PENDING,
 * unconditionally credited wallet.balance += tx.amount, and saved.
 * On a same-instant click pair (user hits "Cancel" while an admin
 * hits "Reject") the wallet ended up double-refunded — user got their
 * money back TWICE from one withdrawal request while only the last-
 * committing path's tx.status overwrite stuck on the tx row.
 *
 * Fix: an atomic conditional UPDATE via
 * {@link TransactionRepository#claimCancelPendingWithdrawal} flips
 * PENDING→CANCELLED and returns 1 only when the row was still PENDING
 * at UPDATE time. The losing caller sees 0, bails before the wallet
 * credit, and surfaces an IllegalStateException with the latest status
 * so the SPA can render the truthful resolution.
 *
 * Same multi-actor-claim shape as wave 124
 * (ListingRepository.claimEndingSoonNotify), wave 125
 * (BuyOrderRepository.claimExpire), and wave 126
 * (sweepStalePendingDeposits multi-pod claim).
 */
class StripeServiceCancelWithdrawalRaceSpec extends Specification {

    TransactionRepository transactionRepository = Mock()
    WalletRepository      walletRepository      = Mock()
    SteamUserRepository   steamUserRepository   = Mock()
    AuditService          auditService          = Mock()
    NotificationService   notificationService   = Mock()

    StripeService service = new StripeService(
        transactionRepository: transactionRepository,
        walletRepository:      walletRepository,
        steamUserRepository:   steamUserRepository,
        auditService:          auditService,
        notificationService:   notificationService,
        secretKey:             'sk_test_replace_me',
        publishableKey:        'pk_test_replace_me',
        webhookSecret:         'whsec_replace_me',
        successUrl:            'http://localhost/ok',
        cancelUrl:             'http://localhost/cancel',
        currency:              'usd'
    )

    private Transaction makeWithdraw(long id, long walletId, BigDecimal amount, String status = 'PENDING') {
        new Transaction(
            id:        id,
            walletId:  walletId,
            type:      'WITHDRAW',
            status:    status,
            amount:    amount,
            createdAt: System.currentTimeMillis() - (5L * 60L * 1000L)
        )
    }

    // ── (1) Happy path — winning claim credits the wallet exactly once ──

    def "cancelPendingWithdrawal credits wallet when claim returns 1 (this caller won the race)"() {
        given:
        def tx = makeWithdraw(7001L, 700L, new BigDecimal('25.00'))
        def wallet = new Wallet(id: 700L, balance: new BigDecimal('40.00'), username: 'steam_76561198000000077')
        transactionRepository.findById(7001L) >> Optional.of(tx)
        walletRepository.findById(700L) >> Optional.of(wallet)
        transactionRepository.claimCancelPendingWithdrawal(7001L) >> 1
        walletRepository.save(_) >> { args -> args[0] }
        transactionRepository.save(_) >> { args -> args[0] }

        when:
        def result = service.cancelPendingWithdrawal(700L, 7001L)

        then:
        wallet.balance == new BigDecimal('65.00')
        result.status == 'CANCELLED'
        result.newBalance == new BigDecimal('65.00')
    }

    // ── (2) BUG REPRO — lost race against admin reject MUST NOT credit ──

    def "cancelPendingWithdrawal bails without crediting wallet when claim returns 0 (admin reject won the race)"() {
        given: "admin's rejectWithdrawal flipped the tx to FAILED + already credited the wallet at the same instant"
        def tx = makeWithdraw(7002L, 700L, new BigDecimal('25.00'))
        // The admin path already credited 40 + 25 = 65 BEFORE we got here
        def wallet = new Wallet(id: 700L, balance: new BigDecimal('65.00'), username: 'steam_76561198000000077')
        // ...but at findById-time our read still sees the cached PENDING row
        // (or a race-window read just before the admin's commit landed)
        transactionRepository.findById(7002L) >> Optional.of(tx)
        walletRepository.findById(700L) >> Optional.of(wallet)
        // Atomic UPDATE skips the row — it's already FAILED (admin terminal flip)
        transactionRepository.claimCancelPendingWithdrawal(7002L) >> 0

        when:
        service.cancelPendingWithdrawal(700L, 7002L)

        then:
        thrown(IllegalStateException)
        // The critical invariant — the wallet MUST NOT be credited a second time.
        // Pre-fix, this assertion failed: balance would have ended at 65 + 25 = 90,
        // double-refunding the user from one withdrawal.
        wallet.balance == new BigDecimal('65.00')
        0 * walletRepository.save(_)
        // Audit log must not fire for a cancel that didn't happen
        0 * auditService.log(AuditService.WITHDRAW_SELF_CANCELLED, _, _, _, _)
    }

    // ── (3) Atomic claim is the single status-flip path ──

    def "cancelPendingWithdrawal goes through claimCancelPendingWithdrawal exactly once on the happy path"() {
        given:
        def tx = makeWithdraw(7003L, 700L, new BigDecimal('10.00'))
        def wallet = new Wallet(id: 700L, balance: new BigDecimal('50.00'), username: 'steam_76561198000000077')
        transactionRepository.findById(7003L) >> Optional.of(tx)
        walletRepository.findById(700L) >> Optional.of(wallet)
        walletRepository.save(_) >> { args -> args[0] }
        transactionRepository.save(_) >> { args -> args[0] }

        when:
        service.cancelPendingWithdrawal(700L, 7003L)

        then:
        1 * transactionRepository.claimCancelPendingWithdrawal(7003L) >> 1
    }
}
