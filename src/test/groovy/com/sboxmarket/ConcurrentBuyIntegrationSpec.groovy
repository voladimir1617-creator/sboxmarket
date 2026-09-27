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

    /**
     * Cross-debit serialization on a SINGLE wallet. The two tests above race
     * many buyers against ONE listing — they prove the *Listing* @Version stops
     * two people getting the same item, but each buyer has their own wallet, so
     * they never exercise two debits landing on the SAME wallet at once.
     *
     * This test closes that gap: one buyer whose wallet covers exactly ONE of
     * two equally-priced listings buys BOTH at the same instant from two
     * threads. The only thing standing between "one debit" and "wallet goes
     * negative" is the *Wallet* @Version optimistic lock — the same shared
     * mechanism every other debit path relies on (buy / cart / offer-accept /
     * withdrawal / trade-protection / auction-settle all mutate the wallet via
     * a Hibernate dirty UPDATE … WHERE id=? AND version=?). If a future change
     * bypassed that version bump (e.g. an @Modifying balance UPDATE, or dropping
     * a pre-side-effect flush) two concurrent debits on one wallet could BOTH
     * commit and drive the balance negative. This test fails if that happens.
     */
    def "one wallet, two concurrent buys of different listings — exactly one debit, wallet never goes negative (Wallet @Version)"() {
        given: "a single buyer whose wallet covers exactly ONE of two \$100 listings"
        def uniq = System.nanoTime()
        def buyer  = userRepo.save(new SteamUser(steamId64: "solo${uniq}", displayName: 'Solo'))
        def wallet = walletRepo.save(new Wallet(username: "w_solo_${uniq}", balance: new BigDecimal("100"), currency: 'USD'))

        def itemA = itemRepo.save(new Item(
            name: "XTargetA-${uniq}", category: 'Hats', rarity: 'Limited',
            supply: 5, totalSold: 0, lowestPrice: new BigDecimal("100.00"), iconEmoji: '🎩'
        ))
        def itemB = itemRepo.save(new Item(
            name: "XTargetB-${uniq}", category: 'Hats', rarity: 'Limited',
            supply: 5, totalSold: 0, lowestPrice: new BigDecimal("100.00"), iconEmoji: '🧢'
        ))
        def listingA = listingRepo.save(new Listing(
            item: itemA, price: new BigDecimal("100.00"), status: 'ACTIVE',
            sellerName: "BotA-${uniq}", rarityScore: BigDecimal.ZERO
        ))
        def listingB = listingRepo.save(new Listing(
            item: itemB, price: new BigDecimal("100.00"), status: 'ACTIVE',
            sellerName: "BotB-${uniq}", rarityScore: BigDecimal.ZERO
        ))

        def pool = Executors.newFixedThreadPool(2)
        def tasks = [
            (Callable) { ->
                try { purchaseService.buy(wallet.id, buyer.id, listingA.id); 'WIN_A' }
                catch (ObjectOptimisticLockingFailureException e) { 'LOSE_A' }
                catch (Exception e) { "ERR_A:${e.class.simpleName}" }
            },
            (Callable) { ->
                try { purchaseService.buy(wallet.id, buyer.id, listingB.id); 'WIN_B' }
                catch (ObjectOptimisticLockingFailureException e) { 'LOSE_B' }
                catch (Exception e) { "ERR_B:${e.class.simpleName}" }
            }
        ]

        when: "both buys hit the one wallet at the same instant"
        List<Future> futures = pool.invokeAll(tasks)
        def outcomes = futures.collect { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()

        then: "the wallet never went negative and was debited for exactly ONE \$100 listing"
        def w = walletRepo.findById(wallet.id).get()
        w.balance >= BigDecimal.ZERO
        // Two debits committing would leave -100 (debited == 200). The Wallet
        // @Version guarantees exactly one commits, so exactly one $100 left.
        def debited = new BigDecimal("100") - w.balance
        debited == new BigDecimal("100")

        and: "exactly one PURCHASE transaction exists across both target listings"
        def purchases = txRepo.findByWalletIdOrderByCreatedAtDesc(wallet.id)
            .findAll { (it.listingId == listingA.id || it.listingId == listingB.id) && it.type == 'PURCHASE' }
        purchases.size() == 1

        and: "exactly one listing is SOLD; the loser's tx rolled back so its listing is still ACTIVE"
        def statusA = listingRepo.findById(listingA.id).get().status
        def statusB = listingRepo.findById(listingB.id).get().status
        [statusA, statusB].count { it == 'SOLD' } == 1
        [statusA, statusB].count { it == 'ACTIVE' } == 1

        and: "exactly one buy won; the other lost cleanly (version conflict or insufficient balance), never a both-win"
        outcomes.size() == 2
        outcomes.count { it == 'WIN_A' || it == 'WIN_B' } == 1
        outcomes.each { assert it in ['WIN_A', 'WIN_B', 'LOSE_A', 'LOSE_B'] ||
                               it == 'ERR_A:InsufficientBalanceException' ||
                               it == 'ERR_B:InsufficientBalanceException' }
    }
}
