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
import spock.lang.Specification

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Real threads, real database: one stale PENDING_BUYER_CONFIRM trade, and four
 * actors hitting it at the same instant:
 *
 *  - the auto-release sweep (sweepPendingConfirm → release(trade, true) in its
 *    own REQUIRES_NEW tx, on a Trade it read OUTSIDE that tx),
 *  - the buyer confirming receipt (buyerConfirm → release),
 *  - the buyer opening a dispute (dispute → DISPUTED, no money moves on an
 *    unprotected trade),
 *  - the seller cancelling (cancel → refundBuyer + CANCELLED).
 *
 * Whatever the interleaving, the money invariants are:
 *
 *  - exactly one actor wins and the trade lands in exactly one outcome
 *    (VERIFIED, CANCELLED or DISPUTED);
 *  - the seller is credited at most once (one SALE row, price - fee) and only
 *    when VERIFIED;
 *  - the buyer is refunded at most once and only when CANCELLED, so never both
 *    refunded AND the seller paid;
 *  - the FEE is booked to the treasury exactly once iff VERIFIED;
 *  - every wallet balance equals the signed sum of its ledger rows, and the
 *    treasury moves by exactly the signed sum of the rows written against it.
 *
 * Measured as deltas / per-trade rows so other specs sharing the cached
 * context and H2 cannot disturb the result.
 */
@SpringBootTest
@ActiveProfiles("test")
class TradeAutoReleaseRaceConcurrencySpec extends Specification {

    @Autowired ApplicationContext ctx

    TradeService          tradeService
    PlatformLedgerService ledger
    TradeRepository       tradeRepo
    WalletRepository      walletRepo
    TransactionRepository txRepo
    SteamUserRepository   userRepo
    JdbcTemplate          jdbc

    static final BigDecimal PRICE   = new BigDecimal('50.00')
    static final BigDecimal FEE     = new BigDecimal('1.00')
    static final BigDecimal DEPOSIT = new BigDecimal('100.00')

    def setup() {
        tradeService = ctx.getBean(TradeService)
        ledger       = ctx.getBean(PlatformLedgerService)
        tradeRepo    = ctx.getBean(TradeRepository)
        walletRepo   = ctx.getBean(WalletRepository)
        txRepo       = ctx.getBean(TransactionRepository)
        userRepo     = ctx.getBean(SteamUserRepository)
        jdbc         = ctx.getBean(JdbcTemplate)
        ledger.treasury()   // make sure the row exists; first-creation race is not under test here
    }

    // ── helpers ────────────────────────────────────────────────────

    private Wallet treasury() { walletRepo.findByUsername(PlatformLedgerService.TREASURY_USERNAME) }

