package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SellerFollow
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SellerFollowRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.EmailService
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.SellerFollowService
import com.sboxmarket.service.UserBlockService
import spock.lang.Specification
import spock.lang.Subject

class SellerFollowServiceSpec extends Specification {

    SellerFollowRepository repo                = Mock()
    SteamUserRepository    steamUserRepository = Mock()
    NotificationService    notificationService = Mock()
    ListingRepository      listingRepository   = Mock()
    UserBlockService       userBlockService    = Mock()
    EmailService           emailService        = Mock()

    @Subject
    SellerFollowService service = new SellerFollowService(
        repo:                repo,
        steamUserRepository: steamUserRepository,
        notificationService: notificationService,
        listingRepository:   listingRepository,
        userBlockService:    userBlockService,
        emailService:        emailService
    )

    private SteamUser seller(long id = 99L, boolean banned = false) {
        new SteamUser(id: id, steamId64: '111', displayName: 'Bob', banned: banned)
    }

    // ── follow ──────────────────────────────────────────────────

    def "follow creates a row and returns it"() {
        given:
        steamUserRepository.findById(99L) >> Optional.of(seller())
        repo.findByFollowerUserIdAndSellerUserId(42L, 99L) >> Optional.empty()
        repo.findByFollowerUserIdOrderByCreatedAtDesc(42L) >> []
        repo.save(_) >> { args -> args[0].id = 1L; args[0] }

        when:
        def row = service.follow(42L, 99L)

        then:
        row.followerUserId == 42L
        row.sellerUserId == 99L
    }

    def "follow pushes SELLER_FOLLOWED to the seller with the follower's name (batch 536)"() {
        given:
        def follower = new SteamUser(id: 42L, steamId64: '222', displayName: 'Alice')
        steamUserRepository.findById(99L) >> Optional.of(seller())
        steamUserRepository.findById(42L) >> Optional.of(follower)
        repo.findByFollowerUserIdAndSellerUserId(42L, 99L) >> Optional.empty()
        repo.findByFollowerUserIdOrderByCreatedAtDesc(42L) >> []
        repo.save(_) >> { args -> args[0].id = 1L; args[0] }
        repo.countBySeller(99L) >> 7L

        when:
        service.follow(42L, 99L)

        then:
        1 * notificationService.push(99L, 'SELLER_FOLLOWED',
            { String title -> title.contains('Alice') },
            { String body -> body.contains('Alice') && body.contains('7 followers') },
            42L,
            '/stall/99')
    }

    def "follow doesn't fail when notificationService push throws (batch 536)"() {
        given:
        steamUserRepository.findById(99L) >> Optional.of(seller())
        steamUserRepository.findById(42L) >> Optional.of(new SteamUser(id: 42L, displayName: 'Alice'))
        repo.findByFollowerUserIdAndSellerUserId(42L, 99L) >> Optional.empty()
        repo.findByFollowerUserIdOrderByCreatedAtDesc(42L) >> []
        repo.save(_) >> { args -> args[0].id = 1L; args[0] }
        repo.countBySeller(99L) >> 1L
        notificationService.push(_, _, _, _, _, _) >> { throw new RuntimeException('bell down') }

        when:
        def row = service.follow(42L, 99L)

        then:
        // Row still saved + returned despite the failed push
        row != null
        noExceptionThrown()
    }

    def "follow is idempotent — second click returns the existing row"() {
        given:
        def existing = new SellerFollow(id: 5L, followerUserId: 42L, sellerUserId: 99L)
        steamUserRepository.findById(99L) >> Optional.of(seller())
        repo.findByFollowerUserIdAndSellerUserId(42L, 99L) >> Optional.of(existing)

        when:
        def row = service.follow(42L, 99L)

        then:
        row.id == 5L
        0 * repo.save(_)
    }

    def "follow refuses self-follow"() {
        when:
        service.follow(42L, 42L)

        then:
        thrown(BadRequestException)
    }

    def "follow refuses a banned seller"() {
        given:
        steamUserRepository.findById(99L) >> Optional.of(seller(99L, true))

        when:
        service.follow(42L, 99L)

        then:
        thrown(BadRequestException)
    }

