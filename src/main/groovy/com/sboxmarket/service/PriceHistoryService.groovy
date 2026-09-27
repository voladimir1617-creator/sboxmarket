package com.sboxmarket.service

import com.sboxmarket.model.Item
import com.sboxmarket.model.PriceHistory
import com.sboxmarket.repository.PriceHistoryRepository
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate

import java.text.SimpleDateFormat
import java.util.concurrent.ConcurrentHashMap

/**
 * Append-or-coalesce writer for the item price-history table that feeds the
 * sparkline on the item detail modal. Without this service the table stayed
 * empty and the chart rendered as a blank strip for every item.
 *
 * Dedupe rule: **one row per item per calendar day** (keyed off a
 * "MMM dd, yyyy" label so the chart's x-axis labels are human-readable AND
 * unambiguous). If a row already exists for today the price is overwritten
 * in place — so the chart tracks the closing price of the day and the table
 * doesn't explode at the pull cadence of the two writers (SCMM + Steam
 * market sync).
 *
 * The label MUST carry the year: `getPriceHistory` hydrates a 400-day
 * window, so a series routinely spans two calendar years. A year-less
 * "MMM dd" label collided same-day rows across years — both the chart
 * tooltip ("Apr 01" with no year) AND, worse, the coalesce key: an item
 * idle for ~365 days whose most-recent row was last year's "Apr 01" would
 * have today's "Apr 01" price overwrite that year-old data point in place
 * instead of appending a fresh row.
 */
@Service
@Slf4j
class PriceHistoryService {

    /** Year-qualified so the dayLabel is a unique key across the 400-day
     *  hydration window — not just "MMM dd", which repeats every year. */
    private static final String DAY_LABEL_PATTERN = 'MMM dd, yyyy'

    /** In-process idempotency cache. Keyed off the full call shape
     *  `(itemId|dayLabel|price|bump)` so the same logical event recorded
     *  twice in quick succession (a settle-flush that retries, a Steam
     *  sync that double-fires inside its own poll window, etc.) coalesces
     *  to one write instead of double-counting volume. Bounded at 5k
     *  entries with TTL eviction so a long-running pod can't grow this
     *  cache unbounded. Per-pod scope: across a cluster two pods could
     *  each let one duplicate through, but the same-day coalesce path in
     *  the writer guarantees the price still ends up canonical and the
     *  volume drift is bounded to "+1 per pod per retry" — never a
     *  fundamental leak. */
    private static final long IDEMPOTENCY_WINDOW_MS = 5_000L
    private static final int  IDEMPOTENCY_MAX_KEYS  = 5_000
    private final ConcurrentHashMap<String, Long> recentWrites = new ConcurrentHashMap<>()

    @Autowired PriceHistoryRepository priceHistoryRepository

    /** Optional so unit tests that build the service with `new
     *  PriceHistoryService(...)` (no Spring context) still work — in that
     *  case there is never an active transaction and the deferred work
     *  runs immediately anyway. */
    @Autowired(required = false) PlatformTransactionManager transactionManager

