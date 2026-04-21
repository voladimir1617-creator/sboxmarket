package com.sboxmarket

import com.sboxmarket.controller.ReviewController
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.Review
import com.sboxmarket.service.ReviewService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import spock.lang.Specification
import spock.lang.Subject

/**
 * Coverage for the review CRUD + helpful-vote + reply endpoints. The
 * review system is privacy-sensitive on two axes:
 *
 *   1. `leaveReview()` must not leak the reviewer's uid (fromUserId)
 *      in the success response. The seller's stall page lists
 *      reviews without author identities by design — the controller's
 *      response projection is the last defense against a schema leak.
 *
 *   2. `GET /user/{id}` uses a `private, max-age=60` cache because
 *      the per-row `viewerHasVoted` flag diverges per viewer. A
 *      shared cache there would poison the helpful-vote UI across
 *      users. Pinned explicitly here.
 *
 *   3. `GET /user/{id}/summary` is the opposite — viewer-agnostic
 *      aggregate, `public, max-age=120`.
 *
 * Plus the usual auth gating, field-specific validation codes for
 * `leaveReview`, and envelope shapes.
 *
 * Batch 1068 — added to close the coverage gap identified by walking
 * the controller list vs. the test directory.
 */
class ReviewControllerSpec extends Specification {

    ReviewService reviewService = Mock()

    @Subject
    ReviewController controller = new ReviewController(reviewService: reviewService)

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

    // ── leaveReview ──────────────────────────────────────────────

    def "leaveReview() requires sign-in"() {
        given: anonSession()
        when:  controller.leaveReview([tradeId: 1, rating: 5], req)
        then:  thrown(UnauthorizedException)
        0 * reviewService.leaveReview(_, _, _, _)
    }

