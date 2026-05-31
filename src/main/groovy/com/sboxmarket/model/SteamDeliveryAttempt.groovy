package com.sboxmarket.model

import jakarta.persistence.*

/**
 * Audit row for an automated Steam delivery attempt (send or poll). Written by
 * {@link com.sboxmarket.service.SteamDeliveryService}. Purely informational —
 * escrow/money state lives on Trade and is owned by TradeService.
 *
 * This table is ALSO the authoritative store for the Steam trade-offer id +
 * last-seen offer state per trade, so the delivery orchestrator never has to add
 * a column to the Trade entity (owned by a parallel agent): the newest row whose
 * {@code steamOfferId} is non-null is the live offer for that trade. Backed by
 * the steam_delivery_attempts table (Flyway V200).
 */
@Entity
@Table(name = "steam_delivery_attempts")
class SteamDeliveryAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id

    @Column(name = "trade_id")
    Long tradeId

    @Column(name = "steam_offer_id", length = 64)
    String steamOfferId

    @Column(name = "offer_state", length = 64)
    String offerState

    /** 'SEND' or 'POLL' — which phase produced this row. */
    @Column(name = "phase", length = 16)
    String phase

    @Column(name = "success")
    boolean success = false

    @Column(name = "error_message", length = 500)
    String errorMessage

    @Column(name = "created_at")
    Long createdAt = System.currentTimeMillis()
}
