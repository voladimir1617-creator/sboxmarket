package com.sboxmarket.service

import com.sboxmarket.model.Notification
import com.sboxmarket.repository.NotificationRepository
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Persists user-facing notifications. Every non-trivial business event in the
 * system (sale, purchase, bid, offer, buy-order fill, deposit, withdrawal) calls
 * into this service so the bell in the top-nav and the Notifications tab in the
 * profile stay in sync with reality.
 */
@Service
@Slf4j
class NotificationService {

    @Autowired NotificationRepository notificationRepository

    /** Optional so unit tests that build the service with `new
     *  NotificationService(...)` (no Spring context) still work — in that
     *  case there is never an active transaction and the deferred work
     *  runs immediately anyway. */
    @Autowired(required = false) PlatformTransactionManager transactionManager

    /** Optional so unit tests with `new NotificationService(...)` keep
     *  building. When present we use it to drop banned recipients from
     *  multi-target fan-outs (PRICE_DROPPED to cart-holders,
     *  CART_ITEM_SOLD to other cart-holders, AUCTION_ENDING to
     *  watchers/bidders). Banned users can't act on the ping — banGuard
     *  rejects every re-shop attempt — so the bell entry is dead-end
     *  noise. PurchaseService.buy got the inline version of this filter
     *  in batch 316; this lifts it to a shared helper so the 4 sister
     *  fan-outs (ListingController price edit, ListingService.bulkAdjust,
     *  OfferService.notifyOfferHoldersOfPriceDrop, AdminService.buy +
     *  AUCTION_ENDING in BidService) share one implementation. */
    @Autowired(required = false) com.sboxmarket.repository.SteamUserRepository steamUserRepository

    /**
     * Drop banned recipients from a fan-out list. Safe defaults: if the
     * repo isn't wired, or the lookup fails, return the input list
     * unchanged — matches the prior behaviour at every call site.
     *
     * Bulk single-query lookup (`findAllById`) so the fan-out stays O(1)
     * round-trips instead of O(N).
     */
    List<Long> filterActiveRecipients(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return [] as List<Long>
        List<Long> list = userIds.findAll { it != null } as List<Long>
        if (list.isEmpty()) return list
        if (steamUserRepository == null) return list
        try {
            def users = steamUserRepository.findAllById(list)
            if (users == null) return list
            Set<Long> bannedIds = users
                .findAll { Boolean.TRUE.equals(it.banned) }
                .collect { it.id } as Set<Long>
            if (bannedIds.isEmpty()) return list
            return list.findAll { !bannedIds.contains(it) } as List<Long>
        } catch (Exception e) {
            log.warn("filterActiveRecipients lookup failed (n=${list.size()}): ${e.message}")
            return list
        }
    }

    /**
     * Run {@code work} after the caller's transaction commits — or
     * immediately when there is no active transaction (e.g. a unit test
     * with no Spring proxy, or a non-transactional caller).
     *
     * The bell push is a best-effort side-effect: every one of the ~40
     * call sites wraps it expecting "a notification hiccup must NEVER
     * fail the parent purchase/trade/offer". With a plain @Transactional
     * the save() joined the caller's transaction, so a failing
     * repository.save() marked the SHARED transaction rollback-only — the
     * swallowing try/catch let the caller "succeed", then its commit blew
     * up with UnexpectedRollbackException and the real operation was
     * rolled back.
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

    Notification push(Long userId, String kind, String title, String body = null, Long refId = null, String path = null) {
        if (userId == null) return null
        def n = new Notification(
            userId: userId,
            kind:   kind,
            // Truncate to the column caps (Notification.title VARCHAR(200),
            // body VARCHAR(500)). An over-length title/body — e.g. a long
            // interpolated item name or a verbose fraud summary — would
            // otherwise throw a DataException at save() time. The deferral
            // below means such a failure can no longer poison the parent
            // transaction, but truncating still avoids losing the row.
            // (2026-05-20)
            title:  title?.take(200),
            body:   body?.take(500),
            refId:  refId,
            path:   path
        )
        // Defer the save until after the caller's transaction commits, so a
        // failing save() can't mark the caller's transaction rollback-only.
        deferOrRun { notificationRepository.save(n) }
        n
    }

    /**
     * DRY helper (batch 625): swallow + log any exception thrown by
     * `push`. Replaces the 40+ repeated `try { push(...) } catch
     * (Exception e) { log.warn("X push failed for user ${uid}") }`
     * blocks across the service layer. Call sites now write one-liner
     * `safePush(uid, kind, title, body, refId, path)` and forget about
     * the try/catch entirely. Non-transactional so the wrapping
     * transaction's success/failure is decoupled from the notification
     * side-effect — matches the intent of every existing try/catch
     * (don't fail the parent op because of a bell-push hiccup).
     *
     * Returns the saved notification, or null if the push failed.
     */
    Notification safePush(Long userId, String kind, String title, String body = null, Long refId = null, String path = null) {
        if (userId == null) return null
        try {
            return push(userId, kind, title, body, refId, path)
        } catch (Exception e) {
            log.warn("Notification push failed for user ${userId} kind=${kind}: ${e.message}")
            return null
        }
    }

