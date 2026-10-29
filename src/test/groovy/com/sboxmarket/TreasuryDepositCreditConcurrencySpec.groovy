package com.sboxmarket

import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.PlatformLedgerService
import com.sboxmarket.service.StripeService
import com.stripe.model.checkout.Session
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.util.AopTestUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import spock.lang.Specification

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Real threads, real database: a live-mode deposit credits the user NET and
 * then books the pass-through processor pair (PROCESSING_COST debit +
 * PROCESSING_RECOVERY credit) on the single @Version treasury row in an
 * AFTER-COMMIT transaction (PlatformLedgerService.deferOrRun, ~843). On a
 * version conflict that deferred transaction rolls back and the only trace is
 * a "margin understated by this row" log line.
 *
 * This spec pins what DOES hold under parallel deposits: each user is
 * credited exactly gross - fee; the processor pair is atomic
 * (both rows or neither, never a lone debit); and the treasury balance moves
 * by exactly the signed sum of its rows. (Whether every pair survives a
 * treasury conflict is a separate question — see the failing-spec report.)
 * The burst is kept well under half the connection pool so it measures the
 * ledger, not pool exhaustion.
 *
 * Stripe is never called: the service's own seams (isLive, the verified
 * Session) are stubbed on the bean's target instance only.
 */
@SpringBootTest
@ActiveProfiles("test")
class TreasuryDepositCreditConcurrencySpec extends Specification {

    @Autowired ApplicationContext ctx

    StripeService         stripeService
    PlatformLedgerService ledger
    WalletRepository      walletRepo
    TransactionRepository txRepo
    JdbcTemplate          jdbc
    TransactionTemplate   tt
    Object                stripeTarget

    def setup() {
        stripeService = ctx.getBean(StripeService)
        ledger        = ctx.getBean(PlatformLedgerService)
        walletRepo    = ctx.getBean(WalletRepository)
        txRepo        = ctx.getBean(TransactionRepository)
        jdbc          = ctx.getBean(JdbcTemplate)
        tt            = new TransactionTemplate(ctx.getBean(PlatformTransactionManager))
        ledger.treasury()
        stripeTarget = AopTestUtils.getUltimateTargetObject(stripeService)
        stripeTarget.metaClass.isLive = { -> true }
        stripeTarget.metaClass.assertDepositPaidAtStripe = { String sid, Transaction t ->
            def s = new Session(); s.id = sid; s.paymentIntent = "pi_${sid}".toString(); s
        }
    }

    def cleanup() {
        stripeTarget.metaClass = null
    }

    // ── helpers ────────────────────────────────────────────────────

    private Wallet treasury() { walletRepo.findByUsername(PlatformLedgerService.TREASURY_USERNAME) }

    private BigDecimal treasuryRowSum() {
        txRepo.findByWalletIdOrderByCreatedAtDesc(treasury().id).sum(BigDecimal.ZERO) { Transaction t ->
            PlatformLedgerService.REVENUE_TYPES.contains(t.type) ? t.amount :
            PlatformLedgerService.COST_TYPES.contains(t.type)    ? -t.amount : BigDecimal.ZERO
        } as BigDecimal
    }

    private List<Transaction> treasuryRowsFor(String sessionId) {
        txRepo.findByWalletIdOrderByCreatedAtDesc(treasury().id).findAll { it.stripeReference == sessionId }
    }

    private List<Map> makeDeposits(int n, String tag, BigDecimal gross) {
        def fee = ledger.depositFeeCharged(gross)
        (1..n).collect { i ->
            def w = walletRepo.save(new Wallet(username: "steam_${tag}${i}", balance: BigDecimal.ZERO, currency: 'USD'))
            def sid = "cs_test_${tag}${i}".toString()
            def tx = txRepo.save(new Transaction(walletId: w.id, type: 'DEPOSIT', status: 'PENDING',
                amount: gross, feeAmount: fee, currency: 'USD', stripeReference: sid,
                description: "Deposit \$${gross}".toString()))
            [wallet: w, sid: sid, tx: tx, fee: fee, net: gross - fee]
        }
    }

    private String complete(Map d) {
        try { stripeService.completeDeposit(d.sid); 'OK' } catch (Exception e) { e.class.simpleName }
    }

    private boolean awaitBlockedSessions(int n, long timeoutMs = 10_000) {
        long deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            def c = jdbc.queryForObject(
                'SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE BLOCKER_ID IS NOT NULL', Integer)
            if (c >= n) return true
            Thread.onSpinWait()
        }
        false
    }

    private void assertDepositBooked(Map d) {
        def tx = txRepo.findById(d.tx.id).get()
        assert tx.status == 'COMPLETED'
        assert walletRepo.findById(d.wallet.id).get().balance == d.net
        def rows = treasuryRowsFor(d.sid)
        assert rows.count { it.type == PlatformLedgerService.TYPE_PROCESSING_COST } == 1 :
            "deposit ${d.sid} has no PROCESSING_COST row — its after-commit posting was lost"
        assert rows.count { it.type == PlatformLedgerService.TYPE_PROCESSING_RECOVERY } == 1 :
            "deposit ${d.sid} has no PROCESSING_RECOVERY row — its after-commit posting was lost"
        assert rows.find { it.type == PlatformLedgerService.TYPE_PROCESSING_RECOVERY }.amount == d.fee
    }

    // ── features ───────────────────────────────────────────────────

    def "parallel live deposits: each user credited exactly net once, processor pairs atomic, treasury == its rows"() {
        given: '4 PENDING live deposits of different sizes on 4 wallets'
        def uniq = System.nanoTime()
        def deps = [new BigDecimal('100.00'), new BigDecimal('33.33'), new BigDecimal('250.00'), new BigDecimal('12.50')]
            .withIndex().collect { BigDecimal g, int i -> makeDeposits(1, "tdk_${uniq}_${i}_", g)[0] }
        def balBefore = treasury().balance
        def rowsBefore = treasuryRowSum()

        when: 'all 4 deposits complete at the same instant'
        def pool = Executors.newFixedThreadPool(4)
        def go = new CountDownLatch(1)
        def futures = deps.collect { d -> pool.submit({ -> go.await(); complete(d) } as Callable<String>) }
        go.countDown()
        def outcomes = futures.collect { it.get(20, TimeUnit.SECONDS) }
        pool.shutdown()
        println "[deposit-credit] outcomes=${outcomes.countBy { it }} pairsBooked=${deps.count { !treasuryRowsFor(it.sid).isEmpty() }}/4"

        then: 'every deposit is COMPLETED and its wallet holds exactly gross - fee '
        outcomes.every { it == 'OK' }
        deps.each { d ->
            assert txRepo.findById(d.tx.id).get().status == 'COMPLETED'
            assert walletRepo.findById(d.wallet.id).get().balance == d.net
        }

        and: 'each processor pair is all-or-nothing: never a cost without its recovery'
        deps.each { d ->
            def rows = treasuryRowsFor(d.sid)
            assert rows.size() in [0, 2]
            if (rows) {
                assert rows*.type.sort() == [PlatformLedgerService.TYPE_PROCESSING_COST,
                                              PlatformLedgerService.TYPE_PROCESSING_RECOVERY].sort()
                assert rows.find { it.type == PlatformLedgerService.TYPE_PROCESSING_RECOVERY }.amount == d.fee
            }
        }

        and: 'the treasury moved by exactly the signed sum of the rows written against it'
        (treasury().balance - balBefore) == (treasuryRowSum() - rowsBefore)
    }
}
