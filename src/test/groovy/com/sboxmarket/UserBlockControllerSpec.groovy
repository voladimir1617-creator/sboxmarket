package com.sboxmarket

import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.controller.UserBlockController
import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ConflictException
import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.UserBlock
import com.sboxmarket.service.UserBlockService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import spock.lang.Specification
import spock.lang.Subject

/**
 * Coverage for the per-viewer block-list surface. Every endpoint is
 * session-gated — there's no public view into who has blocked whom.
 *
 *   GET    /api/profile/blocks        — list w/ count + items envelope
 *   POST   /api/profile/blocks/{id}   — block (idempotent in the service)
 *   DELETE /api/profile/blocks/{id}   — unblock (returns removed count)
 *   DELETE /api/profile/blocks        — unblock everything
 *
 * The response shapes the frontend depends on:
 *   list    → { count, items }
 *   block   → { blockedUserId, createdAt }   (never leaks blockerUserId)
 *   unblock → { removed: 0|1 }
 *
 * Batch 1068 — added to close the coverage gap identified by walking
 * the controller list vs. the test directory.
 */
class UserBlockControllerSpec extends Specification {

    UserBlockService userBlockService = Mock()

    @Subject
    UserBlockController controller = new UserBlockController(
        userBlockService: userBlockService
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

    def "list() requires sign-in — anon viewers never see the block list"() {
        given: anonSession()
        when:  controller.list(req)
        then:  thrown(UnauthorizedException)
        0 * userBlockService.listBlocked(_)
    }

    def "list() returns {count, items} envelope from the service"() {
        given:
        def items = [
            [blockedUserId: 200L, name: 'spammer-a', blockedAt: 1700L],
            [blockedUserId: 201L, name: 'spammer-b', blockedAt: 1701L]
        ]
        authedSession(100L)
        1 * userBlockService.listBlocked(100L) >> items

        when:
        def resp = controller.list(req)

        then:
        resp.statusCode.value() == 200
        resp.body.count == 2
        resp.body.items.is(items)
    }

    def "list() envelope counts an empty list cleanly"() {
        given:
        authedSession(100L)
        1 * userBlockService.listBlocked(100L) >> []

        when:
        def resp = controller.list(req)

        then:
        resp.body == [count: 0, items: []]
    }

    def "block() requires sign-in"() {
        given: anonSession()
        when:  controller.block(200L, req)
        then:  thrown(UnauthorizedException)
        0 * userBlockService.block(_, _)
    }

    def "block() projects to {blockedUserId, createdAt} — never leaks blockerUserId"() {
        given:
        def row = new UserBlock(
            id: 9L,
            blockerUserId: 100L,   // sensitive — must not leak in response
            blockedUserId: 200L,
            createdAt: 1700_000_000_000L
        )
        authedSession(100L)
        1 * userBlockService.block(100L, 200L) >> row

        when:
        def resp = controller.block(200L, req)

        then:
        resp.body.keySet() == ['blockedUserId', 'createdAt'] as Set
        resp.body.blockedUserId == 200L
        resp.body.createdAt == 1700_000_000_000L
        !resp.body.containsKey('blockerUserId')
        !resp.body.containsKey('id')
    }

    def "block() rejects a null target id with INVALID_BLOCK once the caller is authed"() {
        given:
        authedSession(100L)

        when:
        controller.block(null, req)

        then:
        BadRequestException e = thrown()
        e.code == 'INVALID_BLOCK'
        // Guard fires in the controller — the service is never reached.
        0 * userBlockService.block(_, _)
    }

    def "block() maps a null service result to 409 — never a 500 NPE"() {
        given: 'the service idempotent re-read loses a concurrent unblock race and returns null'
        authedSession(100L)
        1 * userBlockService.block(100L, 200L) >> null

        when:
        controller.block(200L, req)

        then: 'the controller surfaces a clean conflict instead of dereferencing null'
        ConflictException e = thrown()
        e.code == 'BLOCK_CONFLICT'
    }

    def "block() forwards the authed caller's uid as the blocker, not anything client-supplied"() {
        given:
        def row = new UserBlock(id: 1L, blockerUserId: 555L, blockedUserId: 200L, createdAt: 1700L)
        authedSession(555L)

        when:
        controller.block(200L, req)

        then: 'blocker id comes from the session uid, blocked id from the path'
        1 * userBlockService.block(555L, 200L) >> row
    }

    def "unblock() returns {removed: N} envelope"() {
        given:
        authedSession(100L)
        1 * userBlockService.unblock(100L, 200L) >> 1

        when:
        def resp = controller.unblock(200L, req)

        then:
        resp.body == [removed: 1]
    }

    def "unblock() returns {removed: 0} idempotently when target wasn't blocked"() {
        given:
        authedSession(100L)
        1 * userBlockService.unblock(100L, 999L) >> 0

        when:
        def resp = controller.unblock(999L, req)

        then:
        resp.body == [removed: 0]
    }

    def "unblock() forwards the authed caller's uid as the blocker — never a client value"() {
        given: 'a signed-in caller; the blocker id must come from the session, not the path'
        authedSession(555L)

        when:
        controller.unblock(200L, req)

        then: 'session uid is the blocker; only the target id is path-supplied'
        1 * userBlockService.unblock(555L, 200L) >> 1
    }

    def "unblock() requires sign-in"() {
        given: anonSession()
        when:  controller.unblock(200L, req)
        then:  thrown(UnauthorizedException)
        0 * userBlockService.unblock(_, _)
    }

    def "unblockAll() wipes every block the caller owns"() {
        given:
        authedSession(100L)
        1 * userBlockService.unblockAll(100L) >> 7

        when:
        def resp = controller.unblockAll(req)

        then:
        resp.body == [removed: 7]
    }

    def "unblockAll() requires sign-in"() {
        given: anonSession()
        when:  controller.unblockAll(req)
        then:  thrown(UnauthorizedException)
        0 * userBlockService.unblockAll(_)
    }
}
