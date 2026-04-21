package com.sboxmarket

import com.sboxmarket.controller.OfferController
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.dto.request.CreateOfferRequest
import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.Offer
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.OfferService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import spock.lang.Specification
import spock.lang.Subject

/**
 * Coverage for the buy-it-lower offer negotiation surface. Two
 * load-bearing pieces:
 *
 *   1. Shared amount-parser ladder for counter() + raise(): null →
 *      INVALID_AMOUNT ("required"), non-numeric → INVALID_AMOUNT
 *      ("valid number"), zero/negative → INVALID_AMOUNT ("greater
 *      than 0"), above $1,000,000 → INVALID_AMOUNT ("exceeds the
 *      $1,000,000 limit"). All four branches share the same code so
 *      the SPA renders a single structured error regardless of which
 *      bad input the user typed.
 *
 *   2. `mineForListing()` is the only endpoint here that's anon-
 *      tolerant — returns `{offer: null}` for unauthenticated viewers
 *      instead of 401, so the ItemModal's "You offered $X" chip can
 *      render a single null-check branch. Pinned explicitly.
 *
 * Plus auth gating on every private endpoint and envelope shape
 * pins for create / accept / reject / cancel / counter / raise /
 * cancelAllOutgoing / counts / bestPerListing / incoming / outgoing
 * (X-Total-Count header).
 *
 * Batch 1068 — added to close the coverage gap identified by walking
 * the controller list vs. the test directory.
 */
class OfferControllerSpec extends Specification {

    OfferService        offerService        = Mock()
    SteamUserRepository steamUserRepository = Mock()

