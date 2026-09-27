package com.sboxmarket.repository

import com.sboxmarket.model.AuditLog
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

/**
 * Every query below takes a `Pageable` so the admin audit view can
 * never accidentally dump the entire audit log (millions of rows once
 * the platform gets real traffic). The service layer passes a
 * hard-capped `PageRequest.of(0, 500)` for unfiltered recent-first
 * queries. The old unbounded signatures were bug #51 — sending every
 * audit row over the wire on every /api/admin/audit fetch.
 */
@Repository
interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    @Query("SELECT a FROM AuditLog a ORDER BY a.createdAt DESC")
    List<AuditLog> recent(Pageable page)

    @Query("SELECT a FROM AuditLog a WHERE a.actorUserId = :uid ORDER BY a.createdAt DESC")
    List<AuditLog> byActor(@Param("uid") Long uid, Pageable page)

    @Query("SELECT a FROM AuditLog a WHERE a.subjectUserId = :uid ORDER BY a.createdAt DESC")
    List<AuditLog> bySubject(@Param("uid") Long uid, Pageable page)

    @Query("SELECT a FROM AuditLog a WHERE a.eventType = :e ORDER BY a.createdAt DESC")
    List<AuditLog> byEvent(@Param("e") String eventType, Pageable page)

    /** Rows newer than a given wall-clock millis — used by FraudAnalysisService for velocity windows. */
    @Query("SELECT a FROM AuditLog a WHERE a.createdAt >= :since ORDER BY a.createdAt DESC")
    List<AuditLog> since(@Param("since") Long since)

    /** Paged companion to {@link #since(Long)} — same query shape, plus a
     *  Pageable so callers can cap the velocity-window hydration at the
     *  500-row ceiling used elsewhere in this repository. The unbounded
     *  variant stays on the interface for backwards compatibility with
     *  the existing FraudAnalysisService call site; new callers should
     *  prefer this overload. */
    @Query("SELECT a FROM AuditLog a WHERE a.createdAt >= :since ORDER BY a.createdAt DESC")
    List<AuditLog> since(@Param("since") Long since, Pageable page)

    // Date-filtered variants (batch 556) — every existing filter gets a
    // companion that applies an optional `since` lower bound. Uses
    // nullable-sentinel param (caller passes 0L for "no floor") so the
    // query planner keeps the same shape regardless. Paged like every
    // other audit query to keep the 500-row cap.

    @Query("SELECT a FROM AuditLog a WHERE a.createdAt >= :since ORDER BY a.createdAt DESC")
    List<AuditLog> recentSince(@Param("since") Long since, Pageable page)

    @Query("SELECT a FROM AuditLog a WHERE a.actorUserId = :uid AND a.createdAt >= :since ORDER BY a.createdAt DESC")
    List<AuditLog> byActorSince(@Param("uid") Long uid, @Param("since") Long since, Pageable page)

    @Query("SELECT a FROM AuditLog a WHERE a.subjectUserId = :uid AND a.createdAt >= :since ORDER BY a.createdAt DESC")
    List<AuditLog> bySubjectSince(@Param("uid") Long uid, @Param("since") Long since, Pageable page)

    @Query("SELECT a FROM AuditLog a WHERE a.eventType = :e AND a.createdAt >= :since ORDER BY a.createdAt DESC")
    List<AuditLog> byEventSince(@Param("e") String eventType, @Param("since") Long since, Pageable page)

    /** Per-user + per-event query (batch 569) — drives the "my sign-in
     *  history" section on the user profile. Scoped to subjectUserId so
     *  a regular user can only see events about themselves. Paged at
     *  the call site (profile shows 20, admin CSV 500).
     */
    @Query("""
        SELECT a FROM AuditLog a
        WHERE a.subjectUserId = :uid
          AND a.eventType     = :e
        ORDER BY a.createdAt DESC
    """)
    List<AuditLog> byUserAndEvent(@Param("uid") Long userId,
                                  @Param("e") String eventType,
                                  Pageable page)

    /** Distinct non-null IPs observed on this user's sign-in audit rows
     *  since the given wall-clock millis (batch 603). Fraud-triage
     *  signal: a lifetime 4-IP account that suddenly signs in from 20
     *  different IPs in 24h is a shared-credential flag. Null IPs are
     *  filtered (pre-batch-408 sign-in rows didn't capture IP). Scoped
     *  to `USER_SIGN_IN` rows so an audit-rich account's ip churn
     *  doesn't get diluted by staff-action rows.
     */
    @Query("""
        SELECT COUNT(DISTINCT a.ipAddress) FROM AuditLog a
        WHERE a.subjectUserId = :uid
          AND a.eventType = 'USER_SIGN_IN'
          AND a.ipAddress IS NOT NULL
          AND a.createdAt >= :since
    """)
    long countDistinctSignInIpsSince(@Param("uid") Long uid, @Param("since") Long since)

    /** Has this user signed in from the given IP inside the window?
     *  Drives the "new sign-in location" security email (batch 606).
     *  Returns COUNT so the caller can treat 0 as "new IP, send the
     *  alert". Scoped to USER_SIGN_IN so staff-action rows don't
     *  pollute the check.
     */
    @Query("""
        SELECT COUNT(a) FROM AuditLog a
        WHERE a.subjectUserId = :uid
          AND a.eventType = 'USER_SIGN_IN'
          AND a.ipAddress = :ip
          AND a.createdAt >= :since
    """)
    long countSignInsFromIpSince(@Param("uid") Long uid,
                                  @Param("ip") String ip,
                                  @Param("since") Long since)
}
