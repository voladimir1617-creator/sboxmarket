package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.Trade
import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.model.Listing
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.TradeService
import com.sboxmarket.service.security.AdminAuthorization
import com.sboxmarket.service.security.BanGuard
import spock.lang.Specification
import spock.lang.Subject

/**
 * Unit coverage for the escrow state machine.
 *
 * Each legal transition (open → sellerAccept → sellerMarkSent →
 * buyerConfirm → VERIFIED) is asserted with both its state change and
 * its money movement. The dispute / cancel exits are covered with the
 * participant guard and the buyer refund path. Staff-side cancel (admin
 * override) is exercised via a mock AdminAuthorization.
 */
class TradeServiceSpec extends Specification {

    TradeRepository       tradeRepository       = Mock()
    WalletRepository      walletRepository      = Mock()
    TransactionRepository transactionRepository = Mock()
    ListingRepository     listingRepository     = Mock()
    NotificationService   notificationService   = Mock()
    BanGuard              banGuard              = Mock()
    AdminAuthorization    adminAuthorization    = Mock()
    com.sboxmarket.repository.ItemRepository itemRepository = Mock()
    com.sboxmarket.repository.SteamUserRepository steamUserRepository = Mock() {
        // Default: no banned users and no counterparty users. Tests that
        // need a specific row override with their own stub. Without this
        // default, the service NPEs on users.collectEntries (Mock returns
        // null, not [] for the inherited Iterable<T> findAllById).
        findAllById(_) >> []
    }
    TextSanitizer         textSanitizer         = Mock() {
        medium(_) >> { String s -> s ?: '' }
    }
    com.sboxmarket.service.EmailService emailService = Mock() {
        // Delegate to the user's actual flags — mimics the real
        // canSendTo gate (batch 622). Tests that flip
        // `emailVerified:false` / `emailNotificationsEnabled:false`
        // on a fixture still see the gate close without per-test stubs.
        canSendTo(_, _) >> { user, bucket ->
            user != null &&
            user.email && !user.email.isEmpty() &&
            Boolean.TRUE.equals(user.emailVerified) &&
            Boolean.TRUE.equals(user.emailNotificationsEnabled)
        }
    }

    @Subject
    TradeService service = new TradeService(
        tradeRepository       : tradeRepository,
        walletRepository      : walletRepository,
        transactionRepository : transactionRepository,
        listingRepository     : listingRepository,
        notificationService   : notificationService,
        banGuard              : banGuard,
        adminAuthorization    : adminAuthorization,
        steamUserRepository   : steamUserRepository,
        textSanitizer         : textSanitizer,
        emailService          : emailService,
        itemRepository        : itemRepository,
        autoReleaseDays       : 8L,
        sellerResponseDays    : 3L
    )

    private Trade tradeIn(String state, Map args = [:]) {
        new Trade(
            id:             args.id ?: 1L,
            listingId:      args.listingId ?: 100L,
            itemId:         args.itemId ?: 1L,
            itemName:       args.itemName ?: 'Wizard Hat',
            buyerUserId:    args.buyer ?: 10L,
            buyerWalletId:  args.buyerWallet ?: 500L,
            sellerUserId:   args.seller ?: 20L,
            sellerWalletId: args.sellerWallet ?: 600L,
            price:          args.price ?: new BigDecimal("50.00"),
            feeAmount:      args.fee ?: new BigDecimal("1.00"),
            state:          state
        )
    }

    // ── open ──────────────────────────────────────────────────────

    def "open creates a PENDING_SELLER_ACCEPT trade when a seller is known"() {
        given:
        tradeRepository.save(_) >> { Trade t -> t.id = 1L; t }

        when:
        def trade = service.open(100L, 1L, 'Wizard Hat', 10L, 500L, 20L, 600L, new BigDecimal("50"))

        then:
        1 * banGuard.assertNotBanned(10L)
        trade.state == 'PENDING_SELLER_ACCEPT'
        trade.feeAmount == new BigDecimal("1.00")
        1 * notificationService.safePush(10L, 'TRADE_OPENED', _, _, _, _)
        1 * notificationService.safePush(20L, 'TRADE_REQUESTED', _, _, _, _)
    }

    def "open skips seller-accept and goes to PENDING_BUYER_CONFIRM when there is no seller"() {
        given:
        tradeRepository.save(_) >> { Trade t -> t.id = 1L; t }

        when:
        def trade = service.open(100L, 1L, 'Wizard Hat', 10L, 500L, null, null, new BigDecimal("50"))

        then:
        trade.state == 'PENDING_BUYER_CONFIRM'
        1 * notificationService.safePush(10L, 'TRADE_OPENED', _, _, _, _)
        0 * notificationService.safePush(_, 'TRADE_REQUESTED', _, _, _, _)
    }

    def "open sends a TRADE_OPENED email to a seller with verified email (batch 564)"() {
        given:
        tradeRepository.save(_) >> { Trade t -> t.id = 1L; t }
        // Seller with verified email and notifications enabled.
        def buyer  = new com.sboxmarket.model.SteamUser(id: 10L, displayName: 'Alice')
        def seller = new com.sboxmarket.model.SteamUser(
            id: 20L, displayName: 'Bob',
            email: 'bob@example.com', emailVerified: true,
            emailNotificationsEnabled: true)
        steamUserRepository.findById(10L) >> Optional.of(buyer)
        steamUserRepository.findById(20L) >> Optional.of(seller)

        when:
        service.open(100L, 1L, 'Wizard Hat', 10L, 500L, 20L, 600L, new BigDecimal("50"))

        then:
        1 * emailService.sendTradeOpened('bob@example.com', 'Bob', 'Wizard Hat', 'Alice', new BigDecimal("50"), 1L)
    }

    def "open does NOT email a seller who opted out of notifications (batch 564)"() {
        given:
        tradeRepository.save(_) >> { Trade t -> t.id = 1L; t }
        def seller = new com.sboxmarket.model.SteamUser(
            id: 20L, displayName: 'Bob',
            email: 'bob@example.com', emailVerified: true,
            emailNotificationsEnabled: false)  // ← opt-out
        steamUserRepository.findById(10L) >> Optional.of(new com.sboxmarket.model.SteamUser(id: 10L, displayName: 'Alice'))
        steamUserRepository.findById(20L) >> Optional.of(seller)

        when:
        service.open(100L, 1L, 'Wizard Hat', 10L, 500L, 20L, 600L, new BigDecimal("50"))

        then:
        0 * emailService.sendTradeOpened(*_)
    }

    def "open skips the email when the seller's email is unverified (batch 564)"() {
        given:
        tradeRepository.save(_) >> { Trade t -> t.id = 1L; t }
        def seller = new com.sboxmarket.model.SteamUser(
            id: 20L, displayName: 'Bob',
            email: 'bob@example.com', emailVerified: false,  // ← not verified
            emailNotificationsEnabled: true)
        steamUserRepository.findById(10L) >> Optional.of(new com.sboxmarket.model.SteamUser(id: 10L, displayName: 'Alice'))
        steamUserRepository.findById(20L) >> Optional.of(seller)

        when:
        service.open(100L, 1L, 'Wizard Hat', 10L, 500L, 20L, 600L, new BigDecimal("50"))

        then:
        0 * emailService.sendTradeOpened(*_)
    }

    // ── sellerAccept / sellerMarkSent ─────────────────────────────

    def "sellerAccept advances PENDING_SELLER_ACCEPT → PENDING_SELLER_SEND"() {
        given:
        def t = tradeIn('PENDING_SELLER_ACCEPT')
        tradeRepository.findById(1L) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade x -> x }

        when:
        service.sellerAccept(20L, 1L)