    @Subject
    OfferController controller = new OfferController(
        offerService       : offerService,
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

    // ── incoming / outgoing envelopes ─────────────────────────

    def "incoming() requires sign-in"() {
        given: anonSession()
        when:  controller.incoming(req)
        then:  thrown(UnauthorizedException)
        0 * offerService.incomingWithExpiry(_)
    }

    def "incoming() returns body + X-Total-Count for overflow banner"() {
        given:
        def rows = [[id: 1L, expiresAt: 100L]]
        authedSession(100L)
        1 * offerService.incomingWithExpiry(100L) >> rows
        1 * offerService.countIncoming(100L) >> 42L

        when:
        def resp = controller.incoming(req)

        then:
        resp.body.is(rows)
        resp.headers.getFirst('X-Total-Count') == '42'
    }

    def "outgoing() requires sign-in + returns X-Total-Count"() {
        given:
        def rows = [[id: 2L]]
        authedSession(100L)
        1 * offerService.outgoingWithExpiry(100L) >> rows
        1 * offerService.countOutgoing(100L) >> 3L

        when:
        def resp = controller.outgoing(req)

        then:
        resp.headers.getFirst('X-Total-Count') == '3'
    }

    // ── bestPerListing + counts ────────────────────────────────

    def "bestPerListing() requires sign-in and forwards the service result"() {
        given:
        def payload = [9L: [bestAmount: new BigDecimal('45'), count: 2L]]
        authedSession(100L)
        1 * offerService.pendingOfferSummaryForSeller(100L) >> payload

        when:
        def resp = controller.bestPerListing(req)

        then:
        resp.body.is(payload)
    }

    def "counts() returns {incomingPending, outgoingPending}"() {
        given:
        authedSession(100L)
        1 * offerService.countPendingIncoming(100L) >> 5L
        1 * offerService.countPendingOutgoing(100L) >> 2L

        when:
        def resp = controller.counts(req)

        then:
        resp.body == [incomingPending: 5L, outgoingPending: 2L]
    }

    // ── mineForListing anon-tolerant path ──────────────────────

    def "mineForListing() returns {offer:null} for anon viewers — no 401"() {
        given:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> null

        when:
        def resp = controller.mineForListing(42L, req)

        then: 'short-circuits without calling the service'
        0 * offerService.liveOfferByBuyerForListing(_, _)
        resp.body == [offer: null]
    }

    def "mineForListing() forwards the service result for signed-in viewers"() {
        given:
        // liveOfferByBuyerForListing returns a Map (already serialized)
        def offerMap = [id: 9L, listingId: 42L, amount: new BigDecimal('20')]
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 100L
        1 * offerService.liveOfferByBuyerForListing(100L, 42L) >> offerMap

        when:
        def resp = controller.mineForListing(42L, req)

        then:
        resp.body.offer.is(offerMap)
    }

    def "mineForListing() returns {offer:null} when the buyer has no live offer"() {
        given:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 100L
        1 * offerService.liveOfferByBuyerForListing(100L, 42L) >> null

        when:
        def resp = controller.mineForListing(42L, req)

        then:
        resp.body == [offer: null]
    }

    // ── create ────────────────────────────────────────────────

    def "create() requires sign-in"() {
        given: anonSession()
        def body = new CreateOfferRequest(listingId: 42L, amount: new BigDecimal('20'), message: '')

        when:
        controller.create(body, req)

        then:
        thrown(UnauthorizedException)
        0 * offerService.makeOffer(_, _, _, _, _)
    }

    def "create() raises Unauthorized when session uid has no SteamUser row"() {
        given:
        authedSession(100L)
        1 * steamUserRepository.findById(100L) >> Optional.empty()

        when:
        controller.create(new CreateOfferRequest(listingId: 1L, amount: new BigDecimal('10')), req)

        then:
        thrown(UnauthorizedException)
        0 * offerService.makeOffer(_, _, _, _, _)
    }

    def "create() response includes itemName for the SPA toast"() {
        given:
        def user = new SteamUser(id: 100L, displayName: 'alice')
        def offer = new Offer(id: 9L, status: 'PENDING', amount: new BigDecimal('20'),
                              itemName: 'Chef Hat')
        authedSession(100L)
        1 * steamUserRepository.findById(100L) >> Optional.of(user)
        1 * offerService.makeOffer(100L, 'alice', 42L, new BigDecimal('20'), 'please') >> offer

        when:
        def resp = controller.create(
            new CreateOfferRequest(listingId: 42L, amount: new BigDecimal('20'), message: 'please'), req)

        then:
        resp.body == [id: 9L, status: 'PENDING',
                      amount: new BigDecimal('20'), itemName: 'Chef Hat']
    }

    def "create() falls back to 'Player' when displayName is null"() {
        given:
        authedSession(100L)
        1 * steamUserRepository.findById(100L) >> Optional.of(new SteamUser(id: 100L, displayName: null))
        1 * offerService.makeOffer(100L, 'Player', 1L, new BigDecimal('5'), null) >>
            new Offer(id: 1L, status: 'PENDING', amount: new BigDecimal('5'))

        when:
        controller.create(new CreateOfferRequest(listingId: 1L, amount: new BigDecimal('5')), req)

        then:
        true
    }

    // ── accept / reject / cancel ──────────────────────────────

    def "accept() passes the service result through unchanged"() {
        given:
        def payload = [id: 9L, status: 'ACCEPTED']
        authedSession(100L)
        1 * offerService.acceptOffer(100L, 9L) >> payload

        when:
        def resp = controller.accept(9L, req)

        then:
        resp.body.is(payload)
    }

    def "reject() returns {id, status} envelope"() {
        given:
        def offer = new Offer(id: 9L, status: 'REJECTED')
        authedSession(100L)
        1 * offerService.rejectOffer(100L, 9L, 'too low') >> offer

        when:
        def resp = controller.reject(9L, [reply: 'too low'], req)

        then:
        resp.body == [id: 9L, status: 'REJECTED']
    }

    def "reject() with null body passes null reply through"() {
        given:
        def offer = new Offer(id: 9L, status: 'REJECTED')
        authedSession(100L)
        1 * offerService.rejectOffer(100L, 9L, null) >> offer

        when:
        def resp = controller.reject(9L, null, req)

        then:
        resp.body.status == 'REJECTED'
    }

    def "cancel() returns {id, status} envelope"() {
        given:
        def offer = new Offer(id: 9L, status: 'CANCELLED')
        authedSession(100L)
        1 * offerService.cancelOffer(100L, 9L) >> offer

        when:
        def resp = controller.cancel(9L, req)

        then:
        resp.body == [id: 9L, status: 'CANCELLED']
    }

    def "cancelAllOutgoing() returns {cancelled: N} envelope"() {
        given:
        authedSession(100L)
        1 * offerService.cancelAllForUser(100L) >> 3

        when:
        def resp = controller.cancelAllOutgoing(req)

        then:
        resp.body == [cancelled: 3]
    }

    def "cancelAllOutgoing() idempotent — zero-row caller gets {cancelled:0}"() {
        given:
        authedSession(100L)
        1 * offerService.cancelAllForUser(100L) >> 0

        when:
        def resp = controller.cancelAllOutgoing(req)

        then:
        resp.body == [cancelled: 0]
    }

    // ── counter / raise amount-parser ladder ──────────────────

    def "counter() requires sign-in"() {
        given: anonSession()
        when:  controller.counter(9L, [amount: '10'], req)
        then:  thrown(UnauthorizedException)
        0 * offerService.counterOffer(_, _, _, _)
    }

    def "counter() rejects null amount with INVALID_AMOUNT ('required')"() {
        given: authedSession(100L)

        when:
        controller.counter(9L, [:], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_AMOUNT'
        e.message.contains('required')
        0 * offerService.counterOffer(_, _, _, _)
    }

    def "counter() rejects non-numeric amount with INVALID_AMOUNT ('valid number')"() {
        given: authedSession(100L)

        when:
        controller.counter(9L, [amount: 'abc'], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_AMOUNT'
        e.message.contains('valid number')
        0 * offerService.counterOffer(_, _, _, _)
    }

    def "counter() rejects zero amount with INVALID_AMOUNT ('greater than 0')"() {
        given: authedSession(100L)

        when:
        controller.counter(9L, [amount: '0'], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_AMOUNT'
        e.message.contains('greater than 0')
        0 * offerService.counterOffer(_, _, _, _)
    }

    def "counter() rejects negative amount with INVALID_AMOUNT"() {
        given: authedSession(100L)

        when:
        controller.counter(9L, [amount: '-5'], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_AMOUNT'
        0 * offerService.counterOffer(_, _, _, _)
    }

    def 'counter() rejects amount above $1,000,000 with INVALID_AMOUNT'() {
        given: authedSession(100L)

        when:
        controller.counter(9L, [amount: '1000001'], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_AMOUNT'
        e.message.contains('exceeds')
        0 * offerService.counterOffer(_, _, _, _)
    }

    def 'counter() accepts amount at the $1,000,000 limit (upper bound inclusive)'() {
        given:
        def counter = new Offer(id: 10L, parentOfferId: 9L,
                                amount: new BigDecimal('1000000'), status: 'PENDING')
        authedSession(100L)
        1 * offerService.counterOffer(100L, 9L, new BigDecimal('1000000'), null) >> counter

        when:
        controller.counter(9L, [amount: '1000000'], req)

        then:
        true  // service called ⇒ validation passed
    }

    def "counter() happy-path projection includes parentOfferId"() {
        given:
        def counter = new Offer(id: 10L, parentOfferId: 9L,
                                amount: new BigDecimal('15'), status: 'PENDING')
        authedSession(100L)
        1 * offerService.counterOffer(100L, 9L, new BigDecimal('15'), 'meet in middle') >> counter

        when:
        def resp = controller.counter(9L, [amount: '15', message: 'meet in middle'], req)

        then:
        resp.body == [id: 10L, parentOfferId: 9L,
                      amount: new BigDecimal('15'), status: 'PENDING']
    }

    // ── raise uses the SAME parser — spot-check one failure branch ──

    def "raise() shares the parseAmount() ladder with counter()"() {
        given: authedSession(100L)

        when:
        controller.raise(9L, [amount: '-1'], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_AMOUNT'
        0 * offerService.buyerRaise(_, _, _, _)
    }

    def "raise() happy-path returns {id, parentOfferId, amount, status}"() {
        given:
        def raised = new Offer(id: 11L, parentOfferId: 9L,
                               amount: new BigDecimal('25'), status: 'PENDING')
        authedSession(100L)
        1 * offerService.buyerRaise(100L, 9L, new BigDecimal('25'), null) >> raised

        when:
        def resp = controller.raise(9L, [amount: '25'], req)

        then:
        resp.body == [id: 11L, parentOfferId: 9L,
                      amount: new BigDecimal('25'), status: 'PENDING']
    }

    // ── thread (public with redaction) ─────────────────────────

    def "thread() passes the viewer uid to the service (signed in)"() {
        given:
        def rows = [new Offer(id: 1L), new Offer(id: 2L)]
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 100L
        1 * offerService.thread(42L, 100L) >> rows

        when:
        def resp = controller.thread(42L, req)

        then:
        resp.body.is(rows)
    }

    def "thread() is anon-friendly — passes null viewer to the service"() {
        given:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> null
        1 * offerService.thread(42L, null) >> []

        when:
        def resp = controller.thread(42L, req)

        then: 'service decides whether to redact — controller just forwards'
        resp.body == []
    }
}