    def "leaveReview() rejects non-numeric tradeId with INVALID_TRADE_ID"() {
        given: authedSession(100L)

        when:
        controller.leaveReview([tradeId: 'abc', rating: 5], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_TRADE_ID'
        0 * reviewService.leaveReview(_, _, _, _)
    }

    def "leaveReview() rejects non-numeric rating with INVALID_RATING"() {
        given: authedSession(100L)

        when:
        controller.leaveReview([tradeId: 1, rating: 'five'], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_RATING'
        0 * reviewService.leaveReview(_, _, _, _)
    }

    def "leaveReview() coerces stringified numbers and projects without leaking fromUserId"() {
        given:
        def review = new Review(
            id: 9L,
            fromUserId: 100L,       // the caller's own uid — must not leak
            toUserId: 200L,         // the seller being reviewed
            tradeId: 1L,
            rating: 5,
            comment: 'solid',
            createdAt: 1700_000_000_000L
        )
        authedSession(100L)
        1 * reviewService.leaveReview(100L, 1L, 5, 'solid') >> review

        when:
        def resp = controller.leaveReview([tradeId: '1', rating: '5', comment: 'solid'], req)

        then: 'exact field whitelist; fromUserId MUST NOT be in the response'
        resp.body.keySet() == ['id', 'toUserId', 'rating', 'comment', 'createdAt'] as Set
        resp.body.id == 9L
        resp.body.toUserId == 200L
        resp.body.rating == 5
        resp.body.comment == 'solid'
        resp.body.createdAt == 1700_000_000_000L
        !resp.body.containsKey('fromUserId')
    }

    def "leaveReview() with missing tradeId passes null through to the service (service raises)"() {
        given:
        authedSession(100L)
        // Service decides whether a null trade is valid (usually raises
        // NOT_FOUND); controller only rejects parse failures.
        1 * reviewService.leaveReview(100L, null, 5, null) >> { throw new RuntimeException('MISSING_TRADE') }

        when:
        controller.leaveReview([rating: 5], req)

        then:
        thrown(RuntimeException)
    }

    // ── forUser / summary / mine ──────────────────────────────

    def "forUser() is public-ish: uses the private/max-age=60 cache per viewer"() {
        given:
        def rows = [new Review(id: 1L)]
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 100L
        1 * reviewService.listForUser(200L) >> rows
        1 * reviewService.decorateWithHelpful(rows, 100L) >> [[id: 1L, viewerHasVoted: false]]

        when:
        def resp = controller.forUser(200L, req)

        then:
        def cc = resp.headers.getFirst('Cache-Control')
        cc?.contains('private')          // per-viewer — never shared CDN cache
        cc?.contains('max-age=60')
        resp.body[0].viewerHasVoted == false
    }

    def "forUser() accepts anon viewer (no viewerHasVoted truthiness)"() {
        given:
        def rows = [new Review(id: 1L)]
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> null
        1 * reviewService.listForUser(200L) >> rows
        1 * reviewService.decorateWithHelpful(rows, null) >> [[id: 1L, viewerHasVoted: false]]

        when:
        def resp = controller.forUser(200L, req)

        then: 'no 401; anon viewer still sees reviews with viewerHasVoted=false'
        resp.statusCode.value() == 200
    }

    def "summary() is viewer-agnostic and uses public/max-age=120 CDN cache"() {
        given:
        1 * reviewService.summaryForUser(200L) >> [count: 5L, average: 4.6d]

        when:
        def resp = controller.summary(200L)

        then: 'public shared cache — no viewer-specific fields in response'
        def cc = resp.headers.getFirst('Cache-Control')
        cc?.contains('public')
        cc?.contains('max-age=120')
        resp.body == [count: 5L, average: 4.6d]
    }

    def "mine() requires sign-in (authored review index is PII for the reviewer)"() {
        given: anonSession()
        when:  controller.mine(req)
        then:  thrown(UnauthorizedException)
        0 * reviewService.listAuthoredBy(_)
    }

    def "mine() lists reviews the caller has authored"() {
        given:
        def rows = [new Review(id: 1L, fromUserId: 100L)]
        authedSession(100L)
        1 * reviewService.listAuthoredBy(100L) >> rows

        when:
        def resp = controller.mine(req)

        then:
        resp.body.is(rows)
    }

    // ── eligible / pending ─────────────────────────────────────

    def "eligible() requires sign-in"() {
        given: anonSession()
        when:  controller.eligible(200L, req)
        then:  thrown(UnauthorizedException)
        0 * reviewService.eligibleTradesFor(_, _)
    }

    def "eligible() returns the service's list of reviewable trades"() {
        given:
        def rows = [[tradeId: 1, reviewed: false], [tradeId: 2, reviewed: true]]
        authedSession(100L)
        1 * reviewService.eligibleTradesFor(100L, 200L) >> rows

        when:
        def resp = controller.eligible(200L, req)

        then:
        resp.body.is(rows)
    }

    def "pending() returns {count, items} envelope"() {
        given:
        def rows = [[tradeId: 3], [tradeId: 4], [tradeId: 5]]
        authedSession(100L)
        1 * reviewService.pendingReviewsFor(100L) >> rows

        when:
        def resp = controller.pending(req)

        then:
        resp.body == [count: 3, items: rows]
    }

    def "pending() with zero rows returns {count:0, items:[]}"() {
        given:
        authedSession(100L)
        1 * reviewService.pendingReviewsFor(100L) >> []

        when:
        def resp = controller.pending(req)

        then:
        resp.body == [count: 0, items: []]
    }

    // ── delete ─────────────────────────────────────────────────

    def "delete() returns {id, status: DELETED}"() {
        given:
        authedSession(100L)
        1 * reviewService.deleteReview(100L, 9L)

        when:
        def resp = controller.delete(9L, req)

        then:
        resp.body == [id: 9L, status: 'DELETED']
    }

    def "delete() requires sign-in"() {
        given: anonSession()
        when:  controller.delete(9L, req)
        then:  thrown(UnauthorizedException)
        0 * reviewService.deleteReview(_, _)
    }

    // ── helpful vote ───────────────────────────────────────────

    def "toggleHelpful() returns {id, helpfulCount, viewerHasVoted}"() {
        given:
        authedSession(100L)
        1 * reviewService.toggleHelpful(100L, 9L) >> [helpfulCount: 7, viewerHasVoted: true]

        when:
        def resp = controller.toggleHelpful(9L, req)

        then:
        resp.body == [id: 9L, helpfulCount: 7, viewerHasVoted: true]
    }

    def "toggleHelpful() requires sign-in"() {
        given: anonSession()
        when:  controller.toggleHelpful(9L, req)
        then:  thrown(UnauthorizedException)
        0 * reviewService.toggleHelpful(_, _)
    }

    // ── seller reply ───────────────────────────────────────────

    def "reply() saves + projects to {id, sellerReply, sellerReplyAt}"() {
        given:
        def saved = new Review(id: 9L, sellerReply: 'Thanks!', sellerReplyAt: 1700L)
        authedSession(100L)
        1 * reviewService.replyToReview(100L, 9L, 'Thanks!') >> saved

        when:
        def resp = controller.reply(9L, [reply: 'Thanks!'], req)

        then:
        resp.body == [id: 9L, sellerReply: 'Thanks!', sellerReplyAt: 1700L]
    }

    def "reply() with missing body passes null through (service clears the reply)"() {
        given:
        def cleared = new Review(id: 9L, sellerReply: null, sellerReplyAt: null)
        authedSession(100L)
        1 * reviewService.replyToReview(100L, 9L, null) >> cleared

        when:
        def resp = controller.reply(9L, [:], req)

        then: 'null reply passes through — service clears the reply fields'
        resp.body.sellerReply == null
        resp.body.sellerReplyAt == null
    }

    def "reply() requires sign-in"() {
        given: anonSession()
        when:  controller.reply(9L, [reply: 'x'], req)
        then:  thrown(UnauthorizedException)
        0 * reviewService.replyToReview(_, _, _)
    }
}