    def "follow refuses an unknown seller"() {
        given:
        steamUserRepository.findById(99L) >> Optional.empty()

        when:
        service.follow(42L, 99L)

        then:
        thrown(NotFoundException)
    }

    def "follow refuses when the user has hit the 200-follow cap"() {
        given:
        steamUserRepository.findById(99L) >> Optional.of(seller())
        repo.findByFollowerUserIdAndSellerUserId(42L, 99L) >> Optional.empty()
        repo.countByFollowerUserId(42L) >> 200L

        when:
        service.follow(42L, 99L)

        then:
        def ex = thrown(BadRequestException)
        ex.code == 'FOLLOW_LIMIT'
    }

    // ── notifyFollowersOfNewListing ─────────────────────────────

    def "notifyFollowersOfNewListing pushes one notification per follower"() {
        given:
        def item = new Item(id: 7L, name: 'Wizard Hat')
        def listing = new Listing(id: 100L, sellerUserId: 99L, item: item,
            price: new BigDecimal('12.50'), sellerName: 'Bob')
        repo.findBySellerUserId(99L) >> [
            new SellerFollow(followerUserId: 1L, sellerUserId: 99L),
            new SellerFollow(followerUserId: 2L, sellerUserId: 99L),
            new SellerFollow(followerUserId: 3L, sellerUserId: 99L)
        ]

        when:
        service.notifyFollowersOfNewListing(listing)

        then:
        1 * notificationService.push(1L, 'NEW_LISTING_FROM_SELLER', _, _, 100L, '/item/7')
        1 * notificationService.push(2L, 'NEW_LISTING_FROM_SELLER', _, _, 100L, '/item/7')
        1 * notificationService.push(3L, 'NEW_LISTING_FROM_SELLER', _, _, 100L, '/item/7')
    }

    def "notifyFollowersOfNewListing is a no-op when no one follows the seller"() {
        given:
        def item = new Item(id: 7L, name: 'Wizard Hat')
        def listing = new Listing(id: 100L, sellerUserId: 99L, item: item)
        repo.findBySellerUserId(99L) >> []

        when:
        service.notifyFollowersOfNewListing(listing)

        then:
        0 * notificationService.push(_, _, _, _, _, _)
    }

    def "notifyFollowersOfNewListing keeps going even when one push throws"() {
        given:
        def item = new Item(id: 7L, name: 'Wizard Hat')
        def listing = new Listing(id: 100L, sellerUserId: 99L, item: item,
            price: new BigDecimal('5'), sellerName: 'Bob')
        repo.findBySellerUserId(99L) >> [
            new SellerFollow(followerUserId: 1L, sellerUserId: 99L),
            new SellerFollow(followerUserId: 2L, sellerUserId: 99L)
        ]
        notificationService.push(1L, _, _, _, _, _) >> { throw new RuntimeException('boom') }

        when:
        service.notifyFollowersOfNewListing(listing)

        then:
        1 * notificationService.push(2L, 'NEW_LISTING_FROM_SELLER', _, _, _, _)
        noExceptionThrown()
    }

    def "notifyFollowersOfNewListing is a no-op when listing has no sellerUserId"() {
        given:
        def listing = new Listing(id: 100L, sellerUserId: null)

        when:
        service.notifyFollowersOfNewListing(listing)

        then:
        0 * repo.findBySellerUserId(_)
        0 * notificationService.push(_, _, _, _, _, _)
    }

    // ── unfollow + isFollowing ──────────────────────────────────

    def "unfollow delegates to the repository"() {
        when:
        service.unfollow(42L, 99L)

        then:
        1 * repo.deleteByFollowerUserIdAndSellerUserId(42L, 99L)
    }

    def "isFollowing is false for anonymous viewers"() {
        expect:
        !service.isFollowing(null, 99L)
        !service.isFollowing(42L, null)
    }

    // ── Per-follow mute (V36 / batch 279) ───────────────────────────

    def "setNotificationsMuted flips the flag and saves"() {
        given:
        def existing = new SellerFollow(id: 5L, followerUserId: 42L,
            sellerUserId: 99L, notificationsMuted: false)
        repo.findByFollowerUserIdAndSellerUserId(42L, 99L) >> Optional.of(existing)

        when:
        def out = service.setNotificationsMuted(42L, 99L, true)

        then:
        // The explicit `1 * ... >> existing` overrides the bare stub so
        // the closure-style return wires up correctly under Spock's
        // interaction matching.
        1 * repo.save(existing) >> existing
        out.notificationsMuted == true
        existing.notificationsMuted == true
    }

