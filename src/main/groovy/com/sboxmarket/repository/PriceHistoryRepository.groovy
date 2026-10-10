package com.sboxmarket.repository

import com.sboxmarket.model.PriceHistory
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
interface PriceHistoryRepository extends JpaRepository<PriceHistory, Long> {

    @Query("SELECT p FROM PriceHistory p WHERE p.item.id = :itemId ORDER BY p.recordedAt ASC")
    List<PriceHistory> findByItemIdOrdered(@Param("itemId") Long itemId)

    /** Paged companion — bounded by the 400-day retention prune today,
     *  but the cap is a defence against a regression that disables or
     *  delays deleteOlderThan. */
    @Query("SELECT p FROM PriceHistory p WHERE p.item.id = :itemId ORDER BY p.recordedAt ASC")
    List<PriceHistory> findByItemIdOrdered(@Param("itemId") Long itemId, Pageable pageable)

    // findRecentByItemId(itemId, days) removed: the parameter name
    // promised a day-window filter but the body was `LIMIT :days`, a
    // ROW count. PriceHistory can hold more than one row per day
    // (every fresh tick before the same-day coalesce settles), so the
    // contract was a silently truncating sparkline waiting to be
    // wired up. Callers wanting "last N days" must use
    // findByItemIdSince(itemId, cutoff) with a cutoff = now − N·86400_000.
    // Pinned by PriceHistoryRepositoryContractSpec.

    /** ASC-ordered window for the sparkline chart — rows with
     *  `recordedAt >= :cutoff`, oldest-first so the frontend can
     *  `.slice(-N)` to honour its 7D / 1M / 3M / 1Y / ALL range
     *  selectors. Caps hydration at the 400-day window covering ALL
     *  (which renders 365d) with a little headroom for timezone skew.
     *  `cutoff` is epoch millis (UTC by construction), so the window
     *  is timezone-correct regardless of the JVM's default TZ. */
    @Query("SELECT p FROM PriceHistory p WHERE p.item.id = :itemId AND p.recordedAt >= :cutoff ORDER BY p.recordedAt ASC")
    List<PriceHistory> findByItemIdSince(@Param("itemId") Long itemId, @Param("cutoff") Long cutoff)

    /** Most-recent row for an item. Used by PriceHistoryService to decide
     *  whether to coalesce today's update into an existing row or append a
     *  new one. Optional<> because newly indexed items have no history. */
    @Query("SELECT p FROM PriceHistory p WHERE p.item.id = :itemId ORDER BY p.recordedAt DESC LIMIT 1")
    java.util.Optional<PriceHistory> findLatestByItem(@Param("itemId") Long itemId)

    /** Rows for one item on one UTC day label, oldest first. Used to undo a
     *  cancelled sale's write on the day it was recorded. */
    @Query("SELECT p FROM PriceHistory p WHERE p.item.id = :itemId AND p.dayLabel = :day ORDER BY p.recordedAt ASC")
    List<PriceHistory> findByItemIdAndDay(@Param("itemId") Long itemId, @Param("day") String day)

    /**
     * Retention prune. The read path (`findByItemIdSince`) caps hydration
     * at a 400-day window, but until this method existed the write path
     * had no corresponding TTL — rows older than 400 days were inserted
     * forever, then never read. On a long-running pod the `price_history`
     * table grew linearly with sync cadence × catalogue size × tenure,
     * pushing the `idx_price_history_item` index size up for no UI
     * benefit and slowing the chart-hydration query the older the
     * deployment got.
     *
     * `@Modifying(clearAutomatically=true)` so Hibernate's first-level
     * cache is invalidated after the bulk DELETE — otherwise a stale
     * PriceHistory entity could survive in a still-open persistence
     * context and a subsequent read in the same transaction would
     * resurrect a deleted row. `flushAutomatically=true` ensures any
     * pending writes are pushed to the DB before the prune evaluates
     * its WHERE — without it, a row just-saved in the same tx could
     * escape the cutoff filter incorrectly.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("DELETE FROM PriceHistory p WHERE p.recordedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Long cutoff)
}
