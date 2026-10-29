package com.sboxmarket

import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Trade
import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.PlatformLedgerService
import com.sboxmarket.service.TradeService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import spock.lang.Specification

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Real threads, real database: the platform treasury is ONE wallet row with a
 * {@code @Version}, and every trade release books its FEE onto it inside the
 * release's own transaction (PlatformLedgerService.post, ~799). Parallel
 * releases therefore contend on that row. The money invariants that must hold
 * whatever the interleaving:
 *
 *  - a trade is either fully released (VERIFIED, seller credited exactly
 *    price - fee, one SALE row, one FEE row) or not at all; never a seller
 *    credit without the fee or a fee without the credit;
 *  - the treasury balance moves by exactly the signed sum of the ledger rows
 *    written against it.
 *
 * Measured as deltas so other specs sharing the cached context/H2 cannot
 * disturb the result.
 */
@SpringBootTest
@ActiveProfiles("test")
class TreasuryTradeReleaseConcurrencySpec extends Specification {

    @Autowired ApplicationContext ctx

    TradeService          tradeService
    PlatformLedgerService ledger
    TradeRepository       tradeRepo
    WalletRepository      walletRepo
    TransactionRepository txRepo
    SteamUserRepository   userRepo
    JdbcTemplate          jdbc
    TransactionTemplate   tt

    def setup() {
        tradeService = ctx.getBean(TradeService)
        ledger       = ctx.getBean(PlatformLedgerService)
        tradeRepo    = ctx.getBean(TradeRepository)
        walletRepo   = ctx.getBean(WalletRepository)
        txRepo       = ctx.getBean(TransactionRepository)
        userRepo     = ctx.getBean(SteamUserRepository)
        jdbc         = ctx.getBean(JdbcTemplate)
        tt           = new TransactionTemplate(ctx.getBean(PlatformTransactionManager))
        ledger.treasury()   // make sure the row exists; first-creation race is not under test here
    }

    // ── helpers ────────────────────────────────────────────────────

    private Wallet treasury() { walletRepo.findByUsername(PlatformLedgerService.TREASURY_USERNAME) }

    /** Signed sum of every ledger row written against the treasury. */
    private BigDecimal treasuryRowSum() {
        def tid = treasury().id
        txRepo.findByWalletIdOrderByCreatedAtDesc(tid).sum(BigDecimal.ZERO) { Transaction t ->
            PlatformLedgerService.REVENUE_TYPES.contains(t.type) ? t.amount :
            PlatformLedgerService.COST_TYPES.contains(t.type)    ? -t.amount : BigDecimal.ZERO
        } as BigDecimal
    }

    private List<Transaction> feeRowsFor(Long tradeId) {
        txRepo.findByWalletIdOrderByCreatedAtDesc(treasury().id).findAll {
            it.type == PlatformLedgerService.TYPE_FEE &&
                (it.description == "Platform fee on trade #${tradeId}".toString() ||
                 it.description?.startsWith("Platform fee on trade #${tradeId} ".toString()))
        }
    }

    private List<Map> makeTrades(int n, String tag, BigDecimal price, BigDecimal fee) {
        (1..n).collect { i ->
            def buyer  = userRepo.save(new SteamUser(steamId64: "${tag}b${i}", displayName: "B${i}"))
            def seller = userRepo.save(new SteamUser(steamId64: "${tag}s${i}", displayName: "S${i}"))
            def sw = walletRepo.save(new Wallet(username: "steam_${tag}s${i}", balance: BigDecimal.ZERO, currency: 'USD'))
            def t = tradeRepo.save(new Trade(
                listingId: 900000L + i, itemId: 800000L + i, itemName: "TreasuryRace ${tag}${i}",
                buyerUserId: buyer.id, sellerUserId: seller.id, sellerWalletId: sw.id,
                price: price, feeAmount: fee, state: 'PENDING_BUYER_CONFIRM'))
            [trade: t, buyer: buyer, seller: seller, sellerWallet: sw]
        }
    }

    private String confirm(Map m) {
        try { tradeService.buyerConfirm(m.buyer.id, m.trade.id); 'OK' }
        catch (Exception e) { e.class.simpleName }
    }

    /** Wait (bounded) until at least {@code n} H2 sessions are blocked on a row lock. */
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

    /** Asserts the all-or-nothing release invariant for one trade. */
    private void assertWholeOrNothing(Map m) {
        def t  = tradeRepo.findById(m.trade.id).get()
        def sw = walletRepo.findById(m.sellerWallet.id).get()
        def sales = txRepo.findByWalletIdOrderByCreatedAtDesc(sw.id).findAll { it.type == 'SALE' }
        def fees  = feeRowsFor(t.id)
        if (t.state == 'VERIFIED') {
            assert sw.balance == t.price - t.feeAmount
            assert sales.size() == 1 && sales[0].amount == t.price - t.feeAmount
            assert fees.size() == 1 && fees[0].amount == t.feeAmount
        } else {
            assert t.state == 'PENDING_BUYER_CONFIRM'
            assert sw.balance == BigDecimal.ZERO
            assert sales.isEmpty()
            assert fees.isEmpty()
        }
    }

