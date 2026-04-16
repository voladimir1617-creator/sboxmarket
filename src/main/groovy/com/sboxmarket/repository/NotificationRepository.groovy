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
}
