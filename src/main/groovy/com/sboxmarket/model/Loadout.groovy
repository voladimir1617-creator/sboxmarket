package com.sboxmarket.model

import jakarta.persistence.*
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * A user-curated set of up to 8 s&box cosmetics — one per slot (Hats, Jackets, Shirts,
 * Pants, Gloves, Boots, Accessories, Wild-card). Mirrors CSFloat's "Loadout Lab" feature.
 * The slots themselves live in a child LoadoutSlot table so we don't embed JSON.
 */
@Entity
@Table(name = "loadouts")
@JsonIgnoreProperties(["hibernateLazyInitializer", "handler"])
class Loadout {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id

    @Column(nullable = false)
    Long ownerUserId

    @Column
    String ownerName

    @Column(nullable = false, length = 100)
    String name

    @Column(length = 500)
    String description

    /** PUBLIC (shows in Discover), PRIVATE (owner only). */
    @Column(nullable = false)
    String visibility = "PUBLIC"

    /** Denormalised total of all slot prices for sorting — recalculated on save. */
    @Column(nullable = false, precision = 19, scale = 2)
    BigDecimal totalValue = BigDecimal.ZERO

    /** Number of users who starred this loadout. */
    @Column(nullable = false)
    Integer favorites = 0

    @Column(name = "created_at", nullable = false)
    Long createdAt = System.currentTimeMillis()

    @Column(name = "updated_at", nullable = false)
    Long updatedAt = System.currentTimeMillis()
}
