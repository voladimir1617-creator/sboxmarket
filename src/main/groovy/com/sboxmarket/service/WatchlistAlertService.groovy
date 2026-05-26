package com.sboxmarket.service

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.WatchlistAlert
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.WatchlistAlertRepository
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Server-side watchlist price alerts.
 *
 * Unlike the old localStorage alerts (which only fired when the user
 * opened the Watchlist page), these persist across devices and fire
 * even while the user is offline. The scheduled sweeper polls every
 * 5 minutes, finds ACTIVE alerts whose item's lowestPrice is at or
 * below the target, pushes a WATCHLIST_PRICE_DROP notification, and
 * flips the alert to FIRED. A re-set on the same item replaces the
 * FIRED row with a fresh ACTIVE one.
 */
@Service
@Slf4j
class WatchlistAlertService {

    @Autowired WatchlistAlertRepository repo
    @Autowired ItemRepository itemRepository
    @Autowired(required = false) NotificationService notificationService
    @Autowired(required = false) EmailService emailService
    @Autowired(required = false) com.sboxmarket.repository.SteamUserRepository steamUserRepository

    /** Hard cap per user. Protects the sweeper from one-user-creates-
     *  a-million-alerts abuse and keeps the watchlist UI sane. */
    private static final int PER_USER_LIMIT = 50

    /** Per-tick scope cap on the scheduled sweep. The repo query
     *  intentionally has no SQL LIMIT (the existing test suite stubs
     *  the no-arg method shape) so this is an in-memory clamp on the
     *  per-tick push/email/save fan-out. PER_USER_LIMIT caps the
     *  population at 50 × user-count active alerts; on a six-figure
     *  user base the matched-row count can still be tens of thousands.
     *  Triggered rows that don't fit in one pass stay ACTIVE in the DB
     *  and are picked up on the next 5-minute tick — the FIRED-flag
     *  filter in findTriggered() makes the work naturally idempotent
     *  so a crash mid-loop never re-pushes already-FIRED rows. */
    static final int SWEEP_BATCH_LIMIT = 1000

    /** Per-user email dedup window. If a user has N alerts trigger in
     *  the same sweep tick (or back-to-back across a sync sweep + the
     *  scheduled tick), without this gate they'd receive N price-drop
     *  emails simultaneously — a real-world abuse vector for a
     *  PER_USER_LIMIT-of-50 watcher who set 50 alerts on a falling
     *  market. We collapse the email side to at most one send per user
     *  per 5 minutes; the in-app notification still fires once per
     *  triggered item (cheap, contextual, what the bell-icon is for),
     *  but the inbox doesn't get napalmed. Cross-tick state lives in
     *  `lastEmailedAt` below — sized to the active-watcher headcount,
     *  bounded by an LRU cap. */
    static final long EMAIL_DEDUP_WINDOW_MS = 5L * 60_000L

