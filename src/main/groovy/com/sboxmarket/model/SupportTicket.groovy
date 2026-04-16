package com.sboxmarket.model

import jakarta.persistence.*
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * A support ticket thread. The actual messages live in the child SupportMessage
 * table — this entity carries the ticket metadata (subject, category, status).
 */
@Entity
@Table(name = "support_tickets")
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
