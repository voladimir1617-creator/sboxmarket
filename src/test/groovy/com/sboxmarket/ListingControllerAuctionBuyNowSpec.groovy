package com.sboxmarket

import com.sboxmarket.controller.ListingController
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.model.Listing
import com.sboxmarket.service.ListingService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import spock.lang.Specification
import spock.lang.Subject

/**
 * Re-pricing an auction must keep its starting bid below Buy Now, the same
 * rule listing creation enforces. Raising the start from $10 to $30 on an
 * auction with a $20 Buy Now left bids required at $30 while Buy Now still
 * sold the item for $20.
 */
class ListingControllerAuctionBuyNowSpec extends Specification {

    ListingService listingService = Mock()

    @Subject
    ListingController controller = new ListingController(listingService: listingService)

    HttpServletRequest req = Mock()
    HttpSession        ses = Mock()

    def setup() {
        req.session >> ses
        ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 100L
    }

    private Listing auction() {
        new Listing(id: 5L, sellerUserId: 100L, status: 'ACTIVE', listingType: 'AUCTION',
            price: new BigDecimal('10.00'), buyNowPrice: new BigDecimal('20.00'), bidCount: 0)
    }

    def "raising an auction's starting bid to or past its Buy Now price is rejected"() {
        given:
        def listing = auction()
        listingService.getById(5L) >> listing

        when:
        controller.updateStall(5L, [price: newPrice], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_BUY_NOW'
        listing.price == new BigDecimal('10.00')

        where:
        newPrice << ['20', '30']
    }
}
