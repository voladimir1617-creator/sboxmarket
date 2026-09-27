package com.sboxmarket.model

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.persistence.*

@Entity
@Table(name = "price_history")
class PriceHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id

    /** Parent item. @JsonIgnore'd (batch 378) because serialising the
     *  lazy Hibernate proxy outside an open session throws
     *  `could not initialize proxy — no Session`, which bubbled up as
     *  a 500 on `GET /api/items/{id}/history`. The client already
     *  knows which item they're requesting history for; no need to
     *  embed the Item inside each row. */
    @JsonIgnore
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
