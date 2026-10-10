package com.sboxmarket.repository

import com.sboxmarket.model.CartItem
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface CartItemRepository extends JpaRepository<CartItem, Long> {

    /** Listing ids in the user's cart, oldest-added first so the cart
     *  page renders in the order they were added (matches the previous
     *  localStorage behaviour). */
    @Query("SELECT c.listingId FROM CartItem c WHERE c.userId = :uid ORDER BY c.addedAt ASC")
    List<Long> findListingIdsByUser(@Param('uid') Long userId)

    /** Paged companion — a user can hoard hundreds of items in cart
     *  before the per-user cap kicks in; the overload keeps cart-render
     *  costs O(pageSize) regardless. */
    @Query("SELECT c.listingId FROM CartItem c WHERE c.userId = :uid ORDER BY c.addedAt ASC")
    List<Long> findListingIdsByUser(@Param('uid') Long userId,
                                     org.springframework.data.domain.Pageable pageable)

    /** Row-count companion for cap/headroom checks — avoids hydrating
     *  the entire id list just to call .size(). */
    @Query("SELECT COUNT(c) FROM CartItem c WHERE c.userId = :uid")
    long countByUser(@Param('uid') Long userId)

    /** Existence probe for the unique-constraint guard — saves the
     *  cost of catching a ConstraintViolation on idempotent re-adds. */
    @Query("""
        SELECT COUNT(c) > 0 FROM CartItem c
        WHERE c.userId = :uid AND c.listingId = :listingId
    """)
    boolean existsByUserAndListing(@Param('uid') Long userId,
                                    @Param('listingId') Long listingId)

    @org.springframework.transaction.annotation.Transactional
    @Modifying
    @Query("DELETE FROM CartItem c WHERE c.userId = :uid AND c.listingId = :listingId")
    int deleteByUserAndListing(@Param('uid') Long userId,
                                @Param('listingId') Long listingId)

    @org.springframework.transaction.annotation.Transactional
    @Modifying
    @Query("DELETE FROM CartItem c WHERE c.userId = :uid")
    int deleteAllByUser(@Param('uid') Long userId)

    @Query("""
        SELECT c.listingId FROM CartItem c
        WHERE c.userId = :uid AND c.listingId IN :listingIds
    """)
    List<Long> findExistingListingIds(@Param('uid') Long userId,
                                       @Param('listingIds') List<Long> listingIds)

    /** Other users with this listing in their cart, excluding the
     *  buyer who just bought it (batch 503). Drives the CART_ITEM_SOLD
     *  fan-out so users learn that a popular item is gone before they
     *  open the cart page and discover the row is greyed-out stale.
     *  Capped in the service layer to bound fan-out cost on a hot
     *  drop where many users had the same listing queued. */
    @Query("""
        SELECT DISTINCT c.userId FROM CartItem c
        WHERE c.listingId = :listingId AND c.userId <> :excludeUserId
    """)
    List<Long> findOtherUsersWithListing(@Param('listingId') Long listingId,
                                          @Param('excludeUserId') Long excludeUserId)

    /** Paged companion — on a hot drop the per-listing watcher set can
     *  be very large; CART_ITEM_SOLD fan-out callers should cap. */
    @Query("""
        SELECT DISTINCT c.userId FROM CartItem c
        WHERE c.listingId = :listingId AND c.userId <> :excludeUserId
    """)
    List<Long> findOtherUsersWithListing(@Param('listingId') Long listingId,
                                          @Param('excludeUserId') Long excludeUserId,
                                          org.springframework.data.domain.Pageable pageable)

    @org.springframework.transaction.annotation.Transactional
    @Modifying
    @Query("DELETE FROM CartItem c WHERE c.listingId = :listingId")
    int deleteAllByListing(@Param('listingId') Long listingId)

    /** Bulk-delete cart rows pointing at listings whose status is no
     *  longer ACTIVE (batch 506). The sold / cancelled / admin-cancel
     *  paths all call deleteAllByListing inline, but this sweeper
     *  covers legacy rows that pre-date those fan-outs plus any edge
     *  case where a status flipped without going through the batched
     *  cart scrub (e.g. expired auction, bulkCancel). Keeps the cart
     *  UI from accumulating stale-grey rows forever. */
    @org.springframework.transaction.annotation.Transactional
    @Modifying
    @Query("""
        DELETE FROM CartItem c
         WHERE c.listingId IN (
             SELECT l.id FROM com.sboxmarket.model.Listing l
              WHERE l.status <> 'ACTIVE'
         )
    """)
    int deleteRowsPointingAtNonActiveListings()
}
