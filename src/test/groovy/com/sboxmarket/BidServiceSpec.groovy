package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.Bid
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.BidRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.BidService
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.security.BanGuard
import spock.lang.Specification
import spock.lang.Subject

/**
 * Unit coverage for placeBid. The scheduled sweeper (sweepExpired/settle)
 * is integration-level territory and is better tested end-to-end when the
 * auction flow gets its own spec; here we focus on every guard rail for
 * the hot path every bid goes through.
 */
class BidServiceSpec extends Specification {

    ListingRepository     listingRepository     = Mock()
    BidRepository         bidRepository         = Mock()
    WalletRepository      walletRepository      = Mock()
    TransactionRepository transactionRepository = Mock()
    SteamUserRepository   steamUserRepository   = Mock()
    NotificationService   notificationService   = Mock()
    BanGuard              banGuard              = Mock()
    TextSanitizer         textSanitizer         = Mock() {
        cleanShort(_) >> { String s -> s }
    }
    com.sboxmarket.service.EmailService emailService = Mock() {
        // Mimic the real canSendTo gate (batch 622): return true only
        // when the user has a verified email, notifications enabled,
        // and a non-empty email. Lets existing "skip email when
        // unverified" tests keep working without per-test stub churn.
        canSendTo(_, _) >> { user, bucket ->
            user != null &&
            user.email && !user.email.isEmpty() &&
            Boolean.TRUE.equals(user.emailVerified) &&
            Boolean.TRUE.equals(user.emailNotificationsEnabled)
        }
    }
    com.sboxmarket.repository.WatchlistAlertRepository watchlistAlertRepository = Mock()

    @Subject
    BidService service = new BidService(
        listingRepository    : listingRepository,
        bidRepository        : bidRepository,
        walletRepository     : walletRepository,
        transactionRepository: transactionRepository,
        steamUserRepository  : steamUserRepository,
        notificationService  : notificationService,
        banGuard             : banGuard,
        textSanitizer        : textSanitizer,
        emailService         : emailService,
        watchlistAlertRepository: watchlistAlertRepository
    )

    private Listing auctionListing(Map args = [:]) {
        new Listing(
            id:                args.id ?: 100L,
            item:              new Item(id: 1L, name: 'Wizard Hat'),
            price:             args.price ?: new BigDecimal("10"),
            sellerUserId:      args.seller ?: 99L,
            status:            args.status ?: 'ACTIVE',
            listingType:       args.type ?: 'AUCTION',
            expiresAt:         args.expiresAt ?: (System.currentTimeMillis() + 3600_000L),
            currentBid:        args.currentBid,
            currentBidderId:   args.currentBidderId,
            bidCount:          args.bidCount ?: 0
        )
    }

    // ── happy path ────────────────────────────────────────────────

    def "placeBid succeeds when amount meets the floor"() {
        given:
        def listing = auctionListing()
        listingRepository.findById(100L) >> Optional.of(listing)
        bidRepository.save(_) >> { Bid b -> b.id = 1L; b }
        listingRepository.save(_) >> { Listing l -> l }

        when:
        def bid = service.placeBid(10L, 'Alice', 100L, new BigDecimal("15"), null)

        then:
        1 * banGuard.assertNotBanned(10L)
        bid.amount == new BigDecimal("15")
        bid.kind == 'MANUAL'
        bid.status == 'WINNING'
        listing.currentBid == new BigDecimal("15")
        listing.currentBidderId == 10L
        listing.bidCount == 1
    }