    def "setNotificationsMuted 404s when the follow doesn't exist"() {
        given:
        repo.findByFollowerUserIdAndSellerUserId(42L, 99L) >> Optional.empty()

        when:
        service.setNotificationsMuted(42L, 99L, true)

        then:
        thrown(NotFoundException)
        0 * repo.save(_)
    }

    def "notifyFollowersOfNewListing skips followers with notificationsMuted=true"() {
        given:
        // 3 followers: one loud, one muted, one default-loud (null treated as false).
        def listing = new Listing(
            id:            123L,
            sellerUserId:  99L,
            sellerName:    'Bob',
            price:         new BigDecimal('15.00'),
            item:          new Item(id: 7L, name: 'Wizard Hat', category: 'Hats')
        )
        repo.findBySellerUserId(99L) >> [
            new SellerFollow(id: 1L, followerUserId: 11L, sellerUserId: 99L, notificationsMuted: false),
            new SellerFollow(id: 2L, followerUserId: 12L, sellerUserId: 99L, notificationsMuted: true),
            new SellerFollow(id: 3L, followerUserId: 13L, sellerUserId: 99L, notificationsMuted: null)
        ]
        // No emails wired — push is the only side effect under test here.

        when:
        service.notifyFollowersOfNewListing(listing)

        then:
        1 * notificationService.push(11L, 'NEW_LISTING_FROM_SELLER', _, _, 123L, _)
        0 * notificationService.push(12L, _, _, _, _, _)
        1 * notificationService.push(13L, 'NEW_LISTING_FROM_SELLER', _, _, 123L, _)
    }

    def "notifyFollowersOfNewListing skips banned follower accounts (batch 314 bug fix)"() {
        // A banned user who follows a hot seller used to keep getting
        // NEW_LISTING_FROM_SELLER pings (the ban guard only covers
        // write paths). Now banned users are filtered at the fanout.
        given:
        def listing = new Listing(
            id: 123L, sellerUserId: 99L, sellerName: 'Bob',
            price: new BigDecimal('15.00'),
            item: new Item(id: 7L, name: 'Wizard Hat', category: 'Hats')
        )
        repo.findBySellerUserId(99L) >> [
            new SellerFollow(id: 1L, followerUserId: 11L, sellerUserId: 99L, notificationsMuted: false),
            new SellerFollow(id: 2L, followerUserId: 12L, sellerUserId: 99L, notificationsMuted: false)
        ]
        // User 12 is banned; user 11 is clean.
        steamUserRepository.findAllById([11L, 12L]) >> [
            new SteamUser(id: 11L, steamId64: '1', banned: false),
            new SteamUser(id: 12L, steamId64: '2', banned: true)
        ]

        when:
        service.notifyFollowersOfNewListing(listing)

        then:
        1 * notificationService.push(11L, 'NEW_LISTING_FROM_SELLER', _, _, _, _)
        0 * notificationService.push(12L, _, _, _, _, _)
    }

    def "notifyFollowersOfNewListing is a silent no-op when every follower is muted"() {
        given:
        def listing = new Listing(
            id:           123L, sellerUserId: 99L, sellerName: 'Bob',
            price:        new BigDecimal('15.00'),
            item:         new Item(id: 7L, name: 'Wizard Hat', category: 'Hats')
        )
        repo.findBySellerUserId(99L) >> [
            new SellerFollow(id: 1L, followerUserId: 11L, sellerUserId: 99L, notificationsMuted: true)
        ]

        when:
        service.notifyFollowersOfNewListing(listing)

        then:
        0 * notificationService.push(_, _, _, _, _, _)
    }

    // ── unfollowAll (batch 294) ───────────────────────────────────

    def "unfollowAll forwards to the repo bulk delete and returns the count"() {
        given:
        repo.deleteByFollower(10L) >> 5

        when:
        int n = service.unfollowAll(10L)

        then:
        n == 5
    }

