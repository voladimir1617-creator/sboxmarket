package com.sboxmarket.model

import jakarta.persistence.*

/**
 * One row per (listing, reporter) pair. Persisted so the service layer
 * can reject duplicate reports from the same user (the UNIQUE index
 * enforces it at the DB as a second line of defence) and so admins can
 * read back the originating reasons if a pattern emerges. The aggregate
 * counter on `listings.report_count` is the fast-path display; this
 * table is only read when an admin drills in.
 */
@Entity
@Table(name = "listing_reports")
class ListingReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id

    @Column(name = "listing_id", nullable = false)
    Long listingId

    @Column(name = "reporter_user_id", nullable = false)
    Long reporterUserId

    @Column(length = 80)
    String reason

    @Column(length = 500)
    String note

    @Column(name = "created_at", nullable = false)
    Long createdAt = System.currentTimeMillis()
}
