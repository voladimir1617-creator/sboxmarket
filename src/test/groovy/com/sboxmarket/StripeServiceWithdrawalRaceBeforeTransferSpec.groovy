package com.sboxmarket

import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.AuditService
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.StripeService
import org.springframework.orm.ObjectOptimisticLockingFailureException
import spock.lang.Specification

/**
 * Money-safety regression pin: a Wallet @Version conflict on a live-mode
 * withdrawal MUST abort BEFORE the Stripe Transfer is created — never after.
 *
 * Pre-fix bug (caught by the 2026-06-02 Stripe money-boundary audit):
 * StripeService.requestWithdrawal debited the wallet with a bare
 * walletRepository.save (Hibernate dirty-mark, NO flush), then called
 * Transfer.create — real money out — and only THEN did the deferred
 * @Version UPDATE run, at the controller's post-return flush. If a
 * concurrent wallet write (a second withdrawal, or a buy crediting this
 * seller) bumped @Version in the window, that late flush threw
 * OptimisticLockingFailureException, the outer @Transactional rolled the
 * debit back, and the balance was restored AFTER the payout had already
 * left the platform — free money out the door, deliberately triggerable.
 *
 * Fix: flush the debit (firing the @Version check + taking the row write
 * lock) immediately after the save, BEFORE Transfer.create. A race-loss
 * now throws here, before any money moves, so the rollback un-does a debit
 * that never paired with a payout.
 *
 * How this test proves the ordering WITHOUT a Stripe stub: with isLive()
 * true we make walletRepository.flush() throw the optimistic-lock failure.
 * If the flush runs before Transfer.create (fixed), that exception
 * propagates verbatim and Transfer.create is never reached — so no network
 * call, and no COMPLETED Transaction row is ever written. If the flush ran
 * after Transfer.create (the bug), execution would instead reach
 * Transfer.create (which, with a fake live key, fails and is re-wrapped as
 * IllegalStateException by requestWithdrawal's Transfer catch) — a
 * different exception type. So asserting the OptimisticLock type propagates
 * pins the flush-before-Transfer ordering.
 */
class StripeServiceWithdrawalRaceBeforeTransferSpec extends Specification {

    TransactionRepository transactionRepository = Mock()
    WalletRepository      walletRepository      = Mock()
    SteamUserRepository   steamUserRepository   = Mock()
    AuditService          auditService          = Mock()
    NotificationService   notificationService   = Mock()

    // secretKey has no "replace_me" → isLive() == true, so requestWithdrawal
    // takes the real-payout branch (debit → flush → Transfer.create).
    StripeService service = new StripeService(
        transactionRepository: transactionRepository,
        walletRepository:      walletRepository,
        steamUserRepository:   steamUserRepository,
        auditService:          auditService,
        notificationService:   notificationService,
        secretKey:             'sk_test_live_unit_fake',
        publishableKey:        'pk_test_live_unit_fake',
        webhookSecret:         'whsec_unit_fake',
        successUrl:            'http://localhost/ok',
        cancelUrl:             'http://localhost/cancel',
        currency:              'usd'
    )

    def "a @Version conflict at the debit flush aborts BEFORE Transfer.create (no payout)"() {
        given: "a payouts-enabled seller wallet with enough balance to withdraw"
        def wallet = new Wallet(
            id: 900L,
            balance: new BigDecimal('500.00'),
            username: 'steam_76561198000000900',
            payoutsEnabled: true,
            stripeConnectAccountId: 'acct_unit_test'
        )
        walletRepository.findById(900L) >> Optional.of(wallet)
        walletRepository.save(_) >> { args -> args[0] }
        // Simulate a concurrent writer having bumped @Version: the debit's
        // flush is where Hibernate would detect the stale version.
        walletRepository.flush() >> { throw new ObjectOptimisticLockingFailureException(Wallet, 900L) }

        when:
        service.requestWithdrawal(900L, new BigDecimal('100.00'), 'acct_unit_test')

        then: "the optimistic-lock failure propagates verbatim — proving the flush (and its version check) ran BEFORE Transfer.create"
        thrown(ObjectOptimisticLockingFailureException)

        and: "no COMPLETED withdrawal row is written — we bailed before the payout, so there is never a debit without a matching transfer"
        0 * transactionRepository.save(_)
    }

    def "isLive() is true for this fixture (guards against the test silently exercising dev-mode)"() {
        expect:
        service.isLive()
    }
}
