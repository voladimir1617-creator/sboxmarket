package com.sboxmarket.model

import jakarta.persistence.*
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * Server-persisted user notifications — replaces the old localStorage-only bell.
 * Events:
 *   TRADE_VERIFIED, AUCTION_ENDING, AUCTION_WON, AUCTION_LOST,
 *   ITEM_PURCHASED, OFFER_RECEIVED, OFFER_ACCEPTED, OFFER_REJECTED,
 *   BUY_ORDER_FILLED, DEPOSIT_COMPLETE, PRICE_DROPPED, WITHDRAWAL_COMPLETE
 */
@Entity
@Table(name = "notifications")
@JsonIgnoreProperties(["hibernateLazyInitializer", "handler"])
class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id

    @Column(nullable = false)
    Long userId

    @Column(nullable = false)
    String kind

    @Column(nullable = false, length = 200)
    String title

    @Column(length = 500)
    String body

    /** Optional id of the related entity (listing id, offer id, transaction id, etc). */
    @Column
    Long refId

    @Column(nullable = false)
    Boolean read = false

    @Column(name = "created_at", nullable = false)
    Long createdAt = System.currentTimeMillis()
}
