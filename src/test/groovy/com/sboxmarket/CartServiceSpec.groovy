package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.model.CartItem
import com.sboxmarket.repository.CartItemRepository
import com.sboxmarket.service.CartService
import spock.lang.Specification
import spock.lang.Subject

/**
 * Pure-logic coverage for the cross-device cart persistence (batch
 * 263 / V31). Mirrors WatchlistServiceSpec — same shape because both
 * services follow the same idempotent-add / dedup-merge pattern.
 */
class CartServiceSpec extends Specification {

    CartItemRepository repository = Mock()

    @Subject
    CartService service = new CartService(repository: repository)

    def "add inserts when not already in cart"() {
        given:
        repository.existsByUserAndListing(10L, 100L) >> false
        repository.findListingIdsByUser(10L) >> [200L]   // headroom fine

        when:
        def added = service.add(10L, 100L)

        then:
        added == true
        1 * repository.save({ it.userId == 10L && it.listingId == 100L })
    }

    def "add is idempotent — already there returns false without saving"() {
        given:
        repository.existsByUserAndListing(10L, 100L) >> true

        when:
        def added = service.add(10L, 100L)

        then:
        added == false
        0 * repository.save(_)
    }

    def "add rejects when the user is at MAX_PER_USER"() {
        given:
        repository.existsByUserAndListing(10L, 999L) >> false
        repository.findListingIdsByUser(10L) >> (1L..CartService.MAX_PER_USER).collect { it as Long }

        when:
        service.add(10L, 999L)

        then:
        thrown(BadRequestException)
        0 * repository.save(_)
    }

    def "remove returns true on hit / false on miss"() {
        when:
        def hit  = service.remove(10L, 100L)
        def miss = service.remove(10L, 999L)

        then:
        1 * repository.deleteByUserAndListing(10L, 100L) >> 1
        1 * repository.deleteByUserAndListing(10L, 999L) >> 0
        hit == true
        miss == false
    }

    def "clear delegates to deleteAllByUser and returns the count"() {
        when:
        def n = service.clear(10L)

        then:
        1 * repository.deleteAllByUser(10L) >> 7
        n == 7
    }

    def "bulkMerge dedupes input, skips existing rows, returns post-merge list"() {
        given:
        repository.findExistingListingIds(10L, [2L, 3L, 4L]) >> [2L]
        repository.countByUser(10L) >> 2L                  // headroom calc (COUNT query)
        repository.findListingIdsByUser(10L) >> [1L, 2L, 3L, 4L]  // post-merge fetch

        when:
        def out = service.bulkMerge(10L, [2L, 3L, 3L, 4L, null])

        then:
        1 * repository.save({ it.listingId == 3L })
        1 * repository.save({ it.listingId == 4L })
        0 * repository.save({ it.listingId == 2L })
        out == [1L, 2L, 3L, 4L]
    }

    def "bulkMerge no-ops on empty input but still returns current list"() {
        given:
        repository.findListingIdsByUser(10L) >> [1L, 2L]

        when:
        def out = service.bulkMerge(10L, [])

        then:
        0 * repository.save(_)
        out == [1L, 2L]
    }

    def "sweepStaleCartRows delegates to the repo bulk-delete (batch 506)"() {
        given:
        repository.deleteRowsPointingAtNonActiveListings() >> 7

        when:
        service.sweepStaleCartRows()

        then:
        1 * repository.deleteRowsPointingAtNonActiveListings()
        notThrown(Exception)
    }

    def "sweepStaleCartRows swallows repository failures so the scheduler stays alive (batch 506)"() {
        given:
        repository.deleteRowsPointingAtNonActiveListings() >> { throw new RuntimeException('transient DB issue') }

        when:
        service.sweepStaleCartRows()

        then:
        notThrown(Exception)  // sweep should not propagate the failure
    }
}
