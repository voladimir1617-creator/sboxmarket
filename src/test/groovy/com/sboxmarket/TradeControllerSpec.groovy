package com.sboxmarket

import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.controller.TradeController
import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.Trade
import com.sboxmarket.model.TradeMessage
import com.sboxmarket.service.TradeService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import spock.lang.Specification
import spock.lang.Subject

/**
 * Coverage for the escrow trade-state endpoints. Two load-bearing
 * pieces get special attention:
 *
 *   1. `get()` enforces participant visibility at the controller
 *      too — TradeService's state-push methods already gate access,
 *      but a plain GET has to forbid non-participants so a scanner
 *      can't enumerate trades by id.
 *
 *   2. Reason / message length caps: `/dispute` and `/cancel` share
 *      the private `capReason()` which throws REASON_TOO_LONG at
 *      >2000 chars. `/messages` has EMPTY_MESSAGE + TOO_LONG as two
 *      distinct codes so the SPA renders a field-specific error.
 *      `/sent` optional `tradeOfferUrl` falls through to null cleanly
 *      for legacy clients.
 *
 *   3. `mine()` carries X-Total-Count header so the Trades tab can
 *      show "Showing most recent 200 of N" on power users.
 *
 * Batch 1068 — added to close the coverage gap identified by walking
 * the controller list vs. the test directory.
 */
class TradeControllerSpec extends Specification {

    TradeService tradeService = Mock()

    @Subject
    TradeController controller = new TradeController(tradeService: tradeService)

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

    // ── mine() ───────────────────────────────────────────────────

    def "mine() requires sign-in"() {
        given: anonSession()
        when:  controller.mine(req)
        then:  thrown(UnauthorizedException)
        0 * tradeService.listForUserWithCounterparty(_)
    }

    def "mine() returns rows + X-Total-Count header"() {
        given:
        def rows = [[id: 1L], [id: 2L]]
        authedSession(100L)
        1 * tradeService.listForUserWithCounterparty(100L) >> rows
        1 * tradeService.countForUser(100L) >> 350L

        when:
        def resp = controller.mine(req)

        then:
        resp.body.is(rows)
        resp.headers.getFirst('X-Total-Count') == '350'
    }

    // ── get() participant gate ─────────────────────────────────

    def "get() requires sign-in"() {
        given: anonSession()
        when:  controller.get(1L, req)
        then:  thrown(UnauthorizedException)
        0 * tradeService.get(_)
    }

    def "get() returns the trade when caller is the buyer"() {
        given:
        def t = new Trade(id: 9L, buyerUserId: 100L, sellerUserId: 200L)
        authedSession(100L)
        1 * tradeService.get(9L) >> t

        when:
        def resp = controller.get(9L, req)

        then:
        resp.body.is(t)
    }

    def "get() returns the trade when caller is the seller"() {
        given:
        def t = new Trade(id: 9L, buyerUserId: 100L, sellerUserId: 200L)
        authedSession(200L)
        1 * tradeService.get(9L) >> t

        when:
        def resp = controller.get(9L, req)

        then:
        resp.body.is(t)
    }

    def "get() forbids non-participants (enumeration guard)"() {
        given:
        def t = new Trade(id: 9L, buyerUserId: 100L, sellerUserId: 200L)
        authedSession(999L)   // neither buyer nor seller
        1 * tradeService.get(9L) >> t

        when:
        controller.get(9L, req)

        then:
        thrown(ForbiddenException)
    }

    // ── accept / confirm — thin pass-through ───────────────────

    def "accept() forwards to sellerAccept"() {
        given:
        def t = new Trade(id: 9L, buyerUserId: 100L, sellerUserId: 200L)
        authedSession(200L)
        1 * tradeService.sellerAccept(200L, 9L) >> t

        when:
        def resp = controller.accept(9L, req)

        then:
        resp.body.is(t)
    }

    def "accept() requires sign-in"() {
        given: anonSession()
        when:  controller.accept(9L, req)
        then:  thrown(UnauthorizedException)
        0 * tradeService.sellerAccept(_, _)
    }

    def "confirm() forwards to buyerConfirm"() {
        given:
        def t = new Trade(id: 9L)
        authedSession(100L)
        1 * tradeService.buyerConfirm(100L, 9L) >> t

        when:
        def resp = controller.confirm(9L, req)

        then:
        resp.body.is(t)
    }

    // ── markSent — optional tradeOfferUrl ──────────────────────

    def "markSent() passes tradeOfferUrl through when supplied"() {
        given:
        def t = new Trade(id: 9L)
        authedSession(200L)
        1 * tradeService.sellerMarkSent(200L, 9L, 'https://steamcommunity.com/tradeoffer/1234') >> t

        when:
        controller.markSent(9L, [tradeOfferUrl: 'https://steamcommunity.com/tradeoffer/1234'], req)

        then:
        true
    }

    def "markSent() with null body passes null url (legacy client compat)"() {
        given:
        def t = new Trade(id: 9L)
        authedSession(200L)
        1 * tradeService.sellerMarkSent(200L, 9L, null) >> t

        when:
        controller.markSent(9L, null, req)

        then:
        true
    }