    def "unfollowAll returns 0 for a user who follows nobody"() {
        given:
        repo.deleteByFollower(10L) >> 0

        when:
        int n = service.unfollowAll(10L)

        then:
        n == 0
    }

    def "unfollowAll short-circuits on null user id without hitting the repo"() {
        when:
        int n = service.unfollowAll(null)

        then:
        n == 0
        0 * repo.deleteByFollower(_)
    }

    // ── setAllMuted (batch 295) ───────────────────────────────────

    def "setAllMuted forwards to the bulk UPDATE and returns the count"() {
        given:
        repo.updateMutedForFollower(10L, true) >> 4

        when:
        int n = service.setAllMuted(10L, true)

        then:
        n == 4
    }

    def "setAllMuted can flip the flag back to false"() {
        given:
        repo.updateMutedForFollower(10L, false) >> 3

        when:
        int n = service.setAllMuted(10L, false)

        then:
        n == 3
    }

    def "setAllMuted returns 0 for a follow-nobody user"() {
        given:
        repo.updateMutedForFollower(10L, true) >> 0

        when:
        int n = service.setAllMuted(10L, true)

        then:
        n == 0
    }

    def "setAllMuted short-circuits on null user id without hitting the repo"() {
        when:
        int n = service.setAllMuted(null, true)

        then:
        n == 0
        0 * repo.updateMutedForFollower(_, _)
    }

    // ── notifyFollowersOfNewListing: blocked-follower skip (batch 345) ──

    def "notifyFollowersOfNewListing skips followers who have blocked the seller"() {
        // Follow and block are orthogonal — a user can follow a seller
        // then later block them without unfollowing. A blocked follower
        // must get neither the bell nor the email for the seller's new
        // listings (block trumps follow).
        given:
        def listing = new Listing(
            id: 123L, sellerUserId: 99L, sellerName: 'Bob',
            price: new BigDecimal('15.00'),
            item: new Item(id: 7L, name: 'Wizard Hat', category: 'Hats')
        )
        repo.findBySellerUserId(99L) >> [
            new SellerFollow(id: 1L, followerUserId: 11L, sellerUserId: 99L, notificationsMuted: false),
            new SellerFollow(id: 2L, followerUserId: 12L, sellerUserId: 99L, notificationsMuted: false)
        ]
        steamUserRepository.findAllById([11L, 12L]) >> [
            new SteamUser(id: 11L, steamId64: '1', banned: false),
            new SteamUser(id: 12L, steamId64: '2', banned: false)
        ]
        // User 12 blocked the seller; user 11 has not.
        userBlockService.isBlocked(11L, 99L) >> false
        userBlockService.isBlocked(12L, 99L) >> true

        when:
        service.notifyFollowersOfNewListing(listing)

        then:
        1 * notificationService.push(11L, 'NEW_LISTING_FROM_SELLER', _, _, _, _)
        0 * notificationService.push(12L, _, _, _, _, _)
    }

    def "notifyFollowersOfNewListing fails open when the block check throws"() {
        // A block-check error must NOT drop the fanout — the follower
        // still gets the ping (fail-open is the documented behavior).
        given:
        def listing = new Listing(
            id: 123L, sellerUserId: 99L, sellerName: 'Bob',
            price: new BigDecimal('15.00'),
            item: new Item(id: 7L, name: 'Wizard Hat', category: 'Hats')
        )
        repo.findBySellerUserId(99L) >> [
            new SellerFollow(id: 1L, followerUserId: 11L, sellerUserId: 99L, notificationsMuted: false)
        ]
        steamUserRepository.findAllById([11L]) >> [new SteamUser(id: 11L, steamId64: '1', banned: false)]
        userBlockService.isBlocked(11L, 99L) >> { throw new RuntimeException('block table down') }

        when:
        service.notifyFollowersOfNewListing(listing)

        then:
        1 * notificationService.push(11L, 'NEW_LISTING_FROM_SELLER', _, _, _, _)
        noExceptionThrown()
    }

