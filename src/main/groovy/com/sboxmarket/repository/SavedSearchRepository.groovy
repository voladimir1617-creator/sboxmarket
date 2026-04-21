package com.sboxmarket.repository

import com.sboxmarket.model.SavedSearch
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface SavedSearchRepository extends JpaRepository<SavedSearch, Long> {

    @Query("SELECT s FROM SavedSearch s WHERE s.userId = :uid ORDER BY s.createdAt DESC")
    List<SavedSearch> findByUser(@Param('uid') Long userId)

    /** Per-user lookup by name — used by the upsert path so re-saving
     *  a preset under the same label overwrites the row's filter
     *  contents without producing a duplicate. */
    @Query("SELECT s FROM SavedSearch s WHERE s.userId = :uid AND s.name = :name")
    SavedSearch findByUserAndName(@Param('uid') Long userId,
                                   @Param('name') String name)

    @Modifying
    @Query("DELETE FROM SavedSearch s WHERE s.userId = :uid AND s.id = :id")
    int deleteByUserAndId(@Param('uid') Long userId, @Param('id') Long id)

    /** Wipe every saved search for one user. Used by the GDPR
     *  finalizeDeletion flow so a deleted user's preference rows don't
     *  keep running against the saved-search sweeper forever. */
    @Modifying
    @Query("DELETE FROM SavedSearch s WHERE s.userId = :uid")
    int deleteByUser(@Param('uid') Long userId)

    @Query("SELECT COUNT(s) FROM SavedSearch s WHERE s.userId = :uid")
    long countByUser(@Param('uid') Long userId)

    /** Candidate saved searches for a fresh listing (batch 619). Pre-
     *  filters out presets whose `category` or `rarity` are set and
     *  don't match the listing — reducing the in-memory `matches()`
     *  scan from every-saved-search-on-the-platform down to just the
     *  narrowly-targeted ones. Presets with an empty/null category or
     *  rarity still flow through (they accept anything), same as the
     *  Groovy-side matcher semantics.
     *
     *  Free-text `q` and string-typed `minPrice`/`maxPrice` aren't
     *  filterable cheaply in JPQL — those stay in the service-layer
     *  `matches()` method so the logic is one place.
     */
    @Query("""
        SELECT s FROM SavedSearch s
        WHERE (s.category IS NULL OR s.category = '' OR s.category = 'All' OR s.category = :category)
          AND (s.rarity   IS NULL OR s.rarity   = '' OR s.rarity   = 'All' OR s.rarity   = :rarity)
    """)
    List<SavedSearch> findCandidatesForListing(@Param('category') String category,
                                                @Param('rarity') String rarity)
}
