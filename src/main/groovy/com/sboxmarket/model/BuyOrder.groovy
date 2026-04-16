package com.sboxmarket.model

import jakarta.persistence.*
import jakarta.validation.constraints.*
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * A standing buy order — "I want any listing matching these criteria, up to $X, and
 * I'll buy it automatically the moment it shows up." Modelled after the CSFloat
 * Buy Orders feature. When a new listing is created that satisfies every non-null
 * criterion and has price ≤ maxPrice, the service auto-purchases using the buyer's
 * wallet and the buy order is decremented (or cancelled when quantity hits 0).
 */
@Entity
@Table(name = "buy_orders")
@JsonIgnoreProperties(["hibernateLazyInitializer", "handler"])
class BuyOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id

    @NotNull
    @Column(nullable = false)
    Long buyerUserId

    /** Snapshot of buyer display name for seller-facing UIs. */
    @Column
    String buyerName

    /** Optional — when set, only listings for this exact item match. */
    @Column
    Long itemId

    /** Snapshot of item name at creation time (for display/search). */
    @Column
    String itemName

    /** Optional category filter (Hats, Jackets, …). */
    @Column
    String category

    /** Optional rarity filter (Limited, Off-Market, Standard). */
    @Column
    String rarity

    @NotNull
    @DecimalMin("0.01")
    @Column(nullable = false, precision = 19, scale = 2)
    BigDecimal maxPrice

    /** Remaining quantity — decremented each auto-fill. Order cancels at 0. */
    @NotNull
    @Column(nullable = false)
    Integer quantity = 1

    /** Original quantity when the order was opened (for display). */
    @Column(nullable = false)
    Integer originalQuantity = 1

    /** ACTIVE, FILLED, CANCELLED, EXPIRED */
    @Column(nullable = false)
    String status = "ACTIVE"

    @Column(name = "created_at", nullable = false)
    Long createdAt = System.currentTimeMillis()

    @Column(name = "updated_at", nullable = false)
    Long updatedAt = System.currentTimeMillis()
}
