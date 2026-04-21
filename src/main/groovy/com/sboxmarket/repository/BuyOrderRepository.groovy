package com.sboxmarket.repository

import com.sboxmarket.model.BuyOrder
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface BuyOrderRepository extends JpaRepository<BuyOrder, Long> {

    @Query("SELECT b FROM BuyOrder b WHERE b.buyerUserId = :uid ORDER BY b.createdAt DESC")
    List<BuyOrder> findByBuyer(@Param("uid") Long uid)

    /** Paged variant — caps the Profile → Buy Orders tab so a power-
     *  buyer with thousands of CANCELLED/FILLED/EXPIRED orders in
     *  history doesn't force the server to serialise them all on
     *  every tab open. Display-only; the ACTIVE cap (MAX_ACTIVE_ORDERS_PER_BUYER
     *  = 200, batch 1000) is enforced separately at create time. */
    @Query("SELECT b FROM BuyOrder b WHERE b.buyerUserId = :uid ORDER BY b.createdAt DESC")
    List<BuyOrder> findByBuyerPaged(@Param("uid") Long uid,
                                     org.springframework.data.domain.Pageable pageable)

    /** Total buy-order count for the buyer — feeds X-Total-Count. */
    @Query("SELECT COUNT(b) FROM BuyOrder b WHERE b.buyerUserId = :uid")
    long countByBuyer(@Param("uid") Long uid)

    @Query("SELECT COUNT(b) FROM BuyOrder b WHERE b.buyerUserId = :uid AND b.status = 'ACTIVE'")
    long countActiveByBuyer(@Param("uid") Long uid)

    @Query("SELECT b FROM BuyOrder b WHERE b.status = 'ACTIVE' ORDER BY b.maxPrice DESC")
    List<BuyOrder> findAllActive()

    /** Top-N active buy orders by maxPrice — drives the homepage "Top
     *  buy orders" rail (batch 369). Banned buyers filtered out so the
     *  social-proof signal isn't inflated by accounts that can't
     *  transact. Returns typed rows so the controller can render
     *  item-name + avatar + buyer handle alongside the price. */
    @Query("""
        SELECT b FROM BuyOrder b
        WHERE b.status = 'ACTIVE'
          AND b.itemId IS NOT NULL
          AND EXISTS (SELECT u FROM SteamUser u
              WHERE u.id = b.buyerUserId AND (u.banned IS NULL OR u.banned = false))
        ORDER BY b.maxPrice DESC
    """)
    List<BuyOrder> findTopActive(org.springframework.data.domain.Pageable page)

    /** Count of standing buy orders pinned to a specific item id. Drives
     *  the "N buyers want this" chip on the item detail modal —
     *  social-proof signal that there's active demand. Joins
     *  SteamUser + filters banned buyers so the public demand signal
     *  isn't inflated by accounts that can't actually transact
     *  (batch 317, matches the `findMatching` filter). */
    @Query("""
        SELECT COUNT(b) FROM BuyOrder b, SteamUser u
        WHERE b.status = 'ACTIVE'
          AND b.itemId = :itemId
          AND b.buyerUserId = u.id
          AND (u.banned IS NULL OR u.banned = false)
    """)
    long countActiveForItem(@Param("itemId") Long itemId)

    /** Top-of-book: highest maxPrice among ACTIVE buy orders pinned to
     *  this item. Powers the "Best bid \$X" chip on item detail — a
     *  sell-side signal showing the cheapest way to auto-match.
     *  Banned buyers filtered so a locked account's ceiling doesn't
     *  set a misleading anchor. */
    @Query("""
        SELECT COALESCE(MAX(b.maxPrice), 0) FROM BuyOrder b, SteamUser u
        WHERE b.status = 'ACTIVE'
          AND b.quantity > 0
          AND b.itemId = :itemId
          AND b.buyerUserId = u.id
          AND (u.banned IS NULL OR u.banned = false)
    """)
    BigDecimal findBestBidForItem(@Param("itemId") Long itemId)

    /** Bulk variant of the count + best-bid pair (batch 415). Returns
     *  `[itemId, count, bestMax]` rows for a list of item ids so the
     *  MyStall page can render a "N want · best $X" chip per listing
     *  without N+1 round-trips. Banned buyers filtered on the join. */
    @Query("""
        SELECT b.itemId, COUNT(b), COALESCE(MAX(b.maxPrice), 0)
          FROM BuyOrder b, SteamUser u
         WHERE b.status = 'ACTIVE'
           AND b.quantity > 0
           AND b.itemId IN :itemIds
           AND b.buyerUserId = u.id
           AND (u.banned IS NULL OR u.banned = false)
         GROUP BY b.itemId
    """)
    List<Object[]> countAndBestBidByItemIds(@Param('itemIds') List<Long> itemIds)

    /** Matches candidates for `BuyOrderService.tryMatch`. Joins the
     *  buyer's SteamUser row so banned buyers are filtered at scan
     *  time — without this, every new listing still walks the banned
     *  user's ACTIVE orders, does the wallet lookup, then `banGuard`
     *  rejects the actual purchase, but the per-iteration waste
     *  accumulates. A later unban re-includes the buy orders in
     *  future scans (rows stay ACTIVE — the filter is non-destructive). */
    @Query("""
        SELECT b FROM BuyOrder b, SteamUser u
        WHERE b.status = 'ACTIVE'
          AND b.quantity > 0
          AND b.maxPrice >= :price
          AND (b.itemId   IS NULL OR b.itemId   = :itemId)
          AND (b.category IS NULL OR b.category = :category)
          AND (b.rarity   IS NULL OR b.rarity   = :rarity)
          AND b.buyerUserId = u.id
          AND (u.banned IS NULL OR u.banned = false)
        ORDER BY b.maxPrice DESC, b.createdAt ASC
    """)
    List<BuyOrder> findMatching(
        @Param("itemId")   Long itemId,
        @Param("category") String category,
        @Param("rarity")   String rarity,
        @Param("price")    BigDecimal price,
        org.springframework.data.domain.Pageable page
    )

    /** How many other ACTIVE buy orders pinned to the same item would
     *  match a seller's listing before this one. Mirrors the ordering
     *  the matching engine uses (`maxPrice DESC, createdAt ASC`) so
     *  the returned value is exactly "your queue position minus 1".
     *  Filters banned buyers to match `findMatching` — otherwise the
     *  "you'd be #N in queue" projection counts ghost positions that
     *  will never fire. */
    @Query("""
        SELECT COUNT(b) FROM BuyOrder b, SteamUser u
        WHERE b.status = 'ACTIVE'
          AND b.quantity > 0
          AND b.itemId = :itemId
          AND b.buyerUserId = u.id
          AND (u.banned IS NULL OR u.banned = false)
          AND (
            b.maxPrice > :maxPrice
            OR (b.maxPrice = :maxPrice AND b.createdAt < :createdAt)
          )
    """)
    long countAheadInQueue(
        @Param("itemId")    Long itemId,
        @Param("maxPrice")  BigDecimal maxPrice,
        @Param("createdAt") Long createdAt
    )

    /** ACTIVE buy orders idle since the cutoff — drives the 30-day
     *  auto-expire sweep (batch 286). Sorted by updatedAt ASC so the
     *  oldest get processed first if the candidate set is large. */
    @Query("""
        SELECT b FROM BuyOrder b
        WHERE b.status = 'ACTIVE'
          AND b.updatedAt <= :cutoff
        ORDER BY b.updatedAt ASC
    """)
    List<BuyOrder> findStaleActive(@Param('cutoff') Long cutoff)

    /** Top-N active buy orders for a specific item (batch 639). Drives
     *  the CSFloat-style "Buy Orders" table on the item detail modal:
     *  buyers see who's willing to pay what, and sellers can size their
     *  asking price to the demand curve. Banned buyers filtered at
     *  scan-time — same policy as `findMatching` / `findBestBidForItem`.
     *  Sort matches the matching engine (`maxPrice DESC, createdAt ASC`)
     *  so the top row is literally the next to fill on a new listing. */
    @Query("""
        SELECT b FROM BuyOrder b, SteamUser u
        WHERE b.status = 'ACTIVE'
          AND b.quantity > 0
          AND b.itemId = :itemId
          AND b.buyerUserId = u.id
          AND (u.banned IS NULL OR u.banned = false)
        ORDER BY b.maxPrice DESC, b.createdAt ASC
    """)
    List<BuyOrder> findActiveForItem(@Param("itemId") Long itemId, org.springframework.data.domain.Pageable page)
}
