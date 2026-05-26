package com.sboxmarket

import com.sboxmarket.controller.ItemController
import com.sboxmarket.service.ListingFloorRefreshService
import com.sboxmarket.service.SteamMarketPriceService
import spock.lang.Specification
import spock.lang.Subject

/**
 * Regression for the observability bug where /api/items/price-refresh-status
 * silently lied during a wedged floor-sweep scheduler.
 *
 * The endpoint surfaces two refresh sources:
 *   - ListingFloorRefreshService (every 60s — covers cancels/sales/listings,
 *     the DOMINANT signal — wedge here means lowestPrice drifts on every
 *     mutation),
 *   - SteamMarketPriceService    (every 30 min — Steam Community Market floor
 *     for unlisted items).
 *
 * Pre-fix, the endpoint reported `lastUpdatedAt = max(floor.finishedAt,
 * steam.finishedAt)`. Because the Steam sweep keeps ticking every 30 min
 * even when the floor scheduler thread is dead, the chip went green on
 * Steam's tick — the "Prices updated 5s ago" badge lied while the grid's
 * lowestPrice was minutes-out-of-date for every cancel / sale / new listing.
 * The frontend's 5-min stale threshold never fired because the Steam tick
 * kept max() young.
 *
 * Post-fix: the endpoint now publishes a server-computed `stale` boolean
 * that ignores Steam's tick and only considers the floor sweep's freshness
 * against ITS OWN scheduled interval × 3. The chip can light amber the
 * moment the dominant source goes overdue regardless of whether the Steam
 * sweep just ran.
 */
class PriceRefreshStatusFreshnessSpec extends Specification {

    @Subject
    ItemController controller = new ItemController()

    ListingFloorRefreshService floorSvc = Mock()
    SteamMarketPriceService    steamSvc = Mock()

    def setup() {
        controller.listingFloorRefreshService = floorSvc
        controller.steamMarketPriceService    = steamSvc
    }

    def "BUG REGRESSION — wedged floor sweep with a fresh Steam tick is reported as STALE, not healthy"() {
        given: 'the floor sweep last finished 10 minutes ago (scheduler is wedged) but Steam just ticked 5 seconds ago'
        long now = System.currentTimeMillis()
        long floorAt = now - (10L * 60_000L)
        long steamAt = now - 5_000L
        floorSvc.getLastRunSummary() >> [
            finishedAt: floorAt,
            intervalMs: ListingFloorRefreshService.REFRESH_INTERVAL_MS,
            enabled:    true
        ]
        steamSvc.getLastRunSummary() >> [
            finishedAt: steamAt,
            intervalMs: SteamMarketPriceService.SYNC_INTERVAL_MS
        ]

        when:
        def resp = controller.priceRefreshStatus()
        def body = resp.body as Map

        then: 'server-computed stale flag fires because the DOMINANT floor source is overdue past 3× its 60s cadence'
        body.stale == true

        and: 'lastUpdatedAt remains max() so the "Just now" timestamp render keeps working — `stale` is the gate, not the timestamp'
        body.lastUpdatedAt == steamAt
    }

    def "healthy state — both sources fresh — reports stale=false"() {
        given:
        long now = System.currentTimeMillis()
        floorSvc.getLastRunSummary() >> [
            finishedAt: now - 10_000L,
            intervalMs: ListingFloorRefreshService.REFRESH_INTERVAL_MS,
            enabled:    true
        ]
        steamSvc.getLastRunSummary() >> [
            finishedAt: now - 60_000L,
            intervalMs: SteamMarketPriceService.SYNC_INTERVAL_MS
        ]

        when:
        def body = controller.priceRefreshStatus().body as Map

        then:
        body.stale == false
    }

    def "floor sweep never ran (finishedAt=0) — reported as STALE even if Steam has ticked"() {
        given: 'floor scheduler never fired since boot; Steam already completed a pass'
        long now = System.currentTimeMillis()
        floorSvc.getLastRunSummary() >> [
            finishedAt: 0L,
            intervalMs: ListingFloorRefreshService.REFRESH_INTERVAL_MS,
            enabled:    true
        ]
        steamSvc.getLastRunSummary() >> [
            finishedAt: now - 1_000L,
            intervalMs: SteamMarketPriceService.SYNC_INTERVAL_MS
        ]

        when:
        def body = controller.priceRefreshStatus().body as Map

        then: 'a never-fired dominant sweep is just as much an outage as a wedged one'
        body.stale == true
    }

    def "floor sweep explicitly disabled — NOT reported as stale (it is OFF, not wedged)"() {
        given: 'ops flipped app.price-refresh.enabled=false; nothing to be stale about'
        long now = System.currentTimeMillis()
        floorSvc.getLastRunSummary() >> [
            finishedAt: 0L,
            intervalMs: ListingFloorRefreshService.REFRESH_INTERVAL_MS,
            enabled:    false
        ]
        steamSvc.getLastRunSummary() >> [
            finishedAt: now - 1_000L,
            intervalMs: SteamMarketPriceService.SYNC_INTERVAL_MS
        ]

        when:
        def body = controller.priceRefreshStatus().body as Map

        then: 'disabled is an intentional operator action, not a wedge — chip stays green'
        body.stale == false
    }
}
