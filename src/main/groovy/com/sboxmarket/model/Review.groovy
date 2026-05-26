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
@Table(name = "reviews",
    indexes = [
        @Index(name = "idx_review_to",    columnList = "toUserId"),
        @Index(name = "idx_review_from",  columnList = "fromUserId"),
        @Index(name = "idx_review_trade", columnList = "tradeId")
    ],
    uniqueConstraints = [
        // Mirrors V67__reviews_unique_from_trade.sql so the dev H2 schema
        // (built from JPA via ddl-auto=update) carries the same guard as
        // the prod Flyway-managed Postgres schema. Without this, a service
        // double-write race could land two reviews from the same buyer on
        // the same trade in dev/CI and not be caught until prod.
        @UniqueConstraint(name = "uq_reviews_from_user_trade",
            columnNames = ["fromUserId", "tradeId"])
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

    /** Seller's public reply to the review. Optional; sellers can address
     *  the feedback directly (classic "thank you for the feedback" or
     *  "sorry this happened, reached out via DM") so the review doesn't
     *  sit unanswered in front of future buyers. Sanitised + capped. */
    @Column(length = 300)
    String sellerReply

    @Column
    Long sellerReplyAt

    /** Last time the author edited this review (batch 745). Null = never
     *  edited. Surfaces as "· edited" next to createdAt on the stall so
     *  future buyers aren't misled by a silently-rewritten rating. */
    @Column(name = "edited_at")
    Long editedAt
}
