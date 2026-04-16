package com.sboxmarket.repository

import com.sboxmarket.model.Listing
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

/**
 * NOTE: every list query below uses `JOIN FETCH l.item` so Hibernate pulls
 * the listing and its item in a single SQL round-trip. Without the join
 * fetch, the EAGER @ManyToOne on `Listing.item` triggers one extra SELECT
 * per row — classic N+1. A 100-row listings page was issuing 101 queries
 * under load; now it issues 1.
 */
@Repository
interface ListingRepository extends JpaRepository<Listing, Long> {

    List<Listing> findByItemIdAndStatus(Long itemId, String status)

    List<Listing> findByStatus(String status)

    @Query("SELECT l FROM Listing l JOIN FETCH l.item WHERE l.status = 'ACTIVE' ORDER BY l.price ASC")
    List<Listing> findActiveOrderByPrice()

    @Query("SELECT l FROM Listing l JOIN FETCH l.item WHERE l.status = 'ACTIVE' ORDER BY l.listedAt DESC")
    List<Listing> findActiveOrderByNewest()

    @Query("SELECT l FROM Listing l JOIN FETCH l.item WHERE l.item.id = :itemId AND l.status = 'ACTIVE' ORDER BY l.price ASC")
    List<Listing> findCheapestForItem(@Param("itemId") Long itemId)

    /** SQL aggregate for `ListingService.updateItemFloorPrice` — fires on
     *  every listing mutation (buy, cancel, save, createListing), so the
     *  old `findCheapestForItem(id).first().price` path was pulling every
     *  active row for the item just to read one number. */
    @Query("SELECT MIN(l.price) FROM Listing l WHERE l.item.id = :itemId AND l.status = 'ACTIVE'")
    BigDecimal minPriceForItem(@Param("itemId") Long itemId)

    @Query("SELECT l FROM Listing l JOIN FETCH l.item WHERE l.status = 'ACTIVE' AND l.price BETWEEN :min AND :max ORDER BY l.price ASC")
    List<Listing> findActiveByPriceRange(@Param("min") BigDecimal min, @Param("max") BigDecimal max)

    @Query("SELECT l FROM Listing l JOIN FETCH l.item WHERE l.status = 'ACTIVE' AND LOWER(l.item.name) LIKE LOWER(CONCAT('%', :q, '%')) ORDER BY l.price ASC")
    List<Listing> searchActiveByName(@Param("q") String query)

    @Query("SELECT l FROM Listing l JOIN FETCH l.item WHERE l.status = 'ACTIVE' AND l.item.category = :category ORDER BY l.price ASC")
    List<Listing> findActiveByCategory(@Param("category") String category)

    @Query("SELECT l FROM Listing l JOIN FETCH l.item WHERE l.status = 'ACTIVE' AND l.item.rarity = :rarity ORDER BY l.price ASC")
    List<Listing> findActiveByRarity(@Param("rarity") String rarity)

    @Query("SELECT COUNT(l) FROM Listing l WHERE l.status = 'ACTIVE'")
    Long countActive()

    @Query("SELECT SUM(l.price) FROM Listing l WHERE l.status = 'SOLD' AND l.soldAt > :since")
    BigDecimal sumVolumeAfter(@Param("since") Long since)

    @Query("SELECT l FROM Listing l JOIN FETCH l.item WHERE l.sellerUserId = :uid AND l.status = 'ACTIVE' ORDER BY l.listedAt DESC")
    List<Listing> findActiveBySeller(@Param("uid") Long uid)

    /** Public stall view — same as findActiveBySeller but excludes
     *  stall-privacy / Away-mode rows. Moved from a Groovy post-filter
     *  in ListingController.publicStall into the JPQL WHERE so the
     *  hidden column is evaluated once in SQL instead of per-row in
     *  Java. Null-hidden legacy rows count as visible. */
    @Query("""
        SELECT l FROM Listing l JOIN FETCH l.item
        WHERE l.sellerUserId = :uid
          AND l.status = 'ACTIVE'
          AND (l.hidden IS NULL OR l.hidden = false)
        ORDER BY l.listedAt DESC
    """)
    List<Listing> findActiveVisibleBySeller(@Param("uid") Long uid)

