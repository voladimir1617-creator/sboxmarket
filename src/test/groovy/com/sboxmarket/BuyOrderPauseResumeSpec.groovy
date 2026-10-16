package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.model.BuyOrder
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.BuyOrderRepository
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.BuyOrderService
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.PurchaseService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.security.BanGuard
import spock.lang.Specification
import spock.lang.Subject

/**
 * Pause / resume a buy order without deleting it. PAUSED is a new value in
 * the existing status column, so stored orders keep reading as ACTIVE and
 * nothing is migrated. A paused order must never fill.
 */
class BuyOrderPauseResumeSpec extends Specification {

    BuyOrderRepository  buyOrderRepository  = Mock()
    ItemRepository      itemRepository      = Mock()
    WalletRepository    walletRepository    = Mock()
    SteamUserRepository steamUserRepository = Mock()
    NotificationService notificationService = Mock()
    TextSanitizer       textSanitizer       = Mock() { cleanShort(_) >> { String s -> s } }
    PurchaseService     purchaseService     = Mock()
    BanGuard            banGuard            = Mock()

    @Subject
    BuyOrderService service = new BuyOrderService(
        buyOrderRepository  : buyOrderRepository,
        itemRepository      : itemRepository,
        walletRepository    : walletRepository,
        steamUserRepository : steamUserRepository,
        notificationService : notificationService,
        textSanitizer       : textSanitizer,
        purchaseService     : purchaseService,
        banGuard            : banGuard
    )

    private BuyOrder order(String status, Map extra = [:]) {
        new BuyOrder([id: 7L, buyerUserId: 10L, itemId: 1L, status: status,
                      maxPrice: new BigDecimal('30'), quantity: 1, originalQuantity: 1,
                      updatedAt: 1000L] + extra)
    }

    def "pause flips an active order to PAUSED and keeps it on file"() {
        given:
        def o = order('ACTIVE')
        buyOrderRepository.findById(7L) >> Optional.of(o)
        buyOrderRepository.save(_) >> { BuyOrder b -> b }

        when:
        def r = service.pause(10L, 7L)

        then:
        r.status == 'PAUSED'
        r.updatedAt > 1000L
        r.quantity == 1
        r.maxPrice == new BigDecimal('30')
    }

    def "pause is an idempotent no-op on an already paused order"() {
        given:
        buyOrderRepository.findById(7L) >> Optional.of(order('PAUSED'))

        when:
        def r = service.pause(10L, 7L)

        then:
        r.status == 'PAUSED'
        r.updatedAt == 1000L
        0 * buyOrderRepository.save(_)
    }

    def "pause refuses a #status order"() {
        given:
        def o = order(status)
        buyOrderRepository.findById(7L) >> Optional.of(o)

        when:
        service.pause(10L, 7L)

        then:
        def e = thrown(BadRequestException)
        e.code == 'NOT_ACTIVE'
        o.status == status
        0 * buyOrderRepository.save(_)

        where:
        status << ['FILLED', 'CANCELLED', 'EXPIRED']
    }

    def "pause and resume forbid a non-owner before any status check"() {
        given:
        buyOrderRepository.findById(7L) >> Optional.of(order(status))

        when:
        service."$method"(99L, 7L)

        then:
        thrown(ForbiddenException)
        0 * buyOrderRepository.save(_)

        where:
        method   | status
        'pause'  | 'FILLED'
        'resume' | 'PAUSED'
    }

    def "resume turns a paused order back on and restarts the idle clock"() {
        given:
        def o = order('PAUSED')
        buyOrderRepository.findById(7L) >> Optional.of(o)
        buyOrderRepository.countActiveByBuyer(10L) >> 0L
        buyOrderRepository.save(_) >> { BuyOrder b -> b }

        when:
        def r = service.resume(10L, 7L)

        then:
        1 * banGuard.assertNotBanned(10L)
        r.status == 'ACTIVE'
        r.updatedAt > 1000L
    }