    /** Up to 100 most-recent notifications. The bell UI only shows 12
     *  but admin debugging wants more history. Hard cap prevents a
     *  long-lived account from dumping its entire notification log. */
    List<Notification> listFor(Long userId) {
        listFor(userId, 100)
    }

    /** Variant with explicit limit — nav bell dropdown requests 12, the
     *  full NotificationsModal uses the 100 default. Limit is clamped
     *  at the server boundary too. */
    List<Notification> listFor(Long userId, int limit) {
        int cap = Math.max(1, Math.min(limit, 100))
        notificationRepository.findForUser(userId, PageRequest.of(0, cap))
    }

    Long countUnread(Long userId) {
        notificationRepository.countUnread(userId) ?: 0L
    }

    @Transactional
    void markRead(Long userId, Long id) {
        def n = notificationRepository.findById(id).orElse(null)
        if (n && n.userId == userId) {
            n.read = true
            notificationRepository.save(n)
        }
    }

    /** Flip a notification back to unread — lets a user defer handling
     *  ("I'll come back to this") without losing the row. Scoped to the
     *  caller so a hostile user can't reset someone else's inbox. */
    @Transactional
    void markUnread(Long userId, Long id) {
        def n = notificationRepository.findById(id).orElse(null)
        if (n && n.userId == userId) {
            n.read = false
            notificationRepository.save(n)
        }
    }

    @Transactional
    void markAllRead(Long userId) {
        // Bulk set-based UPDATE — no cap, no row hydration. The earlier
        // row-hydration sweep only swept the 500 most-recent rows, but
        // the bell badge's `unread` count comes from the *uncapped*
        // `countUnread`. A user with >500 unread would clear the badge
        // optimistically client-side, then the next poll re-read a
        // non-zero count and the badge reappeared forever. A single
        // UPDATE flips every unread row so the badge always clears.
        if (userId == null) return
        notificationRepository.markAllReadForUser(userId)
    }

    /**
     * Mark a caller-scoped *subset* of notification ids as read (batch
     * 635). Drives the filter-aware "Mark visible read" flow on the
     * notifications page — the client knows which rows are actually
     * showing (after mute, type-filter, search) and passes only those
     * ids so an accidental click doesn't silently clear the 47 unread
     * rows the user had deliberately filtered out of view.
     *
     * Ownership-scoped: rows whose `userId` doesn't match the caller
     * are silently skipped (same policy as `markRead` / `deleteOne` —
     * no existence leak). Already-read rows are no-ops. Returns the
     * count actually flipped so the client can surface "Marked N read".
     *
     * Hard-cap the input at 500 ids to cap the Hibernate load — the
     * client UI lists at most 100 per page, so 500 is more than enough
     * headroom even for power users who open the page to a filter that
     * matches their full history.
     */
    @Transactional
    int markReadByIds(Long userId, Collection<Long> ids) {
        if (userId == null || ids == null || ids.isEmpty()) return 0
        def capped = ids.findAll { it != null }.unique().take(500)
        if (capped.isEmpty()) return 0
        def rows = notificationRepository.findAllById(capped)
        def mine = rows.findAll { it.userId == userId && !it.read }
        if (mine.isEmpty()) return 0
        mine.each { it.read = true }
        notificationRepository.saveAll(mine)
        return mine.size()
    }

    /** Delete every READ notification belonging to the user. Lets users
     *  tidy an accumulating inbox after marking-all-read. Unread rows
     *  stay untouched so nothing actionable gets hidden.
     *
     *  Set-based DELETE — no cap, no row hydration. The earlier
     *  implementation hydrated the 500 most-recent rows of *any*
     *  read-state and filtered to the read ones, so a user with 500+
     *  recent UNREAD rows had their whole window consumed by unread and
     *  the "Clear read" button silently deleted nothing even though
     *  older read rows were eligible. Mirrors the markAllRead fix. */
    @Transactional
    int deleteAllRead(Long userId) {
        if (userId == null) return 0
        notificationRepository.deleteAllReadForUser(userId)
    }

