package com.sboxmarket.service

import com.sboxmarket.model.Item
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.PriceHistoryRepository
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

/**
 * Recomputes every catalogue item's denormalised `lowestPrice` from the
 * current set of ACTIVE non-hidden listings, once a minute.
 *
 * Why this exists:
 *  - `ListingService.updateItemFloorPrice` already runs on every listing
 *    mutation (buy, cancel, save, createListing) so the floor stays
 *    correct in the happy path.
 *  - But there are off-path drift sources: a transactional save that
 *    rolls back AFTER updateItemFloorPrice ran, an admin force-cancel
 *    that bypasses the normal mutation entry points, an auction settle
 *    racing with a manual cancel, the SteamMarketPriceService's
 *    isListed=false branch overwriting `lowestPrice` while a stale
 *    Listing row still claimed the floor, etc.
 *  - Buyers complain "prices on the grid don't match what's actually
 *    listed". A 60s reconciliation sweep guarantees the worst-case
 *    drift window is one minute, regardless of the bug class.
 *
 * Cost: one MIN(price) aggregate per catalogue row per minute. The
 * existing `idx_listing_item_status` covers it; an 80-item catalogue
 * is ~80 cheap index seeks per minute. Negligible against the rest of
 * the workload.
 *
 * Gated by `app.price-refresh.enabled` (default true) so ops can flip
 * it off via env var without a redeploy if it ever misbehaves.
 *
 * Also hosts a daily TTL prune of `price_history` (see
 * {@link #pruneOldPriceHistory}). Co-located here rather than in its
 * own service because both sweeps share the same kill-switch + scheduler
 * pool and the concern is the same: keep the price-display pipeline's
 * supporting data lean.
 */
@Service
@Slf4j
class ListingFloorRefreshService {

    /** Run every 60s, starting 30s after boot so Flyway + Hibernate
     *  finish before the first sweep fires. fixedDelay (not fixedRate)
     *  so a slow run can never stack with the next tick. */
    static final long REFRESH_INTERVAL_MS = 60L * 1000L
    static final long INITIAL_DELAY_MS    = 30L * 1000L

    @Autowired ItemRepository itemRepository
    @Autowired ListingRepository listingRepository
    @Autowired PriceHistoryRepository priceHistoryRepository

    @Value('${app.price-refresh.enabled:true}')
    boolean enabled

    /** Retention window for `price_history` rows. The sparkline chart's
     *  widest range hydrates 400 days; anything older is dead weight on
     *  disk + index. Matches ItemService.getPriceHistory's 400-day cutoff. */
    static final long PRICE_HISTORY_RETENTION_MS = 400L * 24L * 60L * 60L * 1000L

    /** Daily cadence + 5min initial delay so the retention sweep doesn't
     *  collide with the floor sweep at boot. */
    static final long RETENTION_INTERVAL_MS = 24L * 60L * 60L * 1000L
    static final long RETENTION_INITIAL_DELAY_MS = 5L * 60L * 1000L

    /** Last-run telemetry — same shape as SteamMarketPriceService so
     *  the admin Health tab + the frontend "prices updated Xs ago"
     *  chip can read both with one wire format. Volatile because
     *  the read path (HTTP worker thread) and write path (sched thread)
     *  are different threads. */
    private volatile long lastRunStartedAt  = 0L
    private volatile long lastRunFinishedAt = 0L
    private volatile int  lastRunChanged    = 0
    private volatile int  lastRunChecked    = 0

