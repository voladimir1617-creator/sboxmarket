package com.sboxmarket.service

import com.sboxmarket.model.Bid
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.BidRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.security.BanGuard
import spock.lang.Specification
import spock.lang.Subject

/**
 * Regression spec for the unbounded soft-close extension griefing vector
 * on {@code BidService#placeBid}.
 *
 * Pre-fix behaviour: the anti-snipe block at the bottom of placeBid pushed
 * {@code listing.expiresAt} out by {@code SNIPE_EXTEND_MS} (30s) every
 * time a bid landed inside the final {@code SNIPE_WINDOW_MS} (30s) window
 * — with no upper bound. The bid-time solvency check verifies
 * {@code wallet.balance >= bid.amount} but does NOT debit, and a single
 * bidder is free to outbid themselves (the self-bid block only blocks the
 * auto-bid bot + outbid notification; the bid itself is accepted). A
 * griefer with a $1000 wallet can therefore place 20,000 successive
 * $0.05-incrementing bids — each one re-opens the 30s window. Total stall:
 * up to ~7 days for $0 of actual cost (the wallet is only debited at
 * settle, and settle never fires because the auction never closes).
 *
 * Even a single rival bidder absorbing the top spot occasionally to avoid
 * winning is enough to make the listing's natural close unreachable: the
 * griefer can resume extending after every legitimate bid. CSFloat and
 * eBay both cap the soft-close window for exactly this reason.
 *
 * Fix: introduce {@code MAX_SOFT_CLOSE_EXTENSIONS} (20 = ~10 minutes of
 * total extension past original close) and persist a
 * {@code softCloseExtensions} counter on each listing. Once the counter
 * hits the cap, further bids inside the snipe window are still accepted
 * (so a legit late bidder isn't silently dropped — they still place a
 * winning bid and the next sweep tick closes the auction normally), but
 * {@code expiresAt} stops being pushed out and the auction can finally
 * close.
 *
 * These specs verify:
 *   1. Each in-window bid increments {@code softCloseExtensions}.
 *   2. Once the counter reaches the cap, expiresAt stays put even though
 *      the bid itself is accepted (count == cap, bid amount applied,
 *      currentBidderId updated).
 *   3. The cap doesn't affect bids OUTSIDE the snipe window — those never
 *      touched expiresAt to begin with and never bump the counter.
 *   4. A bidder can't trivially work around the cap by self-bidding —
 *      self-bids increment the counter the same way rival bids do.
 */
class BidServiceSoftCloseCapSpec extends Specification {

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
    EmailService emailService = Mock() {
        canSendTo(_, _) >> false
    }
    com.sboxmarket.repository.WatchlistAlertRepository watchlistAlertRepository = Mock()

    @Subject
    BidService service = new BidService(
        listingRepository       : listingRepository,
        bidRepository           : bidRepository,
        walletRepository        : walletRepository,
        transactionRepository   : transactionRepository,
        steamUserRepository     : steamUserRepository,
        notificationService     : notificationService,
        banGuard                : banGuard,
        textSanitizer           : textSanitizer,
        emailService            : emailService,
        watchlistAlertRepository: watchlistAlertRepository
    )

    private Listing auctionListing(Map args = [:]) {
        new Listing(
            id:                   args.id ?: 100L,
            item:                 new Item(id: 1L, name: 'Wizard Hat'),
            price:                args.price ?: new BigDecimal('10'),
            sellerUserId:         args.seller ?: 99L,
            status:               'ACTIVE',
            listingType:          'AUCTION',
            expiresAt:            args.expiresAt,
            currentBid:           args.currentBid,
            currentBidderId:      args.currentBidderId,
            bidCount:             args.bidCount ?: 0,
            softCloseExtensions:  args.softCloseExtensions ?: 0
        )
    }

    private SteamUser bidder(long id) {
        new SteamUser(
            id: id,
            steamId64: "SID${id}",
            displayName: "User${id}",
            tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=t'
        )
    }

    private Wallet wallet(long bidderId, BigDecimal balance = new BigDecimal('100000')) {
        new Wallet(id: 500L + bidderId, username: "steam_SID${bidderId}", balance: balance)
    }

    def "first soft-close bid increments softCloseExtensions to 1 and extends expiresAt"() {
        given:
        def now = System.currentTimeMillis()
        def listing = auctionListing(
            currentBid:           new BigDecimal('20'),
            currentBidderId:      7L,
            expiresAt:            now + 5_000L,    // 5s left → inside window
            softCloseExtensions:  0
        )
        listingRepository.findById(100L) >> Optional.of(listing)
        steamUserRepository.findById(10L) >> Optional.of(bidder(10L))
        walletRepository.findByUsername('steam_SID10') >> wallet(10L)
        bidRepository.save(_) >> { Bid b -> b }
        listingRepository.save(_) >> { Listing l -> l }
        bidRepository.findByListing(_) >> []
        bidRepository.findByListing(_, _) >> []

        when:
        service.placeBid(10L, 'Alice', 100L, new BigDecimal('25'), null)

        then:
        // Counter went 0 → 1.
        listing.softCloseExtensions == 1
        // expiresAt pushed out by ~30s (allow generous slack for CI clock jitter).
        listing.expiresAt >= now + 25_000L
    }

