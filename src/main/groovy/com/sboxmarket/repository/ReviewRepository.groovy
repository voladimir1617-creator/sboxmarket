package com.sboxmarket.repository

import com.sboxmarket.model.Review
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ReviewRepository extends JpaRepository<Review, Long> {

    List<Review> findByToUserIdOrderByCreatedAtDesc(Long toUserId, Pageable page)

    List<Review> findByFromUserId(Long fromUserId)

    Review findByFromUserIdAndTradeId(Long fromUserId, Long tradeId)

    /** Aggregate stats — avoids loading all rows when we only need average + count. */
    @Query("SELECT COUNT(r), AVG(r.rating) FROM Review r WHERE r.toUserId = :uid")
    List<Object[]> aggregateForUser(@Param("uid") Long uid)
}