    def "notifyFollowersOfNewListing skips a follower who is BOTH banned and blocked exactly once"() {
        // Belt-and-braces: the banned guard and the block guard both
        // independently exclude this follower — no double-push, no throw.
        given:
        def listing = new Listing(
            id: 123L, sellerUserId: 99L, sellerName: 'Bob',
            price: new BigDecimal('15.00'),
            item: new Item(id: 7L, name: 'Wizard Hat', category: 'Hats')
        )
        repo.findBySellerUserId(99L) >> [
            new SellerFollow(id: 1L, followerUserId: 12L, sellerUserId: 99L, notificationsMuted: false)
        ]
        steamUserRepository.findAllById([12L]) >> [new SteamUser(id: 12L, steamId64: '2', banned: true)]
        userBlockService.isBlocked(12L, 99L) >> true

        when:
        service.notifyFollowersOfNewListing(listing)

        then:
        0 * notificationService.push(_, _, _, _, _, _)
        0 * emailService.sendNewListingFromSeller(_, _, _, _, _, _)
    }

    // ── notifyFollowersOfNewListing: email fan-out ───────────────────

    def "notifyFollowersOfNewListing emails followers who opted in to the FOLLOWS bucket"() {
        given:
        def listing = new Listing(
            id: 123L, sellerUserId: 99L, sellerName: 'Bob',
            price: new BigDecimal('15.00'),
            item: new Item(id: 7L, name: 'Wizard Hat', category: 'Hats')
        )
        def follower = new SteamUser(id: 11L, steamId64: '1', banned: false,
            email: 'alice@example.com', displayName: 'Alice')
        repo.findBySellerUserId(99L) >> [
            new SellerFollow(id: 1L, followerUserId: 11L, sellerUserId: 99L, notificationsMuted: false)
        ]
        steamUserRepository.findAllById([11L]) >> [follower]
        emailService.canSendTo(follower, 'FOLLOWS') >> true

        when:
        service.notifyFollowersOfNewListing(listing)

        then:
        1 * emailService.sendNewListingFromSeller('alice@example.com', 'Alice', 'Bob',
            'Wizard Hat', new BigDecimal('15.00'), '/item/7')
    }

    def "notifyFollowersOfNewListing does not email a follower who muted the FOLLOWS bucket"() {
        given:
        def listing = new Listing(
            id: 123L, sellerUserId: 99L, sellerName: 'Bob',
            price: new BigDecimal('15.00'),
            item: new Item(id: 7L, name: 'Wizard Hat', category: 'Hats')
        )
        def follower = new SteamUser(id: 11L, steamId64: '1', banned: false,
            email: 'alice@example.com', displayName: 'Alice')
        repo.findBySellerUserId(99L) >> [
            new SellerFollow(id: 1L, followerUserId: 11L, sellerUserId: 99L, notificationsMuted: false)
        ]
        steamUserRepository.findAllById([11L]) >> [follower]
        emailService.canSendTo(follower, 'FOLLOWS') >> false

        when:
        service.notifyFollowersOfNewListing(listing)

        then: 'the bell still fires — only the email is gated on the bucket'
        1 * notificationService.push(11L, 'NEW_LISTING_FROM_SELLER', _, _, _, _)
        0 * emailService.sendNewListingFromSeller(_, _, _, _, _, _)
    }

    def "notifyFollowersOfNewListing keeps pushing when a follower email throws"() {
        given:
        def listing = new Listing(
            id: 123L, sellerUserId: 99L, sellerName: 'Bob',
            price: new BigDecimal('15.00'),
            item: new Item(id: 7L, name: 'Wizard Hat', category: 'Hats')
        )
        def f1 = new SteamUser(id: 11L, steamId64: '1', banned: false, email: 'a@x.com')
        def f2 = new SteamUser(id: 12L, steamId64: '2', banned: false, email: 'b@x.com')
        repo.findBySellerUserId(99L) >> [
            new SellerFollow(id: 1L, followerUserId: 11L, sellerUserId: 99L, notificationsMuted: false),
            new SellerFollow(id: 2L, followerUserId: 12L, sellerUserId: 99L, notificationsMuted: false)
        ]
        steamUserRepository.findAllById([11L, 12L]) >> [f1, f2]
        emailService.canSendTo(_, 'FOLLOWS') >> true
        emailService.sendNewListingFromSeller('a@x.com', _, _, _, _, _) >> { throw new RuntimeException('smtp down') }

        when:
        service.notifyFollowersOfNewListing(listing)

        then: 'follower 12 still gets both push and email despite 11 email failing'
        1 * notificationService.push(12L, 'NEW_LISTING_FROM_SELLER', _, _, _, _)
        1 * emailService.sendNewListingFromSeller('b@x.com', _, _, _, _, _)
        noExceptionThrown()
    }

