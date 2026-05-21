package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.InsufficientBalanceException
import com.sboxmarket.exception.ListingNotAvailableException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.PurchaseService
import com.sboxmarket.service.PriceHistoryService
import com.sboxmarket.service.TradeService
import com.sboxmarket.service.security.BanGuard
import spock.lang.Specification
import spock.lang.Subject

/**
 * Unit tests for the money path. We mock the repository layer so these are
 * fast and isolated from the database. The asserts check both the typed
 * exception class AND the side effects (balance, listing state, transaction).
 */
class PurchaseServiceSpec extends Specification {

    ListingRepository     listingRepo = Mock()
    WalletRepository      walletRepo  = Mock()
    TransactionRepository txRepo      = Mock()
    SteamUserRepository   steamUserRepo = Mock()
    BanGuard              banGuard    = Mock()
    PriceHistoryService   priceHistoryService = Mock()
    ItemRepository        itemRepo = Mock()

    @Subject
    PurchaseService service = new PurchaseService(
        listingRepository    : listingRepo,
        walletRepository     : walletRepo,
        transactionRepository: txRepo,
        steamUserRepository  : steamUserRepo,
        banGuard             : banGuard,
        priceHistoryService  : priceHistoryService,
        itemRepository       : itemRepo
    )

    def "buy succeeds: debits buyer, marks listing SOLD, records transaction"() {
        given:
        def buyer = new Wallet(id: 1L, username: "steam_111", balance: new BigDecimal("100.00"))
        def item = new Item(id: 10L, name: "Wizard Hat", lowestPrice: new BigDecimal("50.00"))
        def listing = new Listing(id: 5L, item: item, price: new BigDecimal("50.00"),
                                  status: 'ACTIVE', sellerName: "Bot")

        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)

        when:
        def result = service.buy(1L, 999L, 5L)

