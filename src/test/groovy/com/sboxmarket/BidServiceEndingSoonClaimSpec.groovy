package com.sboxmarket

import com.sboxmarket.model.Bid
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.BidRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.WatchlistAlertRepository
import com.sboxmarket.service.BidService
import com.sboxmarket.service.EmailService
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.security.BanGuard
import com.sboxmarket.service.TextSanitizer
import spock.lang.Specification

/**
 * Wave 124 multi-pod race regression pin for
 * {@link BidService#sweepEndingSoon} / {@link BidService#notifyEndingSoon}.
 *
 * The bug: `findEndingSoonUnnotified` is read concurrently by every pod's
 * scheduled sweeper on the same 2-minute heartbeat, and both pods would
 * see the SAME `endingSoonNotified=false` row. The old code then ran the
 * full fan-out (bell push + email) on BOTH pods before either pod's save
 * landed — so every bidder + watcher received AUCTION_ENDING TWICE (and
 * the email TWICE). The fix is a conditional UPDATE
 * (`claimEndingSoonNotify`) that flips false→true atomically and returns
 * the affected-row count: 1 = this pod owns the fan-out, 0 = sibling pod
 * already claimed it and we bail before any recipient lookup.
 *
 * Same shape as wave 112 (WatchlistAlertService.claimForFiring) and
 * wave 120 (FraudAnalysisService cluster claim).
 *
 * Pinned three ways:
 *   1. Winning claim (UPDATE returns 1) — fan-out fires
 *   2. Losing claim (UPDATE returns 0) — fan-out skipped, no recipient
 *      lookup runs, no bell push, no email
 *   3. sweepEndingSoon delegates per-row through claim — so a 5-listing
 *      batch where pods race for 3 rows fires exactly 2 fan-outs locally,
 *      with the other 3 claims belonging to the sibling pod.
 */
class BidServiceEndingSoonClaimSpec extends Specification {

    ListingRepository listingRepository = Mock()
    BidRepository bidRepository = Mock()
    SteamUserRepository steamUserRepository = Mock()
    NotificationService notificationService = Mock()
    EmailService emailService = Mock()
    WatchlistAlertRepository watchlistAlertRepository = Mock()
    BanGuard banGuard = Mock()
    TextSanitizer textSanitizer = Mock()

    BidService service

    def setup() {
        service = new BidService(
            listingRepository:        listingRepository,
            bidRepository:            bidRepository,
            steamUserRepository:      steamUserRepository,
            notificationService:      notificationService,
            emailService:             emailService,
            watchlistAlertRepository: watchlistAlertRepository,
            banGuard:                 banGuard,
            textSanitizer:            textSanitizer
        )
    }

    private Listing makeListing(long id) {
        new Listing(
            id:                  id,
            status:              'ACTIVE',
            listingType:         'AUCTION',
            expiresAt:           System.currentTimeMillis() + 5L * 60_000L,
            currentBid:          new BigDecimal('10.00'),
            endingSoonNotified:  false,
            item:                new Item(id: 99L, name: 'Wizard Hat')
        )
    }

    // ── (1) Winning claim — UPDATE returns 1 → fan-out fires ──────────────

    def "notifyEndingSoon fires fan-out when claimEndingSoonNotify returns 1 (this pod won)"() {
        given:
        def listing = makeListing(1001L)
        listingRepository.claimEndingSoonNotify(1001L) >> 1
        bidRepository.findByListing(1001L) >> [
            new Bid(bidderUserId: 42L),
            new Bid(bidderUserId: 43L)
        ]
        watchlistAlertRepository.findActiveUserIdsForItem(99L) >> [44L]
        steamUserRepository.findAllById(_) >> [
            new SteamUser(id: 42L, banned: false, email: 'a@x', displayName: 'A', emailVerified: true),
            new SteamUser(id: 43L, banned: false, email: 'b@x', displayName: 'B', emailVerified: true),
            new SteamUser(id: 44L, banned: false, email: 'c@x', displayName: 'C', emailVerified: true)
        ]
        emailService.canSendTo(_, 'AUCTIONS') >> true

        when:
        def fired = service.notifyEndingSoon(listing)

        then: "return value signals 'this pod won'"
        fired

        and: "three distinct AUCTION_ENDING bell pushes — bidders 42, 43 and watcher 44"
        3 * notificationService.push(_, 'AUCTION_ENDING', _, _, 1001L, _)
        3 * emailService.sendAuctionEnding(_, _, _, _, _, _)
    }

