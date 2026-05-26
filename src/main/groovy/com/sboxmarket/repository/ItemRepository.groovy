package com.sboxmarket.repository

import com.sboxmarket.model.Item
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface ItemRepository extends JpaRepository<Item, Long> {

    List<Item> findByCategory(String category)

    List<Item> findByRarity(String rarity)

    List<Item> findByCategoryAndRarity(String category, String rarity)

    @Query("SELECT i FROM Item i WHERE LOWER(i.name) LIKE LOWER(CONCAT('%', :q, '%')) ESCAPE '\\'")
    List<Item> searchByName(@Param("q") String query)

    /** Exact-match lookup for mapping a Steam inventory item name to our
     *  internal catalogue row. Uses the `idx_items_name` functional index
     *  on `LOWER(name)` from V1 baseline — O(log N) instead of the O(N)
     *  full-table scan the old `findAll().find { equalsIgnoreCase }` did. */
    @Query("SELECT i FROM Item i WHERE LOWER(i.name) = LOWER(:name)")
    Item findByNameIgnoreCase(@Param("name") String name)

    /** Bulk version of `findByNameIgnoreCase` — used by
     *  `SteamInventoryController.inventory` to map every item in a user's
     *  Steam inventory to its catalogue row in a single indexed query
     *  instead of walking the whole catalogue with `findAll()` first. */
    @Query("SELECT i FROM Item i WHERE LOWER(i.name) IN :names")
    List<Item> findByNamesLowerIn(@Param("names") Collection<String> namesLower)

    /** Cheapest catalogue item in a given category whose lowestPrice fits
     *  under a budget ceiling. Used by `LoadoutService.autoGenerate` —
     *  the old path walked `findAll()` per slot which scaled linearly
     *  with the whole catalogue for every auto-fill. Empty string in
     *  `category` means "any category" (Wild-card slot). Caller passes
     *  `PageRequest.of(0, 1)` since we only need the cheapest row. */
    @Query("""
        SELECT i FROM Item i
        WHERE (:category = '' OR i.category = :category)
          AND i.lowestPrice IS NOT NULL
          AND i.lowestPrice > 0
          AND i.lowestPrice <= :budget
        ORDER BY i.lowestPrice ASC
    """)
    List<Item> findCheapestInBudget(
        @Param("category") String category,
        @Param("budget") BigDecimal budget,
        Pageable page
    )

    @Query("SELECT i FROM Item i WHERE i.lowestPrice BETWEEN :min AND :max")
    List<Item> findByPriceRange(@Param("min") BigDecimal min, @Param("max") BigDecimal max)

    @Query("SELECT i FROM Item i ORDER BY i.lowestPrice DESC")
    List<Item> findAllOrderByPriceDesc()

    @Query("SELECT i FROM Item i ORDER BY i.totalSold DESC")
    List<Item> findAllOrderByPopularity()

    @Query("SELECT i FROM Item i ORDER BY i.supply ASC")
    List<Item> findAllOrderByRarity()

    /** Sitemap feed — item rows for the /sitemap.xml generator. Paged
     *  so a growing catalogue doesn't force `findAll()` into a full-
     *  table hydration just to `.take(10_000)`. Sorted deterministically
     *  by `id ASC` so a future sitemap-index split can page by offset
     *  without duplicating URLs across shards. */
    @Query("SELECT i FROM Item i ORDER BY i.id ASC")
    List<Item> findAllForSitemap(org.springframework.data.domain.Pageable page)

    /**
     * Filtered-and-paginated catalogue query for `/api/database`. Pushes
     * name/category/rarity filters and sort direction down into a single
     * JPQL query so Postgres can use the query planner + `LIMIT/OFFSET`
     * instead of the old `findAll().findAll { … }` full-table scan plus
     * Groovy `.drop().take()` slicing.
     *
     * Sentinel-empty-string convention: callers pass `''` to mean "no
     * filter". Binding a real null here would make Postgres infer the
     * parameter type as `bytea` and blow up with
     * `function lower(bytea) does not exist` — using empty strings keeps
     * the parameter type unambiguously TEXT. The controller whitelists
     * the sort value; unknown values fall back to supply ASC.
     */
    @Query("""
        SELECT i FROM Item i
        WHERE (:q        = '' OR LOWER(i.name) LIKE LOWER(CONCAT('%', :q, '%')) ESCAPE '\\')
          AND (:category = '' OR i.category = :category)
          AND (:rarity   = '' OR i.rarity   = :rarity)
          AND (:minPrice IS NULL OR i.lowestPrice >= :minPrice)
          AND (:maxPrice IS NULL OR i.lowestPrice <= :maxPrice)
    """)
    Page<Item> searchCatalogue(
        @Param("q") String q,
        @Param("category") String category,
        @Param("rarity") String rarity,
        @Param("minPrice") BigDecimal minPrice,
        @Param("maxPrice") BigDecimal maxPrice,
        Pageable pageable
    )

    /** Variant that also filters to items with at least one active,
     *  non-hidden listing RIGHT NOW. Used by the Database page's
     *  "Listed only" toggle so a browser can hide catalogue rows with
     *  zero active listings. Uses an EXISTS subquery against the Listing
     *  table so the filter stays correct even if the denormalized
     *  Item.isListed flag drifts (see ListingService.updateItemFloorPrice
     *  for the flag maintenance). Sentinel empty-string filters match
     *  the main searchCatalogue query. */
    @Query("""
        SELECT i FROM Item i
        WHERE (:q        = '' OR LOWER(i.name) LIKE LOWER(CONCAT('%', :q, '%')) ESCAPE '\\')
          AND (:category = '' OR i.category = :category)
          AND (:rarity   = '' OR i.rarity   = :rarity)
          AND (:minPrice IS NULL OR i.lowestPrice >= :minPrice)
          AND (:maxPrice IS NULL OR i.lowestPrice <= :maxPrice)
          AND EXISTS (
              SELECT 1 FROM Listing l
              WHERE l.item = i
                AND l.status = 'ACTIVE'
                AND (l.hidden IS NULL OR l.hidden = false)
          )
    """)
    Page<Item> searchListedCatalogue(
        @Param("q") String q,
        @Param("category") String category,
        @Param("rarity") String rarity,
        @Param("minPrice") BigDecimal minPrice,
        @Param("maxPrice") BigDecimal maxPrice,
        Pageable pageable
    )

    /**
     * "Similar items" feed for the item detail view. Narrows the
     * candidate pool down to rows that share the subject's category OR
     * rarity, excluding the subject itself, ordered by
     *   1. `isListed DESC` — items with active listings first, so the
     *      rail never lures a buyer into a dead-end item detail (bug
     *      surfaced while walking empty-seed state).
     *   2. absolute price distance from the subject — buyers anchor on
     *      the current item's price; the closest-priced sibling feels
     *      like a like-for-like alternative.
     * The index plan stays the same (`idx_items_category` / `rarity`);
     * `is_listed` is a BOOLEAN column so DESC comes out in the order
     * we want (true > false).
     */
    @Query("""
        SELECT i FROM Item i
        WHERE i.id <> :selfId
          AND (i.category = :category OR i.rarity = :rarity)
        ORDER BY i.isListed DESC, ABS(COALESCE(i.lowestPrice, 0) - :basePrice) ASC
    """)
    List<Item> findSimilar(
        @Param("selfId") Long selfId,
        @Param("category") String category,
        @Param("rarity") String rarity,
        @Param("basePrice") BigDecimal basePrice,
        Pageable pageable
    )

    /** Race-safe totalSold increment. Used on every real platform sale
     *  (BUY_NOW + auction settle) so the Database page's "Most Traded"
     *  sort reflects actual platform activity instead of the external
     *  SCMM subscription count that the scheduled sync used to write.
     *  Single UPDATE means two concurrent sales can't clobber each other
     *  the way a read-modify-write would (no @Version on Item). */
    @Modifying
    @Query("UPDATE Item i SET i.totalSold = COALESCE(i.totalSold, 0) + 1 WHERE i.id = :itemId")
    int incrementTotalSold(@Param("itemId") Long itemId)

    /** Race-safe reverse of incrementTotalSold — fired when a sale is
     *  unwound (trade cancelled, dispute refunded, seller banned-out).
     *  Clamps at zero with GREATEST so a missed-increment can't push the
     *  counter negative and pollute "Most Traded" with bogus rows. */
    @Modifying
    @Query("UPDATE Item i SET i.totalSold = GREATEST(COALESCE(i.totalSold, 0) - 1, 0) WHERE i.id = :itemId")
    int decrementTotalSold(@Param("itemId") Long itemId)

    /** Race-safe per-item view counter bump (batch 409). Called from
     *  the public GET /api/items/{id} path outside any ambient @Transactional
     *  scope (the controller method isn't wrapped), so this repo method
     *  is self-transactional. Single UPDATE so two concurrent viewers
     *  can't clobber each other's counter. */
    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE Item i SET i.viewCount = COALESCE(i.viewCount, 0) + 1 WHERE i.id = :itemId")
    int incrementViewCount(@Param("itemId") Long itemId)

    /** Top-N item ids by lifetime viewCount (batch 412). Drives the
     *  homepage "Most viewed" rail — service layer joins to the cheapest
     *  active listing per item. Filters out zero-view items so a sparse
     *  catalogue doesn't surface 80 ties. Returns [itemId, viewCount]
     *  pairs so callers can optionally surface the count. */
    @Query("""
        SELECT i.id, i.viewCount FROM Item i
         WHERE i.viewCount > 0
         ORDER BY i.viewCount DESC, i.id ASC
    """)
    List<Object[]> findTopViewedItemIds(org.springframework.data.domain.Pageable page)

    /** Catalogue stats — single aggregate pass replacing the legacy
     *  `findAll().count{...}` full-table scan in `ItemService.getStats()`
     *  (batch 618). One index-only query returning `[totalItems,
     *  limitedCount, floorPrice, highestPrice]`. Category breakdown is
     *  a separate GROUP BY — see {@link #countByCategory}.
     *
     *  Batch 644: MIN/MAX wrapped in a CASE that excludes rows with
     *  `lowestPrice <= 0`. A single placeholder/dev-seeded $0 item was
     *  poisoning the MIN and making `/api/items/stats` report
     *  `floorPrice: 0.0` even when the marketplace floor was actually
     *  $1.08. MAX stays filtered too so a rogue negative/null doesn't
     *  propagate either (cheap defence).
     */
    @Query("""
        SELECT COUNT(i),
               SUM(CASE WHEN i.rarity = 'Limited' THEN 1 ELSE 0 END),
               MIN(CASE WHEN i.lowestPrice > 0 THEN i.lowestPrice ELSE NULL END),
               MAX(CASE WHEN i.lowestPrice > 0 THEN i.lowestPrice ELSE NULL END)
        FROM Item i
    """)
    List<Object[]> catalogueSummary()

    /** GROUP BY aggregate for the homepage stats strip's category
     *  breakdown (batch 618). Returns `[category, count]` rows. */
    @Query("""
        SELECT i.category, COUNT(i)
        FROM Item i
        GROUP BY i.category
    """)
    List<Object[]> countByCategory()
}
