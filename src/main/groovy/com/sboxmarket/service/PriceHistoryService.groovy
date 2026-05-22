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
        def today = new SimpleDateFormat(DAY_LABEL_PATTERN).format(new Date())
        // Volume is a non-negative trade count; clamp the delta so a
        // negative value can never decrement (update) or seed a negative
        // row (insert). Treat null as zero.
        int bump = Math.max(0, volumeDelta ?: 0)
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
