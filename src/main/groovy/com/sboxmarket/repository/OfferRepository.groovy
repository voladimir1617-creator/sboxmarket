package com.sboxmarket.repository

import com.sboxmarket.model.Offer
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
}
