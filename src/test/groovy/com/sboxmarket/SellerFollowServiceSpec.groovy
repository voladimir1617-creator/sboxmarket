package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SellerFollow
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.SellerFollowRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.SellerFollowService
import spock.lang.Specification
import spock.lang.Subject

class SellerFollowServiceSpec extends Specification {

    SellerFollowRepository repo                = Mock()
    SteamUserRepository    steamUserRepository = Mock()
    NotificationService    notificationService = Mock()

    @Subject
    SellerFollowService service = new SellerFollowService(
        repo:                repo,
        steamUserRepository: steamUserRepository,
        notificationService: notificationService
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
        repo.findByFollowerUserIdOrderByCreatedAtDesc(42L) >> (1..200).collect {
            new SellerFollow(followerUserId: 42L, sellerUserId: it as Long)
        }

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
}
