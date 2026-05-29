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
    private volatile int  lastRunRateLimited = 0
    private volatile int  lastRunTotal      = 0
    /** True when the last sync hit the 5-consecutive-429 circuit breaker
     *  and aborted before processing every item. Surfaces in the admin
     *  Health tab so ops can distinguish "all 80 synced cleanly" from
     *  "Steam IP-banned us mid-sync, only 7 of 80 cleared". */
    private volatile boolean lastRunAborted = false

    /** Single-flight guard. The @Scheduled tick (every 30 min) runs for
     *  ~11 min in steady state; AdminController.syncPrices spawns
     *  syncPricesFromSteam() in a bare `new Thread(...)` on demand. If
     *  an admin clicks "Sync prices" while the scheduled tick is mid-run
     *  (~37% of every 30-min window), or rapid-clicks the button, two
     *  threads execute syncPricesFromSteam() concurrently and:
     *    • Burn through Steam's 1 req/s priceoverview ceiling — both
     *      threads independently throttle at 8s/item, but together they
     *      fire 2 req per 8s on the same IP. Steam's per-IP cooldown
     *      kicks in and BOTH threads start 429-burning. The circuit
     *      breaker (`consecutive429s`) is a per-invocation LOCAL
     *      variable, so each thread counts only ITS OWN 429s — the
     *      5-strike abort fires later than designed and we burn more of
     *      Steam's cooldown budget than the breaker meant to allow.
     *      Result: IP ban, the price feed this service exists to keep
     *      fresh stops updating.
     *    • Race on `itemRepository.save(item)` for the same Item
     *      instance loaded by both threads' `itemRepository.findAll()`
     *      — whichever commits second wins, the other thread's price
     *      update is silently lost.
     *    • Stomp the `lastRun*` volatile telemetry; the admin Health
     *      tab observes garbled numbers (e.g. updated=0 while
     *      finishedAt is fresh, because thread B's `lastRunUpdated = 0`
     *      lands after thread A's `lastRunFinishedAt = …`).
     *
     *  compareAndSet(false, true) ensures only one body runs at a time.
     *  Concurrent callers log + return immediately — no queue, because
     *  the admin's intent ("kick off a sync NOW") is already satisfied
     *  by the in-flight one and the next scheduled tick is at most
     *  30 min away. `finally` always clears the flag so a throw inside
     *  the body can't permanently lock out future ticks. */
    private final java.util.concurrent.atomic.AtomicBoolean syncRunning =
        new java.util.concurrent.atomic.AtomicBoolean(false)

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
        // Single-flight guard — see the syncRunning docstring. The
        // fixedDelay @Scheduled already serialises THE SCHEDULER's own
        // ticks per-method; the CAS only ever loses to a manual /
        // admin-button-triggered invocation racing a mid-run tick.
        // Concurrent callers return immediately so we never have two
        // threads hammering Steam's 1 req/s ceiling.
        if (!syncRunning.compareAndSet(false, true)) {
            log.info("Steam Market price sync skipped — another sync already in flight")
            return
        }
        try {
            syncPricesFromSteamBody()
        } finally {
            // Always release — a throw inside the body must not
            // permanently lock out future ticks.
            syncRunning.set(false)
        }
    }

    /** Inner body of {@link #syncPricesFromSteam} — kept package-private
     *  so a spec can call it directly to exercise the loop without
     *  going through the single-flight guard. Production callers MUST
     *  go through the outer method so the guard fires. */
    void syncPricesFromSteamBody() {
        def items = itemRepository.findAll()
        if (items.isEmpty()) return

        lastRunStartedAt = System.currentTimeMillis()
        lastRunTotal = items.size()
        int updated = 0, failed = 0, skipped = 0, rateLimited = 0, consecutive429s = 0
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
                // A 429 is the ONLY condition that arms the circuit
                // breaker. "No data" (item not on Steam market / transient
                // non-200) is an expected, non-rate-limited outcome — it
                // must NOT increment consecutive429s, otherwise 5 unlisted
                // items in a row would falsely abort the whole sweep.
                if (prices?.rateLimited) {
                    consecutive429s++
                    rateLimited++
                    // fetchSteamPrice already slept the long 429 backoff;
                    // skip the loop's 8s throttle. But if that backoff was
                    // cut short by an interrupt (graceful shutdown), honour
                    // it now rather than firing another HTTP round-trip.
                    if (Thread.currentThread().isInterrupted()) break
                    continue
                }
                consecutive429s = 0  // a non-429 fetch (any data) resets the counter

                def lowestPrice = prices?.lowest
                def medianPrice = prices?.median

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
        lastRunRateLimited = rateLimited
        lastRunAborted = aborted
        log.info("Steam Market price sync done — updated=$updated skipped=$skipped " +
                 "rateLimited=$rateLimited failed=$failed aborted=$aborted")
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
            rateLimited:  lastRunRateLimited,
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
            // Explicit scale + rounding — a bare BigDecimal `/` throws
            // ArithmeticException("Non-terminating decimal expansion")
            // whenever the quotient doesn't terminate (e.g. a $3.00 → $4.00
            // move = 1/3). That was silently failing every such item's
            // price-sync for good.
            def change = (bestPrice - oldPrice).divide(oldPrice, 6, java.math.RoundingMode.HALF_UP)
            item.trendPercent = Math.max(-99,
                Math.min(99, Math.round(change * 100) as int))
        }
    }

    /**
     * Fetch the current lowest and median price for an item from
     * Steam's public priceoverview endpoint.
     *
     * Returns a status-bearing Map so the caller can tell *why* a fetch
     * produced no price — this distinction is load-bearing for the
     * circuit breaker:
     *   • [rateLimited: true]                  — Steam 429'd us; the
     *     caller MUST count this toward the consecutive-429 breaker.
     *   • [lowest: null, median: null]         — endpoint reached but the
     *     item simply isn't on the Steam Community Market, or Steam
     *     returned a transient non-200 / `success:false`. This is NOT
     *     rate-limiting and must NOT trip the breaker — for an s&box
     *     catalogue most items legitimately have no Steam-market listing,
     *     so treating "no data" as a 429 would abort every sync the
     *     moment 5 unlisted items landed back-to-back.
     *   • [lowest: <bd>, median: <bd>]         — a real price (one or
     *     both fields may still be null if only one side parsed).
     *
     * `consecutive429s` is the count of immediately-prior 429s in this
     * sync — the backoff doubles for each (30s, 60s, 120s, 240s, 480s)
     * so Steam's per-IP cooldown actually clears before the next probe.
     * Previous static 30s wait was too short — Steam often takes 1-2
     * minutes to reset a flagged IP, and we'd just keep getting 429
     * after 429 until the 5-strikes circuit breaker fired.
     */
    Map fetchSteamPrice(String marketHashName, int consecutive429s = 0) {
        // Spaces — and Steam item names are FULL of them ("AK-47 | Redline
        // (Field-Tested)") — must be %20, not '+'. URLEncoder.encode uses
        // application/x-www-form-urlencoded rules where space is '+',
        // which most Steam endpoints tolerate but the canonical query-string
        // form is %20. Matches the extension's encodeURIComponent path
        // (extension/content.js line 103) so server-side and browser-side
        // requests for the same item always produce byte-identical URLs —
        // any future cache key keyed on URL therefore agrees.
        def encoded = URLEncoder.encode(marketHashName, 'UTF-8').replace('+', '%20')
        def url = "https://steamcommunity.com/market/priceoverview/?country=US&currency=1&appid=${SBOX_APP_ID}&market_hash_name=${encoded}"

        HttpURLConnection conn = null
        try {
            conn = (HttpURLConnection) new URL(url).openConnection()
            conn.setRequestProperty('User-Agent', 'SkinBox/1.0 (+https://skinbox.market)')
            conn.setRequestProperty('Accept', 'application/json')
            conn.connectTimeout = 8000
            conn.readTimeout = 10000
            conn.instanceFollowRedirects = false

            int status = conn.responseCode
            if (status == 429) {
                // Exponential backoff: 30s base, doubles per consecutive 429.
                // Capped at 8 minutes — past that we should just abort this
                // sync via the outer circuit breaker.
                long backoffMs = Math.min(480_000L, 30_000L * (1L << Math.min(4, consecutive429s)))
                // Honour a Retry-After hint if Steam sent one — backing off
                // for LESS than Steam asked just earns another 429 on the
                // next probe (and extends the IP cooldown). Header may be
                // a delta-seconds integer or an HTTP-date; we only act on
                // the simple integer form (Steam never sends HTTP-date here).
                // Bound by [our-exponential, 8min] so a hostile or buggy
                // upstream "Retry-After: 86400" can't park the sync thread
                // for a day.
                def retryAfter = conn.getHeaderField('Retry-After')
                if (retryAfter?.isInteger()) {
                    long hinted = retryAfter.toLong() * 1000L
                    if (hinted > backoffMs) backoffMs = Math.min(480_000L, hinted)
                }
                log.warn("Steam Market rate-limited (429) — backing off ${(backoffMs / 1000) as long}s (consecutive=${consecutive429s + 1})")
                try { Thread.sleep(backoffMs) }
                catch (InterruptedException ie) { Thread.currentThread().interrupt() }
                return [rateLimited: true]
            }
            // Any other non-200 (5xx, redirect, etc.) is a transient
            // endpoint hiccup, NOT a rate-limit — return "no data" so the
            // 429 circuit breaker isn't tripped by a brief Steam outage.
            if (status != 200) return [lowest: null, median: null]

            def body = conn.inputStream.getText('UTF-8')
            def json = new JsonSlurper().parseText(body)
            // success:false means the item isn't on the Steam market —
            // a normal, expected outcome, not a failure or a rate-limit.
            if (!json || json.success != true) return [lowest: null, median: null]

            return [
                lowest: parseSteamPrice(json.lowest_price),
                median: parseSteamPrice(json.median_price)
            ]
        } finally {
            // Drain + release the socket so keep-alive can reuse it and a
            // long-lived scheduler doesn't slowly leak connections.
            try { conn?.disconnect() } catch (Exception ignored) {}
        }
    }

    private static BigDecimal parseSteamPrice(String raw) {
        if (!raw) return null
        // Steam priceoverview returns the currency-formatted string,
        // so the parse has to cope with BOTH locales:
        //   • USD / GBP / JPY:  "$1.23" / "£1.23" / "¥1"          — `.` is decimal
        //   • EUR / RUB / BRL:  "1,23€" / "1,23 ₽" / "R$ 1,23"   — `,` is decimal
        //   • Thousands-separated (German EUR): "1.234,56€"      — `.` thousands, `,` decimal
        //   • Thousands-separated (US):         "$1,234.56"      — `,` thousands, `.` decimal
        //
        // Pre-fix the regex `[^\d.]` kept ONLY digits + dots, so the
        // claimed-supported "1,23€" silently produced "123" → 123.00
        // (a 100x overprice). The hardcoded currency=1 (USD) in the
        // fetch URL meant this never fired in production, but the
        // comment promised an invariant the code didn't hold — the
        // moment someone changes the currency param the price feed
        // lies by two orders of magnitude.
        //
        // Strategy: keep digits + both separators, then identify the
        // RIGHTMOST separator as the decimal point and treat any
        // earlier separators as thousands (strip them). The rightmost
        // marker rule works regardless of which character convention
        // the locale uses.
        //
        // EXCEPT for no-decimal currencies whose values still carry
        // thousand-separators (JPY "¥1,500" / KRW "₩1,500" / IDR
        // "Rp1.500" / VND "₫1.500" / CLP "$1.500"). The rightmost
        // separator there is THOUSANDS, not decimal — treating it as
        // decimal silently divides the price by 1000. Pre-fix, a
        // sub-$2 catalogue item on a JPY rollout would have round-
        // tripped as ¥1,500 → 1.5 → BigDecimal("1.5"), a 1000× under-
        // price; once persisted, every later sync would see a "trend"
        // collapse to -99% and clamp there. The hardcoded currency=1
        // (USD) in the fetch URL means this is latent today, but the
        // docstring above explicitly claims JPY support and the
        // existing spec covers "¥150" — so the moment a thousand-
        // separator JPY value lands the price feed lies by three
        // orders of magnitude.
        //
        // Rule: if the rightmost separator has EXACTLY 3 trailing
        // digits, it is a thousands separator. Every fractional
        // currency on Steam uses 2-digit minor units; the only way to
        // see exactly 3 trailing digits past the rightmost separator
        // is a thousands grouping. USD "$1,234" (no cents shown) also
        // collapses to 1234 under this rule, which is the correct
        // dollar amount — Steam ordinarily ships "$1,234.00" but the
        // pure-thousands form is parsed correctly either way.
        def cleaned = raw.replaceAll(/[^\d.,]/, '')
        if (cleaned.isEmpty()) return null
        // Find the last separator (',' or '.') — that's the decimal.
        // String overload (not the char one) — Groovy's `as char` boxes
        // to a Character object and String.lastIndexOf has no overload
        // for that (only `int` codepoint or `String`).
        int lastDot = cleaned.lastIndexOf('.')
        int lastComma = cleaned.lastIndexOf(',')
        int decimalIdx = Math.max(lastDot, lastComma)
        String normalised
        if (decimalIdx < 0) {
            // No separator at all — pure integer like "123".
            normalised = cleaned
        } else {
            int trailing = cleaned.length() - decimalIdx - 1
            if (trailing == 3) {
                // Rightmost separator is a THOUSANDS marker, not a
                // decimal — JPY/KRW/IDR/VND/CLP "1,500" / "1.500" /
                // multi-group "1,234,567". Strip every separator.
                normalised = cleaned.replaceAll(/[.,]/, '')
            } else {
                String intPart = cleaned.substring(0, decimalIdx).replaceAll(/[.,]/, '')
                String fracPart = cleaned.substring(decimalIdx + 1).replaceAll(/[.,]/, '')
                normalised = intPart + '.' + fracPart
            }
        }
        if (normalised.isEmpty() || normalised == '.') return null
        try {
            def bd = new BigDecimal(normalised)
            return bd > BigDecimal.ZERO ? bd : null
        } catch (NumberFormatException ignored) {
            return null
        }
    }
}