        then:
        1 * banGuard.assertNotBanned(20L)
        t.state == 'PENDING_SELLER_SEND'
        1 * notificationService.safePush(10L, 'TRADE_ACCEPTED', _, _, _, _)
    }

    def "sellerAccept forbids a non-seller"() {
        given:
        tradeRepository.findById(_) >> Optional.of(tradeIn('PENDING_SELLER_ACCEPT'))

        when:
        service.sellerAccept(99L, 1L)

        then:
        thrown(ForbiddenException)
    }

    def "sellerAccept refuses wrong starting state"() {
        given:
        tradeRepository.findById(_) >> Optional.of(tradeIn('PENDING_BUYER_CONFIRM'))

        when:
        service.sellerAccept(20L, 1L)

        then:
        thrown(BadRequestException)
    }

    def "sellerMarkSent advances PENDING_SELLER_SEND → PENDING_BUYER_CONFIRM"() {
        given:
        def t = tradeIn('PENDING_SELLER_SEND')
        tradeRepository.findById(1L) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade x -> x }

        when:
        service.sellerMarkSent(20L, 1L)

        then:
        t.state == 'PENDING_BUYER_CONFIRM'
        1 * notificationService.safePush(10L, 'TRADE_SENT', _, _, _, _)
    }

    def "sellerMarkSent sends a TRADE_SENT email to the buyer (batch 565)"() {
        given:
        def t = tradeIn('PENDING_SELLER_SEND', [id: 7L, buyer: 10L, seller: 20L])
        tradeRepository.findById(7L) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade x -> x }
        def buyer = new com.sboxmarket.model.SteamUser(
            id: 10L, displayName: 'Alice',
            email: 'alice@example.com', emailVerified: true,
            emailNotificationsEnabled: true)
        def seller = new com.sboxmarket.model.SteamUser(id: 20L, displayName: 'Bob')
        steamUserRepository.findById(10L) >> Optional.of(buyer)
        steamUserRepository.findById(20L) >> Optional.of(seller)

        when:
        service.sellerMarkSent(20L, 7L)

        then:
        1 * emailService.sendTradeSent('alice@example.com', 'Alice', 'Wizard Hat', 'Bob', 7L)
    }

    def "sellerMarkSent stores a valid Steam trade-offer URL (batch 773)"() {
        given:
        def t = tradeIn('PENDING_SELLER_SEND')
        tradeRepository.findById(1L) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade x -> x }

        when:
        service.sellerMarkSent(20L, 1L, 'https://steamcommunity.com/tradeoffer/123456789/')

        then:
        t.tradeOfferUrl == 'https://steamcommunity.com/tradeoffer/123456789/'
    }

    def "sellerMarkSent rejects a non-Steam URL with TRADE_OFFER_URL_INVALID (batch 860 — was silent-drop pre-batch-860)"() {
        given:
        def t = tradeIn('PENDING_SELLER_SEND')
        tradeRepository.findById(1L) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade x -> x }

        when:
        // Attacker pastes a phishing URL in hopes of tricking the buyer —
        // the service regex rejects anything that isn't steamcommunity.com.
        // Batch 860 upgraded this from silent-drop to a hard 400 so the
        // seller's UI can surface "your URL is invalid" instead of
        // accepting a click then silently keeping the trade URL-less.
        service.sellerMarkSent(20L, 1L, 'https://evil.example.com/tradeoffer/1/')

        then:
        def e = thrown(com.sboxmarket.exception.BadRequestException)
        e.code == 'TRADE_OFFER_URL_INVALID'
        t.tradeOfferUrl == null
        // State doesn't advance on a rejected URL — the seller fixes
        // their paste and tries again.
        t.state == 'PENDING_SELLER_SEND'
    }

    def "sellerMarkSent silently drops an empty URL (batch 773)"() {
        given:
        def t = tradeIn('PENDING_SELLER_SEND')
        tradeRepository.findById(1L) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade x -> x }

        when:
        // Seller hits Cancel / leaves the prompt blank — legacy no-URL
        // path, still advances the trade state but writes null.
        service.sellerMarkSent(20L, 1L, '')

        then:
        t.tradeOfferUrl == null
        t.state == 'PENDING_BUYER_CONFIRM'
    }

    def "sellerMarkSent accepts null URL without error (batch 773 legacy path)"() {
        given:
        def t = tradeIn('PENDING_SELLER_SEND')
        tradeRepository.findById(1L) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade x -> x }

        when:
        // Preserves the old two-arg contract (tradeOfferUrl defaults to null).
        service.sellerMarkSent(20L, 1L)

        then:
        t.tradeOfferUrl == null
        t.state == 'PENDING_BUYER_CONFIRM'
    }

    def "sellerMarkSent stamps sentAt once — subsequent call doesn't overwrite (batch 550)"() {
        given:
        def t = tradeIn('PENDING_SELLER_SEND')
        t.sentAt = 12345L  // pre-stamped (e.g., from admin replay scenario)
        tradeRepository.findById(1L) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade x -> x }

        when:
        service.sellerMarkSent(20L, 1L)

        then: "sentAt is preserved rather than re-stamped"
        t.sentAt == 12345L
    }

    // ── buyerConfirm → release ────────────────────────────────────

    def "buyerConfirm releases funds and flips to VERIFIED"() {
        given:
        def t = tradeIn('PENDING_BUYER_CONFIRM')
        def sellerWallet = new Wallet(id: 600L, balance: new BigDecimal("0.00"), currency: 'USD')
        tradeRepository.findById(1L) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade x -> x }
        walletRepository.findById(600L) >> Optional.of(sellerWallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }

        when:
        service.buyerConfirm(10L, 1L)

        then:
        t.state == 'VERIFIED'
        // 50.00 − 1.00 fee = 49.00 credited
        sellerWallet.balance == new BigDecimal("49.00")
        1 * transactionRepository.save({ Transaction tx ->
            tx.type == 'SALE' && tx.amount == new BigDecimal("49.00")
        })
        1 * notificationService.safePush(10L, 'TRADE_VERIFIED', _, _, _, _)
        1 * notificationService.safePush(20L, 'TRADE_VERIFIED', _, _, _, _)
        // Review-nudge for the buyer — deep-links to the seller's stall so
        // the "Leave a review" CTA is one click away. Only fires when the
        // trade has a real sellerUserId (system listings stay silent).
        1 * notificationService.safePush(10L, 'REVIEW_REMINDER', _, _, _, '/stall/20')
    }

    def "buyerConfirm forbids a non-buyer"() {
        given:
        tradeRepository.findById(_) >> Optional.of(tradeIn('PENDING_BUYER_CONFIRM'))

        when:
        service.buyerConfirm(99L, 1L)

        then:
        thrown(ForbiddenException)
    }

    // ── dispute ───────────────────────────────────────────────────

    def "dispute flips the trade to DISPUTED from any pending state"() {
        given:
        def t = tradeIn(startState)
        tradeRepository.findById(1L) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade x -> x }

        when:
        service.dispute(10L, 1L, 'seller ghosted me')

        then:
        t.state == 'DISPUTED'
        t.note == 'seller ghosted me'

        where:
        startState << ['PENDING_SELLER_ACCEPT', 'PENDING_SELLER_SEND', 'PENDING_BUYER_CONFIRM']
    }

    def "dispute forbids non-participants"() {
        given:
        tradeRepository.findById(_) >> Optional.of(tradeIn('PENDING_SELLER_ACCEPT'))

        when:
        service.dispute(999L, 1L, 'nosy')

        then:
        thrown(ForbiddenException)
    }

    def "dispute refuses terminal states"() {
        given:
        tradeRepository.findById(_) >> Optional.of(tradeIn('VERIFIED'))

        when:
        service.dispute(10L, 1L, '?')

        then:
        thrown(BadRequestException)
    }

    def "dispute pushes TRADE_DISPUTED notification to the counterparty (bug #102)"() {
        given:
        // Buyer (uid 10) files the dispute → seller (uid 20) should be notified.
        def t = tradeIn('PENDING_SELLER_SEND')
        tradeRepository.findById(1L) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade x -> x }
        textSanitizer.medium('seller ghosted') >> 'seller ghosted'

        when:
        service.dispute(10L, 1L, 'seller ghosted')

        then:
        t.state == 'DISPUTED'
        // Filer does NOT get a self-notification; only the counterparty.
        1 * notificationService.push(20L, 'TRADE_DISPUTED', _, _, 1L, '/profile?tab=trades')
        0 * notificationService.push(10L, 'TRADE_DISPUTED', _, _, _, _)
    }

    def "dispute emails the counterparty when they have a verified address (batch 567)"() {
        given:
        def t = tradeIn('PENDING_SELLER_SEND')
        tradeRepository.findById(1L) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade x -> x }
        def seller = new com.sboxmarket.model.SteamUser(
            id: 20L, displayName: 'Bob',
            email: 'bob@example.com', emailVerified: true,
            emailNotificationsEnabled: true)
        steamUserRepository.findById(20L) >> Optional.of(seller)
        steamUserRepository.findByRole('ADMIN') >> []

        when:
        service.dispute(10L, 1L, 'item not received')

        then:
        // Default sanitizer stub echoes input, so the reason arrives as-is.
        1 * emailService.sendTradeDisputed('bob@example.com', 'Bob', 'Wizard Hat', 'BUYER', 'item not received', 1L)
    }

    def "dispute skips the email when counterparty email is unverified (batch 567)"() {
        given:
        def t = tradeIn('PENDING_SELLER_SEND')
        tradeRepository.findById(1L) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade x -> x }
        textSanitizer.medium(_) >> 'reason body'
        def seller = new com.sboxmarket.model.SteamUser(
            id: 20L, displayName: 'Bob',
            email: 'bob@example.com', emailVerified: false,  // ← not verified
            emailNotificationsEnabled: true)
        steamUserRepository.findById(20L) >> Optional.of(seller)
        steamUserRepository.findByRole('ADMIN') >> []

        when:
        service.dispute(10L, 1L, 'item not received')

        then:
        0 * emailService.sendTradeDisputed(*_)
    }

    def "dispute fans out TRADE_DISPUTED to every admin (batch 500)"() {
        given:
        def t = tradeIn('PENDING_SELLER_SEND')
        tradeRepository.findById(1L) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade x -> x }
        // Two admins on file — fan-out should ping both with the trades
        // deep-link. Matches the chargeback-fan-out shape from batch 461.
        def admin1 = new com.sboxmarket.model.SteamUser(id: 501L, displayName: 'Admin One', role: 'ADMIN')
        def admin2 = new com.sboxmarket.model.SteamUser(id: 502L, displayName: 'Admin Two', role: 'ADMIN')
        steamUserRepository.findByRole('ADMIN') >> [admin1, admin2]

        when:
        service.dispute(10L, 1L, 'seller ghosted')

        then:
        1 * notificationService.push(501L, 'TRADE_DISPUTED', _, _, 1L, '/admin?tab=trades&filter=DISPUTED')
        1 * notificationService.push(502L, 'TRADE_DISPUTED', _, _, 1L, '/admin?tab=trades&filter=DISPUTED')
    }

    def "dispute by BUYER fires protection auto-claim (existing behaviour)"() {
        given:
        def tradeProtectionService = Mock(com.sboxmarket.service.TradeProtectionService)
        service.tradeProtectionService = tradeProtectionService
        def t = tradeIn('PENDING_BUYER_CONFIRM')
        tradeRepository.findById(1L) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade x -> x }

        when: 'buyer (uid 10) files the dispute'
        service.dispute(10L, 1L, 'item never arrived')

        then: 'protection auto-claim runs — buyer is made whole immediately'
        1 * tradeProtectionService.autoClaim(1L, 'Trade disputed by buyer')
    }

    def "dispute by SELLER does NOT fire protection auto-claim (anti-fraud guard)"() {
        // The hole this guards: a buyer enables Trade Protection (2% fee),
        // accepts the seller's Steam offer (taking the item), then ghosts
        // buyerConfirm. The seller files a dispute. If autoClaim fires
        // unconditionally on every dispute, the protection pays the buyer
        // the FULL item price — net result: buyer keeps the item AND gets
        // a full refund, paying only the 2% protection fee. The platform
        // eats the entire item price. Auto-claim must be gated on the
        // BUYER being the filer; seller-filed disputes wait for staff.
        given:
        def tradeProtectionService = Mock(com.sboxmarket.service.TradeProtectionService)
        service.tradeProtectionService = tradeProtectionService
        def t = tradeIn('PENDING_BUYER_CONFIRM')
        tradeRepository.findById(1L) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade x -> x }

        when: 'seller (uid 20) files the dispute — buyer ghosted after receiving the item'
        service.dispute(20L, 1L, 'buyer received but never confirmed')

        then: 'NO auto-claim — staff must resolve a seller-filed dispute'
        0 * tradeProtectionService.autoClaim(*_)
        and: 'trade still flips to DISPUTED for staff queue'
        t.state == 'DISPUTED'
    }

    // ── cancel ────────────────────────────────────────────────────

    def "cancel refunds the buyer wallet and flips to CANCELLED"() {
        given:
        def t = tradeIn('PENDING_SELLER_SEND')
        def buyerWallet = new Wallet(id: 500L, balance: new BigDecimal("0.00"), currency: 'USD')
        tradeRepository.findById(1L) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade x -> x }
        walletRepository.findById(500L) >> Optional.of(buyerWallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        // Listing is in buyer's inventory at this point (status=SOLD,
        // buyerUserId=10 after the original purchase). Cancel should
        // return it to the seller so they can relist.
        def listing = new Listing(id: 100L, status: 'SOLD', buyerUserId: 10L, sellerUserId: 20L)
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { Listing l -> l }

        when:
        service.cancel(10L, 1L, 'changed my mind')

        then:
        buyerWallet.balance == new BigDecimal("50.00")
        1 * transactionRepository.save({ Transaction tx -> tx.type == 'REFUND' && tx.amount == new BigDecimal("50.00") })
        t.state == 'CANCELLED'
        t.settledAt != null
        // Listing ownership returned to the seller (bug #103).
        listing.buyerUserId == 20L
        listing.status == 'SOLD'
        listing.soldAt != null
    }

    def "cancel decrements Item.totalSold so a buy → cancel loop can't inflate Most Traded"() {
        given: "a cancellable trade whose listing carries an item"
        def t = tradeIn('PENDING_SELLER_SEND')
        def buyerWallet = new Wallet(id: 500L, balance: new BigDecimal("0.00"), currency: 'USD')
        tradeRepository.findById(1L) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade x -> x }
        walletRepository.findById(500L) >> Optional.of(buyerWallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        def item = new com.sboxmarket.model.Item(id: 77L, name: 'Wizard Hat')
        def listing = new Listing(id: 100L, status: 'SOLD', buyerUserId: 10L,
                                  sellerUserId: 20L, item: item)
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { Listing l -> l }

        when:
        service.cancel(10L, 1L, 'changed my mind')

        then: "the increment from the original sale is reversed"
        1 * itemRepository.decrementTotalSold(77L)
    }

    // ── bot-escrow custody release on cancel (data-consistency audit) ──
    // A cancel/auto-cancel returns the DB listing to the seller but, before
    // this fix, never released the bot's IN_CUSTODY hold on the real Steam
    // asset → the physical item was stranded in the bot forever. The escrow
    // return is deferred (afterCommit), which runs inline in these no-tx unit
    // specs (same as the totalSold decrement above), so it's assertable here.

    def "cancel returns the bot-held item to the seller when escrow is enabled (data-consistency audit)"() {
        given: "a cancellable trade whose listing's real asset the bot holds, escrow ON"
        def t = tradeIn('PENDING_SELLER_SEND')
        def buyerWallet = new Wallet(id: 500L, balance: new BigDecimal("0.00"), currency: 'USD')
        tradeRepository.findById(1L) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade x -> x }
        walletRepository.findById(500L) >> Optional.of(buyerWallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        def listing = new Listing(id: 100L, status: 'SOLD', buyerUserId: 10L, sellerUserId: 20L, item: null)
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { Listing l -> l }
        def escrow = Mock(com.sboxmarket.service.SteamEscrowService)
        escrow.escrowEnabled >> true
        service.steamEscrowService = escrow

        when:
        service.cancel(10L, 1L, 'changed my mind')

        then: "bot custody is released back to the seller for the cancelled trade's listing"
        1 * escrow.returnToSeller(100L, _)

        cleanup:
        service.steamEscrowService = null
    }

    def "cancel does NOT touch bot escrow when escrow is disabled"() {
        given: "escrow service present but disabled"
        def t = tradeIn('PENDING_SELLER_SEND')
        def buyerWallet = new Wallet(id: 500L, balance: new BigDecimal("0.00"), currency: 'USD')
        tradeRepository.findById(1L) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade x -> x }
        walletRepository.findById(500L) >> Optional.of(buyerWallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        def listing = new Listing(id: 100L, status: 'SOLD', buyerUserId: 10L, sellerUserId: 20L, item: null)
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { Listing l -> l }
        def escrow = Mock(com.sboxmarket.service.SteamEscrowService)
        escrow.escrowEnabled >> false
        service.steamEscrowService = escrow

        when:
        service.cancel(10L, 1L, 'changed my mind')

        then: "no return offer is attempted"
        0 * escrow.returnToSeller(_, _)

        cleanup:
        service.steamEscrowService = null
    }

    def "cancel does not call decrementTotalSold when the listing has no item (system listing safety)"() {
        given:
        def t = tradeIn('PENDING_SELLER_SEND')
        def buyerWallet = new Wallet(id: 500L, balance: new BigDecimal("0.00"), currency: 'USD')
        tradeRepository.findById(1L) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade x -> x }
        walletRepository.findById(500L) >> Optional.of(buyerWallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        def listing = new Listing(id: 100L, status: 'SOLD', buyerUserId: 10L,
                                  sellerUserId: 20L, item: null)
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { Listing l -> l }

        when:
        service.cancel(10L, 1L, 'changed my mind')

        then: "no NPE, no decrement, cancel still completes"
        0 * itemRepository.decrementTotalSold(_)
        t.state == 'CANCELLED'
    }

    def "cancel still completes when the totalSold decrement throws (cosmetic, swallowed)"() {
        given:
        def t = tradeIn('PENDING_SELLER_SEND')
        def buyerWallet = new Wallet(id: 500L, balance: new BigDecimal("0.00"), currency: 'USD')
        tradeRepository.findById(1L) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade x -> x }
        walletRepository.findById(500L) >> Optional.of(buyerWallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        def item = new com.sboxmarket.model.Item(id: 77L, name: 'Wizard Hat')
        def listing = new Listing(id: 100L, status: 'SOLD', buyerUserId: 10L,
                                  sellerUserId: 20L, item: item)
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { Listing l -> l }
        itemRepository.decrementTotalSold(_) >> { throw new RuntimeException("counter update failed") }

        when:
        service.cancel(10L, 1L, 'changed my mind')

        then: "buyer is still refunded and trade still moves to CANCELLED — counter failure is cosmetic"
        noExceptionThrown()
        buyerWallet.balance == new BigDecimal("50.00")
        t.state == 'CANCELLED'
    }

    def "cancel emails both buyer and seller (batch 573)"() {
        given:
        def t = tradeIn('PENDING_SELLER_SEND')
        def buyerWallet = new Wallet(id: 500L, balance: new BigDecimal("0.00"), currency: 'USD')
        tradeRepository.findById(1L) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade x -> x }
        walletRepository.findById(500L) >> Optional.of(buyerWallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        def listing = new Listing(id: 100L, status: 'SOLD', buyerUserId: 10L, sellerUserId: 20L)
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { Listing l -> l }
        textSanitizer.medium(_) >> 'changed my mind'
        def buyer = new com.sboxmarket.model.SteamUser(
            id: 10L, displayName: 'Alice',
            email: 'alice@example.com', emailVerified: true,
            emailNotificationsEnabled: true)
        def seller = new com.sboxmarket.model.SteamUser(
            id: 20L, displayName: 'Bob',
            email: 'bob@example.com', emailVerified: true,
            emailNotificationsEnabled: true)
        steamUserRepository.findById(10L) >> Optional.of(buyer)
        steamUserRepository.findById(20L) >> Optional.of(seller)

        when:
        service.cancel(10L, 1L, 'changed my mind')

        then:
        // Buyer gets the 'buyer' role in the email helper.
        1 * emailService.sendTradeCancelled('alice@example.com', 'Alice', 'Wizard Hat', 'changed my mind', 'buyer', 1L)
        1 * emailService.sendTradeCancelled('bob@example.com',   'Bob',   'Wizard Hat', 'changed my mind', 'seller', 1L)
    }

    def "cancel by a non-participant requires admin authorization"() {
        given:
        tradeRepository.findById(_) >> Optional.of(tradeIn('PENDING_SELLER_ACCEPT'))
        adminAuthorization.requireAdmin(999L) >> { throw new ForbiddenException("not admin") }

        when:
        service.cancel(999L, 1L, 'nosy')

        then:
        thrown(ForbiddenException)
    }

    def "cancel refuses terminal states"() {
        given:
        tradeRepository.findById(_) >> Optional.of(tradeIn('VERIFIED'))

        when:
        service.cancel(10L, 1L, 'too late')

        then:
        thrown(BadRequestException)
    }

    def "cancel blocks buyer self-cancel after seller marks sent — anti-theft (batch 326)"() {
        // Trade in PENDING_BUYER_CONFIRM: seller says they sent the
        // Steam offer. If buyer could cancel now, they'd pocket the
        // Steam-delivered item AND get their money refunded.
        given:
        def t = tradeIn('PENDING_BUYER_CONFIRM')
        tradeRepository.findById(_) >> Optional.of(t)

        when:
        // Buyer (id 10) tries to cancel.
        service.cancel(10L, 1L, 'changed my mind')

        then:
        def e = thrown(BadRequestException)
        e.code == 'BUYER_CANT_CANCEL_AFTER_SENT'
        // Trade is untouched — no refund, no state flip.
        t.state == 'PENDING_BUYER_CONFIRM'
    }

    def "cancel blocks the BUYER from cancelling a DISPUTED trade — anti-theft #2"() {
        // The exploit: a buyer in PENDING_BUYER_CONFIRM calls dispute()
        // (state → DISPUTED), which slips past the PENDING_BUYER_CONFIRM
        // anti-theft guard, then calls cancel() — pocketing the item AND
        // the refund. A DISPUTED trade is frozen; only staff resolve it.
        given:
        def t = tradeIn('DISPUTED')
        tradeRepository.findById(_) >> Optional.of(t)

        when: 'the buyer tries to cancel out of dispute'
        service.cancel(10L, 1L, 'let me out')

        then:
        def e = thrown(BadRequestException)
        e.code == 'TRADE_DISPUTED'
        // No refund, no state flip — the trade is untouched.
        t.state == 'DISPUTED'
    }

    def "cancel blocks the SELLER from cancelling a DISPUTED trade (no dodging a fraud ruling)"() {
        // Symmetric: a losing seller must not cancel out of DISPUTED to
        // wipe the trade from the admin queue before staff rule.
        given:
        def t = tradeIn('DISPUTED')
        tradeRepository.findById(_) >> Optional.of(t)

        when:
        service.cancel(20L, 1L, 'nothing to see here')

        then:
        def e = thrown(BadRequestException)
        e.code == 'TRADE_DISPUTED'
        t.state == 'DISPUTED'
    }

    def "cancel still allows an ADMIN to cancel a DISPUTED trade (staff resolution)"() {
        given:
        def t = tradeIn('DISPUTED')
        def buyerWallet = new Wallet(id: 500L, balance: BigDecimal.ZERO, currency: 'USD')
        def listing = new Listing(id: 100L, status: 'SOLD', buyerUserId: 10L, sellerUserId: 20L)
        tradeRepository.findById(_) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade trade -> trade }
        walletRepository.findById(500L) >> Optional.of(buyerWallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { Listing l -> l }
        // User 999 is neither buyer (10) nor seller (20) → admin path.
        adminAuthorization.requireAdmin(999L) >> {}

        when: 'staff resolve the dispute by cancelling + refunding the buyer'
        service.cancel(999L, 1L, 'CSR ruling: seller at fault')

        then:
        t.state == 'CANCELLED'
        buyerWallet.balance == new BigDecimal("50.00")
    }

    def "cancel still allows SELLER self-cancel after marking sent (they may want to take it back before buyer confirms)"() {
        // Sellers aren't affected by the anti-theft guard — they can
        // still cancel their own trade (which would refund the buyer).
        // Why this is acceptable: a seller cancel at PENDING_BUYER_CONFIRM
        // with the item already shipped is self-harm (they lose both
        // money + item), not theft.
        given:
        def t = tradeIn('PENDING_BUYER_CONFIRM')
        def buyerWallet = new Wallet(id: 500L, balance: BigDecimal.ZERO, currency: 'USD')
        def listing = new Listing(id: 100L, status: 'SOLD', buyerUserId: 10L, sellerUserId: 20L)
        tradeRepository.findById(_) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade trade -> trade }
        walletRepository.findById(500L) >> Optional.of(buyerWallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { Listing l -> l }

        when:
        // Seller (id 20) cancels.
        service.cancel(20L, 1L, 'buyer never responded')

        then:
        // Allowed — state flips to CANCELLED, buyer refunded.
        t.state == 'CANCELLED'
        buyerWallet.balance == new BigDecimal("50.00")
    }

    def "adminRelease bypasses the buyer ban guard (batch 327)"() {
        // Before batch 327, admin force-release routed through
        // buyerConfirm which called banGuard.assertNotBanned(buyer).
        // That meant a banned buyer's trade was stuck — admin couldn't
        // release it to pay the (honest) seller. adminRelease skips
        // the buyer ban guard entirely.
        given:
        def t = tradeIn('DISPUTED')
        def buyerWallet = new Wallet(id: 500L, balance: BigDecimal.ZERO, currency: 'USD')
        def sellerWallet = new Wallet(id: 600L, balance: BigDecimal.ZERO, currency: 'USD')
        tradeRepository.findById(_) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade trade -> trade }
        walletRepository.findById(600L) >> Optional.of(sellerWallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        // Admin ID 999 — adminAuthorization passes.
        adminAuthorization.requireAdmin(999L) >> {}

        when:
        // Even if ban-guard would fail for the buyer, admin path doesn't
        // invoke it. Proving by NOT setting up banGuard.assertNotBanned.
        service.adminRelease(999L, 1L, 'CSR release: seller honored trade despite banned buyer')

        then:
        // banGuard was never consulted for the buyer.
        0 * banGuard.assertNotBanned(_)
        // Trade is now VERIFIED; seller paid.
        t.state == 'VERIFIED'
        sellerWallet.balance > BigDecimal.ZERO
    }

    def "adminRelease rejects terminal states"() {
        given:
        tradeRepository.findById(_) >> Optional.of(tradeIn('VERIFIED'))
        adminAuthorization.requireAdmin(999L) >> {}

        when:
        service.adminRelease(999L, 1L, 'already done')

        then:
        thrown(BadRequestException)
    }

    def "adminRelease requires admin authorization"() {
        given:
        tradeRepository.findById(_) >> Optional.of(tradeIn('DISPUTED'))
        adminAuthorization.requireAdmin(999L) >> { throw new ForbiddenException('not admin') }

        when:
        service.adminRelease(999L, 1L, 'not allowed')

        then:
        thrown(ForbiddenException)
    }

    def "cancel still allows admin cancel in PENDING_BUYER_CONFIRM (CSR stuck-trade cleanup)"() {
        given:
        def t = tradeIn('PENDING_BUYER_CONFIRM')
        def buyerWallet = new Wallet(id: 500L, balance: BigDecimal.ZERO, currency: 'USD')
        def listing = new Listing(id: 100L, status: 'SOLD', buyerUserId: 10L, sellerUserId: 20L)
        tradeRepository.findById(_) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade trade -> trade }
        walletRepository.findById(500L) >> Optional.of(buyerWallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { Listing l -> l }
        // User 999 isn't the buyer (10) or seller (20) → admin path.
        adminAuthorization.requireAdmin(999L) >> {}

        when:
        service.cancel(999L, 1L, 'confirmed fraud on seller side')

        then:
        t.state == 'CANCELLED'
    }

    // ── cancel × Trade Protection double-payout guard ─────────────
    //
    // The hole: dispute() fires TradeProtectionService.autoClaim, which
    // refunds a protected buyer the FULL item price the instant the
    // dispute is filed. Staff then resolve the dispute in the buyer's
    // favour the standard way — forceCancelTrade → cancel() (admin
    // branch). If cancel() also ran refundBuyer(), the buyer would be
    // paid the item price TWICE for one escrowed sale and the platform
    // would eat the second payout. cancel() must detect an already-
    // CLAIMED protection and skip the escrow refund.

    private com.sboxmarket.model.TradeProtection protectionRow(String status) {
        new com.sboxmarket.model.TradeProtection(
            id: 1L, tradeId: 1L, buyerUserId: 10L,
            feeAmount: new BigDecimal('1.00'),
            coverageAmount: new BigDecimal('50.00'),
            status: status)
    }

    def "cancel of a DISPUTED trade whose protection already CLAIMED does NOT refund the buyer again (double-payout fix)"() {
        given:
        def tradeProtectionService = Mock(com.sboxmarket.service.TradeProtectionService)
        service.tradeProtectionService = tradeProtectionService
        // Trade was disputed → autoClaim already paid the buyer $50 and
        // flipped the protection to CLAIMED. Staff now cancel it.
        def t = tradeIn('DISPUTED')
        def buyerWallet = new Wallet(id: 500L, balance: new BigDecimal('50.00'), currency: 'USD')
        def listing = new Listing(id: 100L, status: 'SOLD', buyerUserId: 10L, sellerUserId: 20L)
        tradeRepository.findById(_) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade trade -> trade }
        walletRepository.findById(500L) >> Optional.of(buyerWallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { Listing l -> l }
        adminAuthorization.requireAdmin(999L) >> {}
        // Locked decision: CLAIMED → returns true → cancel skips refund.
        tradeProtectionService.lockAndExpireIfActiveOrReportClaimed(1L) >> true

        when: 'staff force-cancel the disputed (already protection-paid) trade'
        service.cancel(999L, 1L, 'CSR ruling: seller at fault')

        then: 'trade closes, but NO second refund is issued — buyer keeps the $50 from the claim only'
        t.state == 'CANCELLED'
        buyerWallet.balance == new BigDecimal('50.00')   // unchanged — not 100.00
        // No REFUND transaction from this cancel — the protection claim
        // already booked the buyer payout.
        0 * transactionRepository.save({ Transaction tx -> tx.type == 'REFUND' })
        // The cover is already CLAIMED — cancel still calls expire() but
        // it is a correct no-op on a non-ACTIVE protection.
        1 * tradeProtectionService.expire(1L)
    }

    def "cancel of a protected trade whose protection is still ACTIVE refunds the buyer normally (changed-mind cancel)"() {
        // A protected buyer who cancels WITHOUT disputing — autoClaim
        // never ran, protection is still ACTIVE — must get the ordinary
        // escrow refund. The skip only applies to an already-CLAIMED
        // protection.
        given:
        def tradeProtectionService = Mock(com.sboxmarket.service.TradeProtectionService)
        service.tradeProtectionService = tradeProtectionService
        def t = tradeIn('PENDING_SELLER_ACCEPT')
        def buyerWallet = new Wallet(id: 500L, balance: BigDecimal.ZERO, currency: 'USD')
        def listing = new Listing(id: 100L, status: 'SOLD', buyerUserId: 10L, sellerUserId: 20L)
        tradeRepository.findById(_) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade trade -> trade }
        walletRepository.findById(500L) >> Optional.of(buyerWallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { Listing l -> l }
        // Locked decision: ACTIVE → flipped to EXPIRED inline → returns
        // false → cancel runs its normal refundBuyer.
        tradeProtectionService.lockAndExpireIfActiveOrReportClaimed(1L) >> false

        when: 'the buyer cancels their own still-ACTIVE protected trade'
        service.cancel(10L, 1L, 'changed my mind')

        then: 'the escrow refund still runs — buyer is made whole exactly once'
        t.state == 'CANCELLED'
        buyerWallet.balance == new BigDecimal('50.00')
        1 * transactionRepository.save({ Transaction tx -> tx.type == 'REFUND' && tx.amount == new BigDecimal('50.00') })
        1 * tradeProtectionService.expire(1L)
    }

    def "cancel of an unprotected trade still refunds the buyer when the protection service is wired"() {
        // Regression guard — wiring tradeProtectionService must not
        // change behaviour for the (overwhelming majority) unprotected
        // trades. lockAndExpireIfActiveOrReportClaimed returns false for
        // a missing protection row → escrow refund runs.
        given:
        def tradeProtectionService = Mock(com.sboxmarket.service.TradeProtectionService)
        service.tradeProtectionService = tradeProtectionService
        def t = tradeIn('PENDING_SELLER_SEND')
        def buyerWallet = new Wallet(id: 500L, balance: BigDecimal.ZERO, currency: 'USD')
        def listing = new Listing(id: 100L, status: 'SOLD', buyerUserId: 10L, sellerUserId: 20L)
        tradeRepository.findById(_) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade trade -> trade }
        walletRepository.findById(500L) >> Optional.of(buyerWallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { Listing l -> l }
        tradeProtectionService.lockAndExpireIfActiveOrReportClaimed(1L) >> false  // unprotected

        when:
        service.cancel(10L, 1L, 'changed my mind')

        then:
        t.state == 'CANCELLED'
        buyerWallet.balance == new BigDecimal('50.00')
        1 * transactionRepository.save({ Transaction tx -> tx.type == 'REFUND' })
    }

    def "cancel falls through to the escrow refund when the protection lookup throws (fail-safe)"() {
        // Best-effort lookup — a protection-service hiccup must never
        // block the cancel. On a lookup error we conservatively refund:
        // a missed refund on a genuinely unrefunded buyer is the worse
        // failure (a missed skip is recoverable by staff claw-back).
        given:
        def tradeProtectionService = Mock(com.sboxmarket.service.TradeProtectionService)
        service.tradeProtectionService = tradeProtectionService
        def t = tradeIn('PENDING_SELLER_SEND')
        def buyerWallet = new Wallet(id: 500L, balance: BigDecimal.ZERO, currency: 'USD')
        def listing = new Listing(id: 100L, status: 'SOLD', buyerUserId: 10L, sellerUserId: 20L)
        tradeRepository.findById(_) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade trade -> trade }
        walletRepository.findById(500L) >> Optional.of(buyerWallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { Listing l -> l }
        tradeProtectionService.lockAndExpireIfActiveOrReportClaimed(1L) >> { throw new RuntimeException('protection db down') }

        when:
        service.cancel(10L, 1L, 'changed my mind')

        then: 'cancel still completes and refunds — the lookup failure is swallowed'
        noExceptionThrown()
        t.state == 'CANCELLED'
        buyerWallet.balance == new BigDecimal('50.00')
        1 * transactionRepository.save({ Transaction tx -> tx.type == 'REFUND' })
    }

    def "cancel routes the protection check through the locked arbiter (cancel × autoClaim race fix)"() {
        // Reproducer-shaped guard for the cancel × dispute double-payout
        // race (batch 661). Pre-fix the path was:
        //   tradeProtectionService.findForTrade(t.id)   // UNLOCKED read
        // which could see status=ACTIVE while a concurrent autoClaim's
        // REQUIRES_NEW commit was still in flight. Then refundBuyer
        // credited the wallet, autoClaim committed, and the buyer was
        // paid +price twice for one trade.
        //
        // Post-fix the path goes through lockAndExpireIfActiveOrReportClaimed,
        // which acquires the pessimistic row lock in cancel's outer tx so
        // the contending autoClaim BLOCKS — and when autoClaim eventually
        // runs it re-reads the row under the same lock and sees the EXPIRED
        // marker cancel laid down, so it bails via its idempotency gate.
        //
        // This test pins the public surface: cancel MUST consult the locked
        // arbiter, and MUST NOT consult the unlocked findForTrade for the
        // refund decision. A regression that re-introduces findForTrade
        // re-opens the double-payout window.
        given:
        def tradeProtectionService = Mock(com.sboxmarket.service.TradeProtectionService)
        service.tradeProtectionService = tradeProtectionService
        def t = tradeIn('PENDING_SELLER_SEND')
        def buyerWallet = new Wallet(id: 500L, balance: BigDecimal.ZERO, currency: 'USD')
        def listing = new Listing(id: 100L, status: 'SOLD', buyerUserId: 10L, sellerUserId: 20L)
        tradeRepository.findById(_) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade trade -> trade }
        walletRepository.findById(500L) >> Optional.of(buyerWallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { Listing l -> l }

        when:
        service.cancel(10L, 1L, 'changed my mind')

        then: 'the locked arbiter is consulted exactly once for the refund decision'
        1 * tradeProtectionService.lockAndExpireIfActiveOrReportClaimed(1L) >> false

        and: 'the racy unlocked finder is never used on the cancel path'
        0 * tradeProtectionService.findForTrade(_)
    }

    def "cancel skips the escrow refund for a CLAIMED protection even on the participant (seller-cancel) path"() {
        // The double-payout skip is keyed on the protection state, not
        // the actor — a seller cancelling a disputed-then-claimed trade
        // must also not trigger a second buyer payout. (A seller can't
        // cancel a DISPUTED trade, but can cancel earlier states; this
        // pins the skip to protection state regardless of who cancels.)
        given:
        def tradeProtectionService = Mock(com.sboxmarket.service.TradeProtectionService)
        service.tradeProtectionService = tradeProtectionService
        def t = tradeIn('PENDING_SELLER_SEND')
        def buyerWallet = new Wallet(id: 500L, balance: new BigDecimal('50.00'), currency: 'USD')
        def listing = new Listing(id: 100L, status: 'SOLD', buyerUserId: 10L, sellerUserId: 20L)
        tradeRepository.findById(_) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade trade -> trade }
        walletRepository.findById(500L) >> Optional.of(buyerWallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { Listing l -> l }
        tradeProtectionService.lockAndExpireIfActiveOrReportClaimed(1L) >> true   // CLAIMED at lock-acquire

        when: 'the seller cancels a trade whose protection already paid out'
        service.cancel(20L, 1L, 'seller cancels')

        then: 'no second refund — the buyer keeps only the protection claim payout'
        t.state == 'CANCELLED'
        buyerWallet.balance == new BigDecimal('50.00')
        0 * transactionRepository.save({ Transaction tx -> tx.type == 'REFUND' })
    }

    def "adminRelease of a DISPUTED protected trade whose protection CLAIMED reverses the claim (release-path double-payout fix)"() {
        // A protected buyer disputed → autoClaim already paid them $50
        // and flipped the protection to CLAIMED. Staff now force-release
        // the trade, ruling the seller actually delivered. Crediting the
        // seller while leaving the buyer's claim standing would double-
        // pay the buyer (keeps the item AND the refund) — release() must
        // REVERSE the CLAIMED protection, not expire() it.
        //
        // Wave 110: the resolve path is now the LOCKED
        // lockAndExpireIfActiveOrReportClaimed arbiter (the same one
        // cancel() uses), so a concurrent autoClaim cannot squeeze a
        // payout between this check and the seller credit.
        given:
        def tradeProtectionService = Mock(com.sboxmarket.service.TradeProtectionService)
        service.tradeProtectionService = tradeProtectionService
        def t = tradeIn('DISPUTED')
        def sellerWallet = new Wallet(id: 600L, balance: BigDecimal.ZERO, currency: 'USD')
        tradeRepository.findById(_) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade trade -> trade }
        walletRepository.findById(600L) >> Optional.of(sellerWallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        adminAuthorization.requireAdmin(999L) >> {}
        // Locked arbiter reports CLAIMED → caller must reverseClaim.
        // `_` arg matcher because Spock argument-matches on exact type
        // (a Long call won't match an Integer stub) and TradeService
        // hands the trade id in via `t.id` whose boxing depends on
        // construction path.
        tradeProtectionService.lockAndExpireIfActiveOrReportClaimed(_) >> true

        when: 'staff force-release the disputed (already protection-paid) trade'
        service.adminRelease(999L, 1L, 'CSR ruling: seller delivered, buyer dispute rejected')

        then: 'the seller is paid and the trade verifies'
        t.state == 'VERIFIED'
        sellerWallet.balance > BigDecimal.ZERO

        and: 'the CLAIMED protection is REVERSED — not expired — to reclaim the buyer payout'
        1 * tradeProtectionService.reverseClaim(_, _)
        0 * tradeProtectionService.expire(_)
    }

    def "release of a protected trade with ACTIVE protection still expires the cover (no regression)"() {
        // The release-path protection branch must not regress ordinary
        // completion: a buyerConfirm on a still-ACTIVE protected trade
        // lapses the cover, never reverseClaim().
        //
        // Wave 110: the expire happens INLINE inside
        // lockAndExpireIfActiveOrReportClaimed (under the row lock so a
        // concurrent autoClaim sees EXPIRED on its re-read and bails),
        // not via a separate expire() call. Asserts neither the legacy
        // expire() nor reverseClaim() fires — the inline path covers
        // the ACTIVE case.
        given:
        def tradeProtectionService = Mock(com.sboxmarket.service.TradeProtectionService)
        service.tradeProtectionService = tradeProtectionService
        def t = tradeIn('PENDING_BUYER_CONFIRM')
        def sellerWallet = new Wallet(id: 600L, balance: BigDecimal.ZERO, currency: 'USD')
        tradeRepository.findById(_) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade trade -> trade }
        walletRepository.findById(600L) >> Optional.of(sellerWallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        banGuard.assertNotBanned(10L) >> {}
        // Locked arbiter consumed the ACTIVE cover inline → false.
        tradeProtectionService.lockAndExpireIfActiveOrReportClaimed(_) >> false

        when:
        service.buyerConfirm(10L, 1L)

        then: 'trade verifies and neither the post-lock expire nor reverseClaim fires'
        t.state == 'VERIFIED'
        0 * tradeProtectionService.expire(_)
        0 * tradeProtectionService.reverseClaim(_, _)
    }

    def "release × concurrent autoClaim race — release on a protected trade locks the protection row so a parallel dispute cannot double-pay the buyer (wave 110)"() {
        // The wave-105 cancel × autoClaim fix sealed the SYMMETRIC race
        // for the cancel path but left the release path open. Repro:
        //
        //   1. Trade in PENDING_BUYER_CONFIRM, protection ACTIVE.
        //   2. Thread B files dispute (REQUIRES_NEW autoClaim acquires the
        //      protection row lock, credits the buyer the full cover,
        //      flips ACTIVE → CLAIMED, COMMITS — durable buyer payout).
        //   3. Thread A's release runs concurrently. Pre-fix it did an
        //      UNLOCKED `findForTrade` read. If that read saw ACTIVE (a
        //      live race window before autoClaim's commit was visible to
        //      Thread A), release took the `expire()` branch. expire()
        //      then did its own unlocked read in REQUIRES_NEW — by the
        //      time it ran, autoClaim had committed CLAIMED, so expire's
        //      `status != ACTIVE` gate bailed silently. NO reverseClaim
        //      ever ran. Seller credited (price - fee) + buyer keeps the
        //      cover payout = full-item-price platform loss per race.
        //
        // The fix routes release's protection resolution through the
        // SAME locked arbiter cancel uses
        // (`lockAndExpireIfActiveOrReportClaimed`). When the arbiter
        // reports CLAIMED (Thread B's autoClaim already paid), release
        // calls reverseClaim to claw back. When it returns false, the
        // ACTIVE cover was consumed inline under the lock so a
        // concurrent autoClaim re-reads EXPIRED on its locked re-read
        // and bails via the existing idempotency gate.
        //
        // This pin exercises the CLAIMED branch — the one that pre-fix
        // silently fell through to the now-no-op `expire()` and left the
        // platform double-paying. With the fix in place, the same
        // arbiter return value triggers `reverseClaim` exactly once, the
        // buyer's wallet is clawed back, and the seller's normal credit
        // stands.
        given:
        def tradeProtectionService = Mock(com.sboxmarket.service.TradeProtectionService)
        service.tradeProtectionService = tradeProtectionService
        def t = tradeIn('PENDING_BUYER_CONFIRM')
        def sellerWallet = new Wallet(id: 600L, balance: BigDecimal.ZERO, currency: 'USD')
        tradeRepository.findById(_) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade trade -> trade }
        walletRepository.findById(600L) >> Optional.of(sellerWallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        banGuard.assertNotBanned(10L) >> {}

        and: 'the protection row was concurrently CLAIMED by a parallel dispute'
        tradeProtectionService.lockAndExpireIfActiveOrReportClaimed(1L) >> true

        when: 'the buyer confirms (or the sweeper auto-releases) the trade'
        service.buyerConfirm(10L, 1L)

        then: 'the trade still verifies and the seller is still credited from escrow'
        t.state == 'VERIFIED'
        sellerWallet.balance > BigDecimal.ZERO

        and: 'the standing CLAIMED protection payout is REVERSED — no double payout'
        1 * tradeProtectionService.reverseClaim(1L, _)

        and: 'release goes through the LOCKED arbiter, not the racy unlocked findForTrade read'
        0 * tradeProtectionService.findForTrade(_)
        0 * tradeProtectionService.expire(_)
    }

    def "release × concurrent autoClaim race — when the arbiter reports false (ACTIVE consumed inline), release does NOT separately expire or reverse (wave 110)"() {
        // The false return value from lockAndExpireIfActiveOrReportClaimed
        // means EITHER the trade was unprotected (null protection) OR the
        // ACTIVE cover was just consumed ACTIVE → EXPIRED inline under the
        // pessimistic row lock. Either way release() must NOT call expire()
        // again (no-op but extra round trip) and must NOT call reverseClaim
        // (would clobber a never-paid protection row). This pins that the
        // wave-110 patch's false-branch is a clean no-op rather than
        // re-falling through to the legacy expire() call (which would mask
        // a future regression of the locked semantics).
        given:
        def tradeProtectionService = Mock(com.sboxmarket.service.TradeProtectionService)
        service.tradeProtectionService = tradeProtectionService
        def t = tradeIn('PENDING_BUYER_CONFIRM')
        def sellerWallet = new Wallet(id: 600L, balance: BigDecimal.ZERO, currency: 'USD')
        tradeRepository.findById(_) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade trade -> trade }
        walletRepository.findById(600L) >> Optional.of(sellerWallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        banGuard.assertNotBanned(10L) >> {}
        tradeProtectionService.lockAndExpireIfActiveOrReportClaimed(1L) >> false

        when:
        service.buyerConfirm(10L, 1L)

        then: 'release completes normally with no follow-on protection calls'
        t.state == 'VERIFIED'
        sellerWallet.balance > BigDecimal.ZERO
        0 * tradeProtectionService.expire(_)
        0 * tradeProtectionService.reverseClaim(_, _)
    }

    // ── 404 wrap ──────────────────────────────────────────────────

    def "get throws NotFoundException for unknown trade id"() {
        given:
        tradeRepository.findById(_) >> Optional.empty()

        when:
        service.get(999L)

        then:
        thrown(NotFoundException)
    }

    // ── ban checks (bugs #59-60) ────────────────────────────────

    def "dispute rejects banned users (bug #59)"() {
        given:
        banGuard.assertNotBanned(10L) >> { throw new ForbiddenException("banned") }
        tradeRepository.findById(_) >> Optional.of(tradeIn('PENDING_SELLER_ACCEPT'))

        when:
        service.dispute(10L, 1L, 'reason')

        then:
        thrown(ForbiddenException)
    }

    def "cancel rejects banned participants (bug #60)"() {
        given:
        def t = tradeIn('PENDING_SELLER_SEND')
        tradeRepository.findById(_) >> Optional.of(t)
        banGuard.assertNotBanned(10L) >> { throw new ForbiddenException("banned") }

        when:
        service.cancel(10L, 1L, 'reason')

        then:
        thrown(ForbiddenException)
    }

    def "cancel by admin does NOT check ban on the admin actor (bug #60)"() {
        given:
        def t = tradeIn('PENDING_SELLER_SEND')
        def buyerWallet = new Wallet(id: 500L, balance: new BigDecimal("0.00"), currency: 'USD')
        tradeRepository.findById(_) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade x -> x }
        walletRepository.findById(500L) >> Optional.of(buyerWallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }

        when:
        service.cancel(999L, 1L, 'admin override')

        then:
        // Admin path — should NOT call banGuard for the actor, only adminAuthorization
        1 * adminAuthorization.requireAdmin(999L)
        0 * banGuard.assertNotBanned(999L)
        t.state == 'CANCELLED'
    }

    // ── audit subjects (bug #61) ──────────────────────────────────

    def "dispute logs the counterparty as audit subject (bug #61)"() {
        given:
        def t = tradeIn('PENDING_SELLER_ACCEPT')
        tradeRepository.findById(1L) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade x -> x }
        def auditService = Mock(com.sboxmarket.service.AuditService)
        service.auditService = auditService

        when:
        service.dispute(10L, 1L, 'seller ghosted')

        then:
        // Buyer (10) disputes → subject should be seller (20)
        1 * auditService.log('TRADE_DISPUTED', 10L, 20L, 1L, _)
    }

    def "cancel logs the counterparty as audit subject (bug #61)"() {
        given:
        def t = tradeIn('PENDING_SELLER_SEND')
        def buyerWallet = new Wallet(id: 500L, balance: new BigDecimal("0.00"), currency: 'USD')
        tradeRepository.findById(1L) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade x -> x }
        walletRepository.findById(500L) >> Optional.of(buyerWallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        def auditService = Mock(com.sboxmarket.service.AuditService)
        service.auditService = auditService

        when:
        service.cancel(20L, 1L, 'seller cancels')

        then:
        // Seller (20) cancels → subject should be buyer (10)
        1 * auditService.log('TRADE_CANCELLED', 20L, 10L, 1L, _)
    }

    // ── price validation (bug #63) ────────────────────────────────

    def "open rejects zero price (bug #63)"() {
        when:
        service.open(100L, 1L, 'Hat', 10L, 500L, 20L, 600L, BigDecimal.ZERO)

        then:
        thrown(BadRequestException)
    }

    def "open rejects negative price (bug #63)"() {
        when:
        service.open(100L, 1L, 'Hat', 10L, 500L, 20L, 600L, new BigDecimal("-5"))

        then:
        thrown(BadRequestException)
    }

    def "open rejects price over 100k cap (bug #63)"() {
        when:
        service.open(100L, 1L, 'Hat', 10L, 500L, 20L, 600L, new BigDecimal("100001"))

        then:
        thrown(BadRequestException)
    }

    // ── sweeper + banned seller (bug #65) ─────────────────────────

    def "sweepPendingConfirm auto-cancels trades where seller is banned (bug #65)"() {
        given:
        def stale = tradeIn('PENDING_BUYER_CONFIRM', [id: 1L])
        def buyerWallet = new Wallet(id: 500L, balance: new BigDecimal("0.00"), currency: 'USD')
        tradeRepository.findPendingConfirmOlderThan(_) >> [stale]
        banGuard.isBanned(20L) >> true
        walletRepository.findById(500L) >> Optional.of(buyerWallet)
        tradeRepository.save(_) >> { Trade t -> t }
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }

        when:
        service.sweepPendingConfirm()

        then:
        stale.state == 'CANCELLED'
        buyerWallet.balance == new BigDecimal("50.00")
        1 * transactionRepository.save({ Transaction tx -> tx.type == 'REFUND' })
    }

    def "sweepPendingConfirm banned-seller branch expires the trade protection (no double payout)"() {
        // Regression for the protection-expire call inside
        // autoCancelBannedSellerTrade. The banned-seller branch refunds
        // the buyer via refundBuyer(); without expire(), an ACTIVE
        // protection on the cancelled trade would still be claimable
        // and the buyer could double-dip — once from the escrow refund
        // here, once from a later claim against the same protection.
        // The release() branch already expires via its own path, so
        // this guard only matters for the banned-seller fork.
        given:
        def tradeProtectionService = Mock(com.sboxmarket.service.TradeProtectionService)
        service.tradeProtectionService = tradeProtectionService
        def stale = tradeIn('PENDING_BUYER_CONFIRM', [id: 1L])
        def buyerWallet = new Wallet(id: 500L, balance: new BigDecimal("0.00"), currency: 'USD')
        tradeRepository.findPendingConfirmOlderThan(_) >> [stale]
        banGuard.isBanned(20L) >> true
        walletRepository.findById(500L) >> Optional.of(buyerWallet)
        tradeRepository.save(_) >> { Trade t -> t }
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }

        when:
        service.sweepPendingConfirm()

        then:
        // Refund landed and trade closed — confirms we took the banned
        // branch, not the release branch.
        stale.state == 'CANCELLED'
        buyerWallet.balance == new BigDecimal("50.00")
        // Critical: the protection cover is consumed via the LOCKED arbiter
        // (lockAndExpireIfActiveOrReportClaimed, batch 1083) — under the row
        // lock it flips ACTIVE→EXPIRED so a concurrent dispute autoClaim can't
        // double-pay the buyer, and the cover can't be claimed against the
        // now-cancelled trade. Replaces the old unconditional expire(), which
        // left the sweeper-vs-autoClaim double-refund race open.
        1 * tradeProtectionService.lockAndExpireIfActiveOrReportClaimed(1L) >> false
    }

    def "sweepPendingConfirm banned-seller branch does NOT double-refund when protection autoClaim already paid (batch 1083)"() {
        // The bug the trade-lifecycle audit found: autoCancelBannedSellerTrade
        // (and its stale-seller twin) called UNCONDITIONAL refundBuyer + a
        // no-op expire(). If a concurrent dispute's autoClaim had already
        // credited the buyer (protection CLAIMED), the sweeper credited the
        // escrow AGAIN — a double-refund / money created (the Trade @Version
        // does NOT cover autoClaim's separate committed REQUIRES_NEW credit).
        // The wave-105 arbiter fix was applied to cancel() but NOT these
        // sweepers. The arbiter (returns true on a CLAIMED cover) must now
        // make the sweeper SKIP its escrow refund.
        given:
        def tradeProtectionService = Mock(com.sboxmarket.service.TradeProtectionService)
        service.tradeProtectionService = tradeProtectionService
        def stale = tradeIn('PENDING_BUYER_CONFIRM', [id: 1L])
        def buyerWallet = new Wallet(id: 500L, balance: new BigDecimal("0.00"), currency: 'USD')
        tradeRepository.findPendingConfirmOlderThan(_) >> [stale]
        banGuard.isBanned(20L) >> true
        walletRepository.findById(500L) >> Optional.of(buyerWallet)
        tradeRepository.save(_) >> { Trade t -> t }
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        // autoClaim already paid the buyer → arbiter reports CLAIMED.
        tradeProtectionService.lockAndExpireIfActiveOrReportClaimed(1L) >> true

        when:
        service.sweepPendingConfirm()

        then: 'trade closes but the buyer is NOT refunded a second time'
        stale.state == 'CANCELLED'
        buyerWallet.balance == new BigDecimal("0.00")   // no second escrow credit
        0 * transactionRepository.save({ Transaction tx -> tx.type == 'REFUND' })
    }

    def "sweepPendingConfirm keeps releasing healthy trades when the banned-seller branch throws (per-trade isolation)"() {
        // Regression for the per-trade transaction isolation added to
        // the banned-seller path. The outer sweep loops with an
        // unannotated `each`; the banned branch runs through the
        // @Transactional helper. A failure on the banned trade's
        // wallet write or protection expire must not abort the rest
        // of the batch — the healthy sibling trade still auto-releases.
        given:
        def bannedStale  = tradeIn('PENDING_BUYER_CONFIRM', [id: 1L, seller: 20L])
        def healthyStale = tradeIn('PENDING_BUYER_CONFIRM', [id: 2L, seller: 30L, sellerWallet: 700L])
        def healthySellerWallet = new Wallet(id: 700L, balance: new BigDecimal("0"))
        tradeRepository.findPendingConfirmOlderThan(_) >> [bannedStale, healthyStale]
        banGuard.isBanned(20L) >> true
        banGuard.isBanned(30L) >> false
        // Banned trade's refund lookup blows up — must not poison the loop.
        walletRepository.findById(500L) >> { throw new RuntimeException("wallet DB blip") }
        walletRepository.findById(700L) >> Optional.of(healthySellerWallet)
        tradeRepository.save(_) >> { Trade t -> t }
        walletRepository.save(_) >> { Wallet w -> w }

        when:
        service.sweepPendingConfirm()

        then:
        // Healthy trade still settled as VERIFIED via the release path
        // — the banned-trade failure was contained.
        healthyStale.state == 'VERIFIED'
        noExceptionThrown()
    }

    // ── sweepPendingConfirm (auto-release) ────────────────────────

    def "sweepPendingConfirm is a no-op when the repository returns empty"() {
        given:
        tradeRepository.findPendingConfirmOlderThan(_) >> []

        when:
        service.sweepPendingConfirm()

        then:
        0 * tradeRepository.save(_)
        0 * walletRepository.save(_)
    }

    def "sweepPendingConfirm delegates the cutoff window to the indexed query (bug #27)"() {
        given:
        Long captured = null
        tradeRepository.findPendingConfirmOlderThan(_) >> { args ->
            captured = args[0] as Long
            []
        }

        when:
        service.sweepPendingConfirm()

        then:
        captured != null
        // Cutoff must be roughly now - 8 days. 5-second slop for slow CI.
        def expected = System.currentTimeMillis() - (8L * 24L * 60L * 60L * 1000L)
        Math.abs(captured - expected) < 5000L
    }

    def "sweepPendingConfirm auto-releases every stale trade the query returned"() {
        given:
        def stale1 = tradeIn('PENDING_BUYER_CONFIRM', [id: 1L])
        def stale2 = tradeIn('PENDING_BUYER_CONFIRM', [id: 2L])
        def sellerWallet = new Wallet(id: 600L, balance: new BigDecimal("0"))
        tradeRepository.findPendingConfirmOlderThan(_) >> [stale1, stale2]
        walletRepository.findById(600L) >> Optional.of(sellerWallet)
        tradeRepository.save(_) >> { Trade t -> t }
        walletRepository.save(_) >> { Wallet w -> w }

        when:
        service.sweepPendingConfirm()

        then:
        stale1.state == 'VERIFIED'
        stale2.state == 'VERIFIED'
        2 * transactionRepository.save({ Transaction tx -> tx.type == 'SALE' })
    }

    def "sweepPendingConfirm emails the buyer on auto-release (batch 601)"() {
        given:
        def stale = tradeIn('PENDING_BUYER_CONFIRM', [id: 5L])
        def sellerWallet = new Wallet(id: 600L, balance: new BigDecimal("0"))
        def buyer = new com.sboxmarket.model.SteamUser(id: 10L, steamId64: '111',
            displayName: 'Alice', email: 'alice@example.com',
            emailVerified: true, emailNotificationsEnabled: true)
        tradeRepository.findPendingConfirmOlderThan(_) >> [stale]
        walletRepository.findById(600L) >> Optional.of(sellerWallet)
        steamUserRepository.findById(10L) >> Optional.of(buyer)
        tradeRepository.save(_) >> { Trade t -> t }
        walletRepository.save(_) >> { Wallet w -> w }
        emailService.canSendTo(buyer, 'TRADES') >> true

        when:
        service.sweepPendingConfirm()

        then:
        1 * emailService.sendTradeAutoReleased('alice@example.com', 'Alice',
            'Wizard Hat', 5L)
    }

    def "buyerConfirm does NOT fire the auto-release email — manual path (batch 601)"() {
        given:
        def t = tradeIn('PENDING_BUYER_CONFIRM', [id: 6L])
        def sellerWallet = new Wallet(id: 600L, balance: new BigDecimal("0"))
        tradeRepository.findById(6L) >> Optional.of(t)
        walletRepository.findById(600L) >> Optional.of(sellerWallet)
        tradeRepository.save(_) >> { Trade x -> x }
        walletRepository.save(_) >> { Wallet w -> w }

        when:
        service.buyerConfirm(10L, 6L)

        then:
        // Manual confirm path must never call sendTradeAutoReleased —
        // the buyer is actively on the site confirming, so an email
        // would be noise.
        0 * emailService.sendTradeAutoReleased(*_)
    }

    def "sweepPendingConfirm keeps going even if one release throws"() {
        given:
        def good = tradeIn('PENDING_BUYER_CONFIRM', [id: 1L])
        def bad  = tradeIn('PENDING_BUYER_CONFIRM', [id: 2L])
        def sellerWallet = new Wallet(id: 600L, balance: new BigDecimal("0"))
        tradeRepository.findPendingConfirmOlderThan(_) >> [bad, good]
        walletRepository.findById(600L) >>> [
            { throw new RuntimeException("boom") } as Object,
            Optional.of(sellerWallet)
        ]
        tradeRepository.save(_) >> { Trade t -> t }
        walletRepository.save(_) >> { Wallet w -> w }

        when:
        service.sweepPendingConfirm()

        then:
        // The second trade still gets processed despite the first blowing up.
        good.state == 'VERIFIED'
        noExceptionThrown()
    }

    // ── sweepStaleSellerResponse (seller-timeout refund) ──────────

    def "sweepStaleSellerResponse refunds the buyer and flips to CANCELLED for stale seller-pending trades"() {
        given:
        def buyerWallet = new Wallet(id: 500L, balance: new BigDecimal("0.00"))
        def stale = tradeIn('PENDING_SELLER_ACCEPT', [id: 77L])
        tradeRepository.findStaleSellerPending(_) >> [stale]
        walletRepository.findById(500L) >> Optional.of(buyerWallet)
        tradeRepository.save(_) >> { Trade t -> t }
        walletRepository.save(_) >> { Wallet w -> w }
        textSanitizer.medium(_) >> { String s -> s ?: '' }

        when:
        service.sweepStaleSellerResponse()

        then:
        stale.state == 'CANCELLED'
        // Buyer wallet is refunded by trade.price ($50 default).
        buyerWallet.balance == new BigDecimal("50.00")
        // Both sides get a TRADE_CANCELLED notification.
        1 * notificationService.safePush(10L, 'TRADE_CANCELLED', _, _, 77L, '/profile?tab=trades')
        1 * notificationService.safePush(20L, 'TRADE_CANCELLED', _, _, 77L, '/profile?tab=trades')
    }

    def "sweepStaleSellerResponse uses the sellerResponseDays cutoff"() {
        given:
        Long captured = null
        tradeRepository.findStaleSellerPending(_) >> { args -> captured = args[0] as Long; [] }

        when:
        service.sweepStaleSellerResponse()

        then:
        captured != null
        // 3 days (our default) behind now, ~5s slop for CI.
        def expected = System.currentTimeMillis() - (3L * 24L * 60L * 60L * 1000L)
        Math.abs(captured - expected) < 5000L
    }

    def "sweepStaleSellerResponse is a no-op when the repository returns empty"() {
        given:
        tradeRepository.findStaleSellerPending(_) >> []

        when:
        service.sweepStaleSellerResponse()

        then:
        0 * walletRepository.save(_)
        0 * tradeRepository.save(_)
    }

    def "sweep helpers run each per-trade body in its own REQUIRES_NEW transaction (self-invocation isolation)"() {
        // Regression: the per-trade @Transactional helpers
        // (autoCancelStaleSellerTrade, autoCancelBannedSellerTrade, and
        // release-from-sweep) were called via `this.helper(...)` from the
        // outer @Scheduled sweeps. Spring's CGLIB proxy is bypassed on
        // self-invocation, so the @Transactional annotation was a no-op:
        // a refundBuyer wallet save that committed in its own auto-commit
        // tx followed by a transitionTo failure (optimistic-lock from a
        // concurrent dispute) left the buyer credited but the trade still
        // PENDING_SELLER_*; the next sweep tick re-found and DOUBLE-REFUNDED.
        //
        // Fix wraps each per-trade call in runInIsolatedTx, which spins a
        // fresh REQUIRES_NEW TransactionTemplate when a PlatformTransactionManager
        // is wired. This spec injects a mock manager and asserts the sweep
        // opens exactly one REQUIRES_NEW transaction per candidate trade —
        // the broken (pre-fix) code never touched the manager at all.
        given:
        def txStatus  = Mock(org.springframework.transaction.TransactionStatus)
        def txManager = Mock(org.springframework.transaction.PlatformTransactionManager) {
            getTransaction(_) >> txStatus
        }
        def localSvc = new com.sboxmarket.service.TradeService(
            tradeRepository       : tradeRepository,
            walletRepository      : walletRepository,
            transactionRepository : transactionRepository,
            listingRepository     : listingRepository,
            notificationService   : notificationService,
            banGuard              : banGuard,
            adminAuthorization    : adminAuthorization,
            steamUserRepository   : steamUserRepository,
            textSanitizer         : textSanitizer,
            emailService          : emailService,
            transactionManager    : txManager,
            autoReleaseDays       : 8L,
            sellerResponseDays    : 3L
        )
        def buyerWallet = new Wallet(id: 500L, balance: new BigDecimal("0.00"))
        def t1 = tradeIn('PENDING_SELLER_ACCEPT', [id: 71L])
        def t2 = tradeIn('PENDING_SELLER_SEND',   [id: 72L])
        tradeRepository.findStaleSellerPending(_) >> [t1, t2]
        walletRepository.findById(500L) >> Optional.of(buyerWallet)
        tradeRepository.save(_) >> { Trade t -> t }
        walletRepository.save(_) >> { Wallet w -> w }
        textSanitizer.medium(_) >> { String s -> s ?: '' }

        when:
        localSvc.sweepStaleSellerResponse()

        then: "the sweep commits exactly one transaction per candidate trade"
        2 * txManager.commit(txStatus)
        and: "the wrapped body still mutates state through the per-trade tx"
        t1.state == 'CANCELLED'
        t2.state == 'CANCELLED'
    }

    def "sweep helpers fall back to inline execution when no PlatformTransactionManager is wired (unit-test path)"() {
        // The Spock unit tests in this file build TradeService via the
        // property-map constructor — no Spring context, no
        // PlatformTransactionManager, no active synchronization. The
        // runInIsolatedTx helper must fall back to running the work
        // inline so the existing sweep specs (which all rely on real
        // state mutation reaching the test's mocked repositories)
        // continue to pass. Without this fallback, every sweep test
        // becomes a no-op the moment we introduce the helper.
        given:
        def buyerWallet = new Wallet(id: 500L, balance: new BigDecimal("0.00"))
        def stale = tradeIn('PENDING_SELLER_ACCEPT', [id: 99L])
        tradeRepository.findStaleSellerPending(_) >> [stale]
        walletRepository.findById(500L) >> Optional.of(buyerWallet)
        tradeRepository.save(_) >> { Trade t -> t }
        walletRepository.save(_) >> { Wallet w -> w }
        textSanitizer.medium(_) >> { String s -> s ?: '' }

        when:
        service.sweepStaleSellerResponse()

        then: "state mutation still reaches the test's mocked repositories"
        stale.state == 'CANCELLED'
        buyerWallet.balance == new BigDecimal("50.00")
    }

    // ── expiresAt decoration (Visual Manual §29) ──────────────────

    def "listForUserWithCounterparty decorates PENDING_SELLER_ACCEPT with expiresAt = updatedAt + sellerResponseDays"() {
        given:
        def t = tradeIn('PENDING_SELLER_ACCEPT', [id: 1L])
        t.updatedAt = 1_700_000_000_000L
        tradeRepository.findByParticipantPaged(20L, _) >> [t]

        when:
        def rows = service.listForUserWithCounterparty(20L)

        then:
        rows.size() == 1
        // 3 days past updatedAt, per the service's default sellerResponseDays config.
        rows[0].expiresAt == 1_700_000_000_000L + (3L * 24L * 60L * 60L * 1000L)
        rows[0].state == 'PENDING_SELLER_ACCEPT'
    }

    def "listForUserWithCounterparty decorates PENDING_BUYER_CONFIRM with expiresAt = updatedAt + autoReleaseDays"() {
        given:
        def t = tradeIn('PENDING_BUYER_CONFIRM', [id: 2L])
        t.updatedAt = 1_700_000_000_000L
        tradeRepository.findByParticipantPaged(10L, _) >> [t]

        when:
        def rows = service.listForUserWithCounterparty(10L)

        then:
        // 8 days past updatedAt, per the service's default autoReleaseDays config.
        rows[0].expiresAt == 1_700_000_000_000L + (8L * 24L * 60L * 60L * 1000L)
    }

    def "listForUserWithCounterparty decorates each row with unreadCount via the bulk query"() {
        given:
        // Wire the optional deps the existing spec doesn't construct
        // by default — the unread-count branch only fires when both
        // tradeMessageRepository AND steamUserRepository are present.
        def msgRepo = Mock(com.sboxmarket.repository.TradeMessageRepository)
        def userRepo = Mock(com.sboxmarket.repository.SteamUserRepository)
        service.tradeMessageRepository = msgRepo
        service.steamUserRepository    = userRepo
        userRepo.findAllById(_) >> []
        def t1 = tradeIn('PENDING_BUYER_CONFIRM', [id: 1L])
        def t2 = tradeIn('PENDING_SELLER_SEND',   [id: 2L])
        tradeRepository.findByParticipantPaged(10L, _) >> [t1, t2]
        // Use `_` for the args because the order/typing of the Long
        // list members can vary across Groovy boxing paths and Spock's
        // strict-equality matcher rejects List<Object> vs List<Long>
        // mismatches that aren't visible at the source level.
        msgRepo.countUnreadBulk(_, 10L) >> [
            [1L, 3L] as Object[]
            // Trade #2 has zero unread — repo returns nothing for it.
        ]

        when:
        def rows = service.listForUserWithCounterparty(10L)

        then:
        rows.find { it.id == 1L }.unreadCount == 3L
        rows.find { it.id == 2L }.unreadCount == 0L
    }

    def "listForUserWithCounterparty decorates each row with truncated lastMessage preview"() {
        given:
        def msgRepo = Mock(com.sboxmarket.repository.TradeMessageRepository)
        def userRepo = Mock(com.sboxmarket.repository.SteamUserRepository)
        service.tradeMessageRepository = msgRepo
        service.steamUserRepository    = userRepo
        userRepo.findAllById(_) >> []
        def t1 = tradeIn('PENDING_BUYER_CONFIRM', [id: 1L])
        tradeRepository.findByParticipantPaged(10L, _) >> [t1]
        msgRepo.countUnreadBulk(_, 10L) >> []
        // 100-char body — server truncates to 77 + "…" per the V37
        // batch 283 contract.
        def longBody = 'x' * 100
        msgRepo.findNewestPerTrade(_) >> [
            new com.sboxmarket.model.TradeMessage(
                id: 5L, tradeId: 1L, senderUserId: 20L,
                body: longBody, createdAt: 1_700_000_000_000L)
        ]

        when:
        def rows = service.listForUserWithCounterparty(10L)

        then:
        rows[0].lastMessage != null
        rows[0].lastMessage.body == ('x' * 77) + '…'
        rows[0].lastMessage.senderUserId == 20L
        rows[0].lastMessage.createdAt == 1_700_000_000_000L
    }

    def "listForUserWithCounterparty leaves lastMessage null when no chat history exists"() {
        given:
        def msgRepo = Mock(com.sboxmarket.repository.TradeMessageRepository)
        def userRepo = Mock(com.sboxmarket.repository.SteamUserRepository)
        service.tradeMessageRepository = msgRepo
        service.steamUserRepository    = userRepo
        userRepo.findAllById(_) >> []
        tradeRepository.findByParticipantPaged(10L, _) >> [tradeIn('PENDING_BUYER_CONFIRM')]
        msgRepo.countUnreadBulk(_, 10L) >> []
        msgRepo.findNewestPerTrade(_) >> []

        when:
        def rows = service.listForUserWithCounterparty(10L)

        then:
        rows[0].lastMessage == null
    }

    def "listForUserWithCounterparty unreadCount is 0 when tradeMessageRepository is unwired"() {
        given:
        // Default: service.tradeMessageRepository = null (not assigned).
        service.tradeMessageRepository = null
        tradeRepository.findByParticipantPaged(10L, _) >> [tradeIn('PENDING_BUYER_CONFIRM')]

        when:
        def rows = service.listForUserWithCounterparty(10L)

        then:
        rows.size() == 1
        rows[0].unreadCount == 0L
    }

    def "listForUserWithCounterparty leaves expiresAt null for terminal states"() {
        given:
        def verified  = tradeIn('VERIFIED',  [id: 3L]); verified.updatedAt  = 1_700_000_000_000L
        def cancelled = tradeIn('CANCELLED', [id: 4L]); cancelled.updatedAt = 1_700_000_000_000L
        def disputed  = tradeIn('DISPUTED',  [id: 5L]); disputed.updatedAt  = 1_700_000_000_000L
        tradeRepository.findByParticipantPaged(_, _) >> [verified, cancelled, disputed]

        when:
        def rows = service.listForUserWithCounterparty(20L)

        then:
        rows.every { it.expiresAt == null }
    }

    // ── Review nudge sweeper (batch 284) ────────────────────────────

    def "sweepReviewNudge pushes a follow-up REVIEW_REMINDER and stamps reviewNudgeSentAt"() {
        given:
        def trade = tradeIn('VERIFIED', [id: 7L, buyer: 10L, seller: 20L])
        trade.settledAt = System.currentTimeMillis() - (60L * 3600_000L)  // 60h since clear
        tradeRepository.findReviewNudgeCandidates(_) >> [trade]

        when:
        service.sweepReviewNudge()

        then:
        // The reviewNudgeSentAt stamp is now an atomic conditional UPDATE
        // (claimReviewNudge stamps only WHERE it's still NULL, returns 1 to
        // the winning pod) rather than an in-memory save — so winning the
        // claim is what gates the REVIEW_REMINDER push.
        1 * tradeRepository.claimReviewNudge(7L, _) >> 1
        1 * notificationService.push(10L, 'REVIEW_REMINDER', _, _, 7L,
            { it as String == '/stall/20' || it.toString() == '/stall/20' })
    }

    def "sweepReviewNudge is a silent no-op when nothing past the 48h cutoff"() {
        given:
        tradeRepository.findReviewNudgeCandidates(_) >> []

        when:
        service.sweepReviewNudge()

        then:
        0 * notificationService.push(_, 'REVIEW_REMINDER', _, _, _, _)
        0 * tradeRepository.save(_)
    }

    def "sweepReviewNudge queries with a 48h-ago cutoff"() {
        given:
        Long captured = null
        tradeRepository.findReviewNudgeCandidates(_) >> { args -> captured = args[0] as Long; [] }

        when:
        service.sweepReviewNudge()

        then:
        captured != null
        // 48h behind now, give a 5s slop window for spec scheduling.
        def expected = System.currentTimeMillis() - (48L * 3600_000L)
        Math.abs(captured - expected) < 5000L
    }

    def "sweepReviewNudge isolates per-row claim failures — one bad row never poisons a sibling stamp + push"() {
        // Bug bar: the outer sweep used to be @Transactional. A per-row
        // failure on trade #7 (e.g. an OptimisticLockingFailureException
        // from a concurrent leaveReview / postMessage on the same row)
        // marked the SHARED outer tx rollback-only — the per-row try/catch
        // swallowed the throw, but every reviewNudgeSentAt stamp the sweep
        // had already applied to SIBLING rows silently reverted at commit.
        // Next 24h tick re-fired REVIEW_REMINDER pushes for every nudged
        // buyer in the batch, exactly the duplicate-notification leak the
        // partial-index dedup was meant to prevent. Per-row auto-commit must
        // isolate the bad row from sibling work. The stamp is now the atomic
        // claimReviewNudge conditional UPDATE, so the bad row throws there.
        given:
        def bad  = tradeIn('VERIFIED', [id: 7L, buyer: 10L, seller: 20L])
        def good = tradeIn('VERIFIED', [id: 8L, buyer: 11L, seller: 21L])
        bad.settledAt  = System.currentTimeMillis() - (60L * 3600_000L)
        good.settledAt = System.currentTimeMillis() - (60L * 3600_000L)
        tradeRepository.findReviewNudgeCandidates(_) >> [bad, good]
        // Row #7 claim blows up (concurrent write → OptimisticLockingFailure).
        // Row #8 claim wins (returns 1). Without per-row isolation the sweep
        // would either re-throw out of the .each loop (losing the entire
        // batch) OR commit-rollback the sibling stamp under an outer
        // @Transactional.
        tradeRepository.claimReviewNudge(7L, _) >> {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(
                'Trade', 7L)
        }
        tradeRepository.claimReviewNudge(8L, _) >> 1

        when:
        service.sweepReviewNudge()

        then: "sibling row's claim+push still ran — not aborted by the bad row"
        1 * notificationService.push(11L, 'REVIEW_REMINDER', _, _, 8L, _)
        and: "the bad row never pushed — its claim threw before the push"
        0 * notificationService.push(10L, 'REVIEW_REMINDER', _, _, 7L, _)
        and: "no exception bubbles out — bad row was swallowed in the per-row catch"
        noExceptionThrown()
    }

    def "sweepReviewNudge swallows per-row push exceptions"() {
        given:
        def t1 = tradeIn('VERIFIED', [id: 7L, buyer: 10L])
        def t2 = tradeIn('VERIFIED', [id: 8L, buyer: 11L])
        t1.settledAt = System.currentTimeMillis() - (60L * 3600_000L)
        t2.settledAt = System.currentTimeMillis() - (60L * 3600_000L)
        tradeRepository.findReviewNudgeCandidates(_) >> [t1, t2]
        steamUserRepository.findAllById(_) >> []
        notificationService.push(_, 'REVIEW_REMINDER', _, _, 7L, _) >> { throw new RuntimeException('push down') }

        when:
        service.sweepReviewNudge()

        then:
        // Both attempted.
        1 * notificationService.push(_, 'REVIEW_REMINDER', _, _, 8L, _)
        // Batch 329 stamp ordering: the row is CLAIMED (atomic conditional
        // UPDATE) BEFORE the push, so a failing push doesn't make the
        // sweeper retry the same row every 24h forever. Both rows end
        // claimed now, even though row #7's push threw.
        1 * tradeRepository.claimReviewNudge(7L, _) >> 1
        1 * tradeRepository.claimReviewNudge(8L, _) >> 1
    }

    // ── Trade-chat deep-link path ───────────────────────────────────

    // ── Slow-seller warning sweeper (batch 278) ─────────────────────

    def "sweepSlowSellerWarning pushes TRADE_SLOW_SELLER and stamps slowSellerWarnedAt"() {
        given:
        def trade = tradeIn('PENDING_SELLER_SEND', [id: 7L])
        trade.updatedAt = System.currentTimeMillis() - (30L * 3600_000L)  // 30h idle
        tradeRepository.findSlowSellerUnwarned(_) >> [trade]

        when:
        service.sweepSlowSellerWarning()

        then:
        // The slowSellerWarnedAt stamp is now an atomic conditional UPDATE
        // (claimSlowSellerWarning stamps only WHERE it's still NULL, returns
        // 1 to the winning pod) so winning the claim gates the warning push
        // and guards against the next sweep tick re-pushing.
        1 * tradeRepository.claimSlowSellerWarning(7L, _) >> 1
        // Buyer (10L per the helper default) is the one warned.
        1 * notificationService.push(10L, 'TRADE_SLOW_SELLER', _, _, 7L,
            '/profile?tab=trades&openChat=7')
    }

    def "sweepSlowSellerWarning is a silent no-op when nothing is past the 24h cutoff"() {
        given:
        tradeRepository.findSlowSellerUnwarned(_) >> []

        when:
        service.sweepSlowSellerWarning()

        then:
        0 * notificationService.push(_, 'TRADE_SLOW_SELLER', _, _, _, _)
        0 * tradeRepository.save(_)
    }

    def "sweepSlowSellerWarning queries with a 24h-ago cutoff"() {
        given:
        Long captured = null
        tradeRepository.findSlowSellerUnwarned(_) >> { args -> captured = args[0] as Long; [] }

        when:
        service.sweepSlowSellerWarning()

        then:
        captured != null
        // 24h behind now — give a 5s slop window for spec scheduling jitter.
        def expected = System.currentTimeMillis() - (24L * 3600_000L)
        Math.abs(captured - expected) < 5000L
    }

    def "sweepSlowSellerWarning isolates per-row claim failures — sibling stamp + pushes still fire"() {
        // Same bug bar as the sweepReviewNudge spec just above. With the
        // old outer @Transactional, a per-row failure on row #7 marked the
        // shared tx rollback-only, the catch silently swallowed it, and the
        // slowSellerWarnedAt stamp on row #8 was reverted at commit — so the
        // next hourly tick re-fired TRADE_SLOW_SELLER + TRADE_SELLER_NUDGE
        // to every buyer + seller pair in the batch. Per-row auto-commit
        // must keep the bad row's failure from corrupting sibling stamps.
        // The stamp is now the atomic claimSlowSellerWarning conditional
        // UPDATE, so the bad row throws there.
        given:
        def bad  = tradeIn('PENDING_SELLER_SEND', [id: 7L, buyer: 10L, seller: 20L])
        def good = tradeIn('PENDING_SELLER_ACCEPT', [id: 8L, buyer: 11L, seller: 21L])
        bad.updatedAt  = System.currentTimeMillis() - (30L * 3600_000L)
        good.updatedAt = System.currentTimeMillis() - (30L * 3600_000L)
        tradeRepository.findSlowSellerUnwarned(_) >> [bad, good]
        tradeRepository.claimSlowSellerWarning(7L, _) >> {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(
                'Trade', 7L)
        }
        tradeRepository.claimSlowSellerWarning(8L, _) >> 1
        // No banned sellers — keeps the seller nudge live so we can assert
        // it fires for the sibling row.
        steamUserRepository.findById(21L) >> Optional.of(
            new com.sboxmarket.model.SteamUser(id: 21L, banned: false))

        when:
        service.sweepSlowSellerWarning()

        then: "sibling buyer still gets TRADE_SLOW_SELLER push despite bad row's claim throwing"
        1 * notificationService.push(11L, 'TRADE_SLOW_SELLER', _, _, 8L, _)
        and: "sibling seller still gets TRADE_SELLER_NUDGE push"
        1 * notificationService.push(21L, 'TRADE_SELLER_NUDGE', _, _, 8L, _)
        and: "the bad row never pushed — its claim threw before the push"
        0 * notificationService.push(10L, 'TRADE_SLOW_SELLER', _, _, 7L, _)
        and: "bad row's failure is swallowed in the per-row catch — sweep keeps going"
        noExceptionThrown()
    }

    def "sweepSlowSellerWarning swallows per-row push exceptions"() {
        given:
        def t1 = tradeIn('PENDING_SELLER_SEND', [id: 7L])
        def t2 = tradeIn('PENDING_SELLER_ACCEPT', [id: 8L])
        t1.updatedAt = System.currentTimeMillis() - (30L * 3600_000L)
        t2.updatedAt = System.currentTimeMillis() - (30L * 3600_000L)
        tradeRepository.findSlowSellerUnwarned(_) >> [t1, t2]
        steamUserRepository.findAllById(_) >> []
        // First push throws, second succeeds — sweeper must continue.
        notificationService.push(_, 'TRADE_SLOW_SELLER', _, _, 7L, _) >> { throw new RuntimeException('push down') }

        when:
        service.sweepSlowSellerWarning()

        then:
        // Both trades attempted.
        1 * notificationService.push(_, 'TRADE_SLOW_SELLER', _, _, 8L, _)
        // Batch 329 stamp ordering: the row is CLAIMED (atomic conditional
        // UPDATE) BEFORE the push so a flaky push doesn't retry every hour
        // forever. Both rows end claimed, even though row #7's push threw.
        1 * tradeRepository.claimSlowSellerWarning(7L, _) >> 1
        1 * tradeRepository.claimSlowSellerWarning(8L, _) >> 1
    }

    def "sweepSlowSellerWarning ALSO pushes TRADE_SELLER_NUDGE to the seller (batch 563)"() {
        given:
        def trade = tradeIn('PENDING_SELLER_SEND', [id: 9L, buyer: 10L, seller: 20L])
        trade.updatedAt = System.currentTimeMillis() - (30L * 3600_000L)
        tradeRepository.findSlowSellerUnwarned(_) >> [trade]
        tradeRepository.claimSlowSellerWarning(9L, _) >> 1
        // Seller exists + not banned so the nudge fires.
        steamUserRepository.findById(20L) >> Optional.of(new com.sboxmarket.model.SteamUser(id: 20L, banned: false))

        when:
        service.sweepSlowSellerWarning()

        then: "buyer gets the existing warning"
        1 * notificationService.push(10L, 'TRADE_SLOW_SELLER', _, _, 9L,
            '/profile?tab=trades&openChat=9')
        and: "seller gets the new nudge with matching deep-link"
        1 * notificationService.push(20L, 'TRADE_SELLER_NUDGE', _, _, 9L,
            '/profile?tab=trades&openChat=9')
    }

    def "sweepSlowSellerWarning skips the seller nudge when the seller is banned"() {
        given:
        def trade = tradeIn('PENDING_SELLER_ACCEPT', [id: 11L, buyer: 10L, seller: 20L])
        trade.updatedAt = System.currentTimeMillis() - (30L * 3600_000L)
        tradeRepository.findSlowSellerUnwarned(_) >> [trade]
        tradeRepository.claimSlowSellerWarning(11L, _) >> 1
        steamUserRepository.findById(20L) >> Optional.of(new com.sboxmarket.model.SteamUser(id: 20L, banned: true))

        when:
        service.sweepSlowSellerWarning()

        then: "buyer still gets the warning"
        1 * notificationService.push(10L, 'TRADE_SLOW_SELLER', _, _, 11L, _)
        and: "but no nudge fires for the banned seller"
        0 * notificationService.push(20L, 'TRADE_SELLER_NUDGE', _, _, _, _)
    }

    // ── Read receipts (batch 280) ──────────────────────────────────

    def "listMessages marks incoming messages read for the viewing participant"() {
        given:
        def msgRepo = Mock(com.sboxmarket.repository.TradeMessageRepository)
        service.tradeMessageRepository = msgRepo
        def t = tradeIn('PENDING_BUYER_CONFIRM', [id: 7L, buyer: 10L, seller: 20L])
        tradeRepository.findById(7L) >> Optional.of(t)
        msgRepo.findByTradeRecent(7L, _) >> []
        // Buyer (10L) opens the thread — the seller's (20L) messages
        // should get marked read; the buyer's own messages are NOT
        // touched.
        when:
        service.listMessages(7L, 10L)

        then:
        // Mark-read fires with the viewer's uid so the repo's
        // <> :viewerId clause excludes the viewer's own outbound rows.
        1 * msgRepo.markIncomingRead(7L, 10L, _) >> 0
    }

    def "listMessages does NOT mark-as-read for an admin spectator"() {
        given:
        def msgRepo = Mock(com.sboxmarket.repository.TradeMessageRepository)
        service.tradeMessageRepository = msgRepo
        adminAuthorization.isAdmin(_) >> true   // admin spectator
        def t = tradeIn('PENDING_BUYER_CONFIRM', [id: 7L, buyer: 10L, seller: 20L])
        tradeRepository.findById(7L) >> Optional.of(t)
        msgRepo.findByTradeRecent(7L, _) >> []

        when:
        service.listMessages(7L, 999L)   // non-participant admin

        then:
        // Admin reads must not flip the read state — preserves the
        // forensic "did the buyer ever open this?" signal.
        0 * msgRepo.markIncomingRead(_, _, _)
    }

    def "listMessages swallows mark-read failures and still returns the thread"() {
        given:
        def msgRepo = Mock(com.sboxmarket.repository.TradeMessageRepository)
        service.tradeMessageRepository = msgRepo
        def t = tradeIn('PENDING_BUYER_CONFIRM', [id: 7L, buyer: 10L, seller: 20L])
        def msg = new com.sboxmarket.model.TradeMessage(
            id: 1L, tradeId: 7L, senderUserId: 20L, body: 'hi')
        tradeRepository.findById(7L) >> Optional.of(t)
        msgRepo.markIncomingRead(7L, 10L, _) >> { throw new RuntimeException('db down') }
        msgRepo.findByTradeRecent(7L, _) >> [msg]

        when:
        def out = service.listMessages(7L, 10L)

        then:
        out.size() == 1
        // No exception bubbles — the user still sees the thread.
        noExceptionThrown()
    }

    def "postMessage pushes TRADE_MESSAGE with ?openChat=<tradeId> deep-link"() {
        given:
        def msgRepo = Mock(com.sboxmarket.repository.TradeMessageRepository)
        service.tradeMessageRepository = msgRepo
        def t = tradeIn('PENDING_SELLER_SEND', [id: 7L])
        tradeRepository.findById(7L) >> Optional.of(t)
        textSanitizer.clean('hello', 2000) >> 'hello'
        msgRepo.countBySenderUserIdAndCreatedAtGreaterThan(_, _) >> 0L
        msgRepo.save(_) >> { com.sboxmarket.model.TradeMessage m -> m }

        when:
        // Seller (20L) posts, counterparty is buyer (10L) — the push must
        // carry the openChat deep-link so the buyer's notification bell
        // lands them on the exact trade's chat panel.
        service.postMessage(7L, 20L, 'hello')

        then:
        1 * notificationService.push(10L, 'TRADE_MESSAGE', _, _, 7L, '/profile?tab=trades&openChat=7')
    }
}
