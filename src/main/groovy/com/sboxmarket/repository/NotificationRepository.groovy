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
}
