package com.sboxmarket.model

import jakarta.persistence.*
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@Entity
@Table(name = "support_messages")
@JsonIgnoreProperties(["hibernateLazyInitializer", "handler"])
class SupportMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id

    @Column(nullable = false)
    Long ticketId

    /** USER, STAFF — drives the bubble side in the thread UI. */
    @Column(nullable = false)
    String author = "USER"

    @Column
    String authorName

    @Column(nullable = false, length = 2000)
    String body

    @Column(name = "created_at", nullable = false)
    Long createdAt = System.currentTimeMillis()
}
