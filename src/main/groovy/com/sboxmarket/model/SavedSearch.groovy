package com.sboxmarket.model

import jakarta.persistence.*

/**
 * One saved-search preset belonging to a user. Captures every filter
 * the marketplace toolbar exposes; the frontend re-applies the row on
 * click via `applySavedSearch`.
 *
 * Per-user uniqueness on `(userId, name)` is enforced by the DB so
 * re-saving the same preset name overwrites cleanly. Hard cap of 10
 * per user is enforced at the service layer.
 */
@Entity
@Table(name = "saved_searches", indexes = [
    @Index(name = "idx_saved_searches_user", columnList = "userId")
])
class SavedSearch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id

    @Column(name = 'user_id', nullable = false)
    Long userId

    @Column(nullable = false, length = 80)
    String name

    @Column(nullable = false, length = 80)
    String q = ''

    @Column(nullable = false, length = 40)
    String category = 'All'

    @Column(nullable = false, length = 40)
    String rarity = 'All'

    @Column(nullable = false, length = 40)
    String sort = 'price_desc'

    @Column(name = 'min_price', nullable = false, length = 16)
    String minPrice = ''

    @Column(name = 'max_price', nullable = false, length = 16)
    String maxPrice = ''

    // Batch 957 — the rest of the marketplace toolbar. Before these
    // columns existed, saving a rich filter set ("Hats · ≥20% off ·
    // AUCTIONs") stored only the category and silently dropped
    // everything else on re-apply. Flyway V56 added the columns with
    // sensible defaults so pre-957 rows upgrade cleanly.
    @Column(name = 'min_discount_pct', nullable = false)
    Integer minDiscountPct = 0

    @Column(name = 'deals_only', nullable = false)
    Boolean dealsOnly = false

    @Column(name = 'new_only', nullable = false)
    Boolean newOnly = false

    @Column(name = 'affordable_only', nullable = false)
    Boolean affordableOnly = false

    @Column(name = 'listing_type', nullable = false, length = 16)
    String listingType = 'ALL'

    @Column(name = 'created_at', nullable = false)
    Long createdAt = System.currentTimeMillis()
}