    @Query("SELECT l FROM Listing l JOIN FETCH l.item WHERE l.buyerUserId = :uid AND l.status = 'SOLD' ORDER BY l.soldAt DESC")
    List<Listing> findOwnedBy(@Param("uid") Long uid)

    /**
     * Public marketplace query — pushes every filter the old in-memory
     * Groovy chain used to do into one indexed JPQL query:
     *   - Only ACTIVE status
     *   - Seller hasn't flipped the listing hidden (stall-privacy /
     *     Away mode). `hidden` defaults to false; the IS NULL branch
     *     covers legacy rows written before the column existed.
     *   - Name substring, category, rarity, and price-range filters
     *     are all optional — empty-string / null sentinels keep the
     *     planner happy (see `ItemRepository.searchCatalogue` for the
     *     Postgres bytea null-type-inference trap that forced the
     *     empty-string pattern).
     *
     * Caller applies the final Groovy sort on the filtered result
     * because JPQL can't easily drive a switchable ORDER BY with
     * JOIN FETCH. The filtered page is small so that's O(k log k).
     */
    @Query("""
        SELECT l FROM Listing l JOIN FETCH l.item
        WHERE l.status = 'ACTIVE'
          AND (l.hidden IS NULL OR l.hidden = false)
          AND (:q        = '' OR LOWER(l.item.name) LIKE LOWER(CONCAT('%', :q, '%')))
          AND (:category = '' OR l.item.category = :category)
          AND (:rarity   = '' OR l.item.rarity   = :rarity)
          AND (:minPrice IS NULL OR l.price >= :minPrice)
          AND (:maxPrice IS NULL OR l.price <= :maxPrice)
        ORDER BY l.price ASC
    """)
    List<Listing> findActivePublic(
        @Param("q") String q,
        @Param("category") String category,
        @Param("rarity") String rarity,
        @Param("minPrice") BigDecimal minPrice,
        @Param("maxPrice") BigDecimal maxPrice
    )

    /** Minimum ACTIVE listing price — used by `getMarketStats` to render
     *  the homepage floor without pulling every active row into memory. */
    @Query("SELECT MIN(l.price) FROM Listing l WHERE l.status = 'ACTIVE' AND (l.hidden IS NULL OR l.hidden = false)")
    BigDecimal findMinActivePrice()

    /** Auctions that have passed their expiresAt and need settling.
     *  Previously `BidService.sweepExpired` pulled every ACTIVE listing
     *  and filtered in Groovy on every 30-second tick. This narrows
     *  the query to just the rows the sweeper actually needs. */
    @Query("""
        SELECT l FROM Listing l JOIN FETCH l.item
        WHERE l.status = 'ACTIVE'
          AND l.listingType = 'AUCTION'
          AND l.expiresAt IS NOT NULL
          AND l.expiresAt <= :now
    """)
    List<Listing> findExpiredAuctions(@Param("now") Long now)

    /** Rows flagged as simulator fixtures — `AdminSimulatorService.clearSimulated`
     *  and `countSimulated` used to pull every listing and filter in Groovy.
     *  Pushing the tag filters into SQL keeps the admin sim tool fast even
     *  as the real marketplace grows around it. */
    @Query("""
        SELECT l FROM Listing l
        WHERE l.sellerName LIKE 'SIM · %'
           OR l.description LIKE '[SIMULATED]%'
    """)
    List<Listing> findSimulated()

    @Query("""
        SELECT COUNT(l) FROM Listing l
        WHERE l.sellerName LIKE 'SIM · %'
           OR l.description LIKE '[SIMULATED]%'
    """)
    long countSimulated()
}
