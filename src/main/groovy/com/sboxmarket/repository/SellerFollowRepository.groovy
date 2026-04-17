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

    /** Seller ids the given user follows. Drives the home-page "From
     *  sellers you follow" rail — we need just the ids to fan out to
     *  a single listing query, not the full join rows. */
    @Query("SELECT f.sellerUserId FROM SellerFollow f WHERE f.followerUserId = :uid")
    List<Long> findSellerIdsByFollower(@Param("uid") Long followerUserId)

    long deleteByFollowerUserIdAndSellerUserId(Long followerUserId, Long sellerUserId)
}
