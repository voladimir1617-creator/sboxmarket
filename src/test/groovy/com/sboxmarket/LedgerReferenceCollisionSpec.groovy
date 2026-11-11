package com.sboxmarket

import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.PlatformLedgerService
import com.sboxmarket.service.StripeService
import com.stripe.model.Transfer
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
 * Real database. PlatformLedgerService books the pass-through processor pair
 * (PROCESSING_COST + PROCESSING_RECOVERY) with stripeReference set to the SAME
 * id the user-side row carries: the Checkout Session id for a deposit
 * (StripeService.completeDeposit ~3451) and the Transfer id for a withdrawal
 * (requestWithdrawal ~2161). Every later lookup of that user row goes through
 * the single-result TransactionRepository.findByStripeReference, which then
 * sees three rows.
 */
@SpringBootTest
@ActiveProfiles("test")
class LedgerReferenceCollisionSpec extends Specification {

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

    def "a deposit confirmed again after its ledger pair is booked is a quiet no-op (webhook after success page)"() {
        given: 'a live deposit completed by the success-page confirm; its treasury pair is booked after commit'
        def uniq = System.nanoTime()
        def d = makeDeposits(1, "lrc_${uniq}_", new BigDecimal('100.00'))[0]
        stripeService.completeDeposit(d.sid)
        assert treasuryRowsFor(d.sid).size() == 2

        when: "Stripe's checkout.session.completed for the same session arrives (or the user reloads the success page)"
        def outcome = complete(d)

        then: 'the replay is a no-op, not an error, and the user is still credited exactly once'
        outcome == 'OK'
        walletRepo.findById(d.wallet.id).get().balance == d.net
    }

    def "a reversed live payout is re-credited even though its treasury pair shares the transfer id"() {
        given: 'a completed live withdrawal: wallet debited \$100, WITHDRAW row on tr_x, payout pair booked on tr_x'
        def uniq = System.nanoTime()
        def trId = "tr_lrc_${uniq}".toString()
        def w = walletRepo.save(new Wallet(username: "steam_lrcw_${uniq}", balance: BigDecimal.ZERO, currency: 'USD'))
        def fee = ledger.payoutFeeCharged(new BigDecimal('100.00'))
        def wd = txRepo.save(new Transaction(walletId: w.id, type: 'WITHDRAW', status: 'COMPLETED',
            amount: new BigDecimal('100.00'), feeAmount: fee, currency: 'USD', stripeReference: trId,
            description: 'Withdrawal to bank'))
        // exactly the call requestWithdrawal makes after the Transfer succeeds
        ledger.postPassThroughProcessing(ledger.estimatePayoutCost(new BigDecimal('100.00')), fee, trId,
            'Estimated payout cost on $100.00 withdrawal', 'Payout fee recovered from user on $100.00 withdrawal')
        assert treasuryRowsFor(trId).size() == 2

        when: 'Stripe reverses the whole transfer (closed bank account) and sends transfer.reversed'
        def t = new Transfer(); t.id = trId; t.amount = 10000L; t.amountReversed = 10000L
        def outcome
        try { stripeService.handleTransferReversed(t); outcome = 'OK' } catch (Exception e) { outcome = e.class.simpleName }

        then: "the user's \$100 comes back to their wallet and the withdrawal is marked reversed"
        outcome == 'OK'
        walletRepo.findById(w.id).get().balance == new BigDecimal('100.00')
        txRepo.findById(wd.id).get().status != 'COMPLETED'
    }
}
