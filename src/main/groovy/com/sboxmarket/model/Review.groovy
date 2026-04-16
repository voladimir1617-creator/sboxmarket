package com.sboxmarket.model

import jakarta.persistence.*
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull

/**
 * A buyer-to-seller review tied to a specific completed trade.
 *
 * Rules:
 *   - One review per (fromUserId, tradeId). Attempting to re-review the
 *     same trade is rejected at the service layer.
 *   - Ratings are 1–5 stars (integer). Zero means "unrated", not allowed
 *     as a stored value.
 *   - Only the buyer of a VERIFIED trade can leave a review — enforced
 *     by ReviewService.leaveReview.
 *   - Comments are sanitised and capped at 500 chars.
 *
 * Reviews show up on the public stall page so potential buyers can see
 * an aggregate rating + recent comments for any seller.
 */
@Entity
@Table(name = "reviews", indexes = [
    @Index(name = "idx_review_to",    columnList = "toUserId"),
    @Index(name = "idx_review_from",  columnList = "fromUserId"),
    @Index(name = "idx_review_trade", columnList = "tradeId")
])
class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id

    @NotNull
    @Column(nullable = false)
    Long fromUserId

    @NotNull
    @Column(nullable = false)
    Long toUserId

    /** The trade this review is attached to. Guarantees the author actually traded with the seller. */
    @NotNull
    @Column(nullable = false)
    Long tradeId

    @NotNull
    @Min(1)
    @Max(5)
    @Column(nullable = false)
    Integer rating

    @Column(length = 500)
    String comment

    /** Cached denormalised fields so listing a stall's reviews doesn't need a join. */
    @Column(length = 80)
    String fromDisplayName

    @Column(length = 255)
    String itemName

    @Column(nullable = false)
    Long createdAt = System.currentTimeMillis()
}
