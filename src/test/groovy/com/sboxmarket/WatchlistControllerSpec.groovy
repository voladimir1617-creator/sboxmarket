package com.sboxmarket

import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.controller.WatchlistController
import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.Item
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.WatchlistItemRepository
import com.sboxmarket.service.WatchlistService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import spock.lang.Specification
import spock.lang.Subject

/**
 * Coverage for the server-side watchlist endpoints. Two surfaces are
 * particularly bug-prone and get extra attention:
 *
 * 1. `bulkCounts()` — public "N watching" chip data. Parser must
 *    tolerate malformed ids (skip, don't 400), cap at 200 ids to
 *    bound a crafted query, omit items with 0 watchers to keep the
 *    JSON tight, and return the 60s shared-cache header for CDN
 *    fan-out. All four properties pinned here.
 *
 * 2. `bulkMerge()` — merges a user's anonymous localStorage watchlist
 *    into the server-side set on first sign-in. Must tolerate mixed
 *    types + nulls + non-numeric strings in `ids` without crashing.
 *
 * Every authenticated endpoint (list / star / unstar / clear /
 * bulkMerge / exportCsv) requires a session; anon → 401.
 *
 * Batch 1068 — added to close the coverage gap identified by walking
 * the controller list vs. the test directory.
 */
class WatchlistControllerSpec extends Specification {

    WatchlistService         service             = Mock()
    WatchlistItemRepository  itemRepository      = Mock()
    ItemRepository           catalogueRepository = Mock()

    @Subject
    WatchlistController controller = new WatchlistController(
        service             : service,
        itemRepository      : itemRepository,
        catalogueRepository : catalogueRepository
    )

    HttpServletRequest req = Mock()
    HttpSession        ses = Mock()

    private void anonSession() {
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> null
    }
    private void authedSession(long uid = 100L) {
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> uid
    }

    // ── auth gating ────────────────────────────────────────────────

    def "list() requires sign-in"() {
        given: anonSession()
        when:  controller.list(req)
        then:  thrown(UnauthorizedException)
        0 * service.list(_)
    }

    def "star() requires sign-in"() {
        given: anonSession()
        when:  controller.star(1L, req)
        then:  thrown(UnauthorizedException)
        0 * service.add(_, _)
    }

    def "unstar() requires sign-in"() {
        given: anonSession()
        when:  controller.unstar(1L, req)
        then:  thrown(UnauthorizedException)
        0 * service.remove(_, _)
    }

    def "clear() requires sign-in"() {
        given: anonSession()
        when:  controller.clear(req)
        then:  thrown(UnauthorizedException)
        0 * service.clear(_)
    }

    def "bulkMerge() requires sign-in"() {
        given: anonSession()
        when:  controller.bulkMerge([ids: [1L, 2L]], req)
        then:  thrown(UnauthorizedException)
        0 * service.bulkMerge(_, _)
    }

    // ── star / unstar envelopes ───────────────────────────────────

    def "star() returns {itemId, added, ids} so client can trust optimistic update"() {
        given:
        // Two session reads because star() calls requireUser() THEN service.list()
        // which doesn't touch session — only one session read actually happens.
        authedSession(100L)
        1 * service.add(100L, 42L) >> true
        1 * service.list(100L) >> [10L, 20L, 42L]

        when:
        def resp = controller.star(42L, req)

        then:
        resp.body == [itemId: 42L, added: true, ids: [10L, 20L, 42L]]
    }

    def "star() returns added:false when the row already existed (idempotent)"() {
        given:
        authedSession(100L)
        1 * service.add(100L, 42L) >> false
        1 * service.list(100L) >> [42L]

        when:
        def resp = controller.star(42L, req)

        then:
        resp.body.added == false
    }

    def "unstar() returns {itemId, removed, ids}"() {
        given:
        authedSession(100L)
        1 * service.remove(100L, 42L) >> true
        1 * service.list(100L) >> [10L, 20L]

        when:
        def resp = controller.unstar(42L, req)

        then:
        resp.body == [itemId: 42L, removed: true, ids: [10L, 20L]]
    }

    def "clear() is idempotent (zero-row caller gets {cleared:0}, not 404)"() {
        given:
        authedSession(100L)
        1 * service.clear(100L) >> 0

        when:
        def resp = controller.clear(req)

        then:
        resp.body == [cleared: 0]
    }

    // ── bulkCounts (public) ───────────────────────────────────────

    def "bulkCounts() returns {} when the ids param is missing"() {
        when:
        def resp = controller.bulkCounts(null)

        then:
        0 * itemRepository.countByItemIds(_)
        resp.body == [:]
    }

    def "bulkCounts() returns {} when ids is blank"() {
        when:
        def resp = controller.bulkCounts('   ')

        then:
        0 * itemRepository.countByItemIds(_)
        resp.body == [:]
    }

