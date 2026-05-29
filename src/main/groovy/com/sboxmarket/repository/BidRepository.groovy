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

    /** Paged companion — a hot auction can attract hundreds of bid rows
     *  (snipe-bot territory); the bid-history UI only needs the top N.
     *  Sort order matches the unbounded variant. */
    @Query("SELECT b FROM Bid b WHERE b.listingId = :id ORDER BY b.amount DESC, b.createdAt ASC")
    List<Bid> findByListing(@Param("id") Long listingId,
                            org.springframework.data.domain.Pageable pageable)

    @Query("SELECT b FROM Bid b WHERE b.bidderUserId = :uid ORDER BY b.createdAt DESC")
    List<Bid> findByBidder(@Param("uid") Long uid)

    /** Paged companion — a power-bidder accumulates thousands of historical
     *  bids across won/lost/cancelled auctions; the cap prevents the
     *  Profile → Bids → All tab from hydrating the full history. */
    @Query("SELECT b FROM Bid b WHERE b.bidderUserId = :uid ORDER BY b.createdAt DESC")
    List<Bid> findByBidder(@Param("uid") Long uid,
                           org.springframework.data.domain.Pageable pageable)

    @Query("SELECT b FROM Bid b WHERE b.bidderUserId = :uid AND b.kind = 'AUTO' AND b.status = 'WINNING' ORDER BY b.createdAt DESC")
    List<Bid> findActiveAutoBidsForUser(@Param("uid") Long uid)

    /**
     * Listing-scoped participant probe used by BidService.historyFor to
     * decide between the participant (real identities, scrubbed max caps)
     * and non-participant (handle-aliased) views.
     *
     * The redaction gate used to lean on `all.any { it.bidderUserId == uid }`
     * over the HISTORY_PAGE_SIZE-capped page. A hot auction with > 200 bids
     * pushes the lowest-amount rows off the page, so a legitimate bidder
     * whose only bids were outbid and now sit below the page cutoff would
     * be misclassified as a third party — their OWN identity gets scrubbed
     * to "Bidder #N" in their own bid log and their own auto-cap
     * disappears, even though they are entitled to see both. Probing the
     * DB directly is O(1) and unaffected by the page cap.
     */
    @Query("SELECT COUNT(b) FROM Bid b WHERE b.listingId = :id AND b.bidderUserId = :uid")
    long countByListingAndBidder(@Param("id") Long listingId, @Param("uid") Long uid)

    /** Count-only companion for the /profile hero strip. Avoids
     *  hydrating every Bid row just to call .size() on the list. */
    @Query("SELECT COUNT(b) FROM Bid b WHERE b.bidderUserId = :uid AND b.kind = 'AUTO' AND b.status = 'WINNING'")
    long countActiveAutoBidsForUser(@Param("uid") Long uid)

    /** Every bid the user currently has live — WINNING (top bid) or OUTBID
     *  (still in the auction, just not at the top). Drives the "Active Bids"
     *  tab so users can see at a glance which auctions they're still in
     *  without having to remember. Closed auctions (WON, LOST, CANCELLED)
     *  are excluded. */
    @Query("SELECT b FROM Bid b WHERE b.bidderUserId = :uid " +
           "AND (b.status = 'WINNING' OR b.status = 'OUTBID') " +
           "ORDER BY b.createdAt DESC")
    List<Bid> findLiveBidsForUser(@Param("uid") Long uid)

    /** Paged companion — bounded by the user's live-auction footprint
     *  (small in practice) but a snipe-bot account can amass many live
     *  bids simultaneously; the cap keeps the Active-Bids tab render at
     *  O(pageSize) regardless. */
    @Query("SELECT b FROM Bid b WHERE b.bidderUserId = :uid " +
           "AND (b.status = 'WINNING' OR b.status = 'OUTBID') " +
           "ORDER BY b.createdAt DESC")
    List<Bid> findLiveBidsForUser(@Param("uid") Long uid,
                                  org.springframework.data.domain.Pageable pageable)

    /** Past bids — WON, LOST, CANCELLED. Drives the Profile → Bids
     *  "Past" sub-tab (batch 361) so users can review their auction
     *  history after the live set closes. Capped via Pageable on the
     *  caller — no cap in the query itself so admin / CSR use can
     *  walk the full history when needed. Newest-first. */
    @Query("SELECT b FROM Bid b WHERE b.bidderUserId = :uid " +
           "AND b.status IN ('WON', 'LOST', 'CANCELLED') " +
           "ORDER BY b.createdAt DESC")
    List<Bid> findPastBidsForUser(@Param("uid") Long uid,
                                  org.springframework.data.domain.Pageable page)
}
