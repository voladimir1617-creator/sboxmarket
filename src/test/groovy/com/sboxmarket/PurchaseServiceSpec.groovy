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
        def sellerUser = new SteamUser(id: 500L, displayName: 'Bob', steamId64: '888')
        // Seller wallet required for P2P (SELLER_WALLET_MISSING gate).
        def sellerWallet = new Wallet(id: 700L, username: 'steam_888', balance: BigDecimal.ZERO)
        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)
        steamUserRepo.findById(999L) >> Optional.of(buyerUser)
        steamUserRepo.findById(500L) >> Optional.of(sellerUser)
        walletRepo.findByUsername('steam_888') >> sellerWallet

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
        def sellerUser = new SteamUser(id: 500L, displayName: 'Bob', steamId64: '888')
        def sellerWallet = new Wallet(id: 700L, username: 'steam_888', balance: BigDecimal.ZERO)
        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)
        steamUserRepo.findById(999L) >> Optional.of(buyerUser)
        steamUserRepo.findById(500L) >> Optional.of(sellerUser)
        walletRepo.findByUsername('steam_888') >> sellerWallet

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

    def "buy fails fast with SELLER_WALLET_MISSING when seller has no wallet (no debit, no SOLD flip)"() {
        // Regression: previously the buy "fell through gracefully" — buyer was
        // debited, listing flipped to SOLD, Trade opened with sellerWalletId=null,
        // and at VERIFIED-release the seller credit was silently skipped with a
        // "Manual payout required" log line. Net effect: the platform pocketed
        // the buyer's money until ops noticed the log. Now we reject the buy
        // BEFORE any money or state moves, so the buyer sees a clean error.
        given:
        def buyer = new Wallet(id: 1L, balance: new BigDecimal("200.00"), currency: 'USD')
        def seller = new SteamUser(id: 2L, steamId64: '222',
            tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc')
        def listing = new Listing(
            id: 5L,
            item: new Item(id: 10L, name: 'x'),
            price: new BigDecimal("50.00"),
            status: 'ACTIVE',
            sellerUserId: 2L
        )
        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)
        // Both lookups resolve a SteamUser — for the buyer (trade-url gate)
        // and the seller (wallet resolution). The seller wallet itself is missing.
        steamUserRepo.findById(999L) >> Optional.of(new SteamUser(id: 999L,
            tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc'))
        steamUserRepo.findById(2L) >> Optional.of(seller)
        walletRepo.findByUsername('steam_222') >> null  // seller wallet doesn't exist

        when:
        service.buy(1L, 999L, 5L)

        then:
        def ex = thrown(BadRequestException)
        ex.code == 'SELLER_WALLET_MISSING'

        and: "buyer balance untouched, listing untouched, no transactions written"
        buyer.balance == new BigDecimal("200.00")
        listing.status == 'ACTIVE'
        0 * walletRepo.save(_)
        0 * listingRepo.saveAndFlush(_)
        0 * txRepo.save(_)
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

    def "buy lets a Wallet-typed optimistic-lock conflict propagate too (debit @Version)"() {
        // The buyer's wallet debit (line 126) uses a plain save() whose
        // versioned UPDATE is only pushed by the saveAndFlush(listing)
        // flush. If a concurrent debit on the SAME wallet (two tabs)
        // already bumped Wallet.version, the flush raises an
        // ObjectOptimisticLockingFailureException carrying the Wallet
        // class — it must propagate exactly like the Listing-typed one
        // so GlobalExceptionHandler still maps it to 409.
        given:
        def buyer = new Wallet(id: 1L, username: 'steam_111', balance: new BigDecimal("100.00"))
        def listing = new Listing(
            id: 5L, item: new Item(id: 10L, name: 'Hat'),
            price: new BigDecimal("50.00"), status: 'ACTIVE', sellerName: 'Bot')
        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)
        // The flush forced by saveAndFlush detects the stale wallet row.
        listingRepo.saveAndFlush(_) >> {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(Wallet, 1L)
        }

        when:
        service.buy(1L, 999L, 5L)

        then: "propagates uncaught — same 409 path as the Listing conflict"
        def e = thrown(org.springframework.orm.ObjectOptimisticLockingFailureException)
        e.persistentClassName == Wallet.name
    }

    // ── Dispute-hold happy path + message pluralisation ───────────

    def "buy proceeds when the buyer has zero active deposit disputes (batch 511 happy path)"() {
        given: "countActiveDisputedDeposits returns 0 — the gate is open"
        def buyer = new Wallet(id: 1L, username: 'steam_111', balance: new BigDecimal("100.00"))
        def listing = new Listing(
            id: 5L, item: new Item(id: 10L, name: 'Hat'),
            price: new BigDecimal("50.00"), status: 'ACTIVE', sellerName: 'Bot')
        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)
        txRepo.countActiveDisputedDeposits(1L) >> 0L

        when:
        def result = service.buy(1L, 999L, 5L)

        then: "no PURCHASE_DISPUTE_HOLD thrown — the sale completes"
        result.newBalance == new BigDecimal("50.00")
        listing.status == 'SOLD'
        1 * txRepo.save({ it.type == 'PURCHASE' })
    }

    def "buy's dispute-hold message pluralises correctly for multiple disputes (batch 511)"() {
        given: "two active disputes on file"
        def buyer = new Wallet(id: 1L, username: 'steam_111', balance: new BigDecimal("100.00"))
        walletRepo.findById(1L) >> Optional.of(buyer)
        txRepo.countActiveDisputedDeposits(1L) >> 2L

        when:
        service.buy(1L, 999L, 5L)

        then:
        def e = thrown(BadRequestException)
        e.code == 'PURCHASE_DISPUTE_HOLD'
        // "2 unresolved deposit disputes" — plural form, count interpolated.
        e.message.contains('2 unresolved deposit disputes')
    }

    // ── Buyer-side Transaction is fully + correctly populated ──────

    def "buy stamps the buyer Transaction with COMPLETED status, wallet currency, and the listing id"() {
        given:
        def buyer = new Wallet(id: 1L, username: 'steam_111',
                               balance: new BigDecimal("100.00"), currency: 'USD')
        def item = new Item(id: 10L, name: 'Wizard Hat')
        def listing = new Listing(
            id: 5L, item: item, price: new BigDecimal("50.00"),
            status: 'ACTIVE', sellerName: 'Bot')
        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)

        when:
        service.buy(1L, 999L, 5L)

        then: "every money-relevant field on the buyer ledger row is exact"
        1 * txRepo.save({ Transaction t ->
            t.walletId == 1L &&
            t.type == 'PURCHASE' &&
            t.status == 'COMPLETED' &&
            t.amount == new BigDecimal("50.00") &&
            t.currency == 'USD' &&
            t.stripeReference == 'wallet' &&
            t.listingId == 5L
        })
    }

    // ── result map contract ───────────────────────────────────────

    def "buy returns the SOLD listing in the result map"() {
        given:
        def buyer = new Wallet(id: 1L, username: 'steam_111', balance: new BigDecimal("100.00"))
        def listing = new Listing(
            id: 5L, item: new Item(id: 10L, name: 'Hat'),
            price: new BigDecimal("40.00"), status: 'ACTIVE', sellerName: 'Bot')
        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)

        when:
        def result = service.buy(1L, 999L, 5L)

        then: "the returned listing is the same row, now SOLD with the buyer stamped"
        result.listing.is(listing)
        result.listing.status == 'SOLD'
        result.listing.buyerUserId == 999L
        result.newBalance == new BigDecimal("60.00")
    }

    // ── Trade-URL gate edge: SteamUser row missing ────────────────

    def "buy refuses a P2P listing when the seller's SteamUser row is missing (no NPE, fail-fast)"() {
        // The trade-URL gate skips on a missing buyer SteamUser (no NPE on
        // buyer.tradeUrl). But a missing SELLER SteamUser means we can't
        // resolve their wallet — so SELLER_WALLET_MISSING fires before any
        // money moves. (Previously the buy completed and opened a Trade
        // with sellerWalletId=null, stranding seller credit.)
        given:
        def buyer = new Wallet(id: 1L, username: 'steam_111', balance: new BigDecimal("100.00"))
        def listing = new Listing(
            id: 5L, item: new Item(id: 10L, name: 'Hat'),
            price: new BigDecimal("50.00"), status: 'ACTIVE',
            sellerName: 'Bob', sellerUserId: 500L)
        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)
        steamUserRepo.findById(999L) >> Optional.empty()   // buyer row absent
        steamUserRepo.findById(500L) >> Optional.empty()   // seller row absent

        when:
        service.buy(1L, 999L, 5L)

        then:
        def ex = thrown(BadRequestException)
        ex.code == 'SELLER_WALLET_MISSING'

        and: "no NPE, no debit, no SOLD flip"
        buyer.balance == new BigDecimal("100.00")
        listing.status == 'ACTIVE'
        0 * walletRepo.save(_)
        0 * listingRepo.saveAndFlush(_)
        0 * txRepo.save(_)
    }

    // ── Audit trail ───────────────────────────────────────────────

    def "buy writes a LISTING_PURCHASED audit entry after the sale"() {
        given:
        def auditService = Mock(com.sboxmarket.service.AuditService)
        service.auditService = auditService
        def buyer = new Wallet(id: 1L, username: 'steam_111', balance: new BigDecimal("100.00"))
        def listing = new Listing(
            id: 5L, item: new Item(id: 10L, name: 'Wizard Hat'),
            price: new BigDecimal("50.00"), status: 'ACTIVE',
            sellerName: 'Bob', sellerUserId: 500L)
        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)

        when:
        service.buy(1L, 999L, 5L)

        then: "actor = buyer, subject = seller, resource = listing id"
        1 * auditService.log('LISTING_PURCHASED', 999L, 500L, 5L, _)
    }

    def "buy still completes when the audit-log write throws (swallowed)"() {
        given: "auditService.log blows up"
        def auditService = Mock(com.sboxmarket.service.AuditService)
        service.auditService = auditService
        def buyer = new Wallet(id: 1L, username: 'steam_111', balance: new BigDecimal("100.00"))
        def listing = new Listing(
            id: 5L, item: new Item(id: 10L, name: 'Hat'),
            price: new BigDecimal("50.00"), status: 'ACTIVE', sellerName: 'Bot')
        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)
        auditService.log(*_) >> { throw new RuntimeException("audit table down") }

        when:
        def result = service.buy(1L, 999L, 5L)

        then: "the money path is unaffected by an audit hiccup"
        result.newBalance == new BigDecimal("50.00")
        listing.status == 'SOLD'
        1 * txRepo.save({ it.type == 'PURCHASE' })
    }

    // ── Frozen-flag null-safety ───────────────────────────────────

    def "buy is NOT blocked when the wallet's frozen flag is null (Boolean.TRUE.equals guard)"() {
        // frozen is nullable in SQL; a legacy/backfilled row can carry
        // null. The guard uses Boolean.TRUE.equals so null is treated as
        // not-frozen and the purchase proceeds.
        given:
        def buyer = new Wallet(id: 1L, username: 'steam_111', balance: new BigDecimal("100.00"))
        buyer.frozen = null
        def listing = new Listing(
            id: 5L, item: new Item(id: 10L, name: 'Hat'),
            price: new BigDecimal("50.00"), status: 'ACTIVE', sellerName: 'Bot')
        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)

        when:
        def result = service.buy(1L, 999L, 5L)

        then: "null frozen flag does not throw WALLET_FROZEN"
        result.newBalance == new BigDecimal("50.00")
        listing.status == 'SOLD'
    }

    // ── CART_ITEM_SOLD fan-out is capped at 50 ────────────────────

    def "buy caps the CART_ITEM_SOLD fan-out at 50 recipients even when more carts hold it (batch 503)"() {
        given: "60 other users had the listing queued in their cart"
        def buyer = new Wallet(id: 1L, username: 'steam_111', balance: new BigDecimal("200.00"))
        def item = new Item(id: 10L, name: 'Wizard Hat')
        def listing = new Listing(id: 5L, item: item, price: new BigDecimal('50.00'),
                                  status: 'ACTIVE', sellerName: 'Bot')
        def cartRepo = Mock(com.sboxmarket.repository.CartItemRepository)
        def notifier = Mock(com.sboxmarket.service.NotificationService)
        service.cartItemRepository = cartRepo
        service.notificationService = notifier
        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)
        cartRepo.findOtherUsersWithListing(5L, 999L) >> (1L..60L).collect { it as Long }

        when:
        service.buy(1L, 999L, 5L)

        then: "exactly 50 CART_ITEM_SOLD pushes — the .take(50) cap holds"
        50 * notifier.push(_, 'CART_ITEM_SOLD', _, _, 5L, '/item/10')
        // The scrub still runs once regardless of the cap.
        1 * cartRepo.deleteAllByListing(5L)
    }

    def "buy still completes when a single CART_ITEM_SOLD push throws (per-recipient swallow)"() {
        given:
        def buyer = new Wallet(id: 1L, username: 'steam_111', balance: new BigDecimal("200.00"))
        def item = new Item(id: 10L, name: 'Wizard Hat')
        def listing = new Listing(id: 5L, item: item, price: new BigDecimal('50.00'),
                                  status: 'ACTIVE', sellerName: 'Bot')
        def cartRepo = Mock(com.sboxmarket.repository.CartItemRepository)
        def notifier = Mock(com.sboxmarket.service.NotificationService)
        service.cartItemRepository = cartRepo
        service.notificationService = notifier
        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)
        cartRepo.findOtherUsersWithListing(5L, 999L) >> [42L, 77L]
        // The first recipient's push fails — must not abort the fan-out
        // or the money path.
        notifier.push(42L, 'CART_ITEM_SOLD', _, _, _, _) >> { throw new RuntimeException("bell down") }

        when:
        def result = service.buy(1L, 999L, 5L)

        then: "the buy succeeds, the OTHER recipient still gets pushed, scrub still runs"
        result.newBalance == new BigDecimal("150.00")
        listing.status == 'SOLD'
        1 * notifier.push(77L, 'CART_ITEM_SOLD', _, _, _, _)
        1 * cartRepo.deleteAllByListing(5L)
    }

    def "buy drops BANNED recipients from the CART_ITEM_SOLD fan-out (same skip pattern as the saved-search and seller-follow fan-outs)"() {
        // A user banned after queuing a listing in their cart can't act
        // on a CART_ITEM_SOLD ping (banGuard rejects any re-shop), so
        // the bell entry is dead-end noise. The sister fan-outs in
        // SavedSearchService.notifyMatchingForListing and
        // SellerFollowService.notifyFollowersOfNewListing already filter
        // banned recipients; CART_ITEM_SOLD was the outlier — leaking
        // pushes to banned accounts they could no longer use.
        given:
        def buyer = new Wallet(id: 1L, username: 'steam_111', balance: new BigDecimal('100.00'))
        def item  = new Item(id: 10L, name: 'Wizard Hat')
        def listing = new Listing(id: 5L, item: item, price: new BigDecimal('50.00'),
                                  status: 'ACTIVE', sellerName: 'Bot')
        def cartRepo = Mock(com.sboxmarket.repository.CartItemRepository)
        def notifier = Mock(com.sboxmarket.service.NotificationService)
        service.cartItemRepository = cartRepo
        service.notificationService = notifier
        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)
        // Three other cart-holders, the middle one (77L) is banned.
        cartRepo.findOtherUsersWithListing(5L, 999L) >> [42L, 77L, 88L]
        steamUserRepo.findAllById([42L, 77L, 88L]) >> [
            new SteamUser(id: 42L, banned: false),
            new SteamUser(id: 77L, banned: true),
            new SteamUser(id: 88L, banned: false)
        ]

        when:
        service.buy(1L, 999L, 5L)

        then: "only the two NON-banned recipients are pushed"
        1 * notifier.push(42L, 'CART_ITEM_SOLD', _, _, 5L, '/item/10')
        0 * notifier.push(77L, 'CART_ITEM_SOLD', _, _, _, _)
        1 * notifier.push(88L, 'CART_ITEM_SOLD', _, _, 5L, '/item/10')
        // Scrub still runs — keeping the cart row would just leave a
        // ghost on every cart, including the banned one's.
        1 * cartRepo.deleteAllByListing(5L)
    }

    def "buy still completes when the cart-holders LOOKUP throws (deferOrRun safety)"() {
        // Regression for the deferOrRun wrapper around the CART_ITEM_SOLD
        // fan-out. The motivating comment on PurchaseService.deferOrRun
        // explains the failure mode: the cart query / bulk DELETE are
        // repository calls — if either throws (DB blip, lock-wait
        // timeout, dialect quirk), Spring's inner @Transactional proxy
        // calls setRollbackOnly() on the SHARED transaction. The old
        // try/catch hid the exception but the outer commit still
        // bombed with UnexpectedRollbackException, rolling back the
        // money write + SOLD flip + Trade escrow while the buyer's UI
        // claimed success. Deferring the scrub to afterCommit (or
        // running it in a fresh REQUIRES_NEW under no-tx callers, as
        // here) keeps a cart-side failure from poisoning the sale.
        given:
        def buyer = new Wallet(id: 1L, username: 'steam_111', balance: new BigDecimal("100.00"))
        def item = new Item(id: 10L, name: 'Wizard Hat')
        def listing = new Listing(id: 5L, item: item, price: new BigDecimal('50.00'),
                                  status: 'ACTIVE', sellerName: 'Bot')
        def cartRepo = Mock(com.sboxmarket.repository.CartItemRepository)
        def notifier = Mock(com.sboxmarket.service.NotificationService)
        service.cartItemRepository = cartRepo
        service.notificationService = notifier
        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)
        // The cart-holders query itself blows up — the deferOrRun
        // wrapper must catch this so the buy still returns success.
        cartRepo.findOtherUsersWithListing(5L, 999L) >> { throw new RuntimeException("cart DB down") }

        when:
        def result = service.buy(1L, 999L, 5L)

        then: "money path stands — buyer was charged, listing flipped, no exception escapes"
        noExceptionThrown()
        result.newBalance == new BigDecimal("50.00")
        listing.status == 'SOLD'
        1 * txRepo.save({ Transaction tx -> tx.type == 'PURCHASE' })
        // No fan-out happened because the lookup blew up first.
        0 * notifier.push(_, 'CART_ITEM_SOLD', _, _, _, _)
    }

    // ── No escrow Trade for a system listing ──────────────────────

    def "buy does NOT open a Trade for a system listing (sellerUserId == null)"() {
        given: "a system listing — no counterparty, so no escrow"
        def buyer = new Wallet(id: 1L, username: 'steam_111', balance: new BigDecimal("100.00"))
        def listing = new Listing(
            id: 5L, item: new Item(id: 10L, name: 'Hat'),
            price: new BigDecimal("50.00"), status: 'ACTIVE', sellerName: 'System')
        def tradeService = Mock(TradeService)
        service.tradeService = tradeService
        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)

        when:
        def result = service.buy(1L, 999L, 5L)

        then: "no TradeService.open call — system listings settle in-platform"
        0 * tradeService.open(*_)
        result.newBalance == new BigDecimal("50.00")
        listing.status == 'SOLD'
    }

    // ── Debit happens exactly once ────────────────────────────────

    def "buy debits the buyer wallet exactly once (no double-debit)"() {
        given:
        def buyer = new Wallet(id: 1L, username: 'steam_111', balance: new BigDecimal("100.00"))
        def listing = new Listing(
            id: 5L, item: new Item(id: 10L, name: 'Hat'),
            price: new BigDecimal("30.00"), status: 'ACTIVE', sellerName: 'Bot')
        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)

        when:
        service.buy(1L, 999L, 5L)

        then: "exactly one wallet save, balance reduced by exactly the price"
        1 * walletRepo.save({ Wallet w -> w.balance == new BigDecimal("70.00") })
        buyer.balance == new BigDecimal("70.00")
    }

    // ── Double-purchase guard: a second buy on the now-SOLD row is rejected ──

    def "buy on a listing this same call already flipped to SOLD is rejected on retry (no double-spend)"() {
        // Regression: the status-gate is the in-process double-purchase
        // guard. After a successful buy the listing row carries
        // status='SOLD'; a second buy() on the SAME row (e.g. an
        // impatient double-click that the @Version race didn't catch
        // because the first request already committed) must fail the
        // `status != 'ACTIVE'` gate — NOT debit the wallet a second
        // time. Cross-request races are covered separately by the
        // @Version optimistic-lock specs above; this nails the
        // sequential case the version token can't see.
        given:
        def buyer = new Wallet(id: 1L, username: 'steam_111', balance: new BigDecimal("100.00"))
        def listing = new Listing(
            id: 5L, item: new Item(id: 10L, name: 'Hat'),
            price: new BigDecimal("40.00"), status: 'ACTIVE', sellerName: 'Bot')
        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)

        when: "first buy succeeds and flips the row to SOLD"
        def first = service.buy(1L, 999L, 5L)

        then: "exactly one debit + one listing flush + one purchase tx for the successful buy"
        first.newBalance == new BigDecimal("60.00")
        listing.status == 'SOLD'
        1 * walletRepo.save({ Wallet w -> w.balance == new BigDecimal("60.00") })
        1 * listingRepo.saveAndFlush(_)
        1 * txRepo.save({ it.type == 'PURCHASE' })

        when: "a second buy on the very same (now SOLD) row"
        service.buy(1L, 999L, 5L)

        then: "the status gate throws — the buyer is not charged twice"
        thrown(ListingNotAvailableException)
        // Balance untouched by the rejected retry — still the post-first-buy figure.
        buyer.balance == new BigDecimal("60.00")
        // The rejected retry performs no debit, no listing transition, no ledger row.
        0 * walletRepo.save(_)
        0 * listingRepo.saveAndFlush(_)
        0 * txRepo.save(_)
    }

    // ── Escrow ordering: the Trade opens only after the sale is committed ──

    def "buy opens the escrow Trade only after the wallet debit, listing SOLD flip, and PURCHASE tx"() {
        // Money-path ordering invariant: the P2P escrow Trade must be
        // created strictly AFTER the buyer is debited, the listing is
        // flushed to SOLD, and the buyer-side PURCHASE ledger row is
        // written. If TradeService.open ran first, a failure between
        // open() and the debit would leave an escrow Trade with no
        // funds behind it.
        given:
        def buyer = new Wallet(id: 1L, username: 'steam_111', balance: new BigDecimal("200.00"), currency: 'USD')
        def seller = new SteamUser(id: 2L, steamId64: '222')
        def sellerWallet = new Wallet(id: 500L, username: 'steam_222', balance: new BigDecimal("0.00"))
        def listing = new Listing(
            id: 5L, item: new Item(id: 10L, name: 'Wizard Hat'),
            price: new BigDecimal("100.00"), status: 'ACTIVE',
            sellerName: 'Bob', sellerUserId: 2L)
        def tradeService = Mock(TradeService)
        service.tradeService = tradeService
        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)
        steamUserRepo.findById(2L) >> Optional.of(seller)
        walletRepo.findByUsername('steam_222') >> sellerWallet

        when:
        service.buy(1L, 999L, 5L)

        then: "strict call order — debit, then SOLD flush, then PURCHASE tx, then escrow open"
        1 * walletRepo.save({ Wallet w -> w.balance == new BigDecimal("100.00") })

        then:
        1 * listingRepo.saveAndFlush({ Listing l -> l.status == 'SOLD' })

        then:
        1 * txRepo.save({ Transaction t -> t.type == 'PURCHASE' })

        then:
        1 * tradeService.open(5L, 10L, 'Wizard Hat', 999L, 1L, 2L, 500L, new BigDecimal("100.00"))
    }

    // ── buy always charges listing.price as read at call time ──────

    def "buy charges exactly listing.price even when the price was lowered just before the call (OfferService accept path)"() {
        // OfferService.acceptOffer lowers listing.price to the accepted
        // offer amount, saves, then re-enters PurchaseService.buy. This
        // pins the contract OfferService relies on: buy() debits and
        // records whatever listing.price reads at call time — not some
        // stale or original figure.
        given: "a listing whose price was just rewritten to the offer amount"
        def buyer = new Wallet(id: 1L, username: 'steam_111', balance: new BigDecimal("100.00"), currency: 'USD')
        def listing = new Listing(
            id: 5L, item: new Item(id: 10L, name: 'Hat'),
            price: new BigDecimal("63.50"), status: 'ACTIVE', sellerName: 'Bot')
        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)

        when:
        def result = service.buy(1L, 999L, 5L)

        then: "debit + ledger row + result all reflect the at-call-time price exactly"
        result.newBalance == new BigDecimal("36.50")
        buyer.balance == new BigDecimal("36.50")
        1 * txRepo.save({ Transaction t -> t.amount == new BigDecimal("63.50") })
    }
}
