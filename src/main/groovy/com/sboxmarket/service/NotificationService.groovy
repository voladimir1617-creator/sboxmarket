package com.sboxmarket.service

import com.sboxmarket.model.Notification
import com.sboxmarket.repository.NotificationRepository
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

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

    @Transactional
    Notification push(Long userId, String kind, String title, String body = null, Long refId = null, String path = null) {
        if (userId == null) return null
        def n = new Notification(
            userId: userId,
            kind:   kind,
            title:  title,
            body:   body,
            refId:  refId,
            path:   path
        )
        notificationRepository.save(n)
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
     *  stay untouched so nothing actionable gets hidden. */
    @Transactional
    int deleteAllRead(Long userId) {
        def recent = notificationRepository.findForUser(userId, PageRequest.of(0, 500))
        def toDelete = recent.findAll { it.read }
        if (toDelete.isEmpty()) return 0
        notificationRepository.deleteAll(toDelete)
        toDelete.size()
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

    /**
     * Daily sweep — deletes READ notifications older than
     * `retainReadDays`. Without this the notifications table grows
     * unbounded (1 bell-worthy event per trade, times 10k+ trades
     * per active user, times years of history). Unread rows stay
     * regardless of age because a long-unread row is a signal the
     * user hasn't attended to it yet.
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
        int n = notificationRepository.deleteReadOlderThan(cutoff)
        if (n > 0) log.info("Notification retention sweep: deleted ${n} read row(s) older than ${retainReadDays}d")
    }
}
