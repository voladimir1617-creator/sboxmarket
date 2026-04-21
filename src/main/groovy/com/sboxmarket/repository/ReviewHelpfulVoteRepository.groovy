package com.sboxmarket.repository

import com.sboxmarket.model.ReviewHelpfulVote
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ReviewHelpfulVoteRepository extends JpaRepository<ReviewHelpfulVote, Long> {

    /** Is this review already upvoted by this user? Used both as the
     *  "toggle" pre-check and to decorate review DTOs with
     *  viewerHasVoted. Cheap — serves from the unique index. */
    @Query("""
        SELECT COUNT(v) > 0 FROM ReviewHelpfulVote v
        WHERE v.reviewId = :reviewId AND v.userId = :userId
    """)
    boolean existsByReviewAndUser(@Param('reviewId') Long reviewId, @Param('userId') Long userId)

    /** Total helpful votes on a single review — projected into the review
     *  DTO so the UI can show "👍 N" without a second round trip. */
    @Query("SELECT COUNT(v) FROM ReviewHelpfulVote v WHERE v.reviewId = :reviewId")
    long countByReview(@Param('reviewId') Long reviewId)

    /** Batch count of helpful votes grouped by review id — drives the
     *  stall page so N reviews need N *rows* of output, not N COUNT
     *  round trips. Empty reviews (0 votes) are not returned; the
     *  caller defaults them. */
    @Query("""
        SELECT v.reviewId, COUNT(v) FROM ReviewHelpfulVote v
        WHERE v.reviewId IN :reviewIds
        GROUP BY v.reviewId
    """)
    List<Object[]> countBulk(@Param('reviewIds') List<Long> reviewIds)

    /** Batch "did I vote on these?" lookup — returns the review ids from
     *  the caller's list the user has voted on. Empty input returns
     *  empty so the caller doesn't need an extra isEmpty guard. */
    @Query("""
        SELECT v.reviewId FROM ReviewHelpfulVote v
        WHERE v.userId = :userId AND v.reviewId IN :reviewIds
    """)
    List<Long> findVotedReviewIds(@Param('userId') Long userId,
                                  @Param('reviewIds') List<Long> reviewIds)

    @Modifying
    @Query("DELETE FROM ReviewHelpfulVote v WHERE v.reviewId = :reviewId AND v.userId = :userId")
    int deleteByReviewAndUser(@Param('reviewId') Long reviewId, @Param('userId') Long userId)
}
