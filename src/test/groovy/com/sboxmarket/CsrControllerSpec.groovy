package com.sboxmarket

import com.sboxmarket.controller.CsrController
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.SupportMessage
import com.sboxmarket.model.SupportTicket
import com.sboxmarket.service.CsrService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import spock.lang.Specification
import spock.lang.Subject

/**
 * Coverage for the CSR staff panel endpoints. Every write endpoint
 * is role-gated via `CsrService.requireCsr(uid)` (which admins also
 * pass), so the tests here pin two things:
 *
 *   1. `/check` is the ONLY endpoint that does NOT 401 for anon —
 *      it's the frontend's "should I render the CSR menu entry"
 *      probe. Returns `{csr: false}` for unauthed and non-CSR users
 *      so the UI can fold the menu item away without a 403 branch.
 *
 *   2. `/users/{id}/goodwill` amount parser: null → INVALID_AMOUNT
 *      ('required'); non-numeric → INVALID_AMOUNT ('valid number').
 *      Zero + negative + range checks are enforced in CsrService
 *      (not controller), so those aren't directly tested here — the
 *      two branches that ARE in the controller are what this spec
 *      covers.
 *
 * Batch 1068 — added to close the coverage gap identified by walking
 * the controller list vs. the test directory.
 */
class CsrControllerSpec extends Specification {

    CsrService csrService = Mock()

    @Subject
    CsrController controller = new CsrController(csrService: csrService)

    HttpServletRequest req = Mock()
    HttpSession        ses = Mock()

    private void anonSession() {
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> null
    }
    private void csrSession(long uid = 100L) {
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> uid
        1 * csrService.requireCsr(uid)
    }

    // ── /check never 401s ──────────────────────────────────────

    def "check() returns {csr:false} for anon — never 401"() {
        given:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> null
        1 * csrService.isCsr(null) >> false

        when:
        def resp = controller.check(req)

        then: 'controller NEVER calls requireCsr on this path'
        0 * csrService.requireCsr(_)
        resp.body == [csr: false]
    }

    def "check() returns {csr:false} for a signed-in non-CSR user"() {
        given:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 100L
        1 * csrService.isCsr(100L) >> false

        when:
        def resp = controller.check(req)

        then:
        0 * csrService.requireCsr(_)
        resp.body == [csr: false]
    }

    def "check() returns {csr:true} for an actual CSR user"() {
        given:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 100L
        1 * csrService.isCsr(100L) >> true

        when:
        def resp = controller.check(req)

        then:
        resp.body == [csr: true]
    }

    // ── stats / lookup / tickets require CSR role ──────────────

    def "stats() requires sign-in"() {
        given: anonSession()
        when:  controller.stats(req)
        then:  thrown(UnauthorizedException)
        0 * csrService.requireCsr(_)
        0 * csrService.dashboardStats()
    }

    def "stats() gates on CSR role via requireCsr"() {
        given:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 100L
        1 * csrService.requireCsr(100L) >> { throw new UnauthorizedException('not CSR') }

        when:
        controller.stats(req)

        then:
        thrown(UnauthorizedException)
        0 * csrService.dashboardStats()
    }

    def "stats() returns the service's dashboard snapshot"() {
        given:
        def snap = [openTickets: 7, todaysTrades: 42]
        csrSession(100L)
        1 * csrService.dashboardStats() >> snap

        when:
        def resp = controller.stats(req)

        then:
        resp.body.is(snap)
    }

    def "lookup() passes the query through after CSR gate"() {
        given:
        def result = [id: 42L, name: 'alice']
        csrSession(100L)
        1 * csrService.lookupUser('alice@example.com') >> result

        when:
        def resp = controller.lookup('alice@example.com', req)

        then:
        resp.body.is(result)
    }

    def "tickets() passes status + search filter through"() {
        given:
        def rows = [[id: 1L], [id: 2L]]
        csrSession(100L)
        1 * csrService.listTickets('OPEN', 'hat') >> rows

        when:
        def resp = controller.tickets('OPEN', 'hat', req)

        then:
        resp.body.is(rows)
    }

