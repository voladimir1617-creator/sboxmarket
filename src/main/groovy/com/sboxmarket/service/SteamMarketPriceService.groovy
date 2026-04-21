package com.sboxmarket.service

import com.sboxmarket.repository.ItemRepository
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Fetches live prices directly from the Steam Community Market
 * priceoverview endpoint. Replaces SCMM-sourced prices with the
 * actual lowest_price and median_price from Steam itself.
 *
 * Endpoint: GET https://steamcommunity.com/market/priceoverview/
 *   ?country=US&currency=1&appid=590830&market_hash_name=ITEM_NAME
 *
 * Rate limit: Steam allows ~1 req/s on this endpoint. We throttle
 * to 1.5s between calls so a full 80-item catalogue takes ~2 minutes.
 * Runs every 15 minutes on its own scheduler thread.
 */
@Service
@Slf4j
class SteamMarketPriceService {

    static final String SBOX_APP_ID = '590830'
    // 30 minutes between sync cycles. Previous value (15 min) caused too-frequent
    // retries during Steam IP bans — each 429 retry resets Steam's cooldown timer,
    // extending the ban indefinitely. 30 min gives Steam enough breathing room
    // while still keeping prices reasonably fresh. 80 items × 8s = ~11 min per
    // full sync, well within the 30-min window.
    static final long SYNC_INTERVAL_MS = 30L * 60L * 1000L

    @Autowired ItemRepository itemRepository
    @Autowired(required = false) PriceHistoryService priceHistoryService

    /** Last-run telemetry (batch 396). Populated at the end of every sync
     *  pass so the admin Health tab can render "last sync 4 min ago,
     *  updated 79/80 · next scheduled in 26 min" without tailing the
     *  server log. Volatile so the read from the HTTP worker thread
     *  observes writes from the scheduler thread. All zeros until the
     *  first pass lands. Exposed via `getLastRunSummary()`. */
    private volatile long lastRunStartedAt = 0L
    private volatile long lastRunFinishedAt = 0L
    private volatile int  lastRunUpdated    = 0
    private volatile int  lastRunSkipped    = 0
    private volatile int  lastRunFailed     = 0
    private volatile int  lastRunTotal      = 0
    /** True when the last sync hit the 5-consecutive-429 circuit breaker
     *  and aborted before processing every item. Surfaces in the admin
     *  Health tab so ops can distinguish "all 80 synced cleanly" from
     *  "Steam IP-banned us mid-sync, only 7 of 80 cleared". */
    private volatile boolean lastRunAborted = false

    // 90-second initial delay (batch 376). Previously 10 minutes, chosen
    // to let Steam's cooldown expire if we were mid-429-burst. BUT a
    // typical deploy / CI restart / grind redeploy happens more often
    // than 10 min, so the sync NEVER fired on short-lived containers —
    // the effect was 0 rows in price_history despite a reachable Steam
    // endpoint. 90s is long enough for Spring Boot + HikariCP + Flyway
    // to finish, short enough to always fire before the next redeploy.
    // The existing 5-consecutive-429 circuit breaker already handles
    // the cooldown-window case the 10-min delay was trying to guard
    // against.
    // NOT @Transactional — the outer method runs for ~11 min (80 items
    // × 8s throttle). Wrapping it in one transaction means nothing
    // commits until the whole sync finishes, and any exception rolls
    // the entire batch back. Each individual save() inside the loop
    // runs in its own auto-commit transaction (Spring Data JPA default)
    // so partial progress is durable even if a later item fails.
    @Scheduled(fixedDelay = SYNC_INTERVAL_MS, initialDelay = 90L * 1000L)
    void syncPricesFromSteam() {
        def items = itemRepository.findAll()
        if (items.isEmpty()) return

        lastRunStartedAt = System.currentTimeMillis()
        lastRunTotal = items.size()
        int updated = 0, failed = 0, skipped = 0, consecutive429s = 0
        log.info("Steam Market price sync starting — ${items.size()} items")

        boolean aborted = false
        for (def item : items) {
            if (!item.name) { skipped++; continue }

            // Circuit breaker: if Steam has 429'd us 5 times in a row,
            // stop this sync cycle entirely and wait for the next one.
            // Continuing to loop just wastes 30s × N in sleep with no
            // progress and fills the log with warnings.
            if (consecutive429s >= 5) {
                log.warn("Steam Market: 5 consecutive 429s — aborting sync, will retry next cycle")
                aborted = true
                break
            }

            try {
                def prices = fetchSteamPrice(item.name, consecutive429s)
                if (prices == null) { consecutive429s++; skipped++; continue }
                consecutive429s = 0  // successful fetch resets the counter

                def lowestPrice = prices.lowest
                def medianPrice = prices.median

                // Use lowest_price as the primary, fall back to median
                def bestPrice = lowestPrice ?: medianPrice
                if (bestPrice != null && bestPrice > BigDecimal.ZERO) {
                    applyPriceUpdate(item, lowestPrice, bestPrice)

                    itemRepository.save(item)
                    priceHistoryService?.record(item, bestPrice)
                    updated++
                } else {
                    skipped++
                }
            } catch (Exception e) {
                log.debug("Steam price fetch failed for '${item.name}': ${e.message}")
                failed++
            }

            // Throttle: 8s between requests. Steam's priceoverview
            // enforces a hard per-IP limit. Previous values (1.5s, 3s,
            // 5s) all triggered 429s under real traffic. 8s = ~7.5
            // req/min, safely below the observed limit. 80 items =
            // ~11 min per full sync, well within the 15-min cycle.
            try { Thread.sleep(8000L) }
            catch (InterruptedException ie) {
                Thread.currentThread().interrupt()
                break
            }
        }

        lastRunFinishedAt = System.currentTimeMillis()
        lastRunUpdated = updated
        lastRunSkipped = skipped
        lastRunFailed  = failed
        lastRunAborted = aborted
        log.info("Steam Market price sync done — updated=$updated skipped=$skipped failed=$failed aborted=$aborted")
    }

