package com.sboxmarket

import com.sboxmarket.exception.ListingNotAvailableException
import com.sboxmarket.repository.CartItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.service.CartService
import spock.lang.Specification
import spock.lang.Subject

/**
 * Regression coverage for the cart hidden-listing back door.
 *
 * PurchaseService.buy and OfferService.makeOffer both reject hidden rows
 * with ListingNotAvailableException (see their `Boolean.TRUE.equals(
 * listing.hidden)` gates) because the listing id is stable and guessable
 * — a seller toggling stall-privacy / vacation-mode must not be reachable
 * by a cached client or a scraped-id payload.
 *
 * Before this fix the cart-add path was the back door: a stale tab (or any
 * /api/cart POST with a hidden id) persisted the row, and every subsequent
 * /cart/checkout against it returned LISTING_NOT_AVAILABLE — but
 * CartController.checkout only scrubs SUCCESSFUL rows, so the buyer wedged
 * with a stale row they couldn't see in the grid and couldn't buy. The
 * single-add path and the bulkMerge path were BOTH unguarded.
 *
 * Pins both write paths to the same hidden gate as the buy / offer paths.
 */
class CartServiceHiddenListingSpec extends Specification {

    CartItemRepository repository = Mock()
    ListingRepository listings    = Mock()

    @Subject
    CartService service = new CartService(repository: repository, listingRepository: listings)

    // ── add: hidden gate ─────────────────────────────────────────────

    def "add rejects a hidden listing with LISTING_NOT_AVAILABLE"() {
        given:
        repository.existsByUserAndListing(10L, 555L) >> false
        listings.findHiddenById(555L) >> true

        when:
        service.add(10L, 555L)

        then:
        thrown(ListingNotAvailableException)
        // No insert and no cap probe — guard short-circuits before either.
        0 * repository.save(_)
        0 * repository.findListingIdsByUser(_)
        // Own-listing probe never reached either — hidden gate runs first.
        0 * listings.findSellerUserIdById(_)
    }

    def "add allows a non-hidden listing through to the own-listing / cap path"() {
        given:
        repository.existsByUserAndListing(10L, 555L) >> false
        listings.findHiddenById(555L) >> false
        listings.findSellerUserIdById(555L) >> 99L        // someone else's listing
        repository.findListingIdsByUser(10L) >> []

        when:
        def added = service.add(10L, 555L)

        then:
        added == true
        1 * repository.save({ it.userId == 10L && it.listingId == 555L })
    }

    def "add treats a null hidden projection as visible — legacy null-hidden rows pass"() {
        // Listings written before the `hidden` column existed project null
        // from the @Query. The grid SQL's `l.hidden IS NULL OR l.hidden = false`
        // convention treats those as visible, so the cart-add path must
        // mirror that — a true legacy row is not a hidden row.
        given:
        repository.existsByUserAndListing(10L, 555L) >> false
        listings.findHiddenById(555L) >> null
        listings.findSellerUserIdById(555L) >> 99L
        repository.findListingIdsByUser(10L) >> []

        when:
        def added = service.add(10L, 555L)

        then:
        added == true
        1 * repository.save({ it.listingId == 555L })
    }

    def "add survives a hidden-probe failure — guard falls through (buy-path remains the backstop)"() {
        // A DB blip on the hidden probe must not 500 the add. The buy-path
        // hidden gate still runs at checkout, so the worst case is one
        // stale row the user can manually remove — not a hard failure.
        given:
        repository.existsByUserAndListing(10L, 555L) >> false
        listings.findHiddenById(555L) >> { throw new RuntimeException('DB blip') }
        listings.findSellerUserIdById(555L) >> 99L
        repository.findListingIdsByUser(10L) >> []

        when:
        def added = service.add(10L, 555L)

        then:
        added == true
        notThrown(Exception)
        1 * repository.save({ it.listingId == 555L })
    }

    def "add skips the hidden probe entirely when listingRepository isn't wired"() {
        // The bare-construction mode used by older tests (no listingRepo)
        // must keep working — the buy-path's hidden gate is the backstop in
        // that wiring, matching the single-add own-listing posture.
        given:
        def bare = new CartService(repository: repository)
        repository.existsByUserAndListing(10L, 555L) >> false
        repository.findListingIdsByUser(10L) >> []

        when:
        def added = bare.add(10L, 555L)

        then:
        notThrown(Exception)
        1 * repository.save({ it.listingId == 555L })
    }

    // ── bulkMerge: hidden gate ───────────────────────────────────────

    def "bulkMerge silently drops hidden listings — no save for those rows"() {
        given:
        // Three incoming ids — 70 (hidden) + 71 (visible) + 72 (visible).
        // Hidden ids are dropped before the save loop, mirroring the own-
        // listing pre-filter directly above it in CartService.bulkMerge.
        repository.findExistingListingIds(10L, [70L, 71L, 72L]) >> []
        listings.findSellerUserIdsForListings([70L, 71L, 72L]) >> []   // none owned by caller
        listings.findHiddenListingIds([70L, 71L, 72L]) >> [70L]         // 70 is hidden
        repository.countByUser(10L) >> 0L
        repository.findListingIdsByUser(10L) >> [71L, 72L]

        when:
        def out = service.bulkMerge(10L, [70L, 71L, 72L])

        then:
        // Hidden row never persisted; the other two land normally.
        0 * repository.save({ it.listingId == 70L })
        1 * repository.save({ it.listingId == 71L })
        1 * repository.save({ it.listingId == 72L })
        out == [71L, 72L]
    }

    def "bulkMerge hidden-probe failure falls through — guard is best-effort, buy-path remains the backstop"() {
        // A DB blip on the hidden probe must not 500 the merge — the
        // caller is mid-sign-in and a hard failure here would block their
        // entire first-session cart sync. The buy-path hidden gate still
        // runs at checkout, so the worst case is one row the user can
        // manually remove — not a hard failure.
        given:
        repository.findExistingListingIds(10L, [70L]) >> []
        listings.findSellerUserIdsForListings([70L]) >> []
        listings.findHiddenListingIds([70L]) >> { throw new RuntimeException('DB blip') }
        repository.countByUser(10L) >> 0L
        repository.findListingIdsByUser(10L) >> [70L]

        when:
        def out = service.bulkMerge(10L, [70L])

        then:
        notThrown(Exception)
        1 * repository.save({ it.listingId == 70L })
        out == [70L]
    }

    def "bulkMerge skips the hidden probe entirely when listingRepository isn't wired"() {
        given:
        def bare = new CartService(repository: repository)
        repository.findExistingListingIds(10L, [70L]) >> []
        repository.countByUser(10L) >> 0L
        repository.findListingIdsByUser(10L) >> [70L]

        when:
        def out = bare.bulkMerge(10L, [70L])

        then:
        notThrown(Exception)
        1 * repository.save({ it.listingId == 70L })
        out == [70L]
    }
}
