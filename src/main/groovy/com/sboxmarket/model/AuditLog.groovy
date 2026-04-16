package com.sboxmarket.model

import jakarta.persistence.*
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * Append-only audit log for privileged actions. Every staff action (ban,
 * unban, refund, withdrawal approval, force-cancel, admin grant, goodwill
 * credit) and every self-service money movement (deposit, withdrawal,
 * purchase) writes one row. Rows are never updated or deleted.
 *
 * The schema is deliberately wide + string-typed so a future `event_type`
 * doesn't need a migration — just write a new constant on the service side.
 */
@Entity
@Table(
    name = "audit_log",
    indexes = [
        @Index(name = "idx_audit_actor",    columnList = "actorUserId,createdAt"),
        @Index(name = "idx_audit_subject",  columnList = "subjectUserId,createdAt"),
        @Index(name = "idx_audit_event",    columnList = "eventType,createdAt"),
    ]
)
@JsonIgnoreProperties(["hibernateLazyInitializer", "handler"])
class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id

    /** User who performed the action. Null for system events (e.g. scheduled jobs). */
    @Column
    Long actorUserId

    @Column(length = 80)
    String actorName

    /** User affected by the action, if different from the actor. */
    @Column
    Long subjectUserId

    @Column(length = 80)
    String subjectName

    /** Short machine-readable event type, e.g. USER_BANNED, WITHDRAWAL_APPROVED, REFUND_ISSUED. */
    @Column(nullable = false, length = 48)
    String eventType

    /** Optional id of the related resource — listing id, transaction id, ticket id, etc. */
    @Column
    Long resourceId

    /** Free-form one-line summary (max 500 chars) — shown in the admin audit tab. */
    @Column(length = 500)
    String summary

    @Column(length = 64)
    String ipAddress

    @Column(length = 120)
    String userAgent

    @Column(name = "created_at", nullable = false)
    Long createdAt = System.currentTimeMillis()
}
