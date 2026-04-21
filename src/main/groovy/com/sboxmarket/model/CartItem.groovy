package com.sboxmarket.model

import jakarta.persistence.*

/**
 * Server-side cart row. One per (user_id, listing_id) — enforced by
 * the V31 unique constraint so a hot double-click on Add-to-cart can't
 * stack duplicate rows. The localStorage `sb_cart` array is still
 * maintained as the offline write-through cache so a no-network reload
 * shows the cart contents without a round trip.
 *
 * We deliberately store ONLY the listing id + timestamp here — no
 * cached price / name / thumb. Stale snapshots cause more pain than
 * value; the /cart page already pings each listing on open via
 * `cartFreshness` so the source of truth at checkout time is always
 * the live listing row.
 */
@Entity
@Table(name = "cart_items", indexes = [
    @Index(name = "idx_cart_items_user",    columnList = "userId"),
    @Index(name = "idx_cart_items_listing", columnList = "listingId")
])
class CartItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id

    @Column(name = 'user_id', nullable = false)
    Long userId

    @Column(name = 'listing_id', nullable = false)
    Long listingId

    @Column(name = 'added_at', nullable = false)
    Long addedAt = System.currentTimeMillis()
}
