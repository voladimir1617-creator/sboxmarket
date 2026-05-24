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

    /** Paged companion — a popular seller accumulates many followers;
     *  per-listing fan-out should batch via Pageable rather than emit
     *  thousands of notifications in a single transaction. */
    List<SellerFollow> findBySellerUserId(Long sellerUserId,
                                          org.springframework.data.domain.Pageable pageable)

    /** Sellers a user follows, newest-first — powers the "Sellers I
     *  follow" profile tab. */
    List<SellerFollow> findByFollowerUserIdOrderByCreatedAtDesc(Long followerUserId)

    /** Paged companion — bounded by PER_USER_LIMIT but the cap keeps the
     *  Profile → Following tab render at O(pageSize) regardless. */
    List<SellerFollow> findByFollowerUserIdOrderByCreatedAtDesc(Long followerUserId,
                                                                org.springframework.data.domain.Pageable pageable)

    /** Follower count for the public stall "N followers" chip. Single
     *  indexed COUNT instead of pulling the join rows. */
    @Query("SELECT COUNT(f) FROM SellerFollow f WHERE f.sellerUserId = :sellerId")
    long countBySeller(@Param("sellerId") Long sellerId)

    /** Count of sellers a user follows — drives the PER_USER_LIMIT cap
     *  check in {@link com.sboxmarket.service.SellerFollowService#follow}.
     *  Previously the service hydrated every row just to call .size();
     *  this one-query COUNT avoids the fetch. */
    long countByFollowerUserId(Long followerUserId)

    /** Seller ids the given user follows. Drives the home-page "From
     *  sellers you follow" rail — we need just the ids to fan out to
     *  a single listing query, not the full join rows. */
    @Query("SELECT f.sellerUserId FROM SellerFollow f WHERE f.followerUserId = :uid")
    List<Long> findSellerIdsByFollower(@Param("uid") Long followerUserId)

    /** Paged companion — bounded by PER_USER_LIMIT, but a cap keeps
     *  the homepage rail query from JOIN-FETCHing a large IN clause. */
    @Query("SELECT f.sellerUserId FROM SellerFollow f WHERE f.followerUserId = :uid")
    List<Long> findSellerIdsByFollower(@Param("uid") Long followerUserId,
                                       org.springframework.data.domain.Pageable pageable)

    long deleteByFollowerUserIdAndSellerUserId(Long followerUserId, Long sellerUserId)

    /** Bulk-remove every follow row for one user in a single DELETE.
     *  Used by the "Unfollow all" bulk button and by the GDPR deletion
     *  flow. Returns the count removed so the caller can surface it. */
    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM SellerFollow f WHERE f.followerUserId = :uid")
    int deleteByFollower(@Param("uid") Long followerUserId)

    /** Bulk-remove every follow row targeting the given seller — used
     *  by the GDPR finalizeDeletion flow so follower rows pointing at
     *  a deleted seller account don't linger as dead FKs in the
     *  follower's "Following" list. */
    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM SellerFollow f WHERE f.sellerUserId = :uid")
    int deleteBySeller(@Param("uid") Long sellerUserId)

    /** Bulk-flip `notifications_muted` to a given value for every follow
     *  row the user owns. Returns the count affected — used by the
     *  "Mute all" / "Unmute all" shortcut on the Profile → Personal
     *  Following row so a user can silence fan-out without losing any
     *  of their follow relationships. */
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE SellerFollow f SET f.notificationsMuted = :muted WHERE f.followerUserId = :uid")
    int updateMutedForFollower(@Param("uid") Long followerUserId,
                                @Param("muted") boolean muted)
}