    def "bulkCounts() parses a valid comma-separated list and omits zero-watcher items"() {
        given:
        List<Long> captured = null
        1 * itemRepository.countByItemIds(_) >> { args ->
            captured = args[0]
            [
                ([1L, 5L] as Object[]),
                ([2L, 0L] as Object[]),   // omitted from response
                ([3L, 2L] as Object[])
            ]
        }

        when:
        def resp = controller.bulkCounts('1,2,3')

        then:
        captured == [1L, 2L, 3L]
        resp.body == [1L: 5L, 3L: 2L]
        resp.body.size() == 2
    }

    def "bulkCounts() skips malformed tokens without 400ing the whole request"() {
        given:
        List<Long> captured = null
        1 * itemRepository.countByItemIds(_) >> { args ->
            captured = args[0]
            [([7L, 3L] as Object[])]
        }

        when:
        def resp = controller.bulkCounts('abc,7,,xyz')

        then: 'only valid tokens reach the repo query'
        captured == [7L]
        resp.body == [7L: 3L]
    }

    def "bulkCounts() caps the id list at 200 to bound a crafted query"() {
        given: 'request with 250 ids'
        def lots = (1..250).collect { String.valueOf(it) }.join(',')
        int seenSize = -1
        1 * itemRepository.countByItemIds(_) >> { args ->
            seenSize = args[0].size()
            []
        }

        when:
        controller.bulkCounts(lots)

        then: 'first 200 survive the cap'
        seenSize == 200
    }

    def "bulkCounts() carries the public 60s cache header for CDN fan-out"() {
        given:
        1 * itemRepository.countByItemIds(_) >> [([1L, 1L] as Object[])]

        when:
        def resp = controller.bulkCounts('1')

        then:
        def cc = resp.headers.getFirst('Cache-Control')
        cc?.contains('public')
        cc?.contains('max-age=60')
    }

    def "bulkCounts() skips negative ids (defensive — ids must be > 0)"() {
        given:
        List<Long> captured = null
        1 * itemRepository.countByItemIds(_) >> { args ->
            captured = args[0]
            []
        }

        when:
        controller.bulkCounts('-1,-99,5')

        then: 'negative ids filtered, only 5 survives'
        captured == [5L]
    }

    def "bulkCounts() returns {} when every token is malformed (no service call)"() {
        when:
        def resp = controller.bulkCounts('abc,xyz,,')

        then:
        0 * itemRepository.countByItemIds(_)
        resp.body == [:]
    }

    // ── bulkMerge ────────────────────────────────────────────────

    def "bulkMerge() handles a valid id list"() {
        given:
        authedSession(100L)
        1 * service.bulkMerge(100L, [1L, 2L, 3L]) >> [1L, 2L, 3L, 99L]

        when:
        def resp = controller.bulkMerge([ids: [1L, 2L, 3L]], req)

        then:
        resp.body == [ids: [1L, 2L, 3L, 99L]]
    }

    def "bulkMerge() coerces mixed types + nulls without throwing"() {
        given:
        authedSession(100L)
        // Input has: valid Long (1), valid Integer (2), null, non-numeric 'x', stringified '42'
        // Only 1L, 2L, 42L should survive into the service call.
        1 * service.bulkMerge(100L, [1L, 2L, 42L]) >> [1L, 2L, 42L]

        when:
        def resp = controller.bulkMerge([ids: [1L, 2, null, 'x', '42']], req)

        then:
        resp.body.ids == [1L, 2L, 42L]
    }

    def "bulkMerge() with null body does NPE (pin current behavior)"() {
        given: authedSession(100L)

        when:
        controller.bulkMerge(null, req)

        then: 'body?.ids as List → null, (null ?: []) branches safely to empty'
        1 * service.bulkMerge(100L, []) >> []
        noExceptionThrown()
    }

    // ── exportCsv ────────────────────────────────────────────────

    def "exportCsv() renders the header row + one row per watchlist item"() {
        given:
        def item1 = new Item(id: 1L, name: 'Hat', category: 'Hats', rarity: 'Standard',
                             lowestPrice: new BigDecimal('9.50'),
                             steamPrice : new BigDecimal('10.00'),
                             supply: 42)
        authedSession(100L)
        1 * service.list(100L) >> [1L]
        1 * catalogueRepository.findAllById([1L]) >> [item1]

        when:
        def resp = controller.exportCsv(req)

        then:
        resp.headers.getFirst('Content-Disposition')?.contains('watchlist.csv')
        resp.headers.getFirst('Cache-Control')?.contains('no-store')

        and: 'exactly header + 1 row'
        def lines = resp.body.split('\n')
        lines[0] == 'item_id,name,category,rarity,current_floor,steam_price,supply'
        lines[1] == '1,Hat,Hats,Standard,9.50,10.00,42'
    }

    def "exportCsv() with empty watchlist returns just the header row"() {
        given:
        authedSession(100L)
        1 * service.list(100L) >> []

        when:
        def resp = controller.exportCsv(req)

        then:
        0 * catalogueRepository.findAllById(_)
        resp.body == 'item_id,name,category,rarity,current_floor,steam_price,supply\n'
    }

    def "exportCsv() requires sign-in"() {
        given: anonSession()
        when:  controller.exportCsv(req)
        then:  thrown(UnauthorizedException)
        0 * service.list(_)
    }
}