    // ── features ───────────────────────────────────────────────────

    def "parallel trade releases: every seller is credited exactly once and the treasury moves by exactly the FEE rows"() {
        given: '12 independent trades ready for buyer confirmation, \$50.00 each with a \$1.00 fee'
        def uniq = System.nanoTime()
        def price = new BigDecimal('50.00'), fee = new BigDecimal('1.00')
        def trades = makeTrades(12, "trr_${uniq}_", price, fee)
        def balBefore = treasury().balance
        def rowsBefore = treasuryRowSum()

        when: 'all 12 buyers confirm at the same instant'
        def pool = Executors.newFixedThreadPool(trades.size())
        def go = new CountDownLatch(1)
        def futures = trades.collect { m -> pool.submit({ -> go.await(); confirm(m) } as Callable<String>) }
        go.countDown()
        def outcomes = futures.collect { it.get(20, TimeUnit.SECONDS) }
        pool.shutdown()
        println "[treasury-burst] outcomes=${outcomes.countBy { it }}"

        then: 'each trade is whole-or-nothing after the burst (no seller credit without its fee, or vice versa)'
        outcomes.each { assert it in ['OK', 'ObjectOptimisticLockingFailureException'] }
        trades.each { assertWholeOrNothing(it) }

        and: 'the treasury moved by exactly the signed sum of the rows written against it'
        (treasury().balance - balBefore) == (treasuryRowSum() - rowsBefore)
        (treasury().balance - balBefore) == fee * outcomes.count('OK')

        when: 'any buyer that lost the race retries (what the UI / auto-release sweep does)'
        3.times {
            trades.findAll { tradeRepo.findById(it.trade.id).get().state != 'VERIFIED' }.each { confirm(it) }
        }

        then: 'every trade is released, every seller holds exactly price - fee, and treasury == rows == 12 fees'
        trades.each {
            assert tradeRepo.findById(it.trade.id).get().state == 'VERIFIED'
            assertWholeOrNothing(it)
        }
        (treasury().balance - balBefore) == fee * 12
        (treasuryRowSum() - rowsBefore) == fee * 12
    }

    def "a release that collides with a concurrent treasury write rolls back whole and succeeds on retry"() {
        given: 'one trade, and a concurrent writer that holds the treasury row lock'
        def uniq = System.nanoTime()
        def m = makeTrades(1, "trc_${uniq}_", new BigDecimal('80.00'), new BigDecimal('1.60'))[0]
        def tid = treasury().id
        def balBefore = treasury().balance
        def rowsBefore = treasuryRowSum()
        def locked = new CountDownLatch(1)
        def releaseBlocked = new CountDownLatch(1)

        when: 'the release reads the treasury, then blocks on its UPDATE while another fee posting commits first'
        def pool = Executors.newFixedThreadPool(2)
        def locker = pool.submit({ ->
            tt.executeWithoutResult {
                walletRepo.findByIdForUpdate(tid).get()
                locked.countDown()
                // hold the lock until the release is parked on the treasury UPDATE
                releaseBlocked.await(15, TimeUnit.SECONDS)
                ledger.postTradeFee(new BigDecimal('0.50'), -uniq, null, 'concurrent writer')
            }
            'LOCKER_OK'
        } as Callable<String>)
        locked.await(10, TimeUnit.SECONDS)
        def releaser = pool.submit({ -> confirm(m) } as Callable<String>)
        def sawBlock = awaitBlockedSessions(1)
        releaseBlocked.countDown()
        def lockerOutcome  = locker.get(20, TimeUnit.SECONDS)
        def releaseOutcome = releaser.get(20, TimeUnit.SECONDS)
        pool.shutdown()
        println "[treasury-collision] release=${releaseOutcome} sawBlock=${sawBlock}"

        then: 'the collision really happened and the release did not half-commit'
        sawBlock
        lockerOutcome == 'LOCKER_OK'
        releaseOutcome in ['OK', 'ObjectOptimisticLockingFailureException', 'PessimisticLockingFailureException',
                           'CannotAcquireLockException']
        assertWholeOrNothing(m)
        (treasury().balance - balBefore) == (treasuryRowSum() - rowsBefore)

        when: 'the buyer retries if it was refused'
        if (tradeRepo.findById(m.trade.id).get().state != 'VERIFIED') confirm(m)

        then: 'seller credited exactly once; treasury == rows == locker fee + this fee'
        tradeRepo.findById(m.trade.id).get().state == 'VERIFIED'
        assertWholeOrNothing(m)
        (treasury().balance - balBefore) == new BigDecimal('2.10')
        (treasuryRowSum() - rowsBefore) == new BigDecimal('2.10')
    }
}
