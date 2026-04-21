package com.sboxmarket

import com.sboxmarket.controller.BidController
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.dto.request.PlaceBidRequest
import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.Bid
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.BidService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import spock.lang.Specification
import spock.lang.Subject

/**
 * Coverage for the auction bid surface. The load-bearing pieces:
 *
 *   1. `place()` response projection — only `{id, amount, kind,
 *      status}`. Other Bid fields (like the bidder uid) stay server-
 *      side.
 *
 *   2. Auction Buy-Now price-match guard. `?expectedPrice=X` must run
 *      BEFORE `buyNowAuction()` enters the transactional path, and
 *      drift → `BadRequestException(PRICE_CHANGED)`. Non-numeric →
 *      `INVALID_PRICE`. Happy path (price matches, OR no expectedPrice
 *      supplied) flows through.
 *
 *   3. History is readable anonymously (viewer-aware: signed-in users
 *      get their own max-bid revealed; anon sees only public auction
 *      bid values).
 *
 *   4. Auto-bid cancel + bulk-cancel-all are idempotent `{cancelled: N}`.
 *
 * Batch 1068 — added to close the coverage gap identified by walking
 * the controller list vs. the test directory.
 */
class BidControllerSpec extends Specification {

    BidService          bidService          = Mock()
    SteamUserRepository steamUserRepository = Mock()
    ListingRepository   listingRepository   = Mock()

