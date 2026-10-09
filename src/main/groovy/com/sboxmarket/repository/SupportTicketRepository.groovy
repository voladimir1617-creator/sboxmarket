package com.sboxmarket.repository

import com.sboxmarket.model.SupportTicket
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface SupportTicketRepository extends JpaRepository<SupportTicket, Long> {

    @Query("SELECT t FROM SupportTicket t WHERE t.userId = :uid ORDER BY t.updatedAt DESC")
    List<SupportTicket> findByUser(@Param("uid") Long uid)

    /** Paged companion — a long-tenure user can accumulate many
     *  resolved tickets; the cap keeps the Profile → Tickets tab open
     *  at O(pageSize) regardless of historical volume. */
    @Query("SELECT t FROM SupportTicket t WHERE t.userId = :uid ORDER BY t.updatedAt DESC")
    List<SupportTicket> findByUser(@Param("uid") Long uid,
                                   org.springframework.data.domain.Pageable pageable)

    /** Open ticket count for the admin + CSR dashboards. Replaces
     *  `findAll().findAll { status != 'RESOLVED' }.size()`. */
    @Query("SELECT COUNT(t) FROM SupportTicket t WHERE t.status <> 'RESOLVED'")
    long countOpen()

    /** Per-user open-ticket count. Gates SupportService.create so a
     *  single user can't spam the queue: every new ticket fans a
     *  SUPPORT_REPLY bell push to every ADMIN + CSR (see
     *  SupportService.create staff fan-out, lines 202-221), so an
     *  unbounded open-ticket spammer is a free DoS on the staff
     *  inbox. Same pattern as SavedSearchRepository.countByUser /
     *  CartItemRepository.countByUser / WatchlistItemRepository.
     *  countByUser. Only counts non-RESOLVED rows so the cap is on
     *  the live queue, not a user's lifetime ticket history. */
    @Query("SELECT COUNT(t) FROM SupportTicket t WHERE t.userId = :uid AND t.status <> 'RESOLVED'")
    long countOpenByUser(@Param('uid') Long uid)

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

    /** Paged companion — the admin ticket queue grows without bound on
     *  the RESOLVED filter; new admin UI calls should pass a Pageable. */
    @Query("""
        SELECT t FROM SupportTicket t
        WHERE (:status = '' OR t.status = :status)
        ORDER BY t.updatedAt DESC
    """)
    List<SupportTicket> findForAdmin(@Param("status") String status,
                                     org.springframework.data.domain.Pageable pageable)

    /** Search variant of the admin triage query (batch 576). Narrows to
     *  rows whose subject / username / category (case-insensitive)
     *  matches the free-text `q`. Empty-string sentinel on both filters
     *  so the query planner stays predictable. `status` still gates the
     *  result set on top of the text match — "all RESOLVED tickets
     *  containing chargeback" is the common staff search. */
    @Query("""
        SELECT t FROM SupportTicket t
        WHERE (:status = '' OR t.status = :status)
          AND (:q = '' OR
               LOWER(t.subject)  LIKE LOWER(CONCAT('%', :q, '%')) ESCAPE '\\' OR
               LOWER(t.username) LIKE LOWER(CONCAT('%', :q, '%')) ESCAPE '\\' OR
               LOWER(t.category) LIKE LOWER(CONCAT('%', :q, '%')) ESCAPE '\\')
        ORDER BY t.updatedAt DESC
    """)
    List<SupportTicket> searchForAdmin(@Param("status") String status,
                                        @Param("q") String query)

    /** Paged companion — broad searches (e.g. status='' + short q) can
     *  match thousands of historical tickets; admin UI should cap. */
    @Query("""
        SELECT t FROM SupportTicket t
        WHERE (:status = '' OR t.status = :status)
          AND (:q = '' OR
               LOWER(t.subject)  LIKE LOWER(CONCAT('%', :q, '%')) ESCAPE '\\' OR
               LOWER(t.username) LIKE LOWER(CONCAT('%', :q, '%')) ESCAPE '\\' OR
               LOWER(t.category) LIKE LOWER(CONCAT('%', :q, '%')) ESCAPE '\\')
        ORDER BY t.updatedAt DESC
    """)
    List<SupportTicket> searchForAdmin(@Param("status") String status,
                                        @Param("q") String query,
                                        org.springframework.data.domain.Pageable pageable)

    /** Oldest `updatedAt` across all open (non-resolved) tickets. Used
     *  by `CsrService.dashboardStats` to render a single "longest-
     *  waiting" banner without loading every ticket row into memory. */
    @Query("SELECT MIN(t.updatedAt) FROM SupportTicket t WHERE t.status <> 'RESOLVED' AND t.status = 'WAITING_STAFF'")
    Long oldestWaitingStaffUpdatedAt()

    /** Stale WAITING_USER tickets (batch 553) — staff replied long ago,
     *  the user never came back. Drives the daily auto-close sweep so
     *  the open-ticket queue doesn't accumulate dead threads. Indexed
     *  by the existing `(status)` column; cheap even at 1M+ rows. */
    @Query("""
        SELECT t FROM SupportTicket t
        WHERE t.status = 'WAITING_USER'
          AND t.updatedAt <= :cutoff
        ORDER BY t.updatedAt ASC
    """)
    List<SupportTicket> findStaleWaitingUser(@Param("cutoff") Long cutoff)

    /** Paged companion — same batched-sweeper rationale as the offer /
     *  transaction sweepers; chunk through the stale set rather than
     *  hydrating it all per tick. */
    @Query("""
        SELECT t FROM SupportTicket t
        WHERE t.status = 'WAITING_USER'
          AND t.updatedAt <= :cutoff
        ORDER BY t.updatedAt ASC
    """)
    List<SupportTicket> findStaleWaitingUser(@Param("cutoff") Long cutoff,
                                             org.springframework.data.domain.Pageable pageable)

    /**
     * Atomic claim for the stale-WAITING_USER auto-close sweep (wave 127).
     * Flips `status` from WAITING_USER→RESOLVED and stamps `updatedAt`
     * ONLY if the ticket is still WAITING_USER at UPDATE time. Returns
     * the affected-row count: 1 = this pod owns the fan-out
     * (TICKET_AUTO_RESOLVED bell push), 0 = a sibling pod already
     * claimed it OR the user replied between sweeper read and claim
     * (status flipped to WAITING_STAFF) — in either case the losing
     * path bails before the auto-close notification runs.
     *
     * Multi-pod race protection. Same shape as waves 112, 120, 124,
     * 125, 126. Without an atomic claim each pod independently fires
     * the TICKET_AUTO_RESOLVED bell push before either pod's `save()`
     * lands — the user receives "your support ticket was auto-closed"
     * TWICE for one ticket. Worse: if the user replied between read
     * and save (flipping status to WAITING_STAFF), the unconditional
     * save was about to overwrite that with RESOLVED, silently
     * discarding the user's reply and closing a ticket they expected
     * staff to read.
     */
    // Own transaction: the daily sweep calls this outside one, and a bare
    // @Modifying query then throws, so no stale ticket was ever closed.
    @org.springframework.transaction.annotation.Transactional
    @Modifying
    @Query("""
        UPDATE SupportTicket t
           SET t.status    = 'RESOLVED',
               t.updatedAt = :now
         WHERE t.id     = :id
           AND t.status = 'WAITING_USER'
    """)
    int claimAutoResolve(@Param('id') Long id, @Param('now') Long now)
}
