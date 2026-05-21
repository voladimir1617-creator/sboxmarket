package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.model.CartItem
import com.sboxmarket.repository.CartItemRepository
import com.sboxmarket.service.CartService
import org.springframework.dao.DataIntegrityViolationException
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

    // ── add ──────────────────────────────────────────────────────────

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
        def e = thrown(BadRequestException)
        e.code == 'CART_FULL'
        0 * repository.save(_)
    }

    def "add still succeeds when the user is exactly one below the cap"() {
        given:
        repository.existsByUserAndListing(10L, 999L) >> false
        // MAX_PER_USER - 1 rows → there is room for exactly one more.
        repository.findListingIdsByUser(10L) >> (1L..(CartService.MAX_PER_USER - 1)).collect { it as Long }

        when:
        def added = service.add(10L, 999L)

        then:
        added == true
        1 * repository.save({ it.listingId == 999L })
    }

    def "add short-circuits to false on a null userId or listingId — no repo calls"() {
        when:
        def r1 = service.add(null, 100L)
        def r2 = service.add(10L, null)
        def r3 = service.add(null, null)

        then:
        r1 == false
        r2 == false
        r3 == false
        0 * repository.existsByUserAndListing(_, _)
        0 * repository.save(_)
    }

    // ── remove ───────────────────────────────────────────────────────

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

    def "remove short-circuits to false on null args without touching the repo"() {
        when:
        def r1 = service.remove(null, 100L)
        def r2 = service.remove(10L, null)

        then:
        r1 == false
        r2 == false
        0 * repository.deleteByUserAndListing(_, _)
    }

    // ── clear ────────────────────────────────────────────────────────

    def "clear delegates to deleteAllByUser and returns the count"() {
        when:
        def n = service.clear(10L)

        then:
        1 * repository.deleteAllByUser(10L) >> 7
        n == 7
    }

    def "clear on a null userId returns 0 without a delete"() {
        when:
        def n = service.clear(null)

        then:
        0 * repository.deleteAllByUser(_)
        n == 0
    }

    // ── list ─────────────────────────────────────────────────────────

    def "list delegates to the repo, oldest-first"() {
        given:
        repository.findListingIdsByUser(10L) >> [3L, 7L, 9L]

        expect:
        service.list(10L) == [3L, 7L, 9L]
    }

    def "list on a null userId returns an empty list without a query"() {
        when:
        def out = service.list(null)

        then:
        0 * repository.findListingIdsByUser(_)
        out == []
    }

    // ── bulkMerge ────────────────────────────────────────────────────

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

    def "bulkMerge on a null userId returns an empty list and saves nothing"() {
        when:
        def out = service.bulkMerge(null, [1L, 2L])

        then:
        0 * repository.save(_)
        0 * repository.findExistingListingIds(_, _)
        out == []
    }

    def "bulkMerge treats an all-null input as empty — returns current list, no save"() {
        given:
        repository.findListingIdsByUser(10L) >> [5L]

        when:
        def out = service.bulkMerge(10L, [null, null])

        then:
        0 * repository.findExistingListingIds(_, _)
        0 * repository.save(_)
        out == [5L]
    }

    def "bulkMerge truncates an oversized input to MAX_PER_USER before the existence probe"() {
        given:
        // 60 distinct ids — 10 over the cap. Only the first 50 should be
        // probed / inserted.
        def incoming = (1L..60L).collect { it as Long }
        def capped   = (1L..CartService.MAX_PER_USER).collect { it as Long }
        repository.countByUser(10L) >> 0L
        repository.findListingIdsByUser(10L) >> capped

        when:
        def out = service.bulkMerge(10L, incoming)

        then:
        // Existence probe only ever sees the first 50 ids — the 10 extras
        // are dropped before the query, never persisted. The `>> []`
        // response lives on this interaction (not a separate given:
        // stub) so the counted match still returns a non-null list.
        1 * repository.findExistingListingIds(10L, capped) >> []
        CartService.MAX_PER_USER * repository.save(_)
        out.size() == CartService.MAX_PER_USER
    }

    def "bulkMerge respects remaining headroom — only fills up to the cap"() {
        given:
        // User already holds 48 rows; 5 new ids arrive but only 2 fit.
        def incoming = [101L, 102L, 103L, 104L, 105L]
        repository.findExistingListingIds(10L, incoming) >> []
        repository.countByUser(10L) >> 48L                 // headroom == 2
        repository.findListingIdsByUser(10L) >> ((1L..48L) + [101L, 102L]).collect { it as Long }

        when:
        service.bulkMerge(10L, incoming)

        then:
        // Exactly 2 saved — the oldest two of the toAdd list.
        1 * repository.save({ it.listingId == 101L })
        1 * repository.save({ it.listingId == 102L })
        0 * repository.save({ it.listingId == 103L })
        0 * repository.save({ it.listingId == 104L })
        0 * repository.save({ it.listingId == 105L })
    }

    def "bulkMerge short-circuits when the user is already at the cap — no save, returns current list"() {
        given:
        repository.findExistingListingIds(10L, [900L]) >> []
        // Parenthesise the cast: Spock's `>>` binds tighter than `as`, so
        // `>> CartService.MAX_PER_USER as long` parses as
        // `(... >> MAX_PER_USER) as long` — the cast lands on the
        // interaction object, not the stubbed value, so countByUser does
        // not reliably return 50 and the headroom guard never fires.
        repository.countByUser(10L) >> (CartService.MAX_PER_USER as long)   // headroom == 0
        repository.findListingIdsByUser(10L) >> (1L..CartService.MAX_PER_USER).collect { it as Long }

        when:
        def out = service.bulkMerge(10L, [900L])

        then:
        0 * repository.save(_)
        out.size() == CartService.MAX_PER_USER
    }

    def "bulkMerge skips the existence probe path correctly when every incoming id already exists"() {
        given:
        repository.findExistingListingIds(10L, [1L, 2L]) >> [1L, 2L]
        repository.countByUser(10L) >> 2L
        repository.findListingIdsByUser(10L) >> [1L, 2L]

        when:
        def out = service.bulkMerge(10L, [1L, 2L])

        then:
        0 * repository.save(_)
        out == [1L, 2L]
    }

    /**
     * Regression guard for the bulkMerge constraint-violation fix.
     *
     * The previous implementation wrapped `repository.save` in a
     * try/catch that swallowed the exception and let the loop keep
     * running. That is the exact anti-pattern WatchlistService.bulkMerge
     * documents as wrong: a JPA constraint violation marks the
     * @Transactional rollback-only, so swallowing it does not recover —
     * it just hides the failure behind a debug log while the doomed
     * transaction keeps mutating, and the eventual commit still 500s.
     *
     * Correct behaviour (matching WatchlistService): the save call is
     * NOT wrapped, so a violation from a lost concurrent-insert race
     * propagates straight out — an honest, retryable failure rather than
     * a silently-corrupted half-merge.
     */
    def "bulkMerge does NOT swallow a constraint violation from a concurrent insert"() {
        given:
        repository.findExistingListingIds(10L, [42L]) >> []   // probe says absent…
        repository.countByUser(10L) >> 0L
        // …but a racing tab inserted (10,42) first, so our save loses.
        repository.save(_) >> { throw new DataIntegrityViolationException('uq_cart_items_user_listing') }

        when:
        service.bulkMerge(10L, [42L])

        then:
        // The violation propagates — it is NOT caught and logged away.
        thrown(DataIntegrityViolationException)
    }

    // ── sweepStaleCartRows ───────────────────────────────────────────

    def "sweepStaleCartRows delegates to the repo bulk-delete (batch 506)"() {
        given:
        repository.deleteRowsPointingAtNonActiveListings() >> 7

        when:
        service.sweepStaleCartRows()

        then:
        1 * repository.deleteRowsPointingAtNonActiveListings()
        notThrown(Exception)
    }

    def "sweepStaleCartRows is a clean no-op when there are no stale rows"() {
        given:
        repository.deleteRowsPointingAtNonActiveListings() >> 0

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
