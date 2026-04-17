package com.sboxmarket.repository

import com.sboxmarket.model.Offer
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface OfferRepository extends JpaRepository<Offer, Long> {

    /** Offers the user is making (outgoing). */
    @Query("SELECT o FROM Offer o WHERE o.buyerUserId = :uid ORDER BY o.createdAt DESC")
    List<Offer> findByBuyer(@Param("uid") Long buyerUserId)

    /** Offers the user is receiving (incoming on their listings). */
    @Query("SELECT o FROM Offer o WHERE o.sellerUserId = :uid ORDER BY o.createdAt DESC")
    List<Offer> findBySeller(@Param("uid") Long sellerUserId)

    @Query("SELECT o FROM Offer o WHERE o.listingId = :lid AND o.status = 'PENDING'")
    List<Offer> findPendingForListing(@Param("lid") Long listingId)

    /** Every offer on a listing, newest first. Uses the
     *  `idx_offers_listing` composite index so it stays O(log N). */
    @Query("SELECT o FROM Offer o WHERE o.listingId = :lid ORDER BY o.createdAt DESC")
    List<Offer> findByListingId(@Param("lid") Long listingId)

    @Query("SELECT COUNT(o) FROM Offer o WHERE o.buyerUserId = :uid AND o.status = 'PENDING'")
    long countPendingByBuyer(@Param("uid") Long buyerUserId)

    /** Incoming-offer count for a seller — drives the nav badge so sellers
     *  see "3 offers waiting" without opening the Offers tab. PENDING only
     *  (accepted/rejected/countered are terminal from the seller's view). */
    @Query("SELECT COUNT(o) FROM Offer o WHERE o.sellerUserId = :uid AND o.status = 'PENDING'")
    long countPendingBySeller(@Param("uid") Long sellerUserId)

    /** Stale PENDING offers — drives the auto-decline sweeper. `updatedAt`
     *  captures the last seller/buyer interaction (counter, partial reply,
     *  etc.), so the 7-day window is "last meaningful activity", not
     *  "time since first created". */
    @Query("SELECT o FROM Offer o WHERE o.status = 'PENDING' AND o.updatedAt <= :cutoff")
    List<Offer> findStalePending(@Param("cutoff") Long cutoff)

    /** Root buyer offers the given seller has resolved (accepted,
     *  rejected, or countered). The delta `updatedAt - createdAt` is
     *  the seller's response time. Excludes PENDING (not yet answered),
     *  CANCELLED (buyer withdrew — nothing to measure), and EXPIRED
     *  (sweeper closed it — would skew the stat toward the 7-day cap).
     *  Pageable so the service can bound the window (most recent N). */
    @Query("""
        SELECT o FROM Offer o
        WHERE o.sellerUserId = :uid
          AND o.author = 'USER'
          AND o.status IN ('ACCEPTED','REJECTED','COUNTERED')
        ORDER BY o.updatedAt DESC
    """)
    List<Offer> findRecentSellerResponses(@Param("uid") Long sellerUserId, Pageable pageable)

    /** Offers a seller engaged with — union of resolved responses
     *  (accept / reject / counter) and expired rows (the seller never
     *  answered within the auto-decline window). Denominator for the
     *  public "response rate" chip. CANCELLED is excluded because that
     *  is a buyer-side withdrawal, not a seller-controlled outcome. */
    @Query("""
        SELECT COUNT(o) FROM Offer o
        WHERE o.sellerUserId = :uid
          AND o.author = 'USER'
          AND o.status IN ('ACCEPTED','REJECTED','COUNTERED','EXPIRED')
    """)
    long countSellerEngagedTotal(@Param("uid") Long sellerUserId)

    /** Subset of {@link #countSellerEngagedTotal} that the seller
     *  actively resolved. Numerator for the response rate chip. */
    @Query("""
        SELECT COUNT(o) FROM Offer o
        WHERE o.sellerUserId = :uid
          AND o.author = 'USER'
          AND o.status IN ('ACCEPTED','REJECTED','COUNTERED')
    """)
    long countSellerResponded(@Param("uid") Long sellerUserId)
}