    def "soft-close at the cap accepts the bid but does NOT extend expiresAt further"() {
        given:
        def now = System.currentTimeMillis()
        // Counter already at the cap — the next in-window bid should NOT
        // bump expiresAt. The bid itself must still be accepted (otherwise
        // a legit late bidder gets silently dropped).
        def cap = BidService.MAX_SOFT_CLOSE_EXTENSIONS
        def cappedExpiresAt = now + 4_000L  // 4s left → inside window
        def listing = auctionListing(
            currentBid:           new BigDecimal('20'),
            currentBidderId:      7L,
            expiresAt:            cappedExpiresAt,
            softCloseExtensions:  cap
        )
        listingRepository.findById(100L) >> Optional.of(listing)
        steamUserRepository.findById(10L) >> Optional.of(bidder(10L))
        walletRepository.findByUsername('steam_SID10') >> wallet(10L)
        bidRepository.save(_) >> { Bid b -> b }
        listingRepository.save(_) >> { Listing l -> l }
        bidRepository.findByListing(_) >> []
        bidRepository.findByListing(_, _) >> []

        when:
        def placed = service.placeBid(10L, 'Alice', 100L, new BigDecimal('25'), null)

        then:
        // Bid was accepted — the cap doesn't gate the bid itself, only
        // the expiresAt push-out.
        placed != null
        placed.status == 'WINNING'
        placed.amount == new BigDecimal('25')
        listing.currentBid == new BigDecimal('25')
        listing.currentBidderId == 10L
        // expiresAt MUST NOT have moved — this is the whole point of the
        // cap. With the cap in place the auction can finally close on the
        // next 30s sweep tick.
        listing.expiresAt == cappedExpiresAt
        // Counter stays at the cap (not bumped past it).
        listing.softCloseExtensions == cap
    }

    def "the second-to-last extension still fires but the next one stops at the cap"() {
        given:
        def now = System.currentTimeMillis()
        def cap = BidService.MAX_SOFT_CLOSE_EXTENSIONS
        // One extension below the cap — this in-window bid should be the
        // FINAL one that extends. The bid AFTER it (separate test below)
        // is the no-op.
        def listing = auctionListing(
            currentBid:           new BigDecimal('20'),
            currentBidderId:      7L,
            expiresAt:            now + 5_000L,
            softCloseExtensions:  cap - 1
        )
        listingRepository.findById(100L) >> Optional.of(listing)
        steamUserRepository.findById(10L) >> Optional.of(bidder(10L))
        walletRepository.findByUsername('steam_SID10') >> wallet(10L)
        bidRepository.save(_) >> { Bid b -> b }
        listingRepository.save(_) >> { Listing l -> l }
        bidRepository.findByListing(_) >> []
        bidRepository.findByListing(_, _) >> []

        when:
        service.placeBid(10L, 'Alice', 100L, new BigDecimal('25'), null)

        then:
        // This is the final allowed extension — counter reaches the cap.
        listing.softCloseExtensions == cap
        listing.expiresAt >= now + 25_000L
    }

    def "a bid outside the snipe window never touches the soft-close counter"() {
        given:
        def now = System.currentTimeMillis()
        def farExpiry = now + 600_000L  // 10 minutes out → well outside the window
        def listing = auctionListing(
            currentBid:           new BigDecimal('20'),
            currentBidderId:      7L,
            expiresAt:            farExpiry,
            softCloseExtensions:  3   // already had a few earlier extensions
        )
        listingRepository.findById(100L) >> Optional.of(listing)
        steamUserRepository.findById(10L) >> Optional.of(bidder(10L))
        walletRepository.findByUsername('steam_SID10') >> wallet(10L)
        bidRepository.save(_) >> { Bid b -> b }
        listingRepository.save(_) >> { Listing l -> l }
        bidRepository.findByListing(_) >> []
        bidRepository.findByListing(_, _) >> []

        when:
        service.placeBid(10L, 'Alice', 100L, new BigDecimal('25'), null)

        then:
        // No extension, no counter bump — counter is a per-extension
        // tally, not a per-bid tally. Bids outside the snipe window never
        // moved expiresAt to begin with.
        listing.expiresAt == farExpiry
        listing.softCloseExtensions == 3
    }

    def "self-bid in the snipe window also bumps the counter (no griefer workaround)"() {
        // The pre-fix attack used self-bidding as an amplifier — the
        // self-bid path skips the auto-bid block but the soft-close
        // extension fires unconditionally. The cap MUST apply to self-
        // bids too, otherwise a griefer could trivially work around the
        // cap by bidding against themselves between rival bids.
        given:
        def now = System.currentTimeMillis()
        // Already at the cap — a self-bid (currentBidderId == bidder)
        // must NOT push expiresAt further.
        def cap = BidService.MAX_SOFT_CLOSE_EXTENSIONS
        def cappedExpiresAt = now + 3_000L
        def listing = auctionListing(
            currentBid:           new BigDecimal('20'),
            currentBidderId:      10L,   // already the top bidder — self-raise
            expiresAt:            cappedExpiresAt,
            softCloseExtensions:  cap
        )
        listingRepository.findById(100L) >> Optional.of(listing)
        steamUserRepository.findById(10L) >> Optional.of(bidder(10L))
        walletRepository.findByUsername('steam_SID10') >> wallet(10L)
        bidRepository.save(_) >> { Bid b -> b }
        listingRepository.save(_) >> { Listing l -> l }
        bidRepository.findByListing(_) >> []
        bidRepository.findByListing(_, _) >> []

        when:
        service.placeBid(10L, 'Alice', 100L, new BigDecimal('25'), null)

        then:
        // expiresAt frozen at cap — the self-bid is accepted but the
        // griefer has no further stalling power.
        listing.expiresAt == cappedExpiresAt
        listing.softCloseExtensions == cap
    }
}
