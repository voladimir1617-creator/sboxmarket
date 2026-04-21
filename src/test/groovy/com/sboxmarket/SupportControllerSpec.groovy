package com.sboxmarket

import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.controller.SupportController
import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.SupportMessage
import com.sboxmarket.model.SupportTicket
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.SupportService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import spock.lang.Specification
import spock.lang.Subject

/**
 * Coverage for the support-ticket surface. Every endpoint is session-
 * gated — no public window into anyone else's ticket queue. The
 * user-on-user report endpoint has three load-bearing invariants we
 * pin down here:
 *
 *   1. Self-report → BadRequestException(SELF_REPORT). A user can't
 *      open a fraud ticket against themselves (would poison the CSR
 *      queue with self-DoS).
 *   2. Non-existent target → NotFoundException("SteamUser", id).
 *      Prevents enumeration of user ids via the report endpoint.
 *   3. Reason + context strings are clipped to 80 / 1000 chars
 *      before being templated into the ticket body — a 100 kB
 *      "Context" payload can't inflate the CSR queue or the DB row.
 *
 * Batch 1068 — added to close the coverage gap identified by walking
 * the controller list vs. the test directory.
 */
class SupportControllerSpec extends Specification {

    SupportService      supportService      = Mock()
    SteamUserRepository steamUserRepository = Mock()

