package com.sboxmarket.model

import jakarta.persistence.*
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * A single bid on an auction listing. We keep the full bid history for transparency
 * so the item detail view can render a bid log and the profile "Auto-Bids" tab can
 * show historical standing bids. The current top bid is denormalised onto Listing
 * for O(1) read on the marketplace grid.
 */
@Entity
@Table(name = "bids")
@JsonIgnoreProperties(["hibernateLazyInitializer", "handler"])
class Bid {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id

    @Column(nullable = false)
    Long listingId

    @Column(nullable = false)
    Long bidderUserId

    @Column
    String bidderName

    @Column(nullable = false, precision = 19, scale = 2)
    BigDecimal amount

    /** For auto-bid entries, the maximum the bot should climb to; null for manual bids. */
    @Column(precision = 19, scale = 2)
    BigDecimal maxAmount

    /** MANUAL or AUTO. */
    @Column(nullable = false)
    String kind = "MANUAL"

    /** WINNING, OUTBID, WON, LOST, CANCELLED. */
    @Column(nullable = false)
    String status = "WINNING"

    @Column(name = "created_at", nullable = false)
    Long createdAt = System.currentTimeMillis()
}
