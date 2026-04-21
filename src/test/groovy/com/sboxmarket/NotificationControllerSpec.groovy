package com.sboxmarket

import com.sboxmarket.controller.NotificationController
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.Notification
import com.sboxmarket.service.NotificationService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import spock.lang.Specification
import spock.lang.Subject

/**
 * Coverage for the notifications surface. The controller itself is
 * thin — the interesting pieces are:
 *
 *   - `?limit=N` clamp on `/list`: bell drops to 12 rows (hand-tuned
 *     after batch 1026 when 100-row polls became 85% of the bell's
 *     bandwidth), modal stays at 100. Out-of-range values fall back
 *     to 100 so a crafted `?limit=-5` can't DoS the endpoint.
 *
 *   - Batch scopes (`/read-batch`, `/delete-batch`) — these drive the
 *     "Mark visible read" / "Clear visible read" buttons on the full
 *     notifications modal. The client sends the post-filter visible
 *     ids; the controller must tolerate non-Collection bodies, drop
 *     malformed tokens without 400ing the whole request, and return
 *     `{flipped / deleted: N}` counts.
 *
 *   - Auth gates on every endpoint; no public view into anyone's
 *     notification stream.
 *
 * Batch 1068 — added to close the coverage gap identified by walking
 * the controller list vs. the test directory.
 */
class NotificationControllerSpec extends Specification {

    NotificationService notificationService = Mock()

