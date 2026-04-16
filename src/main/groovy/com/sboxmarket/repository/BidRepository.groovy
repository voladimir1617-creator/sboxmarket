package com.sboxmarket.repository

import com.sboxmarket.model.Bid
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface BidRepository extends JpaRepository<Bid, Long> {

    @Query("SELECT b FROM Bid b WHERE b.listingId = :id ORDER BY b.amount DESC, b.createdAt ASC")
    List<Bid> findByListing(@Param("id") Long listingId)

    @Query("SELECT b FROM Bid b WHERE b.bidderUserId = :uid ORDER BY b.createdAt DESC")
    List<Bid> findByBidder(@Param("uid") Long uid)

    @Query("SELECT b FROM Bid b WHERE b.bidderUserId = :uid AND b.kind = 'AUTO' AND b.status = 'WINNING' ORDER BY b.createdAt DESC")
    List<Bid> findActiveAutoBidsForUser(@Param("uid") Long uid)
}
