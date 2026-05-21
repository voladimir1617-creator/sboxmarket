package com.sboxmarket.controller

import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.SavedSearch
import com.sboxmarket.service.SavedSearchService
import groovy.util.logging.Slf4j
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Server-side saved-search persistence — companion to the local
 * `sb_saved_searches` array. The frontend keeps the cache for offline
 * reads; this controller is the source of truth for signed-in users.
 *
 * Anonymous callers see 401 — anonymous saved searches stay in
 * localStorage by design. The frontend bridges the two on first
 * sign-in via /bulk.
 */
@RestController
@RequestMapping('/api/saved-searches')
@Slf4j
class SavedSearchController {

    @Autowired SavedSearchService service

    private Long requireUser(HttpServletRequest req) {
        def uid = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (uid == null) throw new UnauthorizedException()
        uid
    }

    @GetMapping
    ResponseEntity<List<Map>> list(HttpServletRequest req) {
        def uid = requireUser(req)
        ResponseEntity.ok(service.list(uid).collect { toMap(it) })
    }

    @PostMapping
    ResponseEntity<Map> upsert(@RequestBody Map body, HttpServletRequest req) {
        def uid = requireUser(req)
        def saved = service.upsert(uid, body)
        ResponseEntity.ok(toMap(saved))
    }

    @DeleteMapping('/{id}')
    ResponseEntity<Map> delete(@PathVariable Long id, HttpServletRequest req) {
        def uid = requireUser(req)
        def removed = service.delete(uid, id)
        ResponseEntity.ok([id: id, removed: removed])
    }

    /** Bulk-delete — wipes every saved search the user owns. Parity
     *  with /watchlist DELETE-all and the "Unfollow all" affordance on
     *  seller follows. Batch 353. */
    @DeleteMapping
    ResponseEntity<Map> deleteAll(HttpServletRequest req) {
        def uid = requireUser(req)
        int removed = service.deleteAllForUser(uid)
        ResponseEntity.ok([removed: removed])
    }

    @PostMapping('/bulk')
    ResponseEntity<Map> bulkMerge(@RequestBody Map body, HttpServletRequest req) {
        def uid = requireUser(req)
        def raw = body?.entries
        def list = (raw instanceof List) ? (raw as List) : []
        def merged = service.bulkMerge(uid, list as List<Map>)
        ResponseEntity.ok([entries: merged.collect { toMap(it) }])
    }

    private static Map toMap(SavedSearch s) {
        [
            id:        s.id,
            name:      s.name,
            search:    s.q,
            category:  s.category,
            rarity:    s.rarity,
            sort:      s.sort,
            minPrice:  s.minPrice,
            maxPrice:  s.maxPrice,
            // Batch 957 extended filters. The entity, the service upsert
            // path, the `matches()` predicate and the frontend's
            // `applySavedSearch` were all updated for the richer toolbar,
            // but this projection was missed — so a signed-in user who
            // re-applied a preset silently lost "≥20% off / Auctions /
            // New / Deals / Affordable" because the server never sent
            // them back. Keys mirror what `applySavedSearch` reads.
            minDiscountPct:  s.minDiscountPct,
            dealsOnly:       s.dealsOnly,
            newOnly:         s.newOnly,
            affordableOnly:  s.affordableOnly,
            listingType:     s.listingType,
            savedAt:   s.createdAt
        ]
    }
}