    def "tickets() allows null filters (show everything)"() {
        given:
        csrSession(100L)
        1 * csrService.listTickets(null, null) >> []

        when:
        controller.tickets(null, null, req)

        then:
        true
    }

    def "getTicket() passes csr uid + ticket id through"() {
        given:
        def ticket = [id: 9L, subject: 'help']
        csrSession(100L)
        1 * csrService.getTicket(100L, 9L) >> ticket

        when:
        def resp = controller.getTicket(9L, req)

        then:
        resp.body.is(ticket)
    }

    // ── reply / close envelopes ───────────────────────────────

    def "reply() returns {id, body} projection"() {
        given:
        def msg = new SupportMessage(id: 99L, body: 'Thanks for waiting!')
        csrSession(100L)
        1 * csrService.reply(100L, 9L, 'Thanks for waiting!') >> msg

        when:
        def resp = controller.reply(9L, [body: 'Thanks for waiting!'], req)

        then:
        resp.body == [id: 99L, body: 'Thanks for waiting!']
    }

    def "close() returns {id, status}"() {
        given:
        def t = new SupportTicket(id: 9L, status: 'RESOLVED')
        csrSession(100L)
        1 * csrService.close(100L, 9L) >> t

        when:
        def resp = controller.close(9L, req)

        then:
        resp.body == [id: 9L, status: 'RESOLVED']
    }

    // ── goodwill amount parser ────────────────────────────────

    def "goodwill() rejects missing amount with INVALID_AMOUNT ('required')"() {
        given: csrSession(100L)

        when:
        controller.goodwill(200L, [note: 'n'], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_AMOUNT'
        e.message.contains('required')
        0 * csrService.issueGoodwillCredit(_, _, _, _)
    }

    def "goodwill() rejects non-numeric amount with INVALID_AMOUNT ('valid number')"() {
        given: csrSession(100L)

        when:
        controller.goodwill(200L, [amount: 'xyz', note: 'n'], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_AMOUNT'
        e.message.contains('valid number')
        0 * csrService.issueGoodwillCredit(_, _, _, _)
    }

    def "goodwill() parses stringified BigDecimal and passes through with note"() {
        given:
        def result = [credited: new BigDecimal('25.00'), userId: 200L]
        csrSession(100L)
        1 * csrService.issueGoodwillCredit(100L, 200L, new BigDecimal('25.00'), 'compensation') >> result

        when:
        def resp = controller.goodwill(200L, [amount: '25.00', note: 'compensation'], req)

        then:
        resp.body.is(result)
    }

    def "goodwill() requires CSR role"() {
        given:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 100L
        1 * csrService.requireCsr(100L) >> { throw new UnauthorizedException('not CSR') }

        when:
        controller.goodwill(200L, [amount: '10'], req)

        then:
        thrown(UnauthorizedException)
        0 * csrService.issueGoodwillCredit(_, _, _, _)
    }

    def "goodwill() requires sign-in"() {
        given: anonSession()
        when:  controller.goodwill(200L, [amount: '10'], req)
        then:  thrown(UnauthorizedException)
        0 * csrService.requireCsr(_)
        0 * csrService.issueGoodwillCredit(_, _, _, _)
    }

    // ── flag listing ──────────────────────────────────────────

    def "flag() passes reason through to the service"() {
        given:
        def result = [id: 9L, flagged: true]
        csrSession(100L)
        1 * csrService.flagListing(100L, 9L, 'fraud') >> result

        when:
        def resp = controller.flag(9L, [reason: 'fraud'], req)

        then:
        resp.body.is(result)
    }

    def "flag() tolerates null body (reason=null)"() {
        given:
        csrSession(100L)
        1 * csrService.flagListing(100L, 9L, null) >> [id: 9L]

        when:
        controller.flag(9L, null, req)

        then:
        true
    }

    def "flag() requires CSR role"() {
        given:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 100L
        1 * csrService.requireCsr(100L) >> { throw new UnauthorizedException('not CSR') }

        when:
        controller.flag(9L, [:], req)

        then:
        thrown(UnauthorizedException)
        0 * csrService.flagListing(_, _, _)
    }
}