    /**
     * Run {@code work} after the caller's transaction commits — or
     * immediately when there is no active transaction (e.g. a unit test
     * with no Spring proxy, or a non-transactional caller).
     *
     * The price-history write is a best-effort side-effect: it feeds a
     * cosmetic sparkline and callers (e.g. PurchaseService) wrap it
     * expecting "a history hiccup must NEVER fail the parent purchase".
     * With a plain @Transactional the writes joined the caller's
     * transaction, so a failing repository.save() marked the SHARED
     * transaction rollback-only — the swallowing try/catch let the caller
     * "succeed", then its commit blew up with UnexpectedRollbackException
     * and the real operation was rolled back.
     *
     * Deferring to afterCommit means the deferred write runs AFTER the
     * parent has already durably committed: it can no longer poison the
     * parent, and because the parent's row locks are released post-commit
     * it doesn't extend the lock-hold window either.
     *
     * The deferred write runs in a FRESH REQUIRES_NEW transaction. This is
     * load-bearing: inside an afterCommit callback the original
     * transaction is already committed with "no commit following" — a
     * plain REQUIRED save() would join that spent transaction and never
     * actually commit its INSERT. A new transaction gives the deferred
     * write its own commit. This is safe (unlike REQUIRES_NEW on the
     * service method itself, the rejected prior fix): post-commit the
     * caller holds no row locks, so the new transaction extends no lock
     * window.
     */
    private void deferOrRun(Closure work) {
        if (transactionManager != null && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override void afterCommit() {
                    try {
                        def tt = new TransactionTemplate(transactionManager)
                        tt.propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
                        tt.executeWithoutResult { work() }
                    } catch (Exception e) {
                        log.warn("Deferred best-effort write failed: ${e.message}")
                    }
                }
            })
        } else {
            work()
        }
    }

    void record(Item item, BigDecimal price, Integer volumeDelta = 0) {
        if (item?.id == null || price == null || price <= BigDecimal.ZERO) return
        // A fresh SimpleDateFormat per call — the class is not thread-safe
        // and this writer is hit concurrently by the SCMM + Steam syncs.
        // Force UTC so the day-boundary is identical across deployments and
        // any future TZ change on the container (e.g. switching from local
        // host time to a Dockerfile-set UTC, or vice versa). Without this
        // the dayLabel coalesce key shifts with the JVM's default timezone —
        // a sale at 23:30 UTC labelled under one TZ would not match a sale
        // at 00:30 UTC labelled under another, silently splitting a single
        // logical day across two chart rows and double-counting volume.
        def fmt = new SimpleDateFormat(DAY_LABEL_PATTERN)
        fmt.timeZone = TimeZone.getTimeZone('UTC')
        def today = fmt.format(new Date())
        // Volume is a non-negative trade count; clamp the delta so a
        // negative value can never decrement (update) or seed a negative
        // row (insert). Treat null as zero.
        int bump = Math.max(0, volumeDelta ?: 0)
        // Idempotency short-circuit. PurchaseService wraps record() in a
        // try/catch and a transaction-retry policy can replay a settle a
        // few hundred ms apart — without dedupe the second call's `bump`
        // would compound onto the first one's same-day row. Same trap for
        // the Steam-sync writers that schedule overlapping polls under
        // load. The key encodes the entire write shape (item + day +
        // price + bump) so two GENUINE distinct sales at the same price
        // and bump on the same day inside the window will collapse — an
        // acceptable trade since the chart's volume axis is cosmetic and
        // the price overwrite path stays correct either way.
        def idemKey = item.id + '|' + today + '|' + price.toPlainString() + '|' + bump
        long now = System.currentTimeMillis()
        // Atomic claim-or-skip via `compute` — closes the check-then-act
        // race that defeated the idempotency window under concurrent load.
        // Pre-fix this was `get` then `put`: N parallel writers for the
        // same (item, day, price, bump) all read `prev == null`, all
        // wrote, all proceeded past the dedupe — and bumped the same-day
        // volume row N times instead of once. PurchaseService's retry
        // policy + Steam-sync overlapping polls both hit this code path
        // concurrently, so the race was the common case under load.
        // Mirrors the ItemController.shouldBumpView .compute() fix
        // (batch 319). compute runs the remap fn under the CHM bin lock,
        // so exactly one racer observes "no fresh stamp" and wins; every
        // concurrent peer sees the just-written stamp and short-circuits.
        boolean[] claimedRef = new boolean[1]
        recentWrites.compute(idemKey) { _, prev ->
            if (prev != null && (now - prev) < IDEMPOTENCY_WINDOW_MS) {
                claimedRef[0] = false
                return prev
            }
            claimedRef[0] = true
            return now
        }
        if (!claimedRef[0]) return
        if (recentWrites.size() > IDEMPOTENCY_MAX_KEYS) {
            // Evict every entry older than the window — bounded scan that
            // a) keeps memory in check, b) avoids the LRU-min scan tax
            // that ItemController's viewBumpCache takes per insert.
            //
            // Atomic conditional remove (mirrors the ItemController
            // viewBumpCache fix in batch 1219). Pre-fix this was
            // `recentWrites.entrySet().removeAll { it.value < cutoff }`,
            // which delegates to the iterator's UNCONDITIONAL single-arg
            // `map.remove(key)`. Under concurrent load, a different
            // writer thread could refresh `key`'s stamp to `now` via
            // `compute()` BETWEEN the predicate's stale-snapshot read
            // and the iterator's remove() — wiping the just-refreshed
            // claim. The very next record() for that same
            // (item|day|price|bump) then saw `prev == null` in compute,
            // claimed again, and fired a SECOND deferred write — and
            // because the writer accumulates volume on the same-day row
            // (`latest.volume = (latest.volume ?: 0) + bump`), the
            // duplicate event's bump was added twice. Two-arg remove(K,V)
            // only deletes when the snapshot value still matches, so a
            // refresh that beats us causes a clean skip. Snapshot the
            // entry set first (CHM weakly-consistent iterator is fine
            // for the snapshot; the deletion itself is the atomic part).
            long cutoff = now - IDEMPOTENCY_WINDOW_MS
            for (Map.Entry<String, Long> e : recentWrites.entrySet()) {
                Long v = e.value
                if (v != null && v < cutoff) {
                    recentWrites.remove(e.key, v)
                }
            }
        }
        // Defer the ENTIRE find + update-or-insert. The find must be inside
        // the closure too: if findLatestByItem() ran in the caller's
        // transaction, `latest` would be a managed entity and Hibernate
        // dirty-checking would flush the `latest.price = ...` mutation at
        // the caller's commit regardless of where save() is called — so the
        // mutation could still poison (or be lost with) the parent
        // transaction. Running the whole body post-commit keeps it fully
        // isolated: a fresh persistence context, the parent already
        // durably committed, its row locks released.
        deferOrRun {
            def latest = priceHistoryRepository.findLatestByItem(item.id).orElse(null)
            if (latest != null && latest.dayLabel == today) {
                latest.price = price
                if (bump > 0) {
                    latest.volume = (latest.volume ?: 0) + bump
                }
                priceHistoryRepository.save(latest)
            } else {
                priceHistoryRepository.save(new PriceHistory(
                    item:     item,
                    price:    price,
                    volume:   bump,
                    dayLabel: today
                ))
            }
        }
    }
}
