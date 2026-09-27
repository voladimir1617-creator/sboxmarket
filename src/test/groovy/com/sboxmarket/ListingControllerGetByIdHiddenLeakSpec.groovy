package com.sboxmarket

import com.sboxmarket.controller.ListingController
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.Listing
import com.sboxmarket.service.ListingService
import com.sboxmarket.service.security.AdminAuthorization
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import spock.lang.Specification
import spock.lang.Subject

/**
 * Pins GET /api/listings/{id} against the cross-user hidden-listing leak.
 *
 * Pre-fix the endpoint returned the raw Listing for ANY id — even rows where
 * `hidden=true`. PurchaseService.buy, OfferService.makeOffer and CartService.add
 * all reject hidden ids (the seller has pulled the listing off-market via
 * stall-privacy / vacation mode), but this endpoint was the back door: anyone
 * walking /api/listings/{1..N} could pull a hidden listing's full payload
 * (price, description, sellerUserId, maxDiscount) on a stable id.
 *
 * Post-fix non-owner / non-admin viewers get a 404 NotFoundException; the
 * seller themselves still sees their own row so the MyStall edit path keeps
 * working through this endpoint.
 */
class ListingControllerGetByIdHiddenLeakSpec extends Specification {

    ListingService listingService = Mock()
    AdminAuthorization adminAuthorization = Mock()

    @Subject
    ListingController controller = new ListingController(
        listingService: listingService,
        adminAuthorization: adminAuthorization)

    HttpServletRequest req = Mock()
    HttpSession        ses = Mock()

    private Listing hiddenListing(Long id = 42L, Long sellerId = 100L) {
        new Listing(id: id, sellerUserId: sellerId, price: new BigDecimal('99.99'),
            hidden: true, status: 'ACTIVE')
    }

    private Listing visibleListing(Long id = 42L, Long sellerId = 100L) {
        new Listing(id: id, sellerUserId: sellerId, price: new BigDecimal('99.99'),
            hidden: false, status: 'ACTIVE')
    }

    def "getById() leaks a hidden listing to an unrelated viewer pre-fix — must 404 post-fix"() {
        given: 'a hidden listing owned by seller 100, viewed by stranger 999'
        listingService.getById(42L) >> hiddenListing(42L, 100L)
        req.session >> ses
        ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 999L
        adminAuthorization.isAdmin(999L) >> false

        when:
        controller.getById(42L, req)

        then: 'stranger walking /api/listings/{id} must not see hidden seller payload'
        thrown(NotFoundException)
    }

    def "getById() also 404s an anonymous viewer on a hidden listing"() {
        given:
        listingService.getById(42L) >> hiddenListing(42L, 100L)
        req.session >> ses
        ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> null

        when:
        controller.getById(42L, req)

        then:
        thrown(NotFoundException)
        0 * adminAuthorization.isAdmin(_)
    }

    def "getById() still lets the owning seller fetch their own hidden listing"() {
        given: 'the seller 100 is the viewer — MyStall edit path must keep working'
        def hidden = hiddenListing(42L, 100L)
        listingService.getById(42L) >> hidden
        req.session >> ses
        ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 100L

        when:
        def resp = controller.getById(42L, req)

        then:
        resp.statusCode.value() == 200
        resp.body.is(hidden)
        0 * adminAuthorization.isAdmin(_)
    }

    def "getById() lets a signed-in admin fetch a hidden listing for moderation"() {
        given:
        def hidden = hiddenListing(42L, 100L)
        listingService.getById(42L) >> hidden
        req.session >> ses
        ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 7L
        adminAuthorization.isAdmin(7L) >> true

        when:
        def resp = controller.getById(42L, req)

        then:
        resp.statusCode.value() == 200
        resp.body.is(hidden)
    }

    def "getById() on a visible listing is unchanged for any viewer"() {
        given:
        def visible = visibleListing(42L, 100L)
        listingService.getById(42L) >> visible
        req.session >> ses
        ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 999L

        when:
        def resp = controller.getById(42L, req)

        then: 'no admin probe needed — visible rows skip the hidden gate'
        resp.statusCode.value() == 200
        resp.body.is(visible)
        0 * adminAuthorization.isAdmin(_)
    }
}
