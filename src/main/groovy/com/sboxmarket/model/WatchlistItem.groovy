package com.sboxmarket.model

import jakarta.persistence.*

/**
 * Server-side watchlist row. One per (user_id, item_id) pair —
 * enforced by the V30 unique constraint so a hot double-click can't
 * stack duplicate stars. The localStorage `sb_watchlist` array is
 * still maintained as a write-through cache so an offline reload
 * shows the user's stars without a network round trip.
 */
@Entity
@Table(name = "watchlist_items", indexes = [
    @Index(name = "idx_watchlist_items_user", columnList = "userId"),
    @Index(name = "idx_watchlist_items_item", columnList = "itemId")
])
class WatchlistItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id

    @Column(name = 'user_id', nullable = false)
    Long userId

    @Column(name = 'item_id', nullable = false)
    Long itemId

    @Column(name = 'created_at', nullable = false)
    Long createdAt = System.currentTimeMillis()
}