    def "notifyFollowersOfNewListing degrades to push-only when the bulk user lookup throws"() {
        // When findAllById blows up the fanout must still push (it just
        // loses the ban check + email gate) rather than dropping entirely.
        given:
        def listing = new Listing(
            id: 123L, sellerUserId: 99L, sellerName: 'Bob',
            price: new BigDecimal('15.00'),
            item: new Item(id: 7L, name: 'Wizard Hat', category: 'Hats')
        )
        repo.findBySellerUserId(99L) >> [
            new SellerFollow(id: 1L, followerUserId: 11L, sellerUserId: 99L, notificationsMuted: false)
        ]
        steamUserRepository.findAllById([11L]) >> { throw new RuntimeException('db pool empty') }

        when:
        service.notifyFollowersOfNewListing(listing)

        then:
        1 * notificationService.push(11L, 'NEW_LISTING_FROM_SELLER', _, _, _, _)
        0 * emailService.sendNewListingFromSeller(_, _, _, _, _, _)
        noExceptionThrown()
    }

    // ── read paths: isFollowing / countFollowers / listFollowing ─────

    def "isFollowing is true when a follow row exists"() {
        given:
        repo.findByFollowerUserIdAndSellerUserId(42L, 99L) >>
            Optional.of(new SellerFollow(id: 1L, followerUserId: 42L, sellerUserId: 99L))

        expect:
        service.isFollowing(42L, 99L)
    }

    def "isFollowing is false when no follow row exists"() {
        given:
        repo.findByFollowerUserIdAndSellerUserId(42L, 99L) >> Optional.empty()

        expect:
        !service.isFollowing(42L, 99L)
    }

    def "countFollowers delegates to the repo seller count"() {
        given:
        repo.countBySeller(99L) >> 17L

        expect:
        service.countFollowers(99L) == 17L
    }

    def "listFollowing returns the follower's rows newest-first from the repo"() {
        given:
        def rows = [
            new SellerFollow(id: 2L, followerUserId: 42L, sellerUserId: 88L),
            new SellerFollow(id: 1L, followerUserId: 42L, sellerUserId: 77L)
        ]
        repo.findByFollowerUserIdOrderByCreatedAtDesc(42L) >> rows

        expect:
        service.listFollowing(42L) == rows
    }

    // ── feedForFollower ──────────────────────────────────────────────

    def "feedForFollower returns [] for an anonymous (null) user without hitting the repo"() {
        when:
        def out = service.feedForFollower(null, 20)

        then:
        out == []
        0 * repo.findSellerIdsByFollower(_)
        0 * listingRepository.findActiveVisibleBySellerIds(_, _)
    }

    def "feedForFollower returns [] when the user follows nobody"() {
        given:
        repo.findSellerIdsByFollower(42L) >> []

        when:
        def out = service.feedForFollower(42L, 20)

        then:
        out == []
        0 * listingRepository.findActiveVisibleBySellerIds(_, _)
    }

    def "feedForFollower queries active visible listings for every followed seller"() {
        given:
        def listing = new Listing(id: 5L, sellerUserId: 88L)
        repo.findSellerIdsByFollower(42L) >> [88L, 77L]
        listingRepository.findActiveVisibleBySellerIds([88L, 77L], _) >> [listing]

        when:
        def out = service.feedForFollower(42L, 20)

        then:
        out == [listing]
    }

    def "feedForFollower clamps an oversized limit to the 50-row hard cap"() {
        given:
        org.springframework.data.domain.Pageable seenPage = null
        repo.findSellerIdsByFollower(42L) >> [88L]
        1 * listingRepository.findActiveVisibleBySellerIds([88L], _) >> { args ->
            seenPage = args[1]
            []
        }

        when:
        service.feedForFollower(42L, 9999)

        then: 'a caller asking for 9999 rows is capped at 50'
        seenPage.pageSize == 50
    }
}
