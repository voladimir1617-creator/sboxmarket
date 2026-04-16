package com.sboxmarket.model

import jakarta.persistence.*

@Entity
@Table(name = "price_history")
class PriceHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id", nullable = false)
    Item item

    @Column(nullable = false, precision = 10, scale = 2)
    BigDecimal price

    @Column(nullable = false)
    Integer volume = 0

    @Column(nullable = false)
    Long recordedAt = System.currentTimeMillis()

    @Column(nullable = false)
    String dayLabel  // "Apr 01", etc.
}