    /**
     * Scoped bulk delete (batch 636). Mirrors `markReadByIds` for the
     * "Clear visible read" flow on the notifications page — the client
     * sends the visible-and-read ids, the server confirms ownership +
     * read state and deletes in one round-trip. Un-read rows are
     * silently skipped (deleting an unread row by accident would hide
     * something actionable — no thanks). Foreign / bad ids silently
     * skipped too for the same no-existence-leak policy as `deleteOne`.
     *
     * Same 500-id cap as `markReadByIds` — client pagination never
     * surfaces more than a hundred anyway, but the cap caps Hibernate
     * load if a malicious caller crafts a huge payload.
     */
    @Transactional
    int deleteReadByIds(Long userId, Collection<Long> ids) {
        if (userId == null || ids == null || ids.isEmpty()) return 0
        def capped = ids.findAll { it != null }.unique().take(500)
        if (capped.isEmpty()) return 0
        def rows = notificationRepository.findAllById(capped)
        def mine = rows.findAll { it.userId == userId && it.read }
        if (mine.isEmpty()) return 0
        notificationRepository.deleteAll(mine)
        return mine.size()
    }

    /** Delete a single notification. Silent no-op when the id doesn't
     *  exist or belongs to another user — we don't leak which is which,
     *  and the caller just sees "deleted" either way so a hostile user
     *  walking ids can't confirm existence of sibling rows. */
    @Transactional
    void deleteOne(Long userId, Long id) {
        if (userId == null || id == null) return
        def n = notificationRepository.findById(id).orElse(null)
        if (n != null && n.userId == userId) {
            notificationRepository.delete(n)
        }
    }

    /** Days after which a READ notification is eligible for automatic
     *  deletion. Unread rows never expire — they're still actionable.
     *  Env-configurable so ops can tune; 180d default gives users six
     *  months to export notification history for records before rows
     *  vanish, matches CSFloat and Discord retention for read chat. */
    @Value('${notifications.retain-read-days:180}')
    long retainReadDays

    /** Hard cap on rows deleted per sweep tick. Same shape as
     *  {@code SupportService.SWEEP_BATCH_LIMIT}, {@code
     *  WatchlistAlertService} batch clamps, etc. Without this the
     *  daily sweep is an unbounded `DELETE FROM notifications WHERE
     *  read = true AND created_at < :cutoff`. On a heavy DB (years of
     *  accumulated history) — or after an ops misconfiguration that
     *  drops `notifications.retain-read-days` from 180 to 1 — a single
     *  tick would lock the table, balloon the undo log, and risk
     *  rollback on commit-log overflow. 50k per pass keeps each tick
     *  inside a sane lock window; remaining rows drain across
     *  subsequent ticks (the rolling cutoff still includes them). */
    static final int SWEEP_BATCH_LIMIT = 50000

    /**
     * Daily sweep — deletes READ notifications older than
     * `retainReadDays`. Without this the notifications table grows
     * unbounded (1 bell-worthy event per trade, times 10k+ trades
     * per active user, times years of history). Unread rows stay
     * regardless of age because a long-unread row is a signal the
     * user hasn't attended to it yet.
     *
     * Per-tick row count is clamped at {@link #SWEEP_BATCH_LIMIT} to
     * cap mass deletion: the previous unbounded `deleteReadOlderThan`
     * could match millions of rows in a single statement on a heavy
     * DB or after a `notifications.retain-read-days` cut — locking
     * the table, blowing the undo log, and risking commit-log overflow.
     * The capped version fetches the oldest N matching ids in a single
     * indexed scan and deletes that bounded set; remaining backlog
     * drains across subsequent daily ticks.
     *
     * Runs once a day at a 30-minute offset so it doesn't collide
     * with the other sweepers on container start. Logs the row
     * count for ops visibility.
     */
    @Scheduled(fixedDelay = 24L * 60L * 60L * 1000L,
               initialDelay = 30L * 60L * 1000L)
    @Transactional
    void sweepOldReadNotifications() {
        if (retainReadDays <= 0L) return
        def cutoff = System.currentTimeMillis() - (retainReadDays * 24L * 60L * 60L * 1000L)
        def ids = notificationRepository.findReadIdsOlderThan(
            cutoff, PageRequest.of(0, SWEEP_BATCH_LIMIT))
        if (ids == null || ids.isEmpty()) return
        notificationRepository.deleteAllByIdInBatch(ids)
        int n = ids.size()
        log.info("Notification retention sweep: deleted ${n} read row(s) older than ${retainReadDays}d" +
            (n >= SWEEP_BATCH_LIMIT ? " (capped at ${SWEEP_BATCH_LIMIT} — backlog will drain across subsequent ticks)" : ''))
    }
}
