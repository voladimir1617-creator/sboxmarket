package com.sboxmarket

import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.StripeService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.core.env.Environment
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.util.AopTestUtils
import spock.lang.Specification

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Real threads, real database: N parallel withdrawal requests against ONE
 * wallet, where only some of them fit the balance.
 *
 * Runs the dev-mode (SIMULATED) withdrawal path of the real
 * {@link StripeService#requestWithdrawal} bean: the test profile's
 * {@code sk_test_replace_me} key classifies as SIMULATED, so no Stripe API is
 * ever called. The fabricated-payout door additionally needs the
 * {@code SBOX_DEV_CREDIT_ENABLED=true} opt-in in the PROCESS environment
 * (DevCreditGate); the spec hands the bean an Environment carrying exactly
 * that (SpecEnvs.creditOptedIn) for the duration of each feature and restores
 * the original in cleanup.
 *
 * The balance check in requestWithdrawal reads the wallet unlocked and the
 * debit relies on the wallet's @Version (flushed before any payout). The money
 * invariants that must hold whatever the interleaving:
 *
 *  - the balance never goes negative;
 *  - the total withdrawn (sum of COMPLETED WITHDRAW rows) never exceeds the
 *    starting balance;
 *  - balance == deposits - withdrawals (the wallet matches its ledger rows);
 *  - every request that returned OK has exactly one WITHDRAW row, and no
 *    refused request left one behind.
 */
@SpringBootTest
@ActiveProfiles("test")
class ParallelWithdrawalConcurrencySpec extends Specification {

    @Autowired ApplicationContext ctx

    StripeService         stripeService
    StripeService         stripeTarget
    WalletRepository      walletRepo
    TransactionRepository txRepo
    SteamUserRepository   userRepo
    Environment           originalEnv

    def setup() {
        stripeService = ctx.getBean(StripeService)
        stripeTarget  = AopTestUtils.getUltimateTargetObject(stripeService) as StripeService
        walletRepo    = ctx.getBean(WalletRepository)
        txRepo        = ctx.getBean(TransactionRepository)
        userRepo      = ctx.getBean(SteamUserRepository)
        originalEnv   = stripeTarget.environment
        // The operator's dev-credit opt-in, as it would be exported at a shell.
        stripeTarget.environment = SpecEnvs.creditOptedIn(['test'])
    }

    def cleanup() {
        stripeTarget.environment = originalEnv
    }

    // ── helpers ────────────────────────────────────────────────────

    private Wallet fundedWallet(String tag, BigDecimal start) {
        userRepo.save(new SteamUser(steamId64: tag, displayName: "W ${tag}",
            email: "${tag}@example.com", emailVerified: true))
        def w = walletRepo.save(new Wallet(username: "steam_${tag}", balance: start, currency: 'USD'))
        txRepo.save(new Transaction(walletId: w.id, type: 'DEPOSIT', status: 'COMPLETED', amount: start,
            currency: 'USD', stripeReference: "seed_${tag}", description: 'seed deposit'))
        w
    }

    private List<Transaction> withdrawRows(Long walletId) {
        txRepo.findByWalletIdOrderByCreatedAtDesc(walletId).findAll { it.type == 'WITHDRAW' }
    }

    private BigDecimal withdrawn(Long walletId) {
        withdrawRows(walletId).findAll { it.status == 'COMPLETED' }.sum(BigDecimal.ZERO) { it.amount } as BigDecimal
    }

    /** Signed sum of the wallet's ledger rows. */
    private BigDecimal rowSum(Long walletId) {
        txRepo.findByWalletIdOrderByCreatedAtDesc(walletId).sum(BigDecimal.ZERO) { Transaction t ->
            t.type == 'DEPOSIT' ? t.amount :
            (t.type == 'WITHDRAW' && t.status in ['COMPLETED', 'PENDING']) ? -t.amount : BigDecimal.ZERO
        } as BigDecimal
    }

    private String attempt(Long walletId, BigDecimal amount) {
        try { stripeService.requestWithdrawal(walletId, amount, 'race-test'); 'OK' }
        catch (Exception e) { e.class.simpleName }
    }

    private List<String> burst(Long walletId, List<BigDecimal> amounts) {
        def pool = Executors.newFixedThreadPool(amounts.size())
        def ready = new CountDownLatch(amounts.size())
        def go = new CountDownLatch(1)
        def futures = amounts.collect { BigDecimal a ->
            pool.submit({ -> ready.countDown(); go.await(); attempt(walletId, a) } as Callable<String>)
        }
        ready.await(10, TimeUnit.SECONDS)
        go.countDown()
        def out = futures.collect { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()
        out
    }

    private void assertInvariants(Long walletId, BigDecimal start) {
        def w = walletRepo.findById(walletId).get()
        assert w.balance >= BigDecimal.ZERO
        assert withdrawn(walletId) <= start
        assert w.balance == start - withdrawn(walletId)
        assert w.balance == rowSum(walletId)
    }

    // ── features ───────────────────────────────────────────────────

    def "the dev-mode withdrawal path is the one under test (no Stripe, opt-in granted)"() {
        expect:
        !stripeService.isLive()
        stripeService.devCreditAuthorized()
    }

    def "12 parallel \$30 withdrawals from a \$100 wallet: never negative, never more than the balance, ledger agrees"() {
        given:
        def start = new BigDecimal('100.00')
        def w = fundedWallet("wdr_${System.nanoTime()}", start)
        def amt = new BigDecimal('30.00')

        when: 'twelve requests race; only three could ever fit'
        def out = burst(w.id, (1..12).collect { amt })
        println "[withdraw-burst-equal] outcomes=${out.countBy { it }} balance=${walletRepo.findById(w.id).get().balance}"

        then: 'at most three succeed and every success has exactly one row'
        def oks = out.count('OK')
        oks >= 1 && oks <= 3
        withdrawRows(w.id).size() == oks
        withdrawRows(w.id).every { it.amount == amt && it.status == 'COMPLETED' }
        out.findAll { it != 'OK' }.every {
            it in ['IllegalStateException', 'ObjectOptimisticLockingFailureException',
                   'PessimisticLockingFailureException', 'CannotAcquireLockException']
        }
        assertInvariants(w.id, start)

        when: 'the losers retry one at a time until the wallet refuses'
        12.times { attempt(w.id, amt) }

        then: 'exactly three withdrawals ever land: $90 out, $10 left, rows agree'
        withdrawRows(w.id).size() == 3
        withdrawn(w.id) == new BigDecimal('90.00')
        walletRepo.findById(w.id).get().balance == new BigDecimal('10.00')
        assertInvariants(w.id, start)
    }

    def "mixed-size parallel withdrawals (some fit, some do not) keep the wallet whole"() {
        given:
        def start = new BigDecimal('250.00')
        def w = fundedWallet("wdm_${System.nanoTime()}", start)
        def amounts = ['120.00', '80.00', '75.50', '60.00', '45.25', '200.00', '30.00', '250.00',
                       '15.75', '99.99', '10.00', '130.00', '55.55', '5.00', '240.00', '1.00']
                      .collect { new BigDecimal(it) }

        when: 'sixteen requests of different sizes race'
        def out = burst(w.id, amounts)
        def succeeded = [amounts, out].transpose().findAll { it[1] == 'OK' }.collect { it[0] }
        println "[withdraw-burst-mixed] outcomes=${out.countBy { it }} succeeded=${succeeded} " +
                "balance=${walletRepo.findById(w.id).get().balance}"

        then: 'the rows are exactly the successful requests, and the invariants hold'
        withdrawRows(w.id)*.amount.sort() == succeeded.sort()
        succeeded.sum(BigDecimal.ZERO) <= start
        assertInvariants(w.id, start)

        when: 'every request is retried sequentially, twice over'
        2.times { amounts.each { attempt(w.id, it) } }

        then: 'still never negative, never more than the starting balance, ledger agrees'
        assertInvariants(w.id, start)
    }

    def "repeated bursts of whole-balance withdrawals: the wallet is emptied at most once"() {
        given:
        def start = new BigDecimal('42.42')
        def w = fundedWallet("wds_${System.nanoTime()}", start)

        when: 'three bursts of ten threads each try to take the whole balance'
        def all = []
        3.times { all.addAll(burst(w.id, (1..10).collect { start })) }
        println "[withdraw-burst-sweep] outcomes=${all.countBy { it }}"

        then: 'exactly one sweep lands; the wallet ends at zero, not below'
        all.count('OK') == 1
        withdrawRows(w.id).size() == 1
        walletRepo.findById(w.id).get().balance == BigDecimal.ZERO.setScale(2)
        assertInvariants(w.id, start)
    }
}
