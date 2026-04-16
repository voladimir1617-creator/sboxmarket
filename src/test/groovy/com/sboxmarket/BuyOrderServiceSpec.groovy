package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.BuyOrder
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.BuyOrderRepository
import com.sboxmarket.repository.ItemRepository
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
 * Unit coverage for the BuyOrder matching engine.
 *
 * Create path: validation + whitelist + quantity cap.
 * Cancel path: owner check + state transition.
 * Match path: the full ordering logic, skip rules (inactive, self-trade,
 * insufficient funds, wrong listing type, hidden), and the first-match-wins
 * exit.
 *
 * Purchase is mocked — we only verify the service orchestrates the matching
 * engine, not the PurchaseService internals (those are in PurchaseServiceSpec).
 */
class BuyOrderServiceSpec extends Specification {

    BuyOrderRepository buyOrderRepository = Mock()
    ItemRepository     itemRepository     = Mock()
    WalletRepository   walletRepository   = Mock()
    SteamUserRepository steamUserRepository = Mock()
    NotificationService notificationService = Mock()
    TextSanitizer      textSanitizer = Mock() {
        cleanShort(_) >> { String s -> s }
    }
    PurchaseService    purchaseService    = Mock()
    BanGuard           banGuard           = Mock()

    @Subject
    BuyOrderService service = new BuyOrderService(
        buyOrderRepository   : buyOrderRepository,
        itemRepository       : itemRepository,
        walletRepository     : walletRepository,
        steamUserRepository  : steamUserRepository,
        notificationService  : notificationService,
        textSanitizer        : textSanitizer,
        purchaseService      : purchaseService,
        banGuard             : banGuard
    )

    private Listing listingFor(Map args = [:]) {
        def item = new Item(
            id:       args.itemId ?: 1L,
            name:     args.itemName ?: 'Wizard Hat',
            category: args.category ?: 'Hats',
            rarity:   args.rarity ?: 'Limited'
        )
        new Listing(
            id:           args.id ?: 100L,
            item:         item,
            price:        args.price ?: new BigDecimal("50.00"),
            sellerUserId: args.seller ?: 99L,
            status:       args.status ?: 'ACTIVE',
            listingType:  args.type ?: 'BUY_NOW',
            hidden:       args.hidden ?: false
        )
    }

    // ── Create ────────────────────────────────────────────────────

    def "create rejects non-positive max price"() {
        when:
        service.create(10L, 'Alice', 1L, 'Hats', 'Limited', price, 1)

        then:
        thrown(BadRequestException)

        where:
        price << [BigDecimal.ZERO, new BigDecimal("-1.00"), null]
    }

    def "create caps quantity at 100 and floors at 1"() {
        given:
        itemRepository.findById(1L) >> Optional.of(new Item(id: 1L, name: 'Wizard Hat'))
        buyOrderRepository.save(_) >> { BuyOrder o -> o }

        when:
        def order = service.create(10L, 'Alice', 1L, 'Hats', 'Limited', new BigDecimal("50"), quantity)

        then:
        order.quantity == expected

        where:
        quantity | expected
        null     | 1
        0        | 1
        50       | 50
        100      | 100
        500      | 100
    }

    def "create whitelists category and rarity — invalid values become null"() {
        given:
        itemRepository.findById(_) >> Optional.empty()
        buyOrderRepository.save(_) >> { BuyOrder o -> o }

        when:
        def order = service.create(10L, 'Alice', null, 'Weapons', 'Mythic', new BigDecimal("50"), 1)

        then:
        order.category == null
        order.rarity == null
    }

    def "create accepts the s&box whitelist categories + rarities"() {
        given:
        itemRepository.findById(_) >> Optional.empty()
        buyOrderRepository.save(_) >> { BuyOrder o -> o }

        when:
        def order = service.create(10L, 'Alice', null, 'Jackets', 'Standard', new BigDecimal("12"), 5)

        then:
        order.category == 'Jackets'
        order.rarity == 'Standard'
        order.quantity == 5
    }

    // ── Cancel ────────────────────────────────────────────────────

    def "cancel flips status and bumps updatedAt for the owner"() {
        given:
        def existing = new BuyOrder(id: 7L, buyerUserId: 10L, status: 'ACTIVE')
        buyOrderRepository.findById(7L) >> Optional.of(existing)
        buyOrderRepository.save(_) >> { BuyOrder o -> o }

        when:
        def result = service.cancel(10L, 7L)

        then:
        result.status == 'CANCELLED'
        result.updatedAt != null
    }

    def "cancel forbids a non-owner"() {
        given:
        def existing = new BuyOrder(id: 7L, buyerUserId: 10L, status: 'ACTIVE')
        buyOrderRepository.findById(7L) >> Optional.of(existing)

        when:
        service.cancel(99L, 7L)

        then:
        thrown(ForbiddenException)
    }

    def "cancel 404s for unknown order id"() {
        given:
        buyOrderRepository.findById(_) >> Optional.empty()

        when:
        service.cancel(10L, 999L)

        then:
        thrown(NotFoundException)
    }

