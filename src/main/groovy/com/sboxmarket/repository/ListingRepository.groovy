package com.sboxmarket.repository

import com.sboxmarket.model.Listing
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
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

    // Sales queries below exclude rows where buyerUserId == sellerUserId.
    // Cancelling a listing, an auction ending unsold and a cancelled trade all
    // hand the item back by setting status SOLD with the seller as "buyer",
    // so without the filter a seller could list at $99,999, cancel, and show
    // a $99,999 "sale" in public price history and pad their sold count.
    // Inventory queries (buyerUserId = :uid) keep those rows on purpose.

    List<Listing> findByItemIdAndStatus(Long itemId, String status)

    List<Listing> findByStatus(String status)

    /**
     * Double-list guard: does this seller already have a LIVE listing for this
     * exact Steam asset? Used by SteamInventoryController.list[Bulk]FromSteam
     * to reject a second listing of the SAME physical asset (which, with the
     * escrow bot disabled, would go straight to ACTIVE and let both sell —
     * paying the seller twice for one undeliverable copy). Keys on assetId (the
     * unique per-copy id), NOT itemId — a seller may legitimately own two
     * different copies of the same item. Callers pass the "live" status set
     * (ACTIVE + PENDING_ESCROW); terminal CANCELLED/SOLD rows are ignored, so a
     * returned-then-relisted asset is fine. assetId is required (never matches
     * the NULL legacy/seed rows).
     */
    boolean existsBySellerUserIdAndAssetIdAndStatusIn(Long sellerUserId, String assetId, Collection<String> statuses)

    /**
     * The same question as {@link #existsBySellerUserIdAndAssetIdAndStatusIn},
     * asked once for a whole inventory instead of once per asset.
     *
     * {@code GET /api/steam/inventory} needs to know which of the seller's
     * Steam assets already carry a live listing, so the Sell grid can say
     * "3 of 50 listed" and hand the pick flow a copy that is actually free —
     * rather than offering every copy and letting the POST refuse it. Asking
     * the exists-query per asset would be up to 500 round trips on one page
     * load; this is one, and it returns ids only (no JOIN FETCH, no entity
     * hydration).
     *
     * Callers pass the same live-status set the double-list guard uses, so the
     * grid and the guard cannot drift apart. Rows with a NULL assetId (legacy
     * and seed listings) are excluded because they identify no physical copy.
     */
    @Query("""
        SELECT l.assetId FROM Listing l
        WHERE l.sellerUserId = :uid
          AND l.assetId IS NOT NULL
          AND l.status IN :statuses
    """)
    List<String> findLiveAssetIdsBySeller(@Param("uid") Long uid,
                                          @Param("statuses") Collection<String> statuses)

    /** Public marketplace "cheapest first" query — excludes hidden rows
     *  so vacation-mode / per-listing Hide doesn't leak into the grid. */
    @Query("""
        SELECT l FROM Listing l JOIN FETCH l.item
        WHERE l.status = 'ACTIVE'
          AND (l.hidden IS NULL OR l.hidden = false)
        ORDER BY l.price ASC
    """)
    List<Listing> findActiveOrderByPrice()

    /** Public "newest active" feed — drives the "Just listed" homepage
     *  rail. Filters hidden in SQL (bug chain found in batch 306/307).
     *  The service layer used to re-filter in Groovy after pulling
     *  every active row; pushing it into the index-backed query
     *  reduces the payload before it ever hits the JVM. */
    @Query("""
        SELECT l FROM Listing l JOIN FETCH l.item
        WHERE l.status = 'ACTIVE'
          AND (l.hidden IS NULL OR l.hidden = false)
        ORDER BY l.listedAt DESC
    """)
    List<Listing> findActiveOrderByNewest()

    /** Cheapest-first active listings for one item — drives the public
     *  `/api/listings/item/{id}` endpoint AND the homepage rails'
     *  per-item cheapest projection. Excludes hidden listings so a
     *  seller who listed cheap and then flipped Hide doesn't leak into
     *  public surfaces. */
    @Query("""
        SELECT l FROM Listing l JOIN FETCH l.item
        WHERE l.item.id = :itemId
          AND l.status = 'ACTIVE'
          AND (l.hidden IS NULL OR l.hidden = false)
        ORDER BY l.price ASC
    """)
    List<Listing> findCheapestForItem(@Param("itemId") Long itemId)

    /**
     * Bulk variant of `findCheapestForItem` — every visible ACTIVE listing
     * for a SET of item ids in ONE round-trip. Drives the homepage rails
     * (`findHottest` / `findMostWatched` / `findMostViewed`) which used to
     * loop `findCheapestForItem` once per item id — up to ~180 queries on
     * a single `/hottest` hit (batch 1089).
     *
     * Ordered by item id then price ASC so the service can walk the flat
     * list and take the first row per item id as that item's cheapest
     * active listing. JOIN FETCHes the item so the rail render stays
     * N+1-free. Empty input is the caller's responsibility — `IN ()` is
     * illegal SQL.
     */
    @Query("""
        SELECT l FROM Listing l JOIN FETCH l.item
        WHERE l.item.id IN :itemIds
          AND l.status = 'ACTIVE'
          AND (l.hidden IS NULL OR l.hidden = false)
        ORDER BY l.item.id ASC, l.price ASC
    """)
    List<Listing> findActiveForItemIds(@Param("itemIds") Collection<Long> itemIds)

    /**
     * Inverse of `BuyOrderRepository.findMatching` — finds the cheapest
     * ACTIVE BUY_NOW listings (excluding hidden, non-self) that satisfy
     * a buy order's filter and price ceiling. Drives
     * `BuyOrderService.tryFillFromExisting` so a fresh buy order
     * matches against EXISTING listings, not just future ones (batch
     * 268).
     *
     * Sorted by price ASC so the engine fills the cheapest match first
     * — same "best deal for the buyer" semantics as the existing
     * matching path on the listing-creation side.
     */
    @Query("""
        SELECT l FROM Listing l JOIN FETCH l.item
        WHERE l.status = 'ACTIVE'
          AND l.listingType = 'BUY_NOW'
          AND (l.hidden IS NULL OR l.hidden = false)
          AND l.price <= :maxPrice
          AND (:itemId IS NULL OR l.item.id = :itemId)
          AND (:category IS NULL OR l.item.category = :category)
          AND (:rarity IS NULL OR l.item.rarity = :rarity)
        ORDER BY l.price ASC
    """)
    List<Listing> findMatchingForBuyOrder(
        @Param("itemId")   Long itemId,
        @Param("category") String category,
        @Param("rarity")   String rarity,
        @Param("maxPrice") BigDecimal maxPrice,
        org.springframework.data.domain.Pageable page
    )

    /**
     * Bulk recent-sales count per item — drives the "🔥 N sold 7d" chip
     * on marketplace cards (batch 288). Aggregates SOLD listings since
     * the cutoff, grouped by item id. Caller filters input to the
     * visible card set so the IN clause stays bounded; empty input is
     * the caller's responsibility (`IN ()` is illegal SQL).
     */
    @Query("""
        SELECT l.item.id, COUNT(l) FROM Listing l
        WHERE l.item.id IN :itemIds
          AND l.status IN ('SOLD','RELISTED')
          AND (l.buyerUserId IS NULL OR l.sellerUserId IS NULL OR l.buyerUserId <> l.sellerUserId)
          AND l.soldAt IS NOT NULL
          AND l.soldAt >= :since
        GROUP BY l.item.id
    """)
    List<Object[]> countRecentSalesByItemIds(@Param("itemIds") List<Long> itemIds,
                                             @Param("since")   Long since)

    /**
     * Top-N most-sold item ids over a rolling window — drives the
     * "Hot right now" homepage rail (batch 289). Mirrors the
     * `WatchlistItemRepository.findTopWatchedItemIds` shape so the
     * two social-proof rails read symmetrically (one shows demand,
     * one shows realised volume). Pageable for cap.
     */
    @Query("""
        SELECT l.item.id, COUNT(l) AS cnt FROM Listing l
        WHERE l.status IN ('SOLD','RELISTED')
          AND (l.buyerUserId IS NULL OR l.sellerUserId IS NULL OR l.buyerUserId <> l.sellerUserId)
          AND l.soldAt IS NOT NULL
          AND l.soldAt >= :since
        GROUP BY l.item.id
        ORDER BY COUNT(l) DESC
    """)
    List<Object[]> findTopSoldItemIds(@Param("since") Long since,
                                      org.springframework.data.domain.Pageable page)

    /** SQL aggregate for `ListingService.updateItemFloorPrice` — fires on
     *  every listing mutation (buy, cancel, save, createListing), so the
     *  old `findCheapestForItem(id).first().price` path was pulling every
     *  active row for the item just to read one number.
     *
     *  Excludes hidden listings — the item's denormalised `lowestPrice`
     *  drives the marketplace grid card price and the item-modal floor
     *  chip. Both are public surfaces, so a hidden-by-seller listing
     *  shouldn't pull the public floor down. When a seller unhides, a
     *  subsequent listing mutation re-runs this aggregate and picks up
     *  the row again. An auction counts at its current bid once it has
     *  one, so the floor and the price alerts never quote a starting
     *  price nobody can buy at. */
    @Query("""
        SELECT MIN(CASE WHEN l.listingType = 'AUCTION' AND l.currentBid IS NOT NULL
                        THEN l.currentBid ELSE l.price END) FROM Listing l
        WHERE l.item.id = :itemId
          AND l.status = 'ACTIVE'
          AND (l.hidden IS NULL OR l.hidden = false)
    """)
    BigDecimal minPriceForItem(@Param("itemId") Long itemId)

    /** Live ACTIVE-listing count for one item — drives the 60s `supply`
     *  reconcile in ListingFloorRefreshService. `Item.supply` is a
     *  denormalised display field (item-modal "Supply" stat, /db Supply
     *  column, profile-card "N supply" chip) that was only ever written
     *  by the launch seed (and the retired SCMM sync), so it froze at
     *  its seed value forever — sales/cancels/new listings never moved
     *  it. Same hidden-exclusion semantics as minPriceForItem above:
     *  both feed public surfaces, so a seller-hidden row shouldn't
     *  inflate the public supply figure. */
    @Query("""
        SELECT COUNT(l) FROM Listing l
        WHERE l.item.id = :itemId
          AND l.status = 'ACTIVE'
          AND (l.hidden IS NULL OR l.hidden = false)
    """)
    long countActiveForItem(@Param("itemId") Long itemId)

    // `findActiveByPriceRange`, `searchActiveByName`, `findActiveByCategory`,
    // `findActiveByRarity` — all removed in batch 307. Every real caller
    // went through `findActivePublic` (below) instead, which composes the
    // full filter set in a single index-backed query AND correctly
    // excludes hidden listings. The un-composed variants were dead code
    // that would have silently leaked hidden listings if anyone wired
    // them back up.

    // Hidden listings are seller-side-only — they still count as ACTIVE
    // for the owner's MyStall but must NOT inflate the PUBLIC liveness
    // chips on /api/listings/stats (batch fix — caller is the homepage
    // MarketStatsStrip, an anon-visible surface). Mirrors the
    // hidden-exclusion clause already on findMinActivePrice /
    // findMaxActivePrice / findActiveOrderByNewest so the strip stays
    // self-consistent: a seller can't pad the "N listings from M sellers"
    // chip just by flipping Hide on inventory they don't want surfaced.
    @Query("SELECT COUNT(l) FROM Listing l WHERE l.status = 'ACTIVE' AND (l.hidden IS NULL OR l.hidden = false)")
    Long countActive()

    /** Count of DISTINCT sellers with at least one ACTIVE, non-hidden
     *  listing right now. Drives the homepage "N active sellers" trust
     *  chip (batch 1050) so anon visitors see how many counterparties
     *  there really are. Hidden listings excluded for the same reason
     *  the floor/ceiling queries exclude them — public chip should
     *  reflect what an anon visitor can actually browse. */
    @Query("SELECT COUNT(DISTINCT l.sellerUserId) FROM Listing l WHERE l.status = 'ACTIVE' AND l.sellerUserId IS NOT NULL AND (l.hidden IS NULL OR l.hidden = false)")
    Long countActiveSellers()

    /** Count of live AUCTION listings that haven't expired yet — feeds the
     *  homepage MarketStatsStrip's "N auctions running" chip. :now is the
     *  current epoch-ms (caller supplies it so tests can freeze time). */
    @Query("SELECT COUNT(l) FROM Listing l WHERE l.status = 'ACTIVE' AND l.listingType = 'AUCTION' AND (l.expiresAt IS NULL OR l.expiresAt > :now)")
    Long countActiveAuctions(@Param("now") Long now)

    @Query("SELECT SUM(l.price) FROM Listing l WHERE l.status IN ('SOLD','RELISTED') AND (l.buyerUserId IS NULL OR l.sellerUserId IS NULL OR l.buyerUserId <> l.sellerUserId) AND l.soldAt > :since")
    BigDecimal sumVolumeAfter(@Param("since") Long since)

    /** Count of platform-wide SOLD listings since the cutoff — feeds
     *  the homepage stats strip's 24h-sale-count chip (batch 1054).
     *  Paired with `sumVolumeAfter` this lets anon visitors see BOTH
     *  dollar volume AND deal count — "N sales totalling $X" reads as
     *  a richer liveness signal than either alone. */
    @Query("SELECT COUNT(l) FROM Listing l WHERE l.status IN ('SOLD','RELISTED') AND (l.buyerUserId IS NULL OR l.sellerUserId IS NULL OR l.buyerUserId <> l.sellerUserId) AND l.soldAt > :since")
    Long countSoldAfter(@Param("since") Long since)

    /** Lifetime SOLD count - used by SeedService to decide whether to
     *  backfill demo sales on a fresh boot. Cheap because COUNT against
     *  the status index is constant-time. */
    @Query("SELECT COUNT(l) FROM Listing l WHERE l.status = 'SOLD'")
    long countAllSold()

    @Query("SELECT l FROM Listing l JOIN FETCH l.item WHERE l.sellerUserId = :uid AND l.status = 'ACTIVE' ORDER BY l.listedAt DESC")
    List<Listing> findActiveBySeller(@Param("uid") Long uid)

    /** Paged variant — batch 1033 caps `/api/listings/my-stall` at a
     *  reasonable number of rows so a power-seller with thousands of
     *  active listings doesn't force the server to JOIN-FETCH all of
     *  them on every MyStall open. Same ORDER BY, SQL-level LIMIT. */
    @Query("SELECT l FROM Listing l JOIN FETCH l.item WHERE l.sellerUserId = :uid AND l.status = 'ACTIVE' ORDER BY l.listedAt DESC")
    List<Listing> findActiveBySellerPaged(@Param("uid") Long uid,
                                           org.springframework.data.domain.Pageable pageable)

    /**
     * The seller's own listings that are HELD awaiting a bot-escrow deposit.
     *
     * Deliberately a separate query rather than widening
     * {@link #findActiveBySeller} to {@code status IN ('ACTIVE','PENDING_ESCROW')}.
     * That query feeds the stall grid, the analytics endpoint, the CSV export,
     * the away-mode hide/unhide batch, `cancelAllActive`, and the admin seller
     * view; folding a not-yet-live listing into it would silently change every
     * one of those answers — a held listing would count toward stall value,
     * appear in earnings analytics, and be hidden/unhidden by vacation mode
     * while not actually being on the market. A held listing is a genuinely
     * different thing from a live one and gets its own list.
     *
     * Why it needs to exist at all: PENDING_ESCROW is invisible to every
     * seller-facing query, so before this the seller had no way to see — or
     * therefore to cancel — a listing whose deposit stalled, even though
     * {@code SellService.cancelListing} explicitly accepts PENDING_ESCROW and
     * would have worked if they could have named the id.
     */
    @Query("""
        SELECT l FROM Listing l JOIN FETCH l.item
        WHERE l.sellerUserId = :uid
          AND l.status = 'PENDING_ESCROW'
        ORDER BY l.listedAt DESC
    """)
    List<Listing> findPendingEscrowBySeller(@Param("uid") Long uid)

    /** Count + sum aggregates for the /profile hero strip — avoids
     *  hydrating every seller row just to call .size() / .inject(…)
     *  on it. Paired into a single call site in ProfileService. */
    @Query("SELECT COUNT(l) FROM Listing l WHERE l.sellerUserId = :uid AND l.status = 'ACTIVE'")
    long countActiveBySeller(@Param("uid") Long uid)

    @Query("SELECT COALESCE(SUM(l.price), 0) FROM Listing l WHERE l.sellerUserId = :uid AND l.status = 'ACTIVE'")
    BigDecimal sumActiveListingPriceBySeller(@Param("uid") Long uid)

    /** Scalar projection of the seller's user id for a given listing —
     *  drives the cart own-listing guard (CartService.add) without
     *  hydrating the whole row + item graph. Returns null when the
     *  listing doesn't exist or is a system listing (no seller). */
    @Query("SELECT l.sellerUserId FROM Listing l WHERE l.id = :listingId")
    Long findSellerUserIdById(@Param("listingId") Long listingId)

    /** Batch variant of {@link #findSellerUserIdById} — projects (listingId,
     *  sellerUserId) pairs for a set of listings in a single round trip.
     *  Drives the cart bulkMerge own-listing guard so the merge endpoint can
     *  reject the caller's own listings without a per-row probe. System
     *  listings (sellerUserId IS NULL) are excluded from the result. */
    @Query("SELECT l.id, l.sellerUserId FROM Listing l WHERE l.id IN :ids AND l.sellerUserId IS NOT NULL")
    List<Object[]> findSellerUserIdsForListings(@Param("ids") Collection<Long> ids)

    /** Scalar projection of the `hidden` flag for a given listing — drives
     *  the cart hidden-listing guard (CartService.add) without hydrating
     *  the whole row + item graph. Returns null when the listing doesn't
     *  exist; legacy null-hidden rows project as null and the caller
     *  treats null as visible (matches the listing-grid SQL's
     *  `l.hidden IS NULL OR l.hidden = false` convention). */
    @Query("SELECT l.hidden FROM Listing l WHERE l.id = :listingId")
    Boolean findHiddenById(@Param("listingId") Long listingId)

    /** Batch variant — returns the ids of listings in `:ids` that are
     *  currently hidden. Drives the cart bulkMerge hidden-listing guard
     *  so the merge endpoint can drop hidden ids without a per-row probe.
     *  Null-hidden legacy rows are excluded (treated as visible). */
    @Query("SELECT l.id FROM Listing l WHERE l.id IN :ids AND l.hidden = true")
    List<Long> findHiddenListingIds(@Param("ids") Collection<Long> ids)

    /** Count of the seller's hidden-but-active listings. Drives the
     *  away-mode "N hidden" chip without hydrating every row to count
     *  `.hidden == true`. Includes the legacy NULL-hidden fallback
     *  (rows written before the column existed treated as visible). */
    @Query("SELECT COUNT(l) FROM Listing l WHERE l.sellerUserId = :uid AND l.status = 'ACTIVE' AND l.hidden = true")
    long countHiddenActiveBySeller(@Param("uid") Long uid)

    /** Most-recent listedAt timestamp across every ACTIVE listing the
     *  seller has up right now. Drives the public-stall "Last listed Xh
     *  ago" activity chip (batch 1045) — a sharper signal of a seller's
     *  engagement than `lastSyncedAt` (Steam sync) because it reflects
     *  real marketplace action. Null when the seller has no active
     *  listings. Indexed COUNT-style aggregate, cheap. */
    @Query("SELECT MAX(l.listedAt) FROM Listing l WHERE l.sellerUserId = :uid AND l.status = 'ACTIVE'")
    Long findLastListedAtBySeller(@Param("uid") Long uid)

    /** Most-recent soldAt timestamp for the seller — drives the stall
     *  hero "Last sold Xh ago" chip (batch 1047). Together with
     *  `lastListedAt` this lets buyers read "listing activity" vs
     *  "sales activity" separately: a seller who lists but never sells
     *  looks different from one who sells routinely. Single indexed
     *  MAX aggregate, same composite index shape as lastListedAt. */
    @Query("SELECT MAX(l.soldAt) FROM Listing l WHERE l.sellerUserId = :uid AND l.status IN ('SOLD','RELISTED') AND (l.buyerUserId IS NULL OR l.sellerUserId IS NULL OR l.buyerUserId <> l.sellerUserId)")
    Long findLastSoldAtBySeller(@Param("uid") Long uid)

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

    /** Paged variant — batch 1043 caps the public-stall response at
     *  a reasonable ceiling so visiting a prolific seller's stall
     *  doesn't hydrate thousands of Listing rows on every view. Same
     *  ORDER BY + JOIN FETCH, SQL-level LIMIT. */
    @Query("""
        SELECT l FROM Listing l JOIN FETCH l.item
        WHERE l.sellerUserId = :uid
          AND l.status = 'ACTIVE'
          AND (l.hidden IS NULL OR l.hidden = false)
        ORDER BY l.listedAt DESC
    """)
    List<Listing> findActiveVisibleBySellerPaged(@Param("uid") Long uid,
                                                  org.springframework.data.domain.Pageable pageable)

    /** "More from this seller" rail on the item-detail modal — other
     *  visible active listings from the same seller, excluding the
     *  item currently on screen. Sorted newest-first; Pageable caps
     *  the rail size. Returns `[]` for system listings (uid is null)
     *  or when the seller has no other listings. */
    @Query("""
        SELECT l FROM Listing l JOIN FETCH l.item
        WHERE l.sellerUserId = :uid
          AND l.item.id <> :excludeItemId
          AND l.status = 'ACTIVE'
          AND (l.hidden IS NULL OR l.hidden = false)
        ORDER BY l.listedAt DESC
    """)
    List<Listing> findOtherActiveBySeller(
        @Param("uid") Long uid,
        @Param("excludeItemId") Long excludeItemId,
        org.springframework.data.domain.Pageable page
    )

    /** "From sellers you follow" feed — visible active listings from
     *  the given seller-id set, newest first. Uses `JOIN FETCH l.item`
     *  (N+1 dodge) and leans on the existing `idx_listings_seller`
     *  index; Pageable for the 20-row cap. */
    @Query("""
        SELECT l FROM Listing l JOIN FETCH l.item
        WHERE l.sellerUserId IN :uids
          AND l.status = 'ACTIVE'
          AND (l.hidden IS NULL OR l.hidden = false)
        ORDER BY l.listedAt DESC
    """)
    List<Listing> findActiveVisibleBySellerIds(@Param("uids") Collection<Long> sellerUserIds,
                                                org.springframework.data.domain.Pageable pageable)

    /** Auctions whose 10-minute close reminder is due. Backs
     *  `BidService.sweepEndingSoon`. The partial index
     *  `idx_listings_ending_soon_unnotified` on
     *  (expires_at WHERE status='ACTIVE' AND listing_type='AUCTION' AND
     *  ending_soon_notified=false) makes the scan touch only the tiny
     *  tail of unnotified active auctions each tick, not the whole
     *  listings table. */
    @Query("""
        SELECT l FROM Listing l JOIN FETCH l.item
        WHERE l.status = 'ACTIVE'
          AND l.listingType = 'AUCTION'
          AND l.endingSoonNotified = false
          AND l.expiresAt IS NOT NULL
          AND l.expiresAt > :now
          AND l.expiresAt <= :cutoff
    """)
    List<Listing> findEndingSoonUnnotified(@Param("now") Long now, @Param("cutoff") Long cutoff)

    /**
     * Atomic claim for the ending-soon nudge — flips `endingSoonNotified`
     * from false→true ONLY if it's still false at UPDATE time. Returns the
     * number of rows touched: 1 = this pod owns the fan-out, 0 = a sibling
     * pod already claimed it (or the listing closed / was deleted between
     * sweeper read and claim).
     *
     * Multi-pod race protection. Same shape as
     * {@link com.sboxmarket.repository.WatchlistAlertRepository#claimForFiring}
     * (wave 112) and {@link com.sboxmarket.repository.FraudSignalClaimRepository}
     * (wave 120): `findEndingSoonUnnotified` is read concurrently by every pod
     * running `BidService.sweepEndingSoon` on the same 2-minute heartbeat, and
     * both pods would see the same `endingSoonNotified=false` row. Without an
     * atomic claim, both pods then ran the full fan-out (bell push, email)
     * before either pod's `notifyEndingSoon`'s `endingSoonNotified=true` save
     * landed — so every bidder + watcher received the AUCTION_ENDING
     * notification TWICE (and the email TWICE), making the "one reminder, not
     * a barrage" promise on top of `notifyEndingSoon` a multi-pod lie.
     *
     * The fix is a conditional UPDATE: the FIRST pod's UPDATE flips false→true
     * and returns 1, the LATE pod's UPDATE sees the row no longer matches the
     * `=false` predicate and returns 0 — the late pod bails before any
     * recipient lookup happens, so the fan-out fires exactly once.
     */
    @Modifying
    @Query("""
        UPDATE Listing l
           SET l.endingSoonNotified = true
         WHERE l.id = :id
           AND l.endingSoonNotified = false
    """)
    int claimEndingSoonNotify(@Param("id") Long id)

    @Query("SELECT l FROM Listing l JOIN FETCH l.item WHERE l.buyerUserId = :uid AND l.status = 'SOLD' ORDER BY l.soldAt DESC")
    List<Listing> findOwnedBy(@Param("uid") Long uid)

    /** Paged variant — the /api/listings/inventory endpoint uses this
     *  to cap the hydrated set at 500 rows. A user with a very long
     *  purchase history otherwise forces the server to serialise
     *  thousands of Listings on every SellItemsModal open. */
    @Query("SELECT l FROM Listing l JOIN FETCH l.item WHERE l.buyerUserId = :uid AND l.status = 'SOLD' ORDER BY l.soldAt DESC")
    List<Listing> findOwnedByPaged(@Param("uid") Long uid,
                                    org.springframework.data.domain.Pageable pageable)

    /** Count + floor-price-sum aggregates for the /profile inventory
     *  stat. Uses item.lowestPrice with steamPrice fallback — matches
     *  the inline formula the ProfileService used to run row-by-row. */
    @Query("SELECT COUNT(l) FROM Listing l WHERE l.buyerUserId = :uid AND l.status = 'SOLD'")
    long countOwnedBy(@Param("uid") Long uid)

    /**
     * Owned-inventory value for the /profile Portfolio stat.
     *
     * NULLIF(lowestPrice, 0) is load-bearing: an item with NO active
     * listings carries lowestPrice = 0 (a real BigDecimal zero written by
     * the floor sweep), NOT null. Plain COALESCE(lowestPrice, steamPrice, 0)
     * only skips NULLs, so a 0 floor short-circuited the whole expression to
     * 0 and the steamPrice fallback the doc comment promises NEVER fired —
     * a portfolio of a $76 helmet + $20 gown read as $0 for those rows,
     * undercounting holdings by ~98% (e.g. $2.08 shown for a $112 inventory)
     * and disagreeing with the Sell page's own est-value, which already
     * falls back to steamPrice. Wrapping in NULLIF(...,0) maps a 0 floor to
     * NULL so COALESCE moves on to steamPrice, matching the stated intent.
     * NULLIF + COALESCE are both standard JPQL → portable to H2 and Postgres.
     */
    @Query("""
        SELECT COALESCE(SUM(COALESCE(NULLIF(l.item.lowestPrice, 0), l.item.steamPrice, 0)), 0)
        FROM Listing l
        WHERE l.buyerUserId = :uid AND l.status = 'SOLD'
    """)
    BigDecimal sumOwnedInventoryValueBy(@Param("uid") Long uid)

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
          AND (:q           = '' OR LOWER(l.item.name) LIKE LOWER(CONCAT('%', :q, '%')) ESCAPE '\\')
          AND (:category    = '' OR l.item.category = :category)
          AND (:rarity      = '' OR l.item.rarity   = :rarity)
          AND (:listingType = '' OR l.listingType   = :listingType)
          AND (:minPrice IS NULL OR (CASE WHEN l.listingType = 'AUCTION' AND l.currentBid IS NOT NULL THEN l.currentBid ELSE l.price END) >= :minPrice)
          AND (:maxPrice IS NULL OR (CASE WHEN l.listingType = 'AUCTION' AND l.currentBid IS NOT NULL THEN l.currentBid ELSE l.price END) <= :maxPrice)
        ORDER BY l.price ASC, l.id ASC
    """)
    List<Listing> findActivePublic(
        @Param("q") String q,
        @Param("category") String category,
        @Param("rarity") String rarity,
        @Param("listingType") String listingType,
        @Param("minPrice") BigDecimal minPrice,
        @Param("maxPrice") BigDecimal maxPrice
    )

    /** Minimum ACTIVE listing price — used by `getMarketStats` to render
     *  the homepage floor without pulling every active row into memory. */
    @Query("SELECT MIN(l.price) FROM Listing l WHERE l.status = 'ACTIVE' AND (l.hidden IS NULL OR l.hidden = false)")
    BigDecimal findMinActivePrice()

    /** Maximum ACTIVE listing price — batch 1052 surfaces it on the
     *  homepage stats strip so visitors see the price RANGE (floor →
     *  ceiling), not just the floor. Tells a buyer "this marketplace
     *  has items from $1 to $1600" — both an affordability signal AND
     *  a high-value-trade signal in one chip. */
    @Query("SELECT MAX(l.price) FROM Listing l WHERE l.status = 'ACTIVE' AND (l.hidden IS NULL OR l.hidden = false)")
    BigDecimal findMaxActivePrice()

    /** Most-recent soldAt across the entire marketplace — feeds the
     *  homepage "Last sale Xm ago" liveness chip. Fresh recent-sale
     *  signal is stronger evidence of an active marketplace than a
     *  7-day volume number, especially for anon visitors landing cold. */
    @Query("SELECT MAX(l.soldAt) FROM Listing l WHERE l.status IN ('SOLD','RELISTED') AND (l.buyerUserId IS NULL OR l.sellerUserId IS NULL OR l.buyerUserId <> l.sellerUserId) AND l.soldAt IS NOT NULL")
    Long findLastSaleAt()

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

    /** Auctions ending within a given window (anchor → anchor + window).
     *  Powers the "Auctions ending soon" homepage rail so users see the
     *  live bidding tension at the top of the page. Excludes hidden/away
     *  listings and JOIN FETCHes the item so the frontend can render
     *  without N+1s. */
    @Query("""
        SELECT l FROM Listing l JOIN FETCH l.item
        WHERE l.status = 'ACTIVE'
          AND l.listingType = 'AUCTION'
          AND (l.hidden IS NULL OR l.hidden = false)
          AND l.expiresAt IS NOT NULL
          AND l.expiresAt > :now
          AND l.expiresAt <= :deadline
        ORDER BY l.expiresAt ASC
    """)
    List<Listing> findAuctionsEndingBefore(@Param("now") Long now, @Param("deadline") Long deadline)

    /** Recent sale history for a catalogue item — drives the "Last N sales"
     *  table on the item detail modal. Buyers use this as the anchor for
     *  fairness ("the last 10 actually sold at $X") which the floor price
     *  alone doesn't convey. Caller truncates; typical UI shows 10. */
    @Query("""
        SELECT l FROM Listing l JOIN FETCH l.item
        WHERE l.item.id = :itemId
          AND l.status IN ('SOLD','RELISTED')
          AND (l.buyerUserId IS NULL OR l.sellerUserId IS NULL OR l.buyerUserId <> l.sellerUserId)
          AND l.soldAt IS NOT NULL
        ORDER BY l.soldAt DESC
    """)
    List<Listing> findRecentSalesForItem(@Param("itemId") Long itemId, org.springframework.data.domain.Pageable page)

    /** Count of completed sales by a seller — drives the "verified seller"
     *  badge threshold and the lifetime sales stat on the stall hero. */
    @Query("SELECT COUNT(l) FROM Listing l WHERE l.sellerUserId = :uid AND l.status IN ('SOLD','RELISTED') AND (l.buyerUserId IS NULL OR l.sellerUserId IS NULL OR l.buyerUserId <> l.sellerUserId)")
    long countSoldBySeller(@Param("uid") Long uid)

    /** Every listing a seller has ever posted — across ACTIVE / SOLD /
     *  CANCELLED (batch 589). Drives the admin CSV drill-down for
     *  fraud triage ("how many items did this user list at $10k
     *  overnight"). Eager-fetches the item name so the CSV doesn't
     *  need N+1 round-trips. Sorted newest-listing-first. */
    @Query("""
        SELECT l FROM Listing l JOIN FETCH l.item
        WHERE l.sellerUserId = :uid
        ORDER BY l.listedAt DESC
    """)
    List<Listing> findAllBySeller(@Param("uid") Long uid,
                                  org.springframework.data.domain.Pageable page)

    /** Last-N-days sold count for a seller (batch 534). Drives a
     *  "recently active" chip on the stall hero — differentiates a
     *  daily active seller from a 2-year-old account that hasn't
     *  moved anything in months. Uses `soldAt` so we only count
     *  listings that actually completed a sale in the window. */
    @Query("""
        SELECT COUNT(l) FROM Listing l
        WHERE l.sellerUserId = :uid
          AND l.status IN ('SOLD','RELISTED')
          AND (l.buyerUserId IS NULL OR l.sellerUserId IS NULL OR l.buyerUserId <> l.sellerUserId)
          AND l.soldAt IS NOT NULL
          AND l.soldAt >= :since
    """)
    long countSoldBySellerSince(@Param("uid") Long uid, @Param("since") Long since)

    /** Bulk last-N-days sold count grouped by item id. Powers the
     *  per-item demand chip on the seller's MyStall analytics tab.
     *  One GROUP BY against the (status, soldAt, item_id) index range
     *  beats N+1 round-trips when a stall has 100+ listings. */
    @Query("""
        SELECT l.item.id, COUNT(l) FROM Listing l
        WHERE l.item.id IN :itemIds
          AND l.status IN ('SOLD','RELISTED')
          AND (l.buyerUserId IS NULL OR l.sellerUserId IS NULL OR l.buyerUserId <> l.sellerUserId)
          AND l.soldAt IS NOT NULL
          AND l.soldAt >= :since
        GROUP BY l.item.id
    """)
    List<Object[]> countSoldByItemsSince(@Param("itemIds") List<Long> itemIds, @Param("since") Long since)

    /** Lifetime + windowed revenue for a seller (batch 605). Sums the
     *  gross sale price (the winning bid for an auction, else the
     *  listing `price`; not the post-fee credit) so sellers see
     *  top-line revenue; the net figure is always visible in the
     *  wallet transaction history via the SALE rows. `soldAt` gate
     *  mirrors the count variants so partial / pending trades don't
     *  inflate the number. */
    @Query("""
        SELECT COALESCE(SUM(CASE WHEN l.listingType = 'AUCTION' AND l.currentBid IS NOT NULL THEN l.currentBid ELSE l.price END), 0) FROM Listing l
        WHERE l.sellerUserId = :uid
          AND l.status IN ('SOLD','RELISTED')
          AND (l.buyerUserId IS NULL OR l.sellerUserId IS NULL OR l.buyerUserId <> l.sellerUserId)
    """)
    BigDecimal sumRevenueBySeller(@Param("uid") Long uid)

    @Query("""
        SELECT COALESCE(SUM(CASE WHEN l.listingType = 'AUCTION' AND l.currentBid IS NOT NULL THEN l.currentBid ELSE l.price END), 0) FROM Listing l
        WHERE l.sellerUserId = :uid
          AND l.status IN ('SOLD','RELISTED')
          AND (l.buyerUserId IS NULL OR l.sellerUserId IS NULL OR l.buyerUserId <> l.sellerUserId)
          AND l.soldAt IS NOT NULL
          AND l.soldAt >= :since
    """)
    BigDecimal sumRevenueBySellerSince(@Param("uid") Long uid, @Param("since") Long since)

    /** Bulk sold-count aggregate for many sellers in a single GROUP BY query.
     *  Drives the verified-seller badge on marketplace cards (batch 296) —
     *  O(1) per deduped seller-id instead of N×COUNT queries. Sellers with
     *  zero sales are omitted by Postgres' GROUP BY (no empty groups), so
     *  callers should treat missing ids as 0. */
    @Query("""
        SELECT l.sellerUserId, COUNT(l) FROM Listing l
        WHERE l.sellerUserId IN :ids AND l.status IN ('SOLD','RELISTED')
          AND (l.buyerUserId IS NULL OR l.sellerUserId IS NULL OR l.buyerUserId <> l.sellerUserId)
        GROUP BY l.sellerUserId
    """)
    List<Object[]> countSoldByMultipleSellers(@Param("ids") List<Long> sellerUserIds)

    /** Bulk active-count aggregate for many sellers — mirror of
     *  countSoldByMultipleSellers (batch 666). Drives the seller-search
     *  response so the UI can show `N active · M sold` per result in
     *  a single O(1)-per-seller query instead of 2N round-trips.
     *  Hidden listings are excluded to match every public-audience
     *  surface. */
    @Query("""
        SELECT l.sellerUserId, COUNT(l) FROM Listing l
        WHERE l.sellerUserId IN :ids
          AND l.status = 'ACTIVE'
          AND (l.hidden IS NULL OR l.hidden = false)
        GROUP BY l.sellerUserId
    """)
    List<Object[]> countActiveByMultipleSellers(@Param("ids") List<Long> sellerUserIds)

    /** Recent sale rows by a seller — drives the MyStall "Sold items" tab
     *  and the optional public stall sales-history view. Page size caps
     *  client-side dumps. */
    @Query("""
        SELECT l FROM Listing l JOIN FETCH l.item
        WHERE l.sellerUserId = :uid
          AND l.status IN ('SOLD','RELISTED')
          AND (l.buyerUserId IS NULL OR l.sellerUserId IS NULL OR l.buyerUserId <> l.sellerUserId)
          AND l.soldAt IS NOT NULL
        ORDER BY l.soldAt DESC
    """)
    List<Listing> findSoldBySeller(@Param("uid") Long uid, org.springframework.data.domain.Pageable page)

    /** Homepage "Top sellers" rail aggregate. Returns [sellerUserId, count]
     *  tuples for sellers with at least `minSold` completed sales, sorted
     *  by count DESC. Caller enriches with display name, avatar, rating
     *  summary. The threshold filter stays in SQL so we don't pull every
     *  seller into memory — only those who cleared the bar make it out. */
    @Query("""
        SELECT l.sellerUserId, COUNT(l)
        FROM Listing l
        WHERE l.status IN ('SOLD','RELISTED')
          AND (l.buyerUserId IS NULL OR l.sellerUserId IS NULL OR l.buyerUserId <> l.sellerUserId)
          AND l.sellerUserId IS NOT NULL
        GROUP BY l.sellerUserId
        HAVING COUNT(l) >= :minSold
        ORDER BY COUNT(l) DESC
    """)
    List<Object[]> topSellers(@Param("minSold") long minSold, org.springframework.data.domain.Pageable page)

    /** Distinct seller ids across every listing status (batch 667).
     *  Drives the sitemap seller-URL inclusion so stalls with only
     *  active-but-unsold listings get SEO-indexed too — a fresh
     *  marketplace shouldn't have its stall pages invisible to crawlers
     *  just because the first sale hasn't closed yet. Hidden listings
     *  are excluded — a stall that only hosts hidden listings has no
     *  public content to index. Page-bounded so a future 1M-seller
     *  platform can iterate the set without blowing the heap. */
    @Query("""
        SELECT DISTINCT l.sellerUserId FROM Listing l
        WHERE l.sellerUserId IS NOT NULL
          AND (l.hidden IS NULL OR l.hidden = false)
        ORDER BY l.sellerUserId ASC
    """)
    List<Long> findSellerIdsWithAnyListing(org.springframework.data.domain.Pageable page)

    /** Platform-wide "Just Sold" feed. Most-recent completed sales across
     *  every seller. Fuels the homepage social-proof ticker so anonymous
     *  visitors see activity as soon as they land. JOIN FETCH on item keeps
     *  the card render one round-trip. */
    @Query("""
        SELECT l FROM Listing l JOIN FETCH l.item
        WHERE l.status IN ('SOLD','RELISTED')
          AND (l.buyerUserId IS NULL OR l.sellerUserId IS NULL OR l.buyerUserId <> l.sellerUserId)
          AND l.soldAt IS NOT NULL
        ORDER BY l.soldAt DESC
    """)
    List<Listing> findRecentlySold(org.springframework.data.domain.Pageable page)

    /** Top discounts — active BUY_NOW listings where the price is
     *  meaningfully below the catalogue steamPrice, ordered by percentage
     *  gap DESC. Fuels the homepage "Top deals" rail. Filter out anything
     *  with a non-positive steamPrice so the ratio doesn't divide-by-zero. */
    @Query("""
        SELECT l FROM Listing l JOIN FETCH l.item i
        WHERE l.status = 'ACTIVE'
          AND l.listingType = 'BUY_NOW'
          AND (l.hidden IS NULL OR l.hidden = false)
          AND i.steamPrice IS NOT NULL
          AND i.steamPrice > 0
          AND l.price < i.steamPrice
        ORDER BY (l.price / i.steamPrice) ASC
    """)
    List<Listing> findTopDeals(org.springframework.data.domain.Pageable page)

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

    /** Trade velocity: how many listings for this specific item sold
     *  in the window. Indexed COUNT — avoids pulling rows. Drives the
     *  "N sold this week" activity chip on the item detail modal. */
    @Query("""
        SELECT COUNT(l) FROM Listing l
        WHERE l.item.id = :itemId
          AND l.status IN ('SOLD','RELISTED')
          AND (l.buyerUserId IS NULL OR l.sellerUserId IS NULL OR l.buyerUserId <> l.sellerUserId)
          AND l.soldAt  IS NOT NULL
          AND l.soldAt  >= :since
    """)
    long countSoldForItemSince(@Param('itemId') Long itemId, @Param('since') Long since)

    /** Dollar volume of sold listings for an item since a cutoff.
     *  Complement to the count — drives the "$X volume / 30d" chip on
     *  item detail. Null-safe SUM returns 0 for items with no sales.
     *  Auctions count at the winning bid (currentBid), not the start price. */
    @Query("""
        SELECT COALESCE(SUM(CASE WHEN l.listingType = 'AUCTION' AND l.currentBid IS NOT NULL
                                 THEN l.currentBid ELSE l.price END), 0) FROM Listing l
        WHERE l.item.id = :itemId
          AND l.status IN ('SOLD','RELISTED')
          AND (l.buyerUserId IS NULL OR l.sellerUserId IS NULL OR l.buyerUserId <> l.sellerUserId)
          AND l.soldAt  IS NOT NULL
          AND l.soldAt  >= :since
    """)
    BigDecimal sumSoldVolumeForItemSince(@Param('itemId') Long itemId, @Param('since') Long since)

    /** Most-recent SOLD listing for an item — drives the "last sold" chip
     *  on item detail (different from floor = current cheapest listing).
     *  Returns a Pageable page of 1 so Spring Data doesn't need a top-1
     *  dialect hack. Empty list means the item has never sold. */
    @Query("""
        SELECT l FROM Listing l
        WHERE l.item.id = :itemId
          AND l.status IN ('SOLD','RELISTED')
          AND (l.buyerUserId IS NULL OR l.sellerUserId IS NULL OR l.buyerUserId <> l.sellerUserId)
          AND l.soldAt  IS NOT NULL
        ORDER BY l.soldAt DESC
    """)
    List<Listing> findLastSoldForItem(@Param('itemId') Long itemId, org.springframework.data.domain.Pageable page)

    /** Listings with at least one user report, ordered by report_count DESC
     *  (break ties by most-recently-reported). Admin moderation queue.
     *  Top-N cap is applied at the service layer — keeping the query
     *  method signature free of a limit param avoids Spring Data JPA
     *  trying to parse the method name as a derived query. */
    @Query(value = """
        SELECT l FROM Listing l JOIN FETCH l.item
        WHERE l.reportCount > 0
          AND l.status = 'ACTIVE'
        ORDER BY l.reportCount DESC, l.lastReportedAt DESC
    """)
    List<Listing> selectReportedActive()
}
