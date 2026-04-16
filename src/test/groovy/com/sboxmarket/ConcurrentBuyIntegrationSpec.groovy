package com.sboxmarket

import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.PurchaseService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * End-to-end concurrency test for the BUY path. Two buyers race the same
 * listing from two threads. Before the @Version + optimistic-lock fix
 * BOTH buyers' wallets would have been debited; now exactly one wins
 * and the other gets an ObjectOptimisticLockingFailureException (which
 * the HTTP layer maps to 409).
 */
@SpringBootTest
@ActiveProfiles("test")
class ConcurrentBuyIntegrationSpec extends Specification {

    @Autowired ApplicationContext ctx

    PurchaseService       purchaseService
    ListingRepository     listingRepo
    WalletRepository      walletRepo
    ItemRepository        itemRepo
    SteamUserRepository   userRepo
    TransactionRepository txRepo

    def setup() {
        purchaseService = ctx.getBean(PurchaseService)
        listingRepo     = ctx.getBean(ListingRepository)
        walletRepo      = ctx.getBean(WalletRepository)
        itemRepo        = ctx.getBean(ItemRepository)
        userRepo        = ctx.getBean(SteamUserRepository)
        txRepo          = ctx.getBean(TransactionRepository)
    }

    def "ten concurrent buyers of the same listing — exactly one wins, nine lose cleanly"() {
        given: "ten buyers each with enough balance and one shared active listing"
        def uniq = System.nanoTime()
        def buyers = (1..10).collect { i ->
            def u = userRepo.save(new SteamUser(steamId64: "b${i}_${uniq}", displayName: "Buyer${i}"))
            def w = walletRepo.save(new Wallet(username: "w_${i}_${uniq}", balance: new BigDecimal("500"), currency: 'USD'))
            [user: u, wallet: w]
        }
        def item = itemRepo.save(new Item(
            name: "RaceItem10-${uniq}", category: 'Hats', rarity: 'Limited',
            supply: 10, totalSold: 0, lowestPrice: new BigDecimal("100.00"), iconEmoji: '🎩'
        ))
        def listing = listingRepo.save(new Listing(
            item: item, price: new BigDecimal("100.00"), status: 'ACTIVE',
            sellerName: "Bot-${uniq}", rarityScore: BigDecimal.ZERO
        ))

        def pool = Executors.newFixedThreadPool(10)
        def tasks = buyers.collect { b ->
            (Callable) { ->
                try {
                    purchaseService.buy(b.wallet.id, b.user.id, listing.id)
                    return 'WIN'
                } catch (Exception e) {
                    return e.class.simpleName
                }
            }
        }

        when: "all ten threads invoke buy at the same time"
        List<Future> futures = pool.invokeAll(tasks)
        def outcomes = futures.collect { it.get(15, TimeUnit.SECONDS) }
        pool.shutdown()

        then: "the listing is SOLD exactly once"
        def fresh = listingRepo.findById(listing.id).get()
        fresh.status == 'SOLD'

        and: "exactly one buyer lost \$100 from their wallet, the other nine still have \$500"
        def debitedCount = buyers.count { b ->
            def w = walletRepo.findById(b.wallet.id).get()
            w.balance < new BigDecimal("500")
        }
        debitedCount == 1

        and: "exactly one PURCHASE transaction exists for this listing across all ten buyer wallets"
        def totalPurchaseTxs = buyers.sum { b ->
            txRepo.findByWalletIdOrderByCreatedAtDesc(b.wallet.id)
                  .count { it.listingId == listing.id && it.type == 'PURCHASE' }
        }
        totalPurchaseTxs == 1

        and: "no thread returned an outcome we don't recognise"
        outcomes.each { assert it in ['WIN', 'ObjectOptimisticLockingFailureException',
                                       'ListingNotAvailableException', 'BadRequestException',
                                       'InsufficientBalanceException'] }
        outcomes.count { it == 'WIN' } >= 1
    }

    def "two concurrent buyers of the same listing — exactly one wins, the other gets a lock conflict"() {
        given: "two buyers each with enough balance and one shared active listing"
        def uniq = System.nanoTime()

        def buyerA = userRepo.save(new SteamUser(steamId64: "aaa${uniq}", displayName: 'Alice'))
        def buyerB = userRepo.save(new SteamUser(steamId64: "bbb${uniq}", displayName: 'Bob'))

        def walletA = walletRepo.save(new Wallet(username: "w_a_${uniq}", balance: new BigDecimal("500"), currency: 'USD'))
        def walletB = walletRepo.save(new Wallet(username: "w_b_${uniq}", balance: new BigDecimal("500"), currency: 'USD'))

        def item = itemRepo.save(new Item(
            name: "RaceItem-${uniq}", category: 'Hats', rarity: 'Limited',
            supply: 10, totalSold: 0, lowestPrice: new BigDecimal("100.00"), iconEmoji: '🎩'
        ))
        def listing = listingRepo.save(new Listing(
            item: item, price: new BigDecimal("100.00"), status: 'ACTIVE',
            sellerName: "Bot-${uniq}", rarityScore: BigDecimal.ZERO
        ))

        def pool = Executors.newFixedThreadPool(2)
        def tasks = [
            (Callable) { ->
                try { purchaseService.buy(walletA.id, buyerA.id, listing.id); 'A_WIN' }
                catch (ObjectOptimisticLockingFailureException e) { 'A_LOSE' }
                catch (Exception e) { "A_ERR:${e.class.simpleName}" }
            },
            (Callable) { ->
                try { purchaseService.buy(walletB.id, buyerB.id, listing.id); 'B_WIN' }
                catch (ObjectOptimisticLockingFailureException e) { 'B_LOSE' }
                catch (Exception e) { "B_ERR:${e.class.simpleName}" }
            }
        ]

        when: "both threads invoke buy at the same time"
        List<Future> futures = pool.invokeAll(tasks)
        def outcomes = futures.collect { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()

        then: "at least one of them completed (and if both succeeded the listing wasn't actually shared)"
        // In practice the execution is either WIN+LOSE or WIN+some-other-error
        // (InsufficientBalance / ListingNotAvailable). The invariant we care
        // about: the listing is SOLD exactly once and exactly one wallet was
        // debited.
        def fresh = listingRepo.findById(listing.id).get()
        fresh.status == 'SOLD'

        def wa = walletRepo.findById(walletA.id).get()
        def wb = walletRepo.findById(walletB.id).get()
        def totalDebited = (new BigDecimal("500") - wa.balance) + (new BigDecimal("500") - wb.balance)
        totalDebited == new BigDecimal("100")  // only one buyer paid

        and: "exactly one PURCHASE transaction exists for this listing"
        def purchases = [
            txRepo.findByWalletIdOrderByCreatedAtDesc(walletA.id).findAll { it.listingId == listing.id && it.type == 'PURCHASE' },
            txRepo.findByWalletIdOrderByCreatedAtDesc(walletB.id).findAll { it.listingId == listing.id && it.type == 'PURCHASE' }
        ].flatten()
        purchases.size() == 1

        and: "the outcome breakdown is sane"
        outcomes.size() == 2
        // At least one WIN exists (someone bought it)
        outcomes.any { it == 'A_WIN' || it == 'B_WIN' }
    }
}