    def "markSent() with empty-map body passes null url"() {
        given:
        def t = new Trade(id: 9L)
        authedSession(200L)
        1 * tradeService.sellerMarkSent(200L, 9L, null) >> t

        when:
        controller.markSent(9L, [:], req)

        then:
        true
    }

    def "markSent() requires sign-in"() {
        given: anonSession()
        when:  controller.markSent(9L, null, req)
        then:  thrown(UnauthorizedException)
        0 * tradeService.sellerMarkSent(_, _, _)
    }

    // ── dispute / cancel — reason cap ──────────────────────────

    def "dispute() passes reason through when within the 2000-char limit"() {
        given:
        def t = new Trade(id: 9L)
        authedSession(100L)
        1 * tradeService.dispute(100L, 9L, 'item never arrived') >> t

        when:
        controller.dispute(9L, [reason: 'item never arrived'], req)

        then:
        true
    }

    def "dispute() raises REASON_TOO_LONG when reason > 2000 chars"() {
        // capReason() fires BEFORE requireUser — session never read.
        when:
        controller.dispute(9L, [reason: 'x' * 2001], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'REASON_TOO_LONG'
        0 * req.session
        0 * tradeService.dispute(_, _, _)
    }

    def "dispute() with null body passes null reason through"() {
        given:
        def t = new Trade(id: 9L)
        authedSession(100L)
        1 * tradeService.dispute(100L, 9L, null) >> t

        when:
        controller.dispute(9L, null, req)

        then:
        true
    }

    def "dispute() at exactly 2000 chars is accepted (bound inclusive)"() {
        given:
        def t = new Trade(id: 9L)
        authedSession(100L)
        1 * tradeService.dispute(100L, 9L, 'x' * 2000) >> t

        when:
        controller.dispute(9L, [reason: 'x' * 2000], req)

        then:
        true
    }

    def "cancel() passes reason through and forwards to service"() {
        given:
        def t = new Trade(id: 9L)
        authedSession(100L)
        1 * tradeService.cancel(100L, 9L, 'changed my mind') >> t

        when:
        def resp = controller.cancel(9L, [reason: 'changed my mind'], req)

        then:
        resp.body.is(t)
    }

    def "cancel() raises REASON_TOO_LONG when reason > 2000 chars"() {
        // capReason() fires BEFORE requireUser — session never read.
        when:
        controller.cancel(9L, [reason: 'y' * 2001], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'REASON_TOO_LONG'
        0 * req.session
        0 * tradeService.cancel(_, _, _)
    }

    def "cancel() with null body passes null reason"() {
        given:
        authedSession(100L)
        1 * tradeService.cancel(100L, 9L, null) >> new Trade(id: 9L)

        when:
        controller.cancel(9L, null, req)

        then:
        true
    }

    // ── messages ────────────────────────────────────────────────

    def "messages() lists messages for a trade participant"() {
        given:
        def msgs = [new TradeMessage(id: 1L), new TradeMessage(id: 2L)]
        authedSession(100L)
        1 * tradeService.listMessages(9L, 100L) >> msgs

        when:
        def resp = controller.messages(9L, req)

        then:
        resp.body.is(msgs)
    }

    def "messages() requires sign-in"() {
        given: anonSession()
        when:  controller.messages(9L, req)
        then:  thrown(UnauthorizedException)
        0 * tradeService.listMessages(_, _)
    }

    def "postMessage() rejects missing body with EMPTY_MESSAGE"() {
        // Body validation fires BEFORE requireUser — session never read.
        when:
        controller.postMessage(9L, [:], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'EMPTY_MESSAGE'
        0 * req.session
        0 * tradeService.postMessage(_, _, _)
    }

    def "postMessage() rejects whitespace-only body with EMPTY_MESSAGE"() {
        when:
        controller.postMessage(9L, [body: '   '], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'EMPTY_MESSAGE'
        0 * tradeService.postMessage(_, _, _)
    }

    def "postMessage() rejects body > 2000 chars with TOO_LONG"() {
        when:
        controller.postMessage(9L, [body: 'z' * 2001], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'TOO_LONG'
        0 * tradeService.postMessage(_, _, _)
    }

    def "postMessage() accepts exactly 2000 chars (bound inclusive)"() {
        given:
        def msg = new TradeMessage(id: 1L, body: 'z' * 2000)
        authedSession(100L)
        1 * tradeService.postMessage(9L, 100L, 'z' * 2000) >> msg

        when:
        def resp = controller.postMessage(9L, [body: 'z' * 2000], req)

        then:
        resp.body.is(msg)
    }

    def "postMessage() forwards a valid message to the service"() {
        given:
        def msg = new TradeMessage(id: 1L, body: 'hello')
        authedSession(100L)
        1 * tradeService.postMessage(9L, 100L, 'hello') >> msg

        when:
        def resp = controller.postMessage(9L, [body: 'hello'], req)

        then:
        resp.body.is(msg)
    }

    def "postMessage() requires sign-in"() {
        given: anonSession()
        when:  controller.postMessage(9L, [body: 'hi'], req)
        then:  thrown(UnauthorizedException)
        0 * tradeService.postMessage(_, _, _)
    }
}
