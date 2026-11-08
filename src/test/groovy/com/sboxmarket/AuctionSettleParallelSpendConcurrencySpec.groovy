package com.sboxmarket

import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Trade
import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.BidService
import com.sboxmarket.service.PlatformLedgerService
import com.sboxmarket.service.PurchaseService
import com.sboxmarket.service.StripeService
import com.sboxmarket.service.TradeService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.util.AopTestUtils
import spock.lang.Specification

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Real threads, real database: an auction settles at the same instant its
 * winner spends from the same wallet.
 *
 * The winner holds $100 and is the top bidder ($60) on TWO auctions that have
 * just ended. Bids don't reserve funds, so only one $60 spend can ever fit.
 * Released by one latch:
 *
 *  - two overlapping auction sweeps (BidService.sweepExpired, the scheduled
 *    settle path: each settle debits the winner and opens the escrow trade),
 *  - the winner buying a $60 Buy-Now listing (PurchaseService.buy),
 *  - the winner withdrawing $60 (StripeService.requestWithdrawal, dev/test
 *    SIMULATED path, opt-in granted as in ParallelWithdrawalConcurrencySpec).
 *
 * Whatever the interleaving:
 *  - the winner's balance never goes negative and exactly one $60 spend lands
 *    (after the next sweep tick settles anything a lost race left open);
 *  - each auction charges the winner at most once and opens at most one trade,
 *    and an auction that did not charge the winner opened none;
 *  - once those trades are released, each seller is credited at most once and
 *    only for an auction the winner paid; the trade fee is booked at most once;
 *  - wallets match their ledger rows and the treasury moves by exactly the
 *    rows written against it;
 *  - re-running every actor afterwards moves nothing.
 */
@SpringBootTest
@ActiveProfiles("test")
class AuctionSettleParallelSpendConcurrencySpec extends Specification {

    @Autowired ApplicationContext ctx

    BidService            bidService
    PurchaseService       purchaseService
    StripeService         stripeService
    StripeService         stripeTarget
    TradeService          tradeService
    PlatformLedgerService ledger
    ListingRepository     listingRepo
    ItemRepository        itemRepo
    TradeRepository       tradeRepo
    WalletRepository      walletRepo
    TransactionRepository txRepo
    SteamUserRepository   userRepo
    JdbcTemplate          jdbc
    Environment           originalEnv

    static final BigDecimal DEPOSIT = new BigDecimal('100.00')
    static final BigDecimal SPEND   = new BigDecimal('60.00')
    static final AtomicLong SEQ = new AtomicLong(1)

    def setup() {
        bidService      = ctx.getBean(BidService)
        purchaseService = ctx.getBean(PurchaseService)
        stripeService   = ctx.getBean(StripeService)
        stripeTarget    = AopTestUtils.getUltimateTargetObject(stripeService) as StripeService
        tradeService    = ctx.getBean(TradeService)
        ledger          = ctx.getBean(PlatformLedgerService)
        listingRepo     = ctx.getBean(ListingRepository)
        itemRepo        = ctx.getBean(ItemRepository)
        tradeRepo       = ctx.getBean(TradeRepository)
        walletRepo      = ctx.getBean(WalletRepository)
        txRepo          = ctx.getBean(TransactionRepository)
        userRepo        = ctx.getBean(SteamUserRepository)
        jdbc            = ctx.getBean(JdbcTemplate)
        originalEnv     = stripeTarget.environment
        stripeTarget.environment = SpecEnvs.creditOptedIn(['test'])
        ledger.treasury()
    }

    def cleanup() {
        stripeTarget.environment = originalEnv
    }

    // ── fixtures ───────────────────────────────────────────────────

