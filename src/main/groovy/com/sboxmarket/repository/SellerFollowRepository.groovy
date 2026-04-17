package com.sboxmarket.repository

import com.sboxmarket.model.SellerFollow
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface SellerFollowRepository extends JpaRepository<SellerFollow, Long> {

    Optional<SellerFollow> findByFollowerUserIdAndSellerUserId(Long followerUserId, Long sellerUserId)

    /** All followers of a seller — drives the per-listing fanout when
     *  a new listing is posted. Indexed on seller_user_id. */
    List<SellerFollow> findBySellerUserId(Long sellerUserId)

    /** Sellers a user follows, newest-first — powers the "Sellers I
     *  follow" profile tab. */
    List<SellerFollow> findByFollowerUserIdOrderByCreatedAtDesc(Long followerUserId)

    /** Follower count for the public stall "N followers" chip. Single
     *  indexed COUNT instead of pulling the join rows. */
    @Query("SELECT COUNT(f) FROM SellerFollow f WHERE f.sellerUserId = :sellerId")
    long countBySeller(@Param("sellerId") Long sellerId)

    long deleteByFollowerUserIdAndSellerUserId(Long followerUserId, Long sellerUserId)
}
