package com.sboxmarket.repository

import com.sboxmarket.model.Item
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface ItemRepository extends JpaRepository<Item, Long> {

    List<Item> findByCategory(String category)

    List<Item> findByRarity(String rarity)

    List<Item> findByCategoryAndRarity(String category, String rarity)

    @Query("SELECT i FROM Item i WHERE LOWER(i.name) LIKE LOWER(CONCAT('%', :q, '%'))")
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
        WHERE (:q        = '' OR LOWER(i.name) LIKE LOWER(CONCAT('%', :q, '%')))
          AND (:category = '' OR i.category = :category)
          AND (:rarity   = '' OR i.rarity   = :rarity)
    """)
    Page<Item> searchCatalogue(
        @Param("q") String q,
        @Param("category") String category,
        @Param("rarity") String rarity,
        Pageable pageable
    )

    /**
     * "Similar items" feed for the item detail view. Narrows the
     * candidate pool down to rows that share the subject's category OR
     * rarity, excluding the subject itself, ordered by the absolute
     * distance of their `lowestPrice` from the subject's price. The
     * distance-sort runs in SQL via a correlated subquery so the query
     * planner can use the `idx_items_category` / rarity index instead
     * of loading the whole catalogue and sorting in Groovy (bug #22).
     */
    @Query("""
        SELECT i FROM Item i
        WHERE i.id <> :selfId
          AND (i.category = :category OR i.rarity = :rarity)
        ORDER BY ABS(COALESCE(i.lowestPrice, 0) - :basePrice) ASC
    """)
    List<Item> findSimilar(
        @Param("selfId") Long selfId,
        @Param("category") String category,
        @Param("rarity") String rarity,
        @Param("basePrice") BigDecimal basePrice,
        Pageable pageable
    )
}
