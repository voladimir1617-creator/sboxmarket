package com.sboxmarket

import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.controller.TradeProtectionController
import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.Trade
import com.sboxmarket.model.TradeProtection
import com.sboxmarket.service.TradeProtectionService
import com.sboxmarket.service.TradeService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

/**
 * Coverage for the Trade Protection endpoints.
 *
 *   1. /quote is public — it must NOT touch the session, and it
 *      surfaces a 5-minute Cache-Control header. A non-numeric `price`
 *      query param is rejected with INVALID_PRICE before the service
 *      is consulted.
 *
 *   2. /protection POST (enable) is gated to the signed-in user; the
 *      controller forwards the caller's uid so the service can run its
 *      buyer-only check.
 *
 *   3. /protection GET (status) is participant-gated at the controller:
 *      it reuses TradeService.get() (which 404s an unknown trade) and
 *      then forbids any caller who is neither the buyer nor the seller,
 *      so a plain GET can't enumerate protection state by trade id.
 */
class TradeProtectionControllerSpec extends Specification {

    TradeProtectionService tradeProtectionService = Mock()
    TradeService           tradeService           = Mock()

    @Subject
    TradeProtectionController controller = new TradeProtectionController(
        tradeProtectionService: tradeProtectionService,
        tradeService:           tradeService
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

    // ── quote ─────────────────────────────────────────────────────

    def "quote returns the fee, rate, floor and coverage for a price"() {
        given:
        1 * tradeProtectionService.quote(new BigDecimal('50.00')) >> new BigDecimal('1.00')

        when:
        def resp = controller.quote('50.00')

        then:
        resp.statusCode.value() == 200
        resp.body.price == new BigDecimal('50.00')
        resp.body.fee == new BigDecimal('1.00')
        resp.body.ratePercent == 2
        resp.body.minFee == TradeProtectionService.MIN_FEE
        resp.body.coverageAmount == new BigDecimal('50.00')
    }

    def "quote is public and never touches the session"() {
        given:
        1 * tradeProtectionService.quote(_) >> new BigDecimal('0.25')

        when:
        controller.quote('5.00')

        then: "no sign-in required for the public checkout quote"
        0 * req.session
    }

    def "quote carries a 5-minute public Cache-Control header"() {
        given:
        1 * tradeProtectionService.quote(_) >> new BigDecimal('2.00')

        when:
        def resp = controller.quote('100.00')

        then:
        resp.headers.getFirst('Cache-Control') == 'public, max-age=300'
    }

    @Unroll
    def "quote rejects a non-numeric price (#bad) with INVALID_PRICE"() {
        when:
        controller.quote(bad)

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_PRICE'
        0 * tradeProtectionService.quote(_)

        where:
        bad << ['abc', '', 'free', '12,50', '$10']
    }

    def "quote forwards the parsed price through to the service"() {
        when:
        controller.quote('13.00')

        then:
        1 * tradeProtectionService.quote(new BigDecimal('13.00')) >> new BigDecimal('0.26')
    }

    @Unroll
    def "quote accepts well-formed numeric strings (#raw parses to #parsed)"() {
        given:
        1 * tradeProtectionService.quote(parsed) >> new BigDecimal('0.25')

        when:
        def resp = controller.quote(raw)

        then: "the raw string is parsed and echoed back as price + coverageAmount"
        resp.statusCode.value() == 200
        resp.body.price == parsed
        resp.body.coverageAmount == parsed

        where:
        raw      || parsed
        '100'    || new BigDecimal('100')
        '49.99'  || new BigDecimal('49.99')
        '1e3'    || new BigDecimal('1e3')
    }

    @Unroll
    def "quote refuses #raw before doing any arithmetic"() {
        when:
        controller.quote(raw)

        then:
        def e = thrown(com.sboxmarket.exception.BadRequestException)
        e.code == 'INVALID_PRICE'
        0 * tradeProtectionService.quote(_)

        where:
        raw << ['0', '-5.00', '0.001', '100000.01', '1e3000000', '1' * 21]
    }

    def "quote echoes the service MIN_FEE constant as the floor"() {
        given:
        1 * tradeProtectionService.quote(_) >> new BigDecimal('0.25')

        when:
        def resp = controller.quote('1.00')

        then: "minFee is sourced from the service constant, not a magic literal"
        resp.body.minFee == TradeProtectionService.MIN_FEE
        resp.body.minFee == new BigDecimal('0.25')
    }

    // ── enable ────────────────────────────────────────────────────

    def "enable forwards the caller uid and trade id to the service"() {
        given:
        def protection = new TradeProtection(id: 7L, tradeId: 9L,
            status: TradeProtection.ACTIVE)
        authedSession(100L)
        1 * tradeProtectionService.enable(100L, 9L) >> protection

        when:
        def resp = controller.enable(9L, req)

        then:
        resp.statusCode.value() == 200
        resp.body.is(protection)
    }

    def "enable requires sign-in"() {
        given:
        anonSession()

        when:
        controller.enable(9L, req)

        then:
        thrown(UnauthorizedException)
        0 * tradeProtectionService.enable(_, _)
    }

    def "enable propagates the service buyer-only ForbiddenException"() {
        given:
        authedSession(200L)
        1 * tradeProtectionService.enable(200L, 9L) >> {
            throw new ForbiddenException('Only the buyer can protect this trade')
        }

        when:
        controller.enable(9L, req)

        then:
        thrown(ForbiddenException)
    }

    def "enable propagates a service BadRequestException (e.g. PROTECTION_EXISTS)"() {
        given:
        authedSession(100L)
        1 * tradeProtectionService.enable(100L, 9L) >> {
            throw new BadRequestException('PROTECTION_EXISTS', 'This trade is already protected.')
        }

        when:
        controller.enable(9L, req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'PROTECTION_EXISTS'
    }

    def "enable propagates a service NotFoundException for an unknown trade"() {
        given:
        authedSession(100L)
        1 * tradeProtectionService.enable(100L, 9L) >> { throw new NotFoundException('Trade', 9L) }

        when:
        controller.enable(9L, req)

        then:
        thrown(NotFoundException)
    }

    @Unroll
    def "enable propagates the service wallet-precondition BadRequestException (#code)"() {
        given:
        authedSession(100L)
        1 * tradeProtectionService.enable(100L, 9L) >> {
            throw new BadRequestException(code, 'wallet precondition failed')
        }

        when:
        controller.enable(9L, req)

        then: "the machine-readable code reaches the client unchanged"
        def e = thrown(BadRequestException)
        e.code == code

        where:
        code << ['NO_WALLET', 'WALLET_FROZEN', 'INSUFFICIENT_BALANCE', 'TRADE_NOT_PROTECTABLE']
    }

    // ── status ────────────────────────────────────────────────────

    def "status returns the protection summary for the buyer"() {
        given:
        def trade = new Trade(id: 9L, buyerUserId: 100L, sellerUserId: 200L)
        def summary = [id: 7L, tradeId: 9L, status: 'ACTIVE',
            feeAmount: new BigDecimal('1.00'), coverageAmount: new BigDecimal('50.00')]
        authedSession(100L)
        1 * tradeService.get(9L) >> trade
        1 * tradeProtectionService.summary(9L) >> summary

        when:
        def resp = controller.status(9L, req)

        then:
        resp.statusCode.value() == 200
        resp.body.tradeId == 9L
        resp.body.protected == true
        resp.body.protection.is(summary)
    }

    def "status returns the protection summary for the seller (participant visibility)"() {
        given:
        def trade = new Trade(id: 9L, buyerUserId: 100L, sellerUserId: 200L)
        def summary = [id: 7L, tradeId: 9L, status: 'CLAIMED']
        authedSession(200L)
        1 * tradeService.get(9L) >> trade
        1 * tradeProtectionService.summary(9L) >> summary

        when:
        def resp = controller.status(9L, req)

        then:
        resp.body.protected == true
        resp.body.protection.status == 'CLAIMED'
    }

    def "status reports protected:false with a null summary for an unprotected trade"() {
        given:
        def trade = new Trade(id: 9L, buyerUserId: 100L, sellerUserId: 200L)
        authedSession(100L)
        1 * tradeService.get(9L) >> trade
        1 * tradeProtectionService.summary(9L) >> null

        when:
        def resp = controller.status(9L, req)

        then:
        resp.body.tradeId == 9L
        resp.body.protected == false
        resp.body.protection == null
    }

    def "status requires sign-in"() {
        given:
        anonSession()

        when:
        controller.status(9L, req)

        then:
        thrown(UnauthorizedException)
        0 * tradeService.get(_)
        0 * tradeProtectionService.summary(_)
    }

    def "status forbids a non-participant (enumeration guard)"() {
        given:
        def trade = new Trade(id: 9L, buyerUserId: 100L, sellerUserId: 200L)
        authedSession(999L)   // neither buyer nor seller
        1 * tradeService.get(9L) >> trade

        when:
        controller.status(9L, req)

        then:
        thrown(ForbiddenException)
        and: "protection state is never read for a non-participant"
        0 * tradeProtectionService.summary(_)
    }

    def "status propagates a NotFoundException for an unknown trade"() {
        given:
        authedSession(100L)
        1 * tradeService.get(9L) >> { throw new NotFoundException('Trade', 9L) }

        when:
        controller.status(9L, req)

        then:
        thrown(NotFoundException)
        0 * tradeProtectionService.summary(_)
    }

    def "status forbids a caller when the trade has a null buyer and null seller"() {
        given: "a degenerate trade with no participants — nobody may read it"
        def trade = new Trade(id: 9L, buyerUserId: null, sellerUserId: null)
        authedSession(100L)
        1 * tradeService.get(9L) >> trade

        when:
        controller.status(9L, req)

        then: "a non-participant (everyone, here) is forbidden — no enumeration leak"
        thrown(ForbiddenException)
        0 * tradeProtectionService.summary(_)
    }

    def "status echoes the path id as tradeId even when the trade has none persisted"() {
        given:
        def trade = new Trade(id: null, buyerUserId: 100L, sellerUserId: 200L)
        authedSession(100L)
        1 * tradeService.get(9L) >> trade
        1 * tradeProtectionService.summary(9L) >> null

        when:
        def resp = controller.status(9L, req)

        then: "tradeId in the body is the request path id, consistently"
        resp.body.tradeId == 9L
        resp.body.protected == false
    }
}
