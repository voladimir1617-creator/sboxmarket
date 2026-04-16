package com.sboxmarket.repository

import com.sboxmarket.model.SupportTicket
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface SupportTicketRepository extends JpaRepository<SupportTicket, Long> {

    @Query("SELECT t FROM SupportTicket t WHERE t.userId = :uid ORDER BY t.updatedAt DESC")
    List<SupportTicket> findByUser(@Param("uid") Long uid)

    /** Open ticket count for the admin + CSR dashboards. Replaces
     *  `findAll().findAll { status != 'RESOLVED' }.size()`. */
    @Query("SELECT COUNT(t) FROM SupportTicket t WHERE t.status <> 'RESOLVED'")
    long countOpen()

    @Query("SELECT COUNT(t) FROM SupportTicket t WHERE t.status = :status")
    long countByStatus(@Param('status') String status)

    /** Admin ticket triage query — ordered newest-updated-first and
     *  optionally narrowed to a single status. Empty-string sentinel for
     *  "no filter" keeps the query planner happy and sidesteps the
     *  Postgres bytea null-type-inference trap (see ItemRepository). */
    @Query("""
        SELECT t FROM SupportTicket t
        WHERE (:status = '' OR t.status = :status)
        ORDER BY t.updatedAt DESC
    """)
    List<SupportTicket> findForAdmin(@Param("status") String status)

    /** Oldest `updatedAt` across all open (non-resolved) tickets. Used
     *  by `CsrService.dashboardStats` to render a single "longest-
     *  waiting" banner without loading every ticket row into memory. */
    @Query("SELECT MIN(t.updatedAt) FROM SupportTicket t WHERE t.status <> 'RESOLVED' AND t.status = 'WAITING_STAFF'")
    Long oldestWaitingStaffUpdatedAt()
}
