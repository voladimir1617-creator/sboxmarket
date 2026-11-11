package com.sboxmarket

import com.sboxmarket.model.BuyOrder
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.BuyOrderRepository
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.BuyOrderService
import com.sboxmarket.service.ListingService
import com.sboxmarket.service.SellService
import com.sboxmarket.service.SteamEscrowService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Buy-order over-fill on a real database with real threads.
 *
 * One standing buy order wants Q copies at up to P each. More than Q matching
 * listings go live at the same instant through every path that feeds the real
 * matcher:
 *
 *   - sellers listing fresh copies   (ListingService.createListing → afterCommit tryMatch)
 *   - sellers relisting owned copies (SellService.relist → afterCommit tryMatch)
 *   - listings going live elsewhere  (ListingService.offerToBuyOrders → tryMatch, e.g. a bot deposit)
 *   - the order's own fill loop      (BuyOrderService.tryFillFromExisting, run on order create/update)
 *
 * Whatever interleaving the threads land in, the order must fill at most Q
 * times, the buyer must never pay more than Q × P or go negative, the order's
 * remaining quantity must agree with the fills it actually made, and every
 * listing the order did not take must still be ACTIVE for someone else to buy.
 */
@SpringBootTest
@ActiveProfiles("test")
class BuyOrderOverFillConcurrencySpec extends Specification {

    @Autowired ApplicationContext ctx

    BuyOrderService       buyOrderService
    ListingService        listingService
    SellService           sellService
    BuyOrderRepository    buyOrderRepo
    ListingRepository     listingRepo
    WalletRepository      walletRepo
    ItemRepository        itemRepo
    SteamUserRepository   userRepo
    TransactionRepository txRepo
    boolean               relistAllowed

    def setup() {
        buyOrderService = ctx.getBean(BuyOrderService)
        listingService  = ctx.getBean(ListingService)
        sellService     = ctx.getBean(SellService)
        buyOrderRepo    = ctx.getBean(BuyOrderRepository)
        listingRepo     = ctx.getBean(ListingRepository)
        walletRepo      = ctx.getBean(WalletRepository)
        itemRepo        = ctx.getBean(ItemRepository)
        userRepo        = ctx.getBean(SteamUserRepository)
        txRepo          = ctx.getBean(TransactionRepository)
        relistAllowed   = !(ctx.getBean(SteamEscrowService).escrowEnabled)
    }

    def "a quantity-#q order at #p, hit by #n concurrent matching listings, fills at most #q and charges at most #q x #p"() {
        given: 'a buyer with plenty of money and one standing order for q copies at up to p'
        def P = new BigDecimal(p)
        def f = fixture(q, P, startBalance)

        and: 'n matching listings, split across every path that feeds the matcher'
        def plan = planListings(f, n, P)

        when: 'all of them go live on their own threads, released by one latch'
        def outcomes = runConcurrently(plan.tasks)
        // afterCommit matches run on the caller thread, so they are done once the futures are;
        // give any stray deferred work a moment to settle before reading the books.
        Thread.sleep(500)
        def r = results(f, plan)
        println "[buy-order over-fill q=${q} p=${p} n=${n} bal=${startBalance}] fills=${r.fills} charged=${r.charged} " +
                "balance=${r.balance} order=${r.order.status}/${r.order.quantity} outcomes=${outcomes.countBy { it }} " +
                "statuses=${r.statuses.countBy { it }}"

        then: 'the order filled at most q times'
        r.fills <= q
        r.fills >= 1

        and: 'the buyer paid at most q × p, exactly what the PURCHASE rows say, and never went negative'
        r.charged <= P * q
        r.charged == r.purchaseTotal
        r.purchaseRows == r.fills
        r.balance >= BigDecimal.ZERO
        r.balance == startBalance - r.charged

        and: "the order's remaining quantity agrees with the fills it made"
        r.order.quantity == q - r.fills
        (r.order.status == 'FILLED') == (r.fills == q)

        and: 'every listing the order did not take is still ACTIVE and buyable, none lost'
        r.notFilled.every { it.status == 'ACTIVE' && !Boolean.TRUE.equals(it.hidden) && it.buyerUserId == null }
        r.fills + r.notFilled.size() == plan.listingIds.size()

        where:
        q | p       | n  | startBalance
        1 | '10.00' | 8  | new BigDecimal('500.00')
        3 | '10.00' | 12 | new BigDecimal('500.00')
        3 | '10.00' | 12 | new BigDecimal('30.00')
    }

    // ── fixtures ──────────────────────────────────────────────────

