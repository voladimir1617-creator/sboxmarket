package com.sboxmarket.model

import jakarta.persistence.*
import jakarta.validation.constraints.*
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@Entity
@Table(name = "items")
@JsonIgnoreProperties(["hibernateLazyInitializer", "handler"])
class Item {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id

    @NotBlank
    @Column(nullable = false)
    String name

    @NotBlank
    @Column(nullable = false)
    String category  // Clothing, Hats, Accessories, Workshop

    @NotBlank
    @Column(nullable = false)
    String rarity    // Limited, Off-Market, Standard

    @Column(length = 500)
    String imageUrl

    @Column(nullable = false)
    String iconEmoji = "👕"

    @Column(nullable = false)
    String accentColor = "#1a1a2a"

    @Column(nullable = false)
    Integer supply = 0

    @Column(nullable = false)
    Integer totalSold = 0

    /** Per-item view counter — bumped atomically on every GET /api/items/{id}
     *  read. Drives the "👁 N views" social-proof chip on ItemModal alongside
     *  watcher-count + buy-order-count. Null-default 0 so pre-V46 rows still
     *  hydrate cleanly. Added in V46 / batch 409. */
    @Column(name = 'view_count', nullable = false)
    Long viewCount = 0L

    // Explicit precision = 19, scale = 2 matches every other money column
    // in the project (Wallet.balance, Listing.price, Transaction.amount,
    // Offer.amount, Trade.price, Bid.amount, BuyOrder.maxPrice,
    // TradeProtection.feeAmount, etc.). The DB column is NUMERIC(19,2)
    // via V1__baseline.sql so the live impact is already zero (Postgres
    // clamps on write), but the annotation drift was a code-smell the
    // Hibernate DDL exporter or a future `ddl-auto=update` would
    // surface. Pinned by ItemBigDecimalPrecisionSpec.
    @Column(nullable = false, precision = 19, scale = 2)
    BigDecimal lowestPrice = BigDecimal.ZERO

    @Column(precision = 19, scale = 2)
    BigDecimal steamPrice  // original Steam store price (for discount % display)

    @Column(nullable = false)
    Integer trendPercent = 0  // +/- percent change 30d

    @Column(nullable = false)
    Boolean isListed = false

    @Column(name = "created_at", nullable = false)
    Long createdAt = System.currentTimeMillis()
}
