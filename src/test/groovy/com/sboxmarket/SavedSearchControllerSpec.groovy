package com.sboxmarket

import com.sboxmarket.controller.SavedSearchController
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.SavedSearch
import com.sboxmarket.service.SavedSearchService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import spock.lang.Specification
import spock.lang.Subject

/**
 * Direct coverage for the server-side saved-searches endpoints. The
 * frontend keeps a localStorage mirror for offline reads; this
 * controller is the source of truth for signed-in users. The
 * serialisation shape (`search` instead of `q`, `savedAt` instead of
 * `createdAt`) is load-bearing for the SPA — a field rename would
 * silently break every existing saved search on every user's client,
 * so we pin the projection in tests.
 *
 * Every endpoint requires auth; anon saved searches stay in
 * localStorage by design. Covers the list / upsert / delete /
 * delete-all / bulk-merge surface.
 *
 * Batch 1068 — added to close the coverage gap identified by walking
 * the controller list vs. the test directory.
 */
class SavedSearchControllerSpec extends Specification {

    SavedSearchService service = Mock()

    @Subject
    SavedSearchController controller = new SavedSearchController(service: service)

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

    def "list() requires sign-in"() {
        given: anonSession()
        when:  controller.list(req)
        then:  thrown(UnauthorizedException)
        0 * service.list(_)
    }

    def "list() projects rows to the client shape with savedAt + search aliases"() {
        given:
        def row = new SavedSearch(
            id: 1L, name: 'cheap hats',
            q: 'hat', category: 'Hats', rarity: 'Standard',
            sort: 'price_asc',
            minPrice: '1', maxPrice: '10',
            createdAt: 1700_000_000_000L
        )
        authedSession(100L)
        1 * service.list(100L) >> [row]

        when:
        def resp = controller.list(req)

        then:
        resp.statusCode.value() == 200
        def r = resp.body[0]
        r.id == 1L
        r.name == 'cheap hats'
        r.search == 'hat'             // projected alias, not raw `q`
        r.savedAt == 1700_000_000_000L // projected alias, not raw `createdAt`
        r.category == 'Hats'
        r.rarity == 'Standard'
        r.sort == 'price_asc'
        r.minPrice == '1'
        r.maxPrice == '10'
    }

    def "list() projects the batch-957 extended filter fields so applySavedSearch can restore them"() {
        // Regression: the entity, the service upsert path and the
        // frontend `applySavedSearch` were all updated for the richer
        // toolbar, but `toMap` was missed — a signed-in user who
        // re-applied a preset silently lost "≥20% off / Auctions / New
        // / Deals / Affordable" because the server never sent them back.
        given:
        def row = new SavedSearch(
            id: 2L, name: 'rich preset',
            q: '', category: 'Hats', rarity: 'All', sort: 'newest',
            minPrice: '', maxPrice: '',
            minDiscountPct: 25, dealsOnly: true, newOnly: true,
            affordableOnly: false, listingType: 'AUCTION',
            createdAt: 1700_000_000_000L
        )
        authedSession(100L)
        1 * service.list(100L) >> [row]

        when:
        def r = controller.list(req).body[0]

        then: 'all five extended fields ride the projection under the keys applySavedSearch reads'
        r.minDiscountPct == 25
        r.dealsOnly == true
        r.newOnly == true
        r.affordableOnly == false
        r.listingType == 'AUCTION'
    }

    def "upsert() echoes the extended filter fields back to the client"() {
        given:
        def saved = new SavedSearch(
            id: 9L, name: 'rich', q: '', category: 'Hats', rarity: 'All',
            sort: 'price_desc', minPrice: '', maxPrice: '',
            minDiscountPct: 30, dealsOnly: false, newOnly: true,
            affordableOnly: true, listingType: 'BUY_NOW',
            createdAt: 1700_000_001_000L)
        authedSession(100L)
        1 * service.upsert(100L, _) >> saved

        when:
        def body = controller.upsert([name: 'rich', minDiscountPct: 30], req).body

        then:
        body.minDiscountPct == 30
        body.dealsOnly == false
        body.newOnly == true
        body.affordableOnly == true
        body.listingType == 'BUY_NOW'
    }

