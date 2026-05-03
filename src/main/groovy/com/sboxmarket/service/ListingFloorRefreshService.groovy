package com.sboxmarket.service

import com.sboxmarket.model.Item
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
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

    @Value('${app.price-refresh.enabled:true}')
    boolean enabled

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

        def items = itemRepository.findAll()
        int changed = 0, checked = 0

        for (Item item : items) {
            checked++
            try {
                BigDecimal floor = listingRepository.minPriceForItem(item.id)
                BigDecimal newPrice = floor ?: BigDecimal.ZERO
                boolean newIsListed = (floor != null && floor > BigDecimal.ZERO)

                BigDecimal oldPrice = item.lowestPrice ?: BigDecimal.ZERO
                boolean    oldIsListed = item.isListed ?: false

                // Only write when something actually changed — saves a row
                // version bump + audit entry on the steady-state case
                // where the floor is already correct (which is the common
                // case once the system is settled).
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

        lastRunFinishedAt = System.currentTimeMillis()
        lastRunChecked   = checked
        lastRunChanged   = changed

        long elapsed = lastRunFinishedAt - started
        log.info("Listing-floor refresh — checked=${checked} changed=${changed} in ${elapsed}ms")
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