    def "resume fills at once from a listing already on the market"() {
        given:
        def listingRepo = Mock(ListingRepository)
        service.listingRepository = listingRepo
        def o = order('PAUSED')
        buyOrderRepository.findById(7L) >> Optional.of(o)
        buyOrderRepository.countActiveByBuyer(10L) >> 0L
        buyOrderRepository.save(_) >> { BuyOrder b -> b }
        def listing = new Listing(id: 100L, price: new BigDecimal('25'), sellerUserId: 99L,
            status: 'ACTIVE', listingType: 'BUY_NOW', hidden: false,
            item: new Item(id: 1L, name: 'Wizard Hat', category: 'Hats', rarity: 'Limited'))
        listingRepo.findMatchingForBuyOrder(1L, null, null, new BigDecimal('30'), _) >> [listing]
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, steamId64: 'aaa'))
        walletRepository.findByUsername('steam_aaa') >> new Wallet(id: 200L, balance: new BigDecimal('100'))

        when:
        def r = service.resume(10L, 7L)

        then:
        1 * purchaseService.buy(200L, 10L, 100L, _) >> [success: true]
        r.status == 'FILLED'
        r.quantity == 0
    }

    def "resume respects the per-buyer active-order cap"() {
        given:
        def o = order('PAUSED')
        buyOrderRepository.findById(7L) >> Optional.of(o)
        buyOrderRepository.countActiveByBuyer(10L) >> BuyOrderService.MAX_ACTIVE_ORDERS_PER_BUYER

        when:
        service.resume(10L, 7L)

        then:
        def e = thrown(BadRequestException)
        e.code == 'BUY_ORDER_CAP'
        o.status == 'PAUSED'
        0 * buyOrderRepository.save(_)
    }

    def "resume refuses while the buyer's wallet is frozen"() {
        given:
        def o = order('PAUSED')
        buyOrderRepository.findById(7L) >> Optional.of(o)
        buyOrderRepository.countActiveByBuyer(10L) >> 0L
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, steamId64: 'aaa'))
        walletRepository.findByUsername('steam_aaa') >> new Wallet(id: 200L, frozen: true)

        when:
        service.resume(10L, 7L)

        then:
        def e = thrown(BadRequestException)
        e.code == 'WALLET_FROZEN'
        o.status == 'PAUSED'
    }

    def "resume is an idempotent no-op on an active order"() {
        given:
        buyOrderRepository.findById(7L) >> Optional.of(order('ACTIVE'))

        when:
        def r = service.resume(10L, 7L)

        then:
        r.status == 'ACTIVE'
        r.updatedAt == 1000L
        0 * buyOrderRepository.save(_)
    }

    def "resume refuses a #status order"() {
        given:
        buyOrderRepository.findById(7L) >> Optional.of(order(status))

        when:
        service.resume(10L, 7L)

        then:
        def e = thrown(BadRequestException)
        e.code == 'NOT_PAUSED'
        0 * buyOrderRepository.save(_)

        where:
        status << ['FILLED', 'CANCELLED', 'EXPIRED']
    }

    def "a paused order is skipped by the matcher even if handed to it"() {
        given:
        def paused = order('PAUSED', [itemId: null])
        buyOrderRepository.findMatching(*_) >> [paused]
        def listing = new Listing(id: 100L, price: new BigDecimal('25'), sellerUserId: 99L,
            status: 'ACTIVE', listingType: 'BUY_NOW', hidden: false,
            item: new Item(id: 1L, name: 'Wizard Hat', category: 'Hats', rarity: 'Limited'))

        when:
        service.tryMatch(listing)

        then:
        0 * purchaseService.buy(*_)
        paused.status == 'PAUSED'
    }

    def "cancel and edit work on a paused order"() {
        given:
        def o = order('PAUSED')
        buyOrderRepository.findById(7L) >> Optional.of(o)
        buyOrderRepository.save(_) >> { BuyOrder b -> b }

        when:
        def edited = service.update(10L, 7L, new BigDecimal('40'), null)

        then: "edited but still paused, and no fill probe ran"
        edited.maxPrice == new BigDecimal('40.00')
        edited.status == 'PAUSED'
        0 * purchaseService.buy(*_)

        when:
        def cancelled = service.cancel(10L, 7L)

        then:
        cancelled.status == 'CANCELLED'
    }

    def "cancel-all clears paused orders as well as active ones"() {
        given:
        def a = order('ACTIVE', [id: 1L])
        def p = order('PAUSED', [id: 2L])
        def f = order('FILLED', [id: 3L])
        buyOrderRepository.findByBuyer(10L) >> [a, p, f]
        buyOrderRepository.findById(_) >> { Long id -> Optional.of([a, p, f].find { it.id == id }) }
        buyOrderRepository.save(_) >> { BuyOrder b -> b }

        when:
        int n = service.cancelAllForUser(10L)

        then:
        n == 2
        a.status == 'CANCELLED'
        p.status == 'CANCELLED'
        f.status == 'FILLED'
    }
}