    // ── Match engine ──────────────────────────────────────────────

    def "tryMatch does nothing for non-ACTIVE listings"() {
        given:
        def listing = listingFor(status: 'SOLD')

        when:
        service.tryMatch(listing)

        then:
        0 * buyOrderRepository.findMatching(*_)
    }

    def "tryMatch does nothing for auction listings"() {
        given:
        def listing = listingFor(type: 'AUCTION')

        when:
        service.tryMatch(listing)

        then:
        0 * buyOrderRepository.findMatching(*_)
    }

    def "tryMatch does nothing for hidden listings"() {
        given:
        def listing = listingFor(hidden: true)

        when:
        service.tryMatch(listing)

        then:
        0 * buyOrderRepository.findMatching(*_)
    }

    def "tryMatch fills the first candidate whose wallet can afford the price"() {
        given:
        def listing = listingFor(id: 100L, price: new BigDecimal("50"))
        def order1  = new BuyOrder(id: 1L, buyerUserId: 10L, quantity: 1, status: 'ACTIVE',
                                    maxPrice: new BigDecimal("60"), itemId: 1L)
        def order2  = new BuyOrder(id: 2L, buyerUserId: 20L, quantity: 1, status: 'ACTIVE',
                                    maxPrice: new BigDecimal("55"), itemId: 1L)
        buyOrderRepository.findMatching(_, _, _, _) >> [order1, order2]
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, steamId64: '111'))
        walletRepository.findByUsername('steam_111') >> new Wallet(id: 500L, balance: new BigDecimal("5.00"))  // too poor
        steamUserRepository.findById(20L) >> Optional.of(new SteamUser(id: 20L, steamId64: '222'))
        walletRepository.findByUsername('steam_222') >> new Wallet(id: 600L, balance: new BigDecimal("100.00"))

        when:
        service.tryMatch(listing)

        then:
        1 * purchaseService.buy(600L, 20L, 100L)
        1 * buyOrderRepository.save({ BuyOrder o -> o.id == 2L && o.quantity == 0 && o.status == 'FILLED' })
        1 * notificationService.push(20L, 'BUY_ORDER_FILLED', _, _, _)
    }

    def "tryMatch skips self-trades (seller and buyer are same user)"() {
        given:
        def listing = listingFor(id: 100L, seller: 10L)
        def order   = new BuyOrder(id: 1L, buyerUserId: 10L, quantity: 1, status: 'ACTIVE',
                                    maxPrice: new BigDecimal("100"), itemId: 1L)
        buyOrderRepository.findMatching(_, _, _, _) >> [order]

        when:
        service.tryMatch(listing)

        then:
        0 * purchaseService.buy(*_)
        0 * buyOrderRepository.save(_)
    }

    def "tryMatch decrements quantity without flipping status when 2+ remain"() {
        given:
        def listing = listingFor(id: 100L)
        def order   = new BuyOrder(id: 1L, buyerUserId: 10L, quantity: 3, status: 'ACTIVE',
                                    maxPrice: new BigDecimal("100"), itemId: 1L)
        buyOrderRepository.findMatching(_, _, _, _) >> [order]
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, steamId64: '111'))
        walletRepository.findByUsername('steam_111') >> new Wallet(id: 500L, balance: new BigDecimal("500.00"))

        when:
        service.tryMatch(listing)

        then:
        1 * purchaseService.buy(*_)
        1 * buyOrderRepository.save({ BuyOrder o -> o.quantity == 2 && o.status == 'ACTIVE' })
    }

    def "tryMatch swallows purchase exceptions and tries the next candidate"() {
        given:
        def listing = listingFor(id: 100L, price: new BigDecimal("50"))
        def order1  = new BuyOrder(id: 1L, buyerUserId: 10L, quantity: 1, status: 'ACTIVE',
                                    maxPrice: new BigDecimal("100"), itemId: 1L)
        def order2  = new BuyOrder(id: 2L, buyerUserId: 20L, quantity: 1, status: 'ACTIVE',
                                    maxPrice: new BigDecimal("100"), itemId: 1L)
        buyOrderRepository.findMatching(_, _, _, _) >> [order1, order2]
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, steamId64: '111'))
        walletRepository.findByUsername('steam_111') >> new Wallet(id: 500L, balance: new BigDecimal("500.00"))
        steamUserRepository.findById(20L) >> Optional.of(new SteamUser(id: 20L, steamId64: '222'))
        walletRepository.findByUsername('steam_222') >> new Wallet(id: 600L, balance: new BigDecimal("500.00"))
        // First purchase throws, second succeeds
        purchaseService.buy(500L, 10L, 100L) >> { throw new RuntimeException("race lost") }

        when:
        service.tryMatch(listing)

        then:
        1 * purchaseService.buy(600L, 20L, 100L)
        1 * buyOrderRepository.save({ BuyOrder o -> o.id == 2L && o.status == 'FILLED' })
    }
}