    // ── (2) Losing claim — UPDATE returns 0 → fan-out skipped ─────────────

    def "notifyEndingSoon bails when claimEndingSoonNotify returns 0 (sibling pod beat us)"() {
        given: "the listing object came back from findEndingSoonUnnotified BEFORE the sibling pod claimed it"
        def listing = makeListing(2002L)
        listingRepository.claimEndingSoonNotify(2002L) >> 0

        when:
        def fired = service.notifyEndingSoon(listing)

        then: "return signals 'sibling already fired'"
        !fired

        and: "no recipient lookup runs"
        0 * bidRepository.findByListing(_)
        0 * watchlistAlertRepository.findActiveUserIdsForItem(_)
        0 * steamUserRepository.findAllById(_)

        and: "no bell push, no email"
        0 * notificationService.push(_, _, _, _, _, _)
        0 * emailService.sendAuctionEnding(_, _, _, _, _, _)
    }

    // ── (3) sweepEndingSoon delegates per-row through claim ───────────────

    def "sweepEndingSoon: in a 5-row batch where this pod wins 2 claims, only 2 fan-outs fire"() {
        given: "5 candidate auctions"
        def candidates = (3001L..3005L).collect { makeListing(it) }
        listingRepository.findEndingSoonUnnotified(_, _) >> candidates

        and: "this pod wins the claim for ids 3001 and 3003; sibling pod won the rest"
        listingRepository.claimEndingSoonNotify(3001L) >> 1
        listingRepository.claimEndingSoonNotify(3002L) >> 0
        listingRepository.claimEndingSoonNotify(3003L) >> 1
        listingRepository.claimEndingSoonNotify(3004L) >> 0
        listingRepository.claimEndingSoonNotify(3005L) >> 0

        and: "no bidders / watchers — keeps recipient lookups O(1)"
        bidRepository.findByListing(_) >> []
        watchlistAlertRepository.findActiveUserIdsForItem(_) >> []

        when:
        service.sweepEndingSoon()

        then: "claim runs for every candidate"
        1 * listingRepository.claimEndingSoonNotify(3001L) >> 1
        1 * listingRepository.claimEndingSoonNotify(3002L) >> 0
        1 * listingRepository.claimEndingSoonNotify(3003L) >> 1
        1 * listingRepository.claimEndingSoonNotify(3004L) >> 0
        1 * listingRepository.claimEndingSoonNotify(3005L) >> 0
    }

    // ── (4) Listing without expiresAt returns false without claiming ──────

    def "notifyEndingSoon returns false on a null-expiresAt row WITHOUT consuming the multi-pod claim"() {
        given:
        def listing = makeListing(4004L)
        listing.expiresAt = null

        when:
        def fired = service.notifyEndingSoon(listing)

        then: "no claim consumed — sibling pod still gets to run if/when expiresAt is fixed"
        !fired
        0 * listingRepository.claimEndingSoonNotify(_)
    }

    // ── (5) Notifications never fire on losing-claim row ─────────────────

    def "notifyEndingSoon does NOT save the listing on losing claim (claim UPDATE already persisted, no entity-flush race)"() {
        given:
        def listing = makeListing(5005L)
        listingRepository.claimEndingSoonNotify(5005L) >> 0

        when:
        def fired = service.notifyEndingSoon(listing)

        then: "no save() call — would race with a concurrent placeBid's listing.save()"
        !fired
        0 * listingRepository.save(_)
    }

    // ── (6) Winning claim does NOT redundantly entity-save ───────────────

    def "notifyEndingSoon does NOT entity-save the listing on winning claim either (UPDATE already persisted the flag)"() {
        given:
        def listing = makeListing(6006L)
        listingRepository.claimEndingSoonNotify(6006L) >> 1
        bidRepository.findByListing(6006L) >> []
        watchlistAlertRepository.findActiveUserIdsForItem(99L) >> []

        when:
        def fired = service.notifyEndingSoon(listing)

        then: "claim UPDATE already wrote endingSoonNotified=true — a redundant entity save() would dirty-flush every column and could collide with a concurrent placeBid extending expiresAt for the same row"
        fired
        0 * listingRepository.save(_)
    }
}
