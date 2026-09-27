package com.sboxmarket

import com.sboxmarket.controller.ListingController
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.service.ListingService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import spock.lang.Specification
import spock.lang.Subject

/**
 * Pins the `body.hidden` parse on POST /api/listings/away. A bare
 * `body.hidden as Boolean` is unsafe: Jackson maps `{"hidden":"false"}`
 * to the String "false", and Groovy-truth makes every non-empty String
 * truthy — so the seller would ENTER away-mode when they meant to
 * leave it (and vice versa). Same bug class SellerFollowController
 * closed with parseMutedFlag.
 */
class ListingControllerAwayFlagSpec extends Specification {

    ListingService listingService = Mock()

    @Subject
    ListingController controller = new ListingController(listingService: listingService)

    HttpServletRequest req = Mock()
    HttpSession        ses = Mock()

    private void authedSession(long uid = 100L) {
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> uid
    }

    def "awayMode() honours a string-encoded 'false' instead of Groovy-truthing it"() {
        given: 'JSON {"hidden":"false"} arrives as a String — must un-hide, not hide'
        authedSession(100L)
        1 * listingService.setAwayMode(100L, false, null) >> 7

        when:
        def resp = controller.awayMode([hidden: 'false'], req)

        then: 'a bare `"false" as Boolean` would have been true — this must be false'
        resp.body.hidden == false
        resp.body.affected == 7
    }

    def "awayMode() honours a string-encoded 'true'"() {
        given:
        authedSession(100L)
        1 * listingService.setAwayMode(100L, true, null) >> 3

        when:
        def resp = controller.awayMode([hidden: 'true'], req)

        then:
        resp.body.hidden == true
        resp.body.affected == 3
    }

    def "awayMode() accepts a real Boolean"() {
        given:
        authedSession(100L)
        1 * listingService.setAwayMode(100L, true, null) >> 1

        when:
        def resp = controller.awayMode([hidden: true], req)

        then:
        resp.body.hidden == true
    }

    def "awayMode() rejects a non-boolean hidden value with INVALID_FIELD"() {
        given: authedSession(100L)
        when:  controller.awayMode([hidden: 42], req)
        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_FIELD'
        0 * listingService.setAwayMode(_, _, _)
    }
}
