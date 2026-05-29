package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.model.WatchlistItem
import com.sboxmarket.repository.WatchlistItemRepository
import com.sboxmarket.service.WatchlistService
import com.sboxmarket.service.security.BanGuard
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
    BanGuard banGuard = Mock()

    @Subject
    WatchlistService service = new WatchlistService(repository: repository, banGuard: banGuard)

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

    // ── null-argument guards ──────────────────────────────────────

    def "add short-circuits on a null user id or item id without touching the repo"() {
        when:
        def a = service.add(null, 100L)
        def b = service.add(10L, null)

        then:
        a == false
        b == false
        0 * repository.existsByUserAndItem(_, _)
        0 * repository.save(_)
    }

    def "remove short-circuits on a null user id or item id without touching the repo"() {
        when:
        def a = service.remove(null, 100L)
        def b = service.remove(10L, null)

        then:
        a == false
        b == false
        0 * repository.deleteByUserAndItem(_, _)
    }

    def "list returns an empty list for a null user id without hitting the repo"() {
        when:
        def out = service.list(null)

        then:
        out == []
        0 * repository.findItemIdsByUser(_)
    }

    def "bulkMerge returns an empty list for a null user id without hitting the repo"() {
        when:
        def out = service.bulkMerge(null, [1L, 2L])

        then:
        out == []
        0 * repository.save(_)
        0 * repository.findExistingItemIds(_, _)
    }

    // ── cap boundary ──────────────────────────────────────────────

    def "add succeeds at exactly MAX_PER_USER - 1 (the last open slot)"() {
        given:
        repository.existsByUserAndItem(10L, 999L) >> false
        // One slot short of the cap — the new star must land.
        repository.findItemIdsByUser(10L) >> (1L..(WatchlistService.MAX_PER_USER - 1)).collect { it as Long }

        when:
        def added = service.add(10L, 999L)

        then:
        added == true
        1 * repository.save({ it.itemId == 999L })
    }

    // ── bulkMerge headroom against the per-user cap ────────────────

    def "bulkMerge only saves up to the remaining headroom when the user is near the cap"() {
        given:
        // User already holds (MAX_PER_USER - 3) rows → only 3 slots left.
        // Client posts 10 fresh ids; exactly 3 must be saved.
        def incoming = (9000L..9009L).collect { it as Long }
        repository.findExistingItemIds(10L, incoming) >> []
        // Parenthesise the cast: Spock's `>>` binds tighter than `as`, so
        // `>> X as long` parses as `(mock() >> X) as long` — the cast wraps
        // the whole interaction, Spock fails to register the stub, and
        // countByUser falls back to the mock default 0 (headroom balloons
        // to MAX, every id saves → TooManyInvocations).
        repository.countByUser(10L) >> ((WatchlistService.MAX_PER_USER - 3) as long)
        repository.findItemIdsByUser(10L) >> [1L, 2L, 3L]   // post-merge fetch (shape only)

        when:
        def out = service.bulkMerge(10L, incoming)

        then: 'exactly 3 saves — the open headroom — never more'
        3 * repository.save(_)
        out == [1L, 2L, 3L]
    }

    def "bulkMerge saves nothing once the user is already at the cap"() {
        given:
        repository.findExistingItemIds(10L, [9000L, 9001L]) >> []
        repository.countByUser(10L) >> (WatchlistService.MAX_PER_USER as long)  // no headroom
        repository.findItemIdsByUser(10L) >> [1L, 2L]

        when:
        def out = service.bulkMerge(10L, [9000L, 9001L])

        then: 'headroom <= 0 → early return, current list echoed back, zero writes'
        0 * repository.save(_)
        out == [1L, 2L]
    }

    def "bulkMerge skips every incoming id when they are all already starred"() {
        given:
        repository.findExistingItemIds(10L, [1L, 2L, 3L]) >> [1L, 2L, 3L]
        repository.countByUser(10L) >> 3L
        repository.findItemIdsByUser(10L) >> [1L, 2L, 3L]

        when:
        def out = service.bulkMerge(10L, [1L, 2L, 3L])

        then:
        0 * repository.save(_)
        out == [1L, 2L, 3L]
    }

    // ── Ban guard ───────────────────────────────────────────────────
    // A watchlist row wires the user into the WatchlistAlertService
    // price-drop fanout (bell + email pings forever). A banned account
    // must not be able to plant new alerts; mirrors LoadoutService.create
    // and SavedSearchService.upsert.

    def "add rejects a banned user before any repo work"() {
        given:
        banGuard.assertNotBanned(10L) >> { throw new ForbiddenException('Your account is banned: x') }

        when:
        service.add(10L, 100L)

        then:
        thrown(ForbiddenException)
        // Ban check fires first — no existence probe, no cap query, no save.
        0 * repository.existsByUserAndItem(_, _)
        0 * repository.findItemIdsByUser(_)
        0 * repository.save(_)
    }

    def "add consults the ban guard exactly once on the happy path"() {
        given:
        repository.existsByUserAndItem(10L, 100L) >> false
        repository.findItemIdsByUser(10L) >> []

        when:
        service.add(10L, 100L)

        then:
        1 * banGuard.assertNotBanned(10L)
        1 * repository.save(_)
    }

    def "bulkMerge rejects a banned user before any repo work"() {
        given:
        banGuard.assertNotBanned(10L) >> { throw new ForbiddenException('Your account is banned: x') }

        when:
        service.bulkMerge(10L, [1L, 2L, 3L])

        then:
        thrown(ForbiddenException)
        // Migration endpoint must not let a banned account re-seed an entire
        // watchlist via the bulk path and bypass the per-row check on add().
        0 * repository.findExistingItemIds(_, _)
        0 * repository.countByUser(_)
        0 * repository.save(_)
    }
}