    @Subject
    SupportController controller = new SupportController(
        supportService     : supportService,
        steamUserRepository: steamUserRepository
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

    // ── auth gating ─────────────────────────────────────────────

    def "list() requires sign-in"() {
        given: anonSession()
        when:  controller.list(req)
        then:  thrown(UnauthorizedException)
        0 * supportService.listForUser(_)
    }

    def "get() requires sign-in"() {
        given: anonSession()
        when:  controller.get(1L, req)
        then:  thrown(UnauthorizedException)
        0 * supportService.getTicket(_, _)
    }

    def "create() requires sign-in"() {
        given: anonSession()
        when:  controller.create([subject: 'x', category: 'BUG', body: 'y'], req)
        then:  thrown(UnauthorizedException)
        0 * supportService.create(_, _, _, _, _)
    }

    def "resolve() requires sign-in"() {
        given: anonSession()
        when:  controller.resolve(1L, req)
        then:  thrown(UnauthorizedException)
        0 * supportService.resolve(_, _)
    }

    def "reopen() requires sign-in"() {
        given: anonSession()
        when:  controller.reopen(1L, req)
        then:  thrown(UnauthorizedException)
        0 * supportService.reopen(_, _)
    }

    def "reportUser() requires sign-in"() {
        given: anonSession()
        when:  controller.reportUser(200L, [reason: 'x'], req)
        then:  thrown(UnauthorizedException)
        0 * supportService.create(_, _, _, _, _)
    }

    // ── list / get / resolve / reopen pass-through ──────────────

    def "list() returns the service's ticket list unchanged"() {
        given:
        def rows = [new SupportTicket(id: 1L), new SupportTicket(id: 2L)]
        authedSession(100L)
        1 * supportService.listForUser(100L) >> rows

        when:
        def resp = controller.list(req)

        then:
        resp.body.is(rows)
    }

    def "get() returns the service's ticket detail unchanged"() {
        given:
        def ticket = [id: 1L, subject: 'help', messages: []]
        authedSession(100L)
        1 * supportService.getTicket(100L, 1L) >> ticket

        when:
        def resp = controller.get(1L, req)

        then:
        resp.body.is(ticket)
    }

    def "resolve() passes uid + ticketId through"() {
        given:
        def row = new SupportTicket(id: 9L, status: 'RESOLVED')
        authedSession(100L)
        1 * supportService.resolve(100L, 9L) >> row

        when:
        def resp = controller.resolve(9L, req)

        then:
        resp.body.is(row)
    }

    def "reopen() passes uid + ticketId through"() {
        given:
        def row = new SupportTicket(id: 9L, status: 'WAITING_STAFF')
        authedSession(100L)
        1 * supportService.reopen(100L, 9L) >> row

        when:
        def resp = controller.reopen(9L, req)

        then:
        resp.body.is(row)
    }

    // ── create ───────────────────────────────────────────────────

    def "create() resolves the display name + delegates to the service"() {
        given:
        def user = new SteamUser(id: 100L, displayName: 'alice')
        def ticket = new SupportTicket(id: 9L, subject: 'help plz', category: 'BUG')
        authedSession(100L)
        1 * steamUserRepository.findById(100L) >> Optional.of(user)
        1 * supportService.create(100L, 'alice', 'help plz', 'BUG', 'body text') >> ticket

        when:
        def resp = controller.create([subject: 'help plz', category: 'BUG', body: 'body text'], req)

        then:
        resp.body.is(ticket)
    }

    def "create() falls back to 'Player' when the user has no displayName"() {
        given:
        def user = new SteamUser(id: 100L, displayName: null)
        authedSession(100L)
        1 * steamUserRepository.findById(100L) >> Optional.of(user)
        1 * supportService.create(100L, 'Player', 'x', 'BUG', 'y') >> new SupportTicket()

        when:
        controller.create([subject: 'x', category: 'BUG', body: 'y'], req)

        then:
        true  // mock expectation verifies the 'Player' fallback
    }

    def "create() raises UnauthorizedException when the session uid has no SteamUser row"() {
        given:
        authedSession(100L)
        1 * steamUserRepository.findById(100L) >> Optional.empty()

        when:
        controller.create([subject: 'x'], req)

        then:
        thrown(UnauthorizedException)
        0 * supportService.create(_, _, _, _, _)
    }

    // ── reply ────────────────────────────────────────────────────

    def "reply() resolves the display name + delegates"() {
        given:
        def user = new SteamUser(id: 100L, displayName: 'alice')
        def msg = new SupportMessage(id: 1L)
        authedSession(100L)
        1 * steamUserRepository.findById(100L) >> Optional.of(user)
        1 * supportService.reply(100L, 'alice', 9L, 'thanks!') >> msg

        when:
        def resp = controller.reply(9L, [body: 'thanks!'], req)

        then:
        resp.body.is(msg)
    }

    // ── reportUser ──────────────────────────────────────────────

    def "reportUser() rejects self-report with SELF_REPORT"() {
        given: authedSession(100L)

        when: 'uid 100 tries to report uid 100'
        controller.reportUser(100L, [reason: 'x'], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'SELF_REPORT'
        0 * steamUserRepository.findById(_)
        0 * supportService.create(_, _, _, _, _)
    }

    def "reportUser() raises NotFound when target user doesn't exist"() {
        given:
        authedSession(100L)
        1 * steamUserRepository.findById(999L) >> Optional.empty()

        when:
        controller.reportUser(999L, [reason: 'harassment'], req)

        then:
        thrown(NotFoundException)
        0 * supportService.create(_, _, _, _, _)
    }

    def "reportUser() raises Unauthorized when reporter's own row is missing"() {
        given:
        def target = new SteamUser(id: 200L, displayName: 'bob', steamId64: '765611198...')
        authedSession(100L)
        1 * steamUserRepository.findById(200L) >> Optional.of(target)
        1 * steamUserRepository.findById(100L) >> Optional.empty()

        when:
        controller.reportUser(200L, [reason: 'harassment'], req)

        then:
        thrown(UnauthorizedException)
        0 * supportService.create(_, _, _, _, _)
    }

    def "reportUser() clips reason to 80 chars and context to 1000"() {
        given:
        def target = new SteamUser(id: 200L, displayName: 'bob', steamId64: '7656...')
        def me     = new SteamUser(id: 100L, displayName: 'alice')
        String capturedSubject = null
        String capturedBody    = null
        authedSession(100L)
        1 * steamUserRepository.findById(200L) >> Optional.of(target)
        1 * steamUserRepository.findById(100L) >> Optional.of(me)
        1 * supportService.create(100L, 'alice', _ as String, 'FRAUD', _ as String) >> { args ->
            capturedSubject = args[2]
            capturedBody    = args[4]
            new SupportTicket()
        }

        when: "reason is 200 chars, context is 5000 chars"
        controller.reportUser(200L,
            [reason: 'x' * 200, context: 'y' * 5000], req)

        then: 'reason appears in the subject, clipped to 80 chars'
        capturedSubject.contains('x' * 80)
        !capturedSubject.contains('x' * 81)

        and: 'context in the body clipped to 1000 chars'
        capturedBody.contains('y' * 1000)
        !capturedBody.contains('y' * 1001)
    }

    def "reportUser() templates the body with reporter + target identities"() {
        given:
        def target = new SteamUser(id: 200L, displayName: 'bob', steamId64: '76561199...')
        def me     = new SteamUser(id: 100L, displayName: 'alice')
        String capturedBody = null
        authedSession(100L)
        1 * steamUserRepository.findById(200L) >> Optional.of(target)
        1 * steamUserRepository.findById(100L) >> Optional.of(me)
        1 * supportService.create(_, _, _, 'FRAUD', _) >> { args ->
            capturedBody = args[4]
            new SupportTicket()
        }

        when:
        controller.reportUser(200L, [reason: 'harassment', context: 'DM chain screenshot'], req)

        then:
        capturedBody.contains('Reporter: alice (#100)')
        capturedBody.contains('Target:   bob (#200)')
        capturedBody.contains('76561199')
        capturedBody.contains('Reason:   harassment')
        capturedBody.contains('DM chain screenshot')
    }

    def "reportUser() defaults to 'Not specified' + '(none)' when fields omitted"() {
        given:
        def target = new SteamUser(id: 200L, displayName: 'bob', steamId64: '7656')
        def me     = new SteamUser(id: 100L, displayName: 'alice')
        String capturedSubject = null
        String capturedBody    = null
        authedSession(100L)
        1 * steamUserRepository.findById(200L) >> Optional.of(target)
        1 * steamUserRepository.findById(100L) >> Optional.of(me)
        1 * supportService.create(_, _, _, 'FRAUD', _) >> { args ->
            capturedSubject = args[2]
            capturedBody    = args[4]
            new SupportTicket()
        }

        when:
        controller.reportUser(200L, [:], req)

        then:
        capturedSubject.contains('Not specified')
        capturedBody.contains('(none)')
    }

    def "reportUser() category is always FRAUD (pins CSR routing)"() {
        given:
        def target = new SteamUser(id: 200L, steamId64: '7656')
        def me     = new SteamUser(id: 100L, displayName: 'alice')
        authedSession(100L)
        1 * steamUserRepository.findById(200L) >> Optional.of(target)
        1 * steamUserRepository.findById(100L) >> Optional.of(me)
        1 * supportService.create(100L, 'alice', _ as String, 'FRAUD', _ as String) >> new SupportTicket()

        when:
        controller.reportUser(200L, [reason: 'scam'], req)

        then: 'never landed under a different category (BUG / OTHER etc.)'
        true
    }
}
