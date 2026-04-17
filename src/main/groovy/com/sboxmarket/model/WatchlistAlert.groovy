package com.sboxmarket.model

import jakarta.persistence.*

/**
 * Server-side price alert for a watched item. Replaces the
 * localStorage-only alerts that only fired when the user opened the
 * Watchlist page. The scheduled sweeper polls ACTIVE rows and pushes
 * a WATCHLIST_PRICE_DROP notification when the item's floor price
 * drops to or below targetPrice, then flips status=FIRED so the
 * alert doesn't re-notify on every subsequent tick.
 *
 * One ACTIVE row per (userId, itemId) — re-setting an alert for an
 * item you already watch updates the existing row rather than
 * stacking siblings. Enforced at the service layer and backed by a
 * partial unique index.
 */
@Entity
@Table(name = "watchlist_alerts")
class WatchlistAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id

    @Column(name = "user_id", nullable = false)
    Long userId

    @Column(name = "item_id", nullable = false)
    Long itemId

    @Column(name = "target_price", nullable = false, precision = 10, scale = 2)
    BigDecimal targetPrice

    /** ACTIVE → watching; FIRED → notification sent, dormant; CANCELLED → user removed. */
    @Column(nullable = false)
    String status = 'ACTIVE'

    @Column(name = "created_at", nullable = false)
    Long createdAt = System.currentTimeMillis()

    @Column(name = "fired_at")
    Long firedAt
}
