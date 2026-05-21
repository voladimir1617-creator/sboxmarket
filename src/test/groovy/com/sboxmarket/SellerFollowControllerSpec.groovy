package com.sboxmarket

import com.sboxmarket.controller.SellerFollowController
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SellerFollow
import com.sboxmarket.service.SellerFollowService
import com.sboxmarket.service.UserBlockService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import spock.lang.Specification
import spock.lang.Subject

/**
 * Coverage for the seller-follow endpoints. Two load-bearing pieces
 * land here for the first time:
 *
 * 1. The `{followerUserId}` column on the SellerFollow entity is the
 *    caller's own uid — it MUST NOT appear in the public-ish list /
 *    mute responses. Every projection is pinned to a whitelist.
 *
 * 2. The `/feed` block filter (batch 353). Block trumps follow:
 *    if the follower has blocked a seller they still follow, that
 *    seller's listings must be stripped from the feed. The earlier
 *    integration specs cover the happy path — here we lock down the
 *    filter behavior + the anon-viewer fast path (empty array, no 401).
 *
 * Batch 1068 — added to close the coverage gap identified by walking
 * the controller list vs. the test directory.
 */
class SellerFollowControllerSpec extends Specification {

    SellerFollowService service          = Mock()
    UserBlockService    userBlockService = Mock()

    @Subject
    SellerFollowController controller = new SellerFollowController(
        service          : service,
        userBlockService : userBlockService
    )

    HttpServletRequest req = Mock()
    HttpSession        ses = Mock()

    private void anonSession() {
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> null
    }
    private void authedSession(long uid = 100L) {
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> uid
    }

    def "list() requires sign-in"() {
        given: anonSession()
        when:  controller.list(req)
        then:  thrown(UnauthorizedException)
        0 * service.listFollowing(_)
    }

    def "list() projects each row to the whitelisted public shape"() {
        given:
        def row = new SellerFollow(
            id: 1L,
            followerUserId: 100L,       // the caller's own uid — must not leak
            sellerUserId: 200L,
            createdAt: 1700L,
            notificationsMuted: true
        )
        authedSession(100L)
        1 * service.listFollowing(100L) >> [row]

        when:
        def resp = controller.list(req)

        then: 'exactly 4 fields; no followerUserId ever'
        resp.body.size() == 1
        resp.body[0].keySet() == ['id', 'sellerUserId', 'createdAt', 'notificationsMuted'] as Set
        resp.body[0].sellerUserId == 200L
        resp.body[0].notificationsMuted == true
    }

    def "list() coerces null notificationsMuted to false"() {
        given:
        def row = new SellerFollow(id: 2L, sellerUserId: 201L, createdAt: 1L,
                                   notificationsMuted: null)
        authedSession(100L)
        1 * service.listFollowing(100L) >> [row]

        when:
        def resp = controller.list(req)

        then: 'client JSON should never carry a bare null here'
        resp.body[0].notificationsMuted == false
    }

    def "mute() rejects a body missing the 'muted' field with MISSING_FIELD"() {
        given: authedSession(100L)
        when:  controller.mute(200L, [:], req)
        then:
        def e = thrown(BadRequestException)
        e.code == 'MISSING_FIELD'
        0 * service.setNotificationsMuted(_, _, _)
    }

    def "mute() rejects a null body with MISSING_FIELD"() {
        given: authedSession(100L)
        when:  controller.mute(200L, null, req)
        then:
        def e = thrown(BadRequestException)
        e.code == 'MISSING_FIELD'
    }

    def "mute() flips the per-seller bell and returns the projected row"() {
        given:
        def row = new SellerFollow(sellerUserId: 200L, notificationsMuted: true)
        authedSession(100L)
        1 * service.setNotificationsMuted(100L, 200L, true) >> row

        when:
        def resp = controller.mute(200L, [muted: true], req)

        then:
        resp.body == [sellerUserId: 200L, notificationsMuted: true]
    }

    def "mute() coerces a string-encoded muted flag instead of Groovy-truthing it"() {
        given: 'JSON {"muted":"false"} arrives as a String — must un-mute, not mute'
        def row = new SellerFollow(sellerUserId: 200L, notificationsMuted: false)
        authedSession(100L)
        1 * service.setNotificationsMuted(100L, 200L, false) >> row

        when:
        def resp = controller.mute(200L, [muted: 'false'], req)

        then: 'a bare `"false" as Boolean` would have been true — this must be false'
        resp.body == [sellerUserId: 200L, notificationsMuted: false]
    }

    def "mute() rejects a non-boolean muted value with INVALID_FIELD"() {
        given: authedSession(100L)
        when:  controller.mute(200L, [muted: 'yes-please'], req)
        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_FIELD'
        0 * service.setNotificationsMuted(_, _, _)
    }

