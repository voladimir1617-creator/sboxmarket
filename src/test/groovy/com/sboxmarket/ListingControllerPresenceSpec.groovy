package com.sboxmarket

import com.sboxmarket.controller.ListingController
import com.sboxmarket.model.Listing
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.ReviewService
import spock.lang.Specification
import spock.lang.Subject

/**
 * Seller presence ("Online" / "Offline" on every card) comes from
 * `Listing.sellerLastSeenAt`, attached in bulk by the listing endpoints.
 *
 * The attach step used to run AFTER `if (review summaries are empty) return`,
 * so on any page where no seller had a review yet — a new marketplace, or a
 * page of new sellers — presence was never attached and every card read
 * "Offline" while its seller was browsing the site. Presence has nothing to do
 * with reviews; these pin that it is attached either way.
 */
class ListingControllerPresenceSpec extends Specification {

    SteamUserRepository steamUserRepository = Mock()
    ReviewService       reviewService       = Mock()

    @Subject
    ListingController controller = new ListingController(
        steamUserRepository: steamUserRepository,
        reviewService:       reviewService
    )

    def "presence is attached when no seller on the page has a review"() {
        given:
        def rows = [new Listing(id: 1L, sellerUserId: 7L), new Listing(id: 2L, sellerUserId: null)]
        reviewService.summariesForUsers(_) >> [:]
        steamUserRepository.findLastSeenAtByIds(_) >> [[7L, 1234L] as Object[]]

        when:
        controller.decorateWithSellerRating(rows)

        then:
        rows[0].sellerLastSeenAt == 1234L
        rows[1].sellerLastSeenAt == null   // house listing: nobody to be online
    }

    def "presence is attached when the review service is not wired at all"() {
        given:
        def bare = new ListingController(steamUserRepository: steamUserRepository)
        def rows = [new Listing(id: 1L, sellerUserId: 7L)]
        steamUserRepository.findLastSeenAtByIds(_) >> [[7L, 99L] as Object[]]

        when:
        bare.decorateWithSellerRating(rows)

        then:
        rows[0].sellerLastSeenAt == 99L
    }

    def "ratings are still attached alongside presence"() {
        given:
        def rows = [new Listing(id: 1L, sellerUserId: 7L)]
        reviewService.summariesForUsers(_) >> [7L: [average: 4.5, count: 12]]
        steamUserRepository.findLastSeenAtByIds(_) >> [[7L, 5L] as Object[]]

        when:
        controller.decorateWithSellerRating(rows)

        then:
        rows[0].sellerLastSeenAt == 5L
        rows[0].sellerRating == 4.5d
        rows[0].sellerReviewCount == 12
    }
}
