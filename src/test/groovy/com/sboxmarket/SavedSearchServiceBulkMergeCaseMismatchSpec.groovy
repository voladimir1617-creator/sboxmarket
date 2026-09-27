package com.sboxmarket

import com.sboxmarket.model.SavedSearch
import com.sboxmarket.repository.SavedSearchRepository
import com.sboxmarket.service.SavedSearchService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.security.BanGuard
import spock.lang.Specification
import spock.lang.Subject

/**
 * Regression: bulkMerge's update/create classifier is case-insensitive
 * (mergeKeyFor lowercases), but the per-row upsert's `findByUserAndName`
 * lookup is case-sensitive at the JPQL layer (`s.name = :name`). An
 * incoming "hats" row against an existing "Hats" preset used to be
 * classified as an UPDATE (no headroom consumed) but then upsert's
 * case-sensitive lookup missed, the row fell into the INSERT branch,
 * and the user ended up with a SECOND row — silently exceeding
 * MAX_PER_USER because the headroom math thought this was a pure edit.
 *
 * Fix: bulkMerge rewrites the incoming row's name to the existing
 * stored name (matching case) before handing off to upsert, so the
 * case-sensitive lookup hits the existing row and overwrites in place.
 */
class SavedSearchServiceBulkMergeCaseMismatchSpec extends Specification {

    SavedSearchRepository repository = Mock()
    BanGuard banGuard = Mock()
    TextSanitizer textSanitizer = new TextSanitizer()

    @Subject
    SavedSearchService service = new SavedSearchService(
        repository: repository, banGuard: banGuard, textSanitizer: textSanitizer)

    def "bulkMerge classifies a different-case incoming name as an UPDATE of the existing row (no cap-busting duplicate)"() {
        // User has 10/10 presets, including one stored as "Hats" (caps).
        // Incoming bulk batch syncs the same preset under "hats"
        // (lowercase — e.g. typed differently on another device).
        // The classifier must recognise this as an update, and upsert's
        // case-sensitive lookup must hit the existing "Hats" row so the
        // overwrite path fires — NOT a new INSERT that would bust the
        // 10-row cap.
        given:
        def existing = (1..10).collect { i ->
            new SavedSearch(id: i as Long, userId: 10L,
                name: i == 1 ? 'Hats' : "preset-${i}",
                category: 'All', rarity: 'All', sort: 'newest')
        }
        repository.countByUser(10L) >> 10L
        repository.findByUser(10L) >> existing
        // Case-sensitive lookups — these mirror the JPQL `s.name = :name`
        // semantics. "Hats" hits the existing row; "hats" (the unfixed
        // path) would miss → null → fall into the cap-blocked INSERT.
        repository.findByUserAndName(10L, 'Hats') >> existing[0]
        repository.findByUserAndName(10L, 'hats') >> null
        existing[1..-1].each { e ->
            repository.findByUserAndName(10L, e.name) >> e
        }

        when:
        // Same preset names but the "Hats" one is sent lowercased — a
        // realistic cross-device sync payload (different casing on the
        // other client).
        service.bulkMerge(10L,
            existing.collect { row ->
                def name = row.name == 'Hats' ? 'hats' : row.name
                [name: name, sort: 'price_asc', category: 'Hats']
            })

        then: 'all 10 lookups land as UPDATES — every save() carries the existing row id, never a fresh insert.'
        // Each save call must carry a non-null id (overwrite branch).
        // Before the fix the "hats" row sailed through save() with id=null
        // (INSERT branch), pushing the user to 11/10 — the cap was busted.
        10 * repository.save({ SavedSearch s -> s.id != null }) >> { SavedSearch s -> s }
        // And critically: NO INSERT ever fires.
        0 * repository.save({ SavedSearch s -> s.id == null })
    }
}