    @Subject
    NotificationController controller = new NotificationController(
        notificationService: notificationService
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

    // ── list ────────────────────────────────────────────────────

    def "list() requires sign-in"() {
        given: anonSession()
        when:  controller.list(req, null)
        then:  thrown(UnauthorizedException)
        0 * notificationService.listFor(_, _)
        0 * notificationService.countUnread(_)
    }

    def "list() without ?limit falls back to the 100-row cap"() {
        given:
        def rows = [new Notification(id: 1L)]
        authedSession(100L)
        1 * notificationService.listFor(100L, 100) >> rows
        1 * notificationService.countUnread(100L) >> 5L

        when:
        def resp = controller.list(req, null)

        then:
        resp.body == [items: rows, unread: 5L]
    }

    def "list() with ?limit=12 serves the nav-bell cheap fetch"() {
        given:
        authedSession(100L)
        1 * notificationService.listFor(100L, 12) >> []
        1 * notificationService.countUnread(100L) >> 0L

        when:
        def resp = controller.list(req, 12)

        then:
        resp.body == [items: [], unread: 0L]
    }

    def "list() clamps an absurdly large ?limit to 100"() {
        given:
        authedSession(100L)
        1 * notificationService.listFor(100L, 100) >> []
        1 * notificationService.countUnread(100L) >> 0L

        when:
        controller.list(req, 999_999)

        then:
        true  // mock expectation verifies the clamp to 100
    }

    def "list() clamps negative / zero ?limit up to 1 (no DoS via ?limit=0)"() {
        given:
        authedSession(100L)
        1 * notificationService.listFor(100L, 1) >> []
        1 * notificationService.countUnread(100L) >> 0L

        when:
        controller.list(req, -5)

        then:
        true
    }

    // ── unreadCount ─────────────────────────────────────────────

    def "unreadCount() returns the cheap COUNT the bell polls"() {
        given:
        authedSession(100L)
        1 * notificationService.countUnread(100L) >> 7L

        when:
        def resp = controller.unreadCount(req)

        then: 'no full-list fetch, just the count'
        0 * notificationService.listFor(_, _)
        resp.body == [unread: 7L]
    }

    def "unreadCount() requires sign-in"() {
        given: anonSession()
        when:  controller.unreadCount(req)
        then:  thrown(UnauthorizedException)
    }

    // ── read / unread / read-all ────────────────────────────────

    def "read() delegates and returns {ok:true}"() {
        given:
        authedSession(100L)
        1 * notificationService.markRead(100L, 9L)

        when:
        def resp = controller.read(9L, req)

        then:
        resp.body == [ok: true]
    }

    def "unread() delegates and returns {ok:true}"() {
        given:
        authedSession(100L)
        1 * notificationService.markUnread(100L, 9L)

        when:
        def resp = controller.unread(9L, req)

        then:
        resp.body == [ok: true]
    }

    def "readAll() delegates and returns {ok:true}"() {
        given:
        authedSession(100L)
        1 * notificationService.markAllRead(100L)

        when:
        def resp = controller.readAll(req)

        then:
        resp.body == [ok: true]
    }

    // ── readBatch / deleteBatch ─────────────────────────────────

    def "readBatch() rejects a missing ids list gracefully with {flipped:0}"() {
        given: authedSession(100L)

        when:
        def resp = controller.readBatch([:], req)

        then: 'non-Collection ids short-circuits to 0 — no service call, no 400'
        0 * notificationService.markReadByIds(_, _)
        resp.body == [flipped: 0]
    }

    def "readBatch() rejects a non-Collection ids (string) without throwing"() {
        given: authedSession(100L)

        when:
        def resp = controller.readBatch([ids: 'not-a-list'], req)

        then:
        0 * notificationService.markReadByIds(_, _)
        resp.body == [flipped: 0]
    }

    def "readBatch() parses ids + drops malformed tokens + returns service flip count"() {
        given:
        List<Long> capturedIds = null
        authedSession(100L)
        1 * notificationService.markReadByIds(100L, _) >> { args ->
            capturedIds = args[1]
            3
        }

        when: 'mixed ids: valid Long, stringified Long, null, malformed'
        def resp = controller.readBatch([ids: [1L, '2', null, 'x', 3]], req)

        then:
        capturedIds == [1L, 2L, 3L]
        resp.body == [flipped: 3]
    }

    def "readBatch() requires sign-in"() {
        given: anonSession()
        when:  controller.readBatch([ids: [1L]], req)
        then:  thrown(UnauthorizedException)
        0 * notificationService.markReadByIds(_, _)
    }

    def "deleteBatch() rejects missing ids gracefully with {deleted:0}"() {
        given: authedSession(100L)

        when:
        def resp = controller.deleteBatch([:], req)

        then:
        0 * notificationService.deleteReadByIds(_, _)
        resp.body == [deleted: 0]
    }

    def "deleteBatch() parses ids + returns service count"() {
        given:
        authedSession(100L)
        1 * notificationService.deleteReadByIds(100L, [1L, 2L]) >> 2

        when:
        def resp = controller.deleteBatch([ids: [1L, '2']], req)

        then:
        resp.body == [deleted: 2]
    }

    def "clearRead() returns {deleted: N}"() {
        given:
        authedSession(100L)
        1 * notificationService.deleteAllRead(100L) >> 12

        when:
        def resp = controller.clearRead(req)

        then:
        resp.body == [deleted: 12]
    }

    def "clearRead() requires sign-in"() {
        given: anonSession()
        when:  controller.clearRead(req)
        then:  thrown(UnauthorizedException)
    }

    def "deleteOne() returns {ok:true, id}"() {
        given:
        authedSession(100L)
        1 * notificationService.deleteOne(100L, 42L)

        when:
        def resp = controller.deleteOne(42L, req)

        then:
        resp.body == [ok: true, id: 42L]
    }

    def "deleteOne() is silently a no-op for foreign/unknown ids (service handles)"() {
        given:
        authedSession(100L)
        // Service's deleteOne is the ownership check — controller just forwards
        1 * notificationService.deleteOne(100L, 9_999_999L)

        when:
        def resp = controller.deleteOne(9_999_999L, req)

        then: 'client gets the same 200 regardless — no enumeration of ownership'
        resp.body.ok == true
        resp.body.id == 9_999_999L
    }
}
