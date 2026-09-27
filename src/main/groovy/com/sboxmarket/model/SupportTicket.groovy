package com.sboxmarket.model

import jakarta.persistence.*
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * A support ticket thread. The actual messages live in the child SupportMessage
 * table — this entity carries the ticket metadata (subject, category, status).
 */
@Entity
@Table(name = "support_tickets", indexes = [
    // `(user_id, updated_at)` already exists via V1__baseline for the
    // "my tickets" path. The (status, updated_at) index below covers
    // the admin/CSR triage queries `findForAdmin(status)`,
    // `searchForAdmin(status, q)`, `findStaleWaitingUser`,
    // `oldestWaitingStaffUpdatedAt`, `countByStatus`, `countOpen` —
    // each filters on status and orders by updated_at. Without the
    // index those queries do a sequential scan on every CSR / admin
    // dashboard render.
    @Index(name = "idx_support_tickets_status", columnList = "status,updated_at")
])
@JsonIgnoreProperties(["hibernateLazyInitializer", "handler"])
class SupportTicket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id

    @Column(nullable = false)
    Long userId

    @Column
    String username

    @Column(nullable = false, length = 200)
    String subject

    /** TRADE, PAYMENT, ACCOUNT, BUG, OTHER */
    @Column(nullable = false)
    String category = "OTHER"

    /** OPEN, WAITING_USER, WAITING_STAFF, RESOLVED */
    @Column(nullable = false)
    String status = "OPEN"

    @Column(name = "created_at", nullable = false)
    Long createdAt = System.currentTimeMillis()

    @Column(name = "updated_at", nullable = false)
    Long updatedAt = System.currentTimeMillis()
}