    /** Snapshot of the most recent sync run. Consumed by the admin Health
     *  tab so ops can see freshness + next-scheduled without tailing logs.
     *  Returns null-fielded map when no sync has completed yet since
     *  boot — the UI hides the stats in that case. */
    Map getLastRunSummary() {
        def nextAt = lastRunFinishedAt > 0 ? lastRunFinishedAt + SYNC_INTERVAL_MS : 0L
        [
            startedAt:    lastRunStartedAt,
            finishedAt:   lastRunFinishedAt,
            durationMs:   lastRunFinishedAt > 0 && lastRunStartedAt > 0
                              ? (lastRunFinishedAt - lastRunStartedAt) : 0L,
            updated:      lastRunUpdated,
            skipped:      lastRunSkipped,
            failed:       lastRunFailed,
            total:        lastRunTotal,
            aborted:      lastRunAborted,
            nextRunAt:    nextAt,
            intervalMs:   SYNC_INTERVAL_MS
        ]
    }

    /**
     * Apply a fetched Steam-market price to the given Item without
     * persisting (caller owns the save) — extracted from the sync
     * loop so the guard rules are unit-testable. Rules:
     *
     *   1. `lowestPrice` is ONLY overwritten when the item is NOT
     *      currently listed on sboxmarket. When a user has a live
     *      listing, `ListingService.updateItemFloorPrice` is the
     *      authoritative source of truth — clobbering it with
     *      Steam's (usually lower) market floor drifts the grid
     *      card away from the real cheapest listing and surprises
     *      buyers at click-through.
     *   2. `steamPrice` is only populated when empty — a reference
     *      price sourced from SCMM originalPrice (retail store)
     *      wins over the market lowest when both exist.
     *   3. `trendPercent` only ticks when we actually wrote a new
     *      floor (unlisted item case). For listed items, trend is
     *      driven by platform listings, not Steam-market wiggle.
     *
     * Package-scope (no modifier) so specs in the same package can
     * call directly without reflection.
     */
    void applyPriceUpdate(com.sboxmarket.model.Item item, BigDecimal lowestPrice, BigDecimal bestPrice) {
        def oldPrice = item.lowestPrice
        if (!item.isListed) {
            item.lowestPrice = bestPrice
        }
        if (item.steamPrice == null || item.steamPrice <= BigDecimal.ZERO) {
            item.steamPrice = lowestPrice
        }
        if (!item.isListed && oldPrice != null && oldPrice > BigDecimal.ZERO) {
            def change = (bestPrice - oldPrice) / oldPrice
            item.trendPercent = Math.max(-99,
                Math.min(99, Math.round(change * 100) as int))
        }
    }

    /**
     * Fetch the current lowest and median price for an item from
     * Steam's public priceoverview endpoint. Returns null if the
     * item isn't listed on the Steam Community Market.
     *
     * `consecutive429s` is the count of immediately-prior 429s in this
     * sync — the backoff doubles for each (30s, 60s, 120s, 240s, 480s)
     * so Steam's per-IP cooldown actually clears before the next probe.
     * Previous static 30s wait was too short — Steam often takes 1-2
     * minutes to reset a flagged IP, and we'd just keep getting 429
     * after 429 until the 5-strikes circuit breaker fired.
     */
    Map fetchSteamPrice(String marketHashName, int consecutive429s = 0) {
        def encoded = URLEncoder.encode(marketHashName, 'UTF-8')
        def url = "https://steamcommunity.com/market/priceoverview/?country=US&currency=1&appid=${SBOX_APP_ID}&market_hash_name=${encoded}"

        def conn = (HttpURLConnection) new URL(url).openConnection()
        conn.setRequestProperty('User-Agent', 'SkinBox/1.0 (+https://skinbox.market)')
        conn.setRequestProperty('Accept', 'application/json')
        conn.connectTimeout = 8000
        conn.readTimeout = 10000

        int status = conn.responseCode
        if (status == 429) {
            // Exponential backoff: 30s base, doubles per consecutive 429.
            // Capped at 8 minutes — past that we should just abort this
            // sync via the outer circuit breaker.
            long backoffMs = Math.min(480_000L, 30_000L * (1L << Math.min(4, consecutive429s)))
            log.warn("Steam Market rate-limited (429) — backing off ${backoffMs / 1000}s (consecutive=${consecutive429s + 1})")
            try { Thread.sleep(backoffMs) }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null }
            return null
        }
        if (status != 200) return null

        def body = conn.inputStream.getText('UTF-8')
        def json = new JsonSlurper().parseText(body)
        if (!json || json.success != true) return null

        [
            lowest: parseSteamPrice(json.lowest_price),
            median: parseSteamPrice(json.median_price)
        ]
    }

    private static BigDecimal parseSteamPrice(String raw) {
        if (!raw) return null
        // "$1.23" → 1.23 ; "1,23€" → 1.23
        def cleaned = raw.replaceAll(/[^\d.]/, '')
        if (!cleaned) return null
        try {
            def bd = new BigDecimal(cleaned)
            return bd > BigDecimal.ZERO ? bd : null
        } catch (NumberFormatException ignored) {
            return null
        }
    }
}