    /** Signed sum of every ledger row written against the treasury. */
    private BigDecimal treasuryRowSum() {
        txRepo.findByWalletIdOrderByCreatedAtDesc(treasury().id).sum(BigDecimal.ZERO) { Transaction t ->
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

    /** Signed sum of a user wallet's own ledger rows (credits minus debits). */
    private BigDecimal walletRowSum(Long walletId) {
        txRepo.findByWalletIdOrderByCreatedAtDesc(walletId).sum(BigDecimal.ZERO) { Transaction t ->
            t.type in ['DEPOSIT', 'SALE', 'REFUND'] ? t.amount :
            t.type in ['PURCHASE', 'WITHDRAW']      ? -t.amount : BigDecimal.ZERO
        } as BigDecimal
    }

    /**
     * A trade that has sat in PENDING_BUYER_CONFIRM past the auto-release window.
     * The buyer deposited $100 and paid $50 into escrow (balance $50, rows agree);
     * the seller wallet is empty.
     */
    private Map staleTrade(String tag) {
        def buyer  = userRepo.save(new SteamUser(steamId64: "${tag}b", displayName: "B ${tag}"))
        def seller = userRepo.save(new SteamUser(steamId64: "${tag}s", displayName: "S ${tag}"))
        def bw = walletRepo.save(new Wallet(username: "steam_${tag}b", balance: DEPOSIT - PRICE, currency: 'USD'))
        def sw = walletRepo.save(new Wallet(username: "steam_${tag}s", balance: BigDecimal.ZERO, currency: 'USD'))
        txRepo.save(new Transaction(walletId: bw.id, type: 'DEPOSIT', status: 'COMPLETED', amount: DEPOSIT,
            currency: 'USD', stripeReference: "seed_${tag}", description: 'seed deposit'))
        txRepo.save(new Transaction(walletId: bw.id, type: 'PURCHASE', status: 'COMPLETED', amount: PRICE,
            currency: 'USD', stripeReference: "seed_${tag}", description: 'seed purchase into escrow'))
        def t = tradeRepo.save(new Trade(
            listingId: 700000000L + (System.nanoTime() % 1000000L), itemId: 700000L, itemName: "AutoReleaseRace ${tag}",
            buyerUserId: buyer.id, sellerUserId: seller.id, buyerWalletId: bw.id, sellerWalletId: sw.id,
            price: PRICE, feeAmount: FEE, state: 'PENDING_BUYER_CONFIRM'))
        // Push it past the 8-day window so the sweep picks it up.
        long thirtyDaysAgo = System.currentTimeMillis() - 30L * 24L * 60L * 60L * 1000L
        jdbc.update('UPDATE trades SET updated_at = ?, created_at = ? WHERE id = ?', thirtyDaysAgo, thirtyDaysAgo, t.id)
        [trade: t, buyer: buyer, seller: seller, buyerWallet: bw, sellerWallet: sw]
    }

    private static String attempt(Closure c) {
        try { c(); 'OK' } catch (Exception e) { e.class.simpleName }
    }

    /** All four actors released by one latch. */
    private Map<String, String> race(Map m) {
        def actors = [
            sweep  : { -> tradeService.sweepPendingConfirm() },
            confirm: { -> tradeService.buyerConfirm(m.buyer.id, m.trade.id) },
            dispute: { -> tradeService.dispute(m.buyer.id, m.trade.id, 'item never arrived') },
            cancel : { -> tradeService.cancel(m.seller.id, m.trade.id, 'changed my mind') },
        ]
        def pool = Executors.newFixedThreadPool(actors.size())
        def ready = new CountDownLatch(actors.size())
        def go = new CountDownLatch(1)
        def futures = actors.collectEntries { name, work ->
            [(name): pool.submit({ -> ready.countDown(); go.await(); attempt(work) } as Callable<String>)]
        }
        ready.await(10, TimeUnit.SECONDS)
        go.countDown()
        def out = futures.collectEntries { name, f -> [(name): f.get(60, TimeUnit.SECONDS)] }
        pool.shutdown()
        out as Map<String, String>
    }

    /** Every money invariant for one trade after the dust settles. */
    private void assertMoney(Map m) {
        def t  = tradeRepo.findById(m.trade.id).get()
        def bw = walletRepo.findById(m.buyerWallet.id).get()
        def sw = walletRepo.findById(m.sellerWallet.id).get()
        def sales   = txRepo.findByWalletIdOrderByCreatedAtDesc(sw.id).findAll { it.type == 'SALE' }
        def refunds = txRepo.findByWalletIdOrderByCreatedAtDesc(bw.id).findAll { it.type == 'REFUND' }
        def fees    = feeRowsFor(t.id)

        // Each wallet balance matches its own ledger rows, whatever happened.
        assert bw.balance == walletRowSum(bw.id)
        assert sw.balance == walletRowSum(sw.id)

        // Seller credited at most once; buyer refunded at most once; never both.
        assert sales.size() <= 1
        assert refunds.size() <= 1
        assert !(sales.size() == 1 && refunds.size() == 1)
        assert fees.size() <= 1

        switch (t.state) {
            case 'VERIFIED':
                assert sw.balance == PRICE - FEE
                assert sales.size() == 1 && sales[0].amount == PRICE - FEE
                assert fees.size() == 1 && fees[0].amount == FEE
                assert refunds.isEmpty()
                assert bw.balance == DEPOSIT - PRICE
                break
            case 'CANCELLED':
                assert bw.balance == DEPOSIT
                assert refunds.size() == 1 && refunds[0].amount == PRICE
                assert sales.isEmpty() && fees.isEmpty()
                assert sw.balance == BigDecimal.ZERO
                break
            case 'DISPUTED':
            case 'PENDING_BUYER_CONFIRM':
                // money still in escrow: nobody paid, nobody refunded
                assert sales.isEmpty() && refunds.isEmpty() && fees.isEmpty()
                assert sw.balance == BigDecimal.ZERO
                assert bw.balance == DEPOSIT - PRICE
                break
            default:
                assert false, "unexpected trade state ${t.state}"
        }
    }

    /** Which actor's success the final state reflects. The sweep returns nothing,
     *  so its win is inferred: VERIFIED without a successful buyer confirm. */
    private static int winners(Map<String, String> out, String state) {
        int direct = ['confirm', 'dispute', 'cancel'].count { out[it] == 'OK' }
        int sweep  = (state == 'VERIFIED' && out.confirm != 'OK') ? 1 : 0
        direct + sweep
    }

    // ── features ───────────────────────────────────────────────────

    def "auto-release sweep x buyer confirm x dispute x cancel on one trade: one outcome, no double payout"() {
        given:
        def uniq = System.nanoTime()
        def rounds = 12
        def balBefore  = treasury().balance
        def rowsBefore = treasuryRowSum()
        def results = []

        when: 'each round, a fresh stale trade is hit by all four actors at the same instant'
        (1..rounds).each { r ->
            def m = staleTrade("arr_${uniq}_${r}_")
            def out = race(m)
            def stateAfterRace = tradeRepo.findById(m.trade.id).get().state
            // A round in which every actor lost a lock collision leaves the
            // trade untouched in PENDING_BUYER_CONFIRM (all rolled back). That
            // is safe; the next sweep tick is what settles it. Drive it.
            if (stateAfterRace == 'PENDING_BUYER_CONFIRM') tradeService.sweepPendingConfirm()
            def finalState = tradeRepo.findById(m.trade.id).get().state
            println "[auto-release-race] round=${r} ${out} afterRace=${stateAfterRace} final=${finalState}"
            results << [m: m, out: out, afterRace: stateAfterRace, finalState: finalState]
        }

        then: 'every trade ends in exactly one terminal-or-held outcome, with at most one racing winner'
        results.each { res ->
            assert res.finalState in ['VERIFIED', 'CANCELLED', 'DISPUTED']
            int w = winners(res.out, res.afterRace)
            if (res.afterRace == 'PENDING_BUYER_CONFIRM') {
                assert w == 0
            } else {
                assert w == 1, "round ${res.out} afterRace=${res.afterRace}: expected exactly one winner"
            }
        }

        and: 'per trade: seller credited at most once, buyer never both refunded and seller paid, wallets == rows'
        results.each { assertMoney(it.m) }

        and: 'treasury moved by exactly the rows written against it, and holds exactly one FEE per VERIFIED trade'
        (treasury().balance - balBefore) == (treasuryRowSum() - rowsBefore)
        def verified = results.count { it.finalState == 'VERIFIED' }
        results.sum(BigDecimal.ZERO) { feeRowsFor(it.m.trade.id).sum(BigDecimal.ZERO) { it.amount } } == FEE * verified

        when: 'every loser retries after the race (what the UI / next sweep tick does)'
        results.each { res ->
            def m = res.m
            attempt { tradeService.buyerConfirm(m.buyer.id, m.trade.id) }
            attempt { tradeService.cancel(m.seller.id, m.trade.id, 'retry') }
            attempt { tradeService.dispute(m.buyer.id, m.trade.id, 'retry') }
        }
        tradeService.sweepPendingConfirm()

        then: 'nothing moved: the first outcome is final and no second payout leaks through'
        results.each { res ->
            assert tradeRepo.findById(res.m.trade.id).get().state == res.finalState
            assertMoney(res.m)
        }
        (treasury().balance - balBefore) == (treasuryRowSum() - rowsBefore)
    }
}
