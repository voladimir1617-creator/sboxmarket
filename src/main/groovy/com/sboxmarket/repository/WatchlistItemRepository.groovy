package com.sboxmarket.repository

import com.sboxmarket.model.WatchlistItem
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface WatchlistItemRepository extends JpaRepository<WatchlistItem, Long> {

    /** Item ids the user has starred, oldest-first so the watchlist
     *  page renders in the order they were added (matches the
     *  pre-server-side localStorage behaviour). */
    @Query("SELECT w.itemId FROM WatchlistItem w WHERE w.userId = :uid ORDER BY w.createdAt ASC")
    List<Long> findItemIdsByUser(@Param('uid') Long userId)

    /** Paged companion — long-tenure users can star hundreds of items;
     *  the cap keeps the Watchlist tab open at O(pageSize). */
    @Query("SELECT w.itemId FROM WatchlistItem w WHERE w.userId = :uid ORDER BY w.createdAt ASC")
    List<Long> findItemIdsByUser(@Param('uid') Long userId,
                                  org.springframework.data.domain.Pageable pageable)

    /** Count-only companion for bulkMerge headroom. Avoids hydrating
     *  the full id list just to call .size() on it. */
    @Query("SELECT COUNT(w) FROM WatchlistItem w WHERE w.userId = :uid")
    long countByUser(@Param('uid') Long userId)

    /** Existence probe for the unique-constraint guard at the service
     *  layer — we'd rather return early than catch a ConstraintViolation
     *  and parse it. */
    @Query("""
        SELECT COUNT(w) > 0 FROM WatchlistItem w
        WHERE w.userId = :uid AND w.itemId = :itemId
    """)
    boolean existsByUserAndItem(@Param('uid') Long userId, @Param('itemId') Long itemId)

    @Modifying
    @Query("DELETE FROM WatchlistItem w WHERE w.userId = :uid AND w.itemId = :itemId")
    int deleteByUserAndItem(@Param('uid') Long userId, @Param('itemId') Long itemId)

    /** Bulk-wipe every watchlist row for a single user. Returns the
     *  count removed so the caller can surface "Cleared N items" in
     *  the toast. Used by the "Clear watchlist" button and by the
     *  GDPR deletion flow. */
    @Modifying
    @Query("DELETE FROM WatchlistItem w WHERE w.userId = :uid")
    int deleteByUser(@Param('uid') Long userId)

    @Query("SELECT w.itemId FROM WatchlistItem w WHERE w.userId = :uid AND w.itemId IN :itemIds")
    List<Long> findExistingItemIds(@Param('uid') Long userId,
                                    @Param('itemIds') List<Long> itemIds)

    /** Public watcher count per item — drives the "👁 N watching" badge
     *  on marketplace cards. Aggregate only; no user identities are
     *  ever returned. Empty input is handled by the caller (an `IN ()`
     *  clause is illegal SQL). */
    @Query("""
        SELECT w.itemId, COUNT(w) FROM WatchlistItem w
        WHERE w.itemId IN :itemIds
        GROUP BY w.itemId
    """)
    List<Object[]> countByItemIds(@Param('itemIds') List<Long> itemIds)

    /** Top N most-watched item ids site-wide. Drives the "Most watched"
     *  rail on the marketplace homepage — pure social-proof discovery.
     *  Pageable so the caller can cap row count without an EVAL of
     *  every star ever placed. */
    @Query("""
        SELECT w.itemId, COUNT(w) AS cnt FROM WatchlistItem w
        GROUP BY w.itemId
        ORDER BY COUNT(w) DESC
    """)
    List<Object[]> findTopWatchedItemIds(org.springframework.data.domain.Pageable page)
}