    /** Per-user "last price-drop email sent at" ledger backing
     *  EMAIL_DEDUP_WINDOW_MS. LinkedHashMap-in-access-order with a
     *  hard cap of 8192 entries — long enough to span the dedup
     *  window for a six-figure active-watcher base (the eldest entry
     *  is at most 5 minutes old, so eviction means it had already
     *  fallen out of the window anyway) and bounded so a long-running
     *  prod node doesn't leak memory across years of uptime. Wrapped
     *  in synchronizedMap because the scheduled sweep and per-item
     *  sweepForItem can run concurrently (the latter is called inline
     *  from sell transactions on any web thread). */
    private final java.util.Map<Long, Long> lastEmailedAt =
        java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<Long, Long>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<Long, Long> e) {
                    return size() > 8192
                }
            } as java.util.LinkedHashMap<Long, Long>)

    /** Returns true the first time we see this user inside the dedup
     *  window; false on a duplicate, in which case the caller drops
     *  the email send (the in-app notification still fires regardless).
     *  Records the timestamp on the "true" return so back-to-back calls
     *  in the same tick collapse correctly. */
    private boolean shouldSendEmail(Long userId) {
        if (userId == null) return true
        long now = System.currentTimeMillis()
        synchronized (lastEmailedAt) {
            Long last = lastEmailedAt.get(userId)
            if (last != null && (now - last) < EMAIL_DEDUP_WINDOW_MS) {
                return false
            }
            lastEmailedAt.put(userId, now)
            return true
        }
    }

    @Transactional
    WatchlistAlert upsertAlert(Long userId, Long itemId, BigDecimal targetPrice) {
        if (targetPrice == null || targetPrice <= BigDecimal.ZERO) {
            throw new BadRequestException('INVALID_TARGET',
                'Target price must be greater than zero')
        }
        if (targetPrice > new BigDecimal('100000')) {
            throw new BadRequestException('TARGET_TOO_HIGH',
                'Target price exceeds the \$100,000 sanity cap')
        }
        // Round-then-validate (batch 1196). The `targetPrice <= 0` guard
        // above fires BEFORE scaling, so a value in (0, 0.005) like
        // `"0.00001"` slipped past the guard, was rounded by
        // `setScale(2, HALF_UP)` to `0.00`, and stored as a target of
        // zero. The sweep query (`i.lowestPrice <= a.targetPrice AND
        // i.lowestPrice > 0`) can never match a zero target, so the
        // alert sat dead in the user's quota slot forever — silently
        // never firing, while the user thought they had a sub-cent
        // watch active. Round first, then re-check the floor at the
        // storage scale: anything below \$0.01 fails fast with the same
        // INVALID_TARGET code so the SPA can surface the same toast.
        BigDecimal scaledTarget = targetPrice.setScale(2, BigDecimal.ROUND_HALF_UP)
        if (scaledTarget <= BigDecimal.ZERO) {
            throw new BadRequestException('INVALID_TARGET',
                'Target price must be at least \$0.01')
        }
        // Item must exist — otherwise the sweeper's join silently drops
        // the row forever.
        def item = itemRepository.findById(itemId)
            .orElseThrow { new NotFoundException('Item', itemId) }

        def existing = repo.findActiveFor(userId, itemId)
        if (existing.isPresent()) {
            def a = existing.get()
            a.targetPrice = scaledTarget
            a.createdAt   = System.currentTimeMillis()
            return repo.save(a)
        }
        // Quota check — only applies when creating a brand new alert, so
        // updating an existing one never hits the cap.
        long activeCount = repo.countByUserIdAndStatus(userId, 'ACTIVE')
        if (activeCount >= PER_USER_LIMIT) {
            throw new BadRequestException('ALERT_LIMIT',
                "Active alert limit reached (${PER_USER_LIMIT}). Delete one before adding more.")
        }
        def alert = new WatchlistAlert(
            userId:      userId,
            itemId:      itemId,
            targetPrice: scaledTarget,
            status:      'ACTIVE',
            createdAt:   System.currentTimeMillis()
        )
        repo.save(alert)
        log.info("User ${userId} set price alert ${item.name ?: itemId} @ \$${alert.targetPrice}")
        alert
    }

    /** Display cap on the Watchlist → Alerts tab. ACTIVE alerts are
     *  naturally bounded at 50 (PER_USER_LIMIT) but FIRED / CANCELLED
     *  rows accumulate unbounded. 300 is generous — months of alert
     *  history — while protecting a power-watcher's tab open from a
     *  thousand-row hydration. */
    static final int ALERT_LIST_CAP = 300

    List<WatchlistAlert> listForUser(Long userId) {
        if (userId == null) return []
        repo.findByUserIdPaged(userId,
            org.springframework.data.domain.PageRequest.of(0, ALERT_LIST_CAP))
    }

    /** Public demand-side social proof for an item: how many viewers
     *  currently have an ACTIVE alert. Aggregate only — no watcher
     *  identities are exposed. Null itemId → 0, so the UI never has to
     *  guard the call. */
    long countWatchersForItem(Long itemId) {
        if (itemId == null) return 0L
        repo.countActiveForItem(itemId)
    }

    /** Delete every FIRED alert the user has accumulated — lets users
     *  tidy the Watchlist summary without having to cancel each one. */
    @Transactional
    int clearFired(Long userId) {
        repo.deleteFiredForUser(userId)
    }

    @Transactional
    void cancelAlert(Long userId, Long alertId) {
        def a = repo.findById(alertId)
            .orElseThrow { new NotFoundException('WatchlistAlert', alertId) }
        if (a.userId != userId) {
            throw new BadRequestException('NOT_OWNER', 'Not your alert')
        }
        a.status = 'CANCELLED'
        repo.save(a)
    }

    /**
     * Scheduled sweeper. Every 5 minutes scans for ACTIVE alerts whose
     * item's lowestPrice crossed the target, pushes one notification
     * per triggered row, and flips the alert to FIRED. Single pass, no
     * pagination — the repo query uses two indexes (idx_watchlist_
     * alerts_active_item + the Item PK) so even a large population
     * stays O(matches).
     */
    // NOT @Transactional — same rollback-only leak as 443f910 / 526a5f4.
    // fireRow's repo.save(a) inside a per-row try/catch poisons the
    // shared outer tx the moment one save throws; the catch absorbs
    // the throw but on sweep() return the commit blows up with
    // UnexpectedRollbackException and every "FIRED" stamp the batch
    // applied to sibling alerts gets rolled back — the next 5-min tick
    // re-finds those alerts and re-fires duplicate WATCHLIST_PRICE_DROP
    // pushes. Each fireRow save is independent (no cross-alert
    // invariant), so per-save implicit tx is the correct posture.
    @Scheduled(fixedDelayString = '${watchlist-alert.sweep-ms:300000}',
               initialDelayString = '${watchlist-alert.initial-delay-ms:60000}')
    void sweep() {
        def all = repo.findTriggered()
        if (all == null || all.isEmpty()) return
        // Clamp per-tick work scope (see SWEEP_BATCH_LIMIT). Remaining
        // ACTIVE rows roll over to the next 5-minute tick. fixedDelay
        // (Spring serialises ticks per scheduled method) guarantees the
        // sweeper can never overlap itself, so the clamp is a pure
        // throughput knob, not a correctness one.
        def triggered = all.size() > SWEEP_BATCH_LIMIT ? all.take(SWEEP_BATCH_LIMIT) : all
        int fired = triggered.count { fireRow(it) ? 1 : 0 } as int
        log.info("Watchlist alert sweep: fired ${fired} of ${triggered.size()} matches (eligible=${all.size()})")
    }

    /** Hard cap on rows hydrated by a single {@link #sweepForItem}
     *  call. Mirrors the per-tick `SWEEP_BATCH_LIMIT` on the periodic
     *  sweep, but enforced AT THE SQL LIMIT (not after-the-fact in
     *  memory) so a 10k-watcher item never pulls 10k Object[] rows
     *  into the JVM on the request thread. Overflow rolls into the
     *  next scheduled 5-minute sweep, whose `findTriggered()` /
     *  `take(SWEEP_BATCH_LIMIT)` still picks up the un-fired rows
     *  (their `status` stays ACTIVE until `fireRow` flips them).
     *
     *  Sized to the same 1000-row ceiling as the periodic sweep —
     *  enough to clear the vast majority of single-item triggers in
     *  one sync pass without making a viral item's relist block on
     *  tens of thousands of inline pushes + emails. */
    static final int SYNC_SWEEP_ITEM_CAP = 1000

    /**
     * Synchronous per-item sweep (batch 389). Called right after a fresh
     * listing lands so any pending price alerts on that item fire within
     * seconds instead of waiting up to 5 minutes for the next scheduled
     * pass. Scoped query keeps the work O(alerts-on-this-item) even when
     * the global pool grows to thousands. Safe to call from inside the
     * sell transaction — all work wraps in try/catch and is best-effort.
     *
     * REQUIRES_NEW propagation is LOAD-BEARING — the caller (SellService.
     * relist) is itself @Transactional, so a default REQUIRED propagation
     * would JOIN the sell tx. fireRow's per-row repo.save then poisons
     * the SHARED sell tx the moment one save throws (e.g. concurrent
     * relist of the same item racing the alert flip): the per-row catch
     * absorbs the throw, sweepForItem returns normally, the SellService
     * try/catch around the call sees no exception — but when the sell
     * tx commits Spring throws UnexpectedRollbackException and the
     * caller's listing save + buy-order fulfilment + every other sell-
     * side-effect is rolled back. Catastrophic for a money-path call.
     *
     * REQUIRES_NEW gives the alert sweep its OWN tx, isolated from the
     * caller's. A failing alert save can still rollback this inner tx,
     * but the worst case is "some alerts in this sweep don't fire and
     * the next 5-min scheduled tick re-tries them" — the sell tx is
     * unaffected.
     *
     * Row-cap (SYNC_SWEEP_ITEM_CAP) is enforced at the SQL LIMIT level
     * via the paged repository method. The previous shape called
     * `findTriggeredForItem(itemId)` (un-paged) and hydrated EVERY
     * matching row into memory on the request thread before fan-out
     * began. PER_USER_LIMIT is 50 alerts per USER, but a single ITEM
     * has no per-item cap on watchers — a viral drop (sticker capsule
     * release, market-moving sale) routinely collects thousands of
     * alerts on the same item. A fresh listing at a deep discount
     * would then trigger every one of those rows, the synchronous
     * sweep would load all of them into a `List<Object[]>` on the
     * sell-request thread, run notification + email fan-out inline
     * across the entire set, and on under-provisioned nodes either
     * OOM the JVM or block the sell HTTP request for the full
     * sweep duration. With the SQL-level cap the relist returns
     * promptly; the 5-minute periodic sweep picks up the overflow
     * rows on its next tick (they're still ACTIVE in the DB until
     * `fireRow` flips them to FIRED).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void sweepForItem(Long itemId) {
        if (itemId == null) return
        try {
            def triggered = repo.findTriggeredForItem(itemId,
                org.springframework.data.domain.PageRequest.of(0, SYNC_SWEEP_ITEM_CAP))
            if (triggered == null || triggered.isEmpty()) return
            int fired = triggered.count { fireRow(it) ? 1 : 0 } as int
            if (fired > 0) {
                log.info("Watchlist alert sync-sweep (item ${itemId}): fired ${fired} of ${triggered.size()}" +
                    (triggered.size() >= SYNC_SWEEP_ITEM_CAP
                        ? " (capped at ${SYNC_SWEEP_ITEM_CAP}; overflow rolls to next scheduled sweep)"
                        : ''))
            }
        } catch (Exception e) {
            log.warn("Sync watchlist sweep for item ${itemId} failed: ${e.message}")
        }
    }

    /** Fires a single triggered row — notification + email + FIRED flip.
     *  Extracted so both the periodic sweep and the per-item synchronous
     *  sweep share the same logic. Returns true when the row was fired
     *  (notification actually pushed), false when the user was banned or
     *  the save failed. */
    private boolean fireRow(Object[] row) {
        try {
            WatchlistAlert a = row[0] as WatchlistAlert
            BigDecimal currentFloor = (row[1] as BigDecimal) ?: BigDecimal.ZERO
            // The item name is the third projection column of
            // findTriggered() / findTriggeredForItem() — both already
            // JOIN Item, so re-fetching with itemRepository.findById
            // per triggered row was a pointless N+1. Fall back to a
            // lookup only when the projection didn't carry a name (a
            // 2-element row, e.g. an older stub) so behaviour is
            // unchanged for any caller passing a short row.
            String name = (row.length > 2 ? row[2] as String : null)
            if (name == null || name.isEmpty()) {
                def item = itemRepository.findById(a.itemId).orElse(null)
                name = item?.name ?: "Item #${a.itemId}"
            }
            def user = steamUserRepository?.findById(a.userId)?.orElse(null)
            boolean userBanned = user != null && Boolean.TRUE.equals(user.banned)
            if (!userBanned) {
                notificationService?.push(a.userId, 'WATCHLIST_PRICE_DROP',
                    "Price drop · ${name}",
                    "Floor price reached \$${currentFloor.toPlainString()} (target \$${a.targetPrice.toPlainString()})",
                    a.itemId,
                    "/item/${a.itemId}")
                try {
                    if (emailService != null && emailService.canSendTo(user, 'WATCHLIST')
                            && shouldSendEmail(a.userId)) {
                        emailService.sendPriceDrop(user.email, user.displayName,
                            name, currentFloor, a.targetPrice,
                            "/item/${a.itemId}".toString())
                    }
                } catch (Exception inner) {
                    log.warn("Price-drop email failed for user ${a.userId}: ${inner.message}")
                }
            }
            a.status = 'FIRED'
            a.firedAt = System.currentTimeMillis()
            repo.save(a)
            return !userBanned
        } catch (Exception e) {
            log.warn("Watchlist alert fire failed for one row: ${e.message}")
            return false
        }
    }
}
