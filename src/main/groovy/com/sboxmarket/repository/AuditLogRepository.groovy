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
}