    def "placeBid refuses a bidder whose wallet is frozen (batch 510)"() {
        given:
        def bidder = new SteamUser(id: 10L, steamId64: '7656117', displayName: 'Alice', tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc')
        def frozenWallet = new com.sboxmarket.model.Wallet(
            id: 500L,
            username: 'steam_7656117',
            balance: new BigDecimal('100.00'),
            frozen: true,
            frozenReason: 'Staff freeze during investigation'
        )
        listingRepository.findById(100L) >> Optional.of(auctionListing())
        steamUserRepository.findById(10L) >> Optional.of(bidder)
        walletRepository.findByUsername('steam_7656117') >> frozenWallet

        when:
        service.placeBid(10L, 'Alice', 100L, new BigDecimal('15'), null)

        then:
        def e = thrown(BadRequestException)
        e.code == 'WALLET_FROZEN'
    }

    def "placeBid refuses a bidder with an active deposit dispute (batch 511)"() {
        given:
        def bidder = new SteamUser(id: 10L, steamId64: '7656117', displayName: 'Alice', tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc')
        def wallet = new com.sboxmarket.model.Wallet(id: 500L, username: 'steam_7656117', balance: new BigDecimal('100.00'))
        listingRepository.findById(100L) >> Optional.of(auctionListing())
        steamUserRepository.findById(10L) >> Optional.of(bidder)
        walletRepository.findByUsername('steam_7656117') >> wallet
        transactionRepository.countActiveDisputedDeposits(500L) >> 1L

        when:
        service.placeBid(10L, 'Alice', 100L, new BigDecimal('15'), null)

        then:
        def e = thrown(BadRequestException)
        e.code == 'PURCHASE_DISPUTE_HOLD'
    }

    def "placeBid with maxAmount > amount flags kind AUTO"() {
        given:
        listingRepository.findById(_) >> Optional.of(auctionListing())
        bidRepository.save(_) >> { Bid b -> b }
        listingRepository.save(_) >> { Listing l -> l }

        when:
        def bid = service.placeBid(10L, 'Alice', 100L, new BigDecimal("15"), new BigDecimal("50"))

        then:
        bid.kind == 'AUTO'
        bid.maxAmount == new BigDecimal("50")
    }

    def "placeBid notifies the previous top bidder when they're outbid"() {
        given:
        def listing = auctionListing(currentBid: new BigDecimal("20"), currentBidderId: 7L)
        listingRepository.findById(_) >> Optional.of(listing)
        bidRepository.save(_) >> { Bid b -> b }
        listingRepository.save(_) >> { Listing l -> l }

        when:
        service.placeBid(10L, 'Alice', 100L, new BigDecimal("25"), null)

        then:
        1 * notificationService.push(7L, 'AUCTION_OUTBID', _, _, 100L, _)
    }

    def "placeBid does NOT notify when the same user raises their own bid"() {
        given:
        def listing = auctionListing(currentBid: new BigDecimal("20"), currentBidderId: 10L)
        listingRepository.findById(_) >> Optional.of(listing)
        bidRepository.save(_) >> { Bid b -> b }
        listingRepository.save(_) >> { Listing l -> l }

        when:
        service.placeBid(10L, 'Alice', 100L, new BigDecimal("25"), null)

        then:
        0 * notificationService.push(*_)
    }

    def "placeBid auto-raises the displaced top's bid when their AUTO cap covers the new bid + increment"() {
        given:
        def listing = auctionListing(currentBid: new BigDecimal('20'), currentBidderId: 7L, bidCount: 1)
        def existing = new Bid(id: 5L, listingId: 100L, bidderUserId: 7L, bidderName: 'Previous',
                               amount: new BigDecimal('20'), maxAmount: new BigDecimal('50'), kind: 'AUTO', status: 'WINNING')
        listingRepository.findById(_) >> Optional.of(listing)
        bidRepository.findByListing(100L) >> [existing]
        // Batch 330: bot re-raise now verifies the previous top's wallet covers
        // the raise. Stub enough balance so the bot fires normally here.
        steamUserRepository.findById(7L) >> Optional.of(new SteamUser(id: 7L, steamId64: 'SID7'))
        walletRepository.findByUsername('steam_SID7') >> new com.sboxmarket.model.Wallet(
            id: 77L, username: 'steam_SID7', balance: new BigDecimal('100.00')
        )
        def savedBids = []
        bidRepository.save(_) >> { Bid b -> if (b.id == null) b.id = (100L + savedBids.size()); savedBids << b; b }
        listingRepository.save(_) >> { Listing l -> l }

        when:
        def result = service.placeBid(10L, 'Alice', 100L, new BigDecimal('25'), null)

        then:
        // Bot fires: new listing currentBid is 25 + 0.05 = 25.05, bidder back to the auto-cap holder
        listing.currentBid == new BigDecimal('25.05')
        listing.currentBidderId == 7L
        // Outbid notification goes to the new bidder (Alice), not the auto-cap holder
        1 * notificationService.push(10L, 'AUCTION_OUTBID', _, _, 100L, _)
        0 * notificationService.push(7L, 'AUCTION_OUTBID', _, _, _, _)
        // Service returns the bot-placed bid, not Alice's bid
        result.bidderUserId == 7L
        result.kind == 'AUTO'
        result.amount == new BigDecimal('25.05')
    }

    def "placeBid with AUTO vs AUTO: higher-cap bidder wins at loser_cap + INC"() {
        given:
        // A has existing AUTO bid amount=$20 max=$40, currently winning.
        // B places amount=$25 with max=$50. B's cap is higher → B wins at $40.05.
        def listing = auctionListing(currentBid: new BigDecimal('20'), currentBidderId: 7L, bidCount: 1)
        def existing = new Bid(id: 5L, listingId: 100L, bidderUserId: 7L, bidderName: 'A',
                               amount: new BigDecimal('20'), maxAmount: new BigDecimal('40'), kind: 'AUTO', status: 'WINNING')
        listingRepository.findById(_) >> Optional.of(listing)
        bidRepository.findByListing(100L) >> [existing]
        bidRepository.save(_) >> { Bid b -> b }
        listingRepository.save(_) >> { Listing l -> l }

        when:
        service.placeBid(10L, 'B', 100L, new BigDecimal('25'), new BigDecimal('50'))

        then:
        // B wins at $40.05 (A's cap + increment), not at their original $25.
        listing.currentBid == new BigDecimal('40.05')
        listing.currentBidderId == 10L
        // A (previous top) gets the plain outbid notification.
        1 * notificationService.push(7L, 'AUCTION_OUTBID', _, _, _, _)
    }

    def "placeBid skips the auto-raise when the previous top's wallet dropped below the required raise (batch 330)"() {
        given:
        // Alice has existing AUTO bid $20 with max=$50 — so aMax=$50. Bob outbids
        // at $25. The bot would normally re-raise Alice to $25.05. But Alice's
        // wallet has only $5 now (she withdrew between her original bid and this
        // moment), so the re-raise would win her an auction she can't pay for.
        // Batch 330: detect + skip the re-raise so Bob wins cleanly at $25.
        def listing = auctionListing(currentBid: new BigDecimal('20'), currentBidderId: 7L, bidCount: 1)
        def existing = new Bid(id: 5L, listingId: 100L, bidderUserId: 7L, bidderName: 'Alice',
                               amount: new BigDecimal('20'), maxAmount: new BigDecimal('50'), kind: 'AUTO', status: 'WINNING')
        listingRepository.findById(_) >> Optional.of(listing)
        bidRepository.findByListing(100L) >> [existing]
        // Alice's account exists but her balance is now only $5 — can't cover $25.05.
        steamUserRepository.findById(7L) >> Optional.of(new SteamUser(id: 7L, steamId64: 'SID7'))
        walletRepository.findByUsername('steam_SID7') >> new com.sboxmarket.model.Wallet(
            id: 77L, username: 'steam_SID7', balance: new BigDecimal('5.00')
        )
        bidRepository.save(_) >> { Bid b -> b.id = 999L; b }
        listingRepository.save(_) >> { Listing l -> l }

        when:
        def result = service.placeBid(10L, 'Bob', 100L, new BigDecimal('25'), null)

        then:
        // Bob wins cleanly at his submitted amount — no bot re-raise fired.
        listing.currentBid == new BigDecimal('25')
        listing.currentBidderId == 10L
        result.bidderUserId == 10L
        result.amount == new BigDecimal('25')
        // Alice gets the explanatory push about her auto-bid being skipped so
        // she knows her cap didn't fire and why.
        1 * notificationService.push(7L, 'AUCTION_OUTBID',
            'Auto-bid skipped — balance too low', _, 100L, _)
        // No bot-bid saved — only Bob's bid.
        // (bidRepository.save is called once for Bob's bid)
    }

    def "placeBid does NOT auto-raise when the displaced top's max can't cover the new bid + increment"() {
        given:
        def listing = auctionListing(currentBid: new BigDecimal('20'), currentBidderId: 7L, bidCount: 1)
        // Previous top's cap is only $24 — Alice's $25 clears it.
        def existing = new Bid(id: 5L, listingId: 100L, bidderUserId: 7L, bidderName: 'Previous',
                               amount: new BigDecimal('20'), maxAmount: new BigDecimal('24'), kind: 'AUTO', status: 'WINNING')
        listingRepository.findById(_) >> Optional.of(listing)
        bidRepository.findByListing(100L) >> [existing]
        bidRepository.save(_) >> { Bid b -> b }
        listingRepository.save(_) >> { Listing l -> l }

        when:
        service.placeBid(10L, 'Alice', 100L, new BigDecimal('25'), null)

        then:
        // No auto-raise: Alice wins
        listing.currentBid == new BigDecimal('25')
        listing.currentBidderId == 10L
        1 * notificationService.push(7L, 'AUCTION_OUTBID', _, _, _, _)
    }

    def "placeBid extends expiresAt when bid lands inside the 30s anti-snipe window"() {
        given:
        def now = System.currentTimeMillis()
        // 10 seconds left — well inside the soft-close window
        def listing = auctionListing(currentBid: new BigDecimal("20"),
                                     currentBidderId: 7L,
                                     expiresAt: now + 10_000L)
        listingRepository.findById(_) >> Optional.of(listing)
        bidRepository.save(_) >> { Bid b -> b }
        listingRepository.save(_) >> { Listing l -> l }

        when:
        service.placeBid(10L, 'Alice', 100L, new BigDecimal("25"), null)

        then:
        // Anti-sniping: the close should be at least ~25s out now (we
        // extend by 30s, give a generous lower bound to avoid clock flakes)
        listing.expiresAt >= now + 25_000L
    }

    def "placeBid does NOT extend expiresAt when bid lands well before the anti-snipe window"() {
        given:
        def now = System.currentTimeMillis()
        def originalExpiry = now + 600_000L  // 10 minutes left
        def listing = auctionListing(currentBid: new BigDecimal("20"),
                                     currentBidderId: 7L,
                                     expiresAt: originalExpiry)
        listingRepository.findById(_) >> Optional.of(listing)
        bidRepository.save(_) >> { Bid b -> b }
        listingRepository.save(_) >> { Listing l -> l }

        when:
        service.placeBid(10L, 'Alice', 100L, new BigDecimal("25"), null)

        then:
        // No extension when the auction isn't close to ending
        listing.expiresAt == originalExpiry
    }

    // ── guard rails ───────────────────────────────────────────────

    def "placeBid refuses a bidder whose wallet can't cover the bid (grief-guard)"() {
        given:
        def listing = auctionListing()
        listingRepository.findById(100L) >> Optional.of(listing)
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, steamId64: 'SID10',
            tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=10&token=abc'))
        walletRepository.findByUsername('steam_SID10') >> new com.sboxmarket.model.Wallet(
            id: 77L, username: 'steam_SID10', balance: new BigDecimal('5.00')
        )

        when:
        service.placeBid(10L, 'Alice', 100L, new BigDecimal('25'), null)

        then:
        def e = thrown(BadRequestException)
        e.code == 'INSUFFICIENT_BALANCE'
        0 * bidRepository.save(_)
    }

    def "placeBid checks balance against maxAmount when auto-bidding (the ceiling is what they actually commit to)"() {
        given:
        def listing = auctionListing()
        listingRepository.findById(100L) >> Optional.of(listing)
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, steamId64: 'SID10',
            tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=10&token=abc'))
        walletRepository.findByUsername('steam_SID10') >> new com.sboxmarket.model.Wallet(
            id: 77L, username: 'steam_SID10', balance: new BigDecimal('20.00')
        )

        when:
        // amount $15 fits, but maxAmount $50 exceeds the $20 balance — reject.
        service.placeBid(10L, 'Alice', 100L, new BigDecimal('15'), new BigDecimal('50'))

        then:
        def e = thrown(BadRequestException)
        e.code == 'INSUFFICIENT_BALANCE'
    }

    def "placeBid passes the solvency check when wallet balance covers the bid"() {
        given:
        def listing = auctionListing()
        listingRepository.findById(100L) >> Optional.of(listing)
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, steamId64: 'SID10',
            tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=10&token=abc'))
        walletRepository.findByUsername('steam_SID10') >> new com.sboxmarket.model.Wallet(
            id: 77L, username: 'steam_SID10', balance: new BigDecimal('100.00')
        )
        bidRepository.save(_) >> { Bid b -> b.id = 1L; b }
        listingRepository.save(_) >> { Listing l -> l }

        when:
        def bid = service.placeBid(10L, 'Alice', 100L, new BigDecimal('25'), null)

        then:
        bid.amount == new BigDecimal('25')
    }

    def "placeBid refuses zero/negative/null amounts"() {
        when:
        service.placeBid(10L, 'Alice', 100L, amount, null)

        then:
        thrown(BadRequestException)

        where:
        amount << [null, BigDecimal.ZERO, new BigDecimal("-1")]
    }

    def "placeBid 404s for unknown listing"() {
        given:
        listingRepository.findById(_) >> Optional.empty()

        when:
        service.placeBid(10L, 'Alice', 999L, new BigDecimal("10"), null)

        then:
        thrown(NotFoundException)
    }

    def "placeBid refuses non-ACTIVE listings"() {
        given:
        listingRepository.findById(_) >> Optional.of(auctionListing(status: 'SOLD'))

        when:
        service.placeBid(10L, 'Alice', 100L, new BigDecimal("15"), null)

        then:
        thrown(BadRequestException)
    }

    def "placeBid refuses BUY_NOW listings"() {
        given:
        listingRepository.findById(_) >> Optional.of(auctionListing(type: 'BUY_NOW'))

        when:
        service.placeBid(10L, 'Alice', 100L, new BigDecimal("15"), null)

        then:
        thrown(BadRequestException)
    }

    def "placeBid refuses hidden auctions (batch 308 bug fix)"() {
        // A seller who hides an auction after it's live (vacation-mode
        // or per-listing Hide) would otherwise keep receiving real
        // bids from cached clients. Must reject.
        given:
        def hidden = auctionListing()
        hidden.hidden = true
        listingRepository.findById(_) >> Optional.of(hidden)

        when:
        service.placeBid(10L, 'Alice', 100L, new BigDecimal("15"), null)

        then:
        thrown(BadRequestException)
    }

    def "placeBid refuses expired auctions"() {
        given:
        listingRepository.findById(_) >> Optional.of(auctionListing(expiresAt: System.currentTimeMillis() - 1000L))

        when:
        service.placeBid(10L, 'Alice', 100L, new BigDecimal("15"), null)

        then:
        thrown(BadRequestException)
    }

    def "placeBid forbids seller bidding on their own auction"() {
        given:
        listingRepository.findById(_) >> Optional.of(auctionListing(seller: 10L))

        when:
        service.placeBid(10L, 'Alice', 100L, new BigDecimal("15"), null)

        then:
        thrown(ForbiddenException)
    }

    def "placeBid refuses bids below the floor (currentBid or starting price)"() {
        given:
        listingRepository.findById(_) >> Optional.of(auctionListing(currentBid: new BigDecimal("20"), currentBidderId: 7L))

        when:
        service.placeBid(10L, 'Alice', 100L, new BigDecimal("19"), null)

        then:
        thrown(BadRequestException)
    }

    def "placeBid refuses a tied bid that would silently displace the current top bidder (bug #30)"() {
        given:
        // Current high bid is $20. Without the increment guard, a new
        // bidder could POST amount=$20 and become the new top bidder
        // even though they aren't actually outbidding anyone.
        listingRepository.findById(_) >> Optional.of(
            auctionListing(currentBid: new BigDecimal("20"), currentBidderId: 7L)
        )

        when:
        service.placeBid(10L, 'Alice', 100L, new BigDecimal("20"), null)

        then:
        def ex = thrown(BadRequestException)
        ex.code == 'BID_TOO_LOW'
        ex.message.contains('increment')
        0 * bidRepository.save(_)
        0 * listingRepository.save(_)
    }

    def "placeBid refuses a bid that's the currentBid + a penny (below the 5c increment)"() {
        given:
        listingRepository.findById(_) >> Optional.of(
            auctionListing(currentBid: new BigDecimal("20.00"), currentBidderId: 7L)
        )

        when:
        service.placeBid(10L, 'Alice', 100L, new BigDecimal("20.01"), null)

        then:
        thrown(BadRequestException)
    }

    def "placeBid accepts exactly currentBid + 0.05 increment"() {
        given:
        listingRepository.findById(_) >> Optional.of(
            auctionListing(currentBid: new BigDecimal("20.00"), currentBidderId: 7L)
        )
        bidRepository.save(_) >> { Bid b -> b }
        listingRepository.save(_) >> { Listing l -> l }

        when:
        def result = service.placeBid(10L, 'Alice', 100L, new BigDecimal("20.05"), null)

        then:
        result != null
        result.amount == new BigDecimal("20.05")
    }

    def "placeBid rejects an auto-bid cap below the bid amount (INVALID_MAX_BID)"() {
        // A maxAmount strictly under `amount` is contradictory — the bot
        // could never raise to a ceiling beneath your own bid. Reject it
        // up front rather than persisting confusing dead data on the row.
        when:
        service.placeBid(10L, 'Alice', 100L, new BigDecimal('25'), new BigDecimal('20'))

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_MAX_BID'
        0 * bidRepository.save(_)
    }

    def "placeBid allows a maxAmount equal to amount (treated as a plain manual bid)"() {
        given:
        listingRepository.findById(_) >> Optional.of(auctionListing())
        bidRepository.save(_) >> { Bid b -> b }
        listingRepository.save(_) >> { Listing l -> l }

        when:
        def bid = service.placeBid(10L, 'Alice', 100L, new BigDecimal('15'), new BigDecimal('15'))

        then:
        bid.kind == 'MANUAL'
    }

    def "placeBid auto-bid tie: the earlier bidder (standing top) keeps the lead"() {
        given:
        // A holds an AUTO bid amount=$20 max=$40. B places amount=$25 with
        // an identical cap max=$40. On a dead-heat the EARLIER bidder (A)
        // must win — eBay/CSFloat convention — so A re-wins at $40, not B.
        def listing = auctionListing(currentBid: new BigDecimal('20'), currentBidderId: 7L, bidCount: 1)
        def existing = new Bid(id: 5L, listingId: 100L, bidderUserId: 7L, bidderName: 'A',
                               amount: new BigDecimal('20'), maxAmount: new BigDecimal('40'),
                               kind: 'AUTO', status: 'WINNING')
        listingRepository.findById(_) >> Optional.of(listing)
        bidRepository.findByListing(100L) >> [existing]
        steamUserRepository.findById(7L) >> Optional.of(new SteamUser(id: 7L, steamId64: 'SID7'))
        walletRepository.findByUsername('steam_SID7') >> new com.sboxmarket.model.Wallet(
            id: 77L, username: 'steam_SID7', balance: new BigDecimal('100.00'))
        bidRepository.save(_) >> { Bid b -> b }
        listingRepository.save(_) >> { Listing l -> l }

        when:
        def result = service.placeBid(10L, 'B', 100L, new BigDecimal('25'), new BigDecimal('40'))

        then:
        // A wins the tie at their cap; B (latecomer) is outbid.
        listing.currentBidderId == 7L
        listing.currentBid == new BigDecimal('40')
        result.bidderUserId == 7L
        1 * notificationService.push(10L, 'AUCTION_OUTBID', _, _, 100L, _)
    }

    // ── buyNowAuction ──────────────────────────────────────────────

    private Listing buyNowListing(Map args = [:]) {
        auctionListing(args).tap {
            buyNowPrice = args.buyNowPrice ?: new BigDecimal('50')
        }
    }

    def "buyNowAuction rejects when bidding has already reached the Buy Now price"() {
        // SellService enforces buyNowPrice > startingPrice at creation, but
        // a bid war can push currentBid up to / past buyNowPrice. Honouring
        // Buy-Now then would settle BELOW the standing top bid.
        given:
        def listing = buyNowListing(buyNowPrice: new BigDecimal('50'),
            currentBid: new BigDecimal('55'), currentBidderId: 7L)
        listingRepository.findById(100L) >> Optional.of(listing)

        when:
        service.buyNowAuction(10L, 'Alice', 100L)

        then:
        def e = thrown(BadRequestException)
        e.code == 'BUY_NOW_UNAVAILABLE'
    }

    def "buyNowAuction rejects when current bid exactly equals the Buy Now price"() {
        given:
        def listing = buyNowListing(buyNowPrice: new BigDecimal('50'),
            currentBid: new BigDecimal('50'), currentBidderId: 7L)
        listingRepository.findById(100L) >> Optional.of(listing)

        when:
        service.buyNowAuction(10L, 'Alice', 100L)

        then:
        def e = thrown(BadRequestException)
        e.code == 'BUY_NOW_UNAVAILABLE'
    }

    def "buyNowAuction 400s with NO_BUY_NOW when the auction has no Buy Now price"() {
        given:
        listingRepository.findById(100L) >> Optional.of(auctionListing())  // buyNowPrice null

        when:
        service.buyNowAuction(10L, 'Alice', 100L)

        then:
        def e = thrown(BadRequestException)
        e.code == 'NO_BUY_NOW'
    }

    // ── cancelAutoBid ──────────────────────────────────────────────

    def "cancelAutoBid no-ops on a terminal (WON/LOST/CANCELLED) bid"() {
        given:
        // A closed-auction bid has no live bot to stop — cancelling it
        // would needlessly rewrite terminal history.
        def won = new Bid(id: 9L, listingId: 100L, bidderUserId: 10L,
            amount: new BigDecimal('40'), maxAmount: new BigDecimal('60'),
            kind: 'AUTO', status: 'WON')
        bidRepository.findById(9L) >> Optional.of(won)

        when:
        def n = service.cancelAutoBid(10L, 9L)

        then:
        n == 0
        won.maxAmount == new BigDecimal('60')  // untouched
        0 * bidRepository.save(_)
    }

    def "cancelAutoBid clears the cap on a live WINNING auto-bid"() {
        given:
        def live = new Bid(id: 9L, listingId: 100L, bidderUserId: 10L,
            amount: new BigDecimal('40'), maxAmount: new BigDecimal('60'),
            kind: 'AUTO', status: 'WINNING')
        bidRepository.findById(9L) >> Optional.of(live)
        bidRepository.save(_) >> { Bid b -> b }

        when:
        def n = service.cancelAutoBid(10L, 9L)

        then:
        n == 1
        live.maxAmount == null
        live.kind == 'MANUAL'
    }

    def "cancelAutoBid forbids cancelling another user's bid"() {
        given:
        bidRepository.findById(9L) >> Optional.of(new Bid(id: 9L, bidderUserId: 999L,
            kind: 'AUTO', status: 'WINNING'))

        when:
        service.cancelAutoBid(10L, 9L)

        then:
        thrown(ForbiddenException)
    }

    // ── historyFor (redaction) ─────────────────────────────────────

    private Bid bid(Map args) {
        new Bid(
            id:           args.id,
            listingId:    args.listingId ?: 100L,
            bidderUserId: args.bidderUserId,
            bidderName:   args.bidderName ?: 'Real',
            amount:       args.amount ?: new BigDecimal("20"),
            maxAmount:    args.maxAmount,
            kind:         args.kind ?: 'MANUAL',
            status:       args.status ?: 'WINNING'
        )
    }

    def "historyFor returns empty list when no bids exist"() {
        given:
        bidRepository.findByListing(100L) >> []

        expect:
        service.historyFor(100L, 10L) == []
    }

    def "historyFor returns raw bids (no redaction) to the listing seller"() {
        given:
        def a = bid(id: 1L, bidderUserId: 10L, bidderName: 'Alice', maxAmount: new BigDecimal("50"))
        def b = bid(id: 2L, bidderUserId: 20L, bidderName: 'Bob')
        bidRepository.findByListing(100L) >> [a, b]
        listingRepository.findById(100L) >> Optional.of(auctionListing(seller: 99L))

        when:
        def out = service.historyFor(100L, 99L)

        then:
        out == [a, b]
        out[0].bidderName == 'Alice'
        out[0].bidderUserId == 10L
        out[0].maxAmount == new BigDecimal("50")
    }

    def "historyFor returns raw bids (no redaction) to any participating bidder"() {
        given:
        def a = bid(id: 1L, bidderUserId: 10L, bidderName: 'Alice')
        def b = bid(id: 2L, bidderUserId: 20L, bidderName: 'Bob')
        bidRepository.findByListing(100L) >> [a, b]
        listingRepository.findById(100L) >> Optional.of(auctionListing(seller: 99L))

        when:
        def out = service.historyFor(100L, 20L)  // Bob viewing

        then:
        out == [a, b]
        out[0].bidderName == 'Alice'
    }

    def "historyFor redacts bidder identities for anonymous viewers"() {
        given:
        def a = bid(id: 1L, bidderUserId: 10L, bidderName: 'Alice', maxAmount: new BigDecimal("50"))
        def b = bid(id: 2L, bidderUserId: 20L, bidderName: 'Bob')
        bidRepository.findByListing(100L) >> [a, b]
        listingRepository.findById(100L) >> Optional.of(auctionListing(seller: 99L))

        when:
        def out = service.historyFor(100L, null)

        then:
        out.size() == 2
        out*.bidderName == ['Bidder #1', 'Bidder #2']
        out*.bidderUserId == [null, null]
        // maxAmount is strategic — never leak it to third parties
        out*.maxAmount == [null, null]
        // Original entities untouched
        a.bidderName == 'Alice'
        a.maxAmount == new BigDecimal("50")
    }

    def "historyFor redacts bidder identities for a logged-in third party"() {
        given:
        bidRepository.findByListing(100L) >> [
            bid(id: 1L, bidderUserId: 10L, bidderName: 'Alice'),
            bid(id: 2L, bidderUserId: 20L, bidderName: 'Bob')
        ]
        listingRepository.findById(100L) >> Optional.of(auctionListing(seller: 99L))

        when:
        def out = service.historyFor(100L, 77L)  // unrelated user

        then:
        out*.bidderName == ['Bidder #1', 'Bidder #2']
        out*.bidderUserId == [null, null]
    }

    def "historyFor gives the same handle to repeat bids from the same bidder"() {
        given:
        bidRepository.findByListing(100L) >> [
            bid(id: 1L, bidderUserId: 10L, bidderName: 'Alice', amount: new BigDecimal("30")),
            bid(id: 2L, bidderUserId: 20L, bidderName: 'Bob',   amount: new BigDecimal("25")),
            bid(id: 3L, bidderUserId: 10L, bidderName: 'Alice', amount: new BigDecimal("20"))
        ]
        listingRepository.findById(100L) >> Optional.of(auctionListing(seller: 99L))

        when:
        def out = service.historyFor(100L, null)

        then:
        out*.bidderName == ['Bidder #1', 'Bidder #2', 'Bidder #1']
    }

    def "historyFor redacts even when the listing row has been deleted"() {
        given:
        bidRepository.findByListing(100L) >> [bid(id: 1L, bidderUserId: 10L, bidderName: 'Alice')]
        listingRepository.findById(100L) >> Optional.empty()

        when:
        def out = service.historyFor(100L, 99L)

        then:
        out.size() == 1
        out[0].bidderName == 'Bidder #1'
        out[0].bidderUserId == null
    }

    // ── outbid email hook ─────────────────────────────────────────

    def "placeBid emails the displaced top bidder when they have a verified email"() {
        given:
        // Listing already has a top bid of $15 by user 7 — new bidder
        // (user 10) posts $20 and displaces them.
        def listing = auctionListing(currentBid: new BigDecimal('15'),
            currentBidderId: 7L, currentBidderName: 'Bob', bidCount: 1)
        listingRepository.findById(100L) >> Optional.of(listing)
        bidRepository.save(_) >> { Bid b -> b.id = 2L; b }
        listingRepository.save(_) >> { Listing l -> l }
        steamUserRepository.findById(7L) >> Optional.of(
            new SteamUser(id: 7L, steamId64: '7', email: 'bob@example.com',
                emailVerified: true, displayName: 'Bob')
        )

        when:
        service.placeBid(10L, 'Alice', 100L, new BigDecimal('20'), null)

        then:
        1 * emailService.sendAuctionOutbid('bob@example.com', 'Bob', 'Wizard Hat',
            new BigDecimal('20'), '/item/1')
    }

    def "placeBid does NOT email when previous top bidder's email is unverified"() {
        given:
        def listing = auctionListing(currentBid: new BigDecimal('15'),
            currentBidderId: 7L, currentBidderName: 'Bob', bidCount: 1)
        listingRepository.findById(100L) >> Optional.of(listing)
        bidRepository.save(_) >> { Bid b -> b.id = 2L; b }
        listingRepository.save(_) >> { Listing l -> l }
        steamUserRepository.findById(7L) >> Optional.of(
            new SteamUser(id: 7L, steamId64: '7', email: 'bob@example.com',
                emailVerified: false, displayName: 'Bob')
        )

        when:
        service.placeBid(10L, 'Alice', 100L, new BigDecimal('20'), null)

        then:
        0 * emailService.sendAuctionOutbid(_, _, _, _, _)
    }

    def "placeBid keeps going when the outbid email throws"() {
        given:
        def listing = auctionListing(currentBid: new BigDecimal('15'),
            currentBidderId: 7L, currentBidderName: 'Bob', bidCount: 1)
        listingRepository.findById(100L) >> Optional.of(listing)
        bidRepository.save(_) >> { Bid b -> b.id = 2L; b }
        listingRepository.save(_) >> { Listing l -> l }
        steamUserRepository.findById(7L) >> Optional.of(
            new SteamUser(id: 7L, steamId64: '7', email: 'bob@example.com',
                emailVerified: true, displayName: 'Bob')
        )
        emailService.sendAuctionOutbid(_, _, _, _, _) >> { throw new RuntimeException('SMTP down') }

        when:
        def bid = service.placeBid(10L, 'Alice', 100L, new BigDecimal('20'), null)

        then:
        // Bid still placed; the notification bell still fires; SMTP boom doesn't roll back.
        bid != null
        1 * notificationService.push(7L, 'AUCTION_OUTBID', _, _, 100L, _)
        noExceptionThrown()
    }

    // ── sweepEndingSoon ──────────────────────────────────────────

    def "sweepEndingSoon is a no-op when nothing is due"() {
        given:
        listingRepository.findEndingSoonUnnotified(_, _) >> []

        when:
        service.sweepEndingSoon()

        then:
        0 * notificationService.push(*_)
        0 * listingRepository.save(_)
    }

    def "sweepEndingSoon notifies every unique bidder plus watchers and flips the flag"() {
        given:
        def listing = auctionListing(id: 100L, currentBid: new BigDecimal("15"),
                                     expiresAt: System.currentTimeMillis() + 5 * 60 * 1000L)
        listingRepository.findEndingSoonUnnotified(_, _) >> [listing]
        // Three bids from two distinct users → two unique bidders
        bidRepository.findByListing(100L) >> [
            new Bid(id: 1L, listingId: 100L, bidderUserId: 10L, amount: new BigDecimal("15")),
            new Bid(id: 2L, listingId: 100L, bidderUserId: 20L, amount: new BigDecimal("13")),
            new Bid(id: 3L, listingId: 100L, bidderUserId: 10L, amount: new BigDecimal("12"))
        ]
        // One watcher (non-bidder) + one overlap (also a bidder — must not
        // duplicate).
        watchlistAlertRepository.findActiveUserIdsForItem(1L) >> [30L, 10L]
        // All three recipients clean (not banned) — the filter is a no-op.
        steamUserRepository.findAllById(_) >> [
            new SteamUser(id: 10L, steamId64: '1', banned: false),
            new SteamUser(id: 20L, steamId64: '2', banned: false),
            new SteamUser(id: 30L, steamId64: '3', banned: false)
        ]

        when:
        service.sweepEndingSoon()

        then:
        // 10, 20, 30 — one push each, no duplicate for 10.
        1 * notificationService.push(10L, 'AUCTION_ENDING', _, _, 100L, _)
        1 * notificationService.push(20L, 'AUCTION_ENDING', _, _, 100L, _)
        1 * notificationService.push(30L, 'AUCTION_ENDING', _, _, 100L, _)
        0 * notificationService.push(_, 'AUCTION_ENDING', _, _, 100L, _)
        // Dedup flag set so the next tick skips this listing.
        listing.endingSoonNotified == true
        1 * listingRepository.save({ Listing l -> l.endingSoonNotified == true })
    }

    def "sweepEndingSoon fan-outs AUCTION_ENDING email to verified-email recipients (batch 572)"() {
        given:
        def listing = auctionListing(id: 100L, currentBid: new BigDecimal("15"),
                                     expiresAt: System.currentTimeMillis() + 5 * 60 * 1000L)
        listingRepository.findEndingSoonUnnotified(_, _) >> [listing]
        bidRepository.findByListing(100L) >> [
            new Bid(id: 1L, listingId: 100L, bidderUserId: 10L, amount: new BigDecimal("15"))
        ]
        watchlistAlertRepository.findActiveUserIdsForItem(1L) >> [20L]
        def optedIn = new SteamUser(id: 10L, steamId64: '1', banned: false,
            email: 'alice@example.com', emailVerified: true,
            emailNotificationsEnabled: true)
        def optedOut = new SteamUser(id: 20L, steamId64: '2', banned: false,
            email: 'bob@example.com', emailVerified: true,
            emailNotificationsEnabled: false)  // ← global notifications off
        steamUserRepository.findAllById(_) >>> [
            [optedIn, optedOut],  // banned-filter fetch
            [optedIn, optedOut]   // email fan-out fetch
        ]
        def emailSvc = Mock(com.sboxmarket.service.EmailService) {
            // Delegate to real-gate semantics so opted-out bob is filtered.
            canSendTo(_, _) >> { user, bucket ->
                user != null &&
                user.email && !user.email.isEmpty() &&
                Boolean.TRUE.equals(user.emailVerified) &&
                Boolean.TRUE.equals(user.emailNotificationsEnabled)
            }
        }
        service.emailService = emailSvc

        when:
        service.sweepEndingSoon()

        then:
        // Push still fires to both recipients (no filter on push path).
        1 * notificationService.push(10L, 'AUCTION_ENDING', _, _, 100L, _)
        1 * notificationService.push(20L, 'AUCTION_ENDING', _, _, 100L, _)
        // Email fires ONLY for the opted-in user.
        1 * emailSvc.sendAuctionEnding('alice@example.com', _, _, _, _, _)
        0 * emailSvc.sendAuctionEnding('bob@example.com', _, _, _, _, _)
    }

    def "settle flips the winner's bid from WINNING to WON (batch 324)"() {
        // Regression test for the stale Active-Bids row. Prior to 324,
        // settle flipped losers to LOST but left winners stuck in
        // WINNING forever, so Profile → Active Bids kept showing the
        // closed auction as live for the winner.
        given:
        def now = System.currentTimeMillis()
        def listing = auctionListing(
            id: 100L, currentBid: new BigDecimal("50"), currentBidderId: 10L,
            expiresAt: now - 1000L  // already expired
        )
        listingRepository.findExpiredAuctions(_) >> [listing]
        def winnerBid = new Bid(id: 1L, listingId: 100L, bidderUserId: 10L,
            amount: new BigDecimal("50"), status: 'WINNING')
        def loserBid = new Bid(id: 2L, listingId: 100L, bidderUserId: 20L,
            amount: new BigDecimal("45"), status: 'WINNING')
        bidRepository.findByListing(100L) >> [winnerBid, loserBid]
        // Winner setup: has a wallet with enough balance.
        def winner = new SteamUser(id: 10L, steamId64: 'winner', displayName: 'W', banned: false)
        steamUserRepository.findById(10L) >> Optional.of(winner)
        def winnerWallet = new com.sboxmarket.model.Wallet(id: 500L, username: 'steam_winner', balance: new BigDecimal("500"))
        walletRepository.findByUsername('steam_winner') >> winnerWallet
        walletRepository.save(_) >> { com.sboxmarket.model.Wallet w -> w }
        listingRepository.save(_) >> { Listing l -> l }
        bidRepository.save(_) >> { Bid b -> b }
        bidRepository.saveAll(_) >> { List<Bid> bs -> bs }
        // Seller-side wiring used by settle for fee/credit — noop enough
        // mocks to let the flow complete.
        def seller = new SteamUser(id: 99L, steamId64: 'seller')
        steamUserRepository.findById(99L) >> Optional.of(seller)
        def sellerWallet = new com.sboxmarket.model.Wallet(id: 501L, username: 'steam_seller', balance: BigDecimal.ZERO)
        walletRepository.findByUsername('steam_seller') >> sellerWallet
        walletRepository.save(sellerWallet) >> sellerWallet

        when:
        service.sweepExpired()

        then:
        // Winner's bid is WON (not stuck in WINNING).
        winnerBid.status == 'WON'
        // Loser flipped to LOST.
        loserBid.status == 'LOST'
        // Listing marked SOLD with the winner as buyer.
        listing.status == 'SOLD'
        listing.buyerUserId == 10L
    }

    def "settle closes out every live bid when the winner is banned (no orphaned WINNING rows)"() {
        // No-sale settle path: a winner banned between bid-time and
        // settle-time returns the item to the seller. Every bid on the
        // listing — the banned winner's AND every loser's — must flip to
        // LOST, else they linger in WINNING on a SOLD listing and the
        // bidders' Active Bids tab shows the dead auction as live forever.
        given:
        def now = System.currentTimeMillis()
        def listing = auctionListing(
            id: 100L, currentBid: new BigDecimal("50"), currentBidderId: 10L,
            seller: 99L, expiresAt: now - 1000L
        )
        listingRepository.findExpiredAuctions(_) >> [listing]
        def winnerBid = new Bid(id: 1L, listingId: 100L, bidderUserId: 10L,
            amount: new BigDecimal("50"), status: 'WINNING')
        def loserBid = new Bid(id: 2L, listingId: 100L, bidderUserId: 20L,
            amount: new BigDecimal("45"), status: 'WINNING')
        bidRepository.findByListing(100L) >> [winnerBid, loserBid]
        // Winner is banned → no-sale branch.
        steamUserRepository.findById(10L) >> Optional.of(
            new SteamUser(id: 10L, steamId64: 'winner', banned: true))
        listingRepository.save(_) >> { Listing l -> l }
        bidRepository.saveAll(_) >> { List<Bid> bs -> bs }

        when:
        service.sweepExpired()

        then:
        // Both the banned winner's bid and the loser's bid are closed.
        winnerBid.status == 'LOST'
        loserBid.status == 'LOST'
        // Item returned to the seller.
        listing.status == 'SOLD'
        listing.buyerUserId == 99L
    }

    def "settle closes out every live bid when the winner can't pay (no orphaned WINNING rows)"() {
        // No-sale settle path: winner's balance dropped below their bid
        // between bid-time and settle-time. The item goes back to the
        // seller and every bid must flip to LOST.
        given:
        def now = System.currentTimeMillis()
        def listing = auctionListing(
            id: 100L, currentBid: new BigDecimal("50"), currentBidderId: 10L,
            seller: 99L, expiresAt: now - 1000L
        )
        listingRepository.findExpiredAuctions(_) >> [listing]
        def winnerBid = new Bid(id: 1L, listingId: 100L, bidderUserId: 10L,
            amount: new BigDecimal("50"), status: 'WINNING')
        def loserBid = new Bid(id: 2L, listingId: 100L, bidderUserId: 20L,
            amount: new BigDecimal("45"), status: 'WINNING')
        bidRepository.findByListing(100L) >> [winnerBid, loserBid]
        steamUserRepository.findById(10L) >> Optional.of(
            new SteamUser(id: 10L, steamId64: 'winner', banned: false))
        // Winner's wallet now holds only $5 — can't cover the $50 bid.
        walletRepository.findByUsername('steam_winner') >> new com.sboxmarket.model.Wallet(
            id: 500L, username: 'steam_winner', balance: new BigDecimal("5"))
        listingRepository.save(_) >> { Listing l -> l }
        bidRepository.saveAll(_) >> { List<Bid> bs -> bs }

        when:
        service.sweepExpired()

        then:
        winnerBid.status == 'LOST'
        loserBid.status == 'LOST'
        listing.status == 'SOLD'
        listing.buyerUserId == 99L
    }

    def "sweepEndingSoon filters banned users out of the recipient set (batch 320)"() {
        given:
        def listing = auctionListing(id: 100L, currentBid: new BigDecimal("15"),
                                     expiresAt: System.currentTimeMillis() + 5 * 60 * 1000L)
        listingRepository.findEndingSoonUnnotified(_, _) >> [listing]
        bidRepository.findByListing(100L) >> [
            new Bid(id: 1L, listingId: 100L, bidderUserId: 10L, amount: new BigDecimal("15")),
            new Bid(id: 2L, listingId: 100L, bidderUserId: 20L, amount: new BigDecimal("13"))
        ]
        watchlistAlertRepository.findActiveUserIdsForItem(1L) >> []
        // User 20 is banned. User 10 is clean.
        steamUserRepository.findAllById(_) >> [
            new SteamUser(id: 10L, steamId64: '1', banned: false),
            new SteamUser(id: 20L, steamId64: '2', banned: true)
        ]

        when:
        service.sweepEndingSoon()

        then:
        1 * notificationService.push(10L, 'AUCTION_ENDING', _, _, 100L, _)
        0 * notificationService.push(20L, _, _, _, _, _)
    }

    // ── OUTBID status lifecycle (bug #1) ───────────────────────────

    def "placeBid flips the displaced top bidder's WINNING row to OUTBID (bug #1)"() {
        // The OUTBID status was dead code: placeBid saved every new bid as
        // WINNING and never demoted the prior leader, so bid history showed
        // every bidder as WINNING forever. A plain manual outbid must flip
        // the previous top bidder's WINNING row to OUTBID.
        given:
        def listing = auctionListing(currentBid: new BigDecimal('20'),
            currentBidderId: 7L, bidCount: 1)
        def previousTop = new Bid(id: 5L, listingId: 100L, bidderUserId: 7L,
            bidderName: 'Bob', amount: new BigDecimal('20'),
            kind: 'MANUAL', status: 'WINNING')
        listingRepository.findById(_) >> Optional.of(listing)
        bidRepository.findByListing(100L) >> [previousTop]
        bidRepository.save(_) >> { Bid b -> b.id = 9L; b }
        bidRepository.saveAll(_) >> { List<Bid> bs -> bs }
        listingRepository.save(_) >> { Listing l -> l }

        when:
        def result = service.placeBid(10L, 'Alice', 100L, new BigDecimal('25'), null)

        then:
        // Alice's new bid is the live WINNING row.
        result.status == 'WINNING'
        result.bidderUserId == 10L
        // Bob's previously-winning row was demoted — no longer WINNING.
        previousTop.status == 'OUTBID'
        // The demotion is persisted.
        1 * bidRepository.saveAll({ List<Bid> bs ->
            bs.size() == 1 && bs[0].is(previousTop) && bs[0].status == 'OUTBID'
        })
    }

    def "placeBid self-raise leaves only the newest row WINNING, older one OUTBID (bug #1)"() {
        // When a bidder who already leads the auction self-raises, their
        // own older WINNING row must flip to OUTBID so exactly one row —
        // the newest bid — reads WINNING.
        given:
        def listing = auctionListing(currentBid: new BigDecimal('20'),
            currentBidderId: 10L, bidCount: 1)
        def ownOldBid = new Bid(id: 5L, listingId: 100L, bidderUserId: 10L,
            bidderName: 'Alice', amount: new BigDecimal('20'),
            kind: 'MANUAL', status: 'WINNING')
        listingRepository.findById(_) >> Optional.of(listing)
        // findByListing returns only the pre-existing row — the new bid is
        // created fresh inside placeBid and is excluded by object identity.
        bidRepository.findByListing(100L) >> [ownOldBid]
        bidRepository.save(_) >> { Bid b -> b.id = 9L; b }
        bidRepository.saveAll(_) >> { List<Bid> bs -> bs }
        listingRepository.save(_) >> { Listing l -> l }

        when:
        def result = service.placeBid(10L, 'Alice', 100L, new BigDecimal('25'), null)

        then:
        // Newest bid is WINNING.
        result.status == 'WINNING'
        result.amount == new BigDecimal('25')
        // The bidder's own older row is demoted — only one WINNING remains.
        ownOldBid.status == 'OUTBID'
        // No outbid notification — a self-raise doesn't displace anyone else.
        0 * notificationService.push(*_)
    }

    def "placeBid auto-raise demotes the prior leader's stale WINNING row (bug #1)"() {
        // Branch A (previous top re-wins via bot re-raise). The bot saves a
        // fresh WINNING row for the previous top; that bidder's OWN earlier
        // WINNING row must flip to OUTBID so only the bot's re-raise row
        // reads WINNING for that user (the new bidder's losing row is
        // demoted too — not asserted here as the mock returns a fixed set).
        given:
        def listing = auctionListing(currentBid: new BigDecimal('20'),
            currentBidderId: 7L, bidCount: 1)
        def previousTop = new Bid(id: 5L, listingId: 100L, bidderUserId: 7L,
            bidderName: 'Bob', amount: new BigDecimal('20'),
            maxAmount: new BigDecimal('50'), kind: 'AUTO', status: 'WINNING')
        listingRepository.findById(_) >> Optional.of(listing)
        bidRepository.findByListing(100L) >> [previousTop]
        steamUserRepository.findById(7L) >> Optional.of(new SteamUser(id: 7L, steamId64: 'SID7'))
        walletRepository.findByUsername('steam_SID7') >> new com.sboxmarket.model.Wallet(
            id: 77L, username: 'steam_SID7', balance: new BigDecimal('100.00'))
        def saved = []
        bidRepository.save(_) >> { Bid b -> b.id = (200L + saved.size()); saved << b; b }
        bidRepository.saveAll(_) >> { List<Bid> bs -> bs }
        listingRepository.save(_) >> { Listing l -> l }

        when:
        def result = service.placeBid(10L, 'Alice', 100L, new BigDecimal('25'), null)

        then:
        // Bot re-raise fired — the returned row is the AUTO winner for Bob.
        result.bidderUserId == 7L
        result.kind == 'AUTO'
        result.status == 'WINNING'
        // Bob's stale earlier row is demoted (the bot's new row is the
        // live WINNING one). previousTop is excluded from being kept since
        // it is not the bot-placed row.
        previousTop.status == 'OUTBID'
    }

    // ── settle: no orphaned WINNING rows (bug #2) ──────────────────

    def "settle flips ALL of the winner's non-terminal rows, only one WON (bug #2)"() {
        // The auto-bid bot saves a SECOND WINNING row for the same user on
        // a re-raise. Before the fix, settle's `bids.find { ... }` flipped
        // only one row to WON and the winner's other WINNING row stayed
        // live on the SOLD listing — so Profile → Active Bids showed the
        // closed auction as live forever. settle must now resolve EVERY
        // non-terminal row of the winner: the highest-amount one → WON,
        // the rest → OUTBID.
        given:
        def now = System.currentTimeMillis()
        def listing = auctionListing(
            id: 100L, currentBid: new BigDecimal('50'), currentBidderId: 10L,
            seller: 99L, expiresAt: now - 1000L)
        listingRepository.findExpiredAuctions(_) >> [listing]
        // Winner 10 has TWO live rows — the auto-bid double-row case:
        // an older manual WINNING bid and the newer bot AUTO WINNING bid.
        def winnerOld = new Bid(id: 1L, listingId: 100L, bidderUserId: 10L,
            amount: new BigDecimal('25'), kind: 'MANUAL', status: 'WINNING')
        def winnerTop = new Bid(id: 2L, listingId: 100L, bidderUserId: 10L,
            amount: new BigDecimal('50'), kind: 'AUTO', status: 'WINNING')
        // A losing bidder whose row was already demoted to OUTBID by an
        // earlier placeBid — it must still terminate at LOST.
        def loserBid = new Bid(id: 3L, listingId: 100L, bidderUserId: 20L,
            amount: new BigDecimal('30'), kind: 'MANUAL', status: 'OUTBID')
        bidRepository.findByListing(100L) >> [winnerTop, loserBid, winnerOld]
        def winner = new SteamUser(id: 10L, steamId64: 'winner', displayName: 'W', banned: false)
        steamUserRepository.findById(10L) >> Optional.of(winner)
        def winnerWallet = new com.sboxmarket.model.Wallet(
            id: 500L, username: 'steam_winner', balance: new BigDecimal('500'))
        walletRepository.findByUsername('steam_winner') >> winnerWallet
        walletRepository.save(_) >> { com.sboxmarket.model.Wallet w -> w }
        listingRepository.save(_) >> { Listing l -> l }
        bidRepository.save(_) >> { Bid b -> b }
        bidRepository.saveAll(_) >> { List<Bid> bs -> bs }
        def seller = new SteamUser(id: 99L, steamId64: 'seller')
        steamUserRepository.findById(99L) >> Optional.of(seller)
        def sellerWallet = new com.sboxmarket.model.Wallet(id: 501L, username: 'steam_seller', balance: BigDecimal.ZERO)
        walletRepository.findByUsername('steam_seller') >> sellerWallet

        when:
        service.sweepExpired()

        then:
        // Exactly one of the winner's rows is WON — the highest-amount one.
        winnerTop.status == 'WON'
        // The winner's other non-terminal row is resolved, NOT left WINNING.
        winnerOld.status == 'OUTBID'
        // No winner row lingers as WINNING on the SOLD listing.
        [winnerTop, winnerOld].count { it.status == 'WINNING' } == 0
        [winnerTop, winnerOld].count { it.status == 'WON' } == 1
        // The losing bidder's already-OUTBID row terminates at LOST.
        loserBid.status == 'LOST'
        // Listing sold to the winner.
        listing.status == 'SOLD'
        listing.buyerUserId == 10L
        // Loser gets exactly one AUCTION_LOST push.
        1 * notificationService.push(20L, 'AUCTION_LOST', _, _, 100L, _)
    }
}
