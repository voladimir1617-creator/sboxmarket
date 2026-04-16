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

    @Column(nullable = false)
    BigDecimal lowestPrice = BigDecimal.ZERO

    @Column
    BigDecimal steamPrice  // original Steam store price (for discount % display)

    @Column(nullable = false)
    Integer trendPercent = 0  // +/- percent change 30d

    @Column(nullable = false)
    Boolean isListed = true

    @Column(name = "created_at", nullable = false)
    Long createdAt = System.currentTimeMillis()
}
