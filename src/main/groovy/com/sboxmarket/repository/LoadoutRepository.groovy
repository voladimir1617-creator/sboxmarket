package com.sboxmarket.repository

import com.sboxmarket.model.Item
import com.sboxmarket.model.Loadout
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface LoadoutRepository extends JpaRepository<Loadout, Long> {

    @Query("SELECT l FROM Loadout l WHERE l.ownerUserId = :uid ORDER BY l.updatedAt DESC")
    List<Loadout> findByOwner(@Param("uid") Long uid)

    // `l.id ASC` is the final, always-unique tiebreaker so the order is
    // fully deterministic — without it two loadouts with equal favorites
    // (and updatedAt) sort nondeterministically across requests, which
    // destabilises pagination and the lowest-id redirect fallback.
    @Query("SELECT l FROM Loadout l WHERE l.visibility = 'PUBLIC' ORDER BY l.favorites DESC, l.updatedAt DESC, l.id ASC")
    List<Loadout> findPublic(Pageable page)

    @Query("SELECT l FROM Loadout l WHERE l.visibility = 'PUBLIC' AND LOWER(l.name) LIKE LOWER(CONCAT('%', :q, '%')) ORDER BY l.favorites DESC, l.updatedAt DESC, l.id ASC")
    List<Loadout> searchPublic(@Param("q") String q, Pageable page)

    long countByOwnerUserId(Long ownerUserId)

    /** Cheapest catalogue item in a category that fits a budget ceiling AND
     *  is not one of the already-picked item ids. Used by
     *  `LoadoutService.autoGenerate` so the AI-Generate fill never drops the
     *  same item into two slots — the `Wild` slot uses `category = ''`
     *  (any category), which without this exclusion almost always lands the
     *  globally cheapest item that another category slot already holds.
     *
     *  Empty string in `category` means "any category" (Wild-card slot).
     *  JPQL forbids `NOT IN ()`, so the caller always passes a non-empty
     *  `exclude` collection — a sentinel `[-1L]` on the first pick, since
     *  item ids are positive IDENTITY values and never collide with -1.
     *  Caller passes `PageRequest.of(0, 1)` since we only need the cheapest. */
    @Query("""
        SELECT i FROM Item i
        WHERE (:category = '' OR i.category = :category)
          AND i.lowestPrice IS NOT NULL
          AND i.lowestPrice > 0
          AND i.lowestPrice <= :budget
          AND i.id NOT IN :exclude
        ORDER BY i.lowestPrice ASC
    """)
    List<Item> findCheapestInBudgetExcluding(
        @Param("category") String category,
        @Param("budget") BigDecimal budget,
        @Param("exclude") Collection<Long> exclude,
        Pageable page
    )
}
