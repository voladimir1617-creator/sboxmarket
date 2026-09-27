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

    /** Derived / transient — enriched by BidService.liveBidsForUser so the
     *  Profile Active-Bids tab can deep-link "Listing #N" → `/item/<itemId>`
     *  without a second round-trip. Not persisted. */
    @Transient
    Long itemId

    /** Derived / transient — item name looked up alongside itemId for the
     *  Profile Active-Bids tab so the row reads "Wizard Hat" rather than
     *  "Listing #42". Not persisted. */
    @Transient
    String itemName

    /** Derived / transient — when the underlying auction closes (epoch ms).
     *  Surfaced on the Active Bids tab so a bidder can see the countdown
     *  next to each live bid without opening every item page. Not persisted. */
    @Transient
    Long listingExpiresAt

    /** Derived / transient — the auction's current top bid at the moment
     *  the list was fetched. Lets the Active Bids tab show "Your $20 /
     *  current $25" so an outbid viewer knows how far behind they are.
     *  Not persisted. */
    @Transient
    BigDecimal listingCurrentBid
}
