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
 * Expected invariants: each user is credited exactly gross - fee; every
 * completed deposit has its two treasury rows; and the treasury balance moves
 * by exactly the signed sum of its rows.
 *
 * Stripe is never called: the service's own seams (isLive, the verified
 * Session) are stubbed on the bean's target instance only.
 */
@SpringBootTest
@ActiveProfiles("test")
class TreasuryDepositPostingLossSpec extends Specification {

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

    def "a deposit whose after-commit treasury posting collides with another treasury write still gets its ledger rows"() {
        given: 'one PENDING \$100 live deposit, and a concurrent treasury writer (e.g. a trade release booking its fee)'
        def uniq = System.nanoTime()
        def d = makeDeposits(1, "tdc_${uniq}_", new BigDecimal('100.00'))[0]
        def tid = treasury().id
        def balBefore = treasury().balance
        def rowsBefore = treasuryRowSum()
        def locked = new CountDownLatch(1)
        def postingBlocked = new CountDownLatch(1)

        when: "the deposit commits, its deferred posting reads the treasury and parks on the UPDATE, the other write commits first"
        def pool = Executors.newFixedThreadPool(2)
        def locker = pool.submit({ ->
            tt.executeWithoutResult {
                walletRepo.findByIdForUpdate(tid).get()
                locked.countDown()
                postingBlocked.await(15, TimeUnit.SECONDS)
                ledger.postTradeFee(new BigDecimal('0.50'), -uniq, null, 'concurrent writer')
            }
            'LOCKER_OK'
        } as Callable<String>)
        locked.await(10, TimeUnit.SECONDS)
        def depositor = pool.submit({ -> complete(d) } as Callable<String>)
        def sawBlock = awaitBlockedSessions(1)
        postingBlocked.countDown()
        def lockerOutcome = locker.get(20, TimeUnit.SECONDS)
        def depositOutcome = depositor.get(20, TimeUnit.SECONDS)
        pool.shutdown()
        println "[deposit-collision] deposit=${depositOutcome} sawBlock=${sawBlock} rows=${treasuryRowsFor(d.sid)*.type}"

        then: 'the collision really happened; the user was credited exactly net'
        sawBlock
        lockerOutcome == 'LOCKER_OK'
        depositOutcome == 'OK'
        walletRepo.findById(d.wallet.id).get().balance == d.net

        and: 'the treasury still equals the sum of its rows'
        (treasury().balance - balBefore) == (treasuryRowSum() - rowsBefore)

        and: 'the deposit has its PROCESSING_COST + PROCESSING_RECOVERY pair'
        assertDepositBooked(d)
    }

}
