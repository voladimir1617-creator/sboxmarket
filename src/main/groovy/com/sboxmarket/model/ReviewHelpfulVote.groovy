package com.sboxmarket.model

import jakarta.persistence.*

/**
 * "Helpful" upvote a viewer placed on a review. One row per
 * (reviewId, userId) — enforced by a unique constraint so a user can't
 * double-vote on the same review. A second attempt in the same
 * direction is a no-op at the service layer; removing the row is the
 * "unvote" action the same button triggers.
 *
 * Deliberately minimal: the review aggregate is computed with a COUNT
 * on review_id at read time, and the "did I vote?" flag is a single
 * EXISTS by (reviewId, userId) — both served by the indexes created
 * in V29.
 */
@Entity
@Table(name = "review_helpful_votes", indexes = [
    @Index(name = "idx_review_helpful_votes_review", columnList = "reviewId"),
    @Index(name = "idx_review_helpful_votes_user",   columnList = "userId")
])
class ReviewHelpfulVote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id

    @Column(name = 'review_id', nullable = false)
    Long reviewId

    @Column(name = 'user_id', nullable = false)
    Long userId

    @Column(name = 'created_at', nullable = false)
    Long createdAt = System.currentTimeMillis()
}
