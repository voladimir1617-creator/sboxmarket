package com.sboxmarket.repository

import com.sboxmarket.model.Notification
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface NotificationRepository extends JpaRepository<Notification, Long> {

    /**
     * Paged recent-first notifications for a user. The bell UI only
     * renders the first 12, but we return up to `PageRequest.of(0, 100)`
     * so an admin can see a deeper history when debugging. The hard cap
     * here prevents a long-lived account with 100k notifications from
     * dumping them all on every bell refresh.
     */
    @Query("SELECT n FROM Notification n WHERE n.userId = :uid ORDER BY n.createdAt DESC")
    List<Notification> findForUser(@Param("uid") Long uid, Pageable page)

    @Query("SELECT COUNT(n) FROM Notification n WHERE n.userId = :uid AND n.read = false")
    Long countUnread(@Param("uid") Long uid)

    /** Total notifications a user owns (read + unread) — the cap gate for
     *  the per-user write-path trim (NotificationService.trimToCapForUser).
     *  Indexed on user_id, so cheap to call per push. */
    @Query("SELECT COUNT(n) FROM Notification n WHERE n.userId = :uid")
    long countByUser(@Param("uid") Long uid)

    /** How many `kind` notifications about `refId` the user got since
     *  `since`. Lets one-per-actor pings (a new follower) dedupe repeats. */
    @Query("SELECT COUNT(n) FROM Notification n WHERE n.userId = :uid AND n.kind = :kind AND n.refId = :refId AND n.createdAt >= :since")
    long countRecentByKindAndRef(@Param("uid") Long uid, @Param("kind") String kind,
                                 @Param("refId") Long refId, @Param("since") Long since)

    /** Bulk-delete a user's notifications older than `cutoff` (epoch ms).
     *  Drives the per-user row cap: once a user exceeds the cap we purge
     *  everything older than their Nth-newest row in one set-based DELETE
     *  — no Hibernate session pressure, no window function. The previous
     *  retention sweep only removed READ rows >180d and never bounded
     *  UNREAD growth, so an attacker who can trigger notifications to a
     *  victim could grow the table without limit. This caps it regardless
     *  of read-state. Returns rows removed. */
    // Own transaction: the scheduled sweep and NotificationService.push call
    // this outside one, and a bare @Modifying query then throws.
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM Notification n WHERE n.userId = :uid AND n.createdAt < :cutoff")
    int deleteForUserOlderThan(@Param("uid") Long uid, @Param("cutoff") Long cutoff)

    /** Bulk-flip every UNREAD notification for a user to read in a
     *  single UPDATE. Drives "Mark all read" from the bell.
     *
     *  The previous row-hydration sweep only touched the 500
     *  most-recent rows, so a user with >500 unread cleared the bell
     *  badge optimistically client-side, then the next poll re-read a
     *  non-zero `countUnread` (which is uncapped) and the badge
     *  reappeared forever. A set-based UPDATE has no cap and no
     *  Hibernate session pressure, so it always agrees with
     *  `countUnread`. Returns the row count flipped. */
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE Notification n SET n.read = true WHERE n.userId = :uid AND n.read = false")
    int markAllReadForUser(@Param("uid") Long uid)

    /** Bulk-delete every READ notification older than the given cutoff.
     *  Drives the daily purge sweeper (batch 359) that keeps the
     *  notifications table bounded on heavy accounts. Unread rows are
     *  preserved regardless of age — they're still actionable. Returns
     *  the row count removed. */
    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM Notification n WHERE n.read = true AND n.createdAt < :cutoff")
    int deleteReadOlderThan(@Param("cutoff") Long cutoff)

    /** Paged companion — ids of READ rows older than `cutoff`, ordered
     *  oldest-first so the sweeper drains the longest-stale rows
     *  before newer ones. Drives the per-tick row cap in
     *  {@code NotificationService.sweepOldReadNotifications}: without
     *  a cap the bulk DELETE on a years-old DB (or a freshly-lowered
     *  `notifications.retain-read-days`) could match millions of rows
     *  in a single statement, locking the table for the duration of
     *  the undo-log write, blocking every concurrent read, and
     *  generating a transaction-log spike large enough to fail the
     *  prod commit. Capping at SWEEP_BATCH_LIMIT per tick keeps each
     *  pass bounded; remaining rows drain across subsequent daily
     *  ticks (the rolling cutoff naturally re-includes them). */
    @Query("SELECT n.id FROM Notification n WHERE n.read = true AND n.createdAt < :cutoff ORDER BY n.createdAt ASC")
    List<Long> findReadIdsOlderThan(@Param("cutoff") Long cutoff,
                                     org.springframework.data.domain.Pageable pageable)

    /** Bulk-delete every READ notification a user owns in a single
     *  DELETE. Drives "Clear read" from the bell.
     *
     *  The previous implementation hydrated the 500 most-recent rows of
     *  *any* read-state and filtered to the read ones — so a user with
     *  500+ recent UNREAD rows had their entire window taken up by
     *  unread, leaving `toDelete` empty and the button a silent no-op
     *  even though thousands of older read rows were eligible. A
     *  set-based DELETE has no window and no Hibernate session
     *  pressure, so it always honours the "every READ notification"
     *  contract. Returns the row count removed. */
    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM Notification n WHERE n.userId = :uid AND n.read = true")
    int deleteAllReadForUser(@Param("uid") Long uid)
}
