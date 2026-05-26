package com.sboxmarket

import com.sboxmarket.repository.PriceHistoryRepository
import spock.lang.Specification

/**
 * Pin for the removal of `findRecentByItemId(Long itemId, int days)`.
 *
 * The old declaration was:
 *
 *   @Query("SELECT p FROM PriceHistory p WHERE p.item.id = :itemId
 *           ORDER BY p.recordedAt DESC LIMIT :days")
 *   List<PriceHistory> findRecentByItemId(@Param("itemId") Long itemId,
 *                                         @Param("days") int days)
 *
 * The parameter name `days` promised a day-window filter (e.g. "last 7
 * days of price snapshots"), but the implementation used `LIMIT :days`
 * — a ROW count. PriceHistory writes can be more frequent than once
 * per day (every fresh price tick after the same-day coalesce window),
 * so passing 7 returns AT MOST 7 rows, not 7 days. Wiring this up to
 * drive a 7D sparkline would silently truncate the chart on any item
 * with sub-daily updates.
 *
 * The method was never called in production (`findByItemIdSince`
 * carries the correct cutoff-based contract for the chart), so the
 * fix is to delete the misleading dead code. This spec pins the
 * removal so a future grep-and-resurrect can't bring it back without
 * also re-failing this contract.
 */
class PriceHistoryRepositoryContractSpec extends Specification {

    def "findRecentByItemId is removed — its days-named LIMIT-row contract was misleading"() {
        expect:
        !PriceHistoryRepository.methods.any { it.name == 'findRecentByItemId' }
    }

    def "the cutoff-based windowing contract findByItemIdSince still exists"() {
        expect:
        // The replacement contract — `cutoff` is epoch-ms; rows with
        // recordedAt >= cutoff. Any future "last N days" use-case must
        // funnel through this method (caller converts days→cutoff) so
        // the day-window promise is not silently row-truncated again.
        PriceHistoryRepository.methods.any { it.name == 'findByItemIdSince' }
    }
}