    Map fixture(int q, BigDecimal P, BigDecimal startBalance) {
        def uniq = System.nanoTime()
        def buyer = userRepo.save(new SteamUser(steamId64: "bo_buyer_${uniq}", displayName: "OverFillBuyer",
            role: 'USER', tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc'))
        def buyerWallet = walletRepo.save(new Wallet(username: "steam_${buyer.steamId64}", balance: startBalance, currency: 'USD'))
        def item = itemRepo.save(new Item(name: "OverFill-${uniq}", category: 'Hats', rarity: 'Limited',
            supply: 100, totalSold: 0, lowestPrice: P, isListed: true, iconEmoji: '🎩'))
        def now = System.currentTimeMillis()
        def order = buyOrderRepo.save(new BuyOrder(buyerUserId: buyer.id, buyerName: 'OverFillBuyer',
            itemId: item.id, itemName: item.name, category: item.category, rarity: item.rarity,
            maxPrice: P, quantity: q, originalQuantity: q, status: 'ACTIVE', createdAt: now, updatedAt: now))
        [uniq: uniq, buyer: buyer, buyerWallet: buyerWallet, item: item, order: order]
    }

    SteamUser seller(Map f, int i) {
        def u = userRepo.save(new SteamUser(steamId64: "bo_seller_${i}_${f.uniq}", displayName: "Seller${i}", role: 'USER'))
        walletRepo.save(new Wallet(username: "steam_${u.steamId64}", balance: BigDecimal.ZERO.setScale(2), currency: 'USD'))
        u
    }

    /** Builds one task per listing path. listingIds is filled in as the tasks create their rows. */
    Map planListings(Map f, int n, BigDecimal P) {
        List<Long> listingIds = Collections.synchronizedList([])
        List<Callable<String>> tasks = []
        // Prices at and just under the cap, so the "at most q × p" bound is the tight one.
        def priceFor = { int i -> i % 3 == 0 ? P - new BigDecimal('0.50') : P }

        (0..<n).each { i ->
            def price = priceFor(i)
            switch (i % 4) {
                case 0: // a seller lists a fresh copy into the order
                    def s = seller(f, i)
                    tasks << ({ ->
                        def l = listingService.createListing(new Listing(item: f.item, price: price, status: 'ACTIVE',
                            sellerName: s.displayName, sellerUserId: s.id, rarityScore: BigDecimal.ZERO, condition: ''))
                        listingIds << l.id
                        'CREATE'
                    } as Callable<String>)
                    break
                case 1: // a seller relists a copy they own into the order
                    def s = seller(f, i)
                    if (relistAllowed) {
                        def owned = listingRepo.save(new Listing(item: f.item, price: P, status: 'SOLD',
                            sellerName: 'Origin', rarityScore: BigDecimal.ZERO, buyerUserId: s.id,
                            soldAt: System.currentTimeMillis() - 60_000L))
                        tasks << ({ ->
                            def l = sellService.relist(s.id, s.displayName, owned.id, price)
                            listingIds << l.id
                            'RELIST'
                        } as Callable<String>)
                    } else {
                        tasks << ({ ->
                            def l = listingService.save(new Listing(item: f.item, price: price, status: 'ACTIVE',
                                sellerName: s.displayName, sellerUserId: s.id, rarityScore: BigDecimal.ZERO, condition: ''))
                            listingIds << l.id
                            'SAVE'
                        } as Callable<String>)
                    }
                    break
                case 2: // a listing that went live elsewhere (bot deposit) is offered to the order
                    def s = seller(f, i)
                    def l = listingRepo.save(new Listing(item: f.item, price: price, status: 'ACTIVE',
                        sellerName: s.displayName, sellerUserId: s.id, rarityScore: BigDecimal.ZERO, condition: ''))
                    listingIds << l.id
                    tasks << ({ -> listingService.offerToBuyOrders(listingRepo.findById(l.id).get()); 'OFFER' } as Callable<String>)
                    break
                default: // a system listing already on the market, swept up by the order's own fill loop
                    def l = listingRepo.save(new Listing(item: f.item, price: price, status: 'ACTIVE',
                        sellerName: "Bot-${f.uniq}", rarityScore: BigDecimal.ZERO, condition: ''))
                    listingIds << l.id
                    tasks << ({ -> buyOrderService.tryFillFromExisting(buyOrderRepo.findById(f.order.id).get()); 'FILL_EXISTING' } as Callable<String>)
            }
        }
        [tasks: tasks, listingIds: listingIds]
    }

    List<String> runConcurrently(List<Callable<String>> tasks) {
        def pool = Executors.newFixedThreadPool(tasks.size())
        def ready = new CountDownLatch(tasks.size())
        def go = new CountDownLatch(1)
        try {
            def futures = tasks.collect { t ->
                pool.submit({ ->
                    ready.countDown()
                    go.await()
                    try { t.call() } catch (Throwable e) { "ERR:${e.class.simpleName}:${e.message}".toString() }
                } as Callable<String>)
            }
            assert ready.await(10, TimeUnit.SECONDS)
            go.countDown()
            futures.collect { it.get(90, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    Map results(Map f, Map plan) {
        def listings = plan.listingIds.collect { listingRepo.findById(it).get() }
        def filled = listings.findAll { it.status == 'SOLD' && it.buyerUserId == f.buyer.id }
        def notFilled = listings - filled
        def purchases = txRepo.findByWalletIdOrderByCreatedAtDesc(f.buyerWallet.id).findAll { it.type == 'PURCHASE' }
        def balance = walletRepo.findById(f.buyerWallet.id).get().balance
        [
            fills        : filled.size(),
            charged      : (f.buyerWallet.balance - balance),
            balance      : balance,
            purchaseRows : purchases.size(),
            purchaseTotal: purchases.sum(BigDecimal.ZERO.setScale(2)) { it.amount } as BigDecimal,
            order        : buyOrderRepo.findById(f.order.id).get(),
            notFilled    : notFilled,
            statuses     : listings*.status,
        ]
    }
}