    def "muteAll() coerces a string-encoded muted flag (\"false\" un-mutes)"() {
        given:
        authedSession(100L)
        1 * service.setAllMuted(100L, false) >> 4

        when:
        def resp = controller.muteAll([muted: 'false'], req)

        then:
        resp.body == [touched: 4, muted: false]
    }

    def "follow() returns {id, sellerUserId, following:true}"() {
        given:
        def row = new SellerFollow(id: 9L, sellerUserId: 200L)
        authedSession(100L)
        1 * service.follow(100L, 200L) >> row

        when:
        def resp = controller.follow(200L, req)

        then:
        resp.body == [id: 9L, sellerUserId: 200L, following: true]
    }

    def "unfollow() returns {sellerUserId, following:false}"() {
        given:
        authedSession(100L)
        1 * service.unfollow(100L, 200L)

        when:
        def resp = controller.unfollow(200L, req)

        then:
        resp.body == [sellerUserId: 200L, following: false]
    }

    def "unfollowAll() returns {unfollowed: N}"() {
        given:
        authedSession(100L)
        1 * service.unfollowAll(100L) >> 3

        when:
        def resp = controller.unfollowAll(req)

        then:
        resp.body == [unfollowed: 3]
    }

    def "muteAll() flips every bell and reports touched count + muted state"() {
        given:
        authedSession(100L)
        1 * service.setAllMuted(100L, true) >> 5

        when:
        def resp = controller.muteAll([muted: true], req)

        then:
        resp.body == [touched: 5, muted: true]
    }

    def "muteAll() rejects missing body with MISSING_FIELD"() {
        given: authedSession(100L)

        when:
        controller.muteAll([:], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'MISSING_FIELD'
        0 * service.setAllMuted(_, _)
    }

    def "muteAll() authenticates BEFORE validating the body — anon caller gets 401, not a 400"() {
        given: 'an anonymous caller sends a body that would otherwise fail validation'
        anonSession()

        when: 'muted is garbage — a body-first order would throw INVALID_FIELD'
        controller.muteAll([muted: 'not-a-bool'], req)

        then: 'auth wins: 401, and the body is never validated or persisted'
        thrown(UnauthorizedException)
        0 * service.setAllMuted(_, _)
    }

    def "muteAll() requires sign-in even when the body is missing the field"() {
        given: anonSession()
        when:  controller.muteAll([:], req)
        then:  thrown(UnauthorizedException)
        0 * service.setAllMuted(_, _)
    }

    def "status() is public — anon viewer gets following:false without a 401"() {
        given:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> null
        1 * service.countFollowers(200L) >> 42

        when:
        def resp = controller.status(200L, req)

        then: 'never calls isFollowing — anon short-circuits before the per-user check'
        0 * service.isFollowing(_, _)
        resp.body == [sellerUserId: 200L, following: false, followerCount: 42]
    }

    def "status() surfaces the signed-in caller's follow state + the public follower count"() {
        given:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 100L
        1 * service.countFollowers(200L) >> 42
        1 * service.isFollowing(100L, 200L) >> true

        when:
        def resp = controller.status(200L, req)

        then:
        resp.body == [sellerUserId: 200L, following: true, followerCount: 42]
    }

    def "feed() returns [] for anon viewers — no 401, lets rail hide itself cleanly"() {
        given:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> null

        when:
        def resp = controller.feed(req)

        then: 'short-circuits before the service call'
        0 * service.feedForFollower(_, _)
        resp.body == []
    }

    def "feed() strips listings from sellers the follower has ALSO blocked (block > follow)"() {
        given:
        def l1 = new Listing(id: 1L, sellerUserId: 200L)
        def l2 = new Listing(id: 2L, sellerUserId: 201L) // this seller is blocked
        def l3 = new Listing(id: 3L, sellerUserId: 202L)
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 100L
        1 * service.feedForFollower(100L, 20) >> [l1, l2, l3]
        1 * userBlockService.blockedIdsFor(100L) >> [201L]

        when:
        def resp = controller.feed(req)

        then:
        resp.body*.id == [1L, 3L]
    }

    def "feed() preserves rows with a null sellerUserId even when block list is non-empty"() {
        given: 'pathological row: sellerUserId null (platform listing, orphaned row)'
        def platformListing = new Listing(id: 99L, sellerUserId: null)
        def regular         = new Listing(id: 1L, sellerUserId: 200L)
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 100L
        1 * service.feedForFollower(100L, 20) >> [platformListing, regular]
        1 * userBlockService.blockedIdsFor(100L) >> [999L]

        when:
        def resp = controller.feed(req)

        then: 'null sellerUserId cannot be in the blocked set, so it survives'
        resp.body*.id == [99L, 1L]
    }
}
