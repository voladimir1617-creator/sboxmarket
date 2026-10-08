package com.sboxmarket

import com.sboxmarket.controller.ListingController
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.ListingService
import com.sboxmarket.service.ReviewService
import spock.lang.Specification

/**
 * The public stall's ✓ Verified badge needs 10+ sales and an average of at
 * least 4.0. The stall read the one-decimal display average, so a 3.96★
 * seller rounded to 4.0 and showed Verified on their stall while seller
 * search and their own progress card (which use the raw average) did not.
 */
class ListingControllerStallVerifiedSpec extends Specification {

    SteamUserRepository steamUserRepository = Mock()
    ListingService      listingService      = Mock()
    ReviewService       reviewService       = Mock()

    ListingController controller = new ListingController(
        steamUserRepository: steamUserRepository,
        listingService:      listingService,
        reviewService:       reviewService
    )

    private Map stallFor(Map summary) {
        steamUserRepository.findById(5L) >> Optional.of(new SteamUser(id: 5L, steamId64: '76561190000000005', displayName: 'Seller'))
        listingService.findActiveVisibleBySeller(5L, _) >> []
        listingService.countActiveBySeller(5L) >> 0L
        listingService.countSoldBySeller(5L) >> 12L
        listingService.countSoldBySellerSince(_, _) >> 0L
        reviewService.summaryForUser(5L) >> summary
        reviewService.summariesForUsers(_) >> [:]
        controller.publicStall(5L, null).body as Map
    }

    def "a 3.96 average shown as 4.0 does not earn the Verified badge"() {
        expect:
        stallFor([count: 25L, average: 4.0d, averageRaw: 3.96d, histogram: [0, 24, 1, 0, 0]]).seller.verified == false
    }

    def "a real 4.0 average with 10+ sales is Verified"() {
        expect:
        stallFor([count: 4L, average: 4.0d, averageRaw: 4.0d, histogram: [0, 4, 0, 0, 0]]).seller.verified == true
    }

    def "a seller with sales and no reviews yet is Verified"() {
        expect:
        stallFor([count: 0, average: null, histogram: [0, 0, 0, 0, 0]]).seller.verified == true
    }
}