    @Scheduled(fixedDelay = REFRESH_INTERVAL_MS, initialDelay = INITIAL_DELAY_MS)
    void refreshAllFloors() {
        if (!enabled) return

        long started = System.currentTimeMillis()
        lastRunStartedAt = started

        int changed = 0, checked = 0

        try {
            // The outer findAll() is intentionally inside the try: a
            // transient DB hiccup here used to throw past the
            // `lastRunFinishedAt = …` line below, leaving the freshness
            // telemetry stuck in "started but never finished" state — the
            // admin Health tab and frontend chip would then render an
            // ever-growing "sweep running for X minutes" until the JVM
            // restarted, even though the next scheduled tick recovered
            // fine. Catching at this level keeps telemetry honest:
            // checked/changed reflect what we got done before the throw,
            // finishedAt always advances, and the NEXT @Scheduled tick
            // gets a clean slate.
            def items = itemRepository.findAll()

            for (Item item : items) {
                checked++
                try {
                    BigDecimal floor = listingRepository.minPriceForItem(item.id)
                    BigDecimal newPrice = floor ?: BigDecimal.ZERO
                    boolean newIsListed = (floor != null && floor > BigDecimal.ZERO)

                    BigDecimal oldPrice = item.lowestPrice ?: BigDecimal.ZERO
                    boolean    oldIsListed = item.isListed ?: false

                    // Only write when something actually changed — saves a
                    // row version bump + audit entry on the steady-state
                    // case where the floor is already correct (which is
                    // the common case once the system is settled).
                    if (newPrice.compareTo(oldPrice) != 0 || newIsListed != oldIsListed) {
                        item.lowestPrice = newPrice
                        item.isListed    = newIsListed
                        itemRepository.save(item)
                        changed++
                    }
                } catch (Exception e) {
                    log.debug("Floor refresh failed for item ${item?.id}: ${e.message}")
                }
            }
        } catch (Exception e) {
            log.warn("Floor refresh aborted at top level — checked=${checked} before throw: ${e.message}")
        } finally {
            lastRunFinishedAt = System.currentTimeMillis()
            lastRunChecked   = checked
            lastRunChanged   = changed
            long elapsed = lastRunFinishedAt - started
            log.info("Listing-floor refresh — checked=${checked} changed=${changed} in ${elapsed}ms")
        }
    }

    /**
     * Daily retention sweep for `price_history`. Until this existed there
     * was no TTL on PriceHistory rows: the write path appended forever
     * while the read path (`ItemService.getPriceHistory`, the sparkline
     * chart) only ever asked for the last 400 days. Rows older than the
     * window accumulated linearly with deployment age × sync cadence ×
     * catalogue size, bloating the table and its index for zero UI
     * benefit. A daily prune holds the table to a known steady-state
     * size.
     *
     * Cadence is 24h (not 60s like the floor sweep) because a) the
     * gain is recovered the next day if a sweep fails, b) a DELETE
     * across a wide cutoff is heavier than a MIN aggregate.
     *
     * Gated by the same `app.price-refresh.enabled` switch as the floor
     * sweep so ops have a single kill-switch covering all background
     * reconciliation on this service.
     */
    @Scheduled(fixedDelay = RETENTION_INTERVAL_MS, initialDelay = RETENTION_INITIAL_DELAY_MS)
    void pruneOldPriceHistory() {
        if (!enabled) return
        long cutoff = System.currentTimeMillis() - PRICE_HISTORY_RETENTION_MS
        try {
            int deleted = priceHistoryRepository.deleteOlderThan(cutoff)
            if (deleted > 0) {
                log.info("Price-history retention — pruned ${deleted} rows older than 400d")
            }
        } catch (Exception e) {
            // Best-effort: a failed prune is recoverable on the next tick;
            // it must never propagate and tear down the scheduler thread
            // (which would also kill the floor sweep on the shared pool).
            log.warn("Price-history retention prune failed: ${e.message}")
        }
    }

    /** Snapshot of the most recent reconciliation pass. Same shape as
     *  SteamMarketPriceService.lastRunSummary so the admin Health tab
     *  and the frontend freshness chip can render both with one
     *  formatter. `intervalMs` lets the UI render "next sweep in N s". */
    Map getLastRunSummary() {
        long nextAt = lastRunFinishedAt > 0 ? lastRunFinishedAt + REFRESH_INTERVAL_MS : 0L
        [
            startedAt:  lastRunStartedAt,
            finishedAt: lastRunFinishedAt,
            durationMs: (lastRunFinishedAt > 0 && lastRunStartedAt > 0)
                            ? (lastRunFinishedAt - lastRunStartedAt) : 0L,
            checked:    lastRunChecked,
            changed:    lastRunChanged,
            nextRunAt:  nextAt,
            intervalMs: REFRESH_INTERVAL_MS,
            enabled:    enabled
        ]
    }
}
