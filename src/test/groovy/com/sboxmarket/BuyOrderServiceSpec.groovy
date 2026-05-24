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

    def "create rejects a buyer who is at the active-order cap"() {
        given:
        buyOrderRepository.countActiveByBuyer(10L) >> BuyOrderService.MAX_ACTIVE_ORDERS_PER_BUYER

        when:
        service.create(10L, 'Alice', 1L, 'Hats', 'Limited', new BigDecimal('50'), 1)

        then:
        def e = thrown(BadRequestException)
        e.code == 'BUY_ORDER_CAP'
        and: "the cap is checked before any order row is created"
        0 * buyOrderRepository.save(_)
    }

    def "create allows a buyer one below the active-order cap"() {
        given:
        buyOrderRepository.countActiveByBuyer(10L) >> (BuyOrderService.MAX_ACTIVE_ORDERS_PER_BUYER - 1L)
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, steamId64: '7656117',
            tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc'))
        walletRepository.findByUsername('steam_7656117') >> new Wallet(id: 1L, balance: new BigDecimal('100'))
        itemRepository.findById(_) >> Optional.empty()
        buyOrderRepository.save(_) >> { BuyOrder o -> o }

        when:
        def order = service.create(10L, 'Alice', null, 'Hats', 'Limited', new BigDecimal('50'), 1)

        then:
        order != null
        order.status == 'ACTIVE'
    }

    def "create rejects a buyer with no Steam trade URL (batch 388)"() {
        given:
        buyOrderRepository.countActiveByBuyer(10L) >> 0L
        // Trade URL blank — the matcher's PurchaseService.buy would
        // reject every fill, so refuse the order upfront.
        steamUserRepository.findById(10L) >> Optional.of(
            new SteamUser(id: 10L, steamId64: '7656117', tradeUrl: '   '))

        when:
        service.create(10L, 'Alice', 1L, 'Hats', 'Limited', new BigDecimal('50'), 1)

        then:
        def e = thrown(BadRequestException)
        e.code == 'TRADE_URL_MISSING'
        0 * buyOrderRepository.save(_)
    }

    def "create rejects a buyer with an unresolved deposit dispute (batch 511)"() {
        given:
        buyOrderRepository.countActiveByBuyer(10L) >> 0L
        def buyer = new SteamUser(id: 10L, steamId64: '7656117', displayName: 'Alice',
            tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc')
        def wallet = new Wallet(id: 500L, username: 'steam_7656117',
            balance: new BigDecimal('100'), frozen: false)
        steamUserRepository.findById(10L) >> Optional.of(buyer)
        walletRepository.findByUsername('steam_7656117') >> wallet
        def txRepo = Mock(com.sboxmarket.repository.TransactionRepository)
        service.transactionRepository = txRepo
        txRepo.countActiveDisputedDeposits(500L) >> 2L

        when:
        service.create(10L, 'Alice', 1L, 'Hats', 'Limited', new BigDecimal('50'), 1)

        then:
        def e = thrown(BadRequestException)
        e.code == 'PURCHASE_DISPUTE_HOLD'
        0 * buyOrderRepository.save(_)
    }

    def "create rejects a banned buyer before any work happens"() {
        given:
        banGuard.assertNotBanned(10L) >> { throw new ForbiddenException("Account banned") }

        when:
        service.create(10L, 'Alice', 1L, 'Hats', 'Limited', new BigDecimal('50'), 1)

        then:
        thrown(ForbiddenException)
        0 * buyOrderRepository.countActiveByBuyer(_)
        0 * buyOrderRepository.save(_)
    }

    def "create rejects a max price above the \$100k cap"() {
        given:
        buyOrderRepository.countActiveByBuyer(10L) >> 0L
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, steamId64: '7656117',
            tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc'))
        walletRepository.findByUsername('steam_7656117') >> new Wallet(id: 1L, balance: new BigDecimal('1'))

        when:
        service.create(10L, 'Alice', 1L, 'Hats', 'Limited', new BigDecimal('100000.01'), 1)

        then:
        def e = thrown(BadRequestException)
        e.code == 'PRICE_TOO_HIGH'
        0 * buyOrderRepository.save(_)
    }

    def "create refuses a buyer with a frozen wallet (batch 511)"() {
        given:
        def buyer = new SteamUser(id: 10L, steamId64: '7656117', displayName: 'Alice',
                                  tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc')
        def wallet = new Wallet(id: 500L, username: 'steam_7656117', balance: new BigDecimal('100'),
                                frozen: true, frozenReason: 'Staff freeze')
        steamUserRepository.findById(10L) >> Optional.of(buyer)
        walletRepository.findByUsername('steam_7656117') >> wallet

        when:
        service.create(10L, 'Alice', 1L, 'Hats', 'Limited', new BigDecimal('50'), 1)

        then:
        def e = thrown(BadRequestException)
        e.code == 'WALLET_FROZEN'
        and: "no order row created"
        0 * buyOrderRepository.save(_)
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

    def "cancel is an idempotent no-op on an already-CANCELLED order"() {
        given:
        def existing = new BuyOrder(id: 7L, buyerUserId: 10L, status: 'CANCELLED',
                                     updatedAt: 1234L)
        buyOrderRepository.findById(7L) >> Optional.of(existing)

        when:
        def result = service.cancel(10L, 7L)

        then:
        // Returned untouched — no second save, no updatedAt bump.
        result.status == 'CANCELLED'
        result.updatedAt == 1234L
        0 * buyOrderRepository.save(_)
    }

    def "cancel refuses to clobber a terminal-state order"() {
        given:
        // A FILLED order means the buyer paid and items shipped; an
        // EXPIRED order carries a reason (ban / wallet hold / staleness).
        // Stamping CANCELLED over either would rewrite history.
        def existing = new BuyOrder(id: 7L, buyerUserId: 10L, status: status)
        buyOrderRepository.findById(7L) >> Optional.of(existing)

        when:
        service.cancel(10L, 7L)

        then:
        def e = thrown(BadRequestException)
        e.code == 'NOT_ACTIVE'
        and: "the terminal status is left intact"
        existing.status == status
        0 * buyOrderRepository.save(_)

        where:
        status << ['FILLED', 'EXPIRED']
    }

    def "cancel checks ownership before the status guard"() {
        given:
        // A non-owner hitting a FILLED order must get FORBIDDEN, not
        // NOT_ACTIVE — ownership is the first gate so we never leak the
        // order's state to a stranger.
        def existing = new BuyOrder(id: 7L, buyerUserId: 10L, status: 'FILLED')
        buyOrderRepository.findById(7L) >> Optional.of(existing)

        when:
        service.cancel(99L, 7L)

        then:
        thrown(ForbiddenException)
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
        buyOrderRepository.findMatching(_, _, _, _, _) >> [order1, order2]
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, steamId64: '111'))
        walletRepository.findByUsername('steam_111') >> new Wallet(id: 500L, balance: new BigDecimal("5.00"))  // too poor
        steamUserRepository.findById(20L) >> Optional.of(new SteamUser(id: 20L, steamId64: '222'))
        walletRepository.findByUsername('steam_222') >> new Wallet(id: 600L, balance: new BigDecimal("100.00"))

        when:
        service.tryMatch(listing)

        then:
        1 * purchaseService.buy(600L, 20L, 100L)
        1 * buyOrderRepository.save({ BuyOrder o -> o.id == 2L && o.quantity == 0 && o.status == 'FILLED' })
        1 * notificationService.push(20L, 'BUY_ORDER_FILLED', _, _, _, _)
    }

    def "tryMatch emails the buyer a BUY_ORDER_FILLED receipt when their email is verified (batch 574)"() {
        given:
        def listing = listingFor(id: 100L, price: new BigDecimal("50"))
        def order   = new BuyOrder(id: 1L, buyerUserId: 10L, quantity: 1, status: 'ACTIVE',
                                    maxPrice: new BigDecimal("60"), itemId: 1L)
        buyOrderRepository.findMatching(_, _, _, _, _) >> [order]
        def buyer = new SteamUser(id: 10L, steamId64: '111',
            email: 'alice@example.com', emailVerified: true,
            emailNotificationsEnabled: true)
        steamUserRepository.findById(10L) >> Optional.of(buyer)
        walletRepository.findByUsername('steam_111') >> new Wallet(id: 500L, balance: new BigDecimal("100.00"))
        def emailSvc = Mock(com.sboxmarket.service.EmailService) {
            canSendTo(_, _) >> { user, bucket ->
                user != null &&
                user.email && !user.email.isEmpty() &&
                Boolean.TRUE.equals(user.emailVerified) &&
                Boolean.TRUE.equals(user.emailNotificationsEnabled)
            }
        }
        service.emailService = emailSvc

        when:
        service.tryMatch(listing)

        then:
        1 * purchaseService.buy(500L, 10L, 100L)
        1 * emailSvc.sendBuyOrderFilled('alice@example.com', _,
            listing.item.name, new BigDecimal("50"), new BigDecimal("60"), _)
    }

    def "tryMatch expires the order and skips the fill when the buyer is banned (batch 331)"() {
        given:
        def listing = listingFor(id: 100L, price: new BigDecimal("50"))
        def bannedOrder = new BuyOrder(id: 1L, buyerUserId: 10L, quantity: 1, status: 'ACTIVE',
                                        maxPrice: new BigDecimal("60"), itemId: 1L)
        def goodOrder   = new BuyOrder(id: 2L, buyerUserId: 20L, quantity: 1, status: 'ACTIVE',
                                        maxPrice: new BigDecimal("60"), itemId: 1L)
        buyOrderRepository.findMatching(_, _, _, _, _) >> [bannedOrder, goodOrder]
        // Buyer 10 was banned AFTER placing the buy order — tryMatch must
        // NOT drain their wallet on a trade they can't complete.
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, steamId64: '111', banned: true))
        steamUserRepository.findById(20L) >> Optional.of(new SteamUser(id: 20L, steamId64: '222', banned: false))
        walletRepository.findByUsername('steam_222') >> new Wallet(id: 600L, balance: new BigDecimal("100.00"))

        when:
        service.tryMatch(listing)

        then:
        // Banned buyer's order flipped to EXPIRED so it doesn't keep spinning.
        1 * buyOrderRepository.save({ BuyOrder o -> o.id == 1L && o.status == 'EXPIRED' })
        // Second (non-banned) buyer gets the fill.
        1 * purchaseService.buy(600L, 20L, 100L)
        1 * buyOrderRepository.save({ BuyOrder o -> o.id == 2L && o.status == 'FILLED' })
        // Banned buyer's wallet lookup never happens — we skip before that.
        0 * walletRepository.findByUsername('steam_111')
    }

    def "tryMatch skips self-trades (seller and buyer are same user)"() {
        given:
        def listing = listingFor(id: 100L, seller: 10L)
        def order   = new BuyOrder(id: 1L, buyerUserId: 10L, quantity: 1, status: 'ACTIVE',
                                    maxPrice: new BigDecimal("100"), itemId: 1L)
        buyOrderRepository.findMatching(_, _, _, _, _) >> [order]

        when:
        service.tryMatch(listing)

        then:
        0 * purchaseService.buy(*_)
        0 * buyOrderRepository.save(_)
    }

    def "tryMatch expires a buy order whose owner's wallet is frozen (batch 517)"() {
        given:
        def listing = listingFor(id: 100L)
        def order   = new BuyOrder(id: 1L, buyerUserId: 10L, quantity: 1, status: 'ACTIVE',
                                    maxPrice: new BigDecimal('100'), itemId: 1L)
        buyOrderRepository.findMatching(_, _, _, _, _) >> [order]
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, steamId64: '111'))
        def frozenWallet = new Wallet(id: 500L, username: 'steam_111',
                                       balance: new BigDecimal('500.00'),
                                       frozen: true, frozenReason: 'staff action')
        walletRepository.findByUsername('steam_111') >> frozenWallet

        when:
        service.tryMatch(listing)

        then:
        0 * purchaseService.buy(*_)
        // Expired + notify fires via expireOrderWithHold
        1 * buyOrderRepository.save({ BuyOrder o -> o.id == 1L && o.status == 'EXPIRED' })
        1 * notificationService.push(10L, 'BUY_ORDER_EXPIRED', _, _, 1L, '/profile?tab=buyorders')
    }

    def "tryMatch expires a buy order whose owner has an active deposit dispute (batch 517)"() {
        given:
        def listing = listingFor(id: 100L)
        def order   = new BuyOrder(id: 1L, buyerUserId: 10L, quantity: 1, status: 'ACTIVE',
                                    maxPrice: new BigDecimal('100'), itemId: 1L)
        def txRepo = Mock(com.sboxmarket.repository.TransactionRepository)
        service.transactionRepository = txRepo
        buyOrderRepository.findMatching(_, _, _, _, _) >> [order]
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, steamId64: '111'))
        def wallet = new Wallet(id: 500L, username: 'steam_111', balance: new BigDecimal('500.00'))
        walletRepository.findByUsername('steam_111') >> wallet
        txRepo.countActiveDisputedDeposits(500L) >> 1L

        when:
        service.tryMatch(listing)

        then:
        0 * purchaseService.buy(*_)
        1 * buyOrderRepository.save({ BuyOrder o -> o.id == 1L && o.status == 'EXPIRED' })
        1 * notificationService.push(10L, 'BUY_ORDER_EXPIRED',
            { String t -> t.contains('dispute') }, _, 1L, '/profile?tab=buyorders')
    }

    def "tryMatch decrements quantity without flipping status when 2+ remain"() {
        given:
        def listing = listingFor(id: 100L)
        def order   = new BuyOrder(id: 1L, buyerUserId: 10L, quantity: 3, status: 'ACTIVE',
                                    maxPrice: new BigDecimal("100"), itemId: 1L)
        buyOrderRepository.findMatching(_, _, _, _, _) >> [order]
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
        buyOrderRepository.findMatching(_, _, _, _, _) >> [order1, order2]
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

    def "tryMatch fills against the pessimistically-locked order copy, not the stale candidate"() {
        given: "findMatching saw the candidate; findByIdForUpdate returns the locked row"
        def listing     = listingFor(id: 100L, price: new BigDecimal("50"))
        def staleOrder  = new BuyOrder(id: 1L, buyerUserId: 10L, quantity: 2, status: 'ACTIVE',
                                        maxPrice: new BigDecimal("60"), itemId: 1L)
        def lockedOrder = new BuyOrder(id: 1L, buyerUserId: 10L, quantity: 2, status: 'ACTIVE',
                                        maxPrice: new BigDecimal("60"), itemId: 1L)
        buyOrderRepository.findMatching(_, _, _, _, _) >> [staleOrder]
        buyOrderRepository.findByIdForUpdate(1L) >> lockedOrder
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, steamId64: '111'))
        walletRepository.findByUsername('steam_111') >> new Wallet(id: 500L, balance: new BigDecimal("100.00"))

        when:
        service.tryMatch(listing)

        then: "the buy + decrement land on the LOCKED copy; the stale candidate is untouched"
        1 * purchaseService.buy(500L, 10L, 100L)
        1 * buyOrderRepository.save({ BuyOrder o -> o.is(lockedOrder) && o.quantity == 1 })
        staleOrder.quantity == 2
    }

    def "tryMatch skips the fill when the locked re-read shows the order was already drained (over-fill guard)"() {
        given: "findMatching saw quantity 1, but a concurrent fill already took the locked row to 0"
        def listing      = listingFor(id: 100L, price: new BigDecimal("50"))
        def staleOrder   = new BuyOrder(id: 1L, buyerUserId: 10L, quantity: 1, status: 'ACTIVE',
                                         maxPrice: new BigDecimal("60"), itemId: 1L)
        def drainedOrder = new BuyOrder(id: 1L, buyerUserId: 10L, quantity: 0, status: 'FILLED',
                                         maxPrice: new BigDecimal("60"), itemId: 1L)
        buyOrderRepository.findMatching(_, _, _, _, _) >> [staleOrder]
        buyOrderRepository.findByIdForUpdate(1L) >> drainedOrder
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, steamId64: '111'))
        walletRepository.findByUsername('steam_111') >> new Wallet(id: 500L, balance: new BigDecimal("100.00"))

        when:
        service.tryMatch(listing)

        then: "the locked re-verify blocks the double-fill — quantity 0 means another listing filled it"
        0 * purchaseService.buy(*_)
        0 * buyOrderRepository.save(_)
    }

    def "tryMatch skips the fill when the locked order's maxPrice no longer covers the listing price (cap guard)"() {
        given: "findMatching saw maxPrice 60; the locked re-read shows the buyer has since lowered it to 40"
        def listing     = listingFor(id: 100L, price: new BigDecimal("50"))
        def staleOrder  = new BuyOrder(id: 1L, buyerUserId: 10L, quantity: 1, status: 'ACTIVE',
                                        maxPrice: new BigDecimal("60"), itemId: 1L)
        def lockedOrder = new BuyOrder(id: 1L, buyerUserId: 10L, quantity: 1, status: 'ACTIVE',
                                        maxPrice: new BigDecimal("40"), itemId: 1L)
        buyOrderRepository.findMatching(_, _, _, _, _) >> [staleOrder]
        buyOrderRepository.findByIdForUpdate(1L) >> lockedOrder
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, steamId64: '111'))
        walletRepository.findByUsername('steam_111') >> new Wallet(id: 500L, balance: new BigDecimal("100.00"))

        when:
        service.tryMatch(listing)

        then: "the cap guard reads the LOCKED maxPrice and blocks the over-cap buy"
        0 * purchaseService.buy(*_)
        0 * buyOrderRepository.save(_)
    }

    // ── countActiveForItem ────────────────────────────────────────

    def "countActiveForItem forwards to the repo"() {
        given:
        buyOrderRepository.countActiveForItem(42L) >> 7L

        expect:
        service.countActiveForItem(42L) == 7L
    }

    def "countActiveForItem returns 0 for null item id without hitting the repo"() {
        when:
        def n = service.countActiveForItem(null)

        then:
        n == 0L
        0 * buyOrderRepository.countActiveForItem(_)
    }

    // ── bestBidForItem ────────────────────────────────────────────

    def "bestBidForItem forwards to the repo and returns the top bid"() {
        given:
        buyOrderRepository.findBestBidForItem(42L) >> new BigDecimal("25.50")

        expect:
        service.bestBidForItem(42L) == new BigDecimal("25.50")
    }

    def "bestBidForItem returns ZERO when the repo returns null (no demand)"() {
        given:
        buyOrderRepository.findBestBidForItem(42L) >> null

        expect:
        service.bestBidForItem(42L) == BigDecimal.ZERO
    }

    def "bestBidForItem returns ZERO for null item id without hitting the repo"() {
        when:
        def b = service.bestBidForItem(null)

        then:
        b == BigDecimal.ZERO
        0 * buyOrderRepository.findBestBidForItem(_)
    }

    // ── listActiveForItem (batch 639) ──────────────────────────────────

    def "listActiveForItem returns decorated rows in top-of-book order without counterparty identity"() {
        given:
        def rows = [
            new BuyOrder(id: 1L, buyerUserId: 10L, itemId: 42L, maxPrice: new BigDecimal("25.50"), quantity: 2, createdAt: 1000L),
            new BuyOrder(id: 2L, buyerUserId: 11L, itemId: 42L, maxPrice: new BigDecimal("20.00"), quantity: 1, createdAt: 2000L)
        ]
        buyOrderRepository.findActiveForItem(42L, _) >> rows

        when:
        def out = service.listActiveForItem(42L, 10)

        then:
        out.size() == 2
        out[0].maxPrice == new BigDecimal("25.50")
        out[0].id == 1L
        out[0].quantity == 2
        out[0].createdAt == 1000L
        // Safety invariant: the aggregate signal must NOT surface buyer id,
        // display name, or avatar. Same policy as every other public buy-
        // order endpoint — stops scrapers from targeting high bidders for
        // private trade pitches.
        !out[0].containsKey('buyerUserId')
        !out[0].containsKey('buyerName')
        !out[0].containsKey('buyerAvatar')
    }

    def "listActiveForItem short-circuits on null itemId"() {
        when:
        def out = service.listActiveForItem(null, 10)

        then:
        out == []
        0 * buyOrderRepository.findActiveForItem(*_)
    }

    def "listActiveForItem clamps limit to [1, 20]"() {
        given:
        def captured = null
        buyOrderRepository.findActiveForItem(42L, _) >> { args ->
            captured = args[1]
            []
        }

        when:
        service.listActiveForItem(42L, 9999)

        then:
        captured != null
        captured.pageSize == 20
    }

    // ── countAheadInQueue ─────────────────────────────────────────

    def "countAheadInQueue returns 0 for null itemId without hitting the repo"() {
        when:
        def n = service.countAheadInQueue(null, new BigDecimal("10"), 1000L)

        then:
        n == 0L
        0 * buyOrderRepository.countAheadInQueue(*_)
    }

    def "countAheadInQueue returns 0 for null maxPrice without hitting the repo"() {
        when:
        def n = service.countAheadInQueue(42L, null, 1000L)

        then:
        n == 0L
        0 * buyOrderRepository.countAheadInQueue(*_)
    }

    def "countAheadInQueue forwards to the repo and substitutes 0 for null createdAt"() {
        given:
        buyOrderRepository.countAheadInQueue(42L, new BigDecimal("10"), 0L) >> 5L

        expect:
        service.countAheadInQueue(42L, new BigDecimal("10"), null) == 5L
    }

    def "countAheadInQueue passes the real createdAt through when present"() {
        given:
        buyOrderRepository.countAheadInQueue(42L, new BigDecimal("10"), 1700000000000L) >> 2L

        expect:
        service.countAheadInQueue(42L, new BigDecimal("10"), 1700000000000L) == 2L
    }

    // ── update (edit) ─────────────────────────────────────────────

    def "update raises maxPrice on an active order"() {
        given:
        def existing = new BuyOrder(id: 7L, buyerUserId: 10L, status: 'ACTIVE',
                                     maxPrice: new BigDecimal("10"), quantity: 3,
                                     originalQuantity: 3)
        buyOrderRepository.findById(7L) >> Optional.of(existing)
        buyOrderRepository.save(_) >> { BuyOrder o -> o }

        when:
        def result = service.update(10L, 7L, new BigDecimal("25"), null)

        then:
        result.maxPrice == new BigDecimal("25")
        result.quantity == 3
        result.updatedAt != null
    }

    def "update rejects non-positive prices"() {
        given:
        def existing = new BuyOrder(id: 7L, buyerUserId: 10L, status: 'ACTIVE',
                                     maxPrice: new BigDecimal("10"), quantity: 1)
        buyOrderRepository.findById(7L) >> Optional.of(existing)

        when:
        service.update(10L, 7L, BigDecimal.ZERO, null)

        then:
        thrown(BadRequestException)
    }

    def "update rejects prices above the 100k cap"() {
        given:
        def existing = new BuyOrder(id: 7L, buyerUserId: 10L, status: 'ACTIVE',
                                     maxPrice: new BigDecimal("10"), quantity: 1)
        buyOrderRepository.findById(7L) >> Optional.of(existing)

        when:
        service.update(10L, 7L, new BigDecimal("100001"), null)

        then:
        thrown(BadRequestException)
    }

    def "update on an untouched order caps quantity at originalQuantity (== remaining)"() {
        given:
        // No fills yet, so quantity == originalQuantity. The ceiling is
        // the original count — the buyer can't queue more than they
        // first committed to.
        def existing = new BuyOrder(id: 7L, buyerUserId: 10L, status: 'ACTIVE',
                                     maxPrice: new BigDecimal("10"),
                                     quantity: 5, originalQuantity: 5)
        buyOrderRepository.findById(7L) >> Optional.of(existing)
        buyOrderRepository.save(_) >> { BuyOrder o -> o }

        when:
        def result = service.update(10L, 7L, null, 10)

        then:
        // Clamped to 5 (current remaining = original), not the requested 10.
        result.quantity == 5
    }

    def "update on a partially-filled order caps quantity at the CURRENT remaining, not the original"() {
        given:
        // The buyer placed qty 5, the matcher filled 3 (quantity now 2,
        // originalQuantity still 5). Editing must NOT let them grow the
        // remaining count back to 5 — that would yield 3 already-filled
        // + 5 remaining = 8 total items, past the original cap of 5.
        def existing = new BuyOrder(id: 7L, buyerUserId: 10L, status: 'ACTIVE',
                                     maxPrice: new BigDecimal("10"),
                                     quantity: 2, originalQuantity: 5)
        buyOrderRepository.findById(7L) >> Optional.of(existing)
        buyOrderRepository.save(_) >> { BuyOrder o -> o }

        when:
        def result = service.update(10L, 7L, null, 10)

        then:
        // Capped at the current remaining (2), so total stays <= 5.
        result.quantity == 2
    }

    def "update allows shrinking the remaining quantity"() {
        given:
        def existing = new BuyOrder(id: 7L, buyerUserId: 10L, status: 'ACTIVE',
                                     maxPrice: new BigDecimal("10"),
                                     quantity: 5, originalQuantity: 5)
        buyOrderRepository.findById(7L) >> Optional.of(existing)
        buyOrderRepository.save(_) >> { BuyOrder o -> o }

        when:
        def result = service.update(10L, 7L, null, 2)

        then:
        result.quantity == 2
    }

    def "update on a partially-filled order still allows shrinking the remaining quantity"() {
        given:
        // qty 5 placed, 2 filled (remaining 3). Buyer wants to drop the
        // rest to 1 — shrinking below the remaining is always fine.
        def existing = new BuyOrder(id: 7L, buyerUserId: 10L, status: 'ACTIVE',
                                     maxPrice: new BigDecimal("10"),
                                     quantity: 3, originalQuantity: 5)
        buyOrderRepository.findById(7L) >> Optional.of(existing)
        buyOrderRepository.save(_) >> { BuyOrder o -> o }

        when:
        def result = service.update(10L, 7L, null, 1)

        then:
        result.quantity == 1
    }

    def "update refuses edits from a non-owner"() {
        given:
        def existing = new BuyOrder(id: 7L, buyerUserId: 10L, status: 'ACTIVE',
                                     maxPrice: new BigDecimal("10"), quantity: 1)
        buyOrderRepository.findById(7L) >> Optional.of(existing)

        when:
        service.update(99L, 7L, new BigDecimal("20"), null)

        then:
        thrown(ForbiddenException)
    }

    def "update refuses edits on non-ACTIVE orders"() {
        given:
        def existing = new BuyOrder(id: 7L, buyerUserId: 10L, status: 'FILLED',
                                     maxPrice: new BigDecimal("10"), quantity: 0)
        buyOrderRepository.findById(7L) >> Optional.of(existing)

        when:
        service.update(10L, 7L, new BigDecimal("20"), null)

        then:
        thrown(BadRequestException)
    }

    // ── update re-fills against existing listings on a price RAISE ──
    // Mirrors the create()/batch-268 "standing order must fire against
    // listings already on the market" guarantee — closes the same bug
    // on the edit path.

    def "update re-probes existing listings when the cap is raised and fills a now-affordable listing"() {
        given:
        def listingRepo = Mock(com.sboxmarket.repository.ListingRepository)
        service.listingRepository = listingRepo
        // Order at $20, a $25 listing already sits on the market — no
        // match at $20. Buyer edits the cap up to $30.
        def existing = new BuyOrder(id: 7L, buyerUserId: 10L, itemId: 1L,
                                     status: 'ACTIVE', maxPrice: new BigDecimal("20"),
                                     quantity: 1, originalQuantity: 1)
        buyOrderRepository.findById(7L) >> Optional.of(existing)
        buyOrderRepository.save(_) >> { BuyOrder o -> o }
        def alreadyListed = listingFor(id: 100L, price: new BigDecimal("25"))
        // The probe uses the NEW (raised) cap of $30, so the $25 listing matches.
        listingRepo.findMatchingForBuyOrder(1L, null, null, new BigDecimal("30"), _) >> [alreadyListed]
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, steamId64: 'aaa'))
        walletRepository.findByUsername('steam_aaa') >> new Wallet(id: 200L, balance: new BigDecimal("100"))
        purchaseService.buy(200L, 10L, 100L) >> [success: true]

        when:
        def result = service.update(10L, 7L, new BigDecimal("30"), null)

        then:
        1 * purchaseService.buy(200L, 10L, 100L)
        result.quantity == 0
        result.status == 'FILLED'
    }

    def "update does NOT re-probe when the cap is lowered"() {
        given:
        def listingRepo = Mock(com.sboxmarket.repository.ListingRepository)
        service.listingRepository = listingRepo
        def existing = new BuyOrder(id: 7L, buyerUserId: 10L, itemId: 1L,
                                     status: 'ACTIVE', maxPrice: new BigDecimal("50"),
                                     quantity: 1, originalQuantity: 1)
        buyOrderRepository.findById(7L) >> Optional.of(existing)
        buyOrderRepository.save(_) >> { BuyOrder o -> o }

        when:
        service.update(10L, 7L, new BigDecimal("20"), null)

        then:
        // A lower cap can never newly enable a match — no probe at all.
        0 * listingRepo.findMatchingForBuyOrder(_, _, _, _, _)
        0 * purchaseService.buy(_, _, _)
    }

    def "update does NOT re-probe when only the quantity changes"() {
        given:
        def listingRepo = Mock(com.sboxmarket.repository.ListingRepository)
        service.listingRepository = listingRepo
        def existing = new BuyOrder(id: 7L, buyerUserId: 10L, itemId: 1L,
                                     status: 'ACTIVE', maxPrice: new BigDecimal("50"),
                                     quantity: 5, originalQuantity: 5)
        buyOrderRepository.findById(7L) >> Optional.of(existing)
        buyOrderRepository.save(_) >> { BuyOrder o -> o }

        when:
        service.update(10L, 7L, null, 3)

        then:
        // maxPrice unchanged — no new matches possible, no probe.
        0 * listingRepo.findMatchingForBuyOrder(_, _, _, _, _)
        0 * purchaseService.buy(_, _, _)
    }

    def "update price raise that fills the order swallows a probe failure and still returns the edit"() {
        given:
        def listingRepo = Mock(com.sboxmarket.repository.ListingRepository)
        service.listingRepository = listingRepo
        def existing = new BuyOrder(id: 7L, buyerUserId: 10L, itemId: 1L,
                                     status: 'ACTIVE', maxPrice: new BigDecimal("20"),
                                     quantity: 1, originalQuantity: 1)
        buyOrderRepository.findById(7L) >> Optional.of(existing)
        buyOrderRepository.save(_) >> { BuyOrder o -> o }
        // The probe itself blows up — the edit must still be returned,
        // ACTIVE, with the new price persisted (best-effort re-fill).
        listingRepo.findMatchingForBuyOrder(_, _, _, _, _) >> { throw new RuntimeException("probe down") }

        when:
        def result = service.update(10L, 7L, new BigDecimal("30"), null)

        then:
        result.maxPrice == new BigDecimal("30")
        result.status == 'ACTIVE'
        result.quantity == 1
    }

    def "update price raise on a partially-filled order re-probes but never over-fills the remaining quantity"() {
        given:
        // qty 5 placed, 4 already filled (remaining 1, originalQuantity 5).
        // Raising the price must let the LAST unit fill but not resurrect
        // the consumed 4 — total received stays <= the original cap of 5.
        def listingRepo = Mock(com.sboxmarket.repository.ListingRepository)
        service.listingRepository = listingRepo
        def existing = new BuyOrder(id: 7L, buyerUserId: 10L, itemId: 1L,
                                     status: 'ACTIVE', maxPrice: new BigDecimal("20"),
                                     quantity: 1, originalQuantity: 5)
        buyOrderRepository.findById(7L) >> Optional.of(existing)
        buyOrderRepository.save(_) >> { BuyOrder o -> o }
        // Four cheap listings all match the raised cap — only ONE may fill.
        def listings = (100L..103L).collect { listingFor(id: it, price: new BigDecimal("10")) }
        listingRepo.findMatchingForBuyOrder(1L, null, null, new BigDecimal("30"), _) >> listings
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, steamId64: 'aaa'))
        walletRepository.findByUsername('steam_aaa') >> new Wallet(id: 200L, balance: new BigDecimal("1000"))
        purchaseService.buy(200L, 10L, _) >> [success: true]

        when:
        def result = service.update(10L, 7L, new BigDecimal("30"), null)

        then:
        // Exactly one buy — remaining quantity was 1, the loop stops there.
        1 * purchaseService.buy(200L, 10L, _)
        result.quantity == 0
        result.status == 'FILLED'
        // originalQuantity untouched — the historical cap record is intact.
        result.originalQuantity == 5
    }

    // ── tryFillFromExisting (batch 268) ─────────────────────────────

    def "tryFillFromExisting buys the cheapest matching listing and decrements the order"() {
        given:
        // Wire the optional ListingRepository — without it the service's
        // nullable dep makes tryFillFromExisting a no-op, which is what
        // the create() tests above implicitly verify.
        def listingRepo = Mock(com.sboxmarket.repository.ListingRepository)
        service.listingRepository = listingRepo

        def order = new BuyOrder(id: 7L, buyerUserId: 10L, itemId: 1L,
            maxPrice: new BigDecimal("50"), quantity: 1, status: 'ACTIVE')
        def cheap = listingFor(id: 100L, price: new BigDecimal("30"))
        listingRepo.findMatchingForBuyOrder(1L, null, null, new BigDecimal("50"), _) >> [cheap]
        steamUserRepository.findById(10L) >> Optional.of(
            new SteamUser(id: 10L, steamId64: 'steam_aaa'))
        walletRepository.findByUsername('steam_steam_aaa') >> new Wallet(id: 200L,
            balance: new BigDecimal("100"))
        purchaseService.buy(200L, 10L, 100L) >> [success: true]

        when:
        service.tryFillFromExisting(order)

        then:
        order.quantity == 0
        order.status == 'FILLED'
        1 * notificationService.push(10L, 'BUY_ORDER_FILLED', _, _, 100L, _)
        1 * buyOrderRepository.save({ it.status == 'FILLED' && it.quantity == 0 })
    }

    def "tryFillFromExisting fills several listings and decrements quantity each time"() {
        given:
        def listingRepo = Mock(com.sboxmarket.repository.ListingRepository)
        service.listingRepository = listingRepo
        def order = new BuyOrder(id: 7L, buyerUserId: 10L, itemId: 1L,
            maxPrice: new BigDecimal("50"), quantity: 3, status: 'ACTIVE')
        // Three matching listings, ASC by price — all affordable.
        def l1 = listingFor(id: 100L, price: new BigDecimal("10"))
        def l2 = listingFor(id: 101L, price: new BigDecimal("20"))
        def l3 = listingFor(id: 102L, price: new BigDecimal("30"))
        listingRepo.findMatchingForBuyOrder(1L, null, null, _, _) >> [l1, l2, l3]
        steamUserRepository.findById(10L) >> Optional.of(
            new SteamUser(id: 10L, steamId64: 'aaa'))
        // Re-fetched each iteration — keep enough balance for all three.
        walletRepository.findByUsername('steam_aaa') >> new Wallet(id: 200L,
            balance: new BigDecimal("1000"))
        purchaseService.buy(200L, 10L, _) >> [success: true]

        when:
        service.tryFillFromExisting(order)

        then:
        // All three listings bought, quantity exhausted, order FILLED.
        3 * purchaseService.buy(200L, 10L, _)
        order.quantity == 0
        order.status == 'FILLED'
    }

    def "tryFillFromExisting stops at the order quantity even when more listings match"() {
        given:
        def listingRepo = Mock(com.sboxmarket.repository.ListingRepository)
        service.listingRepository = listingRepo
        // Order only wants ONE item, but four listings match.
        def order = new BuyOrder(id: 7L, buyerUserId: 10L, itemId: 1L,
            maxPrice: new BigDecimal("50"), quantity: 1, status: 'ACTIVE')
        def listings = (100L..103L).collect { listingFor(id: it, price: new BigDecimal("10")) }
        listingRepo.findMatchingForBuyOrder(1L, null, null, _, _) >> listings
        steamUserRepository.findById(10L) >> Optional.of(
            new SteamUser(id: 10L, steamId64: 'aaa'))
        walletRepository.findByUsername('steam_aaa') >> new Wallet(id: 200L,
            balance: new BigDecimal("1000"))
        purchaseService.buy(200L, 10L, _) >> [success: true]

        when:
        service.tryFillFromExisting(order)

        then:
        // Exactly one buy — the loop breaks once quantity hits 0, so the
        // buyer never over-purchases past what they asked for.
        1 * purchaseService.buy(200L, 10L, _)
        order.quantity == 0
        order.status == 'FILLED'
    }

    def "tryFillFromExisting swallows a purchase failure and keeps trying later listings"() {
        given:
        def listingRepo = Mock(com.sboxmarket.repository.ListingRepository)
        service.listingRepository = listingRepo
        def order = new BuyOrder(id: 7L, buyerUserId: 10L, itemId: 1L,
            maxPrice: new BigDecimal("50"), quantity: 1, status: 'ACTIVE')
        def lost = listingFor(id: 100L, price: new BigDecimal("10"))
        def won  = listingFor(id: 101L, price: new BigDecimal("20"))
        listingRepo.findMatchingForBuyOrder(1L, null, null, _, _) >> [lost, won]
        steamUserRepository.findById(10L) >> Optional.of(
            new SteamUser(id: 10L, steamId64: 'aaa'))
        walletRepository.findByUsername('steam_aaa') >> new Wallet(id: 200L,
            balance: new BigDecimal("1000"))
        // First listing was sniped by another buyer; second succeeds.
        purchaseService.buy(200L, 10L, 100L) >> { throw new RuntimeException("race lost") }
        purchaseService.buy(200L, 10L, 101L) >> [success: true]

        when:
        service.tryFillFromExisting(order)

        then:
        1 * purchaseService.buy(200L, 10L, 101L)
        order.quantity == 0
        order.status == 'FILLED'
    }

    def "tryFillFromExisting skips self-listings (buyer == seller)"() {
        given:
        def listingRepo = Mock(com.sboxmarket.repository.ListingRepository)
        service.listingRepository = listingRepo
        def order = new BuyOrder(id: 7L, buyerUserId: 10L, itemId: 1L,
            maxPrice: new BigDecimal("50"), quantity: 1, status: 'ACTIVE')
        // Listing owned by the same user — must be skipped.
        def selfListing = listingFor(id: 100L, price: new BigDecimal("30"), seller: 10L)
        listingRepo.findMatchingForBuyOrder(1L, null, null, _, _) >> [selfListing]

        when:
        service.tryFillFromExisting(order)

        then:
        // Self-trade short-circuit BEFORE the wallet lookup.
        0 * walletRepository.findByUsername(_)
        0 * purchaseService.buy(_, _, _)
        order.status == 'ACTIVE'
        order.quantity == 1
    }

    def "tryFillFromExisting halts the loop when wallet balance is below the cheapest match"() {
        given:
        def listingRepo = Mock(com.sboxmarket.repository.ListingRepository)
        service.listingRepository = listingRepo
        def order = new BuyOrder(id: 7L, buyerUserId: 10L, itemId: 1L,
            maxPrice: new BigDecimal("50"), quantity: 3, status: 'ACTIVE')
        // Two candidates ASC; the cheapest is already too expensive.
        def cheap = listingFor(id: 100L, price: new BigDecimal("30"))
        def pricier = listingFor(id: 101L, price: new BigDecimal("40"))
        listingRepo.findMatchingForBuyOrder(1L, null, null, _, _) >> [cheap, pricier]
        steamUserRepository.findById(10L) >> Optional.of(
            new SteamUser(id: 10L, steamId64: 'steam_aaa'))
        walletRepository.findByUsername('steam_steam_aaa') >> new Wallet(id: 200L,
            balance: new BigDecimal("10"))   // Can't even afford the cheapest

        when:
        service.tryFillFromExisting(order)

        then:
        // Break on the cheapest — no purchase against either listing.
        0 * purchaseService.buy(_, _, _)
        order.status == 'ACTIVE'
    }

    def "tryFillFromExisting no-ops when listingRepository is unavailable"() {
        given:
        // Default state — listingRepository null.
        def order = new BuyOrder(id: 7L, buyerUserId: 10L, status: 'ACTIVE',
            maxPrice: new BigDecimal("50"), quantity: 1)

        when:
        service.tryFillFromExisting(order)

        then:
        0 * purchaseService.buy(_, _, _)
        0 * notificationService.push(_, _, _, _, _, _)
    }

    def "tryFillFromExisting on a non-ACTIVE / zero-quantity order is a no-op"() {
        given:
        def listingRepo = Mock(com.sboxmarket.repository.ListingRepository)
        service.listingRepository = listingRepo

        when:
        service.tryFillFromExisting(new BuyOrder(status: 'CANCELLED', quantity: 1))
        service.tryFillFromExisting(new BuyOrder(status: 'ACTIVE',    quantity: 0))

        then:
        0 * listingRepo.findMatchingForBuyOrder(_, _, _, _, _)
    }

    // ── sweepStaleBuyOrders (batch 286) ─────────────────────────────

    def "sweepStaleBuyOrders flips idle orders to EXPIRED and pushes a heads-up"() {
        given:
        def stale = new BuyOrder(id: 7L, buyerUserId: 10L,
            itemName: 'Wizard Hat', maxPrice: new BigDecimal("50"),
            quantity: 1, status: 'ACTIVE')
        buyOrderRepository.findStaleActive(_) >> [stale]
        buyOrderRepository.save(_) >> { BuyOrder b -> b }

        when:
        service.sweepStaleBuyOrders()

        then:
        stale.status == 'EXPIRED'
        1 * notificationService.push(10L, 'BUY_ORDER_EXPIRED', _, _, 7L, '/profile?tab=buyorders')
    }

    def "sweepStaleBuyOrders emails the owner when the auto-expire fires (batch 596)"() {
        given:
        def stale = new BuyOrder(id: 7L, buyerUserId: 10L, itemId: 42L,
            itemName: 'Wizard Hat', maxPrice: new BigDecimal("50"),
            quantity: 1, status: 'ACTIVE')
        buyOrderRepository.findStaleActive(_) >> [stale]
        buyOrderRepository.save(_) >> { BuyOrder b -> b }
        def buyer = new SteamUser(id: 10L, steamId64: '111', displayName: 'Alice',
            email: 'alice@example.com', emailVerified: true,
            emailNotificationsEnabled: true)
        steamUserRepository.findById(10L) >> Optional.of(buyer)
        def emailSvc = Mock(com.sboxmarket.service.EmailService) {
            canSendTo(_, _) >> { user, bucket ->
                user != null &&
                user.email && !user.email.isEmpty() &&
                Boolean.TRUE.equals(user.emailVerified) &&
                Boolean.TRUE.equals(user.emailNotificationsEnabled)
            }
        }
        service.emailService = emailSvc

        when:
        service.sweepStaleBuyOrders()

        then:
        1 * emailSvc.sendBuyOrderExpired('alice@example.com', 'Alice',
            'Wizard Hat', new BigDecimal('50'), '/item/42')
    }

    def "sweepStaleBuyOrders skips email when the owner muted the TRADES bucket (batch 596)"() {
        given:
        def stale = new BuyOrder(id: 7L, buyerUserId: 10L, itemId: 42L,
            itemName: 'Wizard Hat', maxPrice: new BigDecimal("50"),
            quantity: 1, status: 'ACTIVE')
        buyOrderRepository.findStaleActive(_) >> [stale]
        buyOrderRepository.save(_) >> { BuyOrder b -> b }
        def buyer = new SteamUser(id: 10L, steamId64: '111',
            email: 'alice@example.com', emailVerified: true,
            emailNotificationsEnabled: true, mutedEmailKinds: 'TRADES')
        steamUserRepository.findById(10L) >> Optional.of(buyer)
        def emailSvc = Mock(com.sboxmarket.service.EmailService) {
            canSendTo(buyer, 'TRADES') >> false
        }
        service.emailService = emailSvc

        when:
        service.sweepStaleBuyOrders()

        then:
        0 * emailSvc.sendBuyOrderExpired(*_)
    }

    def "sweepStaleBuyOrders is a silent no-op when nothing is stale"() {
        given:
        buyOrderRepository.findStaleActive(_) >> []

        when:
        service.sweepStaleBuyOrders()

        then:
        0 * notificationService.push(_, _, _, _, _, _)
        0 * buyOrderRepository.save(_)
    }

    def "sweepStaleBuyOrders queries with a 30-days-ago cutoff"() {
        given:
        Long captured = null
        buyOrderRepository.findStaleActive(_) >> { args -> captured = args[0] as Long; [] }

        when:
        service.sweepStaleBuyOrders()

        then:
        captured != null
        // 30 days behind now, with a 5s slop for spec scheduling.
        def expected = System.currentTimeMillis() - (30L * 86400_000L)
        Math.abs(captured - expected) < 5000L
    }

    // ── cancelAllForUser (batch 290) ────────────────────────────────

    def "cancelAllForUser flips every ACTIVE row to CANCELLED and returns the count"() {
        given:
        def a = new BuyOrder(id: 1L, buyerUserId: 10L, status: 'ACTIVE', maxPrice: new BigDecimal("10"), quantity: 1)
        def b = new BuyOrder(id: 2L, buyerUserId: 10L, status: 'ACTIVE', maxPrice: new BigDecimal("20"), quantity: 1)
        def c = new BuyOrder(id: 3L, buyerUserId: 10L, status: 'FILLED', maxPrice: new BigDecimal("30"), quantity: 0)
        buyOrderRepository.findByBuyer(10L) >> [a, b, c]
        buyOrderRepository.save(_) >> { BuyOrder o -> o }

        when:
        int n = service.cancelAllForUser(10L)

        then:
        n == 2
        a.status == 'CANCELLED'
        b.status == 'CANCELLED'
        a.updatedAt != null
        b.updatedAt != null
        // Non-ACTIVE row is untouched — no save invocation for it.
        c.status == 'FILLED'
    }

    def "cancelAllForUser returns 0 when the user has no active orders"() {
        given:
        buyOrderRepository.findByBuyer(10L) >> [
            new BuyOrder(id: 1L, buyerUserId: 10L, status: 'FILLED'),
            new BuyOrder(id: 2L, buyerUserId: 10L, status: 'CANCELLED')
        ]

        when:
        int n = service.cancelAllForUser(10L)

        then:
        n == 0
        0 * buyOrderRepository.save(_)
    }

    def "cancelAllForUser returns 0 for a null user id without hitting the repo"() {
        when:
        int n = service.cancelAllForUser(null)

        then:
        n == 0
        0 * buyOrderRepository.findByBuyer(_)
        0 * buyOrderRepository.save(_)
    }

    def "cancelAllForUser swallows per-row save failures and keeps flipping"() {
        given:
        def a = new BuyOrder(id: 1L, buyerUserId: 10L, status: 'ACTIVE', maxPrice: new BigDecimal("10"), quantity: 1)
        def b = new BuyOrder(id: 2L, buyerUserId: 10L, status: 'ACTIVE', maxPrice: new BigDecimal("20"), quantity: 1)
        buyOrderRepository.findByBuyer(10L) >> [a, b]
        // First save throws, second succeeds.
        buyOrderRepository.save(a) >> { throw new RuntimeException('db lost') }
        buyOrderRepository.save(b) >> b

        when:
        int n = service.cancelAllForUser(10L)

        then:
        n == 1
        // Both had their status flipped BEFORE the save attempt —
        // that's fine because the transaction rolls back on a throw.
        b.status == 'CANCELLED'
    }

    def "sweepStaleBuyOrders swallows per-row push exceptions"() {
        given:
        def b1 = new BuyOrder(id: 7L, buyerUserId: 10L, status: 'ACTIVE',
            quantity: 1, maxPrice: new BigDecimal("10"), itemName: 'A')
        def b2 = new BuyOrder(id: 8L, buyerUserId: 11L, status: 'ACTIVE',
            quantity: 1, maxPrice: new BigDecimal("20"), itemName: 'B')
        buyOrderRepository.findStaleActive(_) >> [b1, b2]
        buyOrderRepository.save(_) >> { BuyOrder b -> b }
        notificationService.push(10L, 'BUY_ORDER_EXPIRED', _, _, _, _) >> { throw new RuntimeException('push down') }

        when:
        service.sweepStaleBuyOrders()

        then:
        1 * notificationService.push(11L, 'BUY_ORDER_EXPIRED', _, _, _, _)
        // The exception only short-circuits the failed row's stamp.
        // The successful row's status flip happens BEFORE the push,
        // so it lands; the failed row's flip ALSO happens before
        // (status set then save, then push). So both end EXPIRED.
        b1.status == 'EXPIRED'
        b2.status == 'EXPIRED'
    }

    /**
     * Regression for the rollback-only-leak bug class — same fix the
     * BidService.sweepExpired (batch 800) and the two
     * TradeService.sweepReviewNudge / sweepSlowSellerWarning sweeps
     * adopted. The outer sweep must NOT be wrapped in @Transactional;
     * otherwise a single per-row `buyOrderRepository.save` that throws
     * (concurrent tryFillFromExisting / tryMatch optimistic-lock
     * conflict, DB blip, etc) silently marks the shared outer tx
     * rollback-only — the per-row try/catch swallows the throw but the
     * outer commit later reverts every sibling row's EXPIRED stamp +
     * push. Result on the next 24h tick: re-fired BUY_ORDER_EXPIRED
     * pushes to buyers who were already notified, and idle orders that
     * the sweep thought it had killed could surprise-fire on a future
     * match because the EXPIRED flip never landed.
     *
     * This spec asserts the OUTER sweep method carries no @Transactional
     * — that's the source-level invariant that guarantees per-row saves
     * run in their own auto-commit and one bad row can't poison
     * siblings.
     */
    def "sweepStaleBuyOrders is NOT @Transactional on the outer sweep (rollback-only-leak guard)"() {
        expect:
        def m = BuyOrderService.class.getDeclaredMethod('sweepStaleBuyOrders')
        m != null
        m.getAnnotation(org.springframework.transaction.annotation.Transactional) == null
    }

    def "sweepStaleBuyOrders does not let one row's save failure block the next row from being EXPIRED + notified"() {
        given:
        def b1 = new BuyOrder(id: 7L, buyerUserId: 10L, status: 'ACTIVE',
            quantity: 1, maxPrice: new BigDecimal("10"), itemName: 'A')
        def b2 = new BuyOrder(id: 8L, buyerUserId: 11L, status: 'ACTIVE',
            quantity: 1, maxPrice: new BigDecimal("20"), itemName: 'B')
        buyOrderRepository.findStaleActive(_) >> [b1, b2]
        // First save throws (simulating an OptimisticLockingFailureException
        // from a concurrent tryMatch hitting the same row), second succeeds.
        buyOrderRepository.save(b1) >> { throw new RuntimeException('lock conflict') }
        buyOrderRepository.save(b2) >> b2

        when:
        service.sweepStaleBuyOrders()

        then:
        // b2's push MUST fire even though b1's save threw — the rollback-
        // only-leak bug would have silently dropped b2's work too once the
        // outer tx tried to commit. With no outer @Transactional, b2's
        // notification still goes out.
        1 * notificationService.push(11L, 'BUY_ORDER_EXPIRED', _, _, 8L, '/profile?tab=buyorders')
        // b1 is still marked EXPIRED in-memory (we set it before save),
        // but its save threw so the row didn't persist — that's the failure
        // mode this sweep documents as best-effort. The KEY invariant is
        // that the second row still landed.
        b2.status == 'EXPIRED'
    }
}
