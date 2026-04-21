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

    def "update refuses to grow quantity beyond the original"() {
        given:
        def existing = new BuyOrder(id: 7L, buyerUserId: 10L, status: 'ACTIVE',
                                     maxPrice: new BigDecimal("10"),
                                     quantity: 2, originalQuantity: 5)
        buyOrderRepository.findById(7L) >> Optional.of(existing)
        buyOrderRepository.save(_) >> { BuyOrder o -> o }

        when:
        def result = service.update(10L, 7L, null, 10)

        then:
        // Clamped to the original cap, not the requested 10.
        result.quantity == 5
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
}
