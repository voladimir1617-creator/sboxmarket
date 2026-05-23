package com.sboxmarket.repository

import com.sboxmarket.model.Review
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ReviewRepository extends JpaRepository<Review, Long> {

    List<Review> findByToUserIdOrderByCreatedAtDesc(Long toUserId, Pageable page)

    List<Review> findByFromUserId(Long fromUserId)

    /** Paged author-side lookup — reviews a user has written, newest
     *  first. Used by the Profile → Reviews → Given tab so a buyer can
     *  review, edit, or delete feedback they've left about sellers. */
    List<Review> findByFromUserIdOrderByCreatedAtDesc(Long fromUserId, Pageable page)

    Review findByFromUserIdAndTradeId(Long fromUserId, Long tradeId)

    /** Spam guard — count short, recent reviews authored by this buyer
     *  since the given epoch-ms cutoff. A "short" review is one whose
     *  comment is null/blank or shorter than the supplied length. Used
     *  by leaveReview to detect a buyer rapid-firing low-effort
     *  reviews (the classic copy-paste "great seller" pattern across
     *  many trades in a single sitting, often paired with a 1★ retaliation
     *  spree on a single seller). Index-friendly: filters on fromUserId
     *  (covered by idx_review_from) then narrows by createdAt + LENGTH.
     *  COALESCE keeps null comments inside the "short" bucket. */
    @Query("""
        SELECT COUNT(r) FROM Review r
        WHERE r.fromUserId = :uid
          AND r.createdAt >= :since
          AND LENGTH(COALESCE(r.comment, '')) < :maxLen
    """)
    long countRecentShortByFromUser(@Param('uid') Long fromUserId,
                                    @Param('since') Long sinceMs,
                                    @Param('maxLen') int maxLen)

    /** Aggregate stats — avoids loading all rows when we only need average + count. */
    @Query("SELECT COUNT(r), AVG(r.rating) FROM Review r WHERE r.toUserId = :uid")
    List<Object[]> aggregateForUser(@Param("uid") Long uid)

    /** Per-star histogram for a user — [[rating, count], ...] with one
     *  row per star value present. Drives the review breakdown bar
     *  chart on the stall + profile review tabs. */
    @Query("""
        SELECT r.rating, COUNT(r) FROM Review r
        WHERE r.toUserId = :uid
        GROUP BY r.rating
        ORDER BY r.rating DESC
    """)
    List<Object[]> histogramForUser(@Param("uid") Long uid)

    /** Bulk aggregate — [uid, count, avg] for every seller in the input
     *  list. Single GROUP BY query powers the verified-seller badge
     *  decoration on marketplace cards (batch 296). Sellers with no
     *  reviews are absent from the result — treat missing as count=0. */
    @Query("""
        SELECT r.toUserId, COUNT(r), AVG(r.rating) FROM Review r
        WHERE r.toUserId IN :ids
        GROUP BY r.toUserId
    """)
    List<Object[]> aggregateForUsers(@Param("ids") List<Long> userIds)
}
