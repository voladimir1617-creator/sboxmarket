package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Trade
import com.sboxmarket.model.TradeProtection
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TradeProtectionRepository
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.BidService
import com.sboxmarket.service.PurchaseService
import com.sboxmarket.service.TradeService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification
import spock.lang.Timeout

import java.util.concurrent.TimeUnit

/**
 * Cancel and release of a protected trade, through the real Spring
 * transaction proxies and a real database.
 *
 * Both paths take a PESSIMISTIC_WRITE lock on the trade's protection row
 * (TradeProtectionService.lockAndExpireIfActiveOrReportClaimed) in their
 * own transaction. Before the fix they then called a REQUIRES_NEW method
 * on the same row (cancel → expire(), release → reverseClaim()), which
 * opened a second connection and waited on the lock the suspended outer
 * transaction holds: a self-deadlock. On Postgres, with no lock timeout,
 * that wait never ends.
 *
 * This spec runs on its own H2 database with a 60 s lock timeout, so a
 * regression waits far past the @Timeout below and fails the test instead
 * of passing after a short default timeout.
 */
@SpringBootTest(properties = [
    'spring.datasource.url=jdbc:h2:mem:protected-trade-selflock;LOCK_TIMEOUT=60000;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE'
])
@ActiveProfiles("test")
class ProtectedTradeSettleNoSelfLockSpec extends Specification {

    @Autowired TradeService tradeService
    @Autowired PurchaseService purchaseService
    @Autowired BidService bidService
    @Autowired SteamUserRepository userRepo
    @Autowired WalletRepository walletRepo
    @Autowired ItemRepository itemRepo
    @Autowired ListingRepository listingRepo
    @Autowired TradeRepository tradeRepo
    @Autowired TradeProtectionRepository protectionRepo

    String uniq = String.valueOf(System.nanoTime())
    static final java.util.concurrent.atomic.AtomicLong SEQ = new java.util.concurrent.atomic.AtomicLong(1)

    private SteamUser user(String tag, String role = 'USER') {
        def tail = String.valueOf(SEQ.getAndIncrement())
        userRepo.save(new SteamUser(steamId64: '7656119' + tail.padLeft(10, '0'),
            displayName: "${tag}-${uniq}", role: role,
            tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc'))
    }

    private Wallet walletFor(SteamUser u, BigDecimal balance) {
        walletRepo.save(new Wallet(username: "steam_${u.steamId64}", balance: balance, currency: 'USD'))
    }

    private Map protectedTrade(String state, String protectionStatus, BigDecimal buyerBalance) {
        def buyer = user('buyer')
        def seller = user('seller')
        def bw = walletFor(buyer, buyerBalance)
        def sw = walletFor(seller, 0G)
        def item = itemRepo.save(new Item(name: "Hat ${uniq}", category: 'Hats', rarity: 'Standard',
            supply: 1, totalSold: 0, lowestPrice: 50G, isListed: true, iconEmoji: '🎩'))
        def listing = listingRepo.save(new Listing(item: item, sellerUserId: seller.id,
            sellerName: seller.displayName, price: 50G, status: 'SOLD', buyerUserId: buyer.id,
            listingType: 'BUY_NOW'))
        def trade = tradeRepo.save(new Trade(listingId: listing.id, itemId: item.id, itemName: item.name,
            buyerUserId: buyer.id, sellerUserId: seller.id, buyerWalletId: bw.id, sellerWalletId: sw.id,
            price: new BigDecimal('50.00'), feeAmount: new BigDecimal('1.00'), state: state))
        def protection = protectionRepo.save(new TradeProtection(tradeId: trade.id, buyerUserId: buyer.id,
            feeAmount: new BigDecimal('1.00'), coverageAmount: new BigDecimal('50.00'),
            status: protectionStatus,
            claimReason: protectionStatus == TradeProtection.CLAIMED ? 'Trade disputed by buyer' : null))
        [buyer: buyer, seller: seller, buyerWallet: bw, sellerWallet: sw, item: item,
         listing: listing, trade: trade, protection: protection]
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    def "cancelling a trade with ACTIVE protection completes, refunds the buyer once and consumes the cover"() {
        given:
        def f = protectedTrade('PENDING_SELLER_ACCEPT', TradeProtection.ACTIVE, 0G)

        when: 'the buyer cancels before the seller accepts'
        tradeService.cancel(f.buyer.id, f.trade.id, 'changed my mind')

        then:
        tradeRepo.findById(f.trade.id).get().state == 'CANCELLED'
        walletRepo.findById(f.buyerWallet.id).get().balance == new BigDecimal('50.00')
        protectionRepo.findByTradeId(f.trade.id).status == TradeProtection.EXPIRED
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    def "force-releasing a trade whose protection was CLAIMED completes and claws the cover back from the buyer"() {
        given: 'the buyer disputed and autoClaim already paid the $50 cover into their wallet'
        def f = protectedTrade('DISPUTED', TradeProtection.CLAIMED, new BigDecimal('50.00'))
        def admin = user('admin', 'ADMIN')

        when: 'staff rule for the seller'
        tradeService.adminRelease(admin.id, f.trade.id, 'seller delivered')

        then: 'seller is paid, the cover is taken back in the same transaction, the claim is resolved'
        tradeRepo.findById(f.trade.id).get().state == 'VERIFIED'
        walletRepo.findById(f.sellerWallet.id).get().balance == new BigDecimal('49.00')
        walletRepo.findById(f.buyerWallet.id).get().balance == new BigDecimal('0.00')
        def p = protectionRepo.findByTradeId(f.trade.id)
        p.status == TradeProtection.EXPIRED
        p.claimReason.startsWith('Claim reversed')
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    def "an unresolved claim payout can't be spent on a purchase or a bid, and the hold lifts once the trade is cancelled"() {
        given:
        def f = protectedTrade('DISPUTED', TradeProtection.CLAIMED, new BigDecimal('50.00'))
        def otherSeller = user('other-seller')
        def auction = listingRepo.save(new Listing(item: f.item, sellerUserId: otherSeller.id,
            sellerName: otherSeller.displayName, price: 10G, status: 'ACTIVE', listingType: 'AUCTION',
            expiresAt: System.currentTimeMillis() + 3_600_000L))

        when: 'the buyer tries to buy with the claimed cover'
        purchaseService.buy(f.buyerWallet.id, f.buyer.id, 999_999_999L)

        then:
        def buyHold = thrown(BadRequestException)
        buyHold.code == 'PURCHASE_CLAIM_HOLD'

        when: 'the buyer tries to bid with it'
        bidService.placeBid(f.buyer.id, f.buyer.displayName, auction.id, 12G)

        then:
        def bidHold = thrown(BadRequestException)
        bidHold.code == 'PURCHASE_CLAIM_HOLD'

        when: 'staff rule for the buyer (cancel): the buyer keeps the cover and the hold lifts'
        def admin = user('admin2', 'ADMIN')
        tradeService.cancel(admin.id, f.trade.id, 'seller at fault')
        purchaseService.buy(f.buyerWallet.id, f.buyer.id, 999_999_999L)

        then: 'the purchase now gets past the hold and fails only on the unknown listing'
        thrown(NotFoundException)
        tradeRepo.findById(f.trade.id).get().state == 'CANCELLED'
        // Already paid by the claim: no second refund from escrow.
        walletRepo.findById(f.buyerWallet.id).get().balance == new BigDecimal('50.00')
    }
}