        then:
        result.newBalance == new BigDecimal("50.00")
        listing.status == 'SOLD'
        listing.buyerUserId == 999L
        listing.soldAt != null
        1 * walletRepo.save({ it.balance == new BigDecimal("50.00") })
        // The listing transition is persisted via saveAndFlush (not plain
        // save) so the @Version optimistic-lock check fires deterministically
        // OUTSIDE the cosmetic try/catch blocks — see PurchaseService.buy.
        1 * listingRepo.saveAndFlush(listing)
        0 * listingRepo.save(_)
        1 * txRepo.save({ it.type == 'PURCHASE' && it.amount == new BigDecimal("50.00") })
        1 * priceHistoryService.record(item, new BigDecimal("50.00"), 1)
        1 * itemRepo.incrementTotalSold(10L)
    }

    def "buy fans out CART_ITEM_SOLD to other cart-holders + scrubs the listing from every cart (batch 503)"() {
        given:
        def buyer = new Wallet(id: 1L, username: 'steam_111', balance: new BigDecimal('200.00'))
        def item = new Item(id: 10L, name: 'Wizard Hat', lowestPrice: new BigDecimal('50.00'))
        def listing = new Listing(id: 5L, item: item, price: new BigDecimal('50.00'),
                                  status: 'ACTIVE', sellerName: 'Bot')
        def cartRepo = Mock(com.sboxmarket.repository.CartItemRepository)
        def notifier = Mock(com.sboxmarket.service.NotificationService)
        service.cartItemRepository = cartRepo
        service.notificationService = notifier
        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)
        // 3 other cart-holders had this listing queued
        cartRepo.findOtherUsersWithListing(5L, 999L) >> [42L, 77L, 88L]

        when:
        service.buy(1L, 999L, 5L)

        then:
        // Buyer's own ITEM_PURCHASED fires (batch 631: via safePush)
        1 * notifier.safePush(999L, 'ITEM_PURCHASED', _, _, _, _)
        // CART_ITEM_SOLD fans out to each of the 3 other holders
        1 * notifier.push(42L, 'CART_ITEM_SOLD', _, _, 5L, '/item/10')
        1 * notifier.push(77L, 'CART_ITEM_SOLD', _, _, 5L, '/item/10')
        1 * notifier.push(88L, 'CART_ITEM_SOLD', _, _, 5L, '/item/10')
        // And the listing is scrubbed from every cart via bulk DELETE
        1 * cartRepo.deleteAllByListing(5L)
    }

    def "buy emails the buyer a purchase receipt when their email is verified (batch 571)"() {
        given:
        def buyer = new Wallet(id: 1L, username: 'steam_111', balance: new BigDecimal('200.00'))
        def item = new Item(id: 10L, name: 'Wizard Hat', lowestPrice: new BigDecimal('50.00'))
        def listing = new Listing(id: 5L, item: item, price: new BigDecimal('50.00'),
                                  status: 'ACTIVE', sellerName: 'Bot', sellerUserId: 500L)
        def emailSvc = Mock(com.sboxmarket.service.EmailService) {
            // Batch 622: delegate the gate check to the user's flags so
            // the "unverified email" test case still closes the gate.
            canSendTo(_, _) >> { user, bucket ->
                user != null &&
                user.email && !user.email.isEmpty() &&
                Boolean.TRUE.equals(user.emailVerified) &&
                Boolean.TRUE.equals(user.emailNotificationsEnabled)
            }
        }
        def notifier = Mock(com.sboxmarket.service.NotificationService)
        service.emailService = emailSvc
        service.notificationService = notifier
        // Trade URL required — buyer's Steam-side trade URL gate (line 116 of PurchaseService).
        def buyerUser = new SteamUser(id: 999L, displayName: 'Alice',
            email: 'alice@example.com', emailVerified: true,
            emailNotificationsEnabled: true,
            tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc')
        def sellerUser = new SteamUser(id: 500L, displayName: 'Bob')
        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)
        steamUserRepo.findById(999L) >> Optional.of(buyerUser)
        steamUserRepo.findById(500L) >> Optional.of(sellerUser)

        when:
        service.buy(1L, 999L, 5L)

        then:
        1 * emailSvc.sendPurchaseReceipt('alice@example.com', 'Alice', 'Wizard Hat', 'Bob', new BigDecimal('50.00'), 5L)
    }

    def "buy skips the purchase-receipt email when the buyer's email is unverified (batch 571)"() {
        given:
        def buyer = new Wallet(id: 1L, username: 'steam_111', balance: new BigDecimal('200.00'))
        def item = new Item(id: 10L, name: 'Wizard Hat', lowestPrice: new BigDecimal('50.00'))
        def listing = new Listing(id: 5L, item: item, price: new BigDecimal('50.00'),
                                  status: 'ACTIVE', sellerName: 'Bot', sellerUserId: 500L)
        def emailSvc = Mock(com.sboxmarket.service.EmailService) {
            // Batch 622: delegate the gate check to the user's flags so
            // the "unverified email" test case still closes the gate.
            canSendTo(_, _) >> { user, bucket ->
                user != null &&
                user.email && !user.email.isEmpty() &&
                Boolean.TRUE.equals(user.emailVerified) &&
                Boolean.TRUE.equals(user.emailNotificationsEnabled)
            }
        }
        def notifier = Mock(com.sboxmarket.service.NotificationService)
        service.emailService = emailSvc
        service.notificationService = notifier
        // Unverified buyer — email must be skipped. Trade URL still set
        // so the earlier gate doesn't throw before we reach the email path.
        def buyerUser = new SteamUser(id: 999L, displayName: 'Alice',
            email: 'alice@example.com', emailVerified: false,
            emailNotificationsEnabled: true,
            tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc')
        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)
        steamUserRepo.findById(999L) >> Optional.of(buyerUser)

        when:
        service.buy(1L, 999L, 5L)

        then:
        0 * emailSvc.sendPurchaseReceipt(*_)
    }

    def "buy doesn't fan CART_ITEM_SOLD when nobody else has it in cart (batch 503)"() {
        given:
        def buyer = new Wallet(id: 1L, username: 'steam_111', balance: new BigDecimal('200.00'))
        def item = new Item(id: 10L, name: 'Wizard Hat')
        def listing = new Listing(id: 5L, item: item, price: new BigDecimal('50.00'),
                                  status: 'ACTIVE', sellerName: 'Bot')
        def cartRepo = Mock(com.sboxmarket.repository.CartItemRepository)
        def notifier = Mock(com.sboxmarket.service.NotificationService)
        service.cartItemRepository = cartRepo
        service.notificationService = notifier
        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)
        cartRepo.findOtherUsersWithListing(5L, 999L) >> []

        when:
        service.buy(1L, 999L, 5L)

        then:
        1 * notifier.safePush(999L, 'ITEM_PURCHASED', _, _, _, _)
        0 * notifier.push(_, 'CART_ITEM_SOLD', _, _, _, _)
        // Scrub still skips when there's nobody to notify
        0 * cartRepo.deleteAllByListing(_)
    }

    def "buy refuses a frozen wallet (batch 509)"() {
        given:
        def buyer = new Wallet(id: 1L, username: 'steam_111', balance: new BigDecimal('100.00'),
                               frozen: true, frozenReason: 'Staff freeze during investigation')
        walletRepo.findById(1L) >> Optional.of(buyer)

        when:
        service.buy(1L, 999L, 5L)

        then:
        def e = thrown(BadRequestException)
        e.code == 'WALLET_FROZEN'

        and: "no listing probe, no money moved"
        0 * listingRepo.findById(_)
        0 * walletRepo.save(_)
        0 * txRepo.save(_)
    }

    def "buy refuses a wallet with an active deposit dispute (batch 511)"() {
        given:
        def buyer = new Wallet(id: 1L, username: 'steam_111', balance: new BigDecimal('100.00'))
        walletRepo.findById(1L) >> Optional.of(buyer)
        txRepo.countActiveDisputedDeposits(1L) >> 1L

        when:
        service.buy(1L, 999L, 5L)

        then:
        def e = thrown(BadRequestException)
        e.code == 'PURCHASE_DISPUTE_HOLD'

        and: "no listing probe, no money moved"
        0 * listingRepo.findById(_)
        0 * walletRepo.save(_)
        0 * txRepo.save(_)
    }

    def "buy throws InsufficientBalanceException when wallet has too little"() {
        given:
        def buyer = new Wallet(id: 1L, balance: new BigDecimal("10.00"))
        def item = new Item(id: 10L, name: "Skull Helmet")
        def listing = new Listing(id: 5L, item: item, price: new BigDecimal("50.00"), status: 'ACTIVE')

        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)

        when:
        service.buy(1L, 999L, 5L)

        then:
        def ex = thrown(InsufficientBalanceException)
        ex.required == new BigDecimal("50.00")
        ex.available == new BigDecimal("10.00")
        ex.code == "INSUFFICIENT_BALANCE"

        and: "no money moved, no listing change"
        0 * walletRepo.save(_)
        0 * listingRepo.save(_)
        0 * txRepo.save(_)
    }

    def "buy throws ListingNotAvailable when status != ACTIVE"() {
        given:
        def buyer = new Wallet(id: 1L, balance: new BigDecimal("100.00"))
        def listing = new Listing(id: 5L, item: new Item(id: 10L), price: new BigDecimal("10.00"), status: 'SOLD')

        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)

        when:
        service.buy(1L, 999L, 5L)

        then:
        def ex = thrown(ListingNotAvailableException)
        ex.code == "LISTING_NOT_AVAILABLE"
    }

    def "buy throws NotFoundException when wallet missing"() {
        given:
        walletRepo.findById(1L) >> Optional.empty()

        when:
        service.buy(1L, 999L, 5L)

        then:
        thrown(NotFoundException)
    }

    def "buy throws BadRequest when buyer is also seller"() {
        given:
        def buyer = new Wallet(id: 1L, balance: new BigDecimal("100.00"))
        def listing = new Listing(id: 5L, item: new Item(id: 10L),
                                  price: new BigDecimal("10.00"),
                                  status: 'ACTIVE', sellerUserId: 999L)

        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)

        when:
        service.buy(1L, 999L, 5L)

        then:
        def ex = thrown(BadRequestException)
        ex.code == "OWN_LISTING"
    }

    def "buy creates a P2P escrow Trade instead of crediting the seller immediately"() {
        given: "a buyer with funds, a seller with a wallet, an active listing at \$100"
        def buyer = new Wallet(id: 1L, username: 'steam_111', balance: new BigDecimal("200.00"), currency: 'USD')
        def seller = new SteamUser(id: 2L, steamId64: '222')
        def sellerWallet = new Wallet(id: 500L, username: 'steam_222', balance: new BigDecimal("0.00"), currency: 'USD')
        def listing = new Listing(
            id: 5L,
            item: new Item(id: 10L, name: 'Wizard Hat'),
            price: new BigDecimal("100.00"),
            status: 'ACTIVE',
            sellerName: 'Bob',
            sellerUserId: 2L
        )
        def tradeService = Mock(TradeService)
        service.tradeService = tradeService
        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)
        steamUserRepo.findById(2L) >> Optional.of(seller)
        walletRepo.findByUsername('steam_222') >> sellerWallet

        when:
        def result = service.buy(1L, 999L, 5L)

        then:
        result.newBalance == new BigDecimal("100.00")
        // Buyer was debited
        1 * walletRepo.save({ Wallet w -> w.balance == new BigDecimal("100.00") })
        // Seller was NOT credited immediately — funds held in escrow
        sellerWallet.balance == new BigDecimal("0.00")
        // A Trade record was created for the P2P escrow
        1 * tradeService.open(5L, 10L, 'Wizard Hat', 999L, 1L, 2L, 500L, new BigDecimal("100.00"))
        // Purchase tx recorded on buyer side
        1 * txRepo.save({ it.type == 'PURCHASE' })
        // NO immediate SALE tx — that happens when the buyer confirms receipt
        0 * txRepo.save({ it.type == 'SALE' })
    }

    def "buy still completes when seller has no wallet (falls through gracefully)"() {
        given:
        def buyer = new Wallet(id: 1L, balance: new BigDecimal("200.00"), currency: 'USD')
        def seller = new SteamUser(id: 2L, steamId64: '222')
        def listing = new Listing(
            id: 5L,
            item: new Item(id: 10L, name: 'x'),
            price: new BigDecimal("50.00"),
            status: 'ACTIVE',
            sellerUserId: 2L
        )
        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)
        steamUserRepo.findById(2L) >> Optional.of(seller)
        walletRepo.findByUsername('steam_222') >> null  // seller wallet doesn't exist

        when:
        def result = service.buy(1L, 999L, 5L)

        then:
        // Buy completes, buyer debited, no crash
        result.newBalance == new BigDecimal("150.00")
        listing.status == 'SOLD'
        // Only buyer-side transaction saved
        1 * txRepo.save({ it.type == 'PURCHASE' })
        0 * txRepo.save({ it.type == 'SALE' })
    }

    // ── Trade creation failure rolls back the whole buy (bug #75) ──

    def "buy rolls back when TradeService.open fails on a P2P listing (bug #75)"() {
        given: "a P2P listing where trade creation will fail"
        def buyer = new Wallet(id: 1L, username: 'steam_111', balance: new BigDecimal("200.00"), currency: 'USD')
        def seller = new SteamUser(id: 2L, steamId64: '222')
        def sellerWallet = new Wallet(id: 500L, username: 'steam_222', balance: new BigDecimal("0.00"))
        def listing = new Listing(
            id: 5L,
            item: new Item(id: 10L, name: 'Wizard Hat'),
            price: new BigDecimal("100.00"),
            status: 'ACTIVE',
            sellerUserId: 2L
        )
        def tradeService = Mock(TradeService)
        service.tradeService = tradeService
        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)
        steamUserRepo.findById(2L) >> Optional.of(seller)
        walletRepo.findByUsername('steam_222') >> sellerWallet
        // TradeService.open() throws — the buy must NOT complete
        tradeService.open(_, _, _, _, _, _, _, _) >> { throw new RuntimeException("DB error") }

        when:
        service.buy(1L, 999L, 5L)

        then:
        // Exception propagates — @Transactional rolls everything back
        thrown(RuntimeException)
    }

    // ── AUCTION guard (bug #29) ───────────────────────────────────

    def "buy refuses hidden listings (batch 308 bug fix)"() {
        // A hidden listing is off-market to the public grid but the id
        // is stable. Reject so a cached client / scraped-id payload
        // can't buy a listing the seller has pulled.
        given:
        def buyer = new Wallet(id: 1L, balance: new BigDecimal("500.00"))
        def item = new Item(id: 10L, name: "Rare Helmet")
        def listing = new Listing(
            id: 5L, item: item, price: new BigDecimal("50.00"),
            status: 'ACTIVE', hidden: true, sellerName: 'Bot',
            listingType: 'BUY_NOW'
        )

        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)

        when:
        service.buy(1L, 999L, 5L)

        then:
        thrown(ListingNotAvailableException)
        listing.status == 'ACTIVE'   // not mutated
        buyer.balance == new BigDecimal("500.00")
        0 * walletRepo.save(_)
        0 * listingRepo.save(_)
    }

    def "buy refuses to bypass an AUCTION via the BUY_NOW path"() {
        given:
        def buyer = new Wallet(id: 1L, balance: new BigDecimal("500.00"))
        def item = new Item(id: 10L, name: "Rare Helmet")
        def listing = new Listing(
            id: 5L, item: item, price: new BigDecimal("50.00"),
            status: 'ACTIVE', sellerName: 'Bot',
            listingType: 'AUCTION',
            currentBid: new BigDecimal("40.00"),
            currentBidderId: 77L
        )

        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)

        when:
        service.buy(1L, 999L, 5L)

        then:
        def ex = thrown(BadRequestException)
        ex.code == 'NOT_BUY_NOW'
        // Critical side effects must NOT have happened — no debit, no
        // listing mutation, no transaction log, no notification.
        listing.status == 'ACTIVE'
        buyer.balance == new BigDecimal("500.00")
        0 * walletRepo.save(_)
        0 * listingRepo.save(_)
        0 * listingRepo.saveAndFlush(_)
        0 * txRepo.save(_)
    }

    // ── Listing-not-found guard ───────────────────────────────────

    def "buy throws NotFoundException when the listing id doesn't exist"() {
        given:
        def buyer = new Wallet(id: 1L, balance: new BigDecimal("100.00"))
        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.empty()

        when:
        service.buy(1L, 999L, 5L)

        then:
        thrown(NotFoundException)

        and: "nothing moved"
        0 * walletRepo.save(_)
        0 * listingRepo.saveAndFlush(_)
        0 * txRepo.save(_)
    }

    // ── Banned-buyer gate ─────────────────────────────────────────

    def "buy refuses a banned buyer before any wallet/listing probe"() {
        given: "the ban guard rejects this user"
        banGuard.assertNotBanned(999L) >> { throw new BadRequestException("USER_BANNED", "Account suspended") }

        when:
        service.buy(1L, 999L, 5L)

        then:
        def e = thrown(BadRequestException)
        e.code == 'USER_BANNED'

        and: "ban check short-circuits — no wallet lookup, no money moved"
        0 * walletRepo.findById(_)
        0 * listingRepo.findById(_)
        0 * walletRepo.save(_)
        0 * txRepo.save(_)
    }

    // ── ALREADY_OWNED guard ───────────────────────────────────────

    def "buy refuses a listing the caller already bought (ALREADY_OWNED)"() {
        given: "an ACTIVE listing whose buyerUserId is already the caller"
        def buyer = new Wallet(id: 1L, balance: new BigDecimal("500.00"))
        def listing = new Listing(
            id: 5L, item: new Item(id: 10L, name: 'Hat'),
            price: new BigDecimal("50.00"), status: 'ACTIVE',
            sellerName: 'Bot', buyerUserId: 999L)
        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)

        when:
        service.buy(1L, 999L, 5L)

        then:
        def e = thrown(BadRequestException)
        e.code == 'ALREADY_OWNED'

        and: "no debit, no persist"
        buyer.balance == new BigDecimal("500.00")
        0 * walletRepo.save(_)
        0 * listingRepo.saveAndFlush(_)
        0 * txRepo.save(_)
    }

    // ── Trade-URL gate for P2P listings ───────────────────────────

    def "buy refuses a P2P purchase when the buyer has no Steam trade URL set"() {
        given: "a P2P listing and a buyer whose tradeUrl is blank"
        def buyer = new Wallet(id: 1L, username: 'steam_111', balance: new BigDecimal("500.00"))
        def listing = new Listing(
            id: 5L, item: new Item(id: 10L, name: 'Hat'),
            price: new BigDecimal("50.00"), status: 'ACTIVE',
            sellerName: 'Bob', sellerUserId: 500L)
        def buyerUser = new SteamUser(id: 999L, displayName: 'Alice', tradeUrl: '   ')
        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)
        steamUserRepo.findById(999L) >> Optional.of(buyerUser)

        when:
        service.buy(1L, 999L, 5L)

        then:
        def e = thrown(BadRequestException)
        e.code == 'TRADE_URL_MISSING'

        and: "fails BEFORE the wallet is touched — no buy-then-stuck"
        buyer.balance == new BigDecimal("500.00")
        0 * walletRepo.save(_)
        0 * listingRepo.saveAndFlush(_)
        0 * txRepo.save(_)
    }

    def "buy does NOT require a trade URL for system listings (sellerUserId == null)"() {
        given: "a system listing (no seller) and a buyer with no trade URL"
        def buyer = new Wallet(id: 1L, username: 'steam_111', balance: new BigDecimal("500.00"))
        def listing = new Listing(
            id: 5L, item: new Item(id: 10L, name: 'Hat'),
            price: new BigDecimal("50.00"), status: 'ACTIVE',
            sellerName: 'System')  // sellerUserId stays null
        def buyerUser = new SteamUser(id: 999L, displayName: 'Alice')  // no tradeUrl
        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)
        steamUserRepo.findById(999L) >> Optional.of(buyerUser)

        when:
        def result = service.buy(1L, 999L, 5L)

        then: "buy completes — system listings resolve in-platform, no Steam trade"
        result.newBalance == new BigDecimal("450.00")
        listing.status == 'SOLD'
        1 * txRepo.save({ it.type == 'PURCHASE' })
    }

    // ── Money math: exact-balance boundary + sub-cent precision ────

    def "buy succeeds when the wallet balance exactly equals the price (boundary)"() {
        given: "balance == price to the cent"
        def buyer = new Wallet(id: 1L, username: 'steam_111', balance: new BigDecimal("50.00"))
        def listing = new Listing(
            id: 5L, item: new Item(id: 10L, name: 'Hat'),
            price: new BigDecimal("50.00"), status: 'ACTIVE', sellerName: 'Bot')
        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)

        when:
        def result = service.buy(1L, 999L, 5L)

        then: "the `balance < price` check is strict-less-than, so exact balance is allowed"
        result.newBalance == new BigDecimal("0.00")
        buyer.balance == new BigDecimal("0.00")
        listing.status == 'SOLD'
        1 * txRepo.save({ it.amount == new BigDecimal("50.00") })
    }

    def "buy debits a fractional price exactly — no sub-cent drift"() {
        given: "a listing priced at \$9.99 against a \$10.00 wallet"
        def buyer = new Wallet(id: 1L, username: 'steam_111', balance: new BigDecimal("10.00"))
        def listing = new Listing(
            id: 5L, item: new Item(id: 10L, name: 'Hat'),
            price: new BigDecimal("9.99"), status: 'ACTIVE', sellerName: 'Bot')
        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)

        when:
        def result = service.buy(1L, 999L, 5L)

        then: "balance is exactly 0.01 — BigDecimal subtract, no rounding"
        result.newBalance == new BigDecimal("0.01")
        buyer.balance == new BigDecimal("0.01")
        // The transaction records the price to the exact cent, not a rounded value.
        1 * txRepo.save({ Transaction t -> t.amount == new BigDecimal("9.99") })
    }

    def "buy throws InsufficientBalance when short by a single cent"() {
        given: "wallet is \$49.99, listing is \$50.00"
        def buyer = new Wallet(id: 1L, username: 'steam_111', balance: new BigDecimal("49.99"))
        def listing = new Listing(
            id: 5L, item: new Item(id: 10L, name: 'Hat'),
            price: new BigDecimal("50.00"), status: 'ACTIVE', sellerName: 'Bot')
        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)

        when:
        service.buy(1L, 999L, 5L)

        then:
        def e = thrown(InsufficientBalanceException)
        e.required == new BigDecimal("50.00")
        e.available == new BigDecimal("49.99")

        and: "no partial debit"
        buyer.balance == new BigDecimal("49.99")
        0 * walletRepo.save(_)
        0 * listingRepo.saveAndFlush(_)
    }

    // ── CANCELLED listing is not buyable ──────────────────────────

    def "buy throws ListingNotAvailable for a CANCELLED listing"() {
        given:
        def buyer = new Wallet(id: 1L, balance: new BigDecimal("100.00"))
        def listing = new Listing(
            id: 5L, item: new Item(id: 10L, name: 'Hat'),
            price: new BigDecimal("10.00"), status: 'CANCELLED', sellerName: 'Bot')
        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)

        when:
        service.buy(1L, 999L, 5L)

        then:
        thrown(ListingNotAvailableException)
        0 * walletRepo.save(_)
        0 * listingRepo.saveAndFlush(_)
    }

    // ── Cosmetic side-effects never break the money path ──────────

    def "buy still completes when the price-history write throws (cosmetic, swallowed)"() {
        given: "priceHistoryService.record blows up"
        def buyer = new Wallet(id: 1L, username: 'steam_111', balance: new BigDecimal("100.00"))
        def item = new Item(id: 10L, name: 'Hat')
        def listing = new Listing(
            id: 5L, item: item, price: new BigDecimal("50.00"),
            status: 'ACTIVE', sellerName: 'Bot')
        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)
        priceHistoryService.record(_, _, _) >> { throw new RuntimeException("history table down") }

        when: "the sale still goes through — the chart write is best-effort"
        def result = service.buy(1L, 999L, 5L)

        then:
        result.newBalance == new BigDecimal("50.00")
        listing.status == 'SOLD'
        1 * listingRepo.saveAndFlush(listing)
        1 * txRepo.save({ it.type == 'PURCHASE' })
    }

    def "buy still completes when the totalSold bump throws (cosmetic, swallowed)"() {
        given: "itemRepository.incrementTotalSold blows up"
        def buyer = new Wallet(id: 1L, username: 'steam_111', balance: new BigDecimal("100.00"))
        def item = new Item(id: 10L, name: 'Hat')
        def listing = new Listing(
            id: 5L, item: item, price: new BigDecimal("50.00"),
            status: 'ACTIVE', sellerName: 'Bot')
        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)
        itemRepo.incrementTotalSold(_) >> { throw new RuntimeException("counter update failed") }

        when:
        def result = service.buy(1L, 999L, 5L)

        then: "money path unaffected by the Most-Traded counter hiccup"
        result.newBalance == new BigDecimal("50.00")
        listing.status == 'SOLD'
        1 * txRepo.save({ it.type == 'PURCHASE' })
    }

    // ── @Version optimistic-lock conflict propagates cleanly ──────

    def "buy lets an optimistic-lock conflict on saveAndFlush propagate (not swallowed)"() {
        // Regression: the listing transition is persisted via saveAndFlush
        // so the @Version StaleObjectState check fires at a deterministic
        // point OUTSIDE the cosmetic try/catch. The losing concurrent buyer
        // must surface ObjectOptimisticLockingFailureException — which the
        // HTTP layer maps to 409 — instead of having the conflict swallowed
        // and cascading into an opaque UnexpectedRollbackException.
        given:
        def buyer = new Wallet(id: 1L, username: 'steam_111', balance: new BigDecimal("100.00"))
        def listing = new Listing(
            id: 5L, item: new Item(id: 10L, name: 'Hat'),
            price: new BigDecimal("50.00"), status: 'ACTIVE', sellerName: 'Bot')
        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)
        // A concurrent buyer already won the race — this commit is stale.
        listingRepo.saveAndFlush(_) >> {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(Listing, 5L)
        }

        when:
        service.buy(1L, 999L, 5L)

        then: "the lock conflict propagates uncaught — @Transactional rolls the debit back"
        thrown(org.springframework.orm.ObjectOptimisticLockingFailureException)
    }
}
