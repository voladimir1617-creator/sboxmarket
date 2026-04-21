package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.model.WatchlistItem
import com.sboxmarket.repository.WatchlistItemRepository
import com.sboxmarket.service.WatchlistService
import spock.lang.Specification
import spock.lang.Subject

/**
 * Pure-logic coverage for the cross-device watchlist persistence
 * (batch 262 / V30). Behaviour pinned:
 *   - add() is idempotent (no-op + returns false when already starred)
 *   - add() rejects when over MAX_PER_USER
 *   - remove() returns true only when a row was actually removed
 *   - bulkMerge() dedupes input, skips ids already starred, respects
 *     headroom against the cap, returns the post-merge list
 *   - bulkMerge() truncates a giant input to MAX_PER_USER
 */
class WatchlistServiceSpec extends Specification {

    WatchlistItemRepository repository = Mock()

    @Subject
    WatchlistService service = new WatchlistService(repository: repository)

    def "add inserts when not already starred"() {
        given:
        repository.existsByUserAndItem(10L, 100L) >> false
        repository.findItemIdsByUser(10L) >> [200L, 201L]   // headroom is fine

        when:
        def added = service.add(10L, 100L)

        then:
        added == true
        1 * repository.save({ it.userId == 10L && it.itemId == 100L })
    }

    def "add is idempotent — already starred returns false without saving"() {
        given:
        repository.existsByUserAndItem(10L, 100L) >> true

        when:
        def added = service.add(10L, 100L)

        then:
        added == false
        0 * repository.save(_)
    }

    def "add rejects when the user is at MAX_PER_USER"() {
        given:
        repository.existsByUserAndItem(10L, 999L) >> false
        repository.findItemIdsByUser(10L) >> (1L..WatchlistService.MAX_PER_USER).collect { it as Long }

        when:
        service.add(10L, 999L)

        then:
        thrown(BadRequestException)
        0 * repository.save(_)
    }

    def "remove returns true when a row was deleted, false when nothing matched"() {
        when:
        def hit  = service.remove(10L, 100L)
        def miss = service.remove(10L, 999L)

        then:
        1 * repository.deleteByUserAndItem(10L, 100L) >> 1
        1 * repository.deleteByUserAndItem(10L, 999L) >> 0
        hit == true
        miss == false
    }

    def "bulkMerge dedupes input, skips existing rows, returns post-merge list"() {
        given:
        // Existing on server: [1L, 2L]. Client also sent 2L (already there)
        // and a duplicate 3L. Expect saves for 3L and 4L only.
        repository.findExistingItemIds(10L, [2L, 3L, 4L]) >> [2L]
        repository.countByUser(10L) >> 2L                   // headroom calc (COUNT query)
        repository.findItemIdsByUser(10L) >> [1L, 2L, 3L, 4L]  // post-merge fetch

        when:
        def out = service.bulkMerge(10L, [2L, 3L, 3L, 4L, null])

        then:
        1 * repository.save({ it.itemId == 3L })
        1 * repository.save({ it.itemId == 4L })
        0 * repository.save({ it.itemId == 2L })
        out == [1L, 2L, 3L, 4L]
    }

    def "bulkMerge truncates a runaway input to MAX_PER_USER"() {
        given:
        def hugeInput = (1L..(WatchlistService.MAX_PER_USER + 50)).collect { it as Long }
        repository.findExistingItemIds(10L, _) >> { args -> [] }
        repository.countByUser(10L) >> 0L  // headroom = MAX_PER_USER (empty watchlist)
        repository.findItemIdsByUser(10L) >> hugeInput.take(WatchlistService.MAX_PER_USER)

        when:
        def out = service.bulkMerge(10L, hugeInput)

        then:
        // Save should be called at most MAX_PER_USER times
        WatchlistService.MAX_PER_USER * repository.save(_)
        out.size() == WatchlistService.MAX_PER_USER
    }

    def "bulkMerge no-ops on empty input but still returns current list"() {
        given:
        repository.findItemIdsByUser(10L) >> [1L, 2L]

        when:
        def out = service.bulkMerge(10L, [])

        then:
        0 * repository.save(_)
        out == [1L, 2L]
    }

    // ── clear (batch 293) ─────────────────────────────────────────

    def "clear forwards to the repo bulk delete and returns the count"() {
        given:
        repository.deleteByUser(10L) >> 7

        when:
        int n = service.clear(10L)

        then:
        n == 7
    }

    def "clear returns 0 for a zero-row user without logging a noisy success"() {
        given:
        repository.deleteByUser(10L) >> 0

        when:
        int n = service.clear(10L)

        then:
        n == 0
    }

    def "clear short-circuits on null user id without hitting the repo"() {
        when:
        int n = service.clear(null)

        then:
        n == 0
        0 * repository.deleteByUser(_)
    }
}