    private SteamUser user(String tag, String role = 'USER') {
        def tail = String.valueOf(SEQ.getAndIncrement() + (System.nanoTime() % 100000L) * 1000L)
        userRepo.save(new SteamUser(steamId64: '7656118' + tail.padLeft(10, '0'),
            displayName: "${tag}-${tail}", role: role,
            email: "${tag}-${tail}@example.com", emailVerified: true,
            tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc'))
    }

    private Wallet walletFor(SteamUser u, BigDecimal balance) {
        walletRepo.save(new Wallet(username: "steam_${u.steamId64}", balance: balance, currency: 'USD'))
    }

    private Item item(String tag) {
        itemRepo.save(new Item(name: "SettleRace ${tag} ${SEQ.getAndIncrement()}", category: 'Hats',
            rarity: 'Limited', supply: 10, totalSold: 0, lowestPrice: SPEND, iconEmoji: '🎩'))
    }

    private Listing auction(SteamUser seller, String tag) {
        listingRepo.save(new Listing(item: item(tag), sellerUserId: seller.id, sellerName: seller.displayName,
            price: new BigDecimal('10.00'), status: 'ACTIVE', listingType: 'AUCTION',
            rarityScore: BigDecimal.ZERO, expiresAt: System.currentTimeMillis() + 3_600_000L))
    }

    /** Winner with $100 (one DEPOSIT row) is top bidder at $60 on two auctions
     *  that have just ended, and a $60 Buy-Now listing is up for grabs. */
    private Map scene(String tag) {
        def winner  = user("winner-${tag}")
        def seller1 = user("seller1-${tag}")
        def seller2 = user("seller2-${tag}")
        def ww  = walletFor(winner, DEPOSIT)
        def sw1 = walletFor(seller1, BigDecimal.ZERO)
        def sw2 = walletFor(seller2, BigDecimal.ZERO)
        txRepo.save(new Transaction(walletId: ww.id, type: 'DEPOSIT', status: 'COMPLETED', amount: DEPOSIT,
            currency: 'USD', stripeReference: "seed_${tag}", description: 'seed deposit'))
        def a1 = auction(seller1, "${tag}-a1")
        def a2 = auction(seller2, "${tag}-a2")
        bidService.placeBid(winner.id, winner.displayName, a1.id, SPEND)
        bidService.placeBid(winner.id, winner.displayName, a2.id, SPEND)
        def buyNow = listingRepo.save(new Listing(item: item("${tag}-bn"), price: SPEND, status: 'ACTIVE',
            sellerName: "Bot-${tag}", rarityScore: BigDecimal.ZERO))
        // The auctions end now.
        long ended = System.currentTimeMillis() - 1_000L
        jdbc.update('UPDATE listings SET expires_at = ? WHERE id IN (?, ?)', ended, a1.id, a2.id)
        [winner: winner, winnerWallet: ww,
         auctions: [[listing: a1, seller: seller1, sellerWallet: sw1],
                    [listing: a2, seller: seller2, sellerWallet: sw2]],
         buyNow: buyNow]
    }

    // ── ledger readers ─────────────────────────────────────────────

    private Wallet treasury() { walletRepo.findByUsername(PlatformLedgerService.TREASURY_USERNAME) }

    private BigDecimal treasuryRowSum() {
        txRepo.findByWalletIdOrderByCreatedAtDesc(treasury().id).sum(BigDecimal.ZERO) { Transaction t ->
            PlatformLedgerService.REVENUE_TYPES.contains(t.type) ? t.amount :
            PlatformLedgerService.COST_TYPES.contains(t.type)    ? -t.amount : BigDecimal.ZERO
        } as BigDecimal
    }

    private List<Transaction> feeRowsFor(Long tradeId) {
        def re = ~/^Platform fee on trade #${tradeId}(\D.*)?$/
        txRepo.findByWalletIdOrderByCreatedAtDesc(treasury().id).findAll {
            it.type == PlatformLedgerService.TYPE_FEE && it.description ==~ re
        }
    }

    private List<Transaction> rows(Long walletId) { txRepo.findByWalletIdOrderByCreatedAtDesc(walletId) }

    private BigDecimal walletRowSum(Long walletId) {
        rows(walletId).sum(BigDecimal.ZERO) { Transaction t ->
            t.type in ['DEPOSIT', 'SALE', 'REFUND'] ? t.amount :
            t.type == 'PURCHASE' ? -t.amount :
            (t.type == 'WITHDRAW' && t.status in ['COMPLETED', 'PENDING']) ? -t.amount : BigDecimal.ZERO
        } as BigDecimal
    }

    private List<Trade> tradesFor(Long listingId) {
        tradeRepo.findAll().findAll { it.listingId == listingId }
    }

    private static String attempt(Closure c) {
        try { c(); 'OK' } catch (Exception e) { e.class.simpleName }
    }

    private static Map<String, String> race(Map<String, Closure> actors) {
        def pool = Executors.newFixedThreadPool(actors.size())
        def ready = new CountDownLatch(actors.size())
        def go = new CountDownLatch(1)
        def futures = actors.collectEntries { name, work ->
            [(name): pool.submit({ -> ready.countDown(); go.await(); attempt(work) } as Callable<String>)]
        }
        ready.await(10, TimeUnit.SECONDS)
        go.countDown()
        def out = futures.collectEntries { name, f -> [(name): f.get(90, TimeUnit.SECONDS)] }
        pool.shutdown()
        out as Map<String, String>
    }

    // ── invariants ─────────────────────────────────────────────────

    /** Winner-side invariants; `settled` = every auction has left ACTIVE. */
    private void assertWinner(Map s, boolean settled) {
        def ww = walletRepo.findById(s.winnerWallet.id).get()
        def wRows = rows(ww.id)
        def auctionCharges = s.auctions.collectEntries { a ->
            [(a.listing.id): wRows.findAll { it.type == 'PURCHASE' && it.listingId == a.listing.id }]
        }
        def buyCharges = wRows.findAll { it.type == 'PURCHASE' && it.listingId == s.buyNow.id }
        def withdrawals = wRows.findAll { it.type == 'WITHDRAW' && it.status in ['COMPLETED', 'PENDING'] }
        int spends = auctionCharges.values().sum { it.size() } + buyCharges.size() + withdrawals.size()
        def info = "winner=${ww.balance} auctionCharges=${auctionCharges.collectEntries { k, v -> [(k): v*.amount] }} " +
                   "buy=${buyCharges*.amount} withdraw=${withdrawals*.amount}"

        assert ww.balance >= BigDecimal.ZERO, info
        assert ww.balance == walletRowSum(ww.id), info
        assert ww.balance == DEPOSIT - SPEND * spends, info
        assert spends <= 1, info
        if (settled) assert spends == 1, info
        assert buyCharges.size() <= 1, info

        s.auctions.each { a ->
            def l = listingRepo.findById(a.listing.id).get()
            def charges = auctionCharges[a.listing.id]
            def trades = tradesFor(l.id)
            def ainfo = "${info} auction=${l.id} status=${l.status} buyer=${l.buyerUserId} trades=${trades*.id}"
            assert charges.size() <= 1, ainfo
            assert trades.size() <= 1, ainfo
            // An auction opens an escrow trade iff it charged the winner.
            assert trades.size() == charges.size(), ainfo
            if (charges) {
                assert charges[0].amount == SPEND, ainfo
                assert l.status == 'SOLD' && l.buyerUserId == s.winner.id, ainfo
                assert trades[0].price == SPEND && trades[0].buyerWalletId == ww.id, ainfo
            } else if (l.status != 'ACTIVE') {
                // Winner couldn't pay: the item went back to the seller.
                assert l.buyerUserId != s.winner.id, ainfo
            }
            if (settled) assert l.status != 'ACTIVE', ainfo
        }
        def bn = listingRepo.findById(s.buyNow.id).get()
        assert (bn.status == 'SOLD') == (buyCharges.size() == 1), info
    }

    /** Seller-side invariants once trades have been released. */
    private void assertSellers(Map s) {
        s.auctions.each { a ->
            def sw = walletRepo.findById(a.sellerWallet.id).get()
            def sales = rows(sw.id).findAll { it.type == 'SALE' }
            def trades = tradesFor(a.listing.id)
            def winnerPaid = rows(s.winnerWallet.id).any { it.type == 'PURCHASE' && it.listingId == a.listing.id }
            def info = "auction=${a.listing.id} seller=${sw.balance} sales=${sales*.amount} trades=${trades.collect { [it.id, it.state] }} winnerPaid=${winnerPaid}"
            assert sw.balance == walletRowSum(sw.id), info
            assert sales.size() <= 1, info
            if (winnerPaid) {
                def t = trades[0]
                assert t.state == 'VERIFIED', info
                assert sales.size() == 1 && sales[0].amount == t.price - t.feeAmount, info
                def fees = feeRowsFor(t.id)
                assert fees.size() == 1 && fees[0].amount == t.feeAmount, info
                assert sw.balance == t.price - t.feeAmount, info
            } else {
                assert sales.isEmpty() && sw.balance == BigDecimal.ZERO && trades.isEmpty(), info
            }
        }
    }

    // ── feature ────────────────────────────────────────────────────

    def "auction settles x winner buys x winner withdraws from one wallet: never negative, charged once, seller paid once"() {
        given:
        def admin = user('admin', 'ADMIN')
        def rounds = 12
        def balBefore  = treasury().balance
        def rowsBefore = treasuryRowSum()
        def results = []

        when: 'each round, two sweeps, a buy and a withdrawal hit the winner\'s wallet at the same instant'
        (1..rounds).each { r ->
            def s = scene("r${r}")
            // A sweep does more reads before its debit than a buy or a
            // withdrawal, so the spends get a small, round-varying head
            // start handicap (0-35 ms) to land the debits on top of each other.
            long lag = (r % 8) * 5L
            def out = race([
                sweep1  : { -> bidService.sweepExpired() },
                sweep2  : { -> bidService.sweepExpired() },
                buy     : { -> Thread.sleep(lag); purchaseService.buy(s.winnerWallet.id, s.winner.id, s.buyNow.id) },
                withdraw: { -> Thread.sleep(lag); stripeService.requestWithdrawal(s.winnerWallet.id, SPEND, 'race-test') },
            ])
            def statuses = s.auctions.collect { listingRepo.findById(it.listing.id).get().status }
            def bal = walletRepo.findById(s.winnerWallet.id).get().balance
            def spent = rows(s.winnerWallet.id).findAll { it.type in ['PURCHASE', 'WITHDRAW'] }
                .collect { it.type == 'WITHDRAW' ? 'withdraw' : it.listingId == s.buyNow.id ? 'buy' : "settle#${it.listingId}" }
            println "[settle-spend-race] round=${r} lag=${lag} ${out} auctions=${statuses} winnerBalance=${bal} spent=${spent}"
            results << [s: s, out: out]
        }

        then: 'mid-flight, nothing is charged twice and the wallet never dips below zero'
        results.each { assertWinner(it.s, false) }
        (treasury().balance - balBefore) == (treasuryRowSum() - rowsBefore)

        when: 'the next sweep tick settles anything a lost race left open'
        bidService.sweepExpired()

        then: 'every auction is closed and exactly one $60 spend landed per winner'
        results.each { assertWinner(it.s, true) }

        when: 'staff release every escrow trade the auctions opened'
        results.each { res ->
            res.s.auctions.each { a ->
                tradesFor(a.listing.id).each { tradeService.adminRelease(admin.id, it.id, 'delivered') }
            }
        }

        then: 'each seller is credited once, only for an auction the winner paid, fee booked once'
        results.each { assertSellers(it.s); assertWinner(it.s, true) }
        (treasury().balance - balBefore) == (treasuryRowSum() - rowsBefore)

        when: 'every actor runs again'
        def treasuryAfter = treasury().balance
        def snap = results.collect { res -> snapshot(res.s) }
        results.each { res ->
            def s = res.s
            2.times { attempt { bidService.sweepExpired() } }
            attempt { purchaseService.buy(s.winnerWallet.id, s.winner.id, s.buyNow.id) }
            attempt { stripeService.requestWithdrawal(s.winnerWallet.id, SPEND, 'retry') }
            s.auctions.each { a ->
                tradesFor(a.listing.id).each { t -> attempt { tradeService.adminRelease(admin.id, t.id, 'retry') } }
            }
        }

        then: 'nothing moved'
        results.collect { res -> snapshot(res.s) } == snap
        results.each { assertSellers(it.s); assertWinner(it.s, true) }
        treasury().balance == treasuryAfter
        (treasury().balance - balBefore) == (treasuryRowSum() - rowsBefore)
    }

    private Map snapshot(Map s) {
        [winner : walletRepo.findById(s.winnerWallet.id).get().balance,
         wRows  : rows(s.winnerWallet.id).size(),
         sellers: s.auctions.collect { walletRepo.findById(it.sellerWallet.id).get().balance },
         sRows  : s.auctions.collect { rows(it.sellerWallet.id).size() },
         trades : s.auctions.collect { tradesFor(it.listing.id).collect { [it.id, it.state] } }]
    }
}