    def "bulkMerge() echoes the extended filter fields in the merged projection"() {
        given:
        def merged = new SavedSearch(
            id: 1L, name: 'merged', q: '', category: 'All', rarity: 'All',
            sort: 'price_desc', minPrice: '', maxPrice: '',
            minDiscountPct: 10, dealsOnly: true, newOnly: false,
            affordableOnly: false, listingType: 'AUCTION',
            createdAt: 1700_000_002_000L)
        authedSession(100L)
        1 * service.bulkMerge(100L, _) >> [merged]

        when:
        def entry = controller.bulkMerge([entries: [[name: 'merged']]], req).body.entries[0]

        then:
        entry.minDiscountPct == 10
        entry.dealsOnly == true
        entry.newOnly == false
        entry.listingType == 'AUCTION'
    }

    def "upsert() delegates to the service with the raw body"() {
        given:
        def saved = new SavedSearch(id: 9L, name: 'new save', q: 'hat', sort: 'price_asc',
                                    category: 'Hats', rarity: 'All', minPrice: '', maxPrice: '',
                                    createdAt: 1700_000_001_000L)
        authedSession(100L)
        1 * service.upsert(100L, [name: 'new save', q: 'hat']) >> saved

        when:
        def resp = controller.upsert([name: 'new save', q: 'hat'], req)

        then:
        resp.body.id == 9L
        resp.body.search == 'hat'
        resp.body.savedAt == 1700_000_001_000L
    }

    def "upsert() requires sign-in"() {
        given: anonSession()
        when:  controller.upsert([name: 'x'], req)
        then:  thrown(UnauthorizedException)
        0 * service.upsert(_, _)
    }

    def "upsert() propagates a ForbiddenException from the service ban guard"() {
        given:
        authedSession(100L)
        1 * service.upsert(100L, [name: 'x']) >> {
            throw new ForbiddenException('Your account is banned: x')
        }

        when:
        controller.upsert([name: 'x'], req)

        then: 'a banned user gets the 403 surfaced, not a 200'
        thrown(ForbiddenException)
    }

    def "upsert() propagates a BadRequestException when the cap is hit"() {
        given:
        authedSession(100L)
        1 * service.upsert(100L, _) >> {
            throw new BadRequestException('SAVED_SEARCHES_FULL', 'full')
        }

        when:
        controller.upsert([name: 'over the cap'], req)

        then:
        thrown(BadRequestException)
    }

    def "delete() returns {id, removed} envelope"() {
        given:
        authedSession(100L)
        1 * service.delete(100L, 42L) >> true

        when:
        def resp = controller.delete(42L, req)

        then:
        resp.statusCode.value() == 200
        resp.body == [id: 42L, removed: true]
    }

    def "delete() returns {id, removed:false} when row was already gone"() {
        given:
        authedSession(100L)
        1 * service.delete(100L, 42L) >> false

        when:
        def resp = controller.delete(42L, req)

        then:
        resp.body.removed == false
    }

    def "deleteAll() wipes every saved search the user owns"() {
        given:
        authedSession(100L)
        1 * service.deleteAllForUser(100L) >> 5

        when:
        def resp = controller.deleteAll(req)

        then:
        resp.body == [removed: 5]
    }

    def "deleteAll() requires sign-in"() {
        given: anonSession()
        when:  controller.deleteAll(req)
        then:  thrown(UnauthorizedException)
        0 * service.deleteAllForUser(_)
    }

    def "bulkMerge() coerces missing entries to empty list and projects result"() {
        given:
        def merged = new SavedSearch(id: 1L, name: 'only save', q: '', category: 'All',
                                     rarity: 'All', sort: 'price_desc', minPrice: '', maxPrice: '',
                                     createdAt: 1700_000_002_000L)
        authedSession(100L)
        // Entries missing entirely — service still called with empty list, not null
        1 * service.bulkMerge(100L, []) >> [merged]

        when:
        def resp = controller.bulkMerge([:], req)

        then:
        resp.body.entries.size() == 1
        resp.body.entries[0].id == 1L
        resp.body.entries[0].savedAt == 1700_000_002_000L
    }

    def "bulkMerge() passes through a list of entries unchanged"() {
        given:
        def entries = [[name: 'a'], [name: 'b']]
        authedSession(100L)
        1 * service.bulkMerge(100L, entries) >> []

        when:
        def resp = controller.bulkMerge([entries: entries], req)

        then:
        resp.body.entries == []
    }

    def "bulkMerge() ignores a non-list `entries` field instead of 500ing"() {
        given:
        authedSession(100L)
        // entries is a string — controller falls through to []
        1 * service.bulkMerge(100L, []) >> []

        when:
        def resp = controller.bulkMerge([entries: 'not a list'], req)

        then: 'defensive coercion, never a ClassCastException'
        resp.body.entries == []
    }
}