    @Subject
    BidController controller = new BidController(
        bidService         : bidService,
        steamUserRepository: steamUserRepository,
        listingRepository  : listingRepository
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

    private PlaceBidRequest bidReq(Long listingId = 9L,
                                   BigDecimal amount = new BigDecimal('10.50'),
                                   BigDecimal maxAmount = null) {
        new PlaceBidRequest(listingId: listingId, amount: amount, maxAmount: maxAmount)
    }

    // ── place() ─────────────────────────────────────────────────

    def "place() requires sign-in"() {
        given: anonSession()
        when:  controller.place(bidReq(), req)
        then:  thrown(UnauthorizedException)
        0 * bidService.placeBid(_, _, _, _, _)
    }

    def "place() raises Unauthorized when the session uid has no SteamUser row"() {
        given:
        authedSession(100L)
        1 * steamUserRepository.findById(100L) >> Optional.empty()

        when:
        controller.place(bidReq(), req)

        then:
        thrown(UnauthorizedException)
        0 * bidService.placeBid(_, _, _, _, _)
    }

    def "place() falls back to 'Player' for displayName"() {
        given:
        def user = new SteamUser(id: 100L, displayName: null)
        def b = new Bid(id: 1L, amount: new BigDecimal('10.50'), kind: 'MANUAL', status: 'WINNING')
        authedSession(100L)
        1 * steamUserRepository.findById(100L) >> Optional.of(user)
        1 * bidService.placeBid(100L, 'Player', 9L, new BigDecimal('10.50'), null) >> b

        when:
        controller.place(bidReq(), req)

        then:
        true  // mock verifies the fallback
    }

    def "place() response is projected to {id, amount, kind, status}"() {
        given:
        def user = new SteamUser(id: 100L, displayName: 'alice')
        def b = new Bid(id: 9L, amount: new BigDecimal('12.25'),
                        kind: 'AUTO', status: 'OUTBID')
        authedSession(100L)
        1 * steamUserRepository.findById(100L) >> Optional.of(user)
        1 * bidService.placeBid(100L, 'alice', 9L, new BigDecimal('12.25'), new BigDecimal('20')) >> b

        when:
        def resp = controller.place(bidReq(9L, new BigDecimal('12.25'), new BigDecimal('20')), req)

        then: 'exactly 4 fields; bidder identity never leaks'
        resp.body == [id: 9L, amount: new BigDecimal('12.25'), kind: 'AUTO', status: 'OUTBID']
    }

    // ── history() ───────────────────────────────────────────────

    def "history() is anon-friendly — no 401, viewer passed as null"() {
        given:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> null
        def rows = [new Bid(id: 1L, amount: new BigDecimal('5.00'))]
        1 * bidService.historyFor(42L, null) >> rows

        when:
        def resp = controller.history(42L, req)

        then:
        resp.body.is(rows)
    }

    def "history() passes viewer uid to the service when signed in"() {
        given:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 100L
        def rows = [new Bid(id: 1L)]
        1 * bidService.historyFor(42L, 100L) >> rows

        when:
        controller.history(42L, req)

        then:
        true  // mock verifies the viewer uid
    }

    // ── buyNowAuction() ─────────────────────────────────────────

    def "buyNowAuction() requires sign-in"() {
        given: anonSession()
        when:  controller.buyNowAuction(42L, null, req)
        then:  thrown(UnauthorizedException)
        0 * bidService.buyNowAuction(_, _, _)
    }

    def "buyNowAuction() with no expectedPrice flows straight through to the service"() {
        given:
        def user = new SteamUser(id: 100L, displayName: 'alice')
        def listing = new Listing(id: 42L, status: 'SOLD', buyerUserId: 100L,
                                  currentBid: new BigDecimal('99.00'))
        authedSession(100L)
        1 * steamUserRepository.findById(100L) >> Optional.of(user)
        0 * listingRepository.findById(_)          // no price-match probe
        1 * bidService.buyNowAuction(100L, 'alice', 42L) >> listing

        when:
        def resp = controller.buyNowAuction(42L, null, req)

        then:
        resp.body == [listingId: 42L, status: 'SOLD', price: new BigDecimal('99.00'), buyerUserId: 100L]
    }

    def "buyNowAuction() rejects non-numeric expectedPrice with INVALID_PRICE"() {
        given:
        def user = new SteamUser(id: 100L, displayName: 'alice')
        authedSession(100L)
        1 * steamUserRepository.findById(100L) >> Optional.of(user)

        when:
        controller.buyNowAuction(42L, [expectedPrice: 'abc'], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_PRICE'
        0 * bidService.buyNowAuction(_, _, _)
    }

    def "buyNowAuction() raises PRICE_CHANGED when listing's buyNowPrice drifted"() {
        given:
        def user = new SteamUser(id: 100L, displayName: 'alice')
        def listing = new Listing(id: 42L, buyNowPrice: new BigDecimal('120.00'))
        authedSession(100L)
        1 * steamUserRepository.findById(100L) >> Optional.of(user)
        1 * listingRepository.findById(42L) >> Optional.of(listing)

        when: 'buyer clicked at $99 but the seller nudged it to $120 while the modal was open'
        controller.buyNowAuction(42L, [expectedPrice: '99.00'], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'PRICE_CHANGED'
        0 * bidService.buyNowAuction(_, _, _)
    }

    def "buyNowAuction() proceeds when expectedPrice matches server-side price"() {
        given:
        def user = new SteamUser(id: 100L, displayName: 'alice')
        def listing = new Listing(id: 42L, buyNowPrice: new BigDecimal('99.00'))
        def sold = new Listing(id: 42L, status: 'SOLD', buyerUserId: 100L,
                               currentBid: new BigDecimal('99.00'))
        authedSession(100L)
        1 * steamUserRepository.findById(100L) >> Optional.of(user)
        1 * listingRepository.findById(42L) >> Optional.of(listing)
        1 * bidService.buyNowAuction(100L, 'alice', 42L) >> sold

        when:
        def resp = controller.buyNowAuction(42L, [expectedPrice: '99.00'], req)

        then:
        resp.body.status == 'SOLD'
    }

    def "buyNowAuction() tolerates missing listing (service handles NOT_FOUND) when expectedPrice set"() {
        given:
        def user = new SteamUser(id: 100L, displayName: 'alice')
        def sold = new Listing(id: 42L, status: 'SOLD', buyerUserId: 100L,
                               currentBid: new BigDecimal('9.00'))
        authedSession(100L)
        1 * steamUserRepository.findById(100L) >> Optional.of(user)
        1 * listingRepository.findById(42L) >> Optional.empty()
        1 * bidService.buyNowAuction(100L, 'alice', 42L) >> sold

        when: "buyer reloaded + someone else already clicked — service resolves authoritatively"
        controller.buyNowAuction(42L, [expectedPrice: '9.00'], req)

        then: 'no PRICE_CHANGED short-circuit on listing=empty; service path decides'
        noExceptionThrown()
    }

    // ── auto-bid endpoints ─────────────────────────────────────

    def "autoBids() requires sign-in"() {
        given: anonSession()
        when:  controller.autoBids(req)
        then:  thrown(UnauthorizedException)
        0 * bidService.autoBidsForUser(_)
    }

    def "autoBids() returns the service's list unchanged"() {
        given:
        def rows = [new Bid(id: 1L, kind: 'AUTO'), new Bid(id: 2L, kind: 'AUTO')]
        authedSession(100L)
        1 * bidService.autoBidsForUser(100L) >> rows

        when:
        def resp = controller.autoBids(req)

        then:
        resp.body.is(rows)
    }

    def "myActive() requires sign-in and returns live bids"() {
        given:
        def rows = [new Bid(id: 1L, status: 'WINNING')]
        authedSession(100L)
        1 * bidService.liveBidsForUser(100L) >> rows

        when:
        def resp = controller.myActive(req)

        then:
        resp.body.is(rows)
    }

    def "myPast() requires sign-in and returns past bids"() {
        given:
        def rows = [new Bid(id: 1L, status: 'WON'), new Bid(id: 2L, status: 'LOST')]
        authedSession(100L)
        1 * bidService.pastBidsForUser(100L) >> rows

        when:
        def resp = controller.myPast(req)

        then:
        resp.body.is(rows)
    }

    def "cancelAutoBid() returns {id, cancelled:N}"() {
        given:
        authedSession(100L)
        1 * bidService.cancelAutoBid(100L, 9L) >> 1

        when:
        def resp = controller.cancelAutoBid(9L, req)

        then:
        resp.body == [id: 9L, cancelled: 1]
    }

    def "cancelAutoBid() is idempotent — {id, cancelled:0} when nothing to cancel"() {
        given:
        authedSession(100L)
        1 * bidService.cancelAutoBid(100L, 9L) >> 0

        when:
        def resp = controller.cancelAutoBid(9L, req)

        then:
        resp.body == [id: 9L, cancelled: 0]
    }

    def "cancelAutoBid() requires sign-in"() {
        given: anonSession()
        when:  controller.cancelAutoBid(9L, req)
        then:  thrown(UnauthorizedException)
        0 * bidService.cancelAutoBid(_, _)
    }

    def "cancelAllAutoBids() returns the bulk count envelope"() {
        given:
        authedSession(100L)
        1 * bidService.cancelAllAutoBidsForUser(100L) >> 4

        when:
        def resp = controller.cancelAllAutoBids(req)

        then:
        resp.body == [cancelled: 4]
    }

    def "cancelAllAutoBids() is idempotent — zero-row caller gets {cancelled:0}"() {
        given:
        authedSession(100L)
        1 * bidService.cancelAllAutoBidsForUser(100L) >> 0

        when:
        def resp = controller.cancelAllAutoBids(req)

        then:
        resp.body == [cancelled: 0]
    }
}
